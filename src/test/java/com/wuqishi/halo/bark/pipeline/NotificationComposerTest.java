package com.wuqishi.halo.bark.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.wuqishi.halo.bark.content.CommentAuthorResolver;
import com.wuqishi.halo.bark.content.ResolvedSubject;
import com.wuqishi.halo.bark.content.SubjectResolver;
import com.wuqishi.halo.bark.event.SiteEvent;
import com.wuqishi.halo.bark.push.NotificationCategory;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import run.halo.app.infra.ExternalLinkProcessor;

@DisplayName("通知适配：标题 / 副标题 / 正文（纯文本）/ 链接 / 头像 / 配图 / 截断")
class NotificationComposerTest {

    private static final Instant COMMENTED_AT = Instant.parse("2026-08-16T07:20:10Z");

    private final SubjectResolver resolver = mock(SubjectResolver.class);
    private final CommentAuthorResolver authorResolver = mock(CommentAuthorResolver.class);
    private final ExternalLinkProcessor linkProcessor = mock(ExternalLinkProcessor.class);
    private final NotificationComposer composer =
            new NotificationComposer(resolver, authorResolver, linkProcessor);

    {
        // 默认：链接处理为「原样返回」，便于断言绝对化对已是外部地址的 URL 不做改动
        when(linkProcessor.processLink(anyString())).thenAnswer(inv -> inv.getArgument(0));
    }

    private void givenSubject(String title, String url, String kindName) {
        when(resolver.resolve(any())).thenReturn(new ResolvedSubject(title, url, kindName));
    }

    @Test
    @DisplayName("新评论：标题附主体类型；正文首行「谁 · 何时」；HTML 标签被清除")
    void newComment() {
        givenSubject("文章标题", "https://site/archives/p-1", "文章");

        var notification = composer.compose(new SiteEvent.CommentCreated("c", null, "User", "bob",
                "Bob", "涨价是必然<div>不过够用就好</div><img src=\"https://cdn/x.png\">", false,
                COMMENTED_AT), Judgement.NEW_COMMENT, 0);

        assertThat(notification.title()).isEqualTo("💬 新评论 · 文章");
        assertThat(notification.subtitle()).isEqualTo("文章标题");
        assertThat(notification.url()).isEqualTo("https://site/archives/p-1");
        assertThat(notification.body()).doesNotContain("<div>").doesNotContain("<img");
        assertThat(notification.body()).contains("涨价是必然").contains("不过够用就好");
        assertThat(notification.body().lines().findFirst().orElse("")).contains("Bob");
        assertThat(notification.category()).isEqualTo(NotificationCategory.INTERACTIVE);
        assertThat(notification.archive()).isTrue();
    }

    @Test
    @DisplayName("注册用户评论：头像作为逐条图标；正文首图作为展开配图")
    void commentCarriesAvatarAndImage() {
        givenSubject("文章标题", "https://site/archives/p-1", "文章");
        when(authorResolver.avatarUrl("User", "bob")).thenReturn("https://site/upload/avatar.png");

        var notification = composer.compose(new SiteEvent.CommentCreated("c", null, "User", "bob",
                "Bob", "看图<img src=\"https://cdn/x.png\">", false, COMMENTED_AT),
                Judgement.NEW_COMMENT, 0);

        assertThat(notification.icon()).isEqualTo("https://site/upload/avatar.png");
        assertThat(notification.image()).isEqualTo("https://cdn/x.png");
    }

    @Test
    @DisplayName("邮箱评论者无头像：逐条图标为空，交由传输层回退全局图标")
    void emailCommenterHasNoAvatar() {
        givenSubject("", "", "");
        when(authorResolver.avatarUrl("Email", "a@b.c")).thenReturn("");

        var notification = composer.compose(new SiteEvent.CommentCreated("c", null, "Email",
                "a@b.c", "访客", "你好", false, COMMENTED_AT), Judgement.NEW_COMMENT, 0);

        assertThat(notification.icon()).isEmpty();
        assertThat(notification.image()).isEmpty();
    }

    @Test
    @DisplayName("回复事件：正文含「回复了 @某某」一行")
    void replyShowsQuotedAuthor() {
        givenSubject("文章标题", "https://site/archives/p-1", "文章");
        when(authorResolver.quotedReplyAuthorName("r-quoted")).thenReturn("张三");

        var notification = composer.compose(new SiteEvent.ReplyCreated("r", "c-1", "User", "bob",
                "Bob", "同意", "r-quoted", COMMENTED_AT), Judgement.NEW_REPLY, 0);

        assertThat(notification.title()).isEqualTo("↩️ 新回复 · 文章");
        assertThat(notification.body()).contains("回复了 @张三").contains("同意");
    }

    @Test
    @DisplayName("回复无法解析被引用者时仍给出提示行")
    void replyWithoutResolvableQuote() {
        givenSubject("", "", "");
        when(authorResolver.quotedReplyAuthorName(anyString())).thenReturn("");

        var notification = composer.compose(new SiteEvent.ReplyCreated("r", "c-1", "User", "bob",
                "Bob", "同意", "r-quoted", null), Judgement.NEW_REPLY, 0);

        assertThat(notification.body()).contains("回复了某条回复");
    }

    @Test
    @DisplayName("正文用空行分隔「元信息」与「内容」（Markdown 渲染需要）")
    void bodyBlocksSeparatedByBlankLine() {
        givenSubject("", "", "");

        var notification = composer.compose(new SiteEvent.CommentCreated("c", null, "User", "bob",
                "Bob", "内容", false, COMMENTED_AT), Judgement.NEW_COMMENT, 0);

        assertThat(notification.body()).startsWith("Bob · ").contains("\n\n内容");
    }

    @Test
    @DisplayName("评论待审核使用不同标题，并同样附主体类型")
    void pendingComment() {
        givenSubject("文章标题", "https://site/archives/p-1", "文章");

        var notification = composer.compose(new SiteEvent.CommentCreated("c", null, "User", "bob",
                "Bob", "你好", false, COMMENTED_AT), Judgement.PENDING_COMMENT, 0);

        assertThat(notification.title()).isEqualTo("🕓 评论待审核 · 文章");
    }

    @Test
    @DisplayName("无主体类型信息时标题保持简洁")
    void titleWithoutSubjectKind() {
        givenSubject("", "", "");

        var notification = composer.compose(new SiteEvent.CommentCreated("c", null, "User", "bob",
                "Bob", "你好", false, null), Judgement.NEW_COMMENT, 0);

        assertThat(notification.title()).isEqualTo("💬 新评论");
    }

    @Test
    @DisplayName("文章事件：副标题为文章标题")
    void postChanged() {
        givenSubject("文章标题", "https://site/archives/p-1", "");

        var notification = composer.compose(
                new SiteEvent.PostChanged("p-1", SiteEvent.PostChange.PUBLISHED),
                Judgement.POST_PUBLISHED, 0);

        assertThat(notification.title()).isEqualTo("📝 文章已发布");
        assertThat(notification.subtitle()).isEqualTo("文章标题");
        assertThat(notification.url()).isEqualTo("https://site/archives/p-1");
    }

    @Test
    @DisplayName("文章删除 / 取消发布使用与发布不同的标题")
    void postRemoved() {
        givenSubject("文章标题", "https://site/archives/p-1", "");

        var deleted = composer.compose(
                new SiteEvent.PostChanged("p-1", SiteEvent.PostChange.DELETED),
                Judgement.POST_REMOVED, 0);
        var unpublished = composer.compose(
                new SiteEvent.PostChanged("p-1", SiteEvent.PostChange.UNPUBLISHED),
                Judgement.POST_REMOVED, 0);

        assertThat(deleted.title()).isEqualTo("🗑️ 文章已删除或取消发布");
        assertThat(deleted.subtitle()).isEqualTo("文章标题");
        assertThat(unpublished.title()).isEqualTo(deleted.title());
    }

    @Test
    @DisplayName("文章事件带上封面作为展开配图")
    void postCarriesCover() {
        when(resolver.resolve(any())).thenReturn(
                new ResolvedSubject("文章标题", "https://site/archives/p-1", "", "https://site/cover.png"));

        var notification = composer.compose(
                new SiteEvent.PostChanged("p-1", SiteEvent.PostChange.PUBLISHED),
                Judgement.POST_PUBLISHED, 0);

        assertThat(notification.image()).isEqualTo("https://site/cover.png");
    }

    @Test
    @DisplayName("设备事件为安全类，正文含账号 / IP / 平台")
    void deviceLogin() {
        givenSubject("", "https://site/console/users", "");

        var notification = composer.compose(new SiteEvent.DeviceCreated("d", "admin", "203.0.113.7",
                "iOS", "Safari", null, Instant.parse("2026-08-16T07:20:10Z")), Judgement.DEVICE_LOGIN, 0);

        assertThat(notification.title()).isEqualTo("🔐 新设备登录");
        assertThat(notification.category()).isEqualTo(NotificationCategory.SECURITY);
        assertThat(notification.body()).contains("账号：admin").contains("IP：203.0.113.7")
                .contains("iOS · Safari");
    }

    @Test
    @DisplayName("新用户注册正文含显示名与邮箱，头像作为逐条图标")
    void userRegistered() {
        givenSubject("", "https://site/console/users", "");

        var notification = composer.compose(new SiteEvent.UserCreated("u", "新同学", "new@halo.run",
                "https://site/upload/u.png", Instant.parse("2026-08-16T07:20:10Z")),
                Judgement.USER_REGISTERED, 0);

        assertThat(notification.body()).contains("新同学").contains("new@halo.run");
        assertThat(notification.icon()).isEqualTo("https://site/upload/u.png");
    }

    @Test
    @DisplayName("评论配图为站内相对路径时经绝对化变为可下载的外部地址")
    void commentImageIsExternalized() {
        givenSubject("文章标题", "https://site/archives/p-1", "文章");
        when(linkProcessor.processLink("/upload/photo.png"))
                .thenReturn("https://site/upload/photo.png");

        var notification = composer.compose(new SiteEvent.CommentCreated("c", null, "User", "bob",
                "Bob", "看图<img src=\"/upload/photo.png\">", false, COMMENTED_AT),
                Judgement.NEW_COMMENT, 0);

        assertThat(notification.image()).isEqualTo("https://site/upload/photo.png");
    }

    @Test
    @DisplayName("新用户头像为站内相对路径时同样经绝对化")
    void userAvatarIsExternalized() {
        givenSubject("", "https://site/console/users", "");
        when(linkProcessor.processLink("/upload/u.png"))
                .thenReturn("https://site/upload/u.png");

        var notification = composer.compose(new SiteEvent.UserCreated("u", "新同学", "new@halo.run",
                "/upload/u.png", Instant.parse("2026-08-16T07:20:10Z")), Judgement.USER_REGISTERED, 0);

        assertThat(notification.icon()).isEqualTo("https://site/upload/u.png");
    }

    @Test
    @DisplayName("头像为 SVG（iOS 无法渲染）时放弃，回退全局图标")
    void svgIconIsDropped() {
        givenSubject("文章标题", "https://site/archives/p-1", "文章");
        when(authorResolver.avatarUrl("User", "bob")).thenReturn("https://site/upload/avatar.svg");

        var notification = composer.compose(new SiteEvent.CommentCreated("c", null, "User", "bob",
                "Bob", "你好", false, COMMENTED_AT), Judgement.NEW_COMMENT, 0);

        assertThat(notification.icon()).isEmpty();
    }

    @Test
    @DisplayName("头像 URL 带查询串 / 片段的 SVG 仍被识别并放弃")
    void svgIconWithQueryOrFragmentIsDropped() {
        givenSubject("文章标题", "https://site/archives/p-1", "文章");
        when(authorResolver.avatarUrl("User", "bob"))
                .thenReturn("https://site/upload/avatar.svg?token=abc");

        var withQuery = composer.compose(new SiteEvent.CommentCreated("c", null, "User", "bob",
                "Bob", "你好", false, COMMENTED_AT), Judgement.NEW_COMMENT, 0);
        assertThat(withQuery.icon()).isEmpty();

        when(authorResolver.avatarUrl("User", "bob"))
                .thenReturn("https://site/upload/avatar.webp#hash");
        var withFragment = composer.compose(new SiteEvent.CommentCreated("c", null, "User", "bob",
                "Bob", "你好", false, COMMENTED_AT), Judgement.NEW_COMMENT, 0);
        assertThat(withFragment.icon()).isEmpty();
    }

    @Test
    @DisplayName("超过长度上限时优雅截断并补省略号")
    void truncatesBody() {
        givenSubject("", "", "");

        var notification = composer.compose(new SiteEvent.CommentCreated("c", null, "User", "bob",
                "Bob", "0123456789".repeat(5), false, null), Judgement.NEW_COMMENT, 10);

        assertThat(notification.body()).hasSize(10).endsWith("…");
    }

    @Test
    @DisplayName("截断会去掉尾随空白，避免「半句 + 省略号」的观感")
    void truncateStripsTrailingSpace() {
        assertThat(NotificationComposer.truncate("abcdef ghij", 8)).isEqualTo("abcdef…");
    }

    @Test
    @DisplayName("截断边界落在补充字符（emoji）中间时按码点截断，不产生不成对的代理项")
    void truncateDoesNotSplitSurrogatePair() {
        var emoji = "😀"; // 1 个码点，2 个 UTF-16 代码单元
        var text = emoji.repeat(3); // 3 个码点，UTF-16 长度 6
        // 上限 2 个码点，预留 1 个给省略号：应得到「😀」+「…」而非半个 emoji
        var truncated = NotificationComposer.truncate(text, 2);
        assertThat(truncated).isEqualTo(emoji + "…");
        assertThat(truncated.codePointCount(0, truncated.length())).isEqualTo(2);
    }

    @Test
    @DisplayName("含 emoji 的文本按可见码点数截断，而不是按 UTF-16 代码单元")
    void truncateCountsCodePointsNotUnits() {
        // 5 个 emoji，按码点上限 5 不应截断
        var text = "😀".repeat(5);
        assertThat(NotificationComposer.truncate(text, 5)).isEqualTo(text);
        // 上限 4 个码点：保留 3 个 emoji + 省略号
        assertThat(NotificationComposer.truncate(text, 4)).isEqualTo("😀".repeat(3) + "…");
    }
}
