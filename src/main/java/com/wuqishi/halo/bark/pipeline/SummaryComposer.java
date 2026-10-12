package com.wuqishi.halo.bark.pipeline;

import com.wuqishi.halo.bark.push.PushNotification;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 降噪层三类摘要通知的合成（PRD 5.4）。
 *
 * <ul>
 *   <li><b>合并摘要</b>：同一事件类型窗口内累计达阈值后的合并，如 {@code 💬 3 条新评论}；</li>
 *   <li><b>限流摘要</b>：每分钟超出配额的条目，如 {@code 🔔 近 1 分钟另有 5 条通知已合并}；</li>
 *   <li><b>静默摘要</b>：免打扰期间暂存的条目，如 {@code 🌙 免打扰期间收到 5 条通知}。</li>
 * </ul>
 *
 * <p>纯函数，便于单测。
 */
final class SummaryComposer {

    /** 合并摘要标题模板（按判定语义），{@code %d} 为条数。 */
    private static final Map<Judgement, String> MERGE_TITLES = Map.of(
            Judgement.NEW_COMMENT, "💬 %d 条新评论",
            Judgement.PENDING_COMMENT, "🕓 %d 条评论待审核",
            Judgement.NEW_REPLY, "↩️ %d 条新回复",
            Judgement.POST_PUBLISHED, "📝 %d 篇文章已发布",
            Judgement.POST_REMOVED, "🗑️ %d 篇文章被删除或取消发布",
            Judgement.USER_REGISTERED, "👤 %d 位新用户注册",
            Judgement.DEVICE_LOGIN, "🔐 %d 次新设备登录");

    private static final String OVERFLOW_TITLE = "🔔 近 1 分钟另有 %d 条通知已合并";
    private static final String QUIET_TITLE = "🌙 免打扰期间收到 %d 条通知";

    /**
     * 合并摘要：副标题与链接取「所有条目一致」的值，否则留空。
     *
     * @param notificationId 通知唯一标识；非空时摘要携带该 id——若爆发进行中已推送过
     *     「温度计」通知（同一 id），摘要会在手机上**原地替换**它，而不是新增一条
     */
    static PushNotification merged(Judgement judgement, List<BufferedNotification> items,
            int contentMaxLength, String notificationId) {
        var first = items.get(0).notification();
        var title = MERGE_TITLES.getOrDefault(judgement, "🔔 %d 条通知").formatted(items.size());
        return new PushNotification(
                title,
                commonSubtitle(items),
                truncateBodies(items, contentMaxLength),
                commonUrl(items),
                first.category(),
                true,
                "",
                "",
                notificationId);
    }

    /** 限流摘要：正文按事件类型归纳（如「2 条新评论 · 1 篇文章已发布」）。 */
    static PushNotification overflow(List<BufferedNotification> items, int contentMaxLength) {
        var counts = new LinkedHashMap<Judgement, Integer>();
        for (var item : items) {
            counts.merge(item.judgement(), 1, Integer::sum);
        }
        var breakdown = counts.entrySet().stream()
                .map(entry -> MERGE_TITLES.getOrDefault(entry.getKey(), "%d 条通知")
                        .replace("%d", String.valueOf(entry.getValue())))
                .collect(Collectors.joining(" · "));
        return new PushNotification(
                OVERFLOW_TITLE.formatted(items.size()),
                "",
                NotificationComposer.truncate(breakdown, contentMaxLength),
                "",
                items.get(0).notification().category(),
                true);
    }

    /** 静默摘要：正文列出暂存各条要点。 */
    static PushNotification quiet(List<BufferedNotification> items, int contentMaxLength) {
        return new PushNotification(
                QUIET_TITLE.formatted(items.size()),
                commonSubtitle(items),
                truncateBodies(items, contentMaxLength),
                commonUrl(items),
                items.get(0).notification().category(),
                true);
    }

    private static String truncateBodies(List<BufferedNotification> items, int contentMaxLength) {
        var joined = items.stream()
                .map(item -> item.notification().body())
                .filter(body -> body != null && !body.isBlank())
                .collect(Collectors.joining("\n\n"));
        return NotificationComposer.truncate(joined, contentMaxLength);
    }

    private static String commonSubtitle(List<BufferedNotification> items) {
        return common(items, item -> item.notification().subtitle());
    }

    private static String commonUrl(List<BufferedNotification> items) {
        return common(items, item -> item.notification().url());
    }

    /** 所有条目的取值一致时返回该值（非空），否则返回空串——避免误导性的「来源」。 */
    private static String common(List<BufferedNotification> items,
            java.util.function.Function<BufferedNotification, String> extractor) {
        var value = extractor.apply(items.get(0));
        if (value == null || value.isBlank()) {
            return "";
        }
        for (var item : items) {
            if (!value.equals(extractor.apply(item))) {
                return "";
            }
        }
        return value;
    }

    private SummaryComposer() {
    }
}
