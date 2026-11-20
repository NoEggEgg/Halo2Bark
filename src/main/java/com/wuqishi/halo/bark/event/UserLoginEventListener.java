package com.wuqishi.halo.bark.event;

import com.wuqishi.halo.bark.BarkConstants;
import com.wuqishi.halo.bark.Masking;
import com.wuqishi.halo.bark.queue.NotificationDispatcher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import run.halo.app.core.extension.User;
import run.halo.app.event.user.UserLoginEvent;

/**
 * 账号登录采集器：覆盖 Halo 2.27 起广播的 {@code UserLoginEvent}（{@code @SharedEvent}）。
 *
 * <p>与 {@link DeviceReconciler} 的分工：Halo 只在**新设备**登录时创建 {@code Device} 扩展对象，
 * 已存在设备再次登录只更新对象、不触发 Reconciler。本事件补上「账号在已知设备上再次登录」
 * 的提醒——两者语义不同（新设备 vs 账号登录），同一次新设备登录收到两条是预期行为。
 *
 * <p>监听运行在事件发布线程（Reactor），只做无 I/O 的字段投影，异常就地消化。
 */
@Slf4j
@Component
public class UserLoginEventListener {

    private final NotificationDispatcher dispatcher;

    public UserLoginEventListener(NotificationDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @EventListener
    public void onLogin(UserLoginEvent event) {
        try {
            var user = event.getUser();
            if (user == null) {
                return;
            }
            var metadata = user.getMetadata();
            var spec = user.getSpec();
            dispatcher.submit(new SiteEvent.UserLoggedIn(
                    metadata == null ? "" : metadata.getName(),
                    spec == null ? "" : spec.getDisplayName()));
        } catch (RuntimeException e) {
            log.warn("{} 采集账号登录事件失败：{}", BarkConstants.LOG_PREFIX,
                    Masking.sanitize(e.toString()));
        }
    }
}
