package com.wuqishi.halo.bark.support;

import com.wuqishi.halo.bark.config.SecretAccessor;
import java.util.Map;
import java.util.Optional;
import reactor.core.publisher.Mono;

/**
 * 测试用 {@link SecretAccessor}：不依赖 Halo 运行时。
 *
 * <p>两种口径分别对应两类用例：
 * <ul>
 *   <li>{@link #identity()}——把提交的引用原样当作凭据返回，使「直接在表单里填 Key」的既有用例
 *       无需为 Secret 解析额外铺垫。</li>
 *   <li>{@link #of(Map)}——只有显式登记过的引用可解析，其余按「读不到」处理，用于覆盖失败路径。</li>
 * </ul>
 */
public final class FakeSecretAccessor {

    private FakeSecretAccessor() {
    }

    /** 引用即凭据。 */
    public static SecretAccessor identity() {
        return (secretName, key) ->
                Mono.just(Optional.ofNullable(secretName).filter(value -> !value.isBlank()));
    }

    /** 按给定映射解析；未登记的引用返回空。 */
    public static SecretAccessor of(Map<String, String> values) {
        var snapshot = Map.copyOf(values);
        return (secretName, key) ->
                Mono.just(Optional.ofNullable(snapshot.get(secretName))
                        .filter(value -> !value.isBlank()));
    }
}
