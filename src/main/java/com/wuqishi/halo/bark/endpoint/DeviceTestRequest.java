package com.wuqishi.halo.bark.endpoint;

/**
 * 「测试该设备」端点的请求体。
 *
 * <p>对应 {@code verificationForm} 点击「测试该设备」时提交的**扁平父节点值**——
 * 即该设备项本身（{@code name} / {@code key} / {@code serverUrl} / {@code enabled}），
 * 不含 {@code verificationForm} 自身键（PRD 5.6、Halo form-schema 文档）。
 *
 * <p>这些值来自配置页表单，可能尚未保存——端点用它们做测试，但不写回配置。
 * 全局配置（如加密）取已保存的快照，因为只有设备值来自表单（见 {@link DeviceTester}）。
 */
public record DeviceTestRequest(String name, String key, String serverUrl, Boolean enabled) {
}
