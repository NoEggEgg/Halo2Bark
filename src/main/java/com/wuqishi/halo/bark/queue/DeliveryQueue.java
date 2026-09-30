package com.wuqishi.halo.bark.queue;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 有界、按优先级出队的投递队列（PRD 5.1、10.4.1）。
 *
 * <p><b>优先级</b>：安全 &gt; 互动 &gt; 普通；同一优先级内按入队顺序（FIFO）。
 * 安全事件优先出队，呼应 PRD 4.5「安全事件不参与合并、不受静默时段约束」的优先语义。
 *
 * <p><b>溢出</b>：容量有界。入队时若已满，丢弃「优先级最低层级中最旧的一条」并计数（PRD 11）；
 * 若新任务优先级比队列中所有任务都低，则丢弃新任务本身，避免低价值事件挤掉高价值事件。
 *
 * <p><b>线程安全</b>：生产者（事件线程）与消费者（投递线程）可并发访问（PRD 10.4.2）。
 */
final class DeliveryQueue {

    /** 入队结果，便于调用方记录溢出。 */
    enum Result {
        /** 已入队。 */
        ENQUEUED,
        /** 已入队，但为腾出容量丢弃了队列中优先级最低且最旧的一条。 */
        DROPPED_OLDEST,
        /** 队列已满且新任务优先级最低，新任务被丢弃。 */
        DROPPED_INCOMING,
        /** 队列已关闭，不再接收任务。 */
        CLOSED
    }

    private final int capacity;
    private final List<Deque<DeliveryTask>> tiers;

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();

    private int size;
    private long dropped;
    private boolean closed;

    DeliveryQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("队列容量必须为正数：" + capacity);
        }
        this.capacity = capacity;
        this.tiers = List.of(new ArrayDeque<>(), new ArrayDeque<>(), new ArrayDeque<>());
    }

    /** 入队；返回入队结果。 */
    Result offer(DeliveryTask task) {
        lock.lock();
        try {
            if (closed) {
                return Result.CLOSED;
            }
            if (size < capacity) {
                enqueue(task);
                notEmpty.signal();
                return Result.ENQUEUED;
            }
            int lowest = lowestNonEmptyTier();
            if (tierOf(task) > lowest) {
                dropped++;
                return Result.DROPPED_INCOMING;
            }
            tiers.get(lowest).pollFirst();
            size--;
            dropped++;
            enqueue(task);
            notEmpty.signal();
            return Result.DROPPED_OLDEST;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 取出优先级最高的任务；队列已关闭时返回 {@code null}。
     *
     * <p>无任务时阻塞等待，可被中断（{@code shutdownNow}），此时抛出 {@link InterruptedException}。
     */
    DeliveryTask take() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (true) {
                if (closed) {
                    return null;
                }
                DeliveryTask task = pollHighest();
                if (task != null) {
                    return task;
                }
                notEmpty.await();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 关闭队列：停止接收新任务，唤醒等待中的消费者，清空尚未取出的任务。
     *
     * <p>PRD 5.1：停用期间事件直接丢弃，不在恢复后补发，因此剩余任务不再投递。
     *
     * @return 被放弃（未投递）的任务数
     */
    int close() {
        lock.lock();
        try {
            closed = true;
            int abandoned = size;
            tiers.forEach(Deque::clear);
            size = 0;
            notEmpty.signalAll();
            return abandoned;
        } finally {
            lock.unlock();
        }
    }

    /** 溢出丢弃计数（PRD 10.5）。 */
    long droppedCount() {
        lock.lock();
        try {
            return dropped;
        } finally {
            lock.unlock();
        }
    }

    /** 当前排队任务数。 */
    int size() {
        lock.lock();
        try {
            return size;
        } finally {
            lock.unlock();
        }
    }

    private void enqueue(DeliveryTask task) {
        tiers.get(tierOf(task)).addLast(task);
        size++;
    }

    private DeliveryTask pollHighest() {
        for (Deque<DeliveryTask> tier : tiers) {
            DeliveryTask task = tier.pollFirst();
            if (task != null) {
                size--;
                return task;
            }
        }
        return null;
    }

    /** 最低（最次要）的非空层级；仅在队列已满时调用，故必有非空层级。 */
    private int lowestNonEmptyTier() {
        for (int i = tiers.size() - 1; i >= 0; i--) {
            if (!tiers.get(i).isEmpty()) {
                return i;
            }
        }
        return tiers.size() - 1;
    }

    private static int tierOf(DeliveryTask task) {
        return switch (task.event().category()) {
            case SECURITY -> 0;
            case INTERACTIVE -> 1;
            case NORMAL -> 2;
        };
    }
}
