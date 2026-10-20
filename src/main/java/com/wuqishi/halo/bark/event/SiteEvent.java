package com.wuqishi.halo.bark.event;

import com.wuqishi.halo.bark.push.NotificationCategory;
import java.time.Instant;

/**
 * 站点事件的不可变描述：事件采集层与投递流水线之间**唯一**的跨层契约（PRD 10.4.1）。
 *
 * <p>由采集层（Spring 事件监听 / 扩展对象 Reconciler）产出。它只承载**事件对象已经带着的字段**，
 * 不含任何 Bark 概念，也**不直接持有 Halo 的可变扩展对象**——所有字段都投影为不可变类型，
 * 因此可安全跨线程传递（PRD 10.4.2）。
 *
 * <p>采集层不执行任何 I/O：链接获取、系统设置读取、字段补全与正文渲染均由投递线程上的
 * 流水线完成。事件开关过滤、判定规则同样在流水线中执行。
 *
 * <p>队列优先级取自 {@link #category()}（PRD 4.5）。
 */
public sealed interface SiteEvent {

    /** 队列优先级与投递策略分类。 */
    NotificationCategory category();

    /**
     * 评论主体引用（{@code Comment.spec.subjectRef} 的不可变投影）。
     *
     * @param group 主体所属 group
     * @param version 主体所属 version
     * @param kind 主体 kind（如 {@code Post} / {@code SinglePage} / 第三方主体）
     * @param name 主体名称
     */
    record SubjectRef(String group, String version, String kind, String name) {
    }

    /** 文章变更类型。 */
    enum PostChange {
        /** 发布 */
        PUBLISHED,
        /** 删除 */
        DELETED,
        /** 取消发布 */
        UNPUBLISHED
    }

    /**
     * 新评论。「新评论」与「评论待审核」的区分不在采集层完成——判定需读系统设置
     * {@code comment.requireReviewForNew}（I/O），因此随 {@code approved} 一并交给流水线。
     *
     * @param commentName 评论对象名
     * @param subjectRef 评论主体引用；可能为空
     * @param ownerKind 评论者类型（{@code User} / {@code Email}）
     * @param ownerName 评论者标识
     * @param ownerDisplayName 评论者显示名
     * @param content 评论正文（**Halo 存储的是 HTML**，展示前需转纯文本）
     * @param approved 是否已审核通过（{@code Comment.spec.approved}，默认 {@code false}）
     * @param creationTime 评论创建时间；可能为空
     * @param hidden 是否为私密评论（{@code Comment.spec.hidden}；Halo 2.27 起 Console 可将评论
     *     设为私密——公众不可见、仅管理员可见，推送时需标注，避免站长误当作公开评论处理）
     */
    record CommentCreated(String commentName, SubjectRef subjectRef, String ownerKind, String ownerName,
            String ownerDisplayName, String content, boolean approved, Instant creationTime,
            boolean hidden)
            implements SiteEvent {

        /** 便捷构造：非私密评论（{@code hidden=false}），覆盖绝大多数场景。 */
        public CommentCreated(String commentName, SubjectRef subjectRef, String ownerKind, String ownerName,
                String ownerDisplayName, String content, boolean approved, Instant creationTime) {
            this(commentName, subjectRef, ownerKind, ownerName, ownerDisplayName, content, approved,
                    creationTime, false);
        }

        @Override
        public NotificationCategory category() {
            return NotificationCategory.INTERACTIVE;
        }

        /**
         * **安全**：不输出正文——PRD 5.8 要求日志不得记录正文全文。
         *
         * <p>覆盖 {@code toString} 是「失败安全」的做法：任何地方直接打印事件对象都不会泄漏内容。
         */
        @Override
        public String toString() {
            // **安全**：不输出 ownerName——Email 类型评论者的 ownerName 即其邮箱（PII），
            // 该事件会被 EventPipeline 在 DEBUG 级别整体打印（PRD 5.8 禁止日志泄露 PII）。
            return "CommentCreated[commentName=%s, subjectRef=%s, ownerKind=%s, approved=%s]"
                    .formatted(commentName, subjectRef, ownerKind, approved);
        }
    }

    /**
     * 新回复。
     *
     * @param replyName 回复对象名
     * @param commentName 所属评论名，用于取被回复者与链接
     * @param ownerKind 回复者类型
     * @param ownerName 回复者标识
     * @param ownerDisplayName 回复者显示名
     * @param content 回复正文（HTML）
     * @param quoteReplyName 被引用的回复对象名；为空表示直接回复评论而非引用某条回复
     * @param creationTime 回复创建时间；可能为空
     * @param approved 是否已审核通过（{@code Reply.spec.approved}）。Halo 的评论审核设置
     *     {@code comment.requireReviewForNew} 对回复同样生效（见 {@code CommentFinderEndpoint}
     *     创建回复时 {@code setApproved(isFalse(requireReviewForNew))}），因此待审核判定与评论对称
     * @param hidden 是否为私密回复（{@code Reply.spec.hidden}；语义同 {@link CommentCreated}）
     */
    record ReplyCreated(String replyName, String commentName, String ownerKind, String ownerName,
            String ownerDisplayName, String content, String quoteReplyName, Instant creationTime,
            boolean approved, boolean hidden)
            implements SiteEvent {

        /** 便捷构造：已审核通过、非私密的回复（站点未开启评论审核时的常规形态）。 */
        public ReplyCreated(String replyName, String commentName, String ownerKind, String ownerName,
                String ownerDisplayName, String content, String quoteReplyName, Instant creationTime) {
            this(replyName, commentName, ownerKind, ownerName, ownerDisplayName, content,
                    quoteReplyName, creationTime, true, false);
        }

        @Override
        public NotificationCategory category() {
            return NotificationCategory.INTERACTIVE;
        }

        /** **安全**：不输出正文，也不输出 ownerName（同 {@link CommentCreated#toString()}）。 */
        @Override
        public String toString() {
            return "ReplyCreated[replyName=%s, commentName=%s, ownerKind=%s]"
                    .formatted(replyName, commentName, ownerKind);
        }
    }

    /**
     * 文章发布 / 删除 / 取消发布。
     *
     * <p>删除事件在文章已从存储移除后才进入投递线程，重查会取不到标题与链接；因此
     * {@link run.halo.app.event.post.PostDeletedEvent} 携带的原始 {@code Post} 快照会在采集时
     * 一并投影进 {@code title} / {@code url}，由来源解析器优先采用，避免「删除通知」标题为空。
     * 发布 / 取消发布事件 Halo 只给文章名，这两字段留空，仍走原有重查路径。
     *
     * @param postName 文章对象名
     * @param change 变更类型
     * @param title 文章标题（删除事件由采集层携带，其余为空）
     * @param url 文章链接（删除事件由采集层携带，其余为空）
     */
    record PostChanged(String postName, PostChange change, String title, String url)
            implements SiteEvent {

        /** 发布 / 取消发布：Halo 仅给文章名，标题与链接留空，交由投递线程重查。 */
        public PostChanged(String postName, PostChange change) {
            this(postName, change, "", "");
        }

        @Override
        public NotificationCategory category() {
            return NotificationCategory.NORMAL;
        }
    }

    /**
     * 新用户注册。
     *
     * @param username 用户对象名
     * @param displayName 显示名
     * @param email 邮箱
     * @param avatar 头像地址；可能为空
     * @param registeredAt 注册时间；可能为空
     */
    record UserCreated(String username, String displayName, String email, String avatar,
            Instant registeredAt) implements SiteEvent {

        @Override
        public NotificationCategory category() {
            return NotificationCategory.NORMAL;
        }

        /** **安全**：不输出邮箱（个人身份信息）。 */
        @Override
        public String toString() {
            return "UserCreated[username=%s, displayName=%s]".formatted(username, displayName);
        }
    }

    /**
     * 新设备登录（安全事件）。
     *
     * @param deviceName 设备对象名
     * @param principalName 登录账号
     * @param ipAddress 登录 IP
     * @param os 操作系统（取自 {@code Device.status.os}）
     * @param browser 浏览器（取自 {@code Device.status.browser}）
     * @param userAgent User-Agent
     * @param lastAuthenticatedTime 最近认证时间
     */
    record DeviceCreated(String deviceName, String principalName, String ipAddress, String os,
            String browser, String userAgent, Instant lastAuthenticatedTime) implements SiteEvent {

        @Override
        public NotificationCategory category() {
            return NotificationCategory.SECURITY;
        }

        /**
         * **安全**：登录账号、IP、User-Agent 均为敏感信息，绝不进入日志。
         * {@link #toString()} 只暴露非敏感的 deviceName / os / browser，与 {@link UserCreated}
         * 同样的脱敏约定——生成的 {@code toString} 会把账号 / IP / UA 写进「重复事件 / 已抑制」
         * 路径的日志。
         */
        @Override
        public String toString() {
            return "DeviceCreated[deviceName=%s, os=%s, browser=%s]".formatted(deviceName, os,
                    browser);
        }
    }
}
