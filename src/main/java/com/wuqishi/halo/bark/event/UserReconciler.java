package com.wuqishi.halo.bark.event;

import com.wuqishi.halo.bark.queue.NotificationDispatcher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import run.halo.app.core.extension.User;
import run.halo.app.extension.ExtensionClient;

/**
 * 用户采集器，覆盖 PRD 4.1 的「新用户注册」事件。
 *
 * <p>Halo 未提供用户注册的共享事件，故通过监听 {@code User} 扩展对象的创建补齐（PRD 4.2）。
 * 「只处理创建 + 跳过历史对象」由 {@link CreateReconciler} 统一保证。
 */
@Slf4j
@Component
public class UserReconciler extends CreateReconciler<User> {

    private final NotificationDispatcher dispatcher;

    public UserReconciler(ExtensionClient client, NotificationDispatcher dispatcher) {
        super(client, User.class, User::new);
        this.dispatcher = dispatcher;
    }

    @Override
    protected void onCreated(User user) {
        var spec = user.getSpec();
        dispatcher.submit(new SiteEvent.UserCreated(
                user.getMetadata().getName(),
                spec == null ? null : spec.getDisplayName(),
                spec == null ? null : spec.getEmail(),
                spec == null ? null : spec.getAvatar(),
                spec == null ? null : spec.getRegisteredAt()));
    }
}
