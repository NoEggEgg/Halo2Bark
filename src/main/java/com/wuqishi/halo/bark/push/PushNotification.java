package com.wuqishi.halo.bark.push;

/**
 * 跨层投递契约：上层产出的「一条待推送通知」。
 *
 * <p>本记录是事件适配层与 Bark 传输层之间唯一的中间数据结构（PRD 10.4.1）：
 * <ul>
 *   <li>不含任何事件模型，因此传输层无需感知业务语义。</li>
 *   <li>分组、声音、级别、归档等由传输层结合全局配置决定；仅 {@code icon} / {@code image}
 *       允许逐条覆盖（评论者头像、评论配图），为空时回退全局配置。</li>
 * </ul>
 *
 * <p>不可变，可安全跨线程传递（PRD 10.4.2）。
 *
 * @param title 通知标题，含 emoji 以一眼辨识事件类型
 * @param subtitle 副标题，承载来源信息（如文章标题）
 * @param body 正文，首行是最关键信息
 * @param url 点击直达链接；为空时由传输层省略该参数
 * @param category 推送行为分类，决定级别与重试策略
 * @param archive 是否归档；测试通知为 {@code false}（PRD 5.6：测试消息不归档）
 * @param icon 逐条图标（如评论者头像）；为空时回退全局「默认图标 URL」或站点 Logo。
 *     必须是 iOS 的 {@code UIImage} 能原生渲染的位图（PNG / JPEG / GIF）；
 *     SVG / WebP / AVIF 在 iOS 侧无法可靠解码，作为 Bark 图标会显示不出来，故由适配层放弃
 * @param image 展开通知时显示的大图（如评论中的配图）；为空时不下发
 * @param id Bark 的通知唯一标识（PRD 5.2：官方 docs/params.md）；相同 id 的新推送会在
 *     手机上**更新替换**原通知而非堆叠。为空串时不下发。用于「告警-恢复」原地替换与
 *     「爆发温度计」的进展更新。仅 V2 下发（传输层负责），加密推送时随密文整体加密
 */
public record PushNotification(
        String title,
        String subtitle,
        String body,
        String url,
        NotificationCategory category,
        boolean archive,
        String icon,
        String image,
        String id) {

    public PushNotification {
        title = title == null ? "" : title;
        subtitle = subtitle == null ? "" : subtitle;
        body = body == null ? "" : body;
        url = url == null ? "" : url;
        category = category == null ? NotificationCategory.NORMAL : category;
        icon = icon == null ? "" : icon;
        image = image == null ? "" : image;
        id = id == null ? "" : id;
    }

    /** 无逐条图标与配图的便捷构造（测试通知、不含头像/配图的事件）。 */
    public PushNotification(String title, String subtitle, String body, String url,
            NotificationCategory category, boolean archive) {
        this(title, subtitle, body, url, category, archive, "", "");
    }

    /** 带逐条图标与配图、无 id 的便捷构造（常规事件通知）。 */
    public PushNotification(String title, String subtitle, String body, String url,
            NotificationCategory category, boolean archive, String icon, String image) {
        this(title, subtitle, body, url, category, archive, icon, image, "");
    }

    /** 带 id 的便捷构造：显式携带通知唯一标识（告警-恢复、爆发温度计）。 */
    public PushNotification(String title, String subtitle, String body, String url,
            NotificationCategory category, boolean archive, String id) {
        this(title, subtitle, body, url, category, archive, "", "", id);
    }
}
