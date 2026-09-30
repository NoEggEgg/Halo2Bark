package com.wuqishi.halo.bark.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 安全事件对象 {@code toString()} 不得泄漏敏感信息——生成的 {@code toString} 会把登录账号、
 * IP、User-Agent 写进日志。
 */
@DisplayName("SiteEvent.toString：安全事件脱敏")
class SiteEventToStringTest {

    @Test
    @DisplayName("DeviceCreated 的 toString 不含账号、IP 与 User-Agent")
    void deviceCreatedToStringIsRedacted() {
        var event = new SiteEvent.DeviceCreated("iPhone", "admin", "203.0.113.7", "iOS", "Safari",
                "Mozilla/5.0 (iPhone)", Instant.parse("2026-09-28T07:00:00Z"));

        var text = event.toString();
        assertThat(text).contains("iPhone").contains("iOS").contains("Safari");
        assertThat(text).doesNotContain("admin");
        assertThat(text).doesNotContain("203.0.113.7");
        assertThat(text).doesNotContain("Mozilla");
    }

    @Test
    @DisplayName("UserCreated 的 toString 不含邮箱")
    void userCreatedToStringIsRedacted() {
        var event = new SiteEvent.UserCreated("u-1", "雾起时", "me@example.com", null, null);

        var text = event.toString();
        assertThat(text).contains("u-1").contains("雾起时");
        assertThat(text).doesNotContain("me@example.com");
    }

    @Test
    @DisplayName("CommentCreated 的 toString 不含访客邮箱（Email 评论者 ownerName 即邮箱）")
    void commentCreatedToStringOmitsEmail() {
        var event = new SiteEvent.CommentCreated("c-1", null, "Email", "guest@example.com",
                "访客", "不错", false, null);

        var text = event.toString();
        assertThat(text).contains("CommentCreated").contains("Email");
        assertThat(text).doesNotContain("guest@example.com");
    }

    @Test
    @DisplayName("ReplyCreated 的 toString 不含访客邮箱")
    void replyCreatedToStringOmitsEmail() {
        var event = new SiteEvent.ReplyCreated("r-1", "c-1", "Email", "guest@example.com",
                "访客", "同意", null, null);

        var text = event.toString();
        assertThat(text).contains("ReplyCreated").contains("Email");
        assertThat(text).doesNotContain("guest@example.com");
    }
}
