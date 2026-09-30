package com.wuqishi.halo.bark.json;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * JSON 读写的单元测试：只覆盖本插件用到的两种形态。
 */
class JsonTest {

    @Test
    @DisplayName("序列化保持插入顺序且为紧凑格式")
    void writesCompactJsonInInsertionOrder() {
        var params = new LinkedHashMap<String, Object>();
        params.put("title", "新评论");
        params.put("body", "有人评论了你的文章");
        params.put("ttl", 604800);

        assertThat(Json.write(params))
                .isEqualTo("{\"title\":\"新评论\",\"body\":\"有人评论了你的文章\",\"ttl\":604800}");
    }

    @Test
    @DisplayName("序列化数组字段")
    void writesArrayField() {
        Map<String, Object> params = Map.of("device_keys", List.of("a", "b"));
        assertThat(Json.write(params)).isEqualTo("{\"device_keys\":[\"a\",\"b\"]}");
    }

    @Test
    @DisplayName("读取顶层字段")
    void readsTopLevelField() {
        assertThat(Json.field("{\"version\":\"v2.3.6\",\"devices\":3}", "version")).isEqualTo("v2.3.6");
        assertThat(Json.field("{\"version\":\"v2.3.6\"}", "build")).isEmpty();
    }

    @Test
    @DisplayName("响应体非法或为空白时返回空字符串，不抛异常")
    void toleratesInvalidPayload() {
        assertThat(Json.field("not-json", "version")).isEmpty();
        assertThat(Json.field("", "version")).isEmpty();
        assertThat(Json.field(null, "version")).isEmpty();
        assertThat(Json.field("[1,2]", "version")).isEmpty();
    }

    @Test
    @DisplayName("非文本节点（如数字）视为缺失，不误判为 V2")
    void ignoresNonTextVersion() {
        assertThat(Json.field("{\"version\":2}", "version")).isEmpty();
        assertThat(Json.field("{\"version\":true}", "version")).isEmpty();
        assertThat(Json.field("{\"version\":\"v2.3.6\"}", "version")).isEqualTo("v2.3.6");
    }
}
