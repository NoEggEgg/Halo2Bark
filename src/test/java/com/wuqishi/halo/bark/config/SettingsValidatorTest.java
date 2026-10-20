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
 * <p>校验结论分两级：{@code blocking} 停止全部推送（只有加密不可用），
 * {@code warnings} 只提示且运行时有确定降级路径。
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

    /** 阻断级结论：非空即停止全部推送。 */
    private static List<String> blocking(PushSettings push) {
        return SettingsValidator.inspect(new BarkSettings(push, null)).blocking();
    }

    /** 提示级结论：不影响投递。 */
    private static List<String> warnings(PushSettings push) {
        return SettingsValidator.inspect(new BarkSettings(push, null)).warnings();
    }

    @Nested
    @DisplayName("开箱即用：默认配置必须合法")
    class Defaults {

        @Test
        void defaultSettingsPassValidation() {
            var report = SettingsValidator.inspect(BarkSettings.defaults());
            assertThat(report.blocking()).isEmpty();
            assertThat(report.warnings()).isEmpty();
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
            assertThat(events.replyPendingEnabled()).isTrue();
            assertThat(events.newDeviceLoginEnabled()).isTrue();
            assertThat(events.postPublishedEnabled()).isFalse();
            assertThat(events.userRegisteredEnabled()).isFalse();
            assertThat(events.postRemovedEnabled()).isFalse();
        }
    }

    @Nested
    @DisplayName("设备服务地址校验（设备级，只提示不阻断）")
    class DeviceServerUrl {

        @Test
        void acceptsHttpAndHttps() {
            assertThat(blocking(withDevice("A", "https://api.day.app"))).isEmpty();
            assertThat(blocking(withDevice("A", "http://192.168.1.10:8080"))).isEmpty();
        }

        @Test
        @DisplayName("末尾斜杠由运行时裁掉，不再判为问题")
        void acceptsTrailingSlash() {
            assertThat(blocking(withDevice("A", "https://api.day.app/"))).isEmpty();
            assertThat(warnings(withDevice("A", "https://api.day.app/"))).isEmpty();
            assertThat(withDevice("A", "https://api.day.app/").devices().get(0).deliverable()).isTrue();
        }

        @Test
        void acceptsDifferentServersPerDevice() {
            assertThat(blocking(withDevices(
                    "官方", "https://api.day.app",
                    "自建", "https://bark.example.com"))).isEmpty();
        }

        @Test
        @DisplayName("服务地址为空视为配置不完整，不算违规（PRD 5.3）")
        void blankServerUrlIsNotAnIssue() {
            var push = push("""
                    {"devices":[{"name":"A","key":"k","serverUrl":"","enabled":true}]}
                    """);
            assertThat(blocking(push)).isEmpty();
            assertThat(warnings(push)).isEmpty();
        }

        @Test
        void reportsMissingSchemeAsWarning() {
            assertThat(blocking(withDevice("手机", "api.day.app"))).isEmpty();
            assertThat(warnings(withDevice("手机", "api.day.app")))
                    .singleElement().asString().contains("手机").contains("http://");
        }

        @Test
        void reportsNonHttpSchemeAsWarning() {
            assertThat(blocking(withDevice("手机", "ftp://api.day.app"))).isEmpty();
            assertThat(warnings(withDevice("手机", "ftp://api.day.app")))
                    .singleElement().asString().contains("手机").contains("http://");
        }

        @Test
        void reportsMissingHostAsWarning() {
            // "https:foo" 是合法 URI 但没有主机名，应被判定为缺少主机
            assertThat(warnings(withDevice("手机", "https:foo")))
                    .singleElement().asString().contains("主机名");
        }

        @Test
        void reportsMalformedAuthorityAsWarning() {
            assertThat(warnings(withDevice("手机", "https://")))
                    .singleElement().asString().contains("不是合法地址");
        }

        @Test
        @DisplayName("多台设备各有问题时，提示分别指明是哪台设备")
        void reportsPerDeviceWarnings() {
            var warnings = warnings(withDevices(
                    "官方", "https://api.day.app",
                    "自建", "bark.example.com",
                    "备用", "https://bark2.example.com/?token=1"));
            assertThat(warnings).hasSize(2);
            assertThat(warnings).anySatisfy(issue -> assertThat(issue).contains("自建"));
            assertThat(warnings).anySatisfy(issue -> assertThat(issue).contains("备用"));
        }

        @Test
        void unnamedDeviceIsStillIdentifiable() {
            assertThat(warnings(withDevice("", "nope")))
                    .singleElement().asString().contains("未命名设备");
        }

        @Test
        @DisplayName("地址非法的设备不参与投递，但不影响其他设备（爆炸半径收敛）")
        void malformedDeviceIsExcludedWithoutBlockingOthers() {
            var push = withDevices(
                    "好的", "https://api.day.app",
                    "坏的", "bark.example.com");

            assertThat(blocking(push)).as("设备级问题不得阻断全部推送").isEmpty();
            assertThat(warnings(push)).hasSize(1);
            assertThat(push.deliverableDevices()).extracting(DeviceSetting::name)
                    .containsExactly("好的");
        }
    }

    @Nested
    @DisplayName("内容长度上限校验（50–500，只提示不阻断）")
    class ContentMaxLength {

        @Test
        void acceptsLowerAndUpperBounds() {
            assertThat(warnings(withContentMaxLength("50"))).isEmpty();
            assertThat(warnings(withContentMaxLength("500"))).isEmpty();
        }

        @Test
        void acceptsValueSubmittedAsString() {
            assertThat(warnings(withContentMaxLength("150"))).isEmpty();
        }

        @Test
        void reportsBelowLowerBound() {
            assertThat(blocking(withContentMaxLength("49"))).isEmpty();
            assertThat(warnings(withContentMaxLength("49")))
                    .singleElement().asString().contains("内容长度上限").contains("50–500");
        }

        @Test
        void reportsAboveUpperBound() {
            assertThat(warnings(withContentMaxLength("501")))
                    .singleElement().asString().contains("内容长度上限").contains("50–500");
        }

        @Test
        void reportsNonNumeric() {
            assertThat(warnings(withContentMaxLength("abc")))
                    .singleElement().asString().contains("整数").contains("默认值");
        }
    }

    @Nested
    @DisplayName("加密配置校验（对照 Bark App 的 Algorithm 定义，阻断级）")
    class Encryption {

        @Test
        void acceptsValidCombination() {
            assertThat(blocking(withEncryption("AES128", "CBC", "pkcs7", "0123456789abcdef"))).isEmpty();
            assertThat(blocking(withEncryption("AES192", "ECB", "noPadding", "a".repeat(24)))).isEmpty();
            assertThat(blocking(withEncryption("AES256", "GCM", "pkcs7", "b".repeat(32)))).isEmpty();
        }

        @Test
        void requiresKeyWhenEncryptionEnabled() {
            assertThat(blocking(withEncryption("AES128", "CBC", "pkcs7", "")))
                    .singleElement().asString()
                    .contains("加密密钥").contains("重新选择密钥");
        }

        @Test
        void treatsBlankKeyAsMissing() {
            assertThat(blocking(withEncryption("AES128", "CBC", "pkcs7", "   "))).hasSize(1);
        }

        @Test
        @DisplayName("密钥长度由算法决定：AES128=16、AES192=24、AES256=32（按 UTF-8 字节数）")
        void rejectsKeyLengthMismatch() {
            assertThat(blocking(withEncryption("AES128", "CBC", "pkcs7", "a".repeat(24))))
                    .singleElement().asString().contains("必须是 16 个字节").contains("AES128");
            assertThat(blocking(withEncryption("AES192", "CBC", "pkcs7", "a".repeat(16))))
                    .singleElement().asString().contains("必须是 24 个字节");
            assertThat(blocking(withEncryption("AES256", "CBC", "pkcs7", "a".repeat(16))))
                    .singleElement().asString().contains("必须是 32 个字节");
        }

        @Test
        @DisplayName("含多字节字符的密钥按字节数判定：字符数够但字节数不够同样拒绝")
        void rejectsMultibyteKeyByByteLength() {
            // 6 个字符 = 18 字节，AES128 需要 16 字节
            assertThat(blocking(withEncryption("AES128", "CBC", "pkcs7", "中文中文中文")))
                    .singleElement().asString().contains("16 个字节").contains("当前 18");
        }

        @Test
        void rejectsUnsupportedAlgorithm() {
            assertThat(blocking(withEncryption("DES", "CBC", "pkcs7", "a".repeat(16))))
                    .singleElement().asString().contains("加密算法").contains("AES128");
        }

        @Test
        void rejectsUnsupportedMode() {
            assertThat(blocking(withEncryption("AES128", "OFB", "pkcs7", "a".repeat(16))))
                    .singleElement().asString().contains("加密模式").contains("GCM");
        }

        @Test
        void rejectsUnsupportedPadding() {
            assertThat(blocking(withEncryption("AES128", "CBC", "zeroPadding", "a".repeat(16))))
                    .singleElement().asString().contains("填充方式").contains("noPadding");
        }

        @Test
        void ignoresEncryptionSettingsWhenDisabled() {
            assertThat(blocking(push("""
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
    @DisplayName("阻断级与提示级分别汇总，便于用户一次改完")
    void reportsBlockingAndWarningsSeparately() {
        var push = push("""
                {"devices":[{"name":"手机","key":"k","serverUrl":"api.day.app","enabled":true}],
                 "contentMaxLength":"999",
                 "encryptEnabled":true,"encryptKey":""}
                """);
        var report = SettingsValidator.inspect(new BarkSettings(push, null));

        assertThat(report.blocking()).singleElement().asString().contains("加密密钥");
        assertThat(report.warnings()).hasSize(2);
        assertThat(report.warnings()).anySatisfy(issue -> assertThat(issue).contains("手机"));
        assertThat(report.warnings()).anySatisfy(issue -> assertThat(issue).contains("内容长度上限"));
    }

    @Test
    @DisplayName("只有加密问题会阻断全部推送")
    void onlyEncryptionBlocksEverything() {
        assertThat(blocking(push("""
                {"devices":[{"name":"手机","key":"k","serverUrl":"nope","enabled":true}],
                 "contentMaxLength":"999","quietHoursFrom":"25:00","volume":"99"}
                """))).as("地址/长度/时间/音量问题都不得阻断推送").isEmpty();
    }

    @Nested
    @DisplayName("设备归一化")
    class Devices {

        @Test
        void enabledDefaultsToTrueWhenAbsent() {
            assertThat(new DeviceSetting("手机", "key-1", "https://api.day.app", null).enabled()).isTrue();
        }

        @Test
        void deliverableRequiresEnabledKeyAndUsableServerUrl() {
            assertThat(new DeviceSetting("手机", "k", "https://api.day.app", true).deliverable()).isTrue();
            assertThat(new DeviceSetting("手机", "k", "https://api.day.app/", true).deliverable()).isTrue();
            assertThat(new DeviceSetting("手机", "k", "https://api.day.app", false).deliverable()).isFalse();
            assertThat(new DeviceSetting("手机", "", "https://api.day.app", true).deliverable()).isFalse();
            assertThat(new DeviceSetting("手机", "   ", "https://api.day.app", true).deliverable()).isFalse();
            assertThat(new DeviceSetting("手机", "k", "", true).deliverable()).isFalse();
            assertThat(new DeviceSetting("手机", "k", "api.day.app", true).deliverable()).isFalse();
            assertThat(new DeviceSetting("手机", "k", "https://api.day.app?x=1", true).deliverable())
                    .isFalse();
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
    @DisplayName("静默时段与告警音量校验（PRD 5.4 / 5.2，只提示不阻断）")
    class QuietHoursAndVolume {

        @Test
        void acceptsValidQuietHours() {
            assertThat(warnings(push("""
                    {"quietHoursEnabled":true,"quietHoursFrom":"23:00","quietHoursTo":"07:00"}
                    """))).isEmpty();
        }

        @Test
        void reportsInvalidQuietHours() {
            assertThat(warnings(push("""
                    {"quietHoursEnabled":true,"quietHoursFrom":"25:00","quietHoursTo":"07:00"}
                    """))).singleElement().asString().contains("静默开始");
            assertThat(warnings(push("""
                    {"quietHoursEnabled":true,"quietHoursFrom":"23:00","quietHoursTo":"abc"}
                    """))).singleElement().asString().contains("静默结束");
        }

        @Test
        void acceptsBlankAndValidVolume() {
            assertThat(warnings(push("""
                    {"securityCall":true,"volume":""}
                    """))).isEmpty();
            assertThat(warnings(push("""
                    {"securityCall":true,"volume":"10"}
                    """))).isEmpty();
        }

        @Test
        void reportsOutOfRangeVolume() {
            assertThat(warnings(push("""
                    {"securityCall":true,"volume":"42"}
                    """))).singleElement().asString().contains("告警音量");
            assertThat(warnings(push("""
                    {"securityCall":true,"volume":"-1"}
                    """))).singleElement().asString().contains("告警音量");
        }
    }
}
