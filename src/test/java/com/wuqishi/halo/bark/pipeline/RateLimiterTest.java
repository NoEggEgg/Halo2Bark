package com.wuqishi.halo.bark.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("每分钟限流：配额、分钟边界重置")
class RateLimiterTest {

    private final MutableClock clock = new MutableClock();
    private final RateLimiter limiter = new RateLimiter(3, clock);

    @Test
    @DisplayName("配额内放行，超出后拒绝")
    void enforcesQuota() {
        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isFalse();
        assertThat(limiter.tryAcquire()).isFalse();
    }

    @Test
    @DisplayName("进入下一分钟后配额重置")
    void resetsOnNextMinute() {
        limiter.tryAcquire();
        limiter.tryAcquire();
        limiter.tryAcquire();
        assertThat(limiter.tryAcquire()).isFalse();

        clock.advance(Duration.ofMinutes(1));

        assertThat(limiter.currentMinute()).isEqualTo(clock.instant().getEpochSecond() / 60);
        assertThat(limiter.tryAcquire()).isTrue();
    }

    @Test
    @DisplayName("配额下限被钳制为至少 1")
    void clampsToAtLeastOne() {
        var zero = new RateLimiter(0, clock);
        assertThat(zero.tryAcquire()).isTrue();
        assertThat(zero.tryAcquire()).isFalse();
    }

    /** 可推进的 UTC 时钟。 */
    private static final class MutableClock extends Clock {

        private Instant instant = Instant.parse("2026-09-29T00:00:00Z");

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
