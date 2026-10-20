package com.wuqishi.halo.bark.content;

import com.wuqishi.halo.bark.BarkConstants;
import com.wuqishi.halo.bark.Masking;
import com.wuqishi.halo.bark.event.SiteEvent;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import run.halo.app.content.comment.CommentSubject;
import run.halo.app.core.extension.content.Comment;
import run.halo.app.core.extension.content.Post;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.extension.Ref;
import run.halo.app.infra.ExternalLinkProcessor;
import run.halo.app.plugin.extensionpoint.ExtensionGetter;

/**
 * 基于 Halo 提供机制的来源解析（PRD 4.5 / 5.5）。
 *
 * <ul>
 *   <li>文章 / 单页：链接取 {@code post.getStatusOrDefault().getPermalink()}，标题取 {@code spec.title}。</li>
 *   <li>评论 / 回复：由 {@code subjectRef} 找到 {@code supports(ref)} 的 {@link CommentSubject} 扩展，
 *       取其 {@code getSubjectDisplay(name)} 的 {@code title} / {@code url}。第三方注册的评论主体
 *       （如「瞬间」）只要实现了该扩展点即自动支持，无需本插件适配。</li>
 *   <li>评论 / 回复再附加 Halo 2.27 的统一固定链接 fragment（{@code #halo-comment=...}），
 *       点击通知直达该条讨论，见 {@link #commentPermalink}。</li>
 *   <li>所有链接一律经 {@link ExternalLinkProcessor#processLink(String)} 绝对化。</li>
 *   <li><b>兜底</b>：取不到链接时降级为站点首页（{@code processLink("/")}）。</li>
 * </ul>
 *
 * <p>本类执行数据查询与扩展点调用，**只允许在投递线程调用**。
 */
@Slf4j
@Component
public class HaloSubjectResolver implements SubjectResolver {

    /** 等待 CommentSubject 返回来源信息的超时；第三方实现可能异常，超时即降级。 */
    private static final Duration SUBJECT_TIMEOUT = Duration.ofSeconds(2);

    /**
     * CommentSubject 扩展列表的缓存时长。
     *
     * <p>该列表只在插件启停时变化，逐条事件重新查询纯属浪费；短时缓存兼顾「新装评论主体插件后
     * 尽快可见」。
     */
    private static final Duration SUBJECT_LIST_TTL = Duration.ofSeconds(30);

    /** 用户 / 设备类事件没有业务主体，跳转 Console 的用户管理页（PRD 5.5）。 */
    private static final String USER_CONSOLE_PATH = "/console/users";

    /**
     * Halo 2.27 评论 / 回复统一固定链接的 fragment 前缀（PR #10318）。
     *
     * <p>完整格式：{@code #halo-comment=<commentName>} 定位评论，
     * {@code #halo-comment=<commentName>&reply=<replyName>} 定位楼中楼里的某条回复。
     * 与 Halo 服务端 {@code CommentPermalinkService.getPermalink} 逐字一致——插件与服务端
     * 生成同一链接，Console 与手机通知跳过去才是同一个地方。
     */
    private static final String COMMENT_FRAGMENT_PREFIX = "halo-comment=";

    private final ExtensionClient client;
    private final ExtensionGetter extensionGetter;
    private final ExternalLinkProcessor linkProcessor;
    private final AtomicReference<CachedSubjects> subjectCache = new AtomicReference<>();

    public HaloSubjectResolver(ExtensionClient client, ExtensionGetter extensionGetter,
            ExternalLinkProcessor linkProcessor) {
        this.client = client;
        this.extensionGetter = extensionGetter;
        this.linkProcessor = linkProcessor;
    }

    @Override
    public ResolvedSubject resolve(SiteEvent event) {
        var raw = switch (event) {
            case SiteEvent.PostChanged e -> fromPost(e);
            case SiteEvent.CommentCreated e ->
                anchorToComment(fromSubjectRef(e.subjectRef()), e.commentName(), null);
            case SiteEvent.ReplyCreated e ->
                anchorToComment(fromParentComment(e.commentName()), e.commentName(), e.replyName());
            case SiteEvent.UserCreated e -> new ResolvedSubject("", USER_CONSOLE_PATH);
            case SiteEvent.DeviceCreated e -> new ResolvedSubject("", USER_CONSOLE_PATH);
        };
        return absolute(raw);
    }

    /**
     * 为评论 / 回复的来源解析结果附加 2.27 统一固定链接 fragment。
     *
     * <p>主体链接为空（解析失败，将降级为站点首页）或 URL 非法时保持原样——此时加 fragment
     * 没有意义。主题评论组件未实现 fragment 解析时（2.27 之前的主题），链接退化为普通
     * 文章页链接，点击可达性不受影响。
     */
    private ResolvedSubject anchorToComment(ResolvedSubject subject, String commentName,
            String replyName) {
        if (subject.url().isBlank()) {
            return subject;
        }
        var permalink = commentPermalink(subject.url(), commentName, replyName);
        return permalink == null ? subject
                : new ResolvedSubject(subject.title(), permalink, subject.kindName(),
                        subject.image());
    }

    /**
     * 构造评论 / 回复的统一固定链接，格式与 Halo 2.27 的
     * {@code CommentPermalinkService.getPermalink} 一致：
     * {@code <subjectUrl>#halo-comment=<commentName>[&reply=<replyName>]}。
     *
     * <p>拒绝条件与 Halo 逐条对齐（返回 {@code null} 表示保持原主体链接）：非 http(s) 协议、
     * opaque URI、以及既无协议又无 path 的地址。手工字符串拼接而非
     * {@code UriComponentsBuilder}：插件运行时类加载器不保证能见到 spring-web，且
     * {@link URLEncoder} 对对象名（{@code [a-z0-9-]}）不发生转义，与 Halo 侧
     * {@code build(true)} 的已编码语义等价。
     */
    static String commentPermalink(String subjectUrl, String commentName, String replyName) {
        if (subjectUrl == null || subjectUrl.isBlank()
                || commentName == null || commentName.isBlank()) {
            return null;
        }
        try {
            var uri = URI.create(subjectUrl);
            var scheme = uri.getScheme();
            if (uri.isOpaque()
                    || (scheme != null && !"http".equalsIgnoreCase(scheme)
                            && !"https".equalsIgnoreCase(scheme))
                    || (scheme == null && (uri.getRawPath() == null || uri.getRawPath().isBlank()))) {
                return null;
            }
            var fragment = COMMENT_FRAGMENT_PREFIX
                    + URLEncoder.encode(commentName, StandardCharsets.UTF_8);
            if (replyName != null && !replyName.isBlank()) {
                fragment += "&reply=" + URLEncoder.encode(replyName, StandardCharsets.UTF_8);
            }
            // 替换既有 fragment（Halo 的 fragment() 同为替换语义），而非叠加出两个 '#'
            var base = subjectUrl;
            var hash = base.indexOf('#');
            if (hash >= 0) {
                base = base.substring(0, hash);
            }
            return base + '#' + fragment;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private ResolvedSubject fromPost(SiteEvent.PostChanged e) {
        // 删除事件：文章已从存储移除，优先采用采集时随事件携带的标题/链接；两者皆空才走重查路径
        if (!e.title().isBlank() || !e.url().isBlank()) {
            return new ResolvedSubject(e.title(), e.url());
        }
        return fromPost(e.postName());
    }

    private ResolvedSubject fromPost(String postName) {
        if (postName == null) {
            return ResolvedSubject.EMPTY;
        }
        try {
            return client.fetch(Post.class, postName).map(post -> {
                var title = post.getSpec() == null ? "" : post.getSpec().getTitle();
                var cover = post.getSpec() == null ? "" : post.getSpec().getCover();
                var status = post.getStatusOrDefault();
                var permalink = status == null ? "" : status.getPermalink();
                return new ResolvedSubject(title, permalink, "", cover);
            }).orElse(ResolvedSubject.EMPTY);
        } catch (RuntimeException e) {
            log.warn("{} 解析文章来源失败：{}", BarkConstants.LOG_PREFIX,
                    Masking.sanitize(e.toString()));
            return ResolvedSubject.EMPTY;
        }
    }

    private ResolvedSubject fromParentComment(String commentName) {
        if (commentName == null) {
            return ResolvedSubject.EMPTY;
        }
        try {
            return client.fetch(Comment.class, commentName)
                    .map(comment -> comment.getSpec() == null ? null : comment.getSpec().getSubjectRef())
                    .map(this::fromHaloRef)
                    .orElse(ResolvedSubject.EMPTY);
        } catch (RuntimeException e) {
            log.warn("{} 解析回复所属评论失败：{}", BarkConstants.LOG_PREFIX,
                    Masking.sanitize(e.toString()));
            return ResolvedSubject.EMPTY;
        }
    }

    private ResolvedSubject fromSubjectRef(SiteEvent.SubjectRef ref) {
        if (ref == null) {
            return ResolvedSubject.EMPTY;
        }
        var haloRef = new Ref();
        haloRef.setGroup(ref.group());
        haloRef.setVersion(ref.version());
        haloRef.setKind(ref.kind());
        haloRef.setName(ref.name());
        return fromHaloRef(haloRef);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ResolvedSubject fromHaloRef(Ref ref) {
        if (ref == null || ref.getName() == null) {
            return ResolvedSubject.EMPTY;
        }
        try {
            for (CommentSubject subject : subjects()) {
                // 逐 provider 隔离异常：某一个 CommentSubject 的 supports() 或 getSubjectDisplay() 抛错
                // / 超时时，不应中断整轮循环，使后面仍可解析的主体被错误降级到首页。
                try {
                    if (!subject.supports(ref)) {
                        continue;
                    }
                    // CommentSubject 为泛型接口，此处以原始类型调用，block() 返回 Object，故显式转型
                    var display = (CommentSubject.SubjectDisplay) subject.getSubjectDisplay(ref.getName())
                            .block(SUBJECT_TIMEOUT);
                    if (display != null) {
                        return new ResolvedSubject(display.title(), display.url(), display.kindName());
                    }
                } catch (RuntimeException e) {
                    log.warn("{} 解析评论主体失败（扩展 {}），继续尝试下一个：{}", BarkConstants.LOG_PREFIX,
                            subject.getClass().getName(), Masking.sanitize(e.toString()));
                }
            }
        } catch (RuntimeException e) {
            log.warn("{} 获取 CommentSubject 扩展列表失败，链接将降级为站点首页：{}", BarkConstants.LOG_PREFIX,
                    Masking.sanitize(e.toString()));
        }
        return ResolvedSubject.EMPTY;
    }

    /**
     * CommentSubject 扩展列表；带短时缓存。
     *
     * <p>读取失败**不缓存**，下一次事件会立即重试——与「取不到就降级为站点首页」的既有语义一致，
     * 缓存失败结果反而会让一次瞬时故障把来源信息静默抹掉 {@link #SUBJECT_LIST_TTL}。
     */
    private List<CommentSubject> subjects() {
        var now = Instant.now();
        var cached = subjectCache.get();
        if (cached != null && now.isBefore(cached.expiresAt())) {
            return cached.subjects();
        }
        var list = List.copyOf(extensionGetter.getExtensionList(CommentSubject.class));
        subjectCache.set(new CachedSubjects(list, now.plus(SUBJECT_LIST_TTL)));
        return list;
    }

    /** CommentSubject 扩展列表的缓存条目。 */
    private record CachedSubjects(List<CommentSubject> subjects, Instant expiresAt) {
    }

    /**
     * 绝对化链接与配图；链接为空时降级为站点首页（PRD 4.5）。
     *
     * <p>配图（文章封面）同样必须绝对化——它由手机侧的 Bark 直接下载，相对路径不可用。
     */
    private ResolvedSubject absolute(ResolvedSubject subject) {
        var url = process(subject.url().isBlank() ? "/" : subject.url());
        var image = subject.image().isBlank() ? "" : process(subject.image());
        return new ResolvedSubject(subject.title(), url, subject.kindName(), image);
    }

    private String process(String link) {
        try {
            return linkProcessor.processLink(link);
        } catch (RuntimeException e) {
            log.warn("{} 链接绝对化失败：{}", BarkConstants.LOG_PREFIX, Masking.sanitize(e.toString()));
            return "";
        }
    }
}
