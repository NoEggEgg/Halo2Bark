package com.wuqishi.halo.bark.config;

import java.util.List;

/**
 * 插件配置的不可变运行时快照。
 *
 * <p>快照在配置加载或热更新时整体替换，跨线程传递安全（PRD 10.4.2 要求跨线程对象不可变）。
 *
 * <p>{@code issues} 随快照一起传递，使**快照自带合法性结论**：投递线程拿到快照时无需回头查询
 * 配置状态即可判断是否应当停止推送，避免「按新状态判断、却用旧快照的凭据」这类错配。
 *
 * @param push 「推送配置」分组
 * @param events 「推送事件」分组
 * @param issues 该快照的校验问题；为空表示配置合法
 */
public record BarkSettings(PushSettings push, EventToggles events, List<String> issues) {

    public BarkSettings {
        push = push == null ? PushSettings.defaults() : push;
        events = events == null ? EventToggles.defaults() : events;
        issues = issues == null ? List.of() : List.copyOf(issues);
    }

    /** 两参数便捷构造：未附带校验结论，视为合法。 */
    public BarkSettings(PushSettings push, EventToggles events) {
        this(push, events, List.of());
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
