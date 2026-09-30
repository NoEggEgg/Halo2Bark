package com.wuqishi.halo.bark.support;

import com.wuqishi.halo.bark.config.DeviceSetting;
import com.wuqishi.halo.bark.config.PushSettings;
import java.util.List;

/**
 * 测试用配置构造器：以 PRD 声明的默认值为基线，只覆盖被测用例关心的字段。
 *
 * <p>{@link PushSettings} 有 26 个组件，逐条在测试中手写会把断言淹没在参数列表里，
 * 因此统一由本构造器承载默认值。
 */
public final class PushSettingsBuilder {

    private List<DeviceSetting> devices = List.of();
    private String group = "";
    private String icon = "";
    private String sound = "";
    private String volume = "";
    private String badge = "";
    private Boolean badgeEnabled = false;
    private String interactiveLevel = PushSettings.DEFAULT_INTERACTIVE_LEVEL;
    private String normalLevel = PushSettings.DEFAULT_NORMAL_LEVEL;
    private Boolean securityCall = true;
    private Boolean archiveEnabled = true;
    private String archiveTtl = PushSettings.DEFAULT_ARCHIVE_TTL;
    private String action = PushSettings.DEFAULT_ACTION;
    private Boolean autoCopy = false;
    private String copy = "";
    private String contentMaxLength = PushSettings.DEFAULT_CONTENT_MAX_LENGTH;
    private String mergeWindow = PushSettings.DEFAULT_MERGE_WINDOW;
    private Boolean quietHoursEnabled = false;
    private String quietHoursFrom = PushSettings.DEFAULT_QUIET_HOURS_FROM;
    private String quietHoursTo = PushSettings.DEFAULT_QUIET_HOURS_TO;
    private Boolean encryptEnabled = false;
    private String encryptAlgorithm = PushSettings.DEFAULT_ENCRYPT_ALGORITHM;
    private String encryptMode = PushSettings.DEFAULT_ENCRYPT_MODE;
    private String encryptPadding = PushSettings.DEFAULT_ENCRYPT_PADDING;
    private String encryptKey = "";

    public static PushSettingsBuilder push() {
        return new PushSettingsBuilder();
    }

    public PushSettingsBuilder devices(DeviceSetting... values) {
        return devices(List.of(values));
    }

    public PushSettingsBuilder devices(List<DeviceSetting> values) {
        this.devices = values;
        return this;
    }

    public PushSettingsBuilder group(String value) {
        this.group = value;
        return this;
    }

    public PushSettingsBuilder icon(String value) {
        this.icon = value;
        return this;
    }

    public PushSettingsBuilder sound(String value) {
        this.sound = value;
        return this;
    }

    public PushSettingsBuilder volume(String value) {
        this.volume = value;
        return this;
    }

    public PushSettingsBuilder badge(String value) {
        this.badge = value;
        return this;
    }

    public PushSettingsBuilder badgeEnabled(boolean value) {
        this.badgeEnabled = value;
        return this;
    }

    public PushSettingsBuilder interactiveLevel(String value) {
        this.interactiveLevel = value;
        return this;
    }

    public PushSettingsBuilder normalLevel(String value) {
        this.normalLevel = value;
        return this;
    }

    public PushSettingsBuilder securityCall(boolean value) {
        this.securityCall = value;
        return this;
    }

    public PushSettingsBuilder archiveEnabled(boolean value) {
        this.archiveEnabled = value;
        return this;
    }

    public PushSettingsBuilder archiveTtl(String value) {
        this.archiveTtl = value;
        return this;
    }

    public PushSettingsBuilder action(String value) {
        this.action = value;
        return this;
    }

    public PushSettingsBuilder autoCopy(boolean value) {
        this.autoCopy = value;
        return this;
    }

    public PushSettingsBuilder copy(String value) {
        this.copy = value;
        return this;
    }

    public PushSettingsBuilder encryptEnabled(boolean value) {
        this.encryptEnabled = value;
        return this;
    }

    public PushSettingsBuilder encryptAlgorithm(String value) {
        this.encryptAlgorithm = value;
        return this;
    }

    public PushSettingsBuilder encryptMode(String value) {
        this.encryptMode = value;
        return this;
    }

    public PushSettingsBuilder encryptPadding(String value) {
        this.encryptPadding = value;
        return this;
    }

    public PushSettingsBuilder encryptKey(String value) {
        this.encryptKey = value;
        return this;
    }

    public PushSettings build() {
        return new PushSettings(devices, group, icon, sound, volume, badge, badgeEnabled,
                interactiveLevel, normalLevel, securityCall, archiveEnabled, archiveTtl, action,
                autoCopy, copy, contentMaxLength, mergeWindow, quietHoursEnabled, quietHoursFrom,
                quietHoursTo, encryptEnabled, encryptAlgorithm, encryptMode, encryptPadding,
                encryptKey);
    }
}
