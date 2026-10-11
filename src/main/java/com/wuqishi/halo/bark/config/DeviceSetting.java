package com.wuqishi.halo.bark.config;

/**
 * 单台接收设备的配置。
 *
 * <p>服务地址是**设备级**配置：每台设备可指向不同的 Bark 服务端，因此不存在全局服务地址。
 *
 * <p>{@code enabled} 声明为包装类型：ConfigMap 中若缺少该字段，按 PRD 5.3 的默认值 true 处理。
 * {@code serverUrl} 仅在为 {@code null}（字段缺失）时取默认值，空白保留原样交由校验判定。
 *
 * @param name 设备名称，用于在通知与日志中标识设备
 * @param key Bark 设备 Key；为空白表示配置不完整，该设备不参与投递。
 *        <b>此处承载的是已解析的实际 Key</b>——ConfigMap 中保存的是 Secret 名称，
 *        由 {@link CredentialResolver} 在配置加载时替换（PRD 8.2）
 * @param serverUrl 该设备所属的 Bark 服务地址
 * @param enabled 是否参与投递
 */
public record DeviceSetting(String name, String key, String serverUrl, Boolean enabled) {

    /** 新建设备时预填的官方服务地址。 */
    public static final String DEFAULT_SERVER_URL = "https://api.day.app";

    public DeviceSetting {
        name = ConfigValues.textOrEmpty(name);
        key = ConfigValues.textOrEmpty(key);
        serverUrl = ConfigValues.textUnlessNull(serverUrl, DEFAULT_SERVER_URL);
        enabled = ConfigValues.flagOrTrue(enabled);
    }

    /**
     * 设备是否具备投递条件：已启用、设备 Key 非空、服务地址非空且结构上可作为出网目标。
     *
     * <p>服务地址的结构问题在这里就地把该设备排除，而不是升级为全局问题：一台设备的地址笔误
     * 只应影响那一台设备，其他设备照常投递（PRD 5.3、5.7）。
     */
    public boolean deliverable() {
        return Boolean.TRUE.equals(enabled) && !key.isBlank() && !serverUrl.isBlank()
                && SettingsValidator.inspectUrl(serverUrl) == SettingsValidator.UrlIssue.NONE;
    }
}
