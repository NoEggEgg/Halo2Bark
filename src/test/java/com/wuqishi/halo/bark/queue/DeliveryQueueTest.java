package com.wuqishi.halo.bark.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.wuqishi.halo.bark.config.BarkSettings;
import com.wuqishi.halo.bark.event.SiteEvent;
import com.wuqishi.halo.bark.push.NotificationCategory;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("投递队列：优先级、容量与溢出")
class DeliveryQueueTest {

    /** 构造一条带指定优先级、并以 {@code name} 标识的任务。 */
    private static DeliveryTask task(NotificationCategory category, String name) {
        return new DeliveryTask(event(category, name), BarkSettings.defaults());
    }

    private static SiteEvent event(NotificationCategory category, String name) {
        return switch (category) {
            case SECURITY -> new SiteEvent.DeviceCreated(name, null, null, null, null, null, null);
            case INTERACTIVE -> new SiteEvent.CommentCreated(name, null, null, null, null, null, false, null);
            case NORMAL -> new SiteEvent.PostChanged(name, SiteEvent.PostChange.PUBLISHED);
        };
    }

    private static String nameOf(DeliveryTask task) {
        return switch (task.event()) {
            case SiteEvent.DeviceCreated e -> e.deviceName();
            case SiteEvent.CommentCreated e -> e.commentName();
            case SiteEvent.PostChanged e -> e.postName();
            default -> throw new IllegalStateException("测试未覆盖的事件类型");
        };
    }

    @Test
    @DisplayName("容量必须为正数")
    void rejectsNonPositiveCapacity() {
        assertThatThrownBy(() -> new DeliveryQueue(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Nested
    @DisplayName("出队顺序")
    class Ordering {

        @Test
        @DisplayName("同优先级按入队顺序出队（FIFO）")
        void samePriorityIsFifo() throws InterruptedException {
            var queue = new DeliveryQueue(10);
            queue.offer(task(NotificationCategory.NORMAL, "a"));
            queue.offer(task(NotificationCategory.NORMAL, "b"));

            assertThat(nameOf(queue.take())).isEqualTo("a");
            assertThat(nameOf(queue.take())).isEqualTo("b");
        }

        @Test
        @DisplayName("安全 > 互动 > 普通")
        void higherPriorityDequeuedFirst() throws InterruptedException {
            var queue = new DeliveryQueue(10);
            queue.offer(task(NotificationCategory.NORMAL, "n"));
            queue.offer(task(NotificationCategory.INTERACTIVE, "i"));
            queue.offer(task(NotificationCategory.SECURITY, "s"));

            assertThat(nameOf(queue.take())).isEqualTo("s");
            assertThat(nameOf(queue.take())).isEqualTo("i");
            assertThat(nameOf(queue.take())).isEqualTo("n");
        }
    }

    @Nested
    @DisplayName("溢出")
    class Overflow {

        @Test
        @DisplayName("满队列入队同级任务：丢弃最旧的一条并计数")
        void dropsOldestWhenFull() throws InterruptedException {
            var queue = new DeliveryQueue(2);
            assertThat(queue.offer(task(NotificationCategory.NORMAL, "a")))
                    .isEqualTo(DeliveryQueue.Result.ENQUEUED);
            assertThat(queue.offer(task(NotificationCategory.NORMAL, "b")))
                    .isEqualTo(DeliveryQueue.Result.ENQUEUED);
            assertThat(queue.offer(task(NotificationCategory.NORMAL, "c")))
                    .isEqualTo(DeliveryQueue.Result.DROPPED_OLDEST);

            assertThat(queue.size()).isEqualTo(2);
            assertThat(queue.droppedCount()).isEqualTo(1);
            assertThat(nameOf(queue.take())).isEqualTo("b");
            assertThat(nameOf(queue.take())).isEqualTo("c");
        }

        @Test
        @DisplayName("新任务优先级最低时丢弃新任务本身")
        void dropsIncomingWhenLeastImportant() throws InterruptedException {
            var queue = new DeliveryQueue(1);
            queue.offer(task(NotificationCategory.SECURITY, "s"));

            assertThat(queue.offer(task(NotificationCategory.NORMAL, "n")))
                    .isEqualTo(DeliveryQueue.Result.DROPPED_INCOMING);

            assertThat(queue.size()).isEqualTo(1);
            assertThat(queue.droppedCount()).isEqualTo(1);
            assertThat(nameOf(queue.take())).isEqualTo("s");
        }

        @Test
        @DisplayName("新任务优先级更高时挤掉最低优先级的最旧任务")
        void keepsHigherPriorityIncoming() throws InterruptedException {
            var queue = new DeliveryQueue(1);
            queue.offer(task(NotificationCategory.NORMAL, "n"));

            assertThat(queue.offer(task(NotificationCategory.SECURITY, "s")))
                    .isEqualTo(DeliveryQueue.Result.DROPPED_OLDEST);

            assertThat(queue.size()).isEqualTo(1);
            assertThat(nameOf(queue.take())).isEqualTo("s");
        }
    }

    @Nested
    @DisplayName("关闭")
    class Closing {

        @Test
        @DisplayName("关闭后清空排队任务，take 返回 null")
        void closeClearsAndWakesConsumer() throws InterruptedException {
            var queue = new DeliveryQueue(10);
            queue.offer(task(NotificationCategory.NORMAL, "a"));
            queue.offer(task(NotificationCategory.NORMAL, "b"));

            assertThat(queue.close()).isEqualTo(2);
            assertThat(queue.size()).isZero();
            assertThat(queue.take()).isNull();
        }

        @Test
        @DisplayName("关闭后入队返回 CLOSED")
        void offerAfterCloseIsRejected() {
            var queue = new DeliveryQueue(10);
            queue.close();

            assertThat(queue.offer(task(NotificationCategory.NORMAL, "a")))
                    .isEqualTo(DeliveryQueue.Result.CLOSED);
        }
    }

    @Test
    @DisplayName("无任务时 take 阻塞，入队后被唤醒")
    void takeBlocksUntilOffer() throws Exception {
        var queue = new DeliveryQueue(10);
        var executor = Executors.newSingleThreadExecutor();
        try {
            var future = executor.submit(queue::take);
            Thread.sleep(100);
            assertThat(future.isDone()).isFalse();

            queue.offer(task(NotificationCategory.NORMAL, "a"));

            assertThat(nameOf(future.get(1, TimeUnit.SECONDS))).isEqualTo("a");
        } finally {
            executor.shutdownNow();
        }
    }
}
