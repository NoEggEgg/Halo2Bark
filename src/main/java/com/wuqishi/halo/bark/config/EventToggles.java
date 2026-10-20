package com.wuqishi.halo.bark.config;

/**
 * 「推送事件」分组的不可变快照，对应配置页 {@code group: events} 的 8 个事件开关。
 *
 * <p>默认值：安全事件与互动事件默认开启，低频的普通类事件默认关闭（PRD 10.6.6 打扰控制）。
 *
 * @param newCommentEnabled 新评论
 * @param newReplyEnabled 新回复
 * @param commentPendingEnabled 评论待审核
 * @param replyPendingEnabled 回复待审核（Halo 的评论审核设置对回复同样生效）
 * @param newDeviceLoginEnabled 新设备登录（安全事件）
 * @param postPublishedEnabled 文章发布
 * @param userRegisteredEnabled 新用户注册
 * @param postRemovedEnabled 文章删除 / 取消发布
 */
public record EventToggles(
        Boolean newCommentEnabled,
        Boolean newReplyEnabled,
        Boolean commentPendingEnabled,
        Boolean replyPendingEnabled,
        Boolean newDeviceLoginEnabled,
        Boolean postPublishedEnabled,
        Boolean userRegisteredEnabled,
        Boolean postRemovedEnabled) {

    public EventToggles {
        newCommentEnabled = ConfigValues.flagOrTrue(newCommentEnabled);
        newReplyEnabled = ConfigValues.flagOrTrue(newReplyEnabled);
        commentPendingEnabled = ConfigValues.flagOrTrue(commentPendingEnabled);
        replyPendingEnabled = ConfigValues.flagOrTrue(replyPendingEnabled);
        newDeviceLoginEnabled = ConfigValues.flagOrTrue(newDeviceLoginEnabled);
        postPublishedEnabled = ConfigValues.flagOrFalse(postPublishedEnabled);
        userRegisteredEnabled = ConfigValues.flagOrFalse(userRegisteredEnabled);
        postRemovedEnabled = ConfigValues.flagOrFalse(postRemovedEnabled);
    }

    /** 全默认值配置，用于配置组缺失或读取失败时的降级。 */
    public static EventToggles defaults() {
        return new EventToggles(null, null, null, null, null, null, null, null);
    }
}
