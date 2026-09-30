package com.wuqishi.halo.bark.push;

/**
 * 通知的推送行为分类，对应 PRD 4.5 的分级与安全事件约束。
 *
 * <p>传输层据此决定三件事（PRD 5.2）：
 * <ul>
 *   <li>{@link #SECURITY}：级别固定 {@code critical}，不开放配置；重试 3 次；可带响铃与告警音量。</li>
 *   <li>{@link #INTERACTIVE}：级别取「互动类事件级别」配置；重试 2 次。</li>
 *   <li>{@link #NORMAL}：级别取「普通类事件级别」配置；重试 2 次。</li>
 * </ul>
 *
 * <p>本枚举只描述**推送行为**，不描述具体事件类型，因此传输层无需感知事件模型（PRD 10.4.1）。
 */
public enum NotificationCategory {

    /** 安全类：新设备登录。 */
    SECURITY,

    /** 互动类：新评论、新回复、评论待审核。 */
    INTERACTIVE,

    /** 普通类：文章发布、新用户注册、文章删除 / 取消发布。 */
    NORMAL
}
