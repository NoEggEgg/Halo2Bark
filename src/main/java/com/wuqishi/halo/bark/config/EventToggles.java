package com.wuqishi.halo.bark.config;

/**
 * 「推送事件」分组的不可变快照，对应配置页 {@code group: events} 的 10 个事件开关。
 *
 * <p>默认值：安全事件与互动事件默认开启，低频的普通类事件默认关闭（PRD 10.6.6 打扰控制）。
 *
 * <p>新增组件一律追加在末尾，保持既有 8 参构造签名——位置构造的调用方（含测试）不受影响；
 * 运行时由 {@code settingFetcher.fetch} 按字段名绑定，老配置缺字段得到 {@code null}，由
 * {@code flagOrTrue/False} 兜底为默认。
 *
 * @param newCommentEnabled 新评论
 * @param newReplyEnabled 新回复
 * @param commentPendingEnabled 评论待审核
 * @param replyPendingEnabled 回复待审核（Halo 的评论审核设置对回复同样生效）
 * @param newDeviceLoginEnabled 新设备登录（安全事件）
 * @param postPublishedEnabled 文章发布
 * @param userRegisteredEnabled 新用户注册
 * @param postRemovedEnabled 文章删除 / 取消发布
 * @param userLoggedInEnabled 账号登录（安全事件；已存在设备再次登录）
 * @param postVisibilityEnabled 文章可见性变更
 */
public record EventToggles(
        Boolean newCommentEnabled,
        Boolean newReplyEnabled,
        Boolean commentPendingEnabled,
        Boolean replyPendingEnabled,
        Boolean newDeviceLoginEnabled,
        Boolean postPublishedEnabled,
        Boolean userRegisteredEnabled,
        Boolean postRemovedEnabled,
        Boolean userLoggedInEnabled,
        Boolean postVisibilityEnabled) {

    public EventToggles {
        newCommentEnabled = ConfigValues.flagOrTrue(newCommentEnabled);
        newReplyEnabled = ConfigValues.flagOrTrue(newReplyEnabled);
        commentPendingEnabled = ConfigValues.flagOrTrue(commentPendingEnabled);
        replyPendingEnabled = ConfigValues.flagOrTrue(replyPendingEnabled);
        newDeviceLoginEnabled = ConfigValues.flagOrTrue(newDeviceLoginEnabled);
        postPublishedEnabled = ConfigValues.flagOrFalse(postPublishedEnabled);
        userRegisteredEnabled = ConfigValues.flagOrFalse(userRegisteredEnabled);
        postRemovedEnabled = ConfigValues.flagOrFalse(postRemovedEnabled);
        // 账号登录：新设备首登已由 newDeviceLogin 覆盖，本开关针对老设备再次登录，默认开
        userLoggedInEnabled = ConfigValues.flagOrTrue(userLoggedInEnabled);
        // 可见性变更：低频运营动作，默认关（打扰控制）
        postVisibilityEnabled = ConfigValues.flagOrFalse(postVisibilityEnabled);
    }

    /** 8 参便捷构造：老配置/测试用，新增两项走默认值。 */
    public EventToggles(Boolean newCommentEnabled, Boolean newReplyEnabled,
            Boolean commentPendingEnabled, Boolean replyPendingEnabled,
            Boolean newDeviceLoginEnabled, Boolean postPublishedEnabled,
            Boolean userRegisteredEnabled, Boolean postRemovedEnabled) {
        this(newCommentEnabled, newReplyEnabled, commentPendingEnabled, replyPendingEnabled,
                newDeviceLoginEnabled, postPublishedEnabled, userRegisteredEnabled,
                postRemovedEnabled, null, null);
    }

    /** 全默认值配置，用于配置组缺失或读取失败时的降级。 */
    public static EventToggles defaults() {
        return new EventToggles(null, null, null, null, null, null, null, null, null, null);
    }
}
