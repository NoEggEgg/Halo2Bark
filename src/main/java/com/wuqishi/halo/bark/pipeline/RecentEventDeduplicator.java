package com.wuqishi.halo.bark.pipeline;

import com.wuqishi.halo.bark.event.SiteEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 短窗口重复事件去重：流水线的第一道处理。
 *
 * <p><b>存在必要性</b>：Halo 的 {@code PostReconciler} 对**同一次删除**会发布**两次**
 * {@code PostDeletedEvent}——它把 {@code client.update(post)} 写在发布事件之前，该更新自身
 * 触发了第二次 reconcile，而 {@code PostDeletedEvent} 没有「只发一次」的守卫（已在 Halo 2.26.1
 * 上以受控实验 + 字节码确认）。插件无法从源头避免，只能按「事件类型 + 主体名」在窗口内去重。
 *
 * <p>线程安全：投递线程池有多个线程，故使用并发映射。
 */
@Component
public class RecentEventDeduplicator {

    /** 去重窗口：同一主体的同一事件在该时间内的重复投递被丢弃。 */
    static final Duration WINDOW = Duration.ofSeconds(2);

    /** 触发过期清理的条目上限，避免长期运行下映射无限增长。 */
    private static final int PURGE_THRESHOLD = 512;

    private final Map<String, Instant> seen = new ConcurrentHashMap<>();
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final Clock clock;

    public RecentEventDeduplicator() {
        this(Clock.systemUTC());
    }

    RecentEventDeduplicator(Clock clock) {
        this.clock = clock;
    }

    /**
     * @return {@code true} 表示该事件在窗口内已出现过，应当丢弃
     */
    public boolean isDuplicate(SiteEvent event) {
        var now = clock.instant();
        var key = keyOf(event);
        // 用 compute 把「读旧值 + 写新值」原子化，并以单调方式推进时间戳：并发下不回退到更早的
        // 时间戳，否则旧时间戳覆盖新时间戳会让本应判为重复的后续事件漏判。
        var box = new Instant[1];
        seen.compute(key, (k, v) -> {
            box[0] = v;
            return (v != null && v.isAfter(now)) ? v : now;
        });
        if (seen.size() > PURGE_THRESHOLD) {
            purgeExpired(now);
        }
        var previous = box[0];
        return previous != null && Duration.between(previous, now).compareTo(WINDOW) < 0;
    }

    /**
     * 仅判定是否为窗口内重复（不记录）。供「投递成功后再标记」的调用方使用：在判定 / 适配 / 投递
     * 之前先 peek，避免处理失败时把事件提前标记为已见、导致其窗口内重试副本被误丢。
     */
    public boolean isDuplicatePeek(SiteEvent event) {
        var now = clock.instant();
        var previous = seen.get(keyOf(event));
        return previous != null && Duration.between(previous, now).compareTo(WINDOW) < 0;
    }

    /**
     * 标记事件已处理（投递成功后调用），用于窗口去重。时间戳单调推进，不回退（同 {@link #isDuplicate}）。
     */
    public void markSeen(SiteEvent event) {
        var now = clock.instant();
        var key = keyOf(event);
        seen.compute(key, (k, v) -> (v != null && v.isAfter(now)) ? v : now);
        if (seen.size() > PURGE_THRESHOLD) {
            purgeExpired(now);
        }
    }

    /** Reserve a key before processing so concurrent workers cannot both pass the peek check.
     * A failed handoff must release the key without marking it seen, allowing a later retry.
     */
    public boolean tryClaim(SiteEvent event) {
        var key = keyOf(event);
        if (!inFlight.add(key)) {
            return false;
        }
        if (isDuplicatePeek(event)) {
            inFlight.remove(key);
            return false;
        }
        return true;
    }

    /** Called after the handoff has succeeded or failed, respectively. */
    public void release(SiteEvent event) {
        inFlight.remove(keyOf(event));
    }

    private void purgeExpired(Instant now) {
        seen.entrySet().removeIf(entry -> Duration.between(entry.getValue(), now).compareTo(WINDOW) >= 0);
    }

    /**
     * 去重键 = 事件类型 + 主体名。
     *
     * <p>文章类**不区分「删除」与「取消发布」**：Halo 删除一篇已发布文章时会先发
     * {@code PostUnpublishedEvent}、紧接着发 {@code PostDeletedEvent}，两者对用户是**同一个动作**，
     * 若分别建键会推送两条「文章已删除或取消发布」。因此把这两者归入同一个 {@code removed} 桶
     * （同一窗口内的第二次即被丢弃）。
     */
    private static String keyOf(SiteEvent event) {
        return switch (event) {
            case SiteEvent.CommentCreated e -> "comment:" + e.commentName();
            case SiteEvent.ReplyCreated e -> "reply:" + e.replyName();
            case SiteEvent.PostChanged e -> "post:" + e.postName() + ":" + postBucket(e.change());
            case SiteEvent.UserCreated e -> "user:" + e.username();
            case SiteEvent.DeviceCreated e -> "device:" + e.deviceName();
        };
    }

    private static String postBucket(SiteEvent.PostChange change) {
        return change == SiteEvent.PostChange.PUBLISHED ? "published" : "removed";
    }
}
