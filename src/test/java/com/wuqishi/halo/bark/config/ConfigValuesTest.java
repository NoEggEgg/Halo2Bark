package com.wuqishi.halo.bark.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("配置值归一化：宽松解析与默认值补齐")
class ConfigValuesTest {

    @Test
    @DisplayName("text：空值或空白回退默认值")
    void textFallsBackOnBlank() {
        assertThat(ConfigValues.text(null, "d")).isEqualTo("d");
        assertThat(ConfigValues.text("  ", "d")).isEqualTo("d");
        assertThat(ConfigValues.text("v", "d")).isEqualTo("v");
    }

    @Test
    @DisplayName("textUnlessNull：仅 null 回退，空白保留（使「留空即不合法」的校验可达）")
    void textUnlessNullKeepsBlank() {
        assertThat(ConfigValues.textUnlessNull(null, "d")).isEqualTo("d");
        assertThat(ConfigValues.textUnlessNull("  ", "d")).isEqualTo("  ");
        assertThat(ConfigValues.textUnlessNull("v", "d")).isEqualTo("v");
    }

    @Test
    @DisplayName("textOrEmpty：null 归一化为空串")
    void textOrEmpty() {
        assertThat(ConfigValues.textOrEmpty(null)).isEmpty();
        assertThat(ConfigValues.textOrEmpty("v")).isEqualTo("v");
    }

    @Test
    @DisplayName("布尔开关：缺失时分别按 PRD 默认值补齐")
    void flags() {
        assertThat(ConfigValues.flagOrTrue(null)).isTrue();
        assertThat(ConfigValues.flagOrTrue(Boolean.FALSE)).isFalse();
        assertThat(ConfigValues.flagOrFalse(null)).isFalse();
        assertThat(ConfigValues.flagOrFalse(Boolean.TRUE)).isTrue();
    }

    @Test
    @DisplayName("intOrNull：同时接受数字字符串与带空白的形式")
    void intOrNullParses() {
        assertThat(ConfigValues.intOrNull("6")).isEqualTo(6);
        assertThat(ConfigValues.intOrNull("  24  ")).isEqualTo(24);
        assertThat(ConfigValues.intOrNull("-3")).isEqualTo(-3);
    }

    @Test
    @DisplayName("intOrNull：空白、非数字、超范围一律返回 null")
    void intOrNullRejects() {
        assertThat(ConfigValues.intOrNull(null)).isNull();
        assertThat(ConfigValues.intOrNull("  ")).isNull();
        assertThat(ConfigValues.intOrNull("abc")).isNull();
        assertThat(ConfigValues.intOrNull("99999999999999")).isNull();
    }
}
