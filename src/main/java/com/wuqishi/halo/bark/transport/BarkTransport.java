package com.wuqishi.halo.bark.transport;

import com.wuqishi.halo.bark.BarkConstants;
import com.wuqishi.halo.bark.Masking;
import com.wuqishi.halo.bark.config.DeviceSetting;
import com.wuqishi.halo.bark.config.PushSettings;
import com.wuqishi.halo.bark.push.NotificationCategory;
import com.wuqishi.halo.bark.push.PushNotification;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Bark 投递编排：按服务地址分组、批量优先、失败降级逐设备。
 *
 * <p>投递规则（PRD 5.3）：
 * <ul>
 *   <li>服务地址是设备级配置，因此先按 {@code serverUrl} 分组——
 *       {@code device_keys} 批量推送要求所有设备属于同一服务端，跨服务端无法批量。</li>
 *   <li>同一地址下可投递设备 ≥ 2 且服务端支持 V2 → 一次批量请求。</li>
 *   <li>批量请求失败 → 该地址内降级为逐设备重试，保证失败隔离；其他地址不受影响。</li>
 *   <li>服务端不支持 V2 → 回退 V1，V1 无批量能力，一律逐设备。</li>
 * </ul>
 *
 * <p>重试与超时不在本类实现，见 {@link BarkRequestSender}（PRD 5.2）。
 *
 * <p><b>线程约定</b>：本类全部方法阻塞，只允许在**投递线程**调用，
 * 不得在事件线程或 Reactor 调度线程调用（PRD 10.4.2）。
 *
 * <p>本类不引用事件模型，只依赖跨层契约 {@link PushNotification}（PRD 10.4.1）。
 */
@Slf4j
@Component
public class BarkTransport implements NotificationDelivery {

    private static final RetrySleeper DEFAULT_SLEEPER = millis -> {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    };

    private final BarkServerProbe probe;
    private final BarkPayloadFactory factory;
    private final BarkRequestSender sender;
    private final SiteBrandSupplier siteBrand;

    /** 最近一次投递的摘要（含「测试该设备」发起的投递），供状态端点读取。 */
    private final AtomicReference<LastDelivery> lastDelivery = new AtomicReference<>();

    @Autowired
    public BarkTransport(BarkHttpClient http, BarkServerProbe probe, BarkPayloadFactory factory,
            SiteBrandSupplier siteBrand) {
        this(probe, factory, siteBrand, new BarkRequestSender(http, DEFAULT_SLEEPER));
    }

    public BarkTransport(BarkServerProbe probe, BarkPayloadFactory factory,
            SiteBrandSupplier siteBrand, BarkRequestSender sender) {
        this.probe = probe;
        this.factory = factory;
        this.siteBrand = siteBrand;
        this.sender = sender;
    }

    /**
     * 向所有可投递设备投递一条通知。
     *
     * <p>设备不可投递（未启用、Key 或地址为空）时直接跳过；一台设备的失败不影响其他设备。
     * 方法不向调用方抛出异常——异常一律就地转为失败结果，避免影响事件总线（PRD 10.4.3）。
     *
     * @param notification 待推送通知
     * @param settings 全局推送配置；「测试该设备」端点可传入表单中的未保存配置
     * @param devices 候选设备；为 {@code null} 或不含可投递设备时返回空结果且不发请求
     */
    @Override
    public DeliveryReport send(PushNotification notification, PushSettings settings,
            List<DeviceSetting> devices) {
        var targets = devices == null
                ? List.<DeviceSetting>of()
                : devices.stream().filter(Objects::nonNull).filter(DeviceSetting::deliverable).toList();
        if (targets.isEmpty()) {
            // PRD 7.3：无启用设备时触发事件不产生错误，仅在日志记 debug
            log.debug("{} 没有可投递设备，本次未发送请求", BarkConstants.LOG_PREFIX);
            return DeliveryReport.empty();
        }

        // 站点标题与 Logo 的读取是阻塞调用，一次投递只解析一次，避免按设备重复查询
        var context = new DeliveryContext(notification, settings,
                resolveGroup(settings, notification.category()), resolveIcon(settings));

        var groups = new LinkedHashMap<String, List<DeviceSetting>>();
        for (var device : targets) {
            groups.computeIfAbsent(device.serverUrl(), key -> new ArrayList<>()).add(device);
        }

        var results = new ArrayList<DeviceResult>();
        for (var entry : groups.entrySet()) {
            results.addAll(deliverGroup(context, entry.getKey(), entry.getValue()));
        }
        var report = new DeliveryReport(results);
        lastDelivery.set(new LastDelivery(Instant.now(), report.results().size(),
                report.successCount(), report.failureCount()));
        logSummary(report);
        return report;
    }

    /** 最近一次投递摘要；尚未投递过时为空（供状态端点读取）。 */
    public Optional<LastDelivery> lastDelivery() {
        return Optional.ofNullable(lastDelivery.get());
    }

    /**
     * 最近一次投递的摘要。
     *
     * @param at 完成时刻
     * @param deviceCount 参与投递的设备数
     * @param successCount 成功设备数
     * @param failureCount 失败设备数
     */
    public record LastDelivery(Instant at, int deviceCount, long successCount, long failureCount) {
    }

    /** 投递同一个服务地址下的设备。 */
    private List<DeviceResult> deliverGroup(DeliveryContext context, String serverUrl,
            List<DeviceSetting> deviceGroup) {
        try {
            var v2 = probe.probe(serverUrl).v2Supported();
            if (!v2 || deviceGroup.size() == 1) {
                return deviceGroup.stream()
                        .map(device -> deliverOne(context, serverUrl, device, v2))
                        .toList();
            }
            var keys = deviceGroup.stream().map(DeviceSetting::key).toList();
            var batch = attempt(context, serverUrl, keys, v2);
            if (batch.success() && batch.deviceCodes().size() == deviceGroup.size()) {
                var results = new ArrayList<DeviceResult>(deviceGroup.size());
                for (int i = 0; i < deviceGroup.size(); i++) {
                    var device = deviceGroup.get(i);
                    // Bark may return HTTP 200 while individual devices fail. Never resend
                    // devices the server already acknowledged successfully.
                    results.add(batch.deviceCodes().get(i) == 200
                            ? DeviceResult.ok(device.name(), batch.attempts(), batch.elapsedMillis())
                            : deliverOne(context, serverUrl, device, v2));
                }
                return results;
            }
            if (!worthDegrading(batch)) {
                return deviceGroup.stream()
                        .map(device -> DeviceResult.failed(device.name(), batch.failure(),
                                batch.message(), batch.attempts(), batch.elapsedMillis()))
                        .toList();
            }
            log.debug("{} 批量投递失败，该地址降级为逐设备重试：设备数={} 服务={} 原因={}",
                    BarkConstants.LOG_PREFIX, deviceGroup.size(), Masking.maskUrl(serverUrl),
                    batch.failure());
            return deviceGroup.stream()
                    .map(device -> deliverOne(context, serverUrl, device, v2))
                    .toList();
        } catch (RuntimeException e) {
            var detail = Masking.sanitize(e.toString());
            log.warn("{} 投递服务 {} 时发生未预期异常：{}", BarkConstants.LOG_PREFIX,
                    Masking.maskUrl(serverUrl), detail);
            return deviceGroup.stream()
                    .map(device -> DeviceResult.failed(device.name(), DeliveryFailure.UNEXPECTED,
                            DeliveryFailure.UNEXPECTED.describe(detail), 0, 0L))
                    .toList();
        }
    }

    /** 投递单台设备。 */
    private DeviceResult deliverOne(DeliveryContext context, String serverUrl, DeviceSetting device,
            boolean v2) {
        var outcome = attempt(context, serverUrl, List.of(device.key()), v2);
        log.debug("{} 设备「{}」（Key {}）投递{}：请求 {} 次，耗时 {} ms", BarkConstants.LOG_PREFIX,
                device.name(), Masking.maskKey(device.key()),
                outcome.success() ? "成功" : "失败（" + outcome.failure() + "）",
                outcome.attempts(), outcome.elapsedMillis());
        return outcome.success()
                ? DeviceResult.ok(device.name(), outcome.attempts(), outcome.elapsedMillis())
                : DeviceResult.failed(device.name(), outcome.failure(), outcome.message(),
                        outcome.attempts(), outcome.elapsedMillis());
    }

    /** 构造请求并发往一个服务地址。 */
    private BarkRequestSender.Outcome attempt(DeliveryContext context, String serverUrl,
            List<String> deviceKeys, boolean v2) {
        var params = factory.params(context.notification(), context.settings(), deviceKeys,
                context.pushGroup(), context.pushIcon(), v2);
        String body;
        try {
            body = factory.body(params, context.settings());
        } catch (EncryptionException e) {
            log.warn("{} 加密推送失败，不降级为明文：{}", BarkConstants.LOG_PREFIX, e.getMessage());
            return BarkRequestSender.Outcome.failed(DeliveryFailure.ENCRYPTION,
                    DeliveryFailure.ENCRYPTION.describe(e.getMessage()));
        }
        return sender.send(serverUrl, deviceKeys, body, v2, maxRetries(context.notification()));
    }

    /**
     * 批量失败是否值得降级为逐设备重试。
     *
     * <p>PRD 5.3 要求批量请求失败后**必须**降级为逐设备重试，因为批量响应无法区分是哪台设备出错；
     * 且明确写了「若逐设备仍全部失败，判定为服务端或网络问题」，即服务端级故障同样要走降级。
     *
     * <p>两类例外——逐设备重试不会改变结果，或会把不确定的失败放大成确定的重复：
     * <ul>
     *   <li><b>加密配置错误</b>：属全局性错误，逐设备重试只会把同一失败重复 N 遍。</li>
     *   <li><b>请求超时</b>：无法区分「请求未发出」与「服务端已处理、响应丢失」。后者若再逐设备
     *       重发，会把 1 条不确定的重复放大成 N 条确定的重复；因此整组按超时标记失败，
     *       宁可漏掉这一条（有 ERROR 日志）也不制造 N 条重复。</li>
     * </ul>
     */
    static boolean worthDegrading(BarkRequestSender.Outcome batch) {
        return batch.failure() != DeliveryFailure.ENCRYPTION
                && batch.failure() != DeliveryFailure.TIMEOUT;
    }

    private static int maxRetries(PushNotification notification) {
        return notification.category() == NotificationCategory.SECURITY
                ? BarkConstants.SECURITY_MAX_RETRIES
                : BarkConstants.STANDARD_MAX_RETRIES;
    }

    /**
     * 通知分组 = 基础分组 + 事件类别，例如「雾起时 · 互动」。
     *
     * <p>按类别拆分是为了让用户能在 iOS 通知中心**长按某一组单独静音**——例如只静音互动类、
     * 保留安全类提醒。Bark 的 {@code group} 正是这个粒度。
     */
    private String resolveGroup(PushSettings settings, NotificationCategory category) {
        return resolveGroupBase(settings) + " · " + categoryGroupLabel(category);
    }

    private static String categoryGroupLabel(NotificationCategory category) {
        return switch (category) {
            case SECURITY -> "安全";
            case INTERACTIVE -> "互动";
            case NORMAL -> "普通";
        };
    }

    private String resolveGroupBase(PushSettings settings) {
        var configured = settings.group();
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        var title = siteBrand.title();
        return title == null || title.isBlank() ? PushSettings.GROUP_FALLBACK : title.trim();
    }

    private String resolveIcon(PushSettings settings) {
        var configured = settings.icon();
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        var logo = siteBrand.logo();
        return logo == null ? "" : logo.trim();
    }

    private void logSummary(DeliveryReport report) {
        var failed = report.failureCount();
        if (failed == 0) {
            log.debug("{} 投递完成：{} 台设备全部成功", BarkConstants.LOG_PREFIX, report.results().size());
            return;
        }
        if (report.successCount() == 0) {
            log.error("{} 投递失败：全部 {} 台设备均未成功", BarkConstants.LOG_PREFIX, failed);
        } else {
            log.warn("{} 部分设备投递失败：成功 {} 台，失败 {} 台",
                    BarkConstants.LOG_PREFIX, report.successCount(), failed);
        }
        report.results().stream()
                .filter(result -> !result.success())
                .forEach(result -> log.warn("{} 设备「{}」投递失败：{}", BarkConstants.LOG_PREFIX,
                        result.deviceName(), result.message()));
    }

    /**
     * 一次投递中不变的输入，避免在按设备重试时重复解析。
     *
     * @param pushGroup 已解析的通知分组（配置留空时取站点标题，再失败回退 {@code Halo}）
     * @param pushIcon 已解析的图标地址（配置留空时取站点 Logo）
     */
    private record DeliveryContext(PushNotification notification, PushSettings settings,
                                   String pushGroup, String pushIcon) {
    }
}
