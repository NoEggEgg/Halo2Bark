package com.wuqishi.halo.bark;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 脱敏工具的单元测试。
 *
 * <p>依据 PRD 5.8：日志与错误信息中禁止出现设备 Key 明文与完整请求 URL。
 */
class MaskingTest {

    @Nested
    @DisplayName("设备 Key 掩码")
    class KeyMasking {

        @Test
        @DisplayName("保留首尾各 2 位")
        void keepsHeadAndTail() {
            assertThat(Masking.maskKey("AbCdEfGhIjKl")).isEqualTo("Ab****Kl");
        }

        @Test
        @DisplayName("短 Key 整体掩码，不泄漏长度")
        void masksShortKey() {
            assertThat(Masking.maskKey("abc")).isEqualTo("****");
            assertThat(Masking.maskKey("abcd")).isEqualTo("****");
        }

        @Test
        @DisplayName("空 Key 返回占位而非 null")
        void masksBlankKey() {
            assertThat(Masking.maskKey(null)).isEqualTo("(空)");
            assertThat(Masking.maskKey("  ")).isEqualTo("(空)");
        }
    }

    @Nested
    @DisplayName("服务地址掩码")
    class UrlMasking {

        @Test
        @DisplayName("只保留 scheme://host:port")
        void keepsSchemeHostAndPort() {
            assertThat(Masking.maskUrl("https://bark.example.com:8080/push?a=1"))
                    .isEqualTo("https://bark.example.com:8080");
        }

        @Test
        @DisplayName("省略默认端口")
        void omitsDefaultPort() {
            assertThat(Masking.maskUrl("https://api.day.app")).isEqualTo("https://api.day.app");
        }

        @Test
        @DisplayName("无法解析时回退占位，绝不回传原始字符串")
        void fallsBackWhenUnparsable() {
            assertThat(Masking.maskUrl("not a url")).isEqualTo("(未知)");
            assertThat(Masking.maskUrl("")).isEqualTo("(未知)");
            assertThat(Masking.maskUrl(null)).isEqualTo("(未知)");
        }
    }

    @Nested
    @DisplayName("异常文本清理")
    class Sanitizing {

        @Test
        @DisplayName("剔除内嵌的完整 URL（V1 路径含设备 Key）")
        void stripsEmbeddedUrl() {
            var message = "Illegal character in path at index 30: https://bark.example.com/AbCdEfGhIj";

            assertThat(Masking.sanitize(message))
                    .isEqualTo("Illegal character in path at index 30: (地址已隐藏)");
            assertThat(Masking.sanitize(message)).doesNotContain("AbCdEfGhIj");
        }

        @Test
        @DisplayName("空文本返回空字符串")
        void handlesBlank() {
            assertThat(Masking.sanitize(null)).isEmpty();
            assertThat(Masking.sanitize("  ")).isEmpty();
        }

        @Test
        @DisplayName("不含 URL 时原样保留，便于保留可诊断信息")
        void keepsNonUrlText() {
            assertThat(Masking.sanitize("java.net.ConnectException: Connection refused"))
                    .isEqualTo("java.net.ConnectException: Connection refused");
        }

        @Test
        @DisplayName("大写协议（HTTPS）同样被剔除，避免绕过脱敏")
        void stripsUppercaseScheme() {
            var message = "Illegal character in path at index 30: HTTPS://BARK.EXAMPLE.COM/AbCdEfGhIj";

            assertThat(Masking.sanitize(message))
                    .isEqualTo("Illegal character in path at index 30: (地址已隐藏)");
            assertThat(Masking.sanitize(message)).doesNotContain("AbCdEfGhIj");
        }
    }
}
