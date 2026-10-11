package com.wuqishi.halo.bark.queue;

import static org.assertj.core.api.Assertions.assertThat;

import com.wuqishi.halo.bark.config.BarkSettings;
import com.wuqishi.halo.bark.event.SiteEvent;
import com.wuqishi.halo.bark.pipeline.NotificationPipeline;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 投递线程的韧性契约：可恢复错误（{@link StackOverflowError}）不得永久损耗消费者。
 *
 * <p>{@code Error} 会穿透 {@code deliver} 与消费循环；线程池补出的新线程只会阻塞在空队列上，
 * 因此若不重建循环，每发生一次就永久少一个消费者。本测试锁定「重建后仍能继续投递」。
 */
@DisplayName("投递线程：可恢复错误后重建消费循环")
class NotificationDispatcherRecoveryTest {

    @Test
    @DisplayName("首个任务抛出 StackOverflowError 后，后续任务仍被正常投递")
    void consumerRecoversFromRecoverableError() throws Exception {
        var attempts = new AtomicInteger();
        var delivered = new CountDownLatch(1);
        NotificationPipeline pipeline = (event, settings) -> {
            if (attempts.incrementAndGet() == 1) {
                throw new StackOverflowError("模拟可恢复错误");
            }
            delivered.countDown();
        };
        var dispatcher = new NotificationDispatcher(pipeline, BarkSettings::defaults, 16, 1);
        dispatcher.start();
        try {
            // 第一条触发可恢复错误，第二条用于验证消费者已被重建
            dispatcher.submit(new SiteEvent.PostChanged("p-1", SiteEvent.PostChange.PUBLISHED));
            dispatcher.submit(new SiteEvent.PostChanged("p-2", SiteEvent.PostChange.PUBLISHED));

            assertThat(delivered.await(5, TimeUnit.SECONDS))
                    .as("消费者必须重建循环，否则该线程退出后队列中的任务再无人处理")
                    .isTrue();
            assertThat(dispatcher.recoveredErrorCount()).isEqualTo(1);
        } finally {
            dispatcher.stop();
        }
    }
}
