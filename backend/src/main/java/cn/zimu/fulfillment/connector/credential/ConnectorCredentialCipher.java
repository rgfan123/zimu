package cn.zimu.fulfillment.connector.credential;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 渠道凭据的应用层加解密（AES-GCM）。
 *
 * <p>密钥来自环境变量 {@code CONNECTOR_CREDENTIAL_KEY}（经 {@code app.connector.credential-key}
 * 映射），要求 Base64 编码的 16/24/32 字节。设计为 fail-closed：</p>
 *
 * <ul>
 *   <li>密钥未配置时 {@link #encrypt} 直接抛业务错误，绝不退回明文保存；</li>
 *   <li>密钥未配置/无效时 {@link #decrypt} 同样报清楚，绝不静默返回空；</li>
 *   <li>密钥格式错误不在构造期炸掉整个应用，而是在首次使用时以业务码暴露——
 *       未配置密钥的部署整体退回「纯环境变量凭据」模式，不造成回归。</li>
 * </ul>
 *
 * <p>密文格式 {@code v1:<base64(iv)>:<base64(ciphertext+tag)>}；AAD 绑定渠道名，
 * 防止密文在渠道行之间被拷贝复用。任何消息都不携带明文、密文或密钥。</p>
 */
@Component
public final class ConnectorCredentialCipher {

    static final String CIPHERTEXT_PREFIX = "v1:";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 二选一：key 可用，或 keyError 描述密钥为何不可用（null+null = 未配置）。 */
    private final SecretKey key;
    private final String keyError;

    public ConnectorCredentialCipher(
            @Value("${app.connector.credential-key:}") String base64Key) {
        String raw = base64Key == null ? "" : base64Key.trim();
        if (raw.isEmpty()) {
            this.key = null;
            this.keyError = null;
            return;
        }
        SecretKey parsed = null;
        String error = null;
        try {
            byte[] bytes = Base64.getDecoder().decode(raw);
            if (bytes.length == 16 || bytes.length == 24 || bytes.length == 32) {
                parsed = new SecretKeySpec(bytes, "AES");
            } else {
                error = "凭据加密密钥长度无效（需 Base64 编码的 16/24/32 字节）";
            }
        } catch (IllegalArgumentException exception) {
            error = "凭据加密密钥不是合法 Base64";
        }
        this.key = parsed;
        this.keyError = error;
    }

    /** 密钥是否已配置且可用；未配置时保存密码必须被拒绝（fail-closed）。 */
    public boolean keyConfigured() {
        return key != null;
    }

    /** 加密渠道密码；密钥未配置/无效直接抛业务错误，绝不退回明文。 */
    public String encrypt(String channel, String plaintext) {
        requireUsableKey();
        byte[] iv = new byte[IV_LENGTH_BYTES];
        RANDOM.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            cipher.updateAAD(aad(channel));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return CIPHERTEXT_PREFIX
                    + Base64.getEncoder().encodeToString(iv)
                    + ":"
                    + Base64.getEncoder().encodeToString(ciphertext);
        } catch (java.security.GeneralSecurityException exception) {
            throw new ConnectorCredentialException("CREDENTIAL_ENCRYPT_FAILED", "凭据加密失败，密码未保存");
        }
    }

    /** 解密渠道密码；密钥缺失、密文损坏、密钥更换均以独立业务码报清楚。 */
    public String decrypt(String channel, String stored) {
        requireUsableKey();
        Parsed parsed = parse(stored);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, parsed.iv()));
            cipher.updateAAD(aad(channel));
            return new String(cipher.doFinal(parsed.ciphertext()), StandardCharsets.UTF_8);
        } catch (java.security.GeneralSecurityException exception) {
            throw new ConnectorCredentialException(
                    "CREDENTIAL_DECRYPT_FAILED",
                    "凭据解密失败（加密密钥可能已更换），请在界面重新保存密码");
        }
    }

    private void requireUsableKey() {
        if (key != null) {
            return;
        }
        if (keyError != null) {
            throw new ConnectorCredentialException("CREDENTIAL_KEY_INVALID", keyError);
        }
        throw new ConnectorCredentialException(
                "CREDENTIAL_KEY_MISSING",
                "凭据加密密钥未配置（环境变量 CONNECTOR_CREDENTIAL_KEY）");
    }

    private static Parsed parse(String stored) {
        String value = stored == null ? "" : stored.trim();
        if (!value.startsWith(CIPHERTEXT_PREFIX)) {
            throw invalidCiphertext();
        }
        String[] parts = value.substring(CIPHERTEXT_PREFIX.length()).split(":", -1);
        if (parts.length != 2) {
            throw invalidCiphertext();
        }
        try {
            byte[] iv = Base64.getDecoder().decode(parts[0]);
            byte[] ciphertext = Base64.getDecoder().decode(parts[1]);
            if (iv.length != IV_LENGTH_BYTES || ciphertext.length == 0) {
                throw invalidCiphertext();
            }
            return new Parsed(iv, ciphertext);
        } catch (IllegalArgumentException exception) {
            throw invalidCiphertext();
        }
    }

    private static ConnectorCredentialException invalidCiphertext() {
        return new ConnectorCredentialException(
                "CREDENTIAL_CIPHERTEXT_INVALID", "已保存的凭据密文格式无效，请在界面重新保存密码");
    }

    /** AAD 把密文绑定到渠道，跨渠道拷贝密文无法解密。 */
    private static byte[] aad(String channel) {
        return ("connector-credential:" + (channel == null ? "" : channel))
                .getBytes(StandardCharsets.UTF_8);
    }

    private record Parsed(byte[] iv, byte[] ciphertext) {}
}
