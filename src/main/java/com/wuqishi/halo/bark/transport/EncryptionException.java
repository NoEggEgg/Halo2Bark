package com.wuqishi.halo.bark.transport;

/**
 * 加密推送失败。
 *
 * <p>受检异常：强制调用方显式处理。PRD 5.2 与 11 明确规定——加密失败时**不得**静默降级为明文发送，
 * 必须让该次投递失败并提示加密配置有误。
 */
public class EncryptionException extends Exception {

    public EncryptionException(String message) {
        super(message);
    }

    public EncryptionException(String message, Throwable cause) {
        super(message, cause);
    }
}
