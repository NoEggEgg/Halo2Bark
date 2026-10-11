package com.wuqishi.halo.bark.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.wuqishi.halo.bark.config.CredentialResolver.Resolution;
import com.wuqishi.halo.bark.support.FakeSecretAccessor;
import com.wuqishi.halo.bark.support.PushSettingsBuilder;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link CredentialResolver} 的行为契约：ConfigMap 中的 Secret 引用必须被替换为实际凭据，
 * 读不到的引用必须 fail closed（替换为空白 + 可操作归因），且不得误伤「配置不完整」与
 * 「未启用加密」两种正常状态。
 */
@DisplayName("凭据解析：Secret 引用 → 实际值")
class CredentialResolverTest {

    private static final String DEVICE_SECRET = "secret-device-1";
    private static final String ENCRYPT_SECRET = "secret-encrypt";

    @Test
    @DisplayName("设备 Key 的引用被替换为 Secret 中的实际值")
    void resolvesDeviceKeyFromSecret() {
        var resolver = new CredentialResolver(
                FakeSecretAccessor.of(Map.of(DEVICE_SECRET, "device-key-value")));
        var raw = PushSettingsBuilder.push()
                .devices(new DeviceSetting("iPhone", DEVICE_SECRET, "https://api.day.app", true))
                .build();

        var resolution = resolve(resolver, raw);

        assertThat(resolution.warnings()).isEmpty();
        assertThat(resolution.push().devices()).singleElement()
                .satisfies(device -> {
                    assertThat(device.key()).isEqualTo("device-key-value");
                    assertThat(device.name()).isEqualTo("iPhone");
                    assertThat(device.serverUrl()).isEqualTo("https://api.day.app");
                    assertThat(device.deliverable()).isTrue();
                });
    }

    @Test
    @DisplayName("设备 Key 为空：按配置不完整处理，不报错也不解析")
    void blankDeviceKeyIsNotAnIssue() {
        var resolver = new CredentialResolver(FakeSecretAccessor.of(Map.of()));
        var raw = PushSettingsBuilder.push()
                .devices(new DeviceSetting("iPad", "", "https://api.day.app", true))
                .build();

        var resolution = resolve(resolver, raw);

        assertThat(resolution.warnings()).isEmpty();
        assertThat(resolution.push().devices()).singleElement()
                .satisfies(device -> assertThat(device.deliverable()).isFalse());
    }

    @Test
    @DisplayName("设备 Key 的引用读不到：置空并给出可操作归因（不把 Secret 名称当 Key 用）")
    void unresolvableDeviceKeyFailsClosed() {
        var resolver = new CredentialResolver(FakeSecretAccessor.of(Map.of()));
        var raw = PushSettingsBuilder.push()
                .devices(new DeviceSetting("iPhone", "secret-gone", "https://api.day.app", true))
                .build();

        var resolution = resolve(resolver, raw);

        assertThat(resolution.warnings()).singleElement()
                .satisfies(issue -> assertThat(issue)
                        .contains("iPhone").contains("secret-gone").contains("重新选择或创建密钥"));
        assertThat(resolution.blocking()).as("设备密钥读不到只影响该设备，不阻断全部推送").isEmpty();
        assertThat(resolution.push().devices()).singleElement()
                .satisfies(device -> {
                    assertThat(device.key()).as("不得保留引用原文当作 Key").isEmpty();
                    assertThat(device.deliverable()).isFalse();
                });
    }

    @Test
    @DisplayName("多台设备：顺序保持，逐台独立判定")
    void resolvesDevicesIndependentlyInOrder() {
        var resolver = new CredentialResolver(
                FakeSecretAccessor.of(Map.of("secret-a", "key-a", "secret-c", "key-c")));
        var raw = PushSettingsBuilder.push()
                .devices(
                        new DeviceSetting("A", "secret-a", "https://api.day.app", true),
                        new DeviceSetting("B", "secret-b", "https://api.day.app", true),
                        new DeviceSetting("C", "secret-c", "https://api.day.app", true))
                .build();

        var resolution = resolve(resolver, raw);

        assertThat(resolution.push().devices()).extracting(DeviceSetting::name)
                .containsExactly("A", "B", "C");
        assertThat(resolution.push().devices()).extracting(DeviceSetting::key)
                .containsExactly("key-a", "", "key-c");
        assertThat(resolution.warnings()).singleElement()
                .satisfies(issue -> assertThat(issue).contains("B").contains("secret-b"));
    }

    @Test
    @DisplayName("加密启用时，加密密钥的引用被替换为实际值")
    void resolvesEncryptKeyWhenEnabled() {
        var resolver = new CredentialResolver(
                FakeSecretAccessor.of(Map.of(ENCRYPT_SECRET, "0123456789abcdef")));
        var raw = PushSettingsBuilder.push()
                .encryptEnabled(true)
                .encryptKey(ENCRYPT_SECRET)
                .build();

        var resolution = resolve(resolver, raw);

        assertThat(resolution.warnings()).isEmpty();
        assertThat(resolution.push().encryptKey()).isEqualTo("0123456789abcdef");
        assertThat(SettingsValidator.inspect(new BarkSettings(resolution.push(),
                EventToggles.defaults())).blocking()).isEmpty();
    }

    @Test
    @DisplayName("加密启用且引用读不到：置空并归因，交由校验层再补一条「缺失或无法读取」")
    void unresolvableEncryptKeyFailsClosed() {
        var resolver = new CredentialResolver(FakeSecretAccessor.of(Map.of()));
        var raw = PushSettingsBuilder.push()
                .encryptEnabled(true)
                .encryptKey("secret-gone")
                .build();

        var resolution = resolve(resolver, raw);

        assertThat(resolution.push().encryptKey()).isEmpty();
        assertThat(resolution.blocking()).singleElement()
                .satisfies(issue -> assertThat(issue).contains("secret-gone").contains("加密密钥"));
        assertThat(resolution.warnings()).as("加密密钥问题属阻断级，不重复计入提示级").isEmpty();
    }

    @Test
    @DisplayName("未启用加密：不解析加密密钥，已删除的旧引用不使整体配置判为非法")
    void encryptKeyIsIgnoredWhenEncryptionDisabled() {
        var resolver = new CredentialResolver(FakeSecretAccessor.of(Map.of()));
        var raw = PushSettingsBuilder.push()
                .encryptEnabled(false)
                .encryptKey("secret-deleted-long-ago")
                .build();

        var resolution = resolve(resolver, raw);

        assertThat(resolution.warnings()).isEmpty();
        assertThat(resolution.push().encryptKey()).isEqualTo("secret-deleted-long-ago");
        assertThat(SettingsValidator.inspect(new BarkSettings(resolution.push(),
                EventToggles.defaults())).blocking()).isEmpty();
    }

    @Test
    @DisplayName("加密启用但密钥字段为空：不在此报错，交由校验层给出唯一提示")
    void blankEncryptKeyIsNotResolvedHere() {
        var resolver = new CredentialResolver(FakeSecretAccessor.of(Map.of()));
        var raw = PushSettingsBuilder.push().encryptEnabled(true).encryptKey("").build();

        var resolution = resolve(resolver, raw);

        assertThat(resolution.warnings()).isEmpty();
        assertThat(resolution.push().encryptKey()).isEmpty();
    }

    @Test
    @DisplayName("解析不修改除凭据以外的任何字段")
    void otherFieldsAreUntouched() {
        var resolver = new CredentialResolver(
                FakeSecretAccessor.of(Map.of(DEVICE_SECRET, "key", ENCRYPT_SECRET, "0123456789abcdef")));
        var raw = PushSettingsBuilder.push()
                .devices(new DeviceSetting("iPhone", DEVICE_SECRET, "https://api.day.app", true))
                .group("我的站点")
                .archiveTtl("30d")
                .interactiveLevel("critical")
                .encryptEnabled(true)
                .encryptKey(ENCRYPT_SECRET)
                .build();

        var resolution = resolve(resolver, raw);

        assertThat(resolution.push())
                .usingRecursiveComparison()
                .ignoringFields("devices", "encryptKey")
                .isEqualTo(raw);
        assertThat(List.of(resolution.push().group(), resolution.push().archiveTtl(),
                resolution.push().interactiveLevel()))
                .containsExactly("我的站点", "30d", "critical");
    }

    private static Resolution resolve(CredentialResolver resolver, PushSettings raw) {
        var resolution = resolver.resolve(raw).block();
        assertThat(resolution).as("解析结果不应为空流").isNotNull();
        return resolution;
    }
}
