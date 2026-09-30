package com.wuqishi.halo.bark.push;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("通知记录：空值归一化与便捷构造")
class PushNotificationTest {

    @Test
    @DisplayName("null 字段归一化为空串，类别缺失回退普通类")
    void normalizesNulls() {
        var notification = new PushNotification(null, null, null, null, null, false);

        assertThat(notification.title()).isEmpty();
        assertThat(notification.subtitle()).isEmpty();
        assertThat(notification.body()).isEmpty();
        assertThat(notification.url()).isEmpty();
        assertThat(notification.icon()).isEmpty();
        assertThat(notification.image()).isEmpty();
        assertThat(notification.category()).isEqualTo(NotificationCategory.NORMAL);
    }

    @Test
    @DisplayName("六参便捷构造：逐条图标与配图为空，其余原样保留")
    void convenienceConstructor() {
        var notification = new PushNotification("标题", "副标题", "正文", "https://site/p/1",
                NotificationCategory.SECURITY, true);

        assertThat(notification.icon()).isEmpty();
        assertThat(notification.image()).isEmpty();
        assertThat(notification.archive()).isTrue();
        assertThat(notification.category()).isEqualTo(NotificationCategory.SECURITY);
    }

    @Test
    @DisplayName("null 图标与配图同样归一化为空串")
    void normalizesIconAndImage() {
        var notification = new PushNotification("t", "s", "b", "u", NotificationCategory.NORMAL,
                true, null, null);

        assertThat(notification.icon()).isEmpty();
        assertThat(notification.image()).isEmpty();
    }
}
