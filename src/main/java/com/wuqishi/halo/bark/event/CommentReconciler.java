package com.wuqishi.halo.bark.event;

import com.wuqishi.halo.bark.queue.NotificationDispatcher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import run.halo.app.core.extension.content.Comment;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.extension.Ref;

/**
 * 评论采集器，覆盖 PRD 4.1 的「新评论」与「评论待审核」两个事件。
 *
 * <p>两者都源自**评论对象创建**：对应的事件类 {@code CommentCreatedEvent} 位于 Halo application
 * 模块且未标注 {@code @SharedEvent}，不会被转发到插件上下文，插件无法监听；而 {@code Comment}
 * 是插件可监听的扩展对象，故统一由本采集器覆盖。
 *
 * <p>「新评论」与「评论待审核」的互斥判定需要读取系统设置 {@code comment.requireReviewForNew}
 * （I/O），因此不在采集层完成，而是随 {@code approved} 一并交给投递线程上的流水线。
 */
@Slf4j
@Component
public class CommentReconciler extends CreateReconciler<Comment> {

    private final NotificationDispatcher dispatcher;

    public CommentReconciler(ExtensionClient client, NotificationDispatcher dispatcher) {
        super(client, Comment.class, Comment::new);
        this.dispatcher = dispatcher;
    }

    @Override
    protected void onCreated(Comment comment) {
        var spec = comment.getSpec();
        if (spec == null) {
            return;
        }
        var owner = spec.getOwner();
        dispatcher.submit(new SiteEvent.CommentCreated(
                comment.getMetadata().getName(),
                toSubjectRef(spec.getSubjectRef()),
                owner == null ? null : owner.getKind(),
                owner == null ? null : owner.getName(),
                owner == null ? null : owner.getDisplayName(),
                spec.getContent(),
                Boolean.TRUE.equals(spec.getApproved()),
                displayTime(spec.getCreationTime(), comment.getMetadata())));
    }

    private static SiteEvent.SubjectRef toSubjectRef(Ref ref) {
        return ref == null ? null
                : new SiteEvent.SubjectRef(ref.getGroup(), ref.getVersion(), ref.getKind(),
                        ref.getName());
    }
}
