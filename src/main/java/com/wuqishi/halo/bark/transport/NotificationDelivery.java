package com.wuqishi.halo.bark.transport;

import com.wuqishi.halo.bark.config.DeviceSetting;
import com.wuqishi.halo.bark.config.PushSettings;
import com.wuqishi.halo.bark.push.PushNotification;
import java.util.List;

/**
 * 「把一条通知投递到一组设备」的抽象，即队列层与 Bark 传输层之间的跨层契约（PRD 10.4.1）。
 *
 * <p>定义在传输层：上层（队列层）依赖本接口，下层实现本接口，依赖方向仍为单向。
 * 队列层借此可在**不发起任何 HTTP 请求**的前提下验证调度、优先级与优雅关闭逻辑
 * （PRD 10.4.5：不依赖真实实例的测试优先）。
 *
 * <p>{@link BarkTransport} 是生产实现；其 {@code send} 全阻塞，只允许在投递线程调用。
 */
@FunctionalInterface
public interface NotificationDelivery {

    /**
     * 向候选设备投递一条通知。
     *
     * <p>实现不得向调用方抛出异常——异常应就地转为失败结果，避免影响事件总线（PRD 10.4.3）。
     *
     * @param notification 待推送通知
     * @param settings 全局推送配置快照（不可变）
     * @param devices 候选设备；实现内部会过滤掉不可投递的设备
     * @return 逐设备投递结果
     */
    DeliveryReport send(PushNotification notification, PushSettings settings, List<DeviceSetting> devices);
}
