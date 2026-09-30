package com.wuqishi.halo.bark.transport;

/**
 * Bark 服务端的探测结果：可达性、版本号与 V2 支持情况。
 *
 * <p>对应 PRD 5.6 的第一级校验（服务可达性）与 PRD 5.2 的版本探测。
 *
 * @param reachable 服务是否可达
 * @param version 服务端版本号；不可达时为空字符串
 * @param v2Supported 是否支持 V2 API；不可达时为 {@code false}（按 PRD 5.2 回退 V1）
 * @param failure 不可达时的失败分类
 * @param message 可操作的提示文案
 */
public record ServerInfo(
        boolean reachable,
        String version,
        boolean v2Supported,
        DeliveryFailure failure,
        String message) {

    /** V2 的起始主版本号。 */
    private static final int V2_MAJOR = 2;

    /** 文档约定的版本形态：可选前导 v，紧跟主版本号，其后可选 .次版本(.修订)?。 */
    private static final java.util.regex.Pattern MAJOR_VERSION =
            java.util.regex.Pattern.compile("^v?(\\d+)(?:\\.\\d+(?:\\.\\d+)?)?$");

    public ServerInfo {
        version = version == null ? "" : version;
        message = message == null ? "" : message;
    }

    /**
     * 构造可达结果，并据版本号判定 V2 支持情况。
     *
     * <p>版本形如 {@code v2.3.6} 或 {@code 2.0.0}；取主版本号与 2 比较。
     * 版本号缺失或无法解析时判定为不支持 V2——未探测到 V2 即回退 V1（PRD 5.2）。
     */
    public static ServerInfo reachable(String version) {
        var normalized = version == null ? "" : version.trim();
        return new ServerInfo(true, normalized, majorVersion(normalized) >= V2_MAJOR, null, "");
    }

    /** 构造不可达结果。 */
    public static ServerInfo unreachable(DeliveryFailure failure, String message) {
        return new ServerInfo(false, "", false, failure, message);
    }

    private static int majorVersion(String version) {
        // 仅接受文档约定的版本形态：v?MAJOR(.MINOR(.PATCH)?)?，且主版本号必须是前缀中的第一段数字。
        // 形如 release-2026 / 1.2.3-beta 等杂项字符串一律判为无法解析（返回 -1 → 回退 V1），
        // 避免把无关数字误判为 V2 而选错协议。
        var matcher = MAJOR_VERSION.matcher(version);
        if (!matcher.matches()) {
            return -1;
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
