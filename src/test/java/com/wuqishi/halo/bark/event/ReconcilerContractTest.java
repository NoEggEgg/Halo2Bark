package com.wuqishi.halo.bark.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.wuqishi.halo.bark.queue.NotificationDispatcher;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Answers;
import run.halo.app.core.extension.Device;
import run.halo.app.core.extension.User;
import run.halo.app.core.extension.content.Comment;
import run.halo.app.core.extension.content.Reply;
import run.halo.app.extension.Extension;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.extension.ExtensionMatcher;
import run.halo.app.extension.controller.Controller;
import run.halo.app.extension.controller.ControllerBuilder;
import run.halo.app.extension.controller.Reconciler;

/**
 * 四个 Reconciler 的公共契约测试，覆盖 PRD 4.3 的规则 4 与规则 5。
 *
 * <p>这两条规则靠 {@link ControllerBuilder} 的配置表达，一旦被误改不会报错、只会在启用插件
 * 的瞬间涌入大量历史通知（规则 4）或让修改资料、刷新设备时间戳都触发推送（规则 5），
 * 因此必须以测试固定住契约。
 */
@DisplayName("Reconciler 契约：跳过历史对象 + 只处理创建（PRD 4.3 规则 4 / 5）")
class ReconcilerContractTest {

    private final ExtensionClient client = mock(ExtensionClient.class);
    private final NotificationDispatcher dispatcher = mock(NotificationDispatcher.class);

    private List<Reconciler<Reconciler.Request>> reconcilers() {
        return List.of(
                new CommentReconciler(client, dispatcher),
                new ReplyReconciler(client, dispatcher),
                new UserReconciler(client, dispatcher),
                new DeviceReconciler(client, dispatcher));
    }

    /** 可链式调用的 mock：链式方法返回自身，{@code build()} 返回一个假控制器。 */
    private static ControllerBuilder builder() {
        var builder = mock(ControllerBuilder.class, Answers.RETURNS_SELF);
        doReturn(mock(Controller.class)).when(builder).build();
        return builder;
    }

    @Test
    @DisplayName("规则4：启动时不对已存在对象全量同步（syncAllOnStart(false)）")
    void skipsHistoricalObjectsOnStart() {
        for (var reconciler : reconcilers()) {
            var builder = builder();

            reconciler.setupWith(builder);

            verify(builder).syncAllOnStart(false);
        }
    }

    @Test
    @DisplayName("规则5：更新与删除均不触发采集，且创建路径未被关闭")
    void onlyCreationTriggersCollection() {
        for (var reconciler : reconcilers()) {
            var builder = builder();
            reconciler.setupWith(builder);

            var update = ArgumentCaptor.forClass(ExtensionMatcher.class);
            var delete = ArgumentCaptor.forClass(ExtensionMatcher.class);
            verify(builder).onUpdateMatcher(update.capture());
            verify(builder).onDeleteMatcher(delete.capture());

            var extension = new Comment();
            assertThat(update.getValue().match(extension)).isFalse();
            assertThat(delete.getValue().match(extension)).isFalse();
            // 创建路径必须保持默认（放行），否则事件永远采集不到
            verify(builder, never()).onAddMatcher(any());
        }
    }

    @Test
    @DisplayName("各自声明自己的扩展类型，并返回可启动的控制器")
    void declaresOwnExtensionType() {
        assertDeclaresExtension(new CommentReconciler(client, dispatcher), Comment.class);
        assertDeclaresExtension(new ReplyReconciler(client, dispatcher), Reply.class);
        assertDeclaresExtension(new UserReconciler(client, dispatcher), User.class);
        assertDeclaresExtension(new DeviceReconciler(client, dispatcher), Device.class);
    }

    private static void assertDeclaresExtension(Reconciler<Reconciler.Request> reconciler,
            Class<? extends Extension> expected) {
        var builder = builder();

        assertThat(reconciler.setupWith(builder)).isNotNull();

        var captured = ArgumentCaptor.forClass(Extension.class);
        verify(builder).extension(captured.capture());
        assertThat(captured.getValue()).isInstanceOf(expected);
    }
}
