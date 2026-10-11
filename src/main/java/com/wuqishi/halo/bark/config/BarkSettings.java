package com.wuqishi.halo.bark.config;

import java.util.List;

/**
 * 插件配置的不可变运行时快照。
 *
 * <p>快照在配置加载或热更新时整体替换，跨线程传递安全（PRD 10.4.2 要求跨线程对象不可变）。
 *
 * <p>{@code issues} / {@code warnings} 随快照一起传递，使**快照自带结论**：投递线程拿到快照时无需回头
 * 查询配置状态即可判断是否应当停止推送，避免「按新状态判断、却用旧快照的凭据」这类错配。
 *
 * <p>两级结论（PRD 5.7）：{@code issues} 是**阻断级**（只有加密不可用，无法安全投递）；
 * {@code warnings} 是**提示级**（服务地址笔误、长度/时间/音量非法等），运行时有确定降级路径，
 * 不停止推送。因此 {@link #valid()} 只看 {@code issues}。
 *
 * @param push 「推送配置」分组
 * @param events 「推送事件」分组
 * @param issues 阻断级问题；非空表示停止全部推送
 * @param warnings 提示级问题；不影响投递
 */
public record BarkSettings(PushSettings push, EventToggles events, List<String> issues,
        List<String> warnings) {

    public BarkSettings {
        push = push == null ? PushSettings.defaults() : push;
        events = events == null ? EventToggles.defaults() : events;
        issues = issues == null ? List.of() : List.copyOf(issues);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    /** 三参数便捷构造：未附带提示级结论。 */
    public BarkSettings(PushSettings push, EventToggles events, List<String> issues) {
        this(push, events, issues, List.of());
    }

    /** 两参数便捷构造：未附带校验结论，视为合法。 */
    public BarkSettings(PushSettings push, EventToggles events) {
        this(push, events, List.of(), List.of());
    }

    /** 该快照是否可安全用于投递。 */
    public boolean valid() {
        return issues.isEmpty();
    }

    /** 全默认值快照。 */
    public static BarkSettings defaults() {
        return new BarkSettings(null, null);
    }
}
