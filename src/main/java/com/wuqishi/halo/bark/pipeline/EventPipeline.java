package com.wuqishi.halo.bark.pipeline;

import com.wuqishi.halo.bark.BarkConstants;
import com.wuqishi.halo.bark.Masking;
import com.wuqishi.halo.bark.config.BarkSettings;
import com.wuqishi.halo.bark.config.EventToggles;
import com.wuqishi.halo.bark.event.SiteEvent;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 事件流水线：在**投递线程**上把一条 {@link SiteEvent} 加工为通知并发送。
 *
 * <p>处理顺序（PRD 6.1）：
 * <ol>
 *   <li>配置合法性——快照自带校验结论，非法则**停止投递**</li>
 *   <li>窗口去重——丢弃短窗口内的重复事件（Halo 对文章删除会重复发布，见
 *       {@link RecentEventDeduplicator}）</li>
 *   <li>判定规则——自己评论自己 / 回复自己不通知；新评论与评论待审核互斥（{@link EventJudge}）</li>
 *   <li>事件开关过滤——按判定语义查配置快照</li>
 *   <li>适配——组装通知（{@link NotificationComposer}）</li>
 *   <li>投递——交给 Bark 传输层</li>
 * </ol>
 *
 * <p>异常就地消化：单条事件失败不得影响投递线程，也不得抛回队列（PRD 10.4.3）。
 */
@Slf4j
@Component
public class EventPipeline implements NotificationPipeline {

    private final RecentEventDeduplicator deduplicator;
    private final EventJudge judge;
    private final NotificationComposer composer;
    private final NoiseReducer noiseReducer;

    /** 上次记录的「配置非法」问题集合签名，用于避免逐条事件重复打印同一条告警。 */
    private final AtomicReference<String> blockedSignature = new AtomicReference<>();

    /** 处理失败（异常逃逸）的累计条数：失败即丢事件，必须可观测。 */
    private final AtomicLong failedEvents = new AtomicLong();

    public EventPipeline(RecentEventDeduplicator deduplicator, EventJudge judge,
            NotificationComposer composer, NoiseReducer noiseReducer) {
        this.deduplicator = deduplicator;
        this.judge = judge;
        this.composer = composer;
        this.noiseReducer = noiseReducer;
    }

    @Override
    public void process(SiteEvent event, BarkSettings settings) {
        boolean claimed = false;
        try {
            // 配置非法：停止投递。必须早于去重与判定——非法配置下任何推送都不得发生，
            // 尤其不能退回上一份（可能是明文）配置继续发送。
            if (!settings.valid()) {
                reportBlocked(settings);
                return;
            }
            clearBlocked();
            // Claim atomically before work; only mark seen after a successful handoff.
            // An exception releases the claim so a later event can retry.
            if (!deduplicator.tryClaim(event)) {
                // Another worker owns this key, or it was recently handled.
                log.debug("{} 丢弃窗口内重复事件：{}", BarkConstants.LOG_PREFIX, event);
                return;
            }
            claimed = true;
            var judged = judge.judge(event);
            if (judged.isEmpty()) {
                log.debug("{} 判定为不通知：{}", BarkConstants.LOG_PREFIX, event);
                return;
            }
            var judgement = judged.get();
            if (!enabled(judgement, settings.events())) {
                log.debug("{} 事件开关已关闭，丢弃：{}", BarkConstants.LOG_PREFIX, judgement);
                return;
            }
            var push = settings.push();
            var notification = composer.compose(event, judgement, push.contentMaxLengthOrDefault());
            log.debug("{} 投递 {}：{} | 来源={} | 链接={}", BarkConstants.LOG_PREFIX, judgement,
                    notification.title(),
                    notification.subtitle().isBlank() ? "(无)" : notification.subtitle(),
                    notification.url().isBlank() ? "(无)" : notification.url());
            noiseReducer.accept(judgement, notification, push);
            // 仅在成功交给降噪层后标记，使失败事件可被窗口内重试副本重新处理
            deduplicator.markSeen(event);
        } catch (RuntimeException e) {
            // 异常逃逸意味着该条事件已丢失（Halo 不会重发同一事件），因此按 ERROR 记录并累计计数：
            // 只记 WARN 会让持续性缺陷长期无人发现。事件对象本身的 toString 已脱敏，此处只取类型名。
            long failed = failedEvents.incrementAndGet();
            log.error("{} 事件处理失败，本条通知已丢弃（事件类型 {}，累计失败 {} 条）：{}",
                    BarkConstants.LOG_PREFIX, event.getClass().getSimpleName(), failed,
                    Masking.sanitize(e.toString()));
        } finally {
            if (claimed) {
                deduplicator.release(event);
            }
        }
    }

    /**
     * 记录「配置非法 → 停止推送」。
     *
     * <p>事件可能高频到达，逐条打印会淹没日志，也会让真正需要处理的问题被滚动过去；
     * 因此只在问题集合发生变化时打印一次。不输出正文，且服务地址等原文经 {@link Masking} 脱敏
     * （PRD 5.8）。
     */
    private void reportBlocked(BarkSettings settings) {
        var signature = String.join("\n", settings.issues());
        if (!signature.equals(blockedSignature.getAndSet(signature))) {
            log.error("{} 配置校验未通过，已停止全部推送。共 {} 项，请到插件设置页修正：{}",
                    BarkConstants.LOG_PREFIX, settings.issues().size(),
                    Masking.sanitize(settings.issues().toString()));
        }
    }

    /** 配置恢复合法后清除阻断标记，使同类问题再次出现时仍会告警。 */
    private void clearBlocked() {
        if (blockedSignature.get() != null) {
            blockedSignature.set(null);
        }
    }

    /** 因异常逃逸而丢失的事件累计条数（供状态端点与测试读取）。 */
    public long failedEventCount() {
        return failedEvents.get();
    }

    /** 判定语义 → 事件开关。 */
    private static boolean enabled(Judgement judgement, EventToggles events) {
        return switch (judgement) {
            case NEW_COMMENT -> Boolean.TRUE.equals(events.newCommentEnabled());
            case PENDING_COMMENT -> Boolean.TRUE.equals(events.commentPendingEnabled());
            case NEW_REPLY -> Boolean.TRUE.equals(events.newReplyEnabled());
            case POST_PUBLISHED -> Boolean.TRUE.equals(events.postPublishedEnabled());
            case POST_REMOVED -> Boolean.TRUE.equals(events.postRemovedEnabled());
            case USER_REGISTERED -> Boolean.TRUE.equals(events.userRegisteredEnabled());
            case DEVICE_LOGIN -> Boolean.TRUE.equals(events.newDeviceLoginEnabled());
        };
    }
}
