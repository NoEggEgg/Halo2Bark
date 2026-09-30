package com.wuqishi.halo.bark.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import run.halo.app.core.extension.content.Comment;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.extension.controller.Reconciler;

/**
 * {@link CreateReconciler#reconcile} 的可靠性契约：
 *
 * <ul>
 *   <li>瞬时 {@code fetch} 失败必须 {@code requeue} 重试，不可静默丢弃事件。</li>
 *   <li>对象已不存在（读取竞态 / 已删除）直接结束，不重试、不通知。</li>
 *   <li>投影失败记错误日志后结束，不重试（避免打爆日志），但事件已被采集。</li>
 * </ul>
 */
@DisplayName("CreateReconciler：瞬时采集失败必须重试，不可静默丢弃")
class CreateReconcilerRetryTest {

    /** 最小可测子类：记录 {@code onCreated} 收到的对象。 */
    static final class Probe extends CreateReconciler<Comment> {
        final AtomicReference<Comment> captured = new AtomicReference<>();
        final AtomicReference<RuntimeException> projectionError = new AtomicReference<>();

        Probe(ExtensionClient client, boolean throwOnProject) {
            super(client, Comment.class, Comment::new);
            this.throwOnProject = throwOnProject;
        }

        private final boolean throwOnProject;

        @Override
        protected void onCreated(Comment extension) {
            if (throwOnProject) {
                throw projectionError.updateAndGet(p -> new IllegalStateException("projection boom"));
            }
            captured.set(extension);
        }
    }

    private final ExtensionClient client = mock(ExtensionClient.class);
    private final Comment sample = new Comment();

    @Test
    @DisplayName("fetch 抛出瞬时异常时返回 requeue，且事件未被吞掉")
    void requeuesOnTransientFetchFailure() {
        var probe = new Probe(client, false);
        doThrow(new IllegalStateException("store hiccup"))
                .doReturn(java.util.Optional.of(sample))
                .when(client).fetch(Comment.class, "c1");

        var first = probe.reconcile(new Reconciler.Request("c1"));
        assertThat(first.reEnqueue()).isTrue();
        assertThat(first.retryAfter()).isNotNull();
        assertThat(first.retryAfter().getSeconds()).isGreaterThan(0);
        // 第一次异常：onCreated 不应被调用
        assertThat(probe.captured.get()).isNull();

        // 第二次（fetch 成功）：正常采集
        var second = probe.reconcile(new Reconciler.Request("c1"));
        assertThat(second.reEnqueue()).isFalse();
        assertThat(probe.captured.get()).isSameAs(sample);
    }

    @Test
    @DisplayName("对象已被删除（fetch 为空）时直接 doNotRetry，不重试、不通知")
    void emptyFetchIsSkippedWithoutRetry() {
        var probe = new Probe(client, false);
        doReturn(java.util.Optional.empty()).when(client).fetch(Comment.class, "gone");

        var result = probe.reconcile(new Reconciler.Request("gone"));
        assertThat(result.reEnqueue()).isFalse();
        assertThat(probe.captured.get()).isNull();
    }

    @Test
    @DisplayName("投影失败时记录错误并结束，不重试、不无限循环")
    void projectionFailureIsLoggedNotRetried() {
        var probe = new Probe(client, true);
        doReturn(java.util.Optional.of(sample)).when(client).fetch(Comment.class, "c2");

        var result = probe.reconcile(new Reconciler.Request("c2"));
        assertThat(result.reEnqueue()).isFalse();
        assertThat(probe.captured.get()).isNull();
        assertThat(probe.projectionError.get()).isInstanceOf(IllegalStateException.class);
    }
}
