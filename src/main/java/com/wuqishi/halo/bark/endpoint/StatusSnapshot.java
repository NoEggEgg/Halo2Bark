package com.wuqishi.halo.bark.endpoint;

import com.wuqishi.halo.bark.transport.BarkTransport;
import java.util.List;

/**
 * 插件运行状态快照（只读）：供状态端点与周期汇总日志读取。
 *
 * <p>存在必要性：丢弃与失败只体现在逐条日志里，管理员难以回答「最近有没有丢通知、丢了多少、
 * 哪台设备一直失败、配置是否处于阻断态」。把这几项汇总成一个可读快照，运维才有抓手。
 *
 * <p><b>不含任何凭据</b>：只有数量与开关状态；{@code blockingIssues} / {@code warnings} 是配置页
 * 已向管理员展示的可读文本，端点本身受控制台鉴权保护。
 *
 * @param deliverable 配置是否可用于投递（阻断级结论为空）
 * @param blockingIssues 阻断级问题；非空表示已停止全部推送
 * @param warnings 提示级问题；不影响投递
 * @param deviceCount 配置中的设备总数
 * @param deliverableDeviceCount 具备投递条件的设备数
 * @param dispatcher 投递调度器状态
 * @param noise 降噪层状态
 * @param failedEvents 因异常逃逸而丢失的事件累计条数
 * @param lastDelivery 最近一次投递摘要；尚未投递过时为 {@code null}
 */
public record StatusSnapshot(
        boolean deliverable,
        List<String> blockingIssues,
        List<String> warnings,
        int deviceCount,
        int deliverableDeviceCount,
        Dispatcher dispatcher,
        Noise noise,
        long failedEvents,
        BarkTransport.LastDelivery lastDelivery) {

    public StatusSnapshot {
        blockingIssues = blockingIssues == null ? List.of() : List.copyOf(blockingIssues);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    /**
     * 投递调度器状态。
     *
     * @param running 线程池是否在运行
     * @param queueDepth 当前排队任务数
     * @param dropped 因队列溢出而丢弃的累计条数
     */
    public record Dispatcher(boolean running, int queueDepth, long dropped) {
    }

    /**
     * 降噪层状态。
     *
     * @param running 降噪层是否在运行
     * @param overflowBuffered 当前限流溢出缓冲条目数
     * @param overflowDropped 限流溢出缓冲因达到上限而丢弃的累计条数
     * @param quietBuffered 当前免打扰缓冲条目数
     * @param quietDropped 免打扰缓冲因达到上限而丢弃的累计条数
     */
    public record Noise(boolean running, int overflowBuffered, long overflowDropped,
            int quietBuffered, long quietDropped) {
    }
}
