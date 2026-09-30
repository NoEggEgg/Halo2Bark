package com.wuqishi.halo.bark.content;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("HTML 转纯文本：标签、块级换行、实体解码、图片提取")
class HtmlTextTest {

    @Test
    @DisplayName("去除行内标签")
    void stripsInlineTags() {
        assertThat(HtmlText.toPlainText("你好<b>世界</b>"))
                .isEqualTo("你好世界");
    }

    @Test
    @DisplayName("块级标签转为换行，连续换行折叠为单个")
    void blockTagsBecomeNewlines() {
        assertThat(HtmlText.toPlainText("第一段<div>第二段</div><div>第三段</div>"))
                .isEqualTo("第一段\n第二段\n第三段");
        assertThat(HtmlText.toPlainText("a<br><br><br>b")).isEqualTo("a\nb");
    }

    @Test
    @DisplayName("解码常见实体与数字实体")
    void decodesEntities() {
        assertThat(HtmlText.toPlainText("a&nbsp;b &amp; c &lt;d&gt; &#65;&#x42;"))
                .isEqualTo("a b & c <d> AB");
    }

    @Test
    @DisplayName("未知命名实体原样保留，不静默丢弃")
    void preservesUnknownEntitiesInPlainText() {
        assertThat(HtmlText.toPlainText("版权&copy; 与破折号&mdash; 及省略&hellip;"))
                .isEqualTo("版权&copy; 与破折号&mdash; 及省略&hellip;");
    }

    @Test
    @DisplayName("未知命名实体在 Markdown 路径同样保留")
    void preservesUnknownEntitiesInMarkdown() {
        assertThat(HtmlText.toMarkdown("注册即同意&reg; 条款&trade;"))
                .isEqualTo("注册即同意&reg; 条款&trade;");
    }

    @Test
    @DisplayName("图片标签不残留，但可从原文提取首个图片地址")
    void extractsFirstImage() {
        var html = "看图<img src=\"https://cdn/x.png\" alt=\"x\">还有<img src='https://cdn/y.png'>";

        assertThat(HtmlText.toPlainText(html)).doesNotContain("<img").doesNotContain("cdn");
        assertThat(HtmlText.firstImageUrl(html)).isEqualTo("https://cdn/x.png");
    }

    @Test
    @DisplayName("空输入与无图片时返回空串")
    void handlesEmptyInput() {
        assertThat(HtmlText.toPlainText(null)).isEmpty();
        assertThat(HtmlText.toPlainText("   ")).isEmpty();
        assertThat(HtmlText.firstImageUrl("没有图片")).isEmpty();
    }

    @Test
    @DisplayName("Markdown：保留加粗、斜体、删除线、行内代码、链接")
    void markdownKeepsFormatting() {
        assertThat(HtmlText.toMarkdown("a<b>粗</b>c")).isEqualTo("a**粗**c");
        assertThat(HtmlText.toMarkdown("a<i>斜</i>c")).isEqualTo("a*斜*c");
        assertThat(HtmlText.toMarkdown("a<s>删</s>c")).isEqualTo("a~~删~~c");
        assertThat(HtmlText.toMarkdown("a<code>x</code>c")).isEqualTo("a`x`c");
        assertThat(HtmlText.toMarkdown("见<a href=\"https://x\">链接</a>"))
                .isEqualTo("见[链接](https://x)");
    }

    @Test
    @DisplayName("Markdown：图片不进正文（由 image 参数展示）、列表转为短横线、引用加前缀")
    void markdownListsAndImages() {
        assertThat(HtmlText.toMarkdown("看图<img src=\"https://x/a.png\">")).isEqualTo("看图");
        assertThat(HtmlText.toMarkdown("<ul><li>甲</li><li>乙</li></ul>")).isEqualTo("- 甲\n- 乙");
        assertThat(HtmlText.toMarkdown("<blockquote>引用</blockquote>")).isEqualTo("> 引用");
    }

    @Test
    @DisplayName("Markdown：图片不残留「图片」字样或图片地址；带 alt 的保留 alt（表情）")
    void markdownDropsImagesButKeepsAlt() {
        var html = "好看<img src=\"https://cdn/a.png\"><img src='https://cdn/b.png'>";
        var markdown = HtmlText.toMarkdown(html);

        assertThat(markdown).doesNotContain("图片").doesNotContain("cdn").isEqualTo("好看");
        assertThat(HtmlText.toMarkdown("赞<img src=\"https://cdn/emoji.png\" alt=\"😀\">"))
                .isEqualTo("赞😀");
    }

    @Test
    @DisplayName("Markdown：块级标签用空行分段（Markdown 里单个换行不断行）")
    void markdownUsesBlankLinesBetweenBlocks() {
        assertThat(HtmlText.toMarkdown("第一段<div>第二段</div>")).isEqualTo("第一段\n\n第二段");
        assertThat(HtmlText.toMarkdown("a<br>b")).isEqualTo("a\n\nb");
    }

    @Test
    @DisplayName("Markdown：纯文本内容与纯文本转换结果一致（故可安全总是开启）")
    void markdownOfPlainTextEqualsPlainText() {
        var plain = "涨价其实是市场的必然结果，够用就好。";
        assertThat(HtmlText.toMarkdown(plain)).isEqualTo(plain);
        assertThat(HtmlText.toMarkdown(null)).isEmpty();
    }
}
