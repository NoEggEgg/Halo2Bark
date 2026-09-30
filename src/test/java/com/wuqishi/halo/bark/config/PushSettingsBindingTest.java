package com.wuqishi.halo.bark.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * 配置反序列化的单元测试，验证数值字段以字符串承载、以及设备级字段的归一化。
 *
 * <p>依据：Halo 把 FormKit 的 {@code $formkit: number} 字段写入插件 ConfigMap 时，
 * 类型可能是 JSON 数字（{@code 24}），也可能是 JSON 字符串（{@code "6"}）。
 * 因此模型必须同时接受两种形态。
 *
 * <p>不依赖真实 Halo 实例（PRD 13.4.8）。
 */
class PushSettingsBindingTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Test
    @DisplayName("数值字段存为 JSON 字符串时可解析")
    void bindsNumericString() {
        var push = MAPPER.readValue("""
                {"contentMaxLength":"150"}
                """, PushSettings.class);
        assertThat(push.contentMaxLength()).isEqualTo("150");
        assertThat(push.contentMaxLengthOrDefault()).isEqualTo(150);
    }

    @Test
    @DisplayName("数值字段存为 JSON 数字时可解析")
    void bindsNumericLiteral() {
        var push = MAPPER.readValue("""
                {"contentMaxLength":150}
                """, PushSettings.class);
        assertThat(push.contentMaxLength()).isEqualTo("150");
        assertThat(push.contentMaxLengthOrDefault()).isEqualTo(150);
    }

    @Test
    @DisplayName("可选数值字段（volume / badge）两种形态均可解析")
    void bindsOptionalNumericFields() {
        var asString = MAPPER.readValue("""
                {"volume":"7","badge":"3"}
                """, PushSettings.class);
        assertThat(asString.volume()).isEqualTo("7");
        assertThat(asString.badge()).isEqualTo("3");

        var asNumber = MAPPER.readValue("""
                {"volume":7,"badge":3}
                """, PushSettings.class);
        assertThat(asNumber.volume()).isEqualTo("7");
        assertThat(asNumber.badge()).isEqualTo("3");
    }

    @Test
    @DisplayName("空配置组也能得到完整默认值，不产生 null")
    void bindsEmptyGroupToDefaults() {
        var push = MAPPER.readValue("{}", PushSettings.class);
        assertThat(push.group()).isEmpty();
        assertThat(push.devices()).isEmpty();
        assertThat(push.contentMaxLengthOrDefault()).isEqualTo(200);
        assertThat(push.icon()).isEmpty();
        assertThat(push.encryptKey()).isEmpty();
    }

    @Test
    @DisplayName("设备缺少 enabled 时默认启用，缺少 serverUrl 时取官方地址")
    void bindsDeviceDefaults() {
        var push = MAPPER.readValue("""
                {"devices":[
                  {"name":"手机","key":"abc"},
                  {"name":"平板","key":"def","serverUrl":"https://bark.example.com","enabled":false}
                ]}
                """, PushSettings.class);
        assertThat(push.devices()).hasSize(2);

        var phone = push.devices().get(0);
        assertThat(phone.enabled()).isTrue();
        assertThat(phone.serverUrl()).isEqualTo("https://api.day.app");

        var tablet = push.devices().get(1);
        assertThat(tablet.enabled()).isFalse();
        assertThat(tablet.serverUrl()).isEqualTo("https://bark.example.com");

        assertThat(push.deliverableDevices()).extracting(DeviceSetting::name).containsExactly("手机");
    }

    @Test
    @DisplayName("每台设备可持有不同的服务地址，且按地址去重可分组")
    void bindsPerDeviceServerUrls() {
        var push = MAPPER.readValue("""
                {"devices":[
                  {"name":"A","key":"k1","serverUrl":"https://api.day.app"},
                  {"name":"B","key":"k2","serverUrl":"https://api.day.app"},
                  {"name":"C","key":"k3","serverUrl":"https://bark.example.com"}
                ]}
                """, PushSettings.class);
        assertThat(push.deliverableDevices()).hasSize(3);
        assertThat(push.deliverableDevices().stream().map(DeviceSetting::serverUrl).distinct())
                .containsExactlyInAnyOrder("https://api.day.app", "https://bark.example.com");
    }

    @Test
    @DisplayName("角标开关缺失时默认关闭")
    void bindsBadgeToggle() {
        var withoutToggle = MAPPER.readValue("""
                {"badge":5}
                """, PushSettings.class);
        assertThat(withoutToggle.badgeEnabled()).isFalse();
        assertThat(withoutToggle.badgeActive()).isFalse();

        var withToggle = MAPPER.readValue("""
                {"badge":5,"badgeEnabled":true}
                """, PushSettings.class);
        assertThat(withToggle.badgeActive()).isTrue();
    }

    @Test
    @DisplayName("事件开关缺少字段时按 PRD 默认值补齐")
    void bindsEventTogglesWithDefaults() {
        var events = MAPPER.readValue("""
                {"postPublishedEnabled":true}
                """, EventToggles.class);
        assertThat(events.postPublishedEnabled()).isTrue();
        assertThat(events.newCommentEnabled()).isTrue();
        assertThat(events.userRegisteredEnabled()).isFalse();
    }
}
