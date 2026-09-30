package com.wuqishi.halo.bark.event;

import com.wuqishi.halo.bark.BarkConstants;
import com.wuqishi.halo.bark.Masking;
import com.wuqishi.halo.bark.queue.NotificationDispatcher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import run.halo.app.event.post.PostDeletedEvent;
import run.halo.app.event.post.PostPublishedEvent;
import run.halo.app.event.post.PostUnpublishedEvent;

/**
 * 文章类事件采集器，覆盖 PRD 4.1 的「文章发布」与「文章删除 / 取消发布」。
 *
 * <p>这三个事件类位于 Halo api 模块并标注了 {@code @SharedEvent}，会被广播到插件上下文，
 * 因此可以编译期直接引用并监听（评论与回复的事件类则不可）。
 *
 * <p>监听方法运行在**事件发布线程（Reactor）**，不得执行任何阻塞调用：这里只把文章名投影为
 * {@link SiteEvent} 并交给调度器，文章标题与链接的获取留给投递线程（PRD 10.4.2）。
 *
 * <p>异常就地消化，绝不抛给事件总线（PRD 10.4.3）。
 */
@Slf4j
@Component
public class PostEventListener {

    private final NotificationDispatcher dispatcher;

    public PostEventListener(NotificationDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @EventListener
    public void onPublished(PostPublishedEvent event) {
        dispatch(event.getName(), SiteEvent.PostChange.PUBLISHED);
    }

    @EventListener
    public void onDeleted(PostDeletedEvent event) {
        // 删除事件：文章可能已从存储移除，必须在采集时从事件携带的 Post 快照提取标题与链接，
        // 不依赖投递线程重查（纯内存读取，不触发阻塞 I/O）。
        // Halo 的 PostDeletedEvent 构造时即要求 Post 非 null（构造函数会取 post.getMetadata().getName()），
        // 故 getPost() 恒非 null；此处仅防御性地判空 spec/status。
        var post = event.getPost();
        var title = post.getSpec() == null ? "" : post.getSpec().getTitle();
        var status = post.getStatusOrDefault();
        var permalink = status == null ? null : status.getPermalink();
        var url = permalink == null ? "" : permalink;
        dispatch(event.getName(), SiteEvent.PostChange.DELETED, title, url);
    }

    @EventListener
    public void onUnpublished(PostUnpublishedEvent event) {
        dispatch(event.getName(), SiteEvent.PostChange.UNPUBLISHED);
    }

    private void dispatch(String postName, SiteEvent.PostChange change) {
        dispatch(postName, change, "", "");
    }

    private void dispatch(String postName, SiteEvent.PostChange change, String title, String url) {
        try {
            dispatcher.submit(new SiteEvent.PostChanged(postName, change, title, url));
        } catch (RuntimeException e) {
            log.warn("{} 采集文章事件失败：{}", BarkConstants.LOG_PREFIX,
                    Masking.sanitize(e.toString()));
        }
    }
}
