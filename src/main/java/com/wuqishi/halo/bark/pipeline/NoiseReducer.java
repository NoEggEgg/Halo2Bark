package com.wuqishi.halo.bark.pipeline;

import com.wuqishi.halo.bark.BarkConstants;
import com.wuqishi.halo.bark.Masking;
import com.wuqishi.halo.bark.config.PushSettings;
import com.wuqishi.halo.bark.push.NotificationCategory;
import com.wuqishi.halo.bark.push.PushNotification;
import com.wuqishi.halo.bark.transport.NotificationDelivery;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 降噪层状态机（PRD 5.4）：安全穿透 → 静默暂存 → 合并 → 限流。
 *
 * <p>处理顺序不可调换：
 * <ul>
 *   <li><b>安全事件</b>直接送达，不参与合并、不计入限流、不受静默约束（PRD 5.4 优先级）；</li>
 *   <li><b>静默时段</b>内互动/普通类暂存，时段结束合并为一条摘要；</li>
 *   <li><b>合并</b>委托 {@link MergeWindowTracker}——按判定语义分桶，正常间隔的通知零延迟，
 *       只有真正的爆发才合并；</li>
 *   <li><b>限流</b>每自然分钟最多 {@value #RATE_LIMIT_PER_MINUTE} 条，超出的暂存，
 *       该分钟结束时补发一条聚合摘要，**不丢弃**。</li>
 * </ul>
 *
 * <p>合并/限流/静默为内建行为，不提供开关；可配置的只有合并窗口与静默时段区间。
 *
 * <p>线程约定：{@link #accept} 由投递线程调用；冲刷由定时器驱动，但**阻塞投递一律转交专用
 * 执行器**，不在定时器线程执行 I/O。
 */
@Slf4j
@Component
public class NoiseReducer {

    /** 冲刷扫描间隔：1 秒，足以覆盖窗口 / 分钟 / 静默的边界。 */
    static final Duration SWEEP_INTERVAL = Duration.ofSeconds(1);

    /** 每分钟配额（PRD 5.4）。 */
    static final int RATE_LIMIT_PER_MINUTE = 20;

    /** 免打扰缓冲上限：避免超长静默时段叠加高事件量导致无界增长。 */
    static final int QUIET_BUFFER_CAPACITY = 500;

    /**
     * 限流溢出缓冲上限。
     *
     * <p>该缓冲必须有界：限流配额耗尽期间产生的每一条通知都会按条堆积，前置投递队列的
     * 容量限制管不到这里，评论持续增长或流量突增时会持续占用 Halo 内存、影响站点稳定性。
     * 达到上限后丢弃最旧的 1 条，与投递队列在压力下丢弃最旧任务保持一致。
     */
    static final int OVERFLOW_CAPACITY = 500;

    /**
     * 降噪补发任务队列上限。
     *
     * <p>补发（合并摘要 / 限流溢出 / 免打扰时段结束）由专用执行器承担，若队列无界，
     * 冲刷跟不上时任务会无限堆积。因此使用有界队列，超限时丢弃并记录（补发是尽力而为，
     * 丢弃不影响后续投递）。
     */
    static final int DEFERRED_QUEUE_CAPACITY = 200;

    /**
     * 补发执行器线程数。
     *
     * <p>补发任务内部会发起**阻塞 HTTP**（含重试，单条最长可达数十秒）。单线程会让所有摘要相互
     * 串行：一台慢服务端就能把队列堆满，而被丢弃的恰恰是「把 N 条压成 1 条」的聚合通知。
     * 因此用少量线程并行，使个别慢请求不阻塞其余摘要。
     */
    static final int DEFERRED_THREADS = 2;

    private final NotificationDelivery delivery;
    private final Clock clock;
    private final RateLimiter rateLimiter;
    private final MergeWindowTracker mergeWindows;
    private final int quietBufferCapacity;
    private final int overflowCapacity;
    private final Object lifecycle = new Object();

    /** 生命周期资源：每次 {@link #start()} 都会在已终止时重建，保证停用后可再次启用。 */
    private volatile ScheduledExecutorService sweeper;
    private volatile ExecutorService deferred;

    private final Object lock = new Object();
    private final List<BufferedNotification> overflow = new ArrayList<>();
    private final List<BufferedNotification> quietBuffer = new ArrayList<>();
    private long overflowMinute = Long.MIN_VALUE;

    /** 缓冲溢出被丢弃的累计条数：丢弃必须可观测，不能静默发生。 */
    private final AtomicLong droppedOverflow = new AtomicLong();

    /** 免打扰缓冲因达到上限而被丢弃的累计条数（与 {@link #droppedOverflow} 同一口径）。 */
    private final AtomicLong droppedQuiet = new AtomicLong();

    private volatile boolean running;

    /** 缓冲达到上限的告警是否已打印过（避免逐条刷屏，缓冲排空后复位）。 */
    private volatile boolean overflowCapWarned;

    /** 免打扰缓冲达到上限的告警是否已打印过（避免长静默时段逐条刷屏）。 */
    private volatile boolean quietCapWarned;

    @Autowired
    public NoiseReducer(NotificationDelivery delivery) {
        this(delivery, Clock.systemDefaultZone());
    }

    NoiseReducer(NotificationDelivery delivery, Clock clock) {
        this(delivery, clock, RATE_LIMIT_PER_MINUTE);
    }

    /** 供测试注入更小的配额，避免用 20 条事件去逼近阈值。 */
    NoiseReducer(NotificationDelivery delivery, Clock clock, int rateLimitPerMinute) {
        this(delivery, clock, rateLimitPerMinute, QUIET_BUFFER_CAPACITY);
    }

    /** 供测试注入更低的免打扰缓冲上限，便于逼近阈值。 */
    NoiseReducer(NotificationDelivery delivery, Clock clock, int rateLimitPerMinute,
            int quietBufferCapacity) {
        this(delivery, clock, rateLimitPerMinute, quietBufferCapacity, OVERFLOW_CAPACITY);
    }

    /** 供测试注入更低的缓冲上限，便于逼近阈值（免打扰 / 限流溢出）。 */
    NoiseReducer(NotificationDelivery delivery, Clock clock, int rateLimitPerMinute,
            int quietBufferCapacity, int overflowCapacity) {
        this.delivery = delivery;
        this.clock = clock;
        this.rateLimiter = new RateLimiter(rateLimitPerMinute, clock);
        this.mergeWindows = new MergeWindowTracker(clock);
        this.quietBufferCapacity = quietBufferCapacity;
        this.overflowCapacity = overflowCapacity;
        this.sweeper = Executors.newSingleThreadScheduledExecutor(
                daemonFactory(BarkConstants.DELIVERY_THREAD_NAME_PREFIX + "noise-"));
        this.deferred = newDeferredExecutor();
    }

    /**
     * 补发执行器：**有界**队列。
     *
     * <p>冲刷（合并摘要 / 限流溢出 / 免打扰结束）跟不上时，无界队列会让补发任务无限堆积；
     * 这里限定上限并在超限时丢弃该次补发任务（补发是尽力而为，丢弃不影响后续投递的正确性，
     * 但必须记录，不能静默发生）。
     */
    private static ExecutorService newDeferredExecutor() {
        return new ThreadPoolExecutor(DEFERRED_THREADS, DEFERRED_THREADS, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(DEFERRED_QUEUE_CAPACITY),
                daemonFactory(BarkConstants.DELIVERY_THREAD_NAME_PREFIX + "noise-flush-"),
                (task, executor) -> log.warn("{} 降噪补发队列已满（上限 {} 个任务），丢弃本次补发",
                        BarkConstants.LOG_PREFIX, DEFERRED_QUEUE_CAPACITY));
    }

    /** 启动冲刷定时器。重复调用无副作用；停止后可再次启动（执行器会被重建）。 */
    public void start() {
        synchronized (lifecycle) {
            if (running) {
                return;
            }
            if (sweeper == null || sweeper.isShutdown()) {
                sweeper = Executors.newSingleThreadScheduledExecutor(
                        daemonFactory(BarkConstants.DELIVERY_THREAD_NAME_PREFIX + "noise-"));
            }
            if (deferred == null || deferred.isShutdown()) {
                deferred = newDeferredExecutor();
            }
            running = true;
            sweeper.scheduleWithFixedDelay(this::sweep, SWEEP_INTERVAL.toMillis(),
                    SWEEP_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
            log.info("{} 降噪层已启动：限流 {} 条/分钟，合并阈值 {} 条", BarkConstants.LOG_PREFIX,
                    RATE_LIMIT_PER_MINUTE, MergeWindowTracker.MERGE_THRESHOLD);
        }
    }

    /**
     * 停止冲刷定时器与冲刷执行器。
     *
     * <p>仍在暂存的条目一律**丢弃**并计数记录（PRD 5.1：停用期间不补发）。
     * 其中免打扰暂存若在此刻补发，反而会违背用户设置的免打扰意图，故不做补发；
     * 但丢弃必须显式可观测，不能静默发生。
     */
    public void stop() {
        synchronized (lifecycle) {
            if (!running) {
                return;
            }
            running = false;
            int pendingQuiet;
            int pendingOverflow;
            synchronized (lock) {
                pendingQuiet = quietBuffer.size();
                pendingOverflow = overflow.size();
                quietBuffer.clear();
                overflow.clear();
                overflowMinute = Long.MIN_VALUE;
                overflowCapWarned = false;
                quietCapWarned = false;
            }
            int pendingMerge = mergeWindows.discardPending();
            if (pendingQuiet + pendingOverflow + pendingMerge > 0) {
                log.warn("{} 降噪层停止，丢弃暂存通知：免打扰 {} 条、限流溢出 {} 条、合并窗口 {} 条",
                        BarkConstants.LOG_PREFIX, pendingQuiet, pendingOverflow, pendingMerge);
            }
            var currentSweeper = sweeper;
            var currentDeferred = deferred;
            if (currentSweeper != null) {
                currentSweeper.shutdownNow();
            }
            if (currentDeferred != null) {
                currentDeferred.shutdown();
                try {
                    if (!currentDeferred.awaitTermination(BarkConstants.SHUTDOWN_WAIT_MILLIS,
                            TimeUnit.MILLISECONDS)) {
                        currentDeferred.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    currentDeferred.shutdownNow();
                }
            }
            log.info("{} 降噪层已停止", BarkConstants.LOG_PREFIX);
        }
    }

    /**
     * 接收一条已适配的通知。
     *
     * <p>安全类直接送达；其余按静默 → 限流 → 合并处理。
     */
    public void accept(Judgement judgement, PushNotification notification, PushSettings settings) {
        if (notification.category() == NotificationCategory.SECURITY) {
            // 安全类不参与合并、不计入限流、不受静默约束（PRD 5.4）
            deliver(notification, settings);
            return;
        }
        if (isQuiet(settings)) {
            synchronized (lock) {
                if (quietBuffer.size() >= quietBufferCapacity) {
                    // 超出上限丢弃最旧的 1 条：与投递队列在压力下丢弃最旧任务保持一致，
                    // 避免超长静默时段叠加高事件量导致缓冲无界增长。
                    // 告警只打首次，其余降为 debug——长静默时段内逐条 WARN 会淹没日志。
                    quietBuffer.remove(0);
                    long dropped = droppedQuiet.incrementAndGet();
                    if (!quietCapWarned) {
                        quietCapWarned = true;
                        log.warn("{} 免打扰缓冲已达上限 {} 条，丢弃最旧的 1 条以免无界增长"
                                        + "（累计丢弃 {} 条）",
                                BarkConstants.LOG_PREFIX, quietBufferCapacity, dropped);
                    } else {
                        log.debug("{} 免打扰缓冲已满，丢弃最旧的 1 条（累计丢弃 {} 条）",
                                BarkConstants.LOG_PREFIX, dropped);
                    }
                }
                quietBuffer.add(new BufferedNotification(judgement, notification, settings));
            }
            log.debug("{} 免打扰时段，暂存待补发：{}", BarkConstants.LOG_PREFIX, notification.title());
            return;
        }
        merge(judgement, notification, settings);
    }

    /** 交给合并窗口跟踪器，并按返回的动作立即送达或产出合并摘要。 */
    private void merge(Judgement judgement, PushNotification notification, PushSettings settings) {
        var action = mergeWindows.submit(judgement,
                new BufferedNotification(judgement, notification, settings), settings);
        if (action.immediate() != null) {
            deliverCharged(action.immediate());
        }
        // submit() 抢占了冲刷的工作时，到期的单条由这里按原样补发
        for (var single : action.expiredSingles()) {
            deliverCharged(single);
        }
        if (action.merged() != null) {
            var items = action.merged();
            sendOnFlushThread(() -> sendSummaries(items,
                    (group, groupSettings) -> SummaryComposer.merged(group.get(0).judgement(), group,
                            groupSettings.contentMaxLengthOrDefault()),
                    "合并"));
        }
    }

    /** 冲刷扫描；包级可见以便单测直接驱动（无需等待真实定时器）。 */
    void sweep() {
        try {
            flushExpiredMergeWindows();
            flushOverflow();
            flushQuietBufferIfEnded();
        } catch (RuntimeException e) {
            log.warn("{} 降噪冲刷异常：{}", BarkConstants.LOG_PREFIX, Masking.sanitize(e.toString()));
        }
    }

    private void flushExpiredMergeWindows() {
        var expired = mergeWindows.drainExpired();
        for (var entry : expired.merged()) {
            var items = entry.getValue();
            sendOnFlushThread(() -> sendSummaries(items,
                    (group, groupSettings) -> SummaryComposer.merged(group.get(0).judgement(), group,
                            groupSettings.contentMaxLengthOrDefault()),
                    "窗口结束，合并"));
        }
        for (var single : expired.singles()) {
            sendOnFlushThread(() -> deliverCharged(single));
        }
    }

    private void flushOverflow() {
        List<BufferedNotification> toFlush = null;
        synchronized (lock) {
            if (!overflow.isEmpty() && rateLimiter.currentMinute() != overflowMinute) {
                toFlush = List.copyOf(overflow);
                overflow.clear();
                overflowMinute = Long.MIN_VALUE;
                // 缓冲已排空：复位告警，使其下次再次达到上限时仍会提示
                overflowCapWarned = false;
            }
        }
        if (toFlush == null) {
            return;
        }
        var items = toFlush;
        sendOnFlushThread(() -> sendSummaries(items,
                (group, groupSettings) -> SummaryComposer.overflow(group,
                        groupSettings.contentMaxLengthOrDefault()),
                "限流溢出"));
    }

    private void flushQuietBufferIfEnded() {
        List<BufferedNotification> toFlush = null;
        synchronized (lock) {
            if (!quietBuffer.isEmpty()) {
                // 逐条按**自身的**配置快照判断是否仍在免打扰，避免跨配置更新相互牵连
                var ready = new ArrayList<BufferedNotification>();
                var stillQuiet = new ArrayList<BufferedNotification>();
                for (var item : quietBuffer) {
                    if (isQuiet(item.settings())) {
                        stillQuiet.add(item);
                    } else {
                        ready.add(item);
                    }
                }
                if (!ready.isEmpty()) {
                    toFlush = List.copyOf(ready);
                    quietBuffer.clear();
                    quietBuffer.addAll(stillQuiet);
                    if (stillQuiet.isEmpty()) {
                        // 缓冲已排空：复位告警，使其下次再次达到上限时仍会提示
                        quietCapWarned = false;
                    }
                }
            }
        }
        if (toFlush == null) {
            return;
        }
        var items = toFlush;
        sendOnFlushThread(() -> sendSummaries(items,
                (group, groupSettings) -> SummaryComposer.quiet(group,
                        groupSettings.contentMaxLengthOrDefault()),
                "免打扰时段结束"));
    }

    /**
     * 按配置快照分组产出并送达摘要。
     *
     * <p>每条暂存项都带着入队时刻的快照；跨配置更新的批次可能对应不同的可投递设备集合，
     * 若统一取第一条的快照会漏发设备或用了错误的传输参数，因此按快照分组、各组独立成摘要。
     */
    private void sendSummaries(List<BufferedNotification> items,
            BiFunction<List<BufferedNotification>, PushSettings, PushNotification> composer,
            String reason) {
        for (var group : groupBySettings(items)) {
            var settings = group.get(0).settings();
            var summary = composer.apply(group, settings);
            log.info("{} {} {} 条为一条摘要：{}", BarkConstants.LOG_PREFIX, reason, group.size(),
                    summary.title());
            deliverCharged(new BufferedNotification(group.get(0).judgement(), summary, settings));
        }
    }

    private static List<List<BufferedNotification>> groupBySettings(
            List<BufferedNotification> items) {
        // 按「配置快照 + 类别」分组：类别决定 Bark 的级别与通知分组。混合批次若只取第一条的类别，
        // 会让普通事件被当成互动事件、或反之，绕开用户按类别分组的偏好。合并桶内判定本就一致，
        // 按类别分组不改变合并摘要；限流 / 静默摘要则按类别各自成摘要。
        var groups = new LinkedHashMap<SettingsAndCategory, List<BufferedNotification>>();
        for (var item : items) {
            var key = new SettingsAndCategory(item.settings(), item.notification().category());
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(item);
        }
        return new ArrayList<>(groups.values());
    }

    /** 分组键：配置快照与通知类别（类别决定 Bark 级别与分组）。 */
    private record SettingsAndCategory(PushSettings settings, NotificationCategory category) {
    }

    /**
     * 非安全类投递：先占用本分钟配额；占不到则暂存到溢出缓冲，留到下一分钟补发聚合摘要
     * （PRD 5.4：超出配额的部分不丢弃）。
     */
    private void deliverCharged(BufferedNotification item) {
        if (!rateLimiter.tryAcquire()) {
            synchronized (lock) {
                if (overflow.isEmpty()) {
                    overflowMinute = rateLimiter.currentMinute();
                }
                if (overflow.size() >= overflowCapacity) {
                    // 达到上限：丢弃最旧的 1 条，保证内存有界（与投递队列在压力下丢弃最旧任务一致）
                    overflow.remove(0);
                    long dropped = droppedOverflow.incrementAndGet();
                    if (!overflowCapWarned) {
                        overflowCapWarned = true;
                        log.warn("{} 限流溢出缓冲已达上限 {} 条，丢弃最旧的 1 条以免无界增长"
                                        + "（累计丢弃 {} 条）",
                                BarkConstants.LOG_PREFIX, overflowCapacity, dropped);
                    } else {
                        log.debug("{} 限流溢出缓冲已满，丢弃最旧的 1 条（累计丢弃 {} 条）",
                                BarkConstants.LOG_PREFIX, dropped);
                    }
                }
                overflow.add(item);
            }
            log.debug("{} 本分钟配额已满，暂存待聚合：{}", BarkConstants.LOG_PREFIX,
                    item.notification().title());
            return;
        }
        deliver(item.notification(), item.settings());
    }

    /** 限流溢出缓冲因达到上限而被丢弃的累计条数（供状态端点与测试读取）。 */
    public long droppedOverflowCount() {
        return droppedOverflow.get();
    }

    /** 免打扰缓冲因达到上限而被丢弃的累计条数（供状态端点与测试读取）。 */
    public long droppedQuietCount() {
        return droppedQuiet.get();
    }

    /** 当前限流溢出缓冲的条目数（供测试断言其有界）。 */
    public int overflowSize() {
        synchronized (lock) {
            return overflow.size();
        }
    }

    /** 当前免打扰缓冲的条目数（供测试断言其有界）。 */
    public int quietBufferSize() {
        synchronized (lock) {
            return quietBuffer.size();
        }
    }

    /** 降噪层是否在运行（供状态端点读取）。 */
    public boolean running() {
        return running;
    }

    private void sendOnFlushThread(Runnable task) {
        var executor = deferred;
        if (executor == null || executor.isShutdown()) {
            log.warn("{} 降噪补发被拒绝（执行器未就绪或已关闭）", BarkConstants.LOG_PREFIX);
            return;
        }
        try {
            executor.execute(() -> {
                try {
                    task.run();
                } catch (RuntimeException e) {
                    log.warn("{} 降噪补发异常：{}", BarkConstants.LOG_PREFIX,
                            Masking.sanitize(e.toString()));
                }
            });
        } catch (RuntimeException e) {
            log.warn("{} 降噪补发被拒绝（执行器已关闭）", BarkConstants.LOG_PREFIX);
        }
    }

    /** 直接送达，不占配额（安全类专用；其余走 {@link #deliverCharged(BufferedNotification)}）。 */
    private void deliver(PushNotification notification, PushSettings settings) {
        try {
            delivery.send(notification, settings, settings.deliverableDevices());
        } catch (RuntimeException e) {
            log.warn("{} 降噪层投递异常：{}", BarkConstants.LOG_PREFIX, Masking.sanitize(e.toString()));
        }
    }

    private boolean isQuiet(PushSettings settings) {
        return Boolean.TRUE.equals(settings.quietHoursEnabled())
                && QuietHours.isQuiet(LocalTime.now(clock), settings.quietHoursFrom(),
                        settings.quietHoursTo());
    }

    private static ThreadFactory daemonFactory(String prefix) {
        var counter = new AtomicInteger();
        return runnable -> {
            var thread = new Thread(runnable, prefix + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
