package com.wuqishi.halo.bark.config;

import com.wuqishi.halo.bark.pipeline.QuietHours;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 配置校验，对应 PRD 5.7 的三条规则。
 *
 * <p>服务地址是设备级配置，因此逐个设备校验，并在提示中指明是哪一台设备（PRD 10.6.2 反馈必须可操作）。
 * 服务地址为空**不**判为不合法——按 PRD 5.3，配置不完整的设备可保存、但该设备不参与投递；
 * 只有填写了却不合法才拒绝保存。
 *
 * <p>加密参数的取值与长度约束对照 Bark App 的 {@code Model/Algorithm.swift}：
 * 算法 AES128 / AES192 / AES256，模式 CBC / ECB / GCM，填充 pkcs7 / noPadding，
 * 密钥长度由算法决定（16 / 24 / 32）。
 *
 * <p>纯逻辑，不依赖 Halo 运行时。
 */
public final class SettingsValidator {

    /** 内容长度上限的下界（PRD 5.7）。 */
    public static final int MIN_CONTENT_MAX_LENGTH = 50;

    /** 内容长度上限的上界（PRD 5.7）。 */
    public static final int MAX_CONTENT_MAX_LENGTH = 500;

    /** Bark 支持的加密算法。 */
    public static final List<String> ENCRYPT_ALGORITHMS = List.of("AES128", "AES192", "AES256");

    /** Bark 支持的加密模式。 */
    public static final List<String> ENCRYPT_MODES = List.of("CBC", "ECB", "GCM");

    /** Bark 支持的填充方式。 */
    public static final List<String> ENCRYPT_PADDINGS = List.of("pkcs7", "noPadding");

    private static final String UNNAMED_DEVICE = "(未命名设备)";
    private static final String CONTENT_MAX_LENGTH_FIELD = "内容长度上限";
    private static final String ENCRYPT_ALGORITHM_FIELD = "加密算法";
    private static final String ENCRYPT_MODE_FIELD = "加密模式";
    private static final String ENCRYPT_PADDING_FIELD = "填充方式";
    private static final String ENCRYPT_KEY_FIELD = "加密密钥";

    private SettingsValidator() {
    }

    /**
     * 校验配置。
     *
     * @return 违规清单；为空表示配置合法
     */
    public static List<String> validate(BarkSettings settings) {
        var issues = new ArrayList<String>();
        var push = settings.push();
        push.devices().forEach(device -> validateServerUrl(device, issues));
        validateContentMaxLength(push.contentMaxLength(), issues);
        validateEncryption(push, issues);
        validateQuietHours(push, issues);
        validateVolume(push, issues);
        return List.copyOf(issues);
    }

    /**
     * 服务地址的结构性问题；{@link #NONE} 表示该地址可作为出网目标。
     *
     * <p>出网目标必须可以安全地在其后拼接 {@code /info} 与 {@code /push}，
     * 因此不接受查询串、片段与用户信息——它们会让拼接结果落进错误的位置，
     * 也会让地址指向的地址与管理员看到的不一致。
     */
    public enum UrlIssue {

        /** 无问题。 */
        NONE(""),
        /** 无法解析为 URI。 */
        MALFORMED("不是合法地址"),
        /** 协议不是 http / https。 */
        SCHEME("必须以 http:// 或 https:// 开头"),
        /** 缺少主机名。 */
        NO_HOST("缺少主机名"),
        /** 含有 user:pass@ 形式的用户信息。 */
        USER_INFO("不能包含用户信息（形如 user:pass@host）"),
        /** 含有查询串或片段。 */
        QUERY_OR_FRAGMENT("不能包含查询串或片段——插件会在其后拼接 /info 与 /push"),
        /** 以斜杠结尾，拼接后会产生双斜杠。 */
        TRAILING_SLASH("不能以斜杠结尾");

        private final String message;

        UrlIssue(String message) {
            this.message = message;
        }

        /** 面向管理员的问题描述。 */
        public String message() {
            return message;
        }
    }

    /**
     * 检查一个将作为出网目标的服务地址。
     *
     * <p>保存的配置与「测试该设备」提交的**未保存**表单值都要走这条检查：后者绕过了保存校验，
     * 若不检查就会被当作出网目标直接使用。
     *
     * @return 结构性问题；{@link UrlIssue#NONE} 表示可作为出网目标
     */
    public static UrlIssue inspectUrl(String serverUrl) {
        if (serverUrl == null || serverUrl.isBlank()) {
            return UrlIssue.MALFORMED;
        }
        URI uri;
        try {
            uri = URI.create(serverUrl);
        } catch (IllegalArgumentException e) {
            return UrlIssue.MALFORMED;
        }
        var scheme = uri.getScheme();
        if (scheme == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            return UrlIssue.SCHEME;
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            return UrlIssue.NO_HOST;
        }
        if (uri.getRawUserInfo() != null) {
            return UrlIssue.USER_INFO;
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            return UrlIssue.QUERY_OR_FRAGMENT;
        }
        if (serverUrl.endsWith("/")) {
            return UrlIssue.TRAILING_SLASH;
        }
        return UrlIssue.NONE;
    }

    /** 算法对应的密钥长度；不支持的算法返回 -1。 */
    public static int expectedKeyLength(String algorithm) {
        return switch (algorithm == null ? "" : algorithm) {
            case "AES128" -> 16;
            case "AES192" -> 24;
            case "AES256" -> 32;
            default -> -1;
        };
    }

    private static void validateServerUrl(DeviceSetting device, List<String> issues) {
        var serverUrl = device.serverUrl();
        if (serverUrl == null || serverUrl.isBlank()) {
            return;
        }
        var issue = inspectUrl(serverUrl);
        if (issue == UrlIssue.NONE) {
            return;
        }
        var hint = switch (issue) {
            case MALFORMED -> "请检查地址拼写，确认它是可访问的 Bark 服务地址。";
            case SCHEME -> "请补全协议头。";
            case NO_HOST -> "请填写完整地址，例如 https://api.day.app。";
            case TRAILING_SLASH -> "请删除末尾的 /。";
            default -> "请修正后重试。";
        };
        issues.add("设备「%s」的 Bark 服务地址".formatted(deviceLabel(device))
                + issue.message() + "（当前：" + serverUrl + "）。" + hint);
    }

    private static void validateContentMaxLength(String raw, List<String> issues) {
        var value = ConfigValues.intOrNull(raw);
        var range = MIN_CONTENT_MAX_LENGTH + "–" + MAX_CONTENT_MAX_LENGTH;
        if (value == null) {
            issues.add(CONTENT_MAX_LENGTH_FIELD + "必须是 " + range + " 之间的整数（当前："
                    + ConfigValues.textOrEmpty(raw) + "）。");
            return;
        }
        if (value < MIN_CONTENT_MAX_LENGTH || value > MAX_CONTENT_MAX_LENGTH) {
            issues.add(CONTENT_MAX_LENGTH_FIELD + "必须在 " + range + " 之间（当前：" + value
                    + "）。请调整到该范围内。");
        }
    }

    /**
     * 静默时段边界（HH:mm）格式校验：沿用 {@link QuietHours} 的 STRICT 解析口径，
     * 与运行时判定保持一致。任一边界缺失/非法、或起止相同时，运行时按「未设置」处理，
     * 这里仅在格式明显非法时拒绝保存，避免配置笔误让静默时段静默失效（PRD 5.4）。
     */
    private static void validateQuietHours(PushSettings push, List<String> issues) {
        validateQuietTime("静默开始", push.quietHoursFrom(), issues);
        validateQuietTime("静默结束", push.quietHoursTo(), issues);
    }

    private static void validateQuietTime(String field, String raw, List<String> issues) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        if (QuietHours.parse(raw) == null) {
            issues.add(field + "必须是 24 小时制时间（如 23:00），当前：" + raw
                    + "。请使用 00:00–23:59 的格式。");
        }
    }

    /**
     * critical 告警音量（0–10）校验：与 {@link com.wuqishi.halo.bark.transport.BarkPayloadFactory#volume}
     * 的传输层口径一致。留空表示使用系统默认，不报错。
     */
    private static void validateVolume(PushSettings push, List<String> issues) {
        var raw = push.volume();
        if (raw == null || raw.isBlank()) {
            return; // 留空表示使用系统默认音量
        }
        var parsed = ConfigValues.intOrNull(raw);
        if (parsed == null || parsed < 0 || parsed > 10) {
            issues.add("critical 告警音量必须是 0–10 的整数（当前：" + raw
                    + "）。留空表示使用系统默认。");
        }
    }

    private static void validateEncryption(PushSettings push, List<String> issues) {
        if (!Boolean.TRUE.equals(push.encryptEnabled())) {
            return;
        }
        if (!ENCRYPT_ALGORITHMS.contains(push.encryptAlgorithm())) {
            issues.add(ENCRYPT_ALGORITHM_FIELD + "不支持「" + push.encryptAlgorithm() + "」。可选："
                    + String.join(" / ", ENCRYPT_ALGORITHMS) + "，请与 Bark App 中的设置保持一致。");
        }
        if (!ENCRYPT_MODES.contains(push.encryptMode())) {
            issues.add(ENCRYPT_MODE_FIELD + "不支持「" + push.encryptMode() + "」。可选："
                    + String.join(" / ", ENCRYPT_MODES) + "，请与 Bark App 中的设置保持一致。");
        }
        if (!ENCRYPT_PADDINGS.contains(push.encryptPadding())) {
            issues.add(ENCRYPT_PADDING_FIELD + "不支持「" + push.encryptPadding() + "」。可选："
                    + String.join(" / ", ENCRYPT_PADDINGS) + "，请与 Bark App 中的设置保持一致。");
        }

        var key = push.encryptKey();
        if (key == null || key.isBlank()) {
            // 「缺失」与「引用的 Secret 读不到」在这里合并为同一句：两者对用户的处置动作相同
            // （到设置页重新选择密钥），分开表述只会让同一次配置错误出现两条含义相近的提示。
            issues.add(ENCRYPT_KEY_FIELD + "缺失或无法读取，但已启用加密推送。请到插件设置页重新选择密钥"
                    + "（密钥内容需与 Bark App 首页 → 推送加密 → 加密设置 中的一致）。");
            return;
        }
        // 与 PayloadEncryptor 同一口径：按 UTF-8 字节数校验，且不 trim（空格是合法密钥字节）。
        // 只按字符数校验会让「16 个字符但含多字节字符」的密钥通过保存、却在每次投递时失败。
        var expected = expectedKeyLength(push.encryptAlgorithm());
        if (expected > 0) {
            var bytes = key.getBytes(StandardCharsets.UTF_8).length;
            if (bytes != expected) {
                issues.add(ENCRYPT_KEY_FIELD + "必须是 " + expected + " 个字节（" + push.encryptAlgorithm()
                        + " 要求，按 UTF-8 计算，当前 " + bytes + " 个字节）。"
                        + "请与 Bark App 中的密钥逐字核对，注意空格也算一个字节。");
            }
        }
    }

    private static String deviceLabel(DeviceSetting device) {
        return device.name().isBlank() ? UNNAMED_DEVICE : device.name();
    }
}
