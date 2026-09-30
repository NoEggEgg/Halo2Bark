package com.wuqishi.halo.bark.pipeline;

/**
 * 事件经判定规则过滤后得到的**通知语义**，对应 PRD 4.1 事件清单的 7 项。
 *
 * <p>「丢弃」不在此枚举中——判定结果用 {@code Optional.empty()} 表达。
 */
public enum Judgement {

    /** 新评论 */
    NEW_COMMENT,

    /** 评论待审核 */
    PENDING_COMMENT,

    /** 新回复 */
    NEW_REPLY,

    /** 文章发布 */
    POST_PUBLISHED,

    /** 文章删除 / 取消发布 */
    POST_REMOVED,

    /** 新用户注册 */
    USER_REGISTERED,

    /** 新设备登录（安全类） */
    DEVICE_LOGIN
}
