package com.wuqishi.halo.bark.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;

import com.wuqishi.halo.bark.queue.NotificationDispatcher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import run.halo.app.core.extension.content.Post;
import run.halo.app.event.post.PostDeletedEvent;
import run.halo.app.event.post.PostPublishedEvent;
import run.halo.app.extension.Metadata;

@DisplayName("文章事件采集：删除事件须随事件携带标题/链接")
class PostEventListenerTest {

    private final NotificationDispatcher dispatcher = mock(NotificationDispatcher.class);
    private final PostEventListener listener = new PostEventListener(dispatcher);

    @Test
    @DisplayName("删除事件：从携带的 Post 快照投影标题与链接")
    void deletedCarriesTitleAndUrl() {
        var post = new Post();
        var meta = new Metadata();
        meta.setName("p-1");
        post.setMetadata(meta);
        var spec = new Post.PostSpec();
        spec.setTitle("被删除的文章");
        post.setSpec(spec);
        var status = new Post.PostStatus();
        status.setPermalink("/archives/p-1");
        post.setStatus(status);

        listener.onDeleted(new PostDeletedEvent(this, post));

        var captured = capturedPostChanged();
        assertThat(captured.postName()).isEqualTo("p-1");
        assertThat(captured.change()).isEqualTo(SiteEvent.PostChange.DELETED);
        assertThat(captured.title()).isEqualTo("被删除的文章");
        assertThat(captured.url()).isEqualTo("/archives/p-1");
    }

    @Test
    @DisplayName("删除事件：Post 快照 spec 为空时不抛异常（防御性）")
    void deletedWithNullSpecIsSafe() {
        var post = new Post();
        var meta = new Metadata();
        meta.setName("p-x");
        post.setMetadata(meta);
        // spec 为 null，标题与链接应安全取空

        listener.onDeleted(new PostDeletedEvent(this, post));

        var captured = capturedPostChanged();
        assertThat(captured.postName()).isEqualTo("p-x");
        assertThat(captured.title()).isEmpty();
        assertThat(captured.url()).isEmpty();
    }

    @Test
    @DisplayName("发布事件：Halo 只给文章名，标题与链接留空待重查")
    void publishedLeavesFieldsBlank() {
        listener.onPublished(new PostPublishedEvent(this, "p-2"));

        var captured = capturedPostChanged();
        assertThat(captured.change()).isEqualTo(SiteEvent.PostChange.PUBLISHED);
        assertThat(captured.title()).isEmpty();
        assertThat(captured.url()).isEmpty();
    }

    private SiteEvent.PostChanged capturedPostChanged() {
        ArgumentCaptor<SiteEvent> captor = forClass(SiteEvent.class);
        verify(dispatcher).submit(captor.capture());
        var event = captor.getValue();
        assertThat(event).isInstanceOf(SiteEvent.PostChanged.class);
        return (SiteEvent.PostChanged) event;
    }
}
