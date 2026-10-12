package com.wuqishi.halo.bark.transport;

import static org.assertj.core.api.Assertions.assertThat;

import com.wuqishi.halo.bark.config.DeviceSetting;
import com.wuqishi.halo.bark.config.PushSettings;
import com.wuqishi.halo.bark.transport.DeliveryFailure;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 设备健康状态机：告警阈值 / 一次性告警 / 恢复替换 / id 稳定与脱敏。
 */
@DisplayName("设备健康状态机：告警-恢复成对通知")
class DeliveryHealthTrackerTest {

    private final DeliveryHealthTracker tracker = new DeliveryHealthTracker();
    private final PushSettings settings = PushSettings.defaults();

    private static DeviceSetting device(String key) {
        return new DeviceSetting("手机", key, "https://api.day.app", true);
    }

    private static DeviceResult ok() {
        return DeviceResult.ok("手机", 1, 100);
    }

    private static DeviceResult fail() {
        return DeviceResult.failed("手机", DeliveryFailure.UNREACHABLE, "服务不可达", 3, 1_000);
    }

    @Test
    @DisplayName("首次失败静默；连续第二次失败产出告警；持续失败不重复告警")
    void alertsOncePerEpisode() {
        assertThat(tracker.onDeviceResult(device("KEY-A-1"), fail(), settings)).isEmpty();

        var alert = tracker.onDeviceResult(device("KEY-A-1"), fail(), settings);
        assertThat(alert).isPresent();
        assertThat(alert.get().notification().title()).isEqualTo("📵 设备「手机」推送失败");
        assertThat(alert.get().notification().body()).contains("连续 2 次");
        assertThat(alert.get().notification().url()).isEmpty();
        assertThat(alert.get().recovery()).isFalse();

        assertThat(tracker.onDeviceResult(device("KEY-A-1"), fail(), settings)).isEmpty();
    }

    @Test
    @DisplayName("告警后恢复：恢复通知携带同一 id，供手机端原地替换")
    void recoveryCarriesSameIdAsAlert() {
        tracker.onDeviceResult(device("KEY-A-1"), fail(), settings);
        var alert = tracker.onDeviceResult(device("KEY-A-1"), fail(), settings).orElseThrow();
        var alertId = alert.notification().id();
        assertThat(alertId).startsWith("halo2bark-health-");

        var recovery = tracker.onDeviceResult(device("KEY-A-1"), ok(), settings).orElseThrow();
        assertThat(recovery.recovery()).isTrue();
        assertThat(recovery.notification().title()).isEqualTo("✅ 设备「手机」推送已恢复");
        assertThat(recovery.notification().id()).isEqualTo(alertId);
    }

    @Test
    @DisplayName("未达阈值的一次性失败后成功：全程无通知")
    void singleFailureIsSilent() {
        assertThat(tracker.onDeviceResult(device("KEY-A-1"), fail(), settings)).isEmpty();
        assertThat(tracker.onDeviceResult(device("KEY-A-1"), ok(), settings)).isEmpty();
        assertThat(tracker.onDeviceResult(device("KEY-A-1"), fail(), settings)).isEmpty();
    }

    @Test
    @DisplayName("id 按设备指纹生成：不含 Key 明文，不同设备互不相同，同设备稳定")
    void idIsFingerprintedAndStable() {
        var first = tracker.onDeviceResult(device("KEY-A-1"), fail(), settings);
        var second = tracker.onDeviceResult(device("KEY-A-1"), fail(), settings).orElseThrow();
        var alertId = second.notification().id();

        assertThat(alertId).doesNotContain("KEY-A-1");
        var firstId = second.notification().id();
        assertThat(first.isEmpty()).isTrue();

        var other = tracker.onDeviceResult(device("KEY-B-2"), fail(), settings);
        var otherAlert = tracker.onDeviceResult(device("KEY-B-2"), fail(), settings).orElseThrow();
        assertThat(otherAlert.notification().id()).isNotEqualTo(firstId);
        assertThat(other.isEmpty()).isTrue();

        // 同设备再次失败（新的一轮）沿用同一 id：状态灯语义
        tracker.onDeviceResult(device("KEY-A-1"), ok(), settings);
        tracker.onDeviceResult(device("KEY-A-1"), fail(), settings);
        var again = tracker.onDeviceResult(device("KEY-A-1"), fail(), settings).orElseThrow();
        assertThat(again.notification().id()).isEqualTo(firstId);
    }
}
