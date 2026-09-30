package com.wuqishi.halo.bark.transport;

import com.wuqishi.halo.bark.config.PushSettings;
import com.wuqishi.halo.bark.config.SettingsValidator;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * 推送内容加密器。
 *
 * <p>加密对象（Bark 官方 {@code docs/encryption.md}）：把**推送参数整体**序列化为 JSON 字符串后加密，
 * 密文经 {@code ciphertext} 参数下发；{@code device_key} / {@code device_keys} 必须在密文之外，
 * 否则服务端无法路由。随机 IV 必须以 {@code iv} 参数随请求传递。
 *
 * <p>IV 形态：Bark 的 {@code iv} 是**原始字符串**（示例 {@code iv=1234567890123456}），
 * 其 ASCII 字节即 IV 字节。因此本类生成等长的可打印 ASCII 随机串，而非 Base64。
 *
 * <p>支持的算法 / 模式 / 填充与密钥长度见 {@link SettingsValidator}（对照 Bark App 的加密算法实现）。
 * 说明两点实现取舍：
 * <ul>
 *   <li>Java 没有 {@code PKCS7Padding}；AES 的 {@code PKCS5Padding} 在 16 字节块下与 PKCS#7 等价。</li>
 *   <li>{@code noPadding} 时明文长度必须是块大小的整数倍。本类以**空格**补足而非零字节——
 *       空格是合法 JSON 空白字符，Bark 解密后仍能正常解析；零字节则会导致 JSON 解析失败。</li>
 *   <li>GCM 属于 AEAD，无填充概念，忽略填充配置；IV 取 12 字节（GCM 推荐长度）。</li>
 * </ul>
 */
@Component
public class PayloadEncryptor {

    private static final String ALGORITHM = "AES";
    private static final int BLOCK_SIZE = 16;
    private static final int CBC_IV_LENGTH = 16;
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final byte SPACE = (byte) ' ';
    private static final char[] IV_ALPHABET =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray();

    private final SecureRandom random = new SecureRandom();

    /**
     * 加密结果。
     *
     * @param ciphertext Base64 密文
     * @param iv 原始 IV 字符串；ECB 模式下为 {@code null}（不传 {@code iv} 参数）
     */
    public record EncryptionResult(String ciphertext, String iv) {
    }

    /**
     * 加密推送参数 JSON。
     *
     * @param plaintext 推送参数序列化后的 JSON 字符串（不含 {@code device_key} / {@code device_keys}）
     * @param settings 全局推送配置，提供算法、模式、填充与密钥
     * @throws EncryptionException 密钥长度不符、算法参数不受支持或加密过程失败；
     *         调用方必须让该次投递失败，不得改为明文发送
     */
    public EncryptionResult encrypt(String plaintext, PushSettings settings) throws EncryptionException {
        var key = secretKey(settings);
        return switch (settings.encryptMode()) {
            case "CBC" -> {
                var iv = randomIv(CBC_IV_LENGTH);
                yield new EncryptionResult(
                        base64(cipher(plaintext, key, settings.encryptPadding(), "CBC", iv)), iv);
            }
            case "ECB" -> new EncryptionResult(
                    base64(cipher(plaintext, key, settings.encryptPadding(), "ECB", null)), null);
            case "GCM" -> {
                var iv = randomIv(GCM_IV_LENGTH);
                yield new EncryptionResult(
                        base64(cipher(plaintext, key, "noPadding", "GCM", iv)), iv);
            }
            default -> throw new EncryptionException("不支持的加密模式：" + settings.encryptMode()
                    + "，可选 CBC / ECB / GCM");
        };
    }

    private SecretKeySpec secretKey(PushSettings settings) throws EncryptionException {
        var raw = settings.encryptKey();
        var expected = SettingsValidator.expectedKeyLength(settings.encryptAlgorithm());
        if (raw == null || raw.isBlank()) {
            throw new EncryptionException("已启用加密推送但未填写加密密钥");
        }
        if (expected < 0) {
            throw new EncryptionException("不支持的加密算法：" + settings.encryptAlgorithm()
                    + "，可选 AES128 / AES192 / AES256");
        }
        // 密钥按**输入原样**取 UTF-8 字节：Bark App 也是这么取的，两端必须一致。
        // 因此不做 trim——空格是合法密钥字节，去掉它等于换了一把钥匙。
        var bytes = raw.getBytes(StandardCharsets.UTF_8);
        if (bytes.length != expected) {
            throw new EncryptionException("加密密钥必须是 " + expected + " 个字节（"
                    + settings.encryptAlgorithm() + " 要求，按 UTF-8 计算），当前 " + bytes.length
                    + " 个字节");
        }
        return new SecretKeySpec(bytes, ALGORITHM);
    }

    private byte[] cipher(String plaintext, SecretKeySpec key, String padding, String mode, String iv)
            throws EncryptionException {
        var transformation = ALGORITHM + "/" + mode + "/" + ("pkcs7".equals(padding) && !"GCM".equals(mode)
                ? "PKCS5Padding" : "NoPadding");
        try {
            var instance = Cipher.getInstance(transformation);
            if (iv == null) {
                instance.init(Cipher.ENCRYPT_MODE, key);
            } else {
                var ivBytes = iv.getBytes(StandardCharsets.US_ASCII);
                instance.init(Cipher.ENCRYPT_MODE, key, "GCM".equals(mode)
                        ? new GCMParameterSpec(GCM_TAG_BITS, ivBytes)
                        : new IvParameterSpec(ivBytes));
            }
            return instance.doFinal(pad(plaintext.getBytes(StandardCharsets.UTF_8), padding, mode));
        } catch (Exception e) {
            throw new EncryptionException("加密推送失败（" + transformation + "）：" + e.getMessage(), e);
        }
    }

    private byte[] pad(byte[] data, String padding, String mode) {
        if (!"noPadding".equals(padding) || "GCM".equals(mode)) {
            return data;
        }
        var remainder = data.length % BLOCK_SIZE;
        if (remainder == 0) {
            return data;
        }
        var padded = Arrays.copyOf(data, data.length + (BLOCK_SIZE - remainder));
        Arrays.fill(padded, data.length, padded.length, SPACE);
        return padded;
    }

    private String randomIv(int length) {
        var builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append(IV_ALPHABET[random.nextInt(IV_ALPHABET.length)]);
        }
        return builder.toString();
    }

    /** 加密后的 Base64 密文由本方法统一产出，避免各处重复编码。 */
    private static String base64(byte[] data) {
        return Base64.getEncoder().encodeToString(data);
    }
}
