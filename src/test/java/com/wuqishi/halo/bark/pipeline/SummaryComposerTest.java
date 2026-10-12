package com.wuqishi.halo.bark.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.wuqishi.halo.bark.config.PushSettings;
import com.wuqishi.halo.bark.push.NotificationCategory;
import com.wuqishi.halo.bark.push.PushNotification;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("摘要合成：合并 / 限流 / 静默")
class SummaryComposerTest {

    private static final PushSettings SETTINGS = PushSettings.defaults();

    private static BufferedNotification item(Judgement judgement, String subtitle, String body,
            String url) {
        return new BufferedNotification(judgement,
                new PushNotification("原标题", subtitle, body, url,
                        NotificationCategory.INTERACTIVE, true),
                SETTINGS);
    }

    @Test
    @DisplayName("合并摘要：标题为「N 条 + 类型名」；来源与链接取「全部一致」的值")
    void mergedUsesCommonSourceAndUrl() {
        var items = List.of(
                item(Judgement.NEW_COMMENT, "文章标题", "甲：内容一", "https://site/p/1"),
                item(Judgement.NEW_COMMENT, "文章标题", "乙：内容二", "https://site/p/1"));

        var summary = SummaryComposer.merged(Judgement.NEW_COMMENT, items, 0, null);

        assertThat(summary.title()).isEqualTo("💬 2 条新评论");
        assertThat(summary.subtitle()).isEqualTo("文章标题");
        assertThat(summary.url()).isEqualTo("https://site/p/1");
        assertThat(summary.body()).contains("甲：内容一").contains("乙：内容二");
    }

    @Test
    @DisplayName("合并摘要：来源或链接不一致时留空，避免误导")
    void mergedOmitsInconsistentSource() {
        var items = List.of(
                item(Judgement.NEW_COMMENT, "文章A", "一", "https://site/p/1"),
                item(Judgement.NEW_COMMENT, "文章B", "二", "https://site/p/2"));

        var summary = SummaryComposer.merged(Judgement.NEW_COMMENT, items, 0, null);

        assertThat(summary.subtitle()).isEmpty();
        assertThat(summary.url()).isEmpty();
    }

    @Test
    @DisplayName("合并摘要：正文按长度上限优雅截断")
    void mergedTruncates() {
        var items = List.of(
                item(Judgement.NEW_COMMENT, "", "0123456789", ""),
                item(Judgement.NEW_COMMENT, "", "0123456789", ""));

        var summary = SummaryComposer.merged(Judgement.NEW_COMMENT, items, 10, null);

        assertThat(summary.body()).hasSize(10).endsWith("…");
    }

    @Test
    @DisplayName("限流摘要：标题为 PRD 原文，正文按事件类型归纳")
    void overflowBreaksDownByType() {
        var items = List.of(
                item(Judgement.NEW_COMMENT, "", "一", ""),
                item(Judgement.NEW_COMMENT, "", "二", ""),
                item(Judgement.NEW_REPLY, "", "三", ""));

        var summary = SummaryComposer.overflow(items, 0);

        assertThat(summary.title()).isEqualTo("🔔 近 1 分钟另有 3 条通知已合并");
        assertThat(summary.body()).contains("2 条新评论").contains("1 条新回复");
    }

    @Test
    @DisplayName("静默摘要：标题为免打扰条数，正文列出暂存内容")
    void quietListsBuffered() {
        var items = List.of(
                item(Judgement.NEW_COMMENT, "文章标题", "一", "https://site/p/1"),
                item(Judgement.NEW_COMMENT, "文章标题", "二", "https://site/p/1"));

        var summary = SummaryComposer.quiet(items, 0);

        assertThat(summary.title()).isEqualTo("🌙 免打扰期间收到 2 条通知");
        assertThat(summary.subtitle()).isEqualTo("文章标题");
        assertThat(summary.body()).contains("一").contains("二");
    }
}
