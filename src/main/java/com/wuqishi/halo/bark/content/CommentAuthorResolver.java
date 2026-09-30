package com.wuqishi.halo.bark.content;

/**
 * 解析评论 / 回复相关的作者信息（评论者头像、被引用回复的作者）。
 *
 * <p>实现会执行数据查询，因此**只能在投递线程调用**。
 */
public interface CommentAuthorResolver {

    /**
     * 评论者头像地址（已绝对化）。
     *
     * <p>仅注册用户（{@code ownerKind == "User"}）可解析；邮箱评论者没有头像，返回空串，
     * 由调用方回退到全局图标（配置的「默认图标 URL」或站点 Logo）。
     *
     * @return 头像地址；无法解析时为空串
     */
    String avatarUrl(String ownerKind, String ownerName);

    /**
     * 被引用回复的作者显示名，用于「回复了 @某某」。
     *
     * @param quoteReplyName 被引用回复的对象名；为空表示直接回复评论而非引用某条回复
     * @return 显示名；无法解析时为空串
     */
    String quotedReplyAuthorName(String quoteReplyName);
}
