package com.wuqishi.halo.bark.content;

import com.wuqishi.halo.bark.BarkConstants;
import com.wuqishi.halo.bark.Masking;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ExtensionClient;
import run.halo.app.infra.SystemSetting;

/**
 * 系统设置 {@code comment.requireReviewForNew} 的读取，是判定规则 3 的前提（PRD 4.3）。
 *
 * <p><b>内存缓存 + 失效重读</b>：首次读取后缓存，TTL 到期后重新读取；另提供 {@link #invalidate()}
 * 供需要时主动失效。这样每条评论事件不必都去查一次系统设置，同时又能感知站点开关的变化。
 *
 * <p>读取失败时回退为 {@code false}（等同「站点未开启评论审核」）——保守选择：宁可把评论当普通
 * 新评论推送，也不因读取失败而误报「待审核」。
 */
@Slf4j
@Component
public class CommentReviewSetting {

    private static final Duration TTL = Duration.ofMinutes(5);

    /**
     * 读取失败时的短时缓存：失败若沿用常规 TTL，一次瞬时的 ConfigMap 读取故障会把
     * 「待审核」判定长期误判为 false。短时缓存既避免失败瞬间疯狂重试，又尽快恢复读取。
     */
    private static final Duration FAILURE_TTL = Duration.ofSeconds(30);

    private final ExtensionClient client;
    private final Clock clock;
    private final AtomicReference<Cached> cache = new AtomicReference<>();

    @Autowired
    public CommentReviewSetting(ExtensionClient client) {
        this(client, Clock.systemUTC());
    }

    CommentReviewSetting(ExtensionClient client, Clock clock) {
        this.client = client;
        this.clock = clock;
    }

    /** 站点是否要求新评论先经审核。 */
    public boolean requireReviewForNew() {
        var now = clock.instant();
        var cached = cache.get();
        if (cached != null && now.isBefore(cached.expiresAt())) {
            return cached.value();
        }
        var result = readWithExpiry(now);
        cache.set(result);
        return result.value();
    }

    /** 主动失效，下次读取时重新获取。 */
    public void invalidate() {
        cache.set(null);
    }

    /** 读取并打 TTL：成功按 {@link #TTL} 缓存；失败只按 {@link #FAILURE_TTL} 短时缓存。 */
    private Cached readWithExpiry(Instant now) {
        try {
            return new Cached(read(), now.plus(TTL));
        } catch (RuntimeException e) {
            log.warn("{} 读取系统设置 comment.requireReviewForNew 失败，按未开启审核处理：{}",
                    BarkConstants.LOG_PREFIX, Masking.sanitize(e.toString()));
            return new Cached(false, now.plus(FAILURE_TTL));
        }
    }

    /** 读取系统设置；失败向上抛出，由 {@link #readWithExpiry(Instant)} 决定缓存策略。 */
    boolean read() {
        var configMap = client.fetch(ConfigMap.class, SystemSetting.SYSTEM_CONFIG).orElse(null);
        if (configMap == null || configMap.getData() == null) {
            return false;
        }
        var comment = SystemSetting.get(configMap.getData(), SystemSetting.Comment.GROUP,
                SystemSetting.Comment.class);
        return comment != null && Boolean.TRUE.equals(comment.getRequireReviewForNew());
    }

    private record Cached(boolean value, Instant expiresAt) {
    }
}
