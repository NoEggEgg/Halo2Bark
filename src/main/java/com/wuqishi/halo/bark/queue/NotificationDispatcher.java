package com.wuqishi.halo.bark.queue;

import com.wuqishi.halo.bark.BarkConstants;
import com.wuqishi.halo.bark.Masking;
import com.wuqishi.halo.bark.config.BarkSettings;
import com.wuqishi.halo.bark.event.SiteEvent;
import com.wuqishi.halo.bark.pipeline.NotificationPipeline;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 通知调度器：把事件线程与投递解耦（PRD 5.1、10.4.2）。
 *
 * <p>采集层只调用 {@link #submit(SiteEvent)}，该方法**非阻塞**：它在入队时刻对配置取快照，
 * 把任务放入有界队列后立即返回。真正阻塞的工作（判定规则、链接获取、正文渲染、HTTP 投递）
 * 由专用投递线程池上的 {@link NotificationPipeline} 执行，不占用事件线程或 Reactor 调度线程。
 *
 * <p>生命周期：
 * <ul>
 *   <li>{@link #start()}：启动投递线程池。</li>
 *   <li>{@link #stop()}：停止接收新任务，等待在途任务结束（上限
 *       {@link BarkConstants#SHUTDOWN_WAIT_MILLIS}），关闭线程池；排队未开始的任务直接丢弃
 *       （PRD 5.1：停用期间事件直接丢弃，不补发）。</li>
 * </ul>
 */
@Slf4j
@Component
public class NotificationDispatcher {

    private final NotificationPipeline pipeline;
    private final Supplier<BarkSettings> settings;
    private final int capacity;
    private final int threads;
    private final Object lifecycle = new Object();

    private volatile boolean running;
    private volatile DeliveryQueue queue;
    private volatile ExecutorService workers;

    @Autowired
    public NotificationDispatcher(NotificationPipeline pipeline, Supplier<BarkSettings> settings) {
        this(pipeline, settings, BarkConstants.DELIVERY_QUEUE_CAPACITY,
                BarkConstants.DELIVERY_THREADS);
    }

    /** 供测试注入容量与线程数。 */
    NotificationDispatcher(NotificationPipeline pipeline, Supplier<BarkSettings> settings,
            int capacity, int threads) {
        this.pipeline = pipeline;
        this.settings = settings;
        this.capacity = capacity;
        this.threads = threads;
    }

    /**
     * 启动投递线程池。重复调用无副作用。
     *
     * <p>队列与线程池属于生命周期资源：每次启动都重新创建，因此「停用 → 启用」后
     * 调度器仍可用（不会复用已被 {@code shutdown()} 终止的执行器）。
     */
    public void start() {
        synchronized (lifecycle) {
            if (running) {
                return;
            }
            var newQueue = new DeliveryQueue(capacity);
            var newWorkers = Executors.newFixedThreadPool(threads, new DeliveryThreadFactory());
            queue = newQueue;
            workers = newWorkers;
            running = true;
            for (int i = 0; i < threads; i++) {
                newWorkers.submit(this::consume);
            }
            log.info("{} 投递线程池已启动：线程 {} 个，队列上限 {}", BarkConstants.LOG_PREFIX, threads,
                    capacity);
        }
    }

    /**
     * 优雅关闭：停止接收新任务，等待在途任务结束（上限
     * {@link BarkConstants#SHUTDOWN_WAIT_MILLIS}），关闭线程池。
     */
    public void stop() {
        synchronized (lifecycle) {
            if (!running) {
                return;
            }
            running = false;
            var currentQueue = queue;
            var currentWorkers = workers;
            int abandoned = currentQueue == null ? 0 : currentQueue.close();
            if (currentWorkers != null) {
                currentWorkers.shutdown();
                if (!awaitTermination(currentWorkers)) {
                    currentWorkers.shutdownNow();
                    log.warn("{} 等待在途投递任务超时（{} ms），已强制中断", BarkConstants.LOG_PREFIX,
                            BarkConstants.SHUTDOWN_WAIT_MILLIS);
                    // 强制中断后必须再等一次，否则本方法可能在投递线程真正退出前返回
                    awaitTermination(currentWorkers);
                }
            }
            log.info("{} 投递线程池已停止：放弃排队任务 {} 条，累计溢出丢弃 {} 条",
                    BarkConstants.LOG_PREFIX, abandoned,
                    currentQueue == null ? 0 : currentQueue.droppedCount());
        }
    }

    /**
     * 提交一条站点事件；非阻塞。
     *
     * @return 是否成功入队（队列已满而丢弃新任务、或调度器未运行时为 {@code false}）
     */
    public boolean submit(SiteEvent event) {
        var currentQueue = queue;
        if (event == null || !running || currentQueue == null) {
            log.debug("{} 调度器未运行，丢弃事件", BarkConstants.LOG_PREFIX);
            return false;
        }
        var result = currentQueue.offer(new DeliveryTask(event, settings.get()));
        switch (result) {
            case ENQUEUED -> {
                // 正常路径，不记录日志
            }
            case DROPPED_OLDEST -> log.error("{} 投递队列已满，丢弃优先级最低的最旧任务（累计丢弃 {} 条）",
                    BarkConstants.LOG_PREFIX, currentQueue.droppedCount());
            case DROPPED_INCOMING -> log.error("{} 投递队列已满，丢弃本条事件（累计丢弃 {} 条）",
                    BarkConstants.LOG_PREFIX, currentQueue.droppedCount());
            case CLOSED -> log.debug("{} 投递队列已关闭，丢弃事件", BarkConstants.LOG_PREFIX);
        }
        return result == DeliveryQueue.Result.ENQUEUED
                || result == DeliveryQueue.Result.DROPPED_OLDEST;
    }

    /** 溢出丢弃累计计数（PRD 10.5）。 */
    long droppedCount() {
        var currentQueue = queue;
        return currentQueue == null ? 0 : currentQueue.droppedCount();
    }

    private void consume() {
        var currentQueue = queue;
        while (true) {
            DeliveryTask task;
            try {
                task = currentQueue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (task == null) {
                return;
            }
            deliver(task);
        }
    }

    private void deliver(DeliveryTask task) {
        try {
            pipeline.process(task.event(), task.settings());
        } catch (RuntimeException e) {
            // 单个任务失败不得终止投递线程，也不得抛给事件总线（PRD 10.4.3）
            log.warn("{} 投递任务执行异常：{}", BarkConstants.LOG_PREFIX, Masking.sanitize(e.toString()));
        }
    }

    private static boolean awaitTermination(ExecutorService pool) {
        try {
            return pool.awaitTermination(BarkConstants.SHUTDOWN_WAIT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 守护线程工厂，避免投递线程阻止 JVM 退出。 */
    private static final class DeliveryThreadFactory implements ThreadFactory {

        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            var thread = new Thread(runnable,
                    BarkConstants.DELIVERY_THREAD_NAME_PREFIX + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
