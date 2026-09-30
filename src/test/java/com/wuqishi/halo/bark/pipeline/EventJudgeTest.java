package com.wuqishi.halo.bark.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.wuqishi.halo.bark.content.CommentReviewSetting;
import com.wuqishi.halo.bark.event.SiteEvent;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import run.halo.app.core.extension.User;
import run.halo.app.core.extension.content.Comment;
import run.halo.app.core.extension.content.Post;
import run.halo.app.core.extension.content.Reply;
import run.halo.app.core.extension.content.SinglePage;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.extension.Ref;

@DisplayName("事件判定：自己评论自己 / 回复自己 / 待审核互斥")
class EventJudgeTest {

    private final ExtensionClient client = mock(ExtensionClient.class);
    private final CommentReviewSetting reviewSetting = mock(CommentReviewSetting.class);
    private final EventJudge judge = new EventJudge(client, reviewSetting);

    private static SiteEvent.SubjectRef postRef(String name) {
        return new SiteEvent.SubjectRef("content.halo.run", "v1alpha1", "Post", name);
    }

    private void givenPostOwner(String postName, String owner) {
        var post = new Post();
        var spec = new Post.PostSpec();
        spec.setOwner(owner);
        post.setSpec(spec);
        when(client.fetch(Post.class, postName)).thenReturn(Optional.of(post));
    }

    private void givenUserEmail(String username, String email) {
        var user = new User();
        var spec = new User.UserSpec();
        spec.setEmail(email);
        user.setSpec(spec);
        when(client.fetch(User.class, username)).thenReturn(Optional.of(user));
    }

    private void givenCommentOwner(String commentName, String kind, String name) {
        var comment = new Comment();
        var spec = new Comment.CommentSpec();
        var owner = new Comment.CommentOwner();
        owner.setKind(kind);
        owner.setName(name);
        spec.setOwner(owner);
        comment.setSpec(spec);
        when(client.fetch(Comment.class, commentName)).thenReturn(Optional.of(comment));
    }

    /** 评论：指定评论者与被评论主体（用于取主体作者）。 */
    private void givenCommentOnSubject(String commentName, String ownerKind, String ownerName,
            String subjectKind, String subjectName) {
        var comment = new Comment();
        var spec = new Comment.CommentSpec();
        var owner = new Comment.CommentOwner();
        owner.setKind(ownerKind);
        owner.setName(ownerName);
        spec.setOwner(owner);
        var ref = new Ref();
        ref.setGroup("content.halo.run");
        ref.setVersion("v1alpha1");
        ref.setKind(subjectKind);
        ref.setName(subjectName);
        spec.setSubjectRef(ref);
        comment.setSpec(spec);
        when(client.fetch(Comment.class, commentName)).thenReturn(Optional.of(comment));
    }

    private void givenReplyOwner(String replyName, String kind, String name) {
        var reply = new Reply();
        var spec = new Reply.ReplySpec();
        var owner = new Comment.CommentOwner();
        owner.setKind(kind);
        owner.setName(name);
        spec.setOwner(owner);
        reply.setSpec(spec);
        when(client.fetch(Reply.class, replyName)).thenReturn(Optional.of(reply));
    }

    @Test
    @DisplayName("规则1：以用户账号评论自己的文章 → 不通知")
    void selfCommentByUserAccount() {
        givenPostOwner("p-1", "alice");
        var event = new SiteEvent.CommentCreated("c-1", postRef("p-1"), "User", "alice", "Alice",
                "hi", false, null);
        assertThat(judge.judge(event)).isEmpty();
    }

    @Test
    @DisplayName("规则1：以邮箱评论自己的文章（邮箱与账号同人）→ 不通知")
    void selfCommentByEmail() {
        givenPostOwner("p-1", "alice");
        givenUserEmail("alice", "alice@example.com");
        var event = new SiteEvent.CommentCreated("c-1", postRef("p-1"), "Email",
                "alice@example.com", "Alice", "hi", false, null);
        assertThat(judge.judge(event)).isEmpty();
    }

    @Test
    @DisplayName("规则1：访客以不同大小写邮箱评论自己的文章（邮箱与账号同人）→ 不通知")
    void selfCommentByEmailDifferentCase() {
        givenPostOwner("p-1", "alice");
        givenUserEmail("alice", "alice@example.com");
        var event = new SiteEvent.CommentCreated("c-1", postRef("p-1"), "Email",
                "ALICE@EXAMPLE.COM", "Alice", "hi", false, null);
        assertThat(judge.judge(event)).isEmpty();
    }

    @Test
    @DisplayName("他人评论 → 新评论")
    void otherAuthorComment() {
        givenPostOwner("p-1", "alice");
        when(reviewSetting.requireReviewForNew()).thenReturn(false);
        var event = new SiteEvent.CommentCreated("c-1", postRef("p-1"), "User", "bob", "Bob",
                "hi", false, null);
        assertThat(judge.judge(event)).contains(Judgement.NEW_COMMENT);
    }

    @Test
    @DisplayName("规则3：开启审核且未通过 → 评论待审核")
    void pendingWhenReviewRequired() {
        givenPostOwner("p-1", "alice");
        when(reviewSetting.requireReviewForNew()).thenReturn(true);
        var event = new SiteEvent.CommentCreated("c-1", postRef("p-1"), "User", "bob", "Bob",
                "hi", false, null);
        assertThat(judge.judge(event)).contains(Judgement.PENDING_COMMENT);
    }

    @Test
    @DisplayName("规则3：开启审核但已通过 → 新评论（互斥）")
    void approvedIsNewComment() {
        givenPostOwner("p-1", "alice");
        when(reviewSetting.requireReviewForNew()).thenReturn(true);
        var event = new SiteEvent.CommentCreated("c-1", postRef("p-1"), "User", "bob", "Bob",
                "hi", true, null);
        assertThat(judge.judge(event)).contains(Judgement.NEW_COMMENT);
    }

    @Test
    @DisplayName("第三方主体无法判定作者 → 不抑制通知")
    void unknownSubjectKindIsNotSuppressed() {
        when(reviewSetting.requireReviewForNew()).thenReturn(false);
        var ref = new SiteEvent.SubjectRef("moment.halo.run", "v1alpha1", "Moment", "m-1");
        var event = new SiteEvent.CommentCreated("c-1", ref, "User", "alice", "Alice", "hi", false, null);
        assertThat(judge.judge(event)).contains(Judgement.NEW_COMMENT);
    }

    @Test
    @DisplayName("规则2：回复自己的评论 → 不通知")
    void selfReply() {
        givenCommentOwner("c-9", "User", "alice");
        var event = new SiteEvent.ReplyCreated("r-1", "c-9", "User", "alice", "Alice", "reply", null, null);
        assertThat(judge.judge(event)).isEmpty();
    }

    @Test
    @DisplayName("规则2：回复他人评论 → 新回复")
    void replyToOther() {
        givenCommentOwner("c-9", "User", "alice");
        var event = new SiteEvent.ReplyCreated("r-1", "c-9", "User", "bob", "Bob", "reply", null, null);
        assertThat(judge.judge(event)).contains(Judgement.NEW_REPLY);
    }

    @Test
    @DisplayName("规则2：访客以邮箱回复自己的评论 → 不通知")
    void selfReplyByEmail() {
        givenCommentOwner("c-9", "Email", "guest@example.com");
        var event = new SiteEvent.ReplyCreated("r-1", "c-9", "Email", "guest@example.com", "访客",
                "reply", null, null);
        assertThat(judge.judge(event)).isEmpty();
    }

    @Test
    @DisplayName("规则2：访客以不同大小写邮箱回复自己的评论 → 不通知（邮箱大小写不敏感）")
    void selfReplyByEmailDifferentCase() {
        givenCommentOwner("c-9", "Email", "guest@example.com");
        var event = new SiteEvent.ReplyCreated("r-1", "c-9", "Email", "Guest@Example.com", "访客",
                "reply", null, null);
        assertThat(judge.judge(event)).isEmpty();
    }

    @Test
    @DisplayName("规则2：以邮箱回复自己的账号评论（邮箱与账号同人）→ 不通知")
    void selfReplyByEmailAgainstUserAccount() {
        givenCommentOwner("c-9", "User", "alice");
        givenUserEmail("alice", "alice@example.com");
        var event = new SiteEvent.ReplyCreated("r-1", "c-9", "Email", "alice@example.com", "Alice",
                "reply", null, null);
        assertThat(judge.judge(event)).isEmpty();
    }

    @Test
    @DisplayName("规则2：以账号回复自己的邮箱评论（邮箱与账号同人）→ 不通知")
    void selfReplyByUserAccountAgainstEmail() {
        givenCommentOwner("c-9", "Email", "alice@example.com");
        givenUserEmail("alice", "alice@example.com");
        var event = new SiteEvent.ReplyCreated("r-1", "c-9", "User", "alice", "Alice", "reply", null, null);
        assertThat(judge.judge(event)).isEmpty();
    }

    @Test
    @DisplayName("规则2：访客以邮箱回复他人的邮箱评论 → 新回复")
    void replyByEmailToOther() {
        givenCommentOwner("c-9", "Email", "guest@example.com");
        var event = new SiteEvent.ReplyCreated("r-1", "c-9", "Email", "other@example.com", "另一位访客",
                "reply", null, null);
        assertThat(judge.judge(event)).contains(Judgement.NEW_REPLY);
    }

    @Test
    @DisplayName("规则2：楼中楼里回复自己的回复 → 不通知")
    void selfReplyToOwnReply() {
        givenCommentOwner("c-9", "Email", "guest@example.com");
        givenReplyOwner("r-0", "Email", "guest@example.com");
        var event = new SiteEvent.ReplyCreated("r-1", "c-9", "Email", "guest@example.com", "访客",
                "reply", "r-0", null);
        assertThat(judge.judge(event)).isEmpty();
    }

    @Test
    @DisplayName("规则2：被回复者按 quoteReply 取——他人回复我的回复（根评论却是他自己的）→ 新回复")
    void replyToMyReplyIsNotified() {
        givenCommentOwner("c-9", "User", "bob");
        givenReplyOwner("r-0", "User", "alice");
        var event = new SiteEvent.ReplyCreated("r-1", "c-9", "User", "bob", "Bob",
                "reply", "r-0", null);
        assertThat(judge.judge(event)).contains(Judgement.NEW_REPLY);
    }

    @Test
    @DisplayName("规则2：两位访客之间的回复 → 新回复（管理员需要知道）")
    void guestRepliesToAnotherGuest() {
        givenCommentOwner("c-9", "Email", "a@example.com");
        givenReplyOwner("r-0", "Email", "a@example.com");
        var event = new SiteEvent.ReplyCreated("r-1", "c-9", "Email", "b@example.com", "另一位访客",
                "reply", "r-0", null);
        assertThat(judge.judge(event)).contains(Judgement.NEW_REPLY);
    }

    @Test
    @DisplayName("与规则1同理：管理员（文章作者）回复访客评论 → 不通知")
    void adminReplyIsNotNotified() {
        givenCommentOnSubject("c-9", "Email", "guest@example.com", "Post", "p-1");
        givenPostOwner("p-1", "alice");
        var event = new SiteEvent.ReplyCreated("r-1", "c-9", "User", "alice", "Alice",
                "reply", null, null);
        assertThat(judge.judge(event)).isEmpty();
    }

    @Test
    @DisplayName("与规则1同理：管理员以邮箱身份回复自己的文章 → 不通知")
    void adminReplyByEmailIsNotNotified() {
        givenCommentOnSubject("c-9", "Email", "guest@example.com", "SinglePage", "pg-1");
        var page = new SinglePage();
        var pageSpec = new SinglePage.SinglePageSpec();
        pageSpec.setOwner("alice");
        page.setSpec(pageSpec);
        when(client.fetch(SinglePage.class, "pg-1")).thenReturn(Optional.of(page));
        givenUserEmail("alice", "alice@example.com");

        var event = new SiteEvent.ReplyCreated("r-1", "c-9", "Email", "alice@example.com", "Alice",
                "reply", null, null);
        assertThat(judge.judge(event)).isEmpty();
    }

    @Test
    @DisplayName("与规则1同理：他人回复管理员的评论 → 新回复")
    void guestReplyToAdminCommentIsNotified() {
        givenCommentOnSubject("c-9", "User", "alice", "Post", "p-1");
        givenPostOwner("p-1", "alice");
        var event = new SiteEvent.ReplyCreated("r-1", "c-9", "Email", "guest@example.com", "访客",
                "reply", null, null);
        assertThat(judge.judge(event)).contains(Judgement.NEW_REPLY);
    }

    @Test
    @DisplayName("第三方主体无法判定主体作者 → 回复仍不抑制")
    void replyOnUnknownSubjectIsNotSuppressed() {
        givenCommentOnSubject("c-9", "Email", "guest@example.com", "Moment", "m-1");
        var event = new SiteEvent.ReplyCreated("r-1", "c-9", "User", "alice", "Alice",
                "reply", null, null);
        assertThat(judge.judge(event)).contains(Judgement.NEW_REPLY);
    }

    @Test
    @DisplayName("文章 / 用户 / 设备事件的判定")
    void postUserDeviceJudgements() {
        assertThat(judge.judge(new SiteEvent.PostChanged("p", SiteEvent.PostChange.PUBLISHED)))
                .contains(Judgement.POST_PUBLISHED);
        assertThat(judge.judge(new SiteEvent.PostChanged("p", SiteEvent.PostChange.DELETED)))
                .contains(Judgement.POST_REMOVED);
        assertThat(judge.judge(new SiteEvent.PostChanged("p", SiteEvent.PostChange.UNPUBLISHED)))
                .contains(Judgement.POST_REMOVED);
        assertThat(judge.judge(new SiteEvent.UserCreated("u", "N", "e@x", null, null)))
                .contains(Judgement.USER_REGISTERED);
        assertThat(judge.judge(new SiteEvent.DeviceCreated("d", null, null, null, null, null, null)))
                .contains(Judgement.DEVICE_LOGIN);
    }
}
