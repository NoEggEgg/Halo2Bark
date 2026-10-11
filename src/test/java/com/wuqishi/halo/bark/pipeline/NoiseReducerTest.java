package com.wuqishi.halo.bark.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wuqishi.halo.bark.config.PushSettings;
import com.wuqishi.halo.bark.push.NotificationCategory;
import com.wuqishi.halo.bark.push.PushNotification;
import com.wuqishi.halo.bark.transport.DeliveryReport;
import com.wuqishi.halo.bark.transport.NotificationDelivery;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("降噪层：安全穿透 / 合并 / 限流 / 静默")
class NoiseReducerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MutableClock clock = new MutableClock();
    private final List<PushNotification> delivered = Collections.synchronizedList(new ArrayList<>());
    private final NotificationDelivery delivery = (notification, settings, devices) -> {
        delivered.add(notification);
        return DeliveryReport.empty();
    };
    private final NoiseReducer reducer = new NoiseReducer(delivery, clock);

    private static PushSettings pushSettings(String json) {
        try {
            return MAPPER.readValue(json, PushSettings.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static PushNotification note(String title, NotificationCategory category) {
        return new PushNotification(title, "", "正文", "", category, true);
    }

    private void accept(String title, NotificationCategory category, PushSettings settings) {
        reducer.accept(Judgement.NEW_COMMENT, note(title, category), settings);
    }

    /** 冲刷是异步的（走专用执行器），等待其完成。 */
    private void awaitDelivered(int expected) {
        var deadline = System.currentTimeMillis() + 2_000;
        while (delivered.size() < expected && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    @Test
    @DisplayName("限流溢出缓冲有界：达到上限后丢弃最旧，内存不随事件量增长")
    void overflowBufferIsBounded() {
        // 配额 1、溢出上限 3：第 1 条即时送达耗尽配额，其后每条进入溢出缓冲
        var small = new NoiseReducer(delivery, clock, 1, 500, 3);
        var settings = PushSettings.defaults();
        Judgement[] judgements = {Judgement.NEW_COMMENT, Judgement.NEW_REPLY,
            Judgement.POST_PUBLISHED, Judgement.USER_REGISTERED, Judgement.POST_REMOVED,
            Judgement.PENDING_COMMENT};

        for (int i = 0; i < judgements.length; i++) {
            small.accept(judgements[i], note("n" + i, NotificationCategory.NORMAL), settings);
        }

        assertThat(small.overflowSize())
                .as("溢出缓冲必须被上限约束，不得随事件量无界增长")
                .isEqualTo(3);
        assertThat(small.droppedOverflowCount())
                .as("超出上限的部分必须被丢弃且可观测")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("免打扰缓冲有界：达到上限后丢弃最旧，且丢弃量可累计观测")
    void quietBufferIsBounded() {
        // 免打扰缓冲上限 3：静默时段内每条都进入暂存
        var small = new NoiseReducer(delivery, clock, 20, 3, 500);
        var quiet = pushSettings(
                "{\"quietHoursEnabled\":true,\"quietHoursFrom\":\"23:00\",\"quietHoursTo\":\"07:00\"}");
        clock.set(LocalTime.of(23, 30));

        for (int i = 0; i < 6; i++) {
            small.accept(Judgement.NEW_COMMENT, note("q" + i, NotificationCategory.INTERACTIVE), quiet);
        }

        assertThat(small.quietBufferSize())
                .as("免打扰缓冲必须被上限约束，不得随静默时长与事件量无界增长")
                .isEqualTo(3);
        assertThat(small.droppedQuietCount())
                .as("超出上限的部分必须被丢弃且可累计观测")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("安全事件直接送达：不受合并、限流、静默影响")
    void securityBypassesEverything() {
        var quiet = pushSettings("{\"quietHoursEnabled\":true,\"quietHoursFrom\":\"23:00\",\"quietHoursTo\":\"07:00\"}");
        clock.set(LocalTime.of(23, 30));

        for (int i = 0; i < 5; i++) {
            reducer.accept(Judgement.DEVICE_LOGIN, note("安全" + i, NotificationCategory.SECURITY), quiet);
        }

        assertThat(delivered).hasSize(5);
    }

    @Test
    @DisplayName("前 2 条立即送达；第 3、4 条暂存后合并为一条摘要")
    void mergesAfterThreshold() {
        var settings = PushSettings.defaults();

        accept("一", NotificationCategory.INTERACTIVE, settings);
        accept("二", NotificationCategory.INTERACTIVE, settings);
        assertThat(delivered).hasSize(2);

        accept("三", NotificationCategory.INTERACTIVE, settings);
        assertThat(delivered).hasSize(2);
        accept("四", NotificationCategory.INTERACTIVE, settings);

        awaitDelivered(3);
        assertThat(delivered).hasSize(3);
        assertThat(delivered.get(2).title()).isEqualTo("💬 2 条新评论");
    }

    @Test
    @DisplayName("窗口结束时仅 1 条暂存 → 按原样补发，不生成「1 条」摘要")
    void singleHeldIsDeliveredAsIs() {
        var settings = PushSettings.defaults();
        accept("一", NotificationCategory.INTERACTIVE, settings);
        accept("二", NotificationCategory.INTERACTIVE, settings);
        accept("三", NotificationCategory.INTERACTIVE, settings);
        assertThat(delivered).hasSize(2);

        clock.advance(Duration.ofMinutes(10));
        reducer.sweep();

        awaitDelivered(3);
        assertThat(delivered).hasSize(3);
        assertThat(delivered.get(2).title()).isEqualTo("三");
    }

    @Test
    @DisplayName("配额按实际发送量计：本分钟不超发，多出部分下一分钟补发聚合摘要")
    void rateLimitsActualSends() {
        var limited = new NoiseReducer(delivery, clock, 2);
        var settings = PushSettings.defaults();
        // 合并窗口内前 2 条立即送达（占满配额），第 3、4 条触发合并摘要——但配额已满
        for (int i = 0; i < 4; i++) {
            limited.accept(Judgement.NEW_COMMENT, note("第" + i, NotificationCategory.NORMAL),
                    settings);
        }
        settle(); // 合并摘要走补发执行器，等它在本分钟内完成配额判定后再推进时钟
        assertThat(delivered).hasSize(2);

        clock.advance(Duration.ofMinutes(1).plusSeconds(1));
        limited.sweep();

        awaitDelivered(3);
        assertThat(delivered).hasSize(3);
        assertThat(delivered.get(2).title()).startsWith("🔔 近 1 分钟另有");
    }

    @Test
    @DisplayName("窗口到期但冲刷未跑到时提交，暂存的条目不会被新窗口覆盖丢失")
    void submitAfterWindowExpiryDoesNotLoseHeldItems() {
        var settings = PushSettings.defaults();
        accept("一", NotificationCategory.INTERACTIVE, settings);
        accept("二", NotificationCategory.INTERACTIVE, settings);
        accept("三", NotificationCategory.INTERACTIVE, settings);
        assertThat(delivered).hasSize(2);

        // 窗口已到期，但**不**调用 sweep()：直接再提交两条，抢占冲刷的工作
        clock.advance(Duration.ofMinutes(10));
        accept("四", NotificationCategory.INTERACTIVE, settings);
        accept("五", NotificationCategory.INTERACTIVE, settings);

        // 「三」未被新窗口覆盖：按原样补发；「四」「五」属新窗口的 2 条即时送达
        awaitDelivered(5);
        assertThat(delivered).extracting(PushNotification::title).contains("三");
        assertThat(delivered).hasSize(5);
    }

    /** 等待补发执行器上的异步任务跑完（合并摘要是异步送达的）。 */
    private void settle() {
        try {
            Thread.sleep(300);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @DisplayName("免打扰时段内暂存，时段结束后合并补发一条摘要")
    void quietHoursBuffersAndFlushes() {
        var settings = pushSettings("{\"quietHoursEnabled\":true,\"quietHoursFrom\":\"23:00\",\"quietHoursTo\":\"07:00\"}");
        clock.set(LocalTime.of(23, 30));

        accept("一", NotificationCategory.INTERACTIVE, settings);
        accept("二", NotificationCategory.INTERACTIVE, settings);
        assertThat(delivered).isEmpty();

        clock.set(LocalTime.of(7, 0));
        reducer.sweep();

        awaitDelivered(1);
        assertThat(delivered).hasSize(1);
        assertThat(delivered.get(0).title()).isEqualTo("🌙 免打扰期间收到 2 条通知");
    }

    @Test
    @DisplayName("免打扰缓冲超过上限时丢弃最旧条目，避免无界增长")
    void quietBufferCapped() {
        var reducer = new NoiseReducer(delivery, clock, NoiseReducer.RATE_LIMIT_PER_MINUTE, 3);
        reducer.start();
        try {
            var settings = pushSettings(
                    "{\"quietHoursEnabled\":true,\"quietHoursFrom\":\"23:00\",\"quietHoursTo\":\"07:00\"}");
            clock.set(LocalTime.of(23, 30));
            // 容量 3：前 3 条入缓冲，之后每加 1 条丢弃最旧 1 条，最终只保留最后 3 条
            for (int i = 0; i < 6; i++) {
                reducer.accept(Judgement.NEW_COMMENT, note("n" + i, NotificationCategory.INTERACTIVE),
                        settings);
            }
            assertThat(delivered).isEmpty();

            clock.set(LocalTime.of(7, 0));
            reducer.sweep();

            awaitDelivered(1);
            assertThat(delivered).hasSize(1);
            // 仅保留最后 3 条，而非 6 条
            assertThat(delivered.get(0).title()).isEqualTo("🌙 免打扰期间收到 3 条通知");
        } finally {
            reducer.stop();
        }
    }

    @Test
    @DisplayName("停止后再次启动仍可冲刷（执行器被重建）")
    void restartAfterStopStillSweeps() {
        var settings = PushSettings.defaults();
        reducer.start();
        reducer.stop();
        reducer.start();
        try {
            accept("一", NotificationCategory.INTERACTIVE, settings);
            accept("二", NotificationCategory.INTERACTIVE, settings);
            accept("三", NotificationCategory.INTERACTIVE, settings);
            assertThat(delivered).hasSize(2);

            clock.advance(Duration.ofMinutes(10));
            reducer.sweep();
            awaitDelivered(3);
            assertThat(delivered).hasSize(3);
        } finally {
            reducer.stop();
        }
    }

    /** 可推进/设定的 UTC 时钟（与 {@code LocalTime.now(clock)} 的取值域一致）。 */
    private static final class MutableClock extends Clock {

        private Instant instant = Instant.parse("2026-09-28T12:00:00Z");

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        void set(LocalTime time) {
            instant = LocalDate.of(2026, 9, 28).atTime(time).toInstant(ZoneOffset.UTC);
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
