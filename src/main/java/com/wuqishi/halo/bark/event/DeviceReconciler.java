package com.wuqishi.halo.bark.event;

import com.wuqishi.halo.bark.queue.NotificationDispatcher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import run.halo.app.core.extension.Device;
import run.halo.app.extension.ExtensionClient;

/**
 * 设备采集器，覆盖 PRD 4.1 的「新设备登录」事件（安全类）。
 *
 * <p>Halo 未提供设备登录的共享事件，故通过监听 {@code Device} 扩展对象的创建补齐（PRD 4.2）。
 * 「只处理创建 + 跳过历史对象」由 {@link CreateReconciler} 统一保证——否则启用插件的瞬间会涌入
 * 全部历史设备记录。
 */
@Slf4j
@Component
public class DeviceReconciler extends CreateReconciler<Device> {

    private final NotificationDispatcher dispatcher;

    public DeviceReconciler(ExtensionClient client, NotificationDispatcher dispatcher) {
        super(client, Device.class, Device::new);
        this.dispatcher = dispatcher;
    }

    @Override
    protected void onCreated(Device device) {
        var spec = device.getSpec();
        var status = device.getStatus();
        dispatcher.submit(new SiteEvent.DeviceCreated(
                device.getMetadata().getName(),
                spec == null ? null : spec.getPrincipalName(),
                spec == null ? null : spec.getIpAddress(),
                status == null ? null : status.getOs(),
                status == null ? null : status.getBrowser(),
                spec == null ? null : spec.getUserAgent(),
                spec == null ? null : spec.getLastAuthenticatedTime()));
    }
}
