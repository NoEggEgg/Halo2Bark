package com.wuqishi.halo.bark.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.wuqishi.halo.bark.event.SiteEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import run.halo.app.content.comment.CommentSubject;
import run.halo.app.core.extension.content.Comment;
import run.halo.app.core.extension.content.Post;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.extension.Ref;
import run.halo.app.infra.ExternalLinkProcessor;
import run.halo.app.plugin.extensionpoint.ExtensionGetter;

@DisplayName("来源解析：文章 permalink / CommentSubject / 兜底站点首页")
@SuppressWarnings({"rawtypes", "unchecked"})
class HaloSubjectResolverTest {

    private final ExtensionClient client = mock(ExtensionClient.class);
    private final ExtensionGetter extensionGetter = mock(ExtensionGetter.class);
    private final ExternalLinkProcessor linkProcessor = mock(ExternalLinkProcessor.class);
    private final HaloSubjectResolver resolver =
            new HaloSubjectResolver(client, extensionGetter, linkProcessor);

    @BeforeEach
    void setUp() {
        // 绝对化：给相对路径加上站点前缀，便于断言
        when(linkProcessor.processLink(anyString()))
                .thenAnswer(invocation -> "https://site" + invocation.getArgument(0));
    }

    private void givenCommentSubjects(CommentSubject... subjects) {
        List<CommentSubject> list = new ArrayList<>();
        for (var subject : subjects) {
            list.add(subject);
        }
        when(extensionGetter.getExtensionList(CommentSubject.class)).thenReturn(list);
    }

    @Test
    @DisplayName("文章事件：标题取 spec.title，链接取 status.permalink 并绝对化")
    void resolvesPost() {
        var post = new Post();
        var spec = new Post.PostSpec();
        spec.setTitle("文章标题");
        post.setSpec(spec);
        var status = new Post.PostStatus();
        status.setPermalink("/archives/p-1");
        post.setStatus(status);
        when(client.fetch(Post.class, "p-1")).thenReturn(Optional.of(post));

        var resolved = resolver.resolve(new SiteEvent.PostChanged("p-1", SiteEvent.PostChange.PUBLISHED));

        assertThat(resolved.title()).isEqualTo("文章标题");
        assertThat(resolved.url()).isEqualTo("https://site/archives/p-1");
    }

    @Test
    @DisplayName("评论事件：经 CommentSubject 取来源标题与链接")
    void resolvesCommentSubject() {
        givenCommentSubjects(new FakeCommentSubject(true,
                new CommentSubject.SubjectDisplay("文章标题", "/archives/p-1", "文章")));

        var ref = new SiteEvent.SubjectRef("content.halo.run", "v1alpha1", "Post", "p-1");
        var resolved = resolver.resolve(
                new SiteEvent.CommentCreated("c-1", ref, "User", "bob", "Bob", "hi", false, null));

        assertThat(resolved.title()).isEqualTo("文章标题");
        assertThat(resolved.url()).isEqualTo("https://site/archives/p-1#halo-comment=c-1");
    }

    @Test
    @DisplayName("主体类型名必须穿过链接绝对化保留下来")
    void keepsKindNameThroughAbsolute() {
        givenCommentSubjects(new FakeCommentSubject(true,
                new CommentSubject.SubjectDisplay("文章标题", "/archives/p-1", "文章")));

        var ref = new SiteEvent.SubjectRef("content.halo.run", "v1alpha1", "Post", "p-1");
        var resolved = resolver.resolve(
                new SiteEvent.CommentCreated("c-1", ref, "User", "bob", "Bob", "hi", false, null));

        assertThat(resolved.kindName()).isEqualTo("文章");
    }

    @Test
    @DisplayName("文章封面作为配图，且同样被绝对化")
    void resolvesPostCover() {
        var post = new Post();
        var spec = new Post.PostSpec();
        spec.setTitle("文章标题");
        spec.setCover("/upload/cover.png");
        post.setSpec(spec);
        when(client.fetch(Post.class, "p-1")).thenReturn(Optional.of(post));

        var resolved = resolver.resolve(new SiteEvent.PostChanged("p-1", SiteEvent.PostChange.PUBLISHED));

        assertThat(resolved.image()).isEqualTo("https://site/upload/cover.png");
    }

    @Test
    @DisplayName("无匹配的 CommentSubject 时链接降级为站点首页")
    void fallsBackToSiteHome() {
        givenCommentSubjects(new FakeCommentSubject(false, null));

        var ref = new SiteEvent.SubjectRef("moment.halo.run", "v1alpha1", "Moment", "m-1");
        var resolved = resolver.resolve(
                new SiteEvent.CommentCreated("c-1", ref, "User", "bob", "Bob", "hi", false, null));

        assertThat(resolved.url()).isEqualTo("https://site/");
    }

    @Test
    @DisplayName("用户 / 设备事件跳转 Console 用户管理页")
    void userConsolePath() {
        var resolved = resolver.resolve(
                new SiteEvent.DeviceCreated("d", null, null, null, null, null, null));
        assertThat(resolved.url()).isEqualTo("https://site/console/users");
    }

    @Test
    @DisplayName("取对象抛异常时就地消化并降级")
    void fetchFailureIsSwallowed() {
        when(client.fetch(Post.class, "p-1")).thenThrow(new IllegalStateException("db down"));

        var resolved = resolver.resolve(new SiteEvent.PostChanged("p-1", SiteEvent.PostChange.DELETED));

        assertThat(resolved.url()).isEqualTo("https://site/");
    }

    @Test
    @DisplayName("删除事件优先采用随事件携带的标题/链接，不依赖重查")
    void deletedPostUsesCarriedFields() {
        // 不 stub client.fetch：验证标题/链接直接来自事件携带字段，而非重查
        var resolved = resolver.resolve(new SiteEvent.PostChanged("p-1",
                SiteEvent.PostChange.DELETED, "被删除的文章", "/archives/p-1"));

        assertThat(resolved.title()).isEqualTo("被删除的文章");
        assertThat(resolved.url()).isEqualTo("https://site/archives/p-1");
    }

    @Test
    @DisplayName("删除事件携带字段为空时仍走重查路径")
    void deletedPostFallsBackToFetchWhenCarriedFieldsBlank() {
        var post = new Post();
        var spec = new Post.PostSpec();
        spec.setTitle("查到的标题");
        post.setSpec(spec);
        when(client.fetch(Post.class, "p-1")).thenReturn(Optional.of(post));

        var resolved = resolver.resolve(new SiteEvent.PostChanged("p-1",
                SiteEvent.PostChange.DELETED, "", ""));

        assertThat(resolved.title()).isEqualTo("查到的标题");
    }

    @Test
    @DisplayName("某个 CommentSubject 抛异常时不中断循环，后续可解析主体仍被采用")
    void providerFailureDoesNotSkipOthers() {
        givenCommentSubjects(
                new ThrowingCommentSubject(),
                new FakeCommentSubject(true, new CommentSubject.SubjectDisplay("文章标题", "/archives/p-1", "文章")));

        var ref = new SiteEvent.SubjectRef("content.halo.run", "v1alpha1", "Post", "p-1");
        var resolved = resolver.resolve(
                new SiteEvent.CommentCreated("c-1", ref, "User", "bob", "Bob", "hi", false, null));

        assertThat(resolved.title()).isEqualTo("文章标题");
        assertThat(resolved.url()).isEqualTo("https://site/archives/p-1#halo-comment=c-1");
    }

    @Test
    @DisplayName("某个 CommentSubject 的 supports() 抛异常时同样被隔离，不阻止后续主体解析")
    void providerSupportsFailureIsIsolated() {
        givenCommentSubjects(
                new SupportsThrowingCommentSubject(),
                new FakeCommentSubject(true, new CommentSubject.SubjectDisplay("文章标题", "/archives/p-1", "文章")));

        var ref = new SiteEvent.SubjectRef("content.halo.run", "v1alpha1", "Post", "p-1");
        var resolved = resolver.resolve(
                new SiteEvent.CommentCreated("c-1", ref, "User", "bob", "Bob", "hi", false, null));

        assertThat(resolved.title()).isEqualTo("文章标题");
    }

    @Test
    @DisplayName("回复事件：链接附加 halo-comment 与 reply 双参数，直达楼中楼该条回复")
    void replyAnchorsToCommentAndReply() {
        var comment = new Comment();
        var commentSpec = new Comment.CommentSpec();
        commentSpec.setSubjectRef(haloRef("content.halo.run", "v1alpha1", "Post", "p-1"));
        comment.setSpec(commentSpec);
        when(client.fetch(Comment.class, "c-1")).thenReturn(Optional.of(comment));
        givenCommentSubjects(new FakeCommentSubject(true,
                new CommentSubject.SubjectDisplay("文章标题", "/archives/p-1", "文章")));

        var resolved = resolver.resolve(
                new SiteEvent.ReplyCreated("r-1", "c-1", "User", "bob", "Bob", "回复", null, null));

        assertThat(resolved.title()).isEqualTo("文章标题");
        assertThat(resolved.url())
                .isEqualTo("https://site/archives/p-1#halo-comment=c-1&reply=r-1");
    }

    @Test
    @DisplayName("来源解析失败（链接为空）时不加 fragment，仍降级站点首页")
    void blankSubjectUrlKeepsHomeFallback() {
        givenCommentSubjects(new FakeCommentSubject(false, null));

        var ref = new SiteEvent.SubjectRef("moment.halo.run", "v1alpha1", "Moment", "m-1");
        var resolved = resolver.resolve(
                new SiteEvent.CommentCreated("c-1", ref, "User", "bob", "Bob", "hi", false, null));

        assertThat(resolved.url()).isEqualTo("https://site/");
    }

    @Test
    @DisplayName("回复的父评论缺失 subjectRef 时解析为空，链接降级站点首页且不加 fragment")
    void replyWithSubjectlessParentCommentFallsBackToHome() {
        var comment = new Comment();
        var commentSpec = new Comment.CommentSpec();
        comment.setSpec(commentSpec);
        when(client.fetch(Comment.class, "c-orphan")).thenReturn(Optional.of(comment));
        givenCommentSubjects(new FakeCommentSubject(true,
                new CommentSubject.SubjectDisplay("不应被使用", "/archives/p-1", "文章")));

        var resolved = resolver.resolve(
                new SiteEvent.ReplyCreated("r-1", "c-orphan", "User", "bob", "Bob", "回复", null, null));

        assertThat(resolved.title()).isEmpty();
        assertThat(resolved.url()).isEqualTo("https://site/");
    }

    @Test
    @DisplayName("commentPermalink：与 Halo CommentPermalinkService 同规则拒绝非法链接")
    void commentPermalinkRejectsInvalidUrls() {
        // opaque URI（mailto: 等）
        assertThat(HaloSubjectResolver.commentPermalink("mailto:a@b.c", "c-1", null)).isNull();
        // 非 http(s) 协议
        assertThat(HaloSubjectResolver.commentPermalink("ftp://site/p", "c-1", null)).isNull();
        // 既无协议又无 path
        assertThat(HaloSubjectResolver.commentPermalink("?query-only", "c-1", null)).isNull();
        // 评论对象名缺失
        assertThat(HaloSubjectResolver.commentPermalink("https://site/p", " ", null)).isNull();
        assertThat(HaloSubjectResolver.commentPermalink(null, "c-1", null)).isNull();
        // 主体链接为空
        assertThat(HaloSubjectResolver.commentPermalink("", "c-1", null)).isNull();
    }

    @Test
    @DisplayName("commentPermalink：替换既有 fragment 而非叠加出两个 #")
    void commentPermalinkReplacesExistingFragment() {
        assertThat(HaloSubjectResolver.commentPermalink("https://site/p#old", "c-1", "r-1"))
                .isEqualTo("https://site/p#halo-comment=c-1&reply=r-1");
        assertThat(HaloSubjectResolver.commentPermalink("https://site/p#old", "c-1", null))
                .isEqualTo("https://site/p#halo-comment=c-1");
    }

    private static Ref haloRef(String group, String version, String kind, String name) {
        var ref = new Ref();
        ref.setGroup(group);
        ref.setVersion(version);
        ref.setKind(kind);
        ref.setName(name);
        return ref;
    }

    /** 手写的 CommentSubject 实现，避免 Mockito 在泛型接口上的复杂性。 */
    private static final class FakeCommentSubject implements CommentSubject<Post> {

        private final boolean supported;
        private final CommentSubject.SubjectDisplay display;

        FakeCommentSubject(boolean supported, CommentSubject.SubjectDisplay display) {
            this.supported = supported;
            this.display = display;
        }

        @Override
        public Mono<Post> get(String name) {
            return Mono.empty();
        }

        @Override
        public Mono<CommentSubject.SubjectDisplay> getSubjectDisplay(String name) {
            return Mono.just(display);
        }

        @Override
        public boolean supports(Ref ref) {
            return supported;
        }
    }

    /** 在 {@code getSubjectDisplay} 同步抛异常，用于验证逐 provider 异常隔离。 */
    private static final class ThrowingCommentSubject implements CommentSubject<Post> {

        @Override
        public Mono<Post> get(String name) {
            return Mono.empty();
        }

        @Override
        public Mono<CommentSubject.SubjectDisplay> getSubjectDisplay(String name) {
            throw new RuntimeException("provider boom");
        }

        @Override
        public boolean supports(Ref ref) {
            return true;
        }
    }

    /** 在 {@code supports} 同步抛异常，用于验证 supports 路径也被隔离。 */
    private static final class SupportsThrowingCommentSubject implements CommentSubject<Post> {

        @Override
        public Mono<Post> get(String name) {
            return Mono.empty();
        }

        @Override
        public Mono<CommentSubject.SubjectDisplay> getSubjectDisplay(String name) {
            return Mono.just(new CommentSubject.SubjectDisplay("不应被使用", "/x", "文章"));
        }

        @Override
        public boolean supports(Ref ref) {
            throw new RuntimeException("supports boom");
        }
    }
}
