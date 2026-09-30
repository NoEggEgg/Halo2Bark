package com.wuqishi.halo.bark.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.wuqishi.halo.bark.config.PushSettings;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("合并窗口时长解析：越界 / 非正 / 非法回退默认，不抛异常")
class MergeWindowTrackerTest {

    @Test
    @DisplayName("合法值解析为对应分钟数")
    void parsesValid() {
        assertThat(MergeWindowTracker.windowOf(pushWithMergeWindow("2m")))
                .isEqualTo(Duration.ofMinutes(2));
    }

    @Test
    @DisplayName("缺失 / 空白回退默认 2m")
    void defaultsWhenMissing() {
        assertThat(MergeWindowTracker.windowOf(PushSettings.defaults()))
                .isEqualTo(Duration.ofMinutes(2));
        assertThat(MergeWindowTracker.windowOf(pushWithMergeWindow("  ")))
                .isEqualTo(Duration.ofMinutes(2));
    }

    @Test
    @DisplayName("非数字 / 不带 m 单位回退默认")
    void defaultsWhenMalformed() {
        assertThat(MergeWindowTracker.windowOf(pushWithMergeWindow("2x")))
                .isEqualTo(Duration.ofMinutes(2));
        assertThat(MergeWindowTracker.windowOf(pushWithMergeWindow("abc")))
                .isEqualTo(Duration.ofMinutes(2));
    }

    @Test
    @DisplayName("超长分钟数（溢出 long / Duration）回退默认，不抛异常")
    void clampsOverflow() {
        assertThat(MergeWindowTracker.windowOf(pushWithMergeWindow("999999999999999999999m")))
                .isEqualTo(Duration.ofMinutes(2));
    }

    @Test
    @DisplayName("零分钟回退默认，否则窗口瞬间到期失去合并意义")
    void clampsZero() {
        assertThat(MergeWindowTracker.windowOf(pushWithMergeWindow("0m")))
                .isEqualTo(Duration.ofMinutes(2));
    }

    private static PushSettings pushWithMergeWindow(String mergeWindow) {
        return new PushSettings(List.of(), null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, mergeWindow, null, null, null, null, null,
                null, null, null);
    }
}
