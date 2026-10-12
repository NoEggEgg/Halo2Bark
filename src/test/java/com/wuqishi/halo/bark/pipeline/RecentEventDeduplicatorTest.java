package com.wuqishi.halo.bark.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.wuqishi.halo.bark.event.SiteEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 窗口去重的语义测试。
 *
 * <p>统一使用生产路径 API 组合表达「记录 + 判定」：
 * {@link RecentEventDeduplicator#markSeen}（记录，时间戳单调推进）+
 * {@link RecentEventDeduplicator#isDuplicatePeek}（仅判定不记录），
 * 与 {@code EventPipeline} 的 tryClaim / markSeen / release 用法保持一致。
 */
@DisplayName("窗口去重：丢弃短窗口内的重复事件")
class RecentEventDeduplicatorTest {

    private final MutableClock clock = new MutableClock();
    private final RecentEventDeduplicator deduplicator = new RecentEventDeduplicator(clock);

    private static SiteEvent comment(String name) {
        return new SiteEvent.CommentCreated(name, null, "Email", "a@b.c", "访客", "内容", false, null);
    }

    private static SiteEvent post(String name, SiteEvent.PostChange change) {
        return new SiteEvent.PostChanged(name, change);
    }

    @Test
    @DisplayName("并发安全：更早的时间戳不会回退已记录的更新时间戳（避免重复漏判）")
    void olderTimestampDoesNotRegress() {
        var t1 = Instant.parse("2026-09-28T00:00:00Z");
        clock.set(t1);
        assertThat(deduplicator.isDuplicatePeek(comment("c-1"))).isFalse();
        deduplicator.markSeen(comment("c-1")); // 记录于 T
        clock.set(t1.plusSeconds(1));
        assertThat(deduplicator.isDuplicatePeek(comment("c-1"))).isTrue(); // 真实重复
        deduplicator.markSeen(comment("c-1")); // 时间戳推进到 T+1s
        // 拨到一个更早的时刻（模拟并发下旧时间戳的竞争）：单调推进保证不会回退已记录的时间戳
        clock.set(t1.minusSeconds(100));
        assertThat(deduplicator.isDuplicatePeek(comment("c-1"))).isTrue(); // 窗口已激活，仍判重复
        // 关键的回归断言：若已记录的时间戳被旧值覆盖，这里距旧值已超过窗口，会漏判为「新事件」
        clock.set(t1.plusSeconds(2));
        assertThat(deduplicator.isDuplicatePeek(comment("c-1"))).isTrue();
    }

    @Test
    @DisplayName("同一主体的同一事件在窗口内第二次出现即判为重复")
    void duplicateWithinWindow() {
        assertThat(deduplicator.isDuplicatePeek(comment("c-1"))).isFalse();
        deduplicator.markSeen(comment("c-1"));
        assertThat(deduplicator.isDuplicatePeek(comment("c-1"))).isTrue();
    }

    @Test
    @DisplayName("超出窗口后不再视为重复")
    void notDuplicateAfterWindow() {
        deduplicator.markSeen(comment("c-1"));
        clock.advance(RecentEventDeduplicator.WINDOW.plusSeconds(1));
        assertThat(deduplicator.isDuplicatePeek(comment("c-1"))).isFalse();
    }

    @Test
    @DisplayName("不同主体互不影响")
    void differentSubjectsAreIndependent() {
        deduplicator.markSeen(comment("c-1"));
        assertThat(deduplicator.isDuplicatePeek(comment("c-1"))).isTrue();
        assertThat(deduplicator.isDuplicatePeek(comment("c-2"))).isFalse();
    }

    @Test
    @DisplayName("文章「取消发布」与「删除」视为同一动作：同窗口内只推一条")
    void unpublishedAndDeletedCollapse() {
        deduplicator.markSeen(post("p-1", SiteEvent.PostChange.UNPUBLISHED));
        // Halo 删除已发布文章时会紧接着发 DELETED —— 应被判为重复
        assertThat(deduplicator.isDuplicatePeek(post("p-1", SiteEvent.PostChange.DELETED))).isTrue();
    }

    @Test
    @DisplayName("「发布」与「删除」是两件事，互不影响")
    void publishedAndRemovedAreIndependent() {
        deduplicator.markSeen(post("p-1", SiteEvent.PostChange.PUBLISHED));
        assertThat(deduplicator.isDuplicatePeek(post("p-1", SiteEvent.PostChange.DELETED))).isFalse();
        // 同一次删除被 Halo 重复发布两次 → 第二次判为重复
        deduplicator.markSeen(post("p-1", SiteEvent.PostChange.DELETED));
        assertThat(deduplicator.isDuplicatePeek(post("p-1", SiteEvent.PostChange.DELETED))).isTrue();
    }

    /** 可推进的时钟，用于验证窗口过期。 */
    private static final class MutableClock extends Clock {

        private Instant instant = Instant.parse("2026-09-28T00:00:00Z");

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        void set(Instant value) {
            instant = value;
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
