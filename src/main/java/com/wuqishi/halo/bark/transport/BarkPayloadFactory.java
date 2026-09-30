package com.wuqishi.halo.bark.transport;

import com.wuqishi.halo.bark.BarkConstants;
import com.wuqishi.halo.bark.config.ConfigValues;
import com.wuqishi.halo.bark.json.Json;
import com.wuqishi.halo.bark.push.NotificationCategory;
import com.wuqishi.halo.bark.push.PushNotification;
import com.wuqishi.halo.bark.config.PushSettings;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 构造 Bark 请求参数。
 *
 * <p>参数取值全部对照 Bark V2 API 文档（PRD 5.2）：
 * <ul>
 *   <li>{@code isArchive} / {@code call} / {@code automaticallyCopy} 为字符串且取值恒为 {@code "1"}。</li>
 *   <li>{@code ttl} 为**整数秒**，因此配置中的「天」需换算（{@code 1d → 86400}）。</li>
 *   <li>{@code badge} 为整数。</li>
 *   <li>V1 回退时不含 {@code device_keys} / {@code isArchive} / {@code ttl}（PRD 5.2）。</li>
 * </ul>
 *
 * <p>本类不感知事件语义：级别由 {@link NotificationCategory} 与配置共同决定（PRD 5.2 级别配置规则）。
 */
@Component
public class BarkPayloadFactory {

    /** Bark 接受的级别取值；配置值不在其中时回退为 {@code active}。 */
    private static final Set<String> LEVELS = Set.of("active", "timeSensitive", "critical", "passive");

    /** 安全事件固定级别（PRD 5.2：不可配置）。 */
    private static final String CRITICAL = "critical";

    private static final String FLAG_ON = "1";
    private static final String ACTION_NONE = "none";
    private static final int MIN_VOLUME = 0;
    private static final int MAX_VOLUME = 10;
    private static final Pattern TTL_DAYS = Pattern.compile("^(\\d+)d$");

    private final PayloadEncryptor encryptor;

    public BarkPayloadFactory(PayloadEncryptor encryptor) {
        this.encryptor = encryptor;
    }

    /**
     * 构造推送参数。
     *
     * @param notification 待推送通知
     * @param settings 全局推送配置
     * @param deviceKeys 本次请求的目标设备 Key；批量时多于 1 个
     * @param group 已解析的通知分组（配置留空时已由调用方取站点名称）
     * @param icon 已解析的图标地址（配置留空时已由调用方取站点 Logo）
     * @param v2 服务端是否支持 V2；为 {@code false} 时按 V1 参数集构造
     */
    public Map<String, Object> params(PushNotification notification, PushSettings settings,
            List<String> deviceKeys, String group, String icon, boolean v2) {
        var params = new LinkedHashMap<String, Object>();
        put(params, "title", notification.title());
        put(params, "subtitle", notification.subtitle());
        // body 在 Bark V2 参数表中是唯一未标记 optional 的字段，因此即使为空也下发；
        // 同时以 markdown 下发同一份文本——Bark 会用 markdown 渲染并忽略 body，
        // 纯文本内容在 Markdown 下等价呈现，因此可安全地「总是开启」。
        params.put("body", notification.body());
        put(params, "markdown", notification.body());
        put(params, "url", notification.url());

        if (v2 && deviceKeys.size() > 1) {
            params.put("device_keys", List.copyOf(deviceKeys));
        } else {
            params.put("device_key", deviceKeys.get(0));
        }

        params.put("level", level(notification, settings));
        put(params, "group", group);
        // 逐条图标（评论者头像）优先；为空时用调用方解析好的全局图标（配置项或站点 Logo）
        put(params, "icon", notification.icon().isBlank() ? icon : notification.icon());
        put(params, "image", notification.image());
        put(params, "sound", settings.sound());

        if (settings.badgeActive()) {
            var badge = ConfigValues.intOrNull(settings.badge());
            if (badge != null) {
                params.put("badge", badge);
            }
        }

        if (notification.category() == NotificationCategory.SECURITY) {
            if (Boolean.TRUE.equals(settings.securityCall())) {
                params.put("call", FLAG_ON);
            }
            put(params, "volume", volume(settings.volume()));
        }

        if (v2 && Boolean.TRUE.equals(settings.archiveEnabled()) && notification.archive()) {
            params.put("isArchive", FLAG_ON);
            var ttl = ttlSeconds(settings.archiveTtl());
            if (ttl != null) {
                params.put("ttl", ttl);
            }
        }

        if (Boolean.TRUE.equals(settings.autoCopy())) {
            // Bark 的自动复制开关参数名为 automaticallyCopy（非 autoCopy），发错参数会导致功能静默失效。
            params.put("automaticallyCopy", FLAG_ON);
            put(params, "copy", settings.copy());
        }

        if (ACTION_NONE.equals(settings.action())) {
            params.put("action", ACTION_NONE);
        }
        return params;
    }

    /**
     * 生成最终请求体：未启用加密时直接序列化参数；启用加密时把除设备 Key 外的参数整体加密。
     *
     * @throws EncryptionException 加密失败；调用方必须让该次投递失败，不得改为明文发送（PRD 5.2）
     */
    public String body(Map<String, Object> params, PushSettings settings) throws EncryptionException {
        if (!Boolean.TRUE.equals(settings.encryptEnabled())) {
            return Json.write(params);
        }
        var plaintext = new LinkedHashMap<>(params);
        plaintext.remove("device_key");
        plaintext.remove("device_keys");
        var result = encryptor.encrypt(Json.write(plaintext), settings);

        var envelope = new LinkedHashMap<String, Object>();
        var single = params.get("device_key");
        if (single != null) {
            envelope.put("device_key", single);
        } else {
            envelope.put("device_keys", params.get("device_keys"));
        }
        envelope.put("ciphertext", result.ciphertext());
        if (result.iv() != null) {
            envelope.put("iv", result.iv());
        }
        return Json.write(envelope);
    }

    /** 级别：安全类固定 critical；其余按配置，配置值非法时回退该类别的默认值。 */
    private static String level(PushNotification notification, PushSettings settings) {
        return switch (notification.category()) {
            case SECURITY -> CRITICAL;
            case INTERACTIVE -> validLevel(settings.interactiveLevel(),
                    PushSettings.DEFAULT_INTERACTIVE_LEVEL);
            case NORMAL -> validLevel(settings.normalLevel(), PushSettings.DEFAULT_NORMAL_LEVEL);
        };
    }

    private static String validLevel(String configured, String fallback) {
        return LEVELS.contains(configured) ? configured : fallback;
    }

    /**
     * 把归档保留时长换算为 Bark 要求的整数秒。
     *
     * @return {@code null} 表示不下发该参数——「永久」以省略 {@code ttl} 表达，交由服务端默认策略处理
     */
    static Integer ttlSeconds(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        var value = raw.trim();
        if ("0".equals(value)) {
            return null;
        }
        var matcher = TTL_DAYS.matcher(value);
        if (matcher.matches()) {
            try {
                long days = Long.parseLong(matcher.group(1));
                // 防止「任意长位数 × 86400」溢出：先按上限钳制，再换算
                if (days > Integer.MAX_VALUE / (long) BarkConstants.SECONDS_PER_DAY) {
                    return Integer.MAX_VALUE;
                }
                long seconds = days * BarkConstants.SECONDS_PER_DAY;
                return seconds > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) seconds;
            } catch (NumberFormatException e) {
                // 位数过多导致 Long 溢出：钳制为上限（约 68 年），绝不抛异常令投递失败
                return Integer.MAX_VALUE;
            }
        }
        var parsed = ConfigValues.intOrNull(value);
        return parsed == null || parsed <= 0 ? null : parsed;
    }

    /** critical 告警音量：仅接受 0–10（PRD 5.2）；越界或非法时返回空字符串（不下发）。 */
    private static String volume(String raw) {
        var parsed = ConfigValues.intOrNull(raw);
        if (parsed == null || parsed < MIN_VOLUME || parsed > MAX_VOLUME) {
            return "";
        }
        return String.valueOf(parsed);
    }

    private static void put(Map<String, Object> params, String key, String value) {
        if (value != null && !value.isBlank()) {
            params.put(key, value);
        }
    }
}
