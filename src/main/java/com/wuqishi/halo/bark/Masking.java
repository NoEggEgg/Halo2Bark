package com.wuqishi.halo.bark;

import java.net.URI;
import java.util.regex.Pattern;

/**
 * 脱敏工具。
 *
 * <p>PRD 5.8 要求：日志与错误信息中禁止出现设备 Key 明文与完整请求 URL。
 *
 * <p>两类风险来源：
 * <ul>
 *   <li>我们自己拼出的地址——用 {@link #maskUrl(String)} 只保留 {@code scheme://host[:port]}。</li>
 *   <li>**异常自带的信息**——{@code URI.create} 非法时会把完整 URI 写进异常消息，
 *       而 V1 的请求路径形如 {@code {serverUrl}/{设备Key}}，等于把设备 Key 打进日志。
 *       这类文本必须用 {@link #sanitize(String)} 剔除后再记录。</li>
 * </ul>
 */
public final class Masking {

    private static final String UNKNOWN = "(未知)";
    private static final String HIDDEN_URL = "(地址已隐藏)";
    private static final Pattern URL_LIKE = Pattern.compile("(?i)https?://\\S+");

    private Masking() {
    }

    /**
     * 剔除文本中形如 URL 的片段。
     *
     * <p>用于异常消息、第三方文本等**非我们构造**的字符串——只靠 {@link #maskUrl(String)}
     * 无法处理，因为它们的地址不在我们手里。
     *
     * @param text 待清理文本；为 {@code null} 或空白时返回空字符串
     */
    public static String sanitize(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return URL_LIKE.matcher(text).replaceAll(HIDDEN_URL);
    }

    /**
     * 掩码设备 Key：保留首 2 位与末 2 位，中间以 {@code ****} 代替。
     *
     * @param key 设备 Key；为 {@code null} 或空白时返回长度无关的统一占位
     */
    public static String maskKey(String key) {
        if (key == null || key.isBlank()) {
            return "(空)";
        }
        var value = key.trim();
        if (value.length() <= 4) {
            return "****";
        }
        return value.substring(0, 2) + "****" + value.substring(value.length() - 2);
    }

    /**
     * 掩码服务地址：只保留 {@code scheme://host[:port]}，丢弃路径与查询串。
     *
     * <p>目的：日志需要指明是哪一台服务端出了问题，但不能暴露完整请求地址。
     * 无法解析时返回 {@code (未知)}，绝不回传原始字符串。
     */
    public static String maskUrl(String url) {
        if (url == null || url.isBlank()) {
            return UNKNOWN;
        }
        try {
            var uri = URI.create(url.trim());
            if (uri.getScheme() == null || uri.getHost() == null) {
                return UNKNOWN;
            }
            var port = uri.getPort() > 0 ? ":" + uri.getPort() : "";
            return uri.getScheme() + "://" + uri.getHost() + port;
        } catch (IllegalArgumentException e) {
            return UNKNOWN;
        }
    }
}
