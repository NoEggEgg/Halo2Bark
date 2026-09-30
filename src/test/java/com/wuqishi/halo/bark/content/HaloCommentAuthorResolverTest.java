package com.wuqishi.halo.bark.content;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import run.halo.app.core.extension.User;
import run.halo.app.core.extension.content.Comment;
import run.halo.app.core.extension.content.Reply;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.infra.ExternalLinkProcessor;

@DisplayName("作者解析：评论者头像与被引用回复作者")
class HaloCommentAuthorResolverTest {

    private final ExtensionClient client = mock(ExtensionClient.class);
    private final ExternalLinkProcessor linkProcessor = mock(ExternalLinkProcessor.class);
    private final HaloCommentAuthorResolver resolver =
            new HaloCommentAuthorResolver(client, linkProcessor);

    @BeforeEach
    void setUp() {
        when(linkProcessor.processLink(anyString()))
                .thenAnswer(invocation -> "https://site" + invocation.getArgument(0));
    }

    private void givenUser(String username, String avatar) {
        var user = new User();
        var spec = new User.UserSpec();
        spec.setAvatar(avatar);
        user.setSpec(spec);
        when(client.fetch(User.class, username)).thenReturn(Optional.of(user));
    }

    private void givenReply(String name, String ownerName, String displayName) {
        var reply = new Reply();
        var spec = new Reply.ReplySpec();
        var owner = new Comment.CommentOwner();
        owner.setKind("User");
        owner.setName(ownerName);
        owner.setDisplayName(displayName);
        spec.setOwner(owner);
        reply.setSpec(spec);
        when(client.fetch(Reply.class, name)).thenReturn(Optional.of(reply));
    }

    @Test
    @DisplayName("注册用户头像经绝对化后返回")
    void resolvesUserAvatar() {
        givenUser("bob", "/upload/avatar.png");
        assertThat(resolver.avatarUrl("User", "bob")).isEqualTo("https://site/upload/avatar.png");
    }

    @Test
    @DisplayName("邮箱评论者没有头像")
    void emailOwnerHasNoAvatar() {
        assertThat(resolver.avatarUrl("Email", "a@b.c")).isEmpty();
    }

    @Test
    @DisplayName("用户不存在或头像为空时返回空串")
    void missingAvatarYieldsEmpty() {
        when(client.fetch(User.class, "ghost")).thenReturn(Optional.empty());
        assertThat(resolver.avatarUrl("User", "ghost")).isEmpty();

        givenUser("carol", "  ");
        assertThat(resolver.avatarUrl("User", "carol")).isEmpty();
    }

    @Test
    @DisplayName("取用户抛异常时就地消化")
    void fetchFailureIsSwallowed() {
        when(client.fetch(User.class, "boom")).thenThrow(new IllegalStateException("db down"));
        assertThat(resolver.avatarUrl("User", "boom")).isEmpty();
    }

    @Test
    @DisplayName("被引用回复的作者优先取显示名")
    void quotedReplyAuthorUsesDisplayName() {
        givenReply("r-1", "zhang", "张三");
        assertThat(resolver.quotedReplyAuthorName("r-1")).isEqualTo("张三");
    }

    @Test
    @DisplayName("显示名缺失时回退到账号名")
    void quotedReplyFallsBackToName() {
        givenReply("r-2", "zhang", "  ");
        assertThat(resolver.quotedReplyAuthorName("r-2")).isEqualTo("zhang");
    }

    @Test
    @DisplayName("未指定被引用回复时返回空串")
    void blankQuoteNameYieldsEmpty() {
        assertThat(resolver.quotedReplyAuthorName(null)).isEmpty();
        assertThat(resolver.quotedReplyAuthorName("  ")).isEmpty();
    }
}
