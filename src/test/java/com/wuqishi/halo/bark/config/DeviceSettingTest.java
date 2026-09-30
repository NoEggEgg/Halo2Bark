package com.wuqishi.halo.bark.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("设备：可投递判定（启用 + Key 非空 + 地址非空）")
class DeviceSettingTest {

    @Test
    @DisplayName("三项齐备才可投递")
    void deliverableWhenComplete() {
        assertThat(new DeviceSetting("iPhone", "KEY", "https://api.day.app", Boolean.TRUE).deliverable())
                .isTrue();
    }

    @Test
    @DisplayName("显式关闭时不可投递")
    void notDeliverableWhenDisabled() {
        assertThat(new DeviceSetting("iPhone", "KEY", "https://api.day.app", Boolean.FALSE).deliverable())
                .isFalse();
    }

    @Test
    @DisplayName("enabled 缺失（null）按 PRD 5.3 的默认值 true 处理，仍可投递")
    void missingEnabledDefaultsToTrue() {
        var device = new DeviceSetting("iPhone", "KEY", "https://api.day.app", null);

        assertThat(device.enabled()).isTrue();
        assertThat(device.deliverable()).isTrue();
    }

    @Test
    @DisplayName("Key 或地址为空/空白时不可投递")
    void notDeliverableWhenKeyOrUrlMissing() {
        assertThat(new DeviceSetting("iPhone", "", "https://api.day.app", Boolean.TRUE).deliverable())
                .isFalse();
        assertThat(new DeviceSetting("iPhone", "   ", "https://api.day.app", Boolean.TRUE).deliverable())
                .isFalse();
        assertThat(new DeviceSetting("iPhone", "KEY", "", Boolean.TRUE).deliverable())
                .isFalse();
        assertThat(new DeviceSetting("iPhone", "KEY", "  ", Boolean.TRUE).deliverable())
                .isFalse();
    }
}
