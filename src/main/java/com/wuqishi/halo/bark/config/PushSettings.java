package com.wuqishi.halo.bark.config;

import java.util.List;
import java.util.Objects;

/**
 * 「推送配置」分组的不可变快照，对应 PRD 8.2 中 {@code group: push} 的表单。
 *
 * <p>构造时对缺失值补齐 PRD 声明的默认值，保证下游不出现 {@code null}（PRD 8.3）。
 * 数值字段以字符串承载，理由见 {@link ConfigValues}。
 *
 * <p>服务地址不在本记录中——它是设备级配置，见 {@link DeviceSetting#serverUrl()}。
 *
 * @param devices 接收设备列表
 * @param group Bark 通知分组；**留空表示使用站点名称**，站点名称读取失败时回退 {@link #GROUP_FALLBACK}
 * @param icon 默认图标 URL；空表示取站点 Logo
 * @param sound 默认提示音；空表示使用 Bark 默认
 * @param volume critical 告警音量；空表示使用系统默认
 * @param badge App 角标数字；仅在 {@code badgeEnabled} 为 true 时生效
 * @param badgeEnabled 是否显示 App 角标
 * @param interactiveLevel 互动类事件级别
 * @param normalLevel 普通类事件级别
 * @param securityCall 安全事件是否响铃 30 秒
 * @param archiveEnabled 是否归档
 * @param archiveTtl 归档保留时长（{@code 1d} / {@code 7d} / {@code 30d} / {@code 0} 表示永久）
 * @param action 点击行为（{@code url} 或 {@code none}）
 * @param autoCopy 是否自动复制
 * @param copy 复制内容；空表示复制通知正文
 * @param contentMaxLength 内容长度上限，取值 50–500
 * @param mergeWindow 合并窗口（{@code 1m} / {@code 2m} / {@code 5m}）
 * @param quietHoursEnabled 是否启用静默时段
 * @param quietHoursFrom 静默开始时间
 * @param quietHoursTo 静默结束时间
 * @param encryptEnabled 是否启用加密推送
 * @param encryptAlgorithm 加密算法（{@code AES128} / {@code AES192} / {@code AES256}）
 * @param encryptMode 加密模式（{@code CBC} / {@code ECB} / {@code GCM}）
 * @param encryptPadding 填充方式（{@code pkcs7} / {@code noPadding}）
 * @param encryptKey 加密密钥；长度由算法决定（16 / 24 / 32 位）。
 *        <b>此处承载的是已解析的实际密钥</b>——ConfigMap 中保存的是 Secret 名称，
 *        由 {@link CredentialResolver} 在配置加载时替换（PRD 8.2）
 */
public record PushSettings(
        List<DeviceSetting> devices,
        String group,
        String icon,
        String sound,
        String volume,
        String badge,
        Boolean badgeEnabled,
        String interactiveLevel,
        String normalLevel,
        Boolean securityCall,
        Boolean archiveEnabled,
        String archiveTtl,
        String action,
        Boolean autoCopy,
        String copy,
        String contentMaxLength,
        String mergeWindow,
        Boolean quietHoursEnabled,
        String quietHoursFrom,
        String quietHoursTo,
        Boolean encryptEnabled,
        String encryptAlgorithm,
        String encryptMode,
        String encryptPadding,
        String encryptKey) {

    /** 站点名称读取失败、或站点名称为空时使用的分组兜底值。 */
    public static final String GROUP_FALLBACK = "Halo";
    public static final String DEFAULT_INTERACTIVE_LEVEL = "timeSensitive";
    public static final String DEFAULT_NORMAL_LEVEL = "active";
    public static final String DEFAULT_ARCHIVE_TTL = "7d";
    public static final String DEFAULT_ACTION = "url";
    public static final String DEFAULT_CONTENT_MAX_LENGTH = "200";
    public static final String DEFAULT_MERGE_WINDOW = "2m";
    public static final String DEFAULT_QUIET_HOURS_FROM = "23:00";
    public static final String DEFAULT_QUIET_HOURS_TO = "07:00";
    public static final String DEFAULT_ENCRYPT_ALGORITHM = "AES128";
    public static final String DEFAULT_ENCRYPT_MODE = "CBC";
    public static final String DEFAULT_ENCRYPT_PADDING = "pkcs7";

    public PushSettings {
        devices = devices == null
                ? List.of()
                : devices.stream().filter(Objects::nonNull).toList();
        group = ConfigValues.textOrEmpty(group);
        icon = ConfigValues.textOrEmpty(icon);
        sound = ConfigValues.textOrEmpty(sound);
        volume = ConfigValues.textOrEmpty(volume);
        badge = ConfigValues.textOrEmpty(badge);
        badgeEnabled = ConfigValues.flagOrFalse(badgeEnabled);
        interactiveLevel = ConfigValues.text(interactiveLevel, DEFAULT_INTERACTIVE_LEVEL);
        normalLevel = ConfigValues.text(normalLevel, DEFAULT_NORMAL_LEVEL);
        securityCall = ConfigValues.flagOrTrue(securityCall);
        archiveEnabled = ConfigValues.flagOrTrue(archiveEnabled);
        archiveTtl = ConfigValues.text(archiveTtl, DEFAULT_ARCHIVE_TTL);
        action = ConfigValues.text(action, DEFAULT_ACTION);
        autoCopy = ConfigValues.flagOrFalse(autoCopy);
        copy = ConfigValues.textOrEmpty(copy);
        contentMaxLength = ConfigValues.text(contentMaxLength, DEFAULT_CONTENT_MAX_LENGTH);
        mergeWindow = ConfigValues.text(mergeWindow, DEFAULT_MERGE_WINDOW);
        quietHoursEnabled = ConfigValues.flagOrFalse(quietHoursEnabled);
        quietHoursFrom = ConfigValues.text(quietHoursFrom, DEFAULT_QUIET_HOURS_FROM);
        quietHoursTo = ConfigValues.text(quietHoursTo, DEFAULT_QUIET_HOURS_TO);
        encryptEnabled = ConfigValues.flagOrFalse(encryptEnabled);
        encryptAlgorithm = ConfigValues.text(encryptAlgorithm, DEFAULT_ENCRYPT_ALGORITHM);
        encryptMode = ConfigValues.text(encryptMode, DEFAULT_ENCRYPT_MODE);
        encryptPadding = ConfigValues.text(encryptPadding, DEFAULT_ENCRYPT_PADDING);
        encryptKey = ConfigValues.textOrEmpty(encryptKey);
    }

    /** 全默认值配置，用于配置组缺失或读取失败时的降级（PRD 10.6.1 开箱即用）。 */
    public static PushSettings defaults() {
        return new PushSettings(null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /** 内容长度上限的数值形式；无法解析时回退默认值。 */
    public int contentMaxLengthOrDefault() {
        var parsed = ConfigValues.intOrNull(contentMaxLength);
        return parsed == null ? Integer.parseInt(DEFAULT_CONTENT_MAX_LENGTH) : parsed;
    }

    /** 是否应当设置角标：开关开启、数字可解析且非负（负数视为非法，不向 Bark 发送）。 */
    public boolean badgeActive() {
        var parsed = ConfigValues.intOrNull(badge);
        return Boolean.TRUE.equals(badgeEnabled) && parsed != null && parsed >= 0;
    }

    /** 具备投递条件的设备。 */
    public List<DeviceSetting> deliverableDevices() {
        return devices.stream().filter(DeviceSetting::deliverable).toList();
    }

    /**
     * 替换设备列表。
     *
     * <p>供 {@link CredentialResolver} 在凭据解析后回填使用——记录本身不可变，
     * 逐字段重写会把 25 个组件散落到调用点，故由记录自己承担。
     */
    public PushSettings withDevices(List<DeviceSetting> newDevices) {
        return new PushSettings(newDevices, group, icon, sound, volume, badge, badgeEnabled,
                interactiveLevel, normalLevel, securityCall, archiveEnabled, archiveTtl, action,
                autoCopy, copy, contentMaxLength, mergeWindow, quietHoursEnabled, quietHoursFrom,
                quietHoursTo, encryptEnabled, encryptAlgorithm, encryptMode, encryptPadding, encryptKey);
    }

    /** 替换加密密钥（凭据解析后回填，语义同 {@link #withDevices}）。 */
    public PushSettings withEncryptKey(String newEncryptKey) {
        return new PushSettings(devices, group, icon, sound, volume, badge, badgeEnabled,
                interactiveLevel, normalLevel, securityCall, archiveEnabled, archiveTtl, action,
                autoCopy, copy, contentMaxLength, mergeWindow, quietHoursEnabled, quietHoursFrom,
                quietHoursTo, encryptEnabled, encryptAlgorithm, encryptMode, encryptPadding,
                newEncryptKey);
    }
}
