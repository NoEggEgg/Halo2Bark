package com.wuqishi.halo.bark.endpoint;

import com.wuqishi.halo.bark.BarkConstants;
import com.wuqishi.halo.bark.Masking;
import com.wuqishi.halo.bark.config.DeviceSetting;
import com.wuqishi.halo.bark.config.PushSettings;
import com.wuqishi.halo.bark.config.SecretAccessor;
import com.wuqishi.halo.bark.config.SettingsValidator;
import com.wuqishi.halo.bark.push.NotificationCategory;
import com.wuqishi.halo.bark.push.PushNotification;
import com.wuqishi.halo.bark.transport.BarkServerProbe;
import com.wuqishi.halo.bark.transport.BarkTransport;
import com.wuqishi.halo.bark.transport.DeliveryFailure;
import com.wuqishi.halo.bark.transport.DeliveryReport;
import com.wuqishi.halo.bark.transport.DeviceResult;
import com.wuqishi.halo.bark.transport.ServerInfo;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 「测试该设备」的两级校验逻辑（PRD 5.6）。
 *
 * <p>从配置页提交的**单台设备表单值**（含未保存值）出发：
 * <ul>
 *   <li>第一级：{@code GET {serverUrl}/info} 探测服务可达性与版本号；不可达则不再发送。</li>
 *   <li>第二级：用该设备的 Key 发送一条固定测试消息，验证凭据有效性。</li>
 * </ul>
 *
 * <p>规则：测试消息不归档、不受事件开关 / 合并 / 限流 / 静默时段影响、加密开启时同样加密发送
 * （全局加密配置来自已保存配置）。一台设备的测试结果不影响其他设备，且不写回配置。
 *
 * <p>本类是纯阻塞逻辑（内部发起 HTTP 请求），必须由调用方放到投递线程执行；
 * 返回 {@code null} 表示成功，否则返回 RFC 7807 {@link TestProblem}。
 */
@Slf4j
@Component
public class DeviceTester {

    private static final DateTimeFormatter SEND_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 解析 Secret 引用的等待上限；本地资源读取，正常远快于此。 */
    private static final Duration KEY_RESOLVE_TIMEOUT = Duration.ofSeconds(5);

    private final BarkServerProbe probe;
    private final BarkTransport transport;
    private final Supplier<PushSettings> settings;
    private final Supplier<List<String>> configIssues;
    private final SecretAccessor secretAccessor;
    private final Clock clock;

    public DeviceTester(BarkServerProbe probe, BarkTransport transport, Supplier<PushSettings> settings,
            Supplier<List<String>> configIssues, SecretAccessor secretAccessor, Clock clock) {
        this.probe = probe;
        this.transport = transport;
        this.settings = settings;
        this.configIssues = configIssues;
        this.secretAccessor = secretAccessor;
        this.clock = clock;
    }

    /**
     * 执行两级校验。
     *
     * @return {@code null} 表示测试通过；否则为 RFC 7807 失败详情
     */
    public TestProblem test(DeviceTestRequest request) {
        var name = blankToNull(request.name());
        var keyReference = blankToNull(request.key());
        var serverUrl = blankToNull(request.serverUrl());

        // 已保存配置本身非法：不发起任何网络请求，直接把问题返回给用户。
        var issues = configIssues.get();
        if (issues != null && !issues.isEmpty()) {
            log.error("{} 推送配置校验未通过，测试未执行：{}", BarkConstants.LOG_PREFIX,
                    Masking.sanitize(issues.toString()));
            return problem(400, "推送配置校验未通过，已停止推送",
                    String.join("；", issues) + "。请到插件设置页修正后再测试。");
        }

        if (name == null) {
            return problem(400, "设备名称不能为空", "请填写设备名称以标识该设备。");
        }
        if (keyReference == null) {
            return problem(400, "设备 Key 不能为空", "请在 Bark App 首页复制设备 Key 后填入。");
        }
        if (serverUrl == null) {
            return problem(400, "Bark 服务地址不能为空",
                    "请填写该设备所属的 Bark 服务地址（官方地址保持默认即可）。");
        }

        // 表单值未经过保存校验，却会被当作出网目标直接使用：必须先按出网目标检查一遍
        var urlIssue = SettingsValidator.inspectUrl(serverUrl);
        if (urlIssue != SettingsValidator.UrlIssue.NONE) {
            log.warn("{} 测试设备的服务地址不可作为出网目标：{}", BarkConstants.LOG_PREFIX,
                    urlIssue.message());
            return problem(400, "Bark 服务地址不可用", urlIssue.message() + "。请修正后重试。");
        }

        // 表单提交的是 Secret 名称——{@code secret} 组件只保存引用，实际 Key 必须服务端解析。
        // 放在出网目标校验之后：地址本身不合法时无需读取凭据。
        var key = resolveKey(keyReference);
        if (key == null) {
            log.warn("{} 测试设备的密钥引用读不到：Secret「{}」", BarkConstants.LOG_PREFIX, keyReference);
            return problem(400, "设备密钥读不到",
                    "找不到密钥「" + keyReference + "」，或其中缺少 " + SecretAccessor.CREDENTIAL_KEY
                            + " 字段。请在设备项中重新选择或创建密钥后重试。");
        }

        // 测试用于验证「设备能否接收」，与「是否参与正式通知」的启用开关无关，故强制可投递
        var device = new DeviceSetting(name, key, serverUrl, Boolean.TRUE);
        log.info("{} 测试设备「{}」(Key {})", BarkConstants.LOG_PREFIX, device.name(),
                Masking.maskKey(device.key()));

        // 第一级 · 服务可达性
        ServerInfo info = probe.probe(device.serverUrl(), true);
        if (!info.reachable()) {
            log.warn("{} 测试设备「{}」第一级失败：服务不可达 {}", BarkConstants.LOG_PREFIX,
                    device.name(), Masking.maskUrl(device.serverUrl()));
            return problem(400, "服务地址不可达或不是 Bark 服务",
                    "请检查服务地址是否正确，以及它是否指向一个 Bark 服务端。");
        }

        // 第二级 · 设备凭据有效性（真实推送一条固定测试消息）
        PushNotification notification = buildTestNotification(device);
        DeliveryReport report = transport.send(notification, settings.get(), List.of(device));
        if (report.results().isEmpty()) {
            log.warn("{} 测试设备「{}」未产生投递结果（设备不满足投递条件）", BarkConstants.LOG_PREFIX,
                    device.name());
            return problem(400, "测试未执行", "设备不满足投递条件，请检查设备 Key 与服务地址。");
        }
        DeviceResult result = report.results().get(0);
        if (result.success()) {
            log.info("{} 测试设备「{}」成功（耗时 {} ms）", BarkConstants.LOG_PREFIX,
                    device.name(), result.elapsedMillis());
            return null;
        }
        return mapFailure(result);
    }

    /**
     * 把设备表单提交的 Secret 名称解析为实际 Key。
     *
     * <p>本方法运行在投递线程（见 {@link BarkTestEndpoint}），阻塞等待一次本地 Secret 读取是允许的。
     * 读取失败按「取不到」处理：Secret 名称本身不是可用凭据。
     *
     * @return 实际 Key；引用读不到时返回 {@code null}
     */
    private String resolveKey(String secretName) {
        try {
            return secretAccessor.read(secretName, SecretAccessor.CREDENTIAL_KEY)
                    .block(KEY_RESOLVE_TIMEOUT)
                    .orElse(null);
        } catch (RuntimeException e) {
            log.warn("{} 解析设备密钥引用失败（Secret「{}」）：{}", BarkConstants.LOG_PREFIX,
                    secretName, Masking.sanitize(e.toString()));
            return null;
        }
    }

    private TestProblem mapFailure(DeviceResult result) {
        return switch (result.failure()) {
            case ENCRYPTION -> problem(400, "加密配置有误",
                    "加密密钥或算法配置不正确，未发送测试消息。");
            case CLIENT_ERROR -> problem(400, "设备 Key 无效",
                    "Bark 服务端返回 4xx，设备 Key 可能错误，请从 Bark App 首页重新复制。");
            case SERVER_ERROR -> problem(502, "Bark 服务端暂时不可用",
                    "服务端返回 5xx，请稍后重试。");
            case TIMEOUT -> problem(400, "连接 Bark 服务端超时",
                    "请检查网络或服务地址是否可达。");
            case UNREACHABLE -> problem(400, "服务地址不可达",
                    "请检查服务地址是否正确、是否为 Bark 服务。");
            case UNEXPECTED -> problem(400, "测试时发生未预期错误",
                    Masking.sanitize(result.message()));
        };
    }

    /** 构造固定测试消息：不归档，分级为普通（不受静默 / 合并 / 限流约束）。 */
    private PushNotification buildTestNotification(DeviceSetting device) {
        var now = SEND_TIME.format(LocalDateTime.now(clock));
        return new PushNotification(
                "🔔 Halo2Bark 测试通知",
                device.name(),
                "设备「" + device.name() + "」\n发送时间：" + now,
                "",
                NotificationCategory.NORMAL,
                false);
    }

    private static TestProblem problem(int status, String title, String detail) {
        return new TestProblem(title, status, detail);
    }

    /** 空白视为未填写；否则去掉首尾空格——粘贴进来的值常带空格，直接拼进 URL 会失败。 */
    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        var trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
