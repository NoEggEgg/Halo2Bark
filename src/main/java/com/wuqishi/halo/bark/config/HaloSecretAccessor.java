package com.wuqishi.halo.bark.config;

import com.wuqishi.halo.bark.BarkConstants;
import com.wuqishi.halo.bark.Masking;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.extension.Secret;

/**
 * 基于 Halo {@code ReactiveExtensionClient} 的 {@link SecretAccessor} 实现。
 *
 * <p>读取顺序与 Halo 自身一致：先取 {@code stringData}（表单 {@code secret} 组件写入的位置），
 * 再回退 {@code data}（Kubernetes 语义下的 base64 形式）。两者都没有该键时按「取不到」处理。
 *
 * <p><b>读取失败不向上抛异常</b>：配置加载与测试端点都需要继续走完，才能把「哪一条引用读不到」
 * 作为可操作提示返回给管理员。异常在此收敛为「取不到」，并记录不含凭据值的诊断信息。
 *
 * <p>本实现只读取 Secret，不创建也不修改——Secret 由 Halo 的表单组件负责创建。
 */
@Slf4j
@Component
public class HaloSecretAccessor implements SecretAccessor {

    private final ReactiveExtensionClient client;

    public HaloSecretAccessor(ReactiveExtensionClient client) {
        this.client = client;
    }

    @Override
    public Mono<Optional<String>> read(String secretName, String key) {
        if (secretName == null || secretName.isBlank() || key == null || key.isBlank()) {
            return Mono.just(Optional.empty());
        }
        return client.fetch(Secret.class, secretName)
                .map(secret -> extract(secret, key))
                .defaultIfEmpty(Optional.empty())
                .onErrorResume(e -> {
                    // 只记录 Secret 名称与异常摘要，异常文本经脱敏后再落盘
                    log.warn("{} 读取密钥失败（Secret「{}」）：{}", BarkConstants.LOG_PREFIX,
                            secretName, Masking.sanitize(e.toString()));
                    return Mono.just(Optional.empty());
                });
    }

    private static Optional<String> extract(Secret secret, String key) {
        var value = fromStringData(secret.getStringData(), key);
        if (value == null) {
            value = fromData(secret.getData(), key);
        }
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    private static String fromStringData(Map<String, String> stringData, String key) {
        return stringData == null ? null : stringData.get(key);
    }

    private static String fromData(Map<String, byte[]> data, String key) {
        if (data == null) {
            return null;
        }
        var raw = data.get(key);
        return raw == null ? null : new String(raw, StandardCharsets.UTF_8);
    }
}
