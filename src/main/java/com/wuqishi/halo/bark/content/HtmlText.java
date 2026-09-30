package com.wuqishi.halo.bark.content;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把 Halo 的评论正文（HTML）转换为适合通知展示的纯文本。
 *
 * <p>存在必要性：`Comment.spec.content` 是**渲染后的 HTML**（含 `<div>` / `<img>` / `<ol><li>` 等），
 * `spec.raw` 也可能含 HTML（用户可粘贴）。直接拼进通知正文会显示标签。
 *
 * <p>转换规则：
 * <ul>
 *   <li>块级结束标签（`br` / `p` / `div` / `li` / 标题 / 表格行 / 引用）转为换行；</li>
 *   <li>其余标签直接去除；</li>
 *   <li>解码常见实体（`&nbsp;` `&amp;` `&lt;` `&gt;` `&quot;` `&apos;` 及数字实体）；</li>
 *   <li>折叠连续空行并去除首尾空白。</li>
 * </ul>
 */
public final class HtmlText {

    /** 块级结束标签 → 换行。 */
    private static final Pattern BLOCK_BREAK = Pattern.compile(
            "(?i)<\\s*/?\\s*(br|p|div|li|h[1-6]|tr|blockquote|section|article)\\s*/?\\s*>");

    private static final Pattern TAG = Pattern.compile("<[^>]*>");
    private static final Pattern ENTITY = Pattern.compile("&(#x?[0-9a-fA-F]+|[a-zA-Z]+);");

    /**
     * 连续换行折叠为单个换行。
     *
     * <p>必要性：开闭块级标签各产生一个换行（`<div>a</div><div>b</div>` 会得到两个连续换行），
     * 若不折叠，通知正文会出现大量空行；而通知卡片空间有限，单换行已足够表达分段。
     */
    private static final Pattern EXTRA_NEWLINES = Pattern.compile("\\n{2,}");

    /** 提取首个 `<img src="...">` 的地址，供通知配图使用。 */
    private static final Pattern FIRST_IMAGE = Pattern.compile(
            "(?i)<img[^>]*\\ssrc\\s*=\\s*[\"']([^\"']+)[\"']");

    /**
     * 块级标签 → **空行**（Markdown 的段落分隔）。
     *
     * <p>必要性：Markdown 里**单个换行不会断行**（软换行会被合并成一段），必须用空行分段，
     * 否则元信息行与正文首行会并成一行。
     */
    private static final Pattern MARKDOWN_BLOCK_BREAK = Pattern.compile(
            "(?i)<\\s*/?\\s*(br|p|div|h[1-6]|tr|blockquote|section|article)\\s*/?\\s*>");

    /** Markdown 中把 3 个以上连续换行折叠为「空行」（两个换行）。 */
    private static final Pattern MARKDOWN_EXTRA_NEWLINES = Pattern.compile("\\n{3,}");

    public static String toPlainText(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        var text = BLOCK_BREAK.matcher(html).replaceAll("\n");
        text = TAG.matcher(text).replaceAll("");
        text = decodeEntities(text);
        text = EXTRA_NEWLINES.matcher(text).replaceAll("\n");
        return text.strip();
    }

    /**
     * 把 HTML 转为 Bark 可渲染的 **Markdown**（Bark 的 {@code markdown} 参数）。
     *
     * <p>比 {@link #toPlainText} 保留更多结构：加粗、斜体、删除线、行内代码、代码块、链接、列表、引用。
     * 图片**不进入正文**——首图已由 {@code image} 参数在展开通知时展示，正文里再写「图片」或长串
     * 图片地址都是噪音；带 {@code alt} 的图片（如表情）保留 {@code alt} 文字。
     *
     * <p>分段一律用**空行**——Markdown 里单个换行不断行；列表项之间保持单换行以形成同一个列表。
     */
    public static String toMarkdown(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        var text = html;
        text = text.replaceAll("(?is)<(b|strong)[^>]*>(.*?)</\\1>", "**$2**");
        text = text.replaceAll("(?is)<(i|em)[^>]*>(.*?)</\\1>", "*$2*");
        text = text.replaceAll("(?is)<(s|del|strike)[^>]*>(.*?)</\\1>", "~~$2~~");
        text = text.replaceAll("(?is)<code[^>]*>(.*?)</code>", "`$1`");
        text = text.replaceAll("(?is)<pre[^>]*>(.*?)</pre>", "\n\n```\n$1\n```\n\n");
        text = text.replaceAll("(?is)<a[^>]*href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>", "[$2]($1)");
        text = text.replaceAll("(?is)<img[^>]*\\salt\\s*=\\s*[\"']([^\"']*)[\"'][^>]*>", "$1");
        text = text.replaceAll("(?is)<img[^>]*>", "");
        text = text.replaceAll("(?is)<h[1-6][^>]*>(.*?)</h[1-6]>", "\n\n**$1**\n\n");
        text = text.replaceAll("(?is)<blockquote[^>]*>(.*?)</blockquote>", "\n\n> $1\n\n");
        // 列表项之间保持单换行（同一列表），列表整体前后空行
        text = text.replaceAll("(?is)<li[^>]*>(.*?)</li>", "- $1\n");
        text = text.replaceAll("(?is)</?(ul|ol)[^>]*>", "\n\n");
        text = MARKDOWN_BLOCK_BREAK.matcher(text).replaceAll("\n\n");
        text = TAG.matcher(text).replaceAll("");
        text = decodeEntities(text);
        text = MARKDOWN_EXTRA_NEWLINES.matcher(text).replaceAll("\n\n");
        return text.strip();
    }

    /**
     * @return 首个图片地址；无图片时返回空串
     */
    public static String firstImageUrl(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        var matcher = FIRST_IMAGE.matcher(html);
        return matcher.find() ? matcher.group(1).strip() : "";
    }

    private static String decodeEntities(String text) {
        var matcher = ENTITY.matcher(text);
        var builder = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(builder, Matcher.quoteReplacement(decode(matcher.group(1))));
        }
        matcher.appendTail(builder);
        return builder.toString();
    }

    private static String decode(String entity) {
        if (entity.startsWith("#")) {
            try {
                var code = entity.startsWith("#x") || entity.startsWith("#X")
                        ? Integer.parseInt(entity.substring(2), 16)
                        : Integer.parseInt(entity.substring(1));
                return new String(Character.toChars(code));
            } catch (RuntimeException e) {
                return "";
            }
        }
        return switch (entity) {
            case "nbsp" -> " ";
            case "amp" -> "&";
            case "lt" -> "<";
            case "gt" -> ">";
            case "quot" -> "\"";
            case "apos" -> "'";
            // 未识别的命名实体原样保留（如 &copy; &mdash; &hellip; &euro; &reg; &times;），
            // 而非静默丢弃，避免通知正文丢失字符。
            default -> "&" + entity + ";";
        };
    }

    private HtmlText() {
    }
}
