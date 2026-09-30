package com.wuqishi.halo.bark.config;

import java.util.Optional;
import reactor.core.publisher.Mono;

/**
 * 读取 Halo {@code Secret} 中的凭据。
 *
 * <p>存在的理由：Halo 表单的 {@code secret} 组件只在配置中保存 Secret 的**名称**，真正的密钥由
 * Halo 托管在 Secret 资源里（PRD 8.2）。配置层因此拿到的是引用，必须在加载配置时解析成实际值。
 *
 * <p>抽象为接口是为了可测试：配置层与「测试该设备」端点只依赖本接口，无需 Halo 运行时（PRD 10.4.5）。
 */
public interface SecretAccessor {

    /**
     * Secret 中承载单个凭据值的键名。
     *
     * <p>与 {@code settings.yaml} 中 {@code requiredKeys} 声明的键名必须一致，
     * 两处共用本常量以免各自漂移。
     */
    String CREDENTIAL_KEY = "key";

    /**
     * 读取指定 Secret 中某个键的值。
     *
     * @param secretName Secret 资源名称（表单 {@code secret} 组件保存的值）
     * @param key        Secret 内的键名
     * @return 凭据值；Secret 不存在、键缺失、值为空白或读取失败时返回空
     */
    Mono<Optional<String>> read(String secretName, String key);
}
