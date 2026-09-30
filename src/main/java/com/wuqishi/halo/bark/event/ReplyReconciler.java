package com.wuqishi.halo.bark.event;

import com.wuqishi.halo.bark.queue.NotificationDispatcher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import run.halo.app.core.extension.content.Reply;
import run.halo.app.extension.ExtensionClient;

/**
 * 回复采集器，覆盖 PRD 4.1 的「新回复」事件。
 *
 * <p>对应的事件类 {@code ReplyCreatedEvent} 位于 Halo application 模块且未标注 {@code @SharedEvent}，
 * 插件无法监听；而 {@code Reply} 是插件可监听的扩展对象，故由本采集器覆盖。
 *
 * <p>「回复自己不通知」的判定需要取被回复者的身份（I/O），因此不在采集层完成——采集层只携带
 * 所属评论名 {@code commentName}，判定与内容获取留给投递线程上的流水线。
 */
@Slf4j
@Component
public class ReplyReconciler extends CreateReconciler<Reply> {

    private final NotificationDispatcher dispatcher;

    public ReplyReconciler(ExtensionClient client, NotificationDispatcher dispatcher) {
        super(client, Reply.class, Reply::new);
        this.dispatcher = dispatcher;
    }

    @Override
    protected void onCreated(Reply reply) {
        var spec = reply.getSpec();
        if (spec == null) {
            return;
        }
        var owner = spec.getOwner();
        dispatcher.submit(new SiteEvent.ReplyCreated(
                reply.getMetadata().getName(),
                spec.getCommentName(),
                owner == null ? null : owner.getKind(),
                owner == null ? null : owner.getName(),
                owner == null ? null : owner.getDisplayName(),
                spec.getContent(),
                spec.getQuoteReply(),
                displayTime(spec.getCreationTime(), reply.getMetadata())));
    }
}
