package com.wuqishi.halo.bark.content;

/**
 * 事件所属主体的「来源」信息。
 *
 * @param title 来源标题（文章标题 / 评论主体标题）；无来源时为空串
 * @param url 已绝对化的链接；取不到时降级为站点首页
 * @param kindName 主体类型名（文章 / 独立页面 / 第三方主体名），取自
 *     {@code CommentSubject.getSubjectDisplay()}；无来源时为空串
 * @param image 主体配图（文章封面）；无则空串，展开通知时可见
 */
public record ResolvedSubject(String title, String url, String kindName, String image) {

    /** 无来源信息。 */
    public static final ResolvedSubject EMPTY = new ResolvedSubject("", "", "", "");

    public ResolvedSubject {
        title = title == null ? "" : title;
        url = url == null ? "" : url;
        kindName = kindName == null ? "" : kindName;
        image = image == null ? "" : image;
    }

    /** 仅标题与链接（无主体类型、无配图）的便捷构造。 */
    public ResolvedSubject(String title, String url) {
        this(title, url, "", "");
    }

    /** 无配图的便捷构造。 */
    public ResolvedSubject(String title, String url, String kindName) {
        this(title, url, kindName, "");
    }
}
