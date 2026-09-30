package com.wuqishi.halo.bark.pipeline;

import com.wuqishi.halo.bark.BarkConstants;
import com.wuqishi.halo.bark.Masking;
import com.wuqishi.halo.bark.content.CommentReviewSetting;
import com.wuqishi.halo.bark.event.SiteEvent;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import run.halo.app.core.extension.User;
import run.halo.app.core.extension.content.Comment;
import run.halo.app.core.extension.content.Post;
import run.halo.app.core.extension.content.Reply;
import run.halo.app.core.extension.content.SinglePage;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.extension.Ref;

/**
 * 事件判定：把 {@link SiteEvent} 判定为一条通知语义，或判定为「不通知」（PRD 4.3 的判定规则）。
 *
 * <p>规则 4/5（跳过历史对象、只处理创建）已由采集层的
 * {@link com.wuqishi.halo.bark.event.CreateReconciler} 保证，不在此重复。本类实现规则 1/2/3。
 *
 * <p>本类运行在**投递线程**（允许阻塞），因此可以读取扩展对象：被评论主体、被回复评论与被引用回复
 * 的作者。若把这些读取放在采集层，会让默认单线程的 Reconciler worker 承担 I/O 而串行化。
 */
@Slf4j
@Component
public class EventJudge {

    private final ExtensionClient client;
    private final CommentReviewSetting reviewSetting;

    public EventJudge(ExtensionClient client, CommentReviewSetting reviewSetting) {
        this.client = client;
        this.reviewSetting = reviewSetting;
    }

    /** @return 空表示该事件不应产生通知 */
    public Optional<Judgement> judge(SiteEvent event) {
        return switch (event) {
            case SiteEvent.CommentCreated e -> judgeComment(e);
            case SiteEvent.ReplyCreated e -> judgeReply(e);
            case SiteEvent.PostChanged e -> Optional.of(e.change() == SiteEvent.PostChange.PUBLISHED
                    ? Judgement.POST_PUBLISHED : Judgement.POST_REMOVED);
            case SiteEvent.UserCreated e -> Optional.of(Judgement.USER_REGISTERED);
            case SiteEvent.DeviceCreated e -> Optional.of(Judgement.DEVICE_LOGIN);
        };
    }

    /** 规则 1：自己评论自己不通知；规则 3：新评论与评论待审核互斥。 */
    private Optional<Judgement> judgeComment(SiteEvent.CommentCreated comment) {
        var subjectAuthor = new Author("User", subjectOwner(comment.subjectRef()));
        if (isSameAuthor(comment.ownerKind(), comment.ownerName(), subjectAuthor)) {
            return Optional.empty();
        }
        if (reviewSetting.requireReviewForNew() && !comment.approved()) {
            return Optional.of(Judgement.PENDING_COMMENT);
        }
        return Optional.of(Judgement.NEW_COMMENT);
    }

    /**
     * 规则 2：回复自己不通知；与规则 1 同理，管理员（被评论主体作者）自己发起的回复也不通知。
     *
     * <p>两处判定缺一不可：只看「被回复对象」会把管理员回复访客当成新回复推送；只看「主体作者」
     * 又会让访客之间的互相回复漏推。
     */
    private Optional<Judgement> judgeReply(SiteEvent.ReplyCreated reply) {
        if (isSameAuthor(reply.ownerKind(), reply.ownerName(), replieeOf(reply))) {
            return Optional.empty();
        }
        if (isSameAuthor(reply.ownerKind(), reply.ownerName(),
                subjectAuthorOfComment(reply.commentName()))) {
            return Optional.empty();
        }
        return Optional.of(Judgement.NEW_REPLY);
    }

    /**
     * 被回复者的身份：**优先取被引用回复**（{@code Reply.spec.quoteReply}）的作者，其次取被回复评论的作者。
     *
     * <p>先后顺序是正确性的关键：楼中楼里「访客回复管理员的回复」，若只看所属评论，会拿根评论的
     * 作者（访客本人）与回复者比对，判成「自己回复自己」而漏推。
     *
     * <p>身份必须连同 {@code kind} 一起取出：评论者可能是用户账号（{@code name} 为用户名），也可能是
     * 访客邮箱（{@code name} 即邮箱）。只取 {@code name} 会把邮箱误当用户名去查用户表，导致
     * 「访客回复自己的评论」被误判为不同人。
     */
    private Author replieeOf(SiteEvent.ReplyCreated reply) {
        var quoted = reply.quoteReplyName();
        if (quoted != null && !quoted.isBlank()) {
            var quotedAuthor = replyAuthor(quoted);
            if (quotedAuthor != null) {
                return quotedAuthor;
            }
        }
        return commentAuthor(reply.commentName());
    }

    private Author replyAuthor(String replyName) {
        try {
            return client.fetch(Reply.class, replyName)
                    .map(Reply::getSpec)
                    .map(spec -> spec == null ? null : spec.getOwner())
                    .map(owner -> owner == null ? null : new Author(owner.getKind(), owner.getName()))
                    .orElse(null);
        } catch (RuntimeException e) {
            log.warn("{} 判定回复作者时读取被引用回复失败，按「不抑制」处理：{}", BarkConstants.LOG_PREFIX,
                    Masking.sanitize(e.toString()));
            return null;
        }
    }

    private Author commentAuthor(String commentName) {
        if (commentName == null) {
            return null;
        }
        try {
            return client.fetch(Comment.class, commentName)
                    .map(Comment::getSpec)
                    .map(spec -> spec == null ? null : spec.getOwner())
                    .map(owner -> owner == null ? null : new Author(owner.getKind(), owner.getName()))
                    .orElse(null);
        } catch (RuntimeException e) {
            log.warn("{} 判定回复作者时读取被回复评论失败，按「不抑制」处理：{}", BarkConstants.LOG_PREFIX,
                    Masking.sanitize(e.toString()));
            return null;
        }
    }

    /** 回复所属评论的**被评论主体**作者（用户名）；取不到时返回 {@code null} 表示不抑制。 */
    private Author subjectAuthorOfComment(String commentName) {
        if (commentName == null) {
            return null;
        }
        try {
            var ref = client.fetch(Comment.class, commentName)
                    .map(Comment::getSpec)
                    .map(spec -> spec == null ? null : spec.getSubjectRef())
                    .orElse(null);
            var owner = subjectOwner(ref);
            return owner == null ? null : new Author("User", owner);
        } catch (RuntimeException e) {
            log.warn("{} 判定回复作者时读取被评论主体失败，按「不抑制」处理：{}", BarkConstants.LOG_PREFIX,
                    Masking.sanitize(e.toString()));
            return null;
        }
    }

    private String subjectOwner(SiteEvent.SubjectRef ref) {
        if (ref == null || ref.kind() == null || ref.name() == null) {
            return null;
        }
        var haloRef = new Ref();
        haloRef.setGroup(ref.group());
        haloRef.setVersion(ref.version());
        haloRef.setKind(ref.kind());
        haloRef.setName(ref.name());
        return subjectOwner(haloRef);
    }

    /**
     * 被评论主体的作者（用户名）。
     *
     * <p>只有 {@code Post} / {@code SinglePage} 携带 {@code spec.owner}；第三方注册的评论主体
     * （如「瞬间」）无法判定，返回 {@code null} 表示**不抑制**通知——宁可多推一条，也不漏掉真实评论。
     */
    private String subjectOwner(Ref ref) {
        if (ref == null || ref.getName() == null || ref.getKind() == null) {
            return null;
        }
        try {
            return switch (ref.getKind()) {
                case "Post" -> client.fetch(Post.class, ref.getName())
                        .map(Post::getSpec).map(spec -> spec == null ? null : spec.getOwner())
                        .orElse(null);
                case "SinglePage" -> client.fetch(SinglePage.class, ref.getName())
                        .map(SinglePage::getSpec).map(spec -> spec == null ? null : spec.getOwner())
                        .orElse(null);
                default -> null;
            };
        } catch (RuntimeException e) {
            log.warn("{} 判定作者时读取被评论主体失败，按「不抑制」处理：{}", BarkConstants.LOG_PREFIX,
                    Masking.sanitize(e.toString()));
            return null;
        }
    }

    /**
     * 判断评论者 / 回复者与对方作者是否同一人。
     *
     * <p>身份可能是用户账号（{@code User}，{@code name} 为用户名）或访客邮箱（{@code Email}，
     * {@code name} 即邮箱）——PRD 4.3 规则 1 要求两者都支持。同名同类直接判等；同类不同名、
     * 以及跨身份（一方账号一方邮箱）时，取该用户账号的邮箱再比对。
     */
    private boolean isSameAuthor(String ownerKind, String ownerName, Author counterpart) {
        if (ownerKind == null || ownerName == null || counterpart == null
                || counterpart.kind() == null || counterpart.name() == null) {
            return false;
        }
        if (ownerKind.equalsIgnoreCase(counterpart.kind())) {
            // 同类：Email 的 name 是邮箱地址，按大小写不敏感比较；User 的 name 是用户名，大小写敏感
            return isEmailKind(ownerKind)
                    ? ownerName.equalsIgnoreCase(counterpart.name())
                    : ownerName.equals(counterpart.name());
        }
        // 跨身份：一方 Email 一方 User，取用户账号邮箱再比对（邮箱大小写不敏感）
        if ("Email".equalsIgnoreCase(ownerKind) && "User".equalsIgnoreCase(counterpart.kind())) {
            return userEmail(counterpart.name()).filter(ownerName::equalsIgnoreCase).isPresent();
        }
        if ("User".equalsIgnoreCase(ownerKind) && "Email".equalsIgnoreCase(counterpart.kind())) {
            return userEmail(ownerName).filter(counterpart.name()::equalsIgnoreCase).isPresent();
        }
        return false;
    }

    private static boolean isEmailKind(String kind) {
        return "Email".equalsIgnoreCase(kind);
    }

    /** 取用户账号的邮箱；用户不存在、未设置邮箱或读取失败时为空（不抑制判定）。 */
    private Optional<String> userEmail(String username) {
        try {
            return client.fetch(User.class, username)
                    .map(User::getSpec)
                    .map(spec -> spec == null ? null : spec.getEmail());
        } catch (RuntimeException e) {
            log.warn("{} 判定作者时读取用户邮箱失败，按「不抑制」处理：{}", BarkConstants.LOG_PREFIX,
                    Masking.sanitize(e.toString()));
            return Optional.empty();
        }
    }

    /** 一方作者身份：{@code kind} 为 {@code User}（用户账号）或 {@code Email}（访客邮箱）。 */
    private record Author(String kind, String name) {
    }
}
