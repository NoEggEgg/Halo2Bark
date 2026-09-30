package com.wuqishi.halo.bark.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link SettingsValidator} 与配置归一化的单元测试。
 *
 * <p>配置对象经 JSON 构造而非位置参数，避免组件顺序变动导致测试静默失效，
 * 同时顺带覆盖反序列化路径。
 *
 * <p>不依赖真实 Halo 实例（PRD 13.4.8）。
 */
class SettingsValidatorTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static PushSettings push(String json) {
        return MAPPER.readValue(json, PushSettings.class);
    }

    private static PushSettings withDevice(String name, String serverUrl) {
        return push("""
                {"devices":[{"name":"%s","key":"key-of-%s","serverUrl":"%s","enabled":true}]}
                """.formatted(name, name, serverUrl));
    }

    private static PushSettings withDevices(String... nameAndUrlPairs) {
        var sb = new StringBuilder("{\"devices\":[");
        for (int i = 0; i < nameAndUrlPairs.length; i += 2) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("""
                    {"name":"%s","key":"k","serverUrl":"%s","enabled":true}"""
                    .formatted(nameAndUrlPairs[i], nameAndUrlPairs[i + 1]));
        }
        return push(sb.append("]}").toString());
    }

    private static PushSettings withContentMaxLength(String value) {
        return value == null ? push("{}") : push("{\"contentMaxLength\":\"%s\"}".formatted(value));
    }

    private static PushSettings withEncryption(String algorithm, String mode, String padding, String key) {
        return push("""
                {"encryptEnabled":true,"encryptAlgorithm":"%s","encryptMode":"%s","encryptPadding":"%s","encryptKey":"%s"}
                """.formatted(algorithm, mode, padding, key));
    }

    private static List<String> validate(PushSettings push) {
        return SettingsValidator.validate(new BarkSettings(push, null));
    }

    @Nested
    @DisplayName("开箱即用：默认配置必须合法")
    class Defaults {

        @Test
        void defaultSettingsPassValidation() {
            assertThat(SettingsValidator.validate(BarkSettings.defaults())).isEmpty();
        }

        @Test
        void defaultsMatchPrdValues() {
            var push = PushSettings.defaults();
            assertThat(push.group()).isEmpty();
            assertThat(push.interactiveLevel()).isEqualTo("timeSensitive");
            assertThat(push.normalLevel()).isEqualTo("active");
            assertThat(push.archiveEnabled()).isTrue();
            assertThat(push.archiveTtl()).isEqualTo("7d");
            assertThat(push.action()).isEqualTo("url");
            assertThat(push.autoCopy()).isFalse();
            assertThat(push.contentMaxLengthOrDefault()).isEqualTo(200);
            assertThat(push.mergeWindow()).isEqualTo("2m");
            assertThat(push.quietHoursEnabled()).isFalse();
            assertThat(push.encryptEnabled()).isFalse();
            assertThat(push.badgeEnabled()).isFalse();
            assertThat(push.encryptAlgorithm()).isEqualTo("AES128");
            assertThat(push.encryptMode()).isEqualTo("CBC");
            assertThat(push.encryptPadding()).isEqualTo("pkcs7");
            assertThat(push.devices()).isEmpty();
        }

        @Test
        void deviceDefaultsToOfficialServerUrl() {
            assertThat(push("{}").devices()).isEmpty();
            assertThat(push("""
                    {"devices":[{"name":"手机","key":"k"}]}
                    """).devices().get(0).serverUrl()).isEqualTo("https://api.day.app");
        }

        @Test
        void defaultEventTogglesMatchPrd() {
            var events = EventToggles.defaults();
            assertThat(events.newCommentEnabled()).isTrue();
            assertThat(events.newReplyEnabled()).isTrue();
            assertThat(events.commentPendingEnabled()).isTrue();
            assertThat(events.newDeviceLoginEnabled()).isTrue();
            assertThat(events.postPublishedEnabled()).isFalse();
            assertThat(events.userRegisteredEnabled()).isFalse();
            assertThat(events.postRemovedEnabled()).isFalse();
        }
    }

    @Nested
    @DisplayName("设备服务地址校验（设备级）")
    class DeviceServerUrl {

        @Test
        void acceptsHttpAndHttpsWithoutTrailingSlash() {
            assertThat(validate(withDevice("A", "https://api.day.app"))).isEmpty();
            assertThat(validate(withDevice("A", "http://192.168.1.10:8080"))).isEmpty();
        }

        @Test
        void acceptsDifferentServersPerDevice() {
            assertThat(validate(withDevices(
                    "官方", "https://api.day.app",
                    "自建", "https://bark.example.com"))).isEmpty();
        }

        @Test
        @DisplayName("服务地址为空视为配置不完整，不算违规（PRD 5.3）")
        void blankServerUrlIsNotAnIssue() {
            assertThat(validate(push("""
                    {"devices":[{"name":"A","key":"k","serverUrl":"","enabled":true}]}
                    """))).isEmpty();
        }

        @Test
        void rejectsMissingScheme() {
            assertThat(validate(withDevice("手机", "api.day.app")))
                    .singleElement().asString().contains("手机").contains("http://");
        }

        @Test
        void rejectsNonHttpScheme() {
            assertThat(validate(withDevice("手机", "ftp://api.day.app")))
                    .singleElement().asString().contains("手机").contains("http://");
        }

        @Test
        void rejectsMissingHost() {
            // "https:foo" 是合法 URI 但没有主机名，应被判定为缺少主机
            assertThat(validate(withDevice("手机", "https:foo")))
                    .singleElement().asString().contains("主机名");
        }

        @Test
        void rejectsMalformedAuthority() {
            assertThat(validate(withDevice("手机", "https://")))
                    .singleElement().asString().contains("不是合法地址");
        }

        @Test
        void rejectsTrailingSlash() {
            assertThat(validate(withDevice("手机", "https://api.day.app/")))
                    .singleElement().asString().contains("斜杠");
        }

        @Test
        @DisplayName("多台设备各有问题时，提示分别指明是哪台设备")
        void reportsPerDeviceIssues() {
            var issues = validate(withDevices(
                    "官方", "https://api.day.app",
                    "自建", "bark.example.com",
                    "备用", "https://bark2.example.com/"));
            assertThat(issues).hasSize(2);
            assertThat(issues).anySatisfy(issue -> assertThat(issue).contains("自建"));
            assertThat(issues).anySatisfy(issue -> assertThat(issue).contains("备用"));
        }

        @Test
        void unnamedDeviceIsStillIdentifiable() {
            assertThat(validate(withDevice("", "nope")))
                    .singleElement().asString().contains("未命名设备");
        }
    }

    @Nested
    @DisplayName("内容长度上限校验（50–500）")
    class ContentMaxLength {

        @Test
        void acceptsLowerAndUpperBounds() {
            assertThat(validate(withContentMaxLength("50"))).isEmpty();
            assertThat(validate(withContentMaxLength("500"))).isEmpty();
        }

        @Test
        void acceptsValueSubmittedAsString() {
            assertThat(validate(withContentMaxLength("150"))).isEmpty();
        }

        @Test
        void rejectsBelowLowerBound() {
            assertThat(validate(withContentMaxLength("49")))
                    .singleElement().asString().contains("内容长度上限").contains("50–500");
        }

        @Test
        void rejectsAboveUpperBound() {
            assertThat(validate(withContentMaxLength("501")))
                    .singleElement().asString().contains("内容长度上限").contains("50–500");
        }

        @Test
        void rejectsNonNumeric() {
            assertThat(validate(withContentMaxLength("abc")))
                    .singleElement().asString().contains("整数");
        }
    }

    @Nested
    @DisplayName("加密配置校验（对照 Bark App 的 Algorithm 定义）")
    class Encryption {

        @Test
        void acceptsValidCombination() {
            assertThat(validate(withEncryption("AES128", "CBC", "pkcs7", "0123456789abcdef"))).isEmpty();
            assertThat(validate(withEncryption("AES192", "ECB", "noPadding", "a".repeat(24)))).isEmpty();
            assertThat(validate(withEncryption("AES256", "GCM", "pkcs7", "b".repeat(32)))).isEmpty();
        }

        @Test
        void requiresKeyWhenEncryptionEnabled() {
            assertThat(validate(withEncryption("AES128", "CBC", "pkcs7", "")))
                    .singleElement().asString()
                    .contains("加密密钥").contains("重新选择密钥");
        }

        @Test
        void treatsBlankKeyAsMissing() {
            assertThat(validate(withEncryption("AES128", "CBC", "pkcs7", "   "))).hasSize(1);
        }

        @Test
        @DisplayName("密钥长度由算法决定：AES128=16、AES192=24、AES256=32（按 UTF-8 字节数）")
        void rejectsKeyLengthMismatch() {
            assertThat(validate(withEncryption("AES128", "CBC", "pkcs7", "a".repeat(24))))
                    .singleElement().asString().contains("必须是 16 个字节").contains("AES128");
            assertThat(validate(withEncryption("AES192", "CBC", "pkcs7", "a".repeat(16))))
                    .singleElement().asString().contains("必须是 24 个字节");
            assertThat(validate(withEncryption("AES256", "CBC", "pkcs7", "a".repeat(16))))
                    .singleElement().asString().contains("必须是 32 个字节");
        }

        @Test
        @DisplayName("含多字节字符的密钥按字节数判定：字符数够但字节数不够同样拒绝")
        void rejectsMultibyteKeyByByteLength() {
            // 6 个字符 = 18 字节，AES128 需要 16 字节
            assertThat(validate(withEncryption("AES128", "CBC", "pkcs7", "中文中文中文")))
                    .singleElement().asString().contains("16 个字节").contains("当前 18");
        }

        @Test
        void rejectsUnsupportedAlgorithm() {
            assertThat(validate(withEncryption("DES", "CBC", "pkcs7", "a".repeat(16))))
                    .singleElement().asString().contains("加密算法").contains("AES128");
        }

        @Test
        void rejectsUnsupportedMode() {
            assertThat(validate(withEncryption("AES128", "OFB", "pkcs7", "a".repeat(16))))
                    .singleElement().asString().contains("加密模式").contains("GCM");
        }

        @Test
        void rejectsUnsupportedPadding() {
            assertThat(validate(withEncryption("AES128", "CBC", "zeroPadding", "a".repeat(16))))
                    .singleElement().asString().contains("填充方式").contains("noPadding");
        }

        @Test
        void ignoresEncryptionSettingsWhenDisabled() {
            assertThat(validate(push("""
                    {"encryptEnabled":false,"encryptAlgorithm":"DES","encryptKey":"short"}
                    """))).isEmpty();
        }

        @Test
        void expectedKeyLengthMatchesBarkDefinition() {
            assertThat(SettingsValidator.expectedKeyLength("AES128")).isEqualTo(16);
            assertThat(SettingsValidator.expectedKeyLength("AES192")).isEqualTo(24);
            assertThat(SettingsValidator.expectedKeyLength("AES256")).isEqualTo(32);
            assertThat(SettingsValidator.expectedKeyLength("DES")).isEqualTo(-1);
        }
    }

    @Test
    @DisplayName("多条违规一次性返回，便于用户一次改完")
    void reportsAllViolationsAtOnce() {
        var issues = validate(push("""
                {"devices":[{"name":"手机","key":"k","serverUrl":"api.day.app/","enabled":true}],
                 "contentMaxLength":"999",
                 "encryptEnabled":true,"encryptKey":""}
                """));
        assertThat(issues).hasSize(3);
        assertThat(issues).anySatisfy(issue -> assertThat(issue).contains("手机"));
        assertThat(issues).anySatisfy(issue -> assertThat(issue).contains("内容长度上限"));
        assertThat(issues).anySatisfy(issue -> assertThat(issue).contains("加密密钥"));
    }

    @Nested
    @DisplayName("设备归一化")
    class Devices {

        @Test
        void enabledDefaultsToTrueWhenAbsent() {
            assertThat(new DeviceSetting("手机", "key-1", "https://api.day.app", null).enabled()).isTrue();
        }

        @Test
        void deliverableRequiresEnabledKeyAndServerUrl() {
            assertThat(new DeviceSetting("手机", "k", "https://api.day.app", true).deliverable()).isTrue();
            assertThat(new DeviceSetting("手机", "k", "https://api.day.app", false).deliverable()).isFalse();
            assertThat(new DeviceSetting("手机", "", "https://api.day.app", true).deliverable()).isFalse();
            assertThat(new DeviceSetting("手机", "   ", "https://api.day.app", true).deliverable()).isFalse();
            assertThat(new DeviceSetting("手机", "k", "", true).deliverable()).isFalse();
        }

        @Test
        void deliverableDevicesFiltersIncompleteOnes() {
            var push = push("""
                    {"devices":[
                      {"name":"A","key":"k1","serverUrl":"https://api.day.app","enabled":true},
                      {"name":"B","key":"","serverUrl":"https://api.day.app","enabled":true},
                      {"name":"C","key":"k3","serverUrl":"","enabled":true},
                      {"name":"D","key":"k4","serverUrl":"https://api.day.app","enabled":false}
                    ]}
                    """);
            assertThat(push.deliverableDevices()).extracting(DeviceSetting::name).containsExactly("A");
        }
    }

    @Test
    @DisplayName("数值字段以字符串承载：数字与数字字符串均可解析")
    void parsesNumericFieldsLeniently() {
        assertThat(push("{\"contentMaxLength\":150}").contentMaxLengthOrDefault()).isEqualTo(150);
        assertThat(withContentMaxLength("abc").contentMaxLengthOrDefault()).isEqualTo(200);
        assertThat(withContentMaxLength("").contentMaxLengthOrDefault()).isEqualTo(200);
        assertThat(withContentMaxLength(null).contentMaxLengthOrDefault()).isEqualTo(200);
    }

    @Test
    @DisplayName("通知分组留空表示使用站点名称，兜底值独立存在")
    void blankGroupMeansSiteName() {
        assertThat(push("{}").group()).isEmpty();
        assertThat(PushSettings.GROUP_FALLBACK).isEqualTo("Halo");
    }

    @Test
    @DisplayName("角标仅在勾选且数字可解析时生效")
    void badgeRequiresToggleAndNumericValue() {
        assertThat(push("{\"badge\":\"3\",\"badgeEnabled\":true}").badgeActive()).isTrue();
        assertThat(push("{\"badge\":\"3\",\"badgeEnabled\":false}").badgeActive()).isFalse();
        assertThat(push("{\"badge\":\"\",\"badgeEnabled\":true}").badgeActive()).isFalse();
    }

    @Nested
    @DisplayName("静默时段与告警音量校验（PRD 5.4 / 5.2）")
    class QuietHoursAndVolume {

        @Test
        void acceptsValidQuietHours() {
            assertThat(validate(push("""
                    {"quietHoursEnabled":true,"quietHoursFrom":"23:00","quietHoursTo":"07:00"}
                    """))).isEmpty();
        }

        @Test
        void rejectsInvalidQuietHours() {
            assertThat(validate(push("""
                    {"quietHoursEnabled":true,"quietHoursFrom":"25:00","quietHoursTo":"07:00"}
                    """))).singleElement().asString().contains("静默开始");
            assertThat(validate(push("""
                    {"quietHoursEnabled":true,"quietHoursFrom":"23:00","quietHoursTo":"abc"}
                    """))).singleElement().asString().contains("静默结束");
        }

        @Test
        void acceptsBlankAndValidVolume() {
            assertThat(validate(push("""
                    {"securityCall":true,"volume":""}
                    """))).isEmpty();
            assertThat(validate(push("""
                    {"securityCall":true,"volume":"10"}
                    """))).isEmpty();
        }

        @Test
        void rejectsOutOfRangeVolume() {
            assertThat(validate(push("""
                    {"securityCall":true,"volume":"42"}
                    """))).singleElement().asString().contains("告警音量");
            assertThat(validate(push("""
                    {"securityCall":true,"volume":"-1"}
                    """))).singleElement().asString().contains("告警音量");
        }
    }
}
