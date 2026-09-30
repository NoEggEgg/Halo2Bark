package com.wuqishi.halo.bark.transport;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("失败分类：可重试判定与可操作提示")
class DeliveryFailureTest {

    @Test
    @DisplayName("仅网络异常、超时、5xx 可重试（PRD 5.2）")
    void retryableFlags() {
        assertThat(DeliveryFailure.TIMEOUT.retryable()).isTrue();
        assertThat(DeliveryFailure.UNREACHABLE.retryable()).isTrue();
        assertThat(DeliveryFailure.SERVER_ERROR.retryable()).isTrue();

        assertThat(DeliveryFailure.CLIENT_ERROR.retryable()).isFalse();
        assertThat(DeliveryFailure.ENCRYPTION.retryable()).isFalse();
        assertThat(DeliveryFailure.UNEXPECTED.retryable()).isFalse();
    }

    @Test
    @DisplayName("提示文案填充参数后不残留占位符")
    void describeFillsPlaceholders() {
        assertThat(DeliveryFailure.CLIENT_ERROR.describe(404)).contains("404").doesNotContain("%d");
        assertThat(DeliveryFailure.SERVER_ERROR.describe(503)).contains("503").doesNotContain("%d");
        assertThat(DeliveryFailure.ENCRYPTION.describe("密钥长度不符"))
                .contains("密钥长度不符").doesNotContain("%s");
        assertThat(DeliveryFailure.UNEXPECTED.describe("boom")).contains("boom").doesNotContain("%s");
    }

    @Test
    @DisplayName("无参时返回模板本身，且非空")
    void describeWithoutArgs() {
        assertThat(DeliveryFailure.TIMEOUT.describe()).isNotBlank().isEqualTo(
                DeliveryFailure.TIMEOUT.describe(new Object[0]));
    }

    @Test
    @DisplayName("文案给出「怎么解决」，而非只说失败（PRD 10.6.2）")
    void messagesAreActionable() {
        assertThat(DeliveryFailure.TIMEOUT.describe()).contains("请");
        assertThat(DeliveryFailure.UNREACHABLE.describe()).contains("请");
        assertThat(DeliveryFailure.CLIENT_ERROR.describe(401)).contains("请");
        assertThat(DeliveryFailure.SERVER_ERROR.describe(500)).contains("请");
        assertThat(DeliveryFailure.ENCRYPTION.describe("x")).contains("请");
        assertThat(DeliveryFailure.UNEXPECTED.describe("x")).contains("x");
    }
}
