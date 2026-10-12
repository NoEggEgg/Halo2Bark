package com.wuqishi.halo.bark.transport;

import com.wuqishi.halo.bark.config.DeviceSetting;
import com.wuqishi.halo.bark.config.PushSettings;
import com.wuqishi.halo.bark.push.NotificationCategory;
import com.wuqishi.halo.bark.push.PushNotification;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Component;

/**
 * 设备投递健康状态机：跟踪每台设备的连续失败，产出成对的「告警 / 恢复」通知。
 *
 * <p><b>语义（运维监控的 alert + resolve 模式）</b>：
 * <ul>
 *   <li>连续失败达到 {@link #ALERT_THRESHOLD} 次 → 产出**失败告警**（一次性，持续失败不重复刷屏），
 *       携带稳定的通知 id（{@code halo2bark-health-<指纹>}）；</li>
 *   <li>处于告警态的设备随后一次成功 → 产出**恢复通知**，携带**同一个 id**——Bark App
 *       （v1.5.2+ / bark-server v2.2.5+）会用新通知**原地替换**失败告警，手机上每台设备
 *       始终只占一条健康状态通知；旧版服务端优雅降级为两条独立通知（已验证
 *       bark-server 用 map 解析全部参数，未知参数进 APNs 自定义载荷，绝不报错）；</li>
 *   <li>未达阈值的一次性失败静默复位，不产生任何通知（内部重试已经吸收过一次）。</li>
 * </ul>
 *
 * <p><b>发送目标由调用方决定</b>（{@link BarkTransport}）：失败告警只能经**本轮成功**的其他设备
 * 送达（失败设备本身收不到）；恢复通知经**刚恢复**的设备送达——成功本身即是恢复的证明。
 *
 * <p>id 稳定绑定设备指纹（Key + 服务地址的 SHA-256 截断），**不含 Key 明文**——id 会出现在
 * 请求参数中，绝不能泄露凭据；同一设备的历次告警/恢复共用一个 id，如同一条状态灯。
 *
 * <p>线程安全：投递线程池有多线程，状态按指纹用 {@code compute} 原子迁移；
 * 计算前的旧状态经捕获数组带出（与 {@code RecentEventDeduplicator} 同一手法）。
 */
@Component
public class DeliveryHealthTracker {

    /** 触发告警的连续失败次数：内部已有 2～3 次重试，连续两轮失败说明是真故障。 */
    static final int ALERT_THRESHOLD = 2;

    /** 跟踪条目上限：正常只服务管理员配置的少量设备（≤20），兜底防无界增长。 */
    static final int MAX_TRACKED = 64;

    private static final String ID_PREFIX = "halo2bark-health-";
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("M月d日 HH:mm").withZone(ZoneId.systemDefault());

    private final ConcurrentMap<String, Health> states = new ConcurrentHashMap<>();
    private final Clock clock;

    public DeliveryHealthTracker() {
        this(Clock.systemDefaultZone());
    }

    /** 时钟可注入，便于测试断言时间字段。 */
    DeliveryHealthTracker(Clock clock) {
        this.clock = clock;
    }

    /**
     * 记录一台设备的一次投递结果，必要时产出健康通知。
     *
     * @return 空表示无需通知；否则为失败告警或恢复通知（恢复通知携带失败告警的 id 以原地替换）
     */
    public Optional<HealthNotice> onDeviceResult(DeviceSetting device, DeviceResult result,
            PushSettings settings) {
        var fingerprint = fingerprint(device);
        var now = Instant.now(clock);
        // compute 内部完成状态迁移；计算前的旧状态经数组带出，供恢复判定使用
        var previous = new Health[1];
        var outcome = states.compute(fingerprint, (k, prev) -> {
            previous[0] = prev;
            if (result.success()) {
                return new Health(0, false, null);
            }
            int failures = (prev == null ? 0 : prev.consecutiveFailures()) + 1;
            boolean wasAlerted = prev != null && prev.alerted();
            // 首次达到阈值即进入告警态并分配 id；此后持续失败保持告警态，不重复产出
            boolean nowAlerted = wasAlerted || failures >= ALERT_THRESHOLD;
            String alertId = wasAlerted && prev != null ? prev.alertId() : ID_PREFIX + fingerprint;
            return new Health(failures, nowAlerted, alertId);
        });
        evictIfOversized();

        var wasAlerted = previous[0] != null && previous[0].alerted();

        // 告警沿：本次失败后恰好达到阈值（且此前未告警）→ 产出失败告警
        if (!result.success() && outcome.alerted()
                && outcome.consecutiveFailures() == ALERT_THRESHOLD && !wasAlerted) {
            return Optional.of(new HealthNotice(
                    failureAlert(device, outcome.alertId(), outcome.consecutiveFailures(), now),
                    device, false));
        }
        // 恢复沿：成功且此前处于告警态 → 产出恢复通知（同 id 原地替换失败告警）
        if (result.success() && wasAlerted) {
            return Optional.of(new HealthNotice(
                    recoveryNotice(device, previous[0].alertId(), now), device, true));
        }
        return Optional.empty();
    }

    private PushNotification failureAlert(DeviceSetting device, String alertId, int failures,
            Instant now) {
        var name = displayName(device);
        return new PushNotification(
                "📵 设备「" + name + "」推送失败",
                "",
                "连续 " + failures + " 次投递失败\n时间：" + TIME_FORMAT.format(now)
                        + "\n恢复后本条通知将自动更新",
                "",
                NotificationCategory.NORMAL,
                true,
                alertId);
    }

    private PushNotification recoveryNotice(DeviceSetting device, String alertId, Instant now) {
        var name = displayName(device);
        return new PushNotification(
                "✅ 设备「" + name + "」推送已恢复",
                "",
                "投递已恢复正常\n时间：" + TIME_FORMAT.format(now),
                "",
                NotificationCategory.NORMAL,
                true,
                alertId);
    }

    private static String displayName(DeviceSetting device) {
        var name = device.name();
        return name == null || name.isBlank() ? "(未命名设备)" : name;
    }

    /**
     * 设备指纹：Key + 服务地址的 SHA-256 前 8 个十六进制字符。
     *
     * <p>用于告警 id 与状态键：稳定（设备不改名也能续接状态）、不含 Key 明文
     * （id 会进入请求参数与手机端，绝不能携带凭据）。
     */
    static String fingerprint(DeviceSetting device) {
        var material = (device.key() == null ? "" : device.key())
                + "@" + (device.serverUrl() == null ? "" : device.serverUrl());
        try {
            var digest = MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 4);
        } catch (NoSuchAlgorithmException e) {
            // JVM 必带 SHA-256，理论不可达；兜底用 hashCode 保证功能不中断
            return Integer.toHexString(material.hashCode());
        }
    }

    /** 兜底清理：超限时丢弃非告警态条目（纯计数器可随时重建），告警态保留以维持恢复语义。 */
    private void evictIfOversized() {
        if (states.size() <= MAX_TRACKED) {
            return;
        }
        states.entrySet().removeIf(entry -> !entry.getValue().alerted());
        if (states.size() > MAX_TRACKED) {
            states.clear();
        }
    }

    /**
     * 一次健康通知的产出。
     *
     * @param notification 告警或恢复通知（告警/恢复共用同一 id 以原地替换）
     * @param device 状态发生迁移的设备；恢复场景下它也是恢复通知的送达目标
     * @param recovery {@code true} 为恢复通知（送达刚恢复的设备）；{@code false} 为失败告警
     *     （送达本轮成功的其他设备，失败设备收不到自己的告警）
     */
    public record HealthNotice(PushNotification notification, DeviceSetting device,
            boolean recovery) {
    }

    /** 单台设备的健康状态。 */
    private record Health(int consecutiveFailures, boolean alerted, String alertId) {
    }
}
