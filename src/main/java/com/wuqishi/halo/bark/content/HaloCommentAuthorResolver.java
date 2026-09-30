package com.wuqishi.halo.bark.content;

import com.wuqishi.halo.bark.BarkConstants;
import com.wuqishi.halo.bark.Masking;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import run.halo.app.core.extension.User;
import run.halo.app.core.extension.content.Comment;
import run.halo.app.core.extension.content.Reply;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.infra.ExternalLinkProcessor;

/**
 * 基于 Halo 数据的作者信息解析。
 *
 * <ul>
 *   <li>头像：注册用户的 {@code User.spec.avatar}，经 {@link ExternalLinkProcessor} 绝对化；
 *       邮箱评论者无头像，返回空串（由调用方回退全局图标）。</li>
 *   <li>被引用回复的作者：取该 {@code Reply} 的 {@code spec.owner.displayName}，缺失时用 {@code owner.name}。</li>
 * </ul>
 *
 * <p>本类执行数据查询，**只允许在投递线程调用**。
 */
@Slf4j
@Component
public class HaloCommentAuthorResolver implements CommentAuthorResolver {

    private final ExtensionClient client;
    private final ExternalLinkProcessor linkProcessor;

    public HaloCommentAuthorResolver(ExtensionClient client, ExternalLinkProcessor linkProcessor) {
        this.client = client;
        this.linkProcessor = linkProcessor;
    }

    @Override
    public String avatarUrl(String ownerKind, String ownerName) {
        if (!"User".equalsIgnoreCase(ownerKind) || ownerName == null || ownerName.isBlank()) {
            return "";
        }
        try {
            return client.fetch(User.class, ownerName)
                    .map(User::getSpec)
                    .map(spec -> spec == null ? null : spec.getAvatar())
                    .filter(avatar -> avatar != null && !avatar.isBlank())
                    .map(linkProcessor::processLink)
                    .orElse("");
        } catch (RuntimeException e) {
            log.warn("{} 解析评论者头像失败，将使用全局图标：{}", BarkConstants.LOG_PREFIX,
                    Masking.sanitize(e.toString()));
            return "";
        }
    }

    @Override
    public String quotedReplyAuthorName(String quoteReplyName) {
        if (quoteReplyName == null || quoteReplyName.isBlank()) {
            return "";
        }
        try {
            return client.fetch(Reply.class, quoteReplyName)
                    .map(Reply::getSpec)
                    .map(spec -> spec == null ? null : spec.getOwner())
                    .map(HaloCommentAuthorResolver::displayNameOf)
                    .orElse("");
        } catch (RuntimeException e) {
            log.warn("{} 解析被引用回复的作者失败：{}", BarkConstants.LOG_PREFIX,
                    Masking.sanitize(e.toString()));
            return "";
        }
    }

    private static String displayNameOf(Comment.CommentOwner owner) {
        if (owner == null) {
            return "";
        }
        if (owner.getDisplayName() != null && !owner.getDisplayName().isBlank()) {
            return owner.getDisplayName();
        }
        return owner.getName() == null ? "" : owner.getName();
    }
}
