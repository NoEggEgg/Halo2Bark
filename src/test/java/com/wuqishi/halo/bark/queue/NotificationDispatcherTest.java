package com.wuqishi.halo.bark.queue;

import static org.assertj.core.api.Assertions.assertThat;

import com.wuqishi.halo.bark.config.BarkSettings;
import com.wuqishi.halo.bark.event.SiteEvent;
import com.wuqishi.halo.bark.pipeline.NotificationPipeline;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("通知调度器：投递、优雅关闭与异常隔离")
class NotificationDispatcherTest {

    private static final Supplier<BarkSettings> DEFAULTS = BarkSettings::defaults;

    private static SiteEvent event(String postName) {
        return new SiteEvent.PostChanged(postName, SiteEvent.PostChange.PUBLISHED);
    }

    @Test
    @DisplayName("提交的事件会被处理，且使用入队时刻的配置快照")
    void submitsAndProcesses() throws InterruptedException {
        List<SiteEvent> processed = Collections.synchronizedList(new ArrayList<>());
        List<BarkSettings> received = Collections.synchronizedList(new ArrayList<>());
        var latch = new CountDownLatch(1);
        NotificationPipeline pipeline = (siteEvent, settings) -> {
            processed.add(siteEvent);
            received.add(settings);
            latch.countDown();
        };
        var dispatcher = new NotificationDispatcher(pipeline, DEFAULTS, 10, 1);
        dispatcher.start();
        try {
            assertThat(dispatcher.submit(event("hello"))).isTrue();
            assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(processed).containsExactly(event("hello"));
            assertThat(received).hasSize(1);
        } finally {
            dispatcher.stop();
        }
    }

    @Test
    @DisplayName("停止后提交的事件被丢弃")
    void submitAfterStopIsDropped() {
        List<SiteEvent> processed = Collections.synchronizedList(new ArrayList<>());
        NotificationPipeline pipeline = (siteEvent, settings) -> processed.add(siteEvent);
        var dispatcher = new NotificationDispatcher(pipeline, DEFAULTS, 10, 1);
        dispatcher.start();
        dispatcher.stop();

        assertThat(dispatcher.submit(event("late"))).isFalse();
        assertThat(processed).isEmpty();
    }

    @Test
    @DisplayName("停止会等待在途任务完成后再返回")
    void stopWaitsForInFlightTask() throws InterruptedException {
        List<SiteEvent> processed = Collections.synchronizedList(new ArrayList<>());
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        NotificationPipeline pipeline = (siteEvent, settings) -> {
            started.countDown();
            await(release);
            processed.add(siteEvent);
        };
        var dispatcher = new NotificationDispatcher(pipeline, DEFAULTS, 10, 1);
        dispatcher.start();
        dispatcher.submit(event("inflight"));
        assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

        // 200 ms 后释放在途任务；stop() 应等待其完成后才返回
        new Thread(() -> {
            sleep(200);
            release.countDown();
        }).start();
        dispatcher.stop();

        assertThat(processed).containsExactly(event("inflight"));
    }

    @Test
    @DisplayName("单个任务抛异常不会终止投递线程")
    void pipelineExceptionDoesNotKillWorker() throws InterruptedException {
        var calls = new AtomicInteger();
        var secondProcessed = new CountDownLatch(1);
        List<SiteEvent> processed = Collections.synchronizedList(new ArrayList<>());
        NotificationPipeline pipeline = (siteEvent, settings) -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("boom");
            }
            processed.add(siteEvent);
            secondProcessed.countDown();
        };
        var dispatcher = new NotificationDispatcher(pipeline, DEFAULTS, 10, 1);
        dispatcher.start();
        try {
            dispatcher.submit(event("first"));
            dispatcher.submit(event("second"));
            assertThat(secondProcessed.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(processed).containsExactly(event("second"));
        } finally {
            dispatcher.stop();
        }
    }

    @Test
    @DisplayName("队列满时丢弃并计数，提交不阻塞")
    void overflowDropsAndCounts() throws InterruptedException {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        NotificationPipeline pipeline = (siteEvent, settings) -> {
            started.countDown();
            await(release);
        };
        var dispatcher = new NotificationDispatcher(pipeline, DEFAULTS, 1, 1);
        dispatcher.start();
        try {
            assertThat(dispatcher.submit(event("running"))).isTrue();
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(dispatcher.submit(event("queued"))).isTrue();
            assertThat(dispatcher.submit(event("overflow"))).isTrue();

            assertThat(dispatcher.droppedCount()).isEqualTo(1);
        } finally {
            release.countDown();
            dispatcher.stop();
        }
    }

    @Test
    @DisplayName("停止后再次启动仍可投递（生命周期资源被重建）")
    void restartAfterStopStillDelivers() throws InterruptedException {
        List<SiteEvent> processed = Collections.synchronizedList(new ArrayList<>());
        var secondProcessed = new CountDownLatch(1);
        NotificationPipeline pipeline = (siteEvent, settings) -> {
            processed.add(siteEvent);
            secondProcessed.countDown();
        };
        var dispatcher = new NotificationDispatcher(pipeline, DEFAULTS, 10, 1);
        dispatcher.start();
        dispatcher.stop();
        dispatcher.start();
        try {
            assertThat(dispatcher.submit(event("after-restart"))).isTrue();
            assertThat(secondProcessed.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(processed).containsExactly(event("after-restart"));
        } finally {
            dispatcher.stop();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
