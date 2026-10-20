package com.wuqishi.halo.bark.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.wuqishi.halo.bark.queue.NotificationDispatcher;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import run.halo.app.core.extension.Device;
import run.halo.app.core.extension.User;
import run.halo.app.core.extension.content.Comment;
import run.halo.app.core.extension.content.Post;
import run.halo.app.core.extension.content.Reply;
import run.halo.app.event.post.PostDeletedEvent;
import run.halo.app.event.post.PostPublishedEvent;
import run.halo.app.event.post.PostUnpublishedEvent;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.Ref;
import run.halo.app.extension.controller.Reconciler;

@DisplayName("事件采集器：把事件/扩展对象投影为 SiteEvent")
class CollectorTest {

    private final ExtensionClient client = mock(ExtensionClient.class);
    private final NotificationDispatcher dispatcher = mock(NotificationDispatcher.class);

    @Test
    @DisplayName("评论创建 → 新评论事件（含主体引用、评论者与审核状态）")
    void projectsCommentCreated() {
        var comment = new Comment();
        comment.setMetadata(metadata("c-1"));
        var spec = new Comment.CommentSpec();
        spec.setContent("你好");
        spec.setApproved(false);
        spec.setOwner(owner("User", "u-1", "雾起时"));
        spec.setSubjectRef(ref("content.halo.run", "v1alpha1", "Post", "p-1"));
        comment.setSpec(spec);
        when(client.fetch(Comment.class, "c-1")).thenReturn(Optional.of(comment));

        new CommentReconciler(client, dispatcher).reconcile(new Reconciler.Request("c-1"));

        verify(dispatcher).submit(new SiteEvent.CommentCreated("c-1",
                new SiteEvent.SubjectRef("content.halo.run", "v1alpha1", "Post", "p-1"),
                "User", "u-1", "雾起时", "你好", false, null));
    }

    @Test
    @DisplayName("评论缺少主体引用时仍可采集（引用为 null）")
    void projectsCommentWithoutSubjectRef() {
        var comment = new Comment();
        comment.setMetadata(metadata("c-2"));
        var spec = new Comment.CommentSpec();
        spec.setContent("无主体");
        comment.setSpec(spec);
        when(client.fetch(Comment.class, "c-2")).thenReturn(Optional.of(comment));

        new CommentReconciler(client, dispatcher).reconcile(new Reconciler.Request("c-2"));

        verify(dispatcher).submit(new SiteEvent.CommentCreated("c-2", null, null, null, null,
                "无主体", false, null));
    }

    @Test
    @DisplayName("spec.creationTime 缺失时回退 metadata.creationTimestamp")
    void fallsBackToMetadataTimestamp() {
        var comment = new Comment();
        var metadata = metadata("c-3");
        metadata.setCreationTimestamp(Instant.parse("2026-08-16T07:20:10Z"));
        comment.setMetadata(metadata);
        var spec = new Comment.CommentSpec();
        spec.setContent("无 spec 时间");
        comment.setSpec(spec);
        when(client.fetch(Comment.class, "c-3")).thenReturn(Optional.of(comment));

        new CommentReconciler(client, dispatcher).reconcile(new Reconciler.Request("c-3"));

        verify(dispatcher).submit(new SiteEvent.CommentCreated("c-3", null, null, null, null,
                "无 spec 时间", false, Instant.parse("2026-08-16T07:20:10Z")));
    }

    @Test
    @DisplayName("回复创建 → 新回复事件（携带所属评论名与审核状态）")
    void projectsReplyCreated() {
        var reply = new Reply();
        reply.setMetadata(metadata("r-1"));
        var spec = new Reply.ReplySpec();
        spec.setCommentName("c-1");
        spec.setContent("回复内容");
        spec.setOwner(owner("Email", "a@b.c", "访客"));
        spec.setApproved(true);
        reply.setSpec(spec);
        when(client.fetch(Reply.class, "r-1")).thenReturn(Optional.of(reply));

        new ReplyReconciler(client, dispatcher).reconcile(new Reconciler.Request("r-1"));

        verify(dispatcher).submit(new SiteEvent.ReplyCreated("r-1", "c-1", "Email", "a@b.c",
                "访客", "回复内容", null, null));
    }

    @Test
    @DisplayName("未通过审核的回复：approved=false 原样投影（供待审核判定使用）")
    void projectsUnapprovedReply() {
        var reply = new Reply();
        reply.setMetadata(metadata("r-2"));
        var spec = new Reply.ReplySpec();
        spec.setCommentName("c-1");
        spec.setContent("待审回复");
        spec.setApproved(false);
        reply.setSpec(spec);
        when(client.fetch(Reply.class, "r-2")).thenReturn(Optional.of(reply));

        new ReplyReconciler(client, dispatcher).reconcile(new Reconciler.Request("r-2"));

        verify(dispatcher).submit(new SiteEvent.ReplyCreated("r-2", "c-1", null, null,
                null, "待审回复", null, null, false, false));
    }

    @Test
    @DisplayName("私密评论：hidden=true 原样投影（供私密标注使用）")
    void projectsHiddenComment() {
        var comment = new Comment();
        comment.setMetadata(metadata("c-4"));
        var spec = new Comment.CommentSpec();
        spec.setContent("悄悄话");
        spec.setApproved(true);
        spec.setHidden(true);
        comment.setSpec(spec);
        when(client.fetch(Comment.class, "c-4")).thenReturn(Optional.of(comment));

        new CommentReconciler(client, dispatcher).reconcile(new Reconciler.Request("c-4"));

        verify(dispatcher).submit(new SiteEvent.CommentCreated("c-4", null, null, null, null,
                "悄悄话", true, null, true));
    }

    @Test
    @DisplayName("私密回复：hidden=true 原样投影（Halo 会从父评论传染 hidden，投影实际值即可）")
    void projectsHiddenReply() {
        var reply = new Reply();
        reply.setMetadata(metadata("r-3"));
        var spec = new Reply.ReplySpec();
        spec.setCommentName("c-1");
        spec.setContent("私密回复内容");
        spec.setApproved(true);
        spec.setHidden(true);
        reply.setSpec(spec);
        when(client.fetch(Reply.class, "r-3")).thenReturn(Optional.of(reply));

        new ReplyReconciler(client, dispatcher).reconcile(new Reconciler.Request("r-3"));

        verify(dispatcher).submit(new SiteEvent.ReplyCreated("r-3", "c-1", null, null,
                null, "私密回复内容", null, null, true, true));
    }

    @Test
    @DisplayName("用户创建 → 新用户注册事件")
    void projectsUserCreated() {
        var user = new User();
        user.setMetadata(metadata("u-9"));
        var spec = new User.UserSpec();
        spec.setDisplayName("新同学");
        spec.setEmail("new@halo.run");
        user.setSpec(spec);
        when(client.fetch(User.class, "u-9")).thenReturn(Optional.of(user));

        new UserReconciler(client, dispatcher).reconcile(new Reconciler.Request("u-9"));

        verify(dispatcher).submit(new SiteEvent.UserCreated("u-9", "新同学", "new@halo.run", null, null));
    }

    @Test
    @DisplayName("设备创建 → 新设备登录事件（含 status 中的系统与浏览器）")
    void projectsDeviceCreated() {
        var device = new Device();
        device.setMetadata(metadata("d-1"));
        var spec = new Device.Spec();
        spec.setPrincipalName("admin");
        spec.setIpAddress("203.0.113.7");
        spec.setUserAgent("Mozilla/5.0");
        spec.setLastAuthenticatedTime(Instant.parse("2026-09-28T07:00:00Z"));
        device.setSpec(spec);
        var status = new Device.Status();
        status.setOs("iOS");
        status.setBrowser("Safari");
        device.setStatus(status);
        when(client.fetch(Device.class, "d-1")).thenReturn(Optional.of(device));

        new DeviceReconciler(client, dispatcher).reconcile(new Reconciler.Request("d-1"));

        verify(dispatcher).submit(new SiteEvent.DeviceCreated("d-1", "admin", "203.0.113.7",
                "iOS", "Safari", "Mozilla/5.0", Instant.parse("2026-09-28T07:00:00Z")));
    }

    @Test
    @DisplayName("对象不存在时不提交事件")
    void absentObjectSubmitsNothing() {
        when(client.fetch(Comment.class, "missing")).thenReturn(Optional.empty());

        new CommentReconciler(client, dispatcher).reconcile(new Reconciler.Request("missing"));

        verify(dispatcher, never()).submit(any());
    }

    @Test
    @DisplayName("取对象抛瞬时异常时就地消化并安排重试，不抛给事件总线，也不提交事件")
    void clientFailureIsSwallowedAndRetried() {
        when(client.fetch(Comment.class, "boom")).thenThrow(new IllegalStateException("db down"));

        var result = new CommentReconciler(client, dispatcher)
                .reconcile(new Reconciler.Request("boom"));

        // 就地消化：不抛异常、不提交事件
        verify(dispatcher, never()).submit(any());
        // 但瞬时失败必须安排重试，否则评论/回复/用户/设备事件被永久丢弃
        assertThat(result.reEnqueue()).isTrue();
        assertThat(result.retryAfter()).isNotNull();
        assertThat(result.retryAfter().getSeconds()).isGreaterThan(0);
    }

    @Test
    @DisplayName("文章发布 / 删除 / 取消发布 → 文章变更事件")
    void projectsPostEvents() {
        var listener = new PostEventListener(dispatcher);

        // PostDeletedEvent 的构造器接收 Post 对象（其余两个接收文章名）
        var deleted = new Post();
        deleted.setMetadata(metadata("p-2"));

        listener.onPublished(new PostPublishedEvent(this, "p-1"));
        listener.onDeleted(new PostDeletedEvent(this, deleted));
        listener.onUnpublished(new PostUnpublishedEvent(this, "p-3"));

        verify(dispatcher).submit(new SiteEvent.PostChanged("p-1", SiteEvent.PostChange.PUBLISHED));
        verify(dispatcher).submit(new SiteEvent.PostChanged("p-2", SiteEvent.PostChange.DELETED));
        verify(dispatcher).submit(new SiteEvent.PostChanged("p-3", SiteEvent.PostChange.UNPUBLISHED));
    }

    @Test
    @DisplayName("事件类别决定队列优先级分类")
    void categoriesMatchPrdLevels() {
        assertThat(new SiteEvent.CommentCreated("c", null, null, null, null, null, false, null).category())
                .isEqualTo(com.wuqishi.halo.bark.push.NotificationCategory.INTERACTIVE);
        assertThat(new SiteEvent.PostChanged("p", SiteEvent.PostChange.PUBLISHED).category())
                .isEqualTo(com.wuqishi.halo.bark.push.NotificationCategory.NORMAL);
        assertThat(new SiteEvent.DeviceCreated("d", null, null, null, null, null, null).category())
                .isEqualTo(com.wuqishi.halo.bark.push.NotificationCategory.SECURITY);
    }

    private static Metadata metadata(String name) {
        var metadata = new Metadata();
        metadata.setName(name);
        return metadata;
    }

    private static Comment.CommentOwner owner(String kind, String name, String displayName) {
        var owner = new Comment.CommentOwner();
        owner.setKind(kind);
        owner.setName(name);
        owner.setDisplayName(displayName);
        return owner;
    }

    private static Ref ref(String group, String version, String kind, String name) {
        var ref = new Ref();
        ref.setGroup(group);
        ref.setVersion(version);
        ref.setKind(kind);
        ref.setName(name);
        return ref;
    }
}
