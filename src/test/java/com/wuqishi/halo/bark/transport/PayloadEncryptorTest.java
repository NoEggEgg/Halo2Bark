package com.wuqishi.halo.bark.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.wuqishi.halo.bark.support.PushSettingsBuilder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 加密推送的单元测试：验证密文可被 Bark 侧同等参数解密，以及配置错误时明确失败。
 *
 * <p>依据 Bark 官方 {@code docs/encryption.md}：加密对象是**推送参数整体**的 JSON 字符串；
 * {@code iv} 是原始字符串（非 Base64）；{@code device_key} 必须留在密文之外。
 */
class PayloadEncryptorTest {

    private static final String PLAINTEXT = "{\"body\":\"评论正文\",\"title\":\"💬 新评论\"}";
    private static final String KEY_16 = "1234567890123456";

    private final PayloadEncryptor encryptor = new PayloadEncryptor();

    @Test
    @DisplayName("CBC + pkcs7 可被同参数解密还原")
    void cbcRoundTrip() throws Exception {
        var result = encryptor.encrypt(PLAINTEXT, PushSettingsBuilder.push()
                .encryptEnabled(true)
                .encryptAlgorithm("AES128")
                .encryptMode("CBC")
                .encryptPadding("pkcs7")
                .encryptKey(KEY_16)
                .build());

        assertThat(result.iv()).hasSize(16);
        assertThat(decrypt(result.ciphertext(), result.iv(), KEY_16, "CBC", "pkcs7"))
                .isEqualTo(PLAINTEXT);
    }

    @Test
    @DisplayName("密钥按原样使用：首尾空格是合法密钥字节，不做 trim")
    void keyIsUsedVerbatimWithoutTrimming() throws Exception {
        var key = " abcdefghijklmno"; // 1 + 15 = 16 字节
        var result = encryptor.encrypt(PLAINTEXT, PushSettingsBuilder.push()
                .encryptEnabled(true)
                .encryptKey(key)
                .build());

        // 用同一把（未 trim 的）钥匙能解开 → 说明加密时用的就是原样密钥
        assertThat(decrypt(result.ciphertext(), result.iv(), key, "CBC", "pkcs7"))
                .isEqualTo(PLAINTEXT);
        // 反之，去掉空格只剩 15 字节，必须被明确拒绝
        assertThatThrownBy(() -> encryptor.encrypt(PLAINTEXT, PushSettingsBuilder.push()
                .encryptEnabled(true)
                .encryptKey(key.trim())
                .build()))
                .isInstanceOf(EncryptionException.class);
    }

    @Test
    @DisplayName("ECB 不带 IV")
    void ecbRoundTrip() throws Exception {
        var result = encryptor.encrypt(PLAINTEXT, PushSettingsBuilder.push()
                .encryptEnabled(true)
                .encryptMode("ECB")
                .encryptKey(KEY_16)
                .build());

        assertThat(result.iv()).isNull();
        assertThat(decrypt(result.ciphertext(), "", KEY_16, "ECB", "pkcs7")).isEqualTo(PLAINTEXT);
    }

    @Test
    @DisplayName("GCM 使用 12 字节 IV 并可解密还原")
    void gcmRoundTrip() throws Exception {
        var result = encryptor.encrypt(PLAINTEXT, PushSettingsBuilder.push()
                .encryptEnabled(true)
                .encryptMode("GCM")
                .encryptKey(KEY_16)
                .build());

        assertThat(result.iv()).hasSize(12);
        assertThat(decrypt(result.ciphertext(), result.iv(), KEY_16, "GCM", "noPadding"))
                .isEqualTo(PLAINTEXT);
    }

    @Test
    @DisplayName("noPadding 以空格补足块长，解密后仍是可解析的 JSON")
    void noPaddingPadsWithSpaces() throws Exception {
        var result = encryptor.encrypt(PLAINTEXT, PushSettingsBuilder.push()
                .encryptEnabled(true)
                .encryptMode("CBC")
                .encryptPadding("noPadding")
                .encryptKey(KEY_16)
                .build());

        var decrypted = decrypt(result.ciphertext(), result.iv(), KEY_16, "CBC", "noPadding");
        assertThat(decrypted).startsWith(PLAINTEXT);
        assertThat(decrypted.length() % 16).isZero();
        assertThat(decrypted.stripTrailing()).isEqualTo(PLAINTEXT);
    }

    @Test
    @DisplayName("128 / 192 / 256 三种密钥长度均可用")
    void supportsAllKeyLengths() throws Exception {
        assertThat(encryptor.encrypt(PLAINTEXT, settings("AES128", "1234567890123456")).ciphertext())
                .isNotBlank();
        assertThat(encryptor.encrypt(PLAINTEXT, settings("AES192", "123456789012345678901234")).ciphertext())
                .isNotBlank();
        assertThat(encryptor.encrypt(PLAINTEXT,
                settings("AES256", "12345678901234567890123456789012")).ciphertext()).isNotBlank();
    }

    @Test
    @DisplayName("密钥长度与算法不符时报错，不产出明文")
    void rejectsWrongKeyLength() {
        var settings = PushSettingsBuilder.push()
                .encryptEnabled(true)
                .encryptAlgorithm("AES256")
                .encryptKey(KEY_16)
                .build();

        assertThatThrownBy(() -> encryptor.encrypt(PLAINTEXT, settings))
                .isInstanceOf(EncryptionException.class)
                .hasMessageContaining("32 个字节");
    }

    @Test
    @DisplayName("未填密钥时报错")
    void rejectsBlankKey() {
        var settings = PushSettingsBuilder.push().encryptEnabled(true).encryptKey("").build();

        assertThatThrownBy(() -> encryptor.encrypt(PLAINTEXT, settings))
                .isInstanceOf(EncryptionException.class)
                .hasMessageContaining("未填写加密密钥");
    }

    @Test
    @DisplayName("不支持的模式或算法时报错")
    void rejectsUnsupportedParameters() {
        var badMode = PushSettingsBuilder.push()
                .encryptEnabled(true).encryptMode("OFB").encryptKey(KEY_16).build();
        assertThatThrownBy(() -> encryptor.encrypt(PLAINTEXT, badMode))
                .isInstanceOf(EncryptionException.class)
                .hasMessageContaining("不支持的加密模式");

        var badAlgorithm = PushSettingsBuilder.push()
                .encryptEnabled(true).encryptAlgorithm("DES").encryptKey(KEY_16).build();
        assertThatThrownBy(() -> encryptor.encrypt(PLAINTEXT, badAlgorithm))
                .isInstanceOf(EncryptionException.class)
                .hasMessageContaining("不支持的加密算法");
    }

    private static com.wuqishi.halo.bark.config.PushSettings settings(String algorithm, String key) {
        return PushSettingsBuilder.push()
                .encryptEnabled(true)
                .encryptAlgorithm(algorithm)
                .encryptKey(key)
                .build();
    }

    /** 以与 Bark 侧相同的参数解密，用于验证密文可用。 */
    private static String decrypt(String ciphertext, String iv, String key, String mode, String padding)
            throws Exception {
        var keySpec = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES");
        var transformation = "AES/" + mode + "/" + ("pkcs7".equals(padding) ? "PKCS5Padding" : "NoPadding");
        var cipher = Cipher.getInstance(transformation);
        if ("GCM".equals(mode)) {
            cipher.init(Cipher.DECRYPT_MODE, keySpec,
                    new GCMParameterSpec(128, iv.getBytes(StandardCharsets.US_ASCII)));
        } else if ("ECB".equals(mode)) {
            cipher.init(Cipher.DECRYPT_MODE, keySpec);
        } else {
            cipher.init(Cipher.DECRYPT_MODE, keySpec,
                    new IvParameterSpec(iv.getBytes(StandardCharsets.US_ASCII)));
        }
        return new String(cipher.doFinal(Base64.getDecoder().decode(ciphertext)),
                StandardCharsets.UTF_8);
    }
}
