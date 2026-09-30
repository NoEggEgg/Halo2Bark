package com.wuqishi.halo.bark.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.infra.SystemSetting;

@DisplayName("系统设置缓存：comment.requireReviewForNew")
class CommentReviewSettingTest {

    private final ExtensionClient client = mock(ExtensionClient.class);
    private final MutableClock clock = new MutableClock();
    private final CommentReviewSetting setting = new CommentReviewSetting(client, clock);

    private void givenSystemConfig(String commentJson) {
        var configMap = new ConfigMap();
        configMap.setData(Map.of(SystemSetting.Comment.GROUP, commentJson));
        when(client.fetch(ConfigMap.class, SystemSetting.SYSTEM_CONFIG))
                .thenReturn(Optional.of(configMap));
    }

    @Test
    @DisplayName("开启审核时返回 true")
    void readsTrue() {
        givenSystemConfig("{\"enable\":true,\"requireReviewForNew\":true}");
        assertThat(setting.requireReviewForNew()).isTrue();
    }

    @Test
    @DisplayName("未开启审核时返回 false")
    void readsFalse() {
        givenSystemConfig("{\"enable\":true,\"requireReviewForNew\":false}");
        assertThat(setting.requireReviewForNew()).isFalse();
    }

    @Test
    @DisplayName("缺少 system ConfigMap 时按未开启审核处理")
    void missingConfigMap() {
        when(client.fetch(ConfigMap.class, SystemSetting.SYSTEM_CONFIG)).thenReturn(Optional.empty());
        assertThat(setting.requireReviewForNew()).isFalse();
    }

    @Test
    @DisplayName("读取抛异常时就地消化，按未开启审核处理")
    void readFailureIsSwallowed() {
        when(client.fetch(ConfigMap.class, SystemSetting.SYSTEM_CONFIG))
                .thenThrow(new IllegalStateException("db down"));
        assertThat(setting.requireReviewForNew()).isFalse();
    }

    @Test
    @DisplayName("TTL 内命中缓存，不重复读取")
    void cachesWithinTtl() {
        givenSystemConfig("{\"requireReviewForNew\":true}");
        setting.requireReviewForNew();
        setting.requireReviewForNew();
        verify(client, times(1)).fetch(ConfigMap.class, SystemSetting.SYSTEM_CONFIG);
    }

    @Test
    @DisplayName("TTL 到期后失效重读")
    void reloadsAfterTtl() {
        givenSystemConfig("{\"requireReviewForNew\":true}");
        assertThat(setting.requireReviewForNew()).isTrue();
        clock.advance(Duration.ofMinutes(6));
        assertThat(setting.requireReviewForNew()).isTrue();
        verify(client, times(2)).fetch(ConfigMap.class, SystemSetting.SYSTEM_CONFIG);
    }

    @Test
    @DisplayName("读取失败后只短时缓存（30s），尽快重试读取")
    void failureUsesShortTtl() {
        var ok = new ConfigMap();
        ok.setData(Map.of(SystemSetting.Comment.GROUP, "{\"requireReviewForNew\":true}"));
        when(client.fetch(ConfigMap.class, SystemSetting.SYSTEM_CONFIG))
                .thenThrow(new IllegalStateException("db down"))
                .thenReturn(Optional.of(ok));
        assertThat(setting.requireReviewForNew()).isFalse(); // 失败回退
        clock.advance(Duration.ofSeconds(31));              // 超过短时 TTL，未到常规 5 分钟
        assertThat(setting.requireReviewForNew()).isTrue();  // 已重试读取并成功
        verify(client, times(2)).fetch(ConfigMap.class, SystemSetting.SYSTEM_CONFIG);
    }

    @Test
    @DisplayName("主动失效后重新读取")
    void invalidateForcesReload() {
        givenSystemConfig("{\"requireReviewForNew\":false}");
        assertThat(setting.requireReviewForNew()).isFalse();
        setting.invalidate();
        givenSystemConfig("{\"requireReviewForNew\":true}");
        assertThat(setting.requireReviewForNew()).isTrue();
    }

    private static final class MutableClock extends Clock {

        private Instant instant = Instant.parse("2026-09-28T00:00:00Z");

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
