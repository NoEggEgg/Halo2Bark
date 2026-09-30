package com.wuqishi.halo.bark.pipeline;

import java.time.Clock;

/**
 * 每分钟限流（PRD 5.4：每自然分钟最多 20 条）。
 *
 * <p>本实现按**实际发出的通知条数**计数（PRD 5.4「与投递粒度一致」）——配额在实际送达时占用，
 * 而非在入口占用，因此合并摘要按 1 条计、其输入不重复计费。
 * 超出配额的条目不在此丢弃：调用方需自行暂存，并在该分钟结束时补发一条聚合摘要。
 *
 * <p>线程安全：投递线程池有多个线程。
 */
public final class RateLimiter {

    private final int limitPerMinute;
    private final Clock clock;

    private long currentMinute = Long.MIN_VALUE;
    private int count;

    public RateLimiter(int limitPerMinute, Clock clock) {
        this.limitPerMinute = Math.max(1, limitPerMinute);
        this.clock = clock;
    }

    /** 当前所在分钟（自 epoch 起的分钟序号），用于判断分钟边界。 */
    public synchronized long currentMinute() {
        return clock.instant().getEpochSecond() / 60;
    }

    /**
     * 尝试占用一个配额。
     *
     * @return {@code false} 表示本分钟配额已满，调用方应把该条暂存到溢出缓冲
     */
    public synchronized boolean tryAcquire() {
        var minute = currentMinute();
        if (minute != currentMinute) {
            currentMinute = minute;
            count = 0;
        }
        if (count >= limitPerMinute) {
            return false;
        }
        count++;
        return true;
    }
}
