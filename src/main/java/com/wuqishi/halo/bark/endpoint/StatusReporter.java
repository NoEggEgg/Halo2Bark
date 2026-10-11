package com.wuqishi.halo.bark.endpoint;

import com.wuqishi.halo.bark.BarkConstants;
import com.wuqishi.halo.bark.Masking;
import com.wuqishi.halo.bark.config.SettingsStore;
import com.wuqishi.halo.bark.pipeline.EventPipeline;
import com.wuqishi.halo.bark.pipeline.NoiseReducer;
import com.wuqishi.halo.bark.queue.NotificationDispatcher;
import com.wuqishi.halo.bark.transport.BarkTransport;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 运行状态汇总：把各层的只读计数聚合成 {@link StatusSnapshot}，并周期输出「有变化才说话」的汇总日志。
 *
 * <p>两条出口：
 * <ul>
 *   <li>{@link #snapshot()}——供 {@link BarkStatusEndpoint} 暴露为只读接口；</li>
 *   <li>{@link #start()}——每 {@link #SUMMARY_INTERVAL} 检查一次丢弃 / 失败计数，
 *       <b>只有出现新增才输出 WARN</b>；一切正常时保持安静，避免周期日志变成噪音。</li>
 * </ul>
 *
 * <p>读取的全部是 volatile 计数与不可变快照，因此 {@link #snapshot()} 不阻塞，可在 Reactor 线程调用。
 */
@Slf4j
@Component
public class StatusReporter {

    /** 周期汇总间隔。 */
    static final Duration SUMMARY_INTERVAL = Duration.ofMinutes(30);

    private final SettingsStore settingsStore;
    private final NotificationDispatcher dispatcher;
    private final NoiseReducer noiseReducer;
    private final EventPipeline pipeline;
    private final BarkTransport transport;

    private final Object lifecycle = new Object();
    private volatile ScheduledExecutorService summariser;

    /** 上次汇总时的累计计数，用于只报告「新增」的丢弃与失败。 */
    private volatile Counters lastReported = new Counters(0, 0, 0, 0);

    public StatusReporter(SettingsStore settingsStore, NotificationDispatcher dispatcher,
            NoiseReducer noiseReducer, EventPipeline pipeline, BarkTransport transport) {
        this.settingsStore = settingsStore;
        this.dispatcher = dispatcher;
        this.noiseReducer = noiseReducer;
        this.pipeline = pipeline;
        this.transport = transport;
    }

    /** 当前运行状态快照。 */
    public StatusSnapshot snapshot() {
        var settings = settingsStore.current();
        var push = settings.push();
        return new StatusSnapshot(
                settings.valid(),
                settings.issues(),
                settings.warnings(),
                push.devices().size(),
                push.deliverableDevices().size(),
                new StatusSnapshot.Dispatcher(dispatcher.running(), dispatcher.queueSize(),
                        dispatcher.droppedCount()),
                new StatusSnapshot.Noise(noiseReducer.running(), noiseReducer.overflowSize(),
                        noiseReducer.droppedOverflowCount(), noiseReducer.quietBufferSize(),
                        noiseReducer.droppedQuietCount()),
                pipeline.failedEventCount(),
                transport.lastDelivery().orElse(null));
    }

    /** 启动周期汇总。重复调用无副作用；停止后可再次启动。 */
    public void start() {
        synchronized (lifecycle) {
            if (summariser != null && !summariser.isShutdown()) {
                return;
            }
            lastReported = countersOf(snapshot());
            summariser = Executors.newSingleThreadScheduledExecutor(runnable -> {
                var thread = new Thread(runnable,
                        BarkConstants.DELIVERY_THREAD_NAME_PREFIX + "status-");
                thread.setDaemon(true);
                return thread;
            });
            summariser.scheduleWithFixedDelay(this::reportIfChanged,
                    SUMMARY_INTERVAL.toMillis(), SUMMARY_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    /** 停止周期汇总。 */
    public void stop() {
        synchronized (lifecycle) {
            var current = summariser;
            if (current != null) {
                current.shutdownNow();
            }
        }
    }

    /** 只在出现**新增**丢弃或失败时输出汇总；无变化时保持安静。 */
    private void reportIfChanged() {
        try {
            var current = snapshot();
            var counters = countersOf(current);
            var previous = lastReported;
            lastReported = counters;

            var queueDropped = counters.queueDropped() - previous.queueDropped();
            var overflowDropped = counters.overflowDropped() - previous.overflowDropped();
            var quietDropped = counters.quietDropped() - previous.quietDropped();
            var failed = counters.failedEvents() - previous.failedEvents();
            if (queueDropped + overflowDropped + quietDropped + failed == 0) {
                return;
            }
            log.warn("{} 近 {} 分钟出现丢弃或失败：投递队列 {} 条、限流溢出 {} 条、免打扰 {} 条、"
                            + "事件处理失败 {} 条。当前队列深度 {}、限流缓冲 {}、免打扰缓冲 {}、"
                            + "可投递设备 {}/{} 台",
                    BarkConstants.LOG_PREFIX, SUMMARY_INTERVAL.toMinutes(), queueDropped, overflowDropped,
                    quietDropped, failed, current.dispatcher().queueDepth(),
                    current.noise().overflowBuffered(), current.noise().quietBuffered(),
                    current.deliverableDeviceCount(), current.deviceCount());
        } catch (RuntimeException e) {
            log.warn("{} 状态汇总失败：{}", BarkConstants.LOG_PREFIX, Masking.sanitize(e.toString()));
        }
    }

    private static Counters countersOf(StatusSnapshot snapshot) {
        return new Counters(snapshot.dispatcher().dropped(), snapshot.noise().overflowDropped(),
                snapshot.noise().quietDropped(), snapshot.failedEvents());
    }

    /** 参与「新增」比较的累计计数。 */
    private record Counters(long queueDropped, long overflowDropped, long quietDropped,
            long failedEvents) {
    }
}
