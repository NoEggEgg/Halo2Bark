package com.wuqishi.halo.bark.pipeline;

import com.wuqishi.halo.bark.BarkConstants;
import com.wuqishi.halo.bark.Masking;
import com.wuqishi.halo.bark.content.CommentAuthorResolver;
import com.wuqishi.halo.bark.content.HtmlText;
import com.wuqishi.halo.bark.content.SubjectResolver;
import com.wuqishi.halo.bark.event.SiteEvent;
import com.wuqishi.halo.bark.push.NotificationCategory;
import com.wuqishi.halo.bark.push.PushNotification;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import run.halo.app.infra.ExternalLinkProcessor;

/**
 * 事件适配：把「事件 + 判定」适配为一条待推送通知（PRD 4.1 字段适配、5.5 内容获取、10.6.5 内容要求）。
 *
 * <p>取值规则：
 * <ul>
 *   <li><b>标题</b>简短并含 emoji，必要时附上**评论主体类型**（如「新评论 · 文章」）；</li>
 *   <li><b>副标题</b>承载来源（文章标题 / 评论主体标题），由 {@link SubjectResolver} 解析；</li>
 *   <li><b>正文</b>首行是「谁 · 何时」，回复再带一行「回复了 @某某」，其后是内容；内容经
 *       {@link HtmlText} 由 **HTML 转纯文本**（Halo 存储的评论正文是 HTML）；整体按配置上限优雅截断；</li>
 *   <li><b>逐条图标</b>取评论者头像（仅注册用户有），为空时由传输层回退全局图标；
 *       头像与配图一律经 {@code ExternalLinkProcessor} 绝对化（相对地址 → 可下载的外部地址）；</li>
 *   <li><b>配图</b>取评论内容中的首张图片，展开通知可见；</li>
 *   <li><b>链接</b>可点击直达，取不到时已由解析器降级为站点首页。</li>
 * </ul>
 *
 * <p>本类会触发数据查询（来源解析、作者解析），因此**只允许在投递线程调用**。
 */
@Slf4j
@Component
public class NotificationComposer {

    /** 截断后用于提示内容被省略的字符（PRD 10.6.5：截断必须优雅，不得截成半句）。 */
    private static final String ELLIPSIS = "…";

    /**
     * 事件感知的点击目标：站长收到不同事件后最想去的 Console 页面不同（PRD 5.5 的补充）。
     *
     * <p>路由均以 Halo v2.26.1 Console 源码为准（ui/console-src/modules 各模块的 module.ts）：
     * <ul>
     *   <li>评论待审核 → 评论列表并预选「待审核」筛选（CommentList 以 {@code approved} 查询参数
     *       驱动筛选，待审核取 {@code false}）；</li>
     *   <li>文章删除/取消发布 → 回收站（{@code /console/posts/deleted}，DeletedPosts）。
     *       被删文章的原链接已是死链，跳站点首页则丢失上下文，回收站是唯一还有该文章的地方。</li>
     * </ul>
     * 相对路径经 {@link ExternalLinkProcessor} 绝对化为完整站点地址后下发。
     */
    private static final String CONSOLE_PENDING_COMMENTS_URL = "/console/comments?approved=false";
    private static final String CONSOLE_DELETED_POSTS_URL = "/console/posts/deleted";

    /**
     * 逐条图标只接受 iOS 能原生渲染的位图：SVG / WebP / AVIF 在 iOS 的 {@code UIImage} 侧无法可靠解码
     * （SVG 全版本无原生解码器，WebP 需 iOS 14+、AVIF 至今支持有限），而 Bark 的 icon 正是由 iOS 原生
     * 渲染，这些格式会令图标整体失效。Halo 默认头像与部分主题头像是 SVG，若不加过滤会盖掉全局图标。
     */
    private static final java.util.Set<String> UNSUPPORTED_ICON_SUFFIX =
            java.util.Set.of(".svg", ".webp", ".avif");

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("M月d日 HH:mm").withZone(ZoneId.systemDefault());

    private final SubjectResolver subjectResolver;
    private final CommentAuthorResolver authorResolver;
    private final ExternalLinkProcessor linkProcessor;

    public NotificationComposer(SubjectResolver subjectResolver, CommentAuthorResolver authorResolver,
            ExternalLinkProcessor linkProcessor) {
        this.subjectResolver = subjectResolver;
        this.authorResolver = authorResolver;
        this.linkProcessor = linkProcessor;
    }

    /**
     * @param contentMaxLength 正文长度上限；非正数表示不截断
     */
    public PushNotification compose(SiteEvent event, Judgement judgement, int contentMaxLength) {
        var subject = subjectResolver.resolve(event);
        return switch (event) {
            case SiteEvent.CommentCreated e -> {
                // 待审核事件直达 Console 审核列表（预选待审核筛选）；普通新评论仍跳文章页看上下文
                var url = judgement == Judgement.PENDING_COMMENT
                        ? processLink(CONSOLE_PENDING_COMMENTS_URL)
                        : subject.url();
                yield new PushNotification(
                        withSubjectKind(judgement == Judgement.PENDING_COMMENT ? "🕓 评论待审核" : "💬 新评论",
                                subject.kindName()),
                        subject.title(),
                        truncate(interactionBody(e.ownerKind(), e.ownerName(), e.ownerDisplayName(),
                                e.content(), e.creationTime(), null), contentMaxLength),
                        url,
                        NotificationCategory.INTERACTIVE,
                        true,
                        icon(avatar(e.ownerKind(), e.ownerName())),
                        imageOf(e.content()));
            }
            case SiteEvent.ReplyCreated e -> new PushNotification(
                    withSubjectKind("↩️ 新回复", subject.kindName()),
                    subject.title(),
                    truncate(interactionBody(e.ownerKind(), e.ownerName(), e.ownerDisplayName(),
                            e.content(), e.creationTime(), e.quoteReplyName()), contentMaxLength),
                    subject.url(),
                    NotificationCategory.INTERACTIVE,
                    true,
                    icon(avatar(e.ownerKind(), e.ownerName())),
                    imageOf(e.content()));
            case SiteEvent.PostChanged e -> {
                // 删除/取消发布的文章原链接已是死链，点击直达回收站；发布事件仍跳文章页
                var url = e.change() == SiteEvent.PostChange.PUBLISHED
                        ? subject.url()
                        : processLink(CONSOLE_DELETED_POSTS_URL);
                yield new PushNotification(
                        e.change() == SiteEvent.PostChange.PUBLISHED ? "📝 文章已发布" : "🗑️ 文章已删除或取消发布",
                        subject.title(),
                        "",
                        url,
                        NotificationCategory.NORMAL,
                        true,
                        "",
                        subject.image());
            }
            case SiteEvent.UserCreated e -> new PushNotification(
                    "👤 新用户注册",
                    "",
                    truncate(userBody(e), contentMaxLength),
                    subject.url(),
                    NotificationCategory.NORMAL,
                    true,
                    icon(e.avatar()),
                    "");
            case SiteEvent.DeviceCreated e -> new PushNotification(
                    "🔐 新设备登录",
                    "",
                    truncate(deviceBody(e), contentMaxLength),
                    subject.url(),
                    NotificationCategory.SECURITY,
                    true);
        };
    }

    private String avatar(String ownerKind, String ownerName) {
        return authorResolver.avatarUrl(ownerKind, ownerName);
    }

    /**
     * 逐条图标：先绝对化（站内相对地址 → 外部地址，与来源链接同一机制），再过滤 iOS 可渲染的位图格式。
     *
     * <p>{@code ExternalLinkProcessor.processLink} 对站内相对地址转绝对、其余原样返回（幂等），
     * 因此评论/回复头像（{@link #avatar} 已绝对化）二次处理也安全。但 iOS 的 {@code UIImage} 无法可靠
     * 解码 SVG / WebP / AVIF，这些格式作为 Bark 的 icon 在手机上显示不出来，故直接放弃、回退全局图标。
     */
    private String icon(String raw) {
        var absolute = processLink(raw);
        if (absolute.isBlank() || !isRasterIcon(absolute)) {
            return "";
        }
        return absolute;
    }

    /** 评论/回复正文里的首图：必须经绝对化——它由手机侧 Bark 直接下载，相对路径不可用。 */
    private String imageOf(String html) {
        return processLink(HtmlText.firstImageUrl(html));
    }

    private static boolean isRasterIcon(String url) {
        // 先按查询串 / 片段拆分——形如 https://x/avatar.svg?token=1 的地址若不剥离，
        // endsWith(".svg") 会失败而放行，导致 iOS 无法解码的矢量图被当作图标发出、图标整体失效。
        var noQuery = url.split("[?#]", 2)[0];
        var lower = noQuery.toLowerCase();
        return UNSUPPORTED_ICON_SUFFIX.stream().noneMatch(lower::endsWith);
    }

    private String processLink(String link) {
        if (link == null || link.isBlank()) {
            return "";
        }
        try {
            return linkProcessor.processLink(link);
        } catch (RuntimeException e) {
            log.warn("{} 链接绝对化失败：{}", BarkConstants.LOG_PREFIX, Masking.sanitize(e.toString()));
            return "";
        }
    }

    /** 标题附主体类型，如「💬 新评论 · 文章」；无类型信息时保持原样。 */
    private static String withSubjectKind(String title, String kindName) {
        return kindName == null || kindName.isBlank() ? title : title + " · " + kindName;
    }

    /**
     * 评论 / 回复正文：首行「谁 · 何时」，回复再带一行「回复了 @某某」，其后为内容。
     *
     * <p>内容经 HTML 转纯文本——Halo 的 `Comment.spec.content` / `Reply.spec.content` 是 HTML。
     */
    private String interactionBody(String ownerKind, String ownerName, String displayName,
            String htmlContent, Instant creationTime, String quoteReplyName) {
        var lines = new ArrayList<String>();
        addIfPresent(lines, metaLine(displayName, ownerName, creationTime));
        if (quoteReplyName != null && !quoteReplyName.isBlank()) {
            var quoted = authorResolver.quotedReplyAuthorName(quoteReplyName);
            lines.add(quoted.isBlank() ? "回复了某条回复" : "回复了 @" + quoted);
        }
        addIfPresent(lines, HtmlText.toMarkdown(htmlContent));
        // 正文以 Markdown 下发，段落之间必须用空行分隔（Markdown 里单个换行不断行）
        return String.join("\n\n", lines);
    }

    private static String metaLine(String displayName, String name, Instant creationTime) {
        var who = displayName != null && !displayName.isBlank() ? displayName
                : (name == null ? "" : name);
        var when = creationTime == null ? "" : TIME_FORMAT.format(creationTime);
        if (who.isBlank()) {
            return when;
        }
        return when.isBlank() ? who : who + " · " + when;
    }

    private static String userBody(SiteEvent.UserCreated user) {
        var lines = new ArrayList<String>();
        addIfPresent(lines, user.displayName() == null || user.displayName().isBlank()
                ? "(未设置显示名)" : user.displayName());
        addIfPresent(lines, user.email());
        addIfPresent(lines, user.registeredAt() == null ? ""
                : "注册于 " + TIME_FORMAT.format(user.registeredAt()));
        return String.join("\n", lines);
    }

    private static String deviceBody(SiteEvent.DeviceCreated device) {
        var lines = new ArrayList<String>();
        addIfPresent(lines, "账号：" + nullToDash(device.principalName()));
        addIfPresent(lines, "IP：" + nullToDash(device.ipAddress()));
        addIfPresent(lines, joinPlatform(device.os(), device.browser()));
        addIfPresent(lines, device.lastAuthenticatedTime() == null ? ""
                : "时间：" + TIME_FORMAT.format(device.lastAuthenticatedTime()));
        return String.join("\n", lines);
    }

    private static void addIfPresent(List<String> lines, String value) {
        if (value != null && !value.isBlank()) {
            lines.add(value.strip());
        }
    }

    private static String joinPlatform(String os, String browser) {
        var left = os == null ? "" : os.trim();
        var right = browser == null ? "" : browser.trim();
        if (left.isBlank()) {
            return right;
        }
        return right.isBlank() ? left : left + " · " + right;
    }

    private static String nullToDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    /**
     * 优雅截断：超出上限时截到上限前一位并补省略号，并去掉截断处的尾随空白，
     * 避免出现「半句」或「半个词 + 省略号」的观感（PRD 10.6.5）。
     */
    static String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        if (maxLength <= 0) {
            return text;
        }
        // 以码点（而非 UTF-16 代码单元）计长：emoji 等补充字符占 2 个代码单元，
        // 按代码单元截断会落在代理对中间，产生不成对的代理项与乱码。
        var codePointCount = text.codePointCount(0, text.length());
        if (codePointCount <= maxLength) {
            return text;
        }
        // 预留 1 个码点给省略号
        var keepCodePoints = maxLength - 1;
        var cut = text.offsetByCodePoints(0, keepCodePoints);
        var kept = text.substring(0, cut).stripTrailing();
        return kept + ELLIPSIS;
    }
}
