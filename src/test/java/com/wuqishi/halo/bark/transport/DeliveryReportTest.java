package com.wuqishi.halo.bark.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("投递结果与汇总：不变量与 null 归一")
class DeliveryReportTest {

    @Test
    @DisplayName("DeviceResult 成功必然无 failure，失败必然有 failure")
    void deviceResultInvariant() {
        assertThat(DeviceResult.ok("d", 1, 10).success()).isTrue();
        assertThat(DeviceResult.failed("d", DeliveryFailure.TIMEOUT, "x", 1, 10).success()).isFalse();
    }

    @Test
    @DisplayName("矛盾的 DeviceResult 构造直接抛异常")
    void deviceResultRejectsInconsistentState() {
        // 成功却带 failure，矛盾态
        assertThatThrownBy(() -> new DeviceResult("d", true, DeliveryFailure.TIMEOUT, "", 1, 10))
                .isInstanceOf(IllegalArgumentException.class);
        // 失败却没有 failure，矛盾态
        assertThatThrownBy(() -> new DeviceResult("d", false, null, "", 1, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("DeliveryReport 把 null 条目归一为失败结果，而非抛 NPE 崩溃聚合")
    void deliveryReportNormalizesNullEntry() {
        // 构造时含 null 条目的列表需用允许 null 的实现（List.of 会直接抛 NPE 无法进入被测逻辑）
        var report = new DeliveryReport(Arrays.asList(DeviceResult.ok("a", 1, 10), null));
        assertThat(report.results()).hasSize(2);
        assertThat(report.results().get(1).success()).isFalse();
        assertThat(report.results().get(1).failure()).isEqualTo(DeliveryFailure.UNEXPECTED);
    }

    @Test
    @DisplayName("DeliveryReport 接受 null 列表，视为空结果")
    void deliveryReportAcceptsNullList() {
        assertThat(new DeliveryReport(null).results()).isEmpty();
    }
}
