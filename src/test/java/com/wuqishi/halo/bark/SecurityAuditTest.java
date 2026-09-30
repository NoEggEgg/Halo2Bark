package com.wuqishi.halo.bark;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.wuqishi.halo.bark.event.SiteEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 安全与脱敏的回归测试（PRD 5.8）。
 *
 * <p>把六项要求固化为断言，避免后续改动悄悄破坏：
 * 凭据使用 secret 组件（不落 ConfigMap 明文）、日志不含正文、异常剔除 URL、Key / 地址掩码、
 * 加密配置校验（见 {@code SettingsValidatorTest}）。
 */
@DisplayName("安全与脱敏（PRD 5.8）")
class SecurityAuditTest {

    private static final String SETTINGS_YAML = "src/main/resources/extensions/settings.yaml";

    @Test
    @DisplayName("页面：凭据字段使用 secret 组件，不以明文落入插件配置")
    void credentialFieldsUseSecretComponent() throws Exception {
        var yaml = Files.readString(Path.of(SETTINGS_YAML), UTF_8);

        assertThat(yaml)
                .as("password 只隐藏界面显示、值仍以明文写入 ConfigMap，凭据字段不得再用")
                .doesNotContain("$formkit: password");
        assertThat(yaml.split("\\$formkit: secret", -1).length - 1)
                .as("设备 Key 与加密密钥两处凭据都必须使用 secret 组件")
                .isEqualTo(2);
        assertThat(yaml)
                .as("两个 secret 组件必须分别绑定设备 Key 与加密密钥字段")
                .contains("name: key")
                .contains("name: encryptKey")
                .as("必须声明读取端使用的键名（SecretAccessor.CREDENTIAL_KEY）")
                .contains("key: key");
    }

    @Test
    @DisplayName("日志：评论事件不输出正文")
    void commentEventToStringOmitsContent() {
        var comment = new SiteEvent.CommentCreated("c-1", null, "User", "bob", "Bob",
                "正文里的敏感内容", false, null);

        assertThat(comment.toString())
                .doesNotContain("正文里的敏感内容")
                .contains("c-1");
    }

    @Test
    @DisplayName("日志：回复事件不输出正文")
    void replyEventToStringOmitsContent() {
        var reply = new SiteEvent.ReplyCreated("r-1", "c-1", "User", "bob", "Bob",
                "回复里的敏感内容", null, null);

        assertThat(reply.toString()).doesNotContain("回复里的敏感内容").contains("r-1");
    }

    @Test
    @DisplayName("日志：用户事件不输出邮箱")
    void userEventToStringOmitsEmail() {
        var user = new SiteEvent.UserCreated("u-1", "新同学", "secret@example.com", null, null);

        assertThat(user.toString()).doesNotContain("secret@example.com").contains("u-1");
    }

    @Test
    @DisplayName("异常：剔除形如 URL 的片段（V1 请求路径中含设备 Key）")
    void sanitizeRemovesUrls() {
        var message = "java.lang.IllegalArgumentException: Illegal character in path at index 30: "
                + "https://api.day.app/abcdefghijklmnop/标题";

        var sanitized = Masking.sanitize(message);

        assertThat(sanitized).doesNotContain("abcdefghijklmnop").contains("(地址已隐藏)");
    }

    @Test
    @DisplayName("Key 掩码：仅保留首末各 2 位，短 Key 与空值有统一占位")
    void masksKey() {
        assertThat(Masking.maskKey("abcdefghij")).isEqualTo("ab****ij");
        assertThat(Masking.maskKey("abc")).isEqualTo("****");
        assertThat(Masking.maskKey(null)).isEqualTo("(空)");
        assertThat(Masking.maskKey("  ")).isEqualTo("(空)");
    }

    @Test
    @DisplayName("地址掩码：只保留 scheme://host[:port]，丢弃路径与查询串")
    void masksUrl() {
        assertThat(Masking.maskUrl("https://nas.example.com:5779/abcdefghij?x=1"))
                .isEqualTo("https://nas.example.com:5779");
        assertThat(Masking.maskUrl("https://api.day.app")).isEqualTo("https://api.day.app");
        assertThat(Masking.maskUrl("not a url")).isEqualTo("(未知)");
        assertThat(Masking.maskUrl(null)).isEqualTo("(未知)");
    }
}
