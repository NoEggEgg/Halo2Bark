package com.wuqishi.halo.bark.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.wuqishi.halo.bark.config.PushSettings;
import com.wuqishi.halo.bark.json.Json;
import com.wuqishi.halo.bark.push.NotificationCategory;
import com.wuqishi.halo.bark.push.PushNotification;
import com.wuqishi.halo.bark.support.PushSettingsBuilder;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 请求参数构造的单元测试。
 *
 * <p>依据 PRD 5.2 的参数表与 Bark V2 API 文档：
 * {@code isArchive} / {@code call} / {@code automaticallyCopy} 为字符串 {@code "1"}，
 * {@code ttl} 为整数秒，{@code badge} 为整数；V1 回退不含 {@code device_keys} / {@code isArchive} / {@code ttl}。
 *
 * <p>不依赖真实 Halo 实例（PRD 10.4.5）。
 */
class BarkPayloadFactoryTest {

    private static final String ICON = "https://example.com/logo.png";

    private final BarkPayloadFactory factory = new BarkPayloadFactory(new PayloadEncryptor());

    private static PushNotification notification(NotificationCategory category) {
        return new PushNotification("💬 新评论", "文章标题", "评论正文", "https://example.com/p/1",
                category, true);
    }

    private static Map<String, Object> paramsOf(BarkPayloadFactory factory, PushNotification notification,
            PushSettings settings, List<String> keys, boolean v2) {
        return factory.params(notification, settings, keys, "分组", ICON, v2);
    }

    @Nested
    @DisplayName("正文以 Markdown 下发")
    class MarkdownBody {

        @Test
        @DisplayName("同时下发 body 与 markdown（Markdown 总是开启）")
        void sendsMarkdown() {
            var params = paramsOf(factory, notification(NotificationCategory.NORMAL),
                    PushSettingsBuilder.push().build(), List.of("k"), true);

            assertThat(params.get("markdown")).isEqualTo("评论正文");
            assertThat(params.get("body")).isEqualTo("评论正文");
        }
    }

    @Nested
    @DisplayName("逐条图标与配图")
    class PerNotificationIconAndImage {

        @Test
        @DisplayName("逐条图标优先于全局图标，并下发 image 参数")
        void overridesGlobalIcon() {
            var notification = new PushNotification("t", "s", "b", "", NotificationCategory.NORMAL,
                    true, "https://example.com/avatar.png", "https://example.com/photo.png");

            var params = paramsOf(factory, notification, PushSettingsBuilder.push().build(),
                    List.of("k"), true);

            assertThat(params.get("icon")).isEqualTo("https://example.com/avatar.png");
            assertThat(params.get("image")).isEqualTo("https://example.com/photo.png");
        }

        @Test
        @DisplayName("逐条图标为空时回退全局图标；无配图时不下发 image")
        void fallsBackToGlobalIcon() {
            var notification = new PushNotification("t", "s", "b", "", NotificationCategory.NORMAL, true);

            var params = paramsOf(factory, notification, PushSettingsBuilder.push().build(),
                    List.of("k"), true);

            assertThat(params.get("icon")).isEqualTo(ICON);
            assertThat(params).doesNotContainKey("image");
        }
    }

    @Nested
    @DisplayName("目标设备")
    class Devices {

        @Test
        @DisplayName("单台设备使用 device_key")
        void singleDeviceUsesDeviceKey() {
            var params = paramsOf(factory, notification(NotificationCategory.INTERACTIVE),
                    PushSettingsBuilder.push().build(), List.of("KEY1"), true);

            assertThat(params).containsEntry("device_key", "KEY1");
            assertThat(params).doesNotContainKey("device_keys");
        }

        @Test
        @DisplayName("同地址多台设备使用 device_keys")
        void multipleDevicesUseDeviceKeys() {
            var params = paramsOf(factory, notification(NotificationCategory.INTERACTIVE),
                    PushSettingsBuilder.push().build(), List.of("KEY1", "KEY2"), true);

            assertThat(params).containsEntry("device_keys", List.of("KEY1", "KEY2"));
            assertThat(params).doesNotContainKey("device_key");
        }

        @Test
        @DisplayName("V1 回退时不使用批量参数")
        void v1NeverBatches() {
            var params = paramsOf(factory, notification(NotificationCategory.INTERACTIVE),
                    PushSettingsBuilder.push().build(), List.of("KEY1", "KEY2"), false);

            assertThat(params).containsEntry("device_key", "KEY1");
            assertThat(params).doesNotContainKey("device_keys");
        }
    }

    @Nested
    @DisplayName("级别与告警参数")
    class LevelAndAlert {

        @Test
        @DisplayName("安全事件固定 critical 且带响铃与音量")
        void securityForcesCritical() {
            var params = paramsOf(factory, notification(NotificationCategory.SECURITY),
                    PushSettingsBuilder.push().securityCall(true).volume("7").build(),
                    List.of("KEY1"), true);

            assertThat(params).containsEntry("level", "critical");
            assertThat(params).containsEntry("call", "1");
            assertThat(params).containsEntry("volume", "7");
        }

        @Test
        @DisplayName("互动类与配置冲突时仍不得覆盖安全事件的 critical")
        void securityIgnoresConfiguredLevel() {
            var params = paramsOf(factory, notification(NotificationCategory.SECURITY),
                    PushSettingsBuilder.push().interactiveLevel("passive").normalLevel("passive").build(),
                    List.of("KEY1"), true);

            assertThat(params).containsEntry("level", "critical");
        }

        @Test
        @DisplayName("互动类取互动类级别配置")
        void interactiveUsesConfiguredLevel() {
            var params = paramsOf(factory, notification(NotificationCategory.INTERACTIVE),
                    PushSettingsBuilder.push().interactiveLevel("passive").normalLevel("active").build(),
                    List.of("KEY1"), true);

            assertThat(params).containsEntry("level", "passive");
            assertThat(params).doesNotContainKey("call");
            assertThat(params).doesNotContainKey("volume");
        }

        @Test
        @DisplayName("非安全事件不发送响铃与音量")
        void nonSecuritySkipsCallAndVolume() {
            var params = paramsOf(factory, notification(NotificationCategory.NORMAL),
                    PushSettingsBuilder.push().securityCall(true).volume("7").build(),
                    List.of("KEY1"), true);

            assertThat(params).doesNotContainKey("call");
            assertThat(params).doesNotContainKey("volume");
        }

        @Test
        @DisplayName("级别配置非法时回退 active")
        void fallsBackWhenLevelInvalid() {
            var params = paramsOf(factory, notification(NotificationCategory.NORMAL),
                    PushSettingsBuilder.push().normalLevel("loud").build(), List.of("KEY1"), true);

            assertThat(params).containsEntry("level", "active");
        }

        @Test
        @DisplayName("音量越界时不下发，避免服务端拒绝")
        void skipsOutOfRangeVolume() {
            var params = paramsOf(factory, notification(NotificationCategory.SECURITY),
                    PushSettingsBuilder.push().volume("42").build(), List.of("KEY1"), true);

            assertThat(params).containsEntry("call", "1");
            assertThat(params).doesNotContainKey("volume");
        }
    }

    @Nested
    @DisplayName("归档")
    class Archive {

        @Test
        @DisplayName("归档开启时下发 isArchive 与按秒换算的 ttl")
        void archivesWithSeconds() {
            var params = paramsOf(factory, notification(NotificationCategory.INTERACTIVE),
                    PushSettingsBuilder.push().archiveEnabled(true).archiveTtl("7d").build(),
                    List.of("KEY1"), true);

            assertThat(params).containsEntry("isArchive", "1");
            assertThat(params).containsEntry("ttl", 604800);
        }

        @Test
        @DisplayName("「永久」以下发 ttl 字段省略来表达")
        void permanentOmitsTtl() {
            var params = paramsOf(factory, notification(NotificationCategory.INTERACTIVE),
                    PushSettingsBuilder.push().archiveTtl("0").build(), List.of("KEY1"), true);

            assertThat(params).containsEntry("isArchive", "1");
            assertThat(params).doesNotContainKey("ttl");
        }

        @Test
        @DisplayName("测试通知不归档（PRD 5.6）")
        void testNotificationSkipsArchive() {
            var notification = new PushNotification("测试", "", "内容", "",
                    NotificationCategory.NORMAL, false);
            var params = paramsOf(factory, notification,
                    PushSettingsBuilder.push().archiveEnabled(true).build(), List.of("KEY1"), true);

            assertThat(params).doesNotContainKey("isArchive");
            assertThat(params).doesNotContainKey("ttl");
        }

        @Test
        @DisplayName("V1 回退不含归档参数")
        void v1OmitsArchiveParams() {
            var params = paramsOf(factory, notification(NotificationCategory.INTERACTIVE),
                    PushSettingsBuilder.push().archiveEnabled(true).archiveTtl("7d").build(),
                    List.of("KEY1"), false);

            assertThat(params).doesNotContainKey("isArchive");
            assertThat(params).doesNotContainKey("ttl");
        }

        @Test
        @DisplayName("归档保留时长换算")
        void convertsTtl() {
            assertThat(BarkPayloadFactory.ttlSeconds("1d")).isEqualTo(86400);
            assertThat(BarkPayloadFactory.ttlSeconds("7d")).isEqualTo(604800);
            assertThat(BarkPayloadFactory.ttlSeconds("30d")).isEqualTo(2592000);
            assertThat(BarkPayloadFactory.ttlSeconds("0")).isNull();
            assertThat(BarkPayloadFactory.ttlSeconds("")).isNull();
            assertThat(BarkPayloadFactory.ttlSeconds(null)).isNull();
        }

        @Test
        @DisplayName("归档天数换算溢出时钳制为 Integer.MAX_VALUE，绝不抛异常")
        void clampsOverflowDays() {
            assertThat(BarkPayloadFactory.ttlSeconds("24856d")).isEqualTo(Integer.MAX_VALUE);
            // 远超 Long 位数的天数：钳制为上限而非抛出 NumberFormatException
            assertThat(BarkPayloadFactory.ttlSeconds("999999999999999999999d")).isEqualTo(Integer.MAX_VALUE);
        }

        @Test
        @DisplayName("非「Nd」形态的保留时长（如纯数字字符串）按数值解析，越界即视为永久")
        void nonDayFormIsTreatedAsSeconds() {
            assertThat(BarkPayloadFactory.ttlSeconds("3600")).isEqualTo(3600);
            assertThat(BarkPayloadFactory.ttlSeconds("999999999999")).isNull();
        }
    }

    @Nested
    @DisplayName("其余可配参数")
    class OptionalParams {

        @Test
        @DisplayName("角标仅在开关开启且数字可解析时下发")
        void badgeRequiresToggle() {
            var withoutBadge = paramsOf(factory, notification(NotificationCategory.NORMAL),
                    PushSettingsBuilder.push().badge("3").build(), List.of("KEY1"), true);
            assertThat(withoutBadge).doesNotContainKey("badge");

            var withBadge = paramsOf(factory, notification(NotificationCategory.NORMAL),
                    PushSettingsBuilder.push().badgeEnabled(true).badge("3").build(),
                    List.of("KEY1"), true);
            assertThat(withBadge).containsEntry("badge", 3);
        }

        @Test
        @DisplayName("自动复制开启时下发 automaticallyCopy 与 copy（Bark 参数名，非 autoCopy）")
        void autoCopy() {
            var params = paramsOf(factory, notification(NotificationCategory.NORMAL),
                    PushSettingsBuilder.push().autoCopy(true).copy("待复制").build(),
                    List.of("KEY1"), true);

            assertThat(params).containsEntry("automaticallyCopy", "1");
            assertThat(params).containsEntry("copy", "待复制");
        }

        @Test
        @DisplayName("点击行为为默认值时不下发 action")
        void actionOnlyWhenNone() {
            var url = paramsOf(factory, notification(NotificationCategory.NORMAL),
                    PushSettingsBuilder.push().action("url").build(), List.of("KEY1"), true);
            assertThat(url).doesNotContainKey("action");

            var none = paramsOf(factory, notification(NotificationCategory.NORMAL),
                    PushSettingsBuilder.push().action("none").build(), List.of("KEY1"), true);
            assertThat(none).containsEntry("action", "none");
        }

        @Test
        @DisplayName("空白字段不下发，避免发送空值参数")
        void skipsBlankValues() {
            var notification = new PushNotification("标题", "", "正文", "",
                    NotificationCategory.NORMAL, true);
            var params = factory.params(notification, PushSettingsBuilder.push().build(),
                    List.of("KEY1"), "", "", true);

            assertThat(params).doesNotContainKey("subtitle");
            assertThat(params).doesNotContainKey("url");
            assertThat(params).doesNotContainKey("group");
            assertThat(params).doesNotContainKey("icon");
            assertThat(params).containsEntry("body", "正文");
        }
    }

    @Nested
    @DisplayName("请求体与加密")
    class Body {

        @Test
        @DisplayName("未启用加密时明文序列化")
        void plainBody() throws Exception {
            var settings = PushSettingsBuilder.push().build();
            var params = paramsOf(factory, notification(NotificationCategory.NORMAL), settings,
                    List.of("KEY1"), true);

            var body = factory.body(params, settings);

            assertThat(body).contains("\"device_key\":\"KEY1\"").contains("\"body\":\"评论正文\"");
            assertThat(body).doesNotContain("ciphertext");
        }

        @Test
        @DisplayName("启用加密后只下发设备 Key、密文与 IV，不含明文字段")
        void encryptedBodyHidesPlaintext() throws Exception {
            var settings = PushSettingsBuilder.push()
                    .encryptEnabled(true)
                    .encryptKey("1234567890123456")
                    .build();
            var params = paramsOf(factory, notification(NotificationCategory.NORMAL), settings,
                    List.of("KEY1", "KEY2"), true);

            var body = factory.body(params, settings);

            assertThat(Json.field(body, "ciphertext")).isNotBlank();
            assertThat(Json.field(body, "iv")).hasSize(16);
            assertThat(body).contains("\"device_keys\"");
            assertThat(body).doesNotContain("评论正文").doesNotContain("新评论");
        }

        @Test
        @DisplayName("加密配置错误时抛出，交由调用方终止投递而非改发明文")
        void encryptionFailurePropagates() {
            var settings = PushSettingsBuilder.push()
                    .encryptEnabled(true)
                    .encryptKey("short")
                    .build();
            var params = paramsOf(factory, notification(NotificationCategory.NORMAL), settings,
                    List.of("KEY1"), true);

            assertThatThrownBy(() -> factory.body(params, settings))
                    .isInstanceOf(EncryptionException.class)
                    .hasMessageContaining("16 个字节");
        }
    }

    @Nested
    @DisplayName("通知 id：相同 id 的推送在手机端更新替换原通知")
    class NotificationId {

        private static PushNotification withId(String id) {
            return new PushNotification("标题", "", "正文", "", NotificationCategory.NORMAL,
                    true, id);
        }

        @Test
        @DisplayName("V2 携带 id：以字符串类型下发（官方要求 JSON 传参必须字符串）")
        void carriesIdOnV2() {
            var params = paramsOf(factory, withId("halo2bark-health-ab12cd34"),
                    PushSettingsBuilder.push().build(), List.of("KEY1"), true);

            assertThat(params).containsEntry("id", "halo2bark-health-ab12cd34");
        }

        @Test
        @DisplayName("V1 回退不下发 id：保持 V1 参数集精简的既有口径")
        void omitsIdOnV1() {
            var params = paramsOf(factory, withId("halo2bark-health-ab12cd34"),
                    PushSettingsBuilder.push().build(), List.of("KEY1"), false);

            assertThat(params).doesNotContainKey("id");
        }

        @Test
        @DisplayName("无 id 不下发")
        void omitsWhenAbsent() {
            var params = paramsOf(factory, notification(NotificationCategory.NORMAL),
                    PushSettingsBuilder.push().build(), List.of("KEY1"), true);

            assertThat(params).doesNotContainKey("id");
        }

        @Test
        @DisplayName("加密推送时 id 随参数整体进入密文，不以明文出现")
        void idGoesInsideCiphertext() throws com.wuqishi.halo.bark.transport.EncryptionException {
            var settings = PushSettingsBuilder.push()
                    .encryptEnabled(true)
                    .encryptKey("1234567890123456")
                    .build();

            var body = factory.body(
                    factory.params(withId("halo2bark-health-ab12cd34"), settings,
                            List.of("KEY1"), "分组", ICON, true), settings);

            assertThat(body).contains("ciphertext").contains("iv");
            assertThat(body).doesNotContain("halo2bark-health-ab12cd34");
        }
    }
}
