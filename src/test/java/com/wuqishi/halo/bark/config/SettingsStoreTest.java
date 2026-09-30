package com.wuqishi.halo.bark.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.wuqishi.halo.bark.config.BarkSettings;
import com.wuqishi.halo.bark.config.EventToggles;
import com.wuqishi.halo.bark.config.PushSettings;
import com.wuqishi.halo.bark.support.FakeSecretAccessor;
import com.wuqishi.halo.bark.support.PushSettingsBuilder;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import run.halo.app.plugin.ReactiveSettingFetcher;

@DisplayName("配置热更新：乱序刷新不覆盖新配置")
class SettingsStoreTest {

    private final ReactiveSettingFetcher fetcher = mock(ReactiveSettingFetcher.class);
    private final SettingsStore store = new SettingsStore(fetcher,
            new CredentialResolver(FakeSecretAccessor.of(Map.of())));

    @Test
    @DisplayName("过期刷新（先到）被丢弃，保留更新的刷新结果")
    void staleRefreshIsDiscarded() throws Exception {
        // 序号 1 的刷新已生效（latestSeq=1）；序号 0 的迟到结果到达时应被丢弃
        setLatestSeq(1);
        store.applyIfLatest(1, withGroup("newer"));
        store.applyIfLatest(0, withGroup("older"));
        assertThat(store.current().push().group()).isEqualTo("newer");
    }

    @Test
    @DisplayName("正常顺序：后到的刷新覆盖先到的")
    void orderedRefreshAppliesLatest() throws Exception {
        fire(0, withGroup("first"));
        fire(1, withGroup("second"));
        assertThat(store.current().push().group()).isEqualTo("second");
    }

    @Test
    @DisplayName("单条刷新直接生效")
    void singleRefreshApplies() throws Exception {
        fire(5, withGroup("solo"));
        assertThat(store.current().push().group()).isEqualTo("solo");
    }

    @Test
    @DisplayName("开启加密但密钥无效：采用新配置并标记为非法，不退回上一份明文配置")
    void invalidEncryptionDoesNotFallBackToPreviousPlaintext() throws Exception {
        // 1) 先生效一份「未开启加密」的明文配置
        fire(0, withPush(PushSettingsBuilder.push().encryptEnabled(false).build()));
        assertThat(store.current().push().encryptEnabled()).isFalse();
        assertThat(store.current().valid()).isTrue();

        // 2) 管理员勾选「启用加密」并填入一个无效密钥
        fire(1, withPush(PushSettingsBuilder.push()
                .encryptEnabled(true)
                .encryptAlgorithm("AES128")
                .encryptMode("CBC")
                .encryptPadding("pkcs7")
                .encryptKey("short")
                .build()));

        var current = store.current();
        assertThat(current.push().encryptEnabled())
                .as("必须采用新配置；沿用上一份明文配置会让后续推送以明文发出")
                .isTrue();
        assertThat(current.valid())
                .as("必须标记为非法，使投递线程停止推送")
                .isFalse();
        assertThat(current.issues())
                .as("必须给出可操作的错误说明，便于管理员修正")
                .anyMatch(issue -> issue.contains("加密密钥"));
    }

    @Test
    @DisplayName("密钥合法：快照标记为合法且无校验问题")
    void validEncryptionIsMarkedValid() throws Exception {
        fire(0, withPush(PushSettingsBuilder.push()
                .encryptEnabled(true)
                .encryptAlgorithm("AES128")
                .encryptMode("CBC")
                .encryptPadding("pkcs7")
                .encryptKey("0123456789abcdef")
                .build()));

        assertThat(store.current().valid()).isTrue();
        assertThat(store.current().issues()).isEmpty();
    }

    /**
     * 复刻 {@code onConfigUpdated} 的语义：先以序号推进 latestSeq（对应 incrementAndGet），
     * 再以该序号尝试生效。仅当本序号仍为最新时才写入选生效快照。
     */
    private void fire(long seq, BarkSettings candidate) throws Exception {
        setLatestSeq(seq);
        store.applyIfLatest(seq, candidate);
    }

    private void setLatestSeq(long value) throws Exception {
        Field field = SettingsStore.class.getDeclaredField("latestSeq");
        field.setAccessible(true);
        ((AtomicLong) field.get(store)).set(value);
    }

    private static BarkSettings withPush(PushSettings push) {
        return new BarkSettings(push, EventToggles.defaults());
    }

    private static BarkSettings withGroup(String group) {
        var push = new PushSettings(List.of(), group, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null);
        return new BarkSettings(push, EventToggles.defaults());
    }
}
