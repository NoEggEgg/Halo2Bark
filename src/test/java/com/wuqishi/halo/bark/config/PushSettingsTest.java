package com.wuqishi.halo.bark.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("推送配置快照：角标生效条件")
class PushSettingsTest {

    @Test
    @DisplayName("角标开关开启且数字非负 → 生效")
    void badgeActiveWhenEnabledAndNonNegative() {
        assertThat(settings(true, "5").badgeActive()).isTrue();
        assertThat(settings(true, "0").badgeActive()).isTrue();
    }

    @Test
    @DisplayName("角标开关关闭 → 不生效（无论数字如何）")
    void badgeInactiveWhenDisabled() {
        assertThat(settings(false, "5").badgeActive()).isFalse();
    }

    @Test
    @DisplayName("角标为空 → 不生效")
    void badgeInactiveWhenBlank() {
        assertThat(settings(true, "").badgeActive()).isFalse();
    }

    @Test
    @DisplayName("角标为负数（如 -1）→ 不生效，避免向 Bark 发送非法角标")
    void badgeInactiveWhenNegative() {
        assertThat(settings(true, "-1").badgeActive()).isFalse();
    }

    private static PushSettings settings(boolean badgeEnabled, String badge) {
        return new PushSettings(List.of(), "", "", "", "", badge, badgeEnabled, "", "", false,
                false, "", "", false, "", "", "", false, "", "", false, "", "", "", "");
    }
}
