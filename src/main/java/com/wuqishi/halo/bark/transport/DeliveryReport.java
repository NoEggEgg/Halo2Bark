package com.wuqishi.halo.bark.transport;

import java.util.List;

/**
 * 一次投递的汇总结果：按设备逐条记录，保证单设备失败不影响其他设备（PRD 10.1）。
 */
public record DeliveryReport(List<DeviceResult> results) {

    public DeliveryReport {
        // 逐设备隔离是 PRD 10.1 的硬性要求：某个投递实现返回 null 不应令整份报告聚合崩溃。
        // 将 null 条目归一为一条「实现缺陷」失败结果，而不是像 List.copyOf 那样抛 NPE。
        results = results == null ? List.of()
                : results.stream()
                        .map(r -> r == null ? DeviceResult.failed(DeviceResult.UNNAMED_DEVICE,
                                DeliveryFailure.UNEXPECTED, "投递结果为空（实现缺陷）", 0, 0) : r)
                        .toList();
    }

    /** 空结果：没有任何可投递设备，未发出请求。 */
    public static DeliveryReport empty() {
        return new DeliveryReport(List.of());
    }

    /** 成功设备数。 */
    public long successCount() {
        return results.stream().filter(DeviceResult::success).count();
    }

    /** 失败设备数。 */
    public long failureCount() {
        return results.stream().filter(result -> !result.success()).count();
    }

    /** 是否全部成功；空结果视为成功（无设备即无需投递）。 */
    public boolean allSucceeded() {
        return results.stream().allMatch(DeviceResult::success);
    }

    /** 是否至少有一台设备成功。 */
    public boolean anySucceeded() {
        return results.stream().anyMatch(DeviceResult::success);
    }
}
