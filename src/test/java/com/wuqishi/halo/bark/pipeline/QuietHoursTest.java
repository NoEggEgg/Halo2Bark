package com.wuqishi.halo.bark.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("静默时段判定：同日区间与跨天区间")
class QuietHoursTest {

    @Test
    @DisplayName("同日区间：[开始, 结束)")
    void sameDay() {
        assertThat(QuietHours.isQuiet(LocalTime.of(12, 0), "09:00", "18:00")).isTrue();
        assertThat(QuietHours.isQuiet(LocalTime.of(9, 0), "09:00", "18:00")).isTrue();
        assertThat(QuietHours.isQuiet(LocalTime.of(8, 59), "09:00", "18:00")).isFalse();
        assertThat(QuietHours.isQuiet(LocalTime.of(18, 0), "09:00", "18:00")).isFalse();
    }

    @Test
    @DisplayName("跨天区间：23:00–07:00 覆盖午夜两侧")
    void crossDay() {
        assertThat(QuietHours.isQuiet(LocalTime.of(23, 30), "23:00", "07:00")).isTrue();
        assertThat(QuietHours.isQuiet(LocalTime.of(0, 0), "23:00", "07:00")).isTrue();
        assertThat(QuietHours.isQuiet(LocalTime.of(6, 59), "23:00", "07:00")).isTrue();
        assertThat(QuietHours.isQuiet(LocalTime.of(7, 0), "23:00", "07:00")).isFalse();
        assertThat(QuietHours.isQuiet(LocalTime.of(12, 0), "23:00", "07:00")).isFalse();
    }

    @Test
    @DisplayName("缺失、非法或起止相同时视为未设置")
    void invalidInputs() {
        assertThat(QuietHours.isQuiet(LocalTime.NOON, null, "07:00")).isFalse();
        assertThat(QuietHours.isQuiet(LocalTime.NOON, "23:00", null)).isFalse();
        assertThat(QuietHours.isQuiet(LocalTime.NOON, "abc", "07:00")).isFalse();
        assertThat(QuietHours.isQuiet(LocalTime.NOON, "09:00", "09:00")).isFalse();
    }

    @Test
    @DisplayName("24:00 按非法时间处理，而非翻转为 00:00")
    void twentyFourHundredIsInvalid() {
        // STRICT 解析器拒绝 24:00：视为该边界缺失，整段静默窗口失效（安全），而非翻转为 00:00
        assertThat(QuietHours.parse("24:00")).isNull();
        assertThat(QuietHours.isQuiet(LocalTime.of(12, 0), "08:00", "24:00")).isFalse();
        // 合法边界不受影响
        assertThat(QuietHours.parse("00:00")).isEqualTo(LocalTime.MIDNIGHT);
        assertThat(QuietHours.parse("23:59")).isEqualTo(LocalTime.of(23, 59));
    }
}
