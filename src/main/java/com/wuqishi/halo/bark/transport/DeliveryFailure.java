package com.wuqishi.halo.bark.transport;

/**
 * 单台设备投递失败的原因分类。
 *
 * <p>分类的意义在于两件事（PRD 10.4.3）：决定**是否重试**，以及给出**可操作的提示**（PRD 10.6.2）。
 * 提示文案中的 {@code %d} / {@code %s} 占位由传输层在构造结果时填充。
 */
public enum DeliveryFailure {

    /** 连接超时。可重试。 */
    TIMEOUT(true, "请求超时。服务地址响应过慢或网络不通，请检查网络与该地址是否可访问"),

    /** 网络异常、域名无法解析、连接被拒。可重试。 */
    UNREACHABLE(true, "服务地址不可达。请检查地址是否正确、是否为 Bark 服务，以及网络是否可达"),

    /** Bark 返回 4xx。不可重试——属配置或凭据问题（PRD 11）。 */
    CLIENT_ERROR(false, "Bark 服务拒绝了请求（HTTP %d）。多为设备 Key 有误，请从 Bark App 重新复制设备 Key"),

    /** Bark 返回 5xx。可重试。 */
    SERVER_ERROR(true, "Bark 服务暂时不可用（HTTP %d），请稍后重试"),

    /** 加密配置错误或加密失败。不可重试，且**不得**降级为明文发送（PRD 5.2、11）。 */
    ENCRYPTION(false, "加密推送失败：%s。请核对加密算法 / 模式 / 填充 / 密钥是否与 Bark App 中的设置完全一致"),

    /** 其他未预期异常。不可重试。 */
    UNEXPECTED(false, "推送未成功（%s）");

    private final boolean retryable;
    private final String template;

    DeliveryFailure(boolean retryable, String template) {
        this.retryable = retryable;
        this.template = template;
    }

    /** 该失败是否值得重试（PRD 5.2：仅网络异常、超时、5xx 重试）。 */
    public boolean retryable() {
        return retryable;
    }

    /** 按模板生成可操作的提示文案。 */
    public String describe(Object... args) {
        return args == null || args.length == 0 ? template : template.formatted(args);
    }
}
