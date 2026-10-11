package com.wuqishi.halo.bark.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 把配置中的 Secret **引用**解析为实际凭据值。
 *
 * <p>职责边界：只做「引用 → 值」的替换与「读不到」的归因，不做取值合法性校验——
 * 密钥长度、算法匹配等仍由 {@link SettingsValidator} 统一判定，避免两处口径分叉。
 *
 * <p>读不到的引用会被替换为空白并产生归因，由 {@link SettingsStore} 分级写入快照：
 * <b>设备 Key</b> 读不到时该设备因 Key 为空自动不参与投递，属**提示级**；
 * <b>加密密钥</b> 读不到则无法安全投递（不得降级为明文），属**阻断级**，投递线程据此停止全部推送。
 *
 * <p>两处刻意不报错：
 * <ul>
 *   <li>设备 Key 为空——按 PRD 5.3，配置不完整的设备允许保存，只是不参与投递。</li>
 *   <li>未启用加密时的加密密钥——该字段不参与投递，避免「已删除的旧引用」把整体配置判为阻断。</li>
 * </ul>
 */
@Component
public class CredentialResolver {

    private static final String UNNAMED_DEVICE = "(未命名设备)";

    private final SecretAccessor accessor;

    public CredentialResolver(SecretAccessor accessor) {
        this.accessor = accessor;
    }

    /** 解析结果：设备 Key 与加密密钥均已替换为实际值，并附带「读不到」的分级归因。 */
    public record Resolution(PushSettings push, List<String> warnings, List<String> blocking) {

        public Resolution {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
            blocking = blocking == null ? List.of() : List.copyOf(blocking);
        }
    }

    /**
     * 解析一份配置中的所有 Secret 引用。
     *
     * @param raw 直接来自 ConfigMap 的配置（凭据字段承载的是 Secret 名称）
     * @return 凭据已替换为实际值的配置，以及读不到引用的归因
     */
    public Mono<Resolution> resolve(PushSettings raw) {
        return Mono.zip(resolveDevices(raw.devices()), resolveEncryptKey(raw))
                .map(tuple -> assemble(raw, tuple.getT1(), tuple.getT2()));
    }

    private Mono<List<DeviceResolution>> resolveDevices(List<DeviceSetting> devices) {
        // concatMap 保证按序且不并发，设备顺序与配置一致（issue 顺序也随之稳定）
        return Flux.fromIterable(devices)
                .concatMap(this::resolveDevice)
                .collectList();
    }

    private Mono<DeviceResolution> resolveDevice(DeviceSetting device) {
        if (device.key().isBlank()) {
            return Mono.just(new DeviceResolution(device, null));
        }
        var reference = device.key();
        return accessor.read(reference, SecretAccessor.CREDENTIAL_KEY)
                .map(value -> value
                        .map(resolved -> new DeviceResolution(
                                copyWithKey(device, resolved), null))
                        .orElseGet(() -> new DeviceResolution(copyWithKey(device, ""),
                                ("设备「%s」的密钥引用读不到：Secret「%s」不存在，或其中缺少 %s 字段。"
                                        + "请到插件设置页为该设备重新选择或创建密钥。")
                                        .formatted(deviceLabel(device), reference,
                                                SecretAccessor.CREDENTIAL_KEY))));
    }

    private Mono<KeyResolution> resolveEncryptKey(PushSettings raw) {
        if (!Boolean.TRUE.equals(raw.encryptEnabled()) || raw.encryptKey().isBlank()) {
            return Mono.just(new KeyResolution(raw.encryptKey(), null));
        }
        var reference = raw.encryptKey();
        return accessor.read(reference, SecretAccessor.CREDENTIAL_KEY)
                .map(value -> value
                        .map(resolved -> new KeyResolution(resolved, null))
                        .orElseGet(() -> new KeyResolution("",
                                ("加密密钥的引用读不到：Secret「%s」不存在，或其中缺少 %s 字段。"
                                        + "请到插件设置页重新选择或创建加密密钥。")
                                        .formatted(reference, SecretAccessor.CREDENTIAL_KEY))));
    }

    private static Resolution assemble(PushSettings raw, List<DeviceResolution> devices,
            KeyResolution encrypt) {
        // 设备密钥读不到 → 该设备已因 Key 为空而不参与投递，属提示级，不影响其他设备；
        // 加密密钥读不到 → 无法安全投递（不得降级为明文），属阻断级。
        var warnings = new ArrayList<String>();
        devices.stream().map(DeviceResolution::issue).filter(Objects::nonNull).forEach(warnings::add);
        var blocking = new ArrayList<String>();
        if (encrypt.issue() != null) {
            blocking.add(encrypt.issue());
        }
        var resolvedDevices = devices.stream().map(DeviceResolution::device).toList();
        return new Resolution(raw.withDevices(resolvedDevices).withEncryptKey(encrypt.value()),
                warnings, blocking);
    }

    private static DeviceSetting copyWithKey(DeviceSetting device, String key) {
        return new DeviceSetting(device.name(), key, device.serverUrl(), device.enabled());
    }

    private static String deviceLabel(DeviceSetting device) {
        return device.name().isBlank() ? UNNAMED_DEVICE : device.name();
    }

    /** 单台设备的解析结果：{@code issue} 非空表示该设备的引用读不到。 */
    private record DeviceResolution(DeviceSetting device, String issue) {
    }

    /** 加密密钥的解析结果：{@code issue} 非空表示引用读不到。 */
    private record KeyResolution(String value, String issue) {
    }
}
