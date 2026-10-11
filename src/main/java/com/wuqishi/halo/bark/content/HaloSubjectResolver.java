package com.wuqishi.halo.bark.content;

import com.wuqishi.halo.bark.BarkConstants;
import com.wuqishi.halo.bark.Masking;
import com.wuqishi.halo.bark.event.SiteEvent;
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
            case SiteEvent.CommentCreated e -> fromSubjectRef(e.subjectRef());
            case SiteEvent.ReplyCreated e -> fromParentComment(e.commentName());
            case SiteEvent.UserCreated e -> new ResolvedSubject("", USER_CONSOLE_PATH);
            case SiteEvent.DeviceCreated e -> new ResolvedSubject("", USER_CONSOLE_PATH);
        };
        return absolute(raw);
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
