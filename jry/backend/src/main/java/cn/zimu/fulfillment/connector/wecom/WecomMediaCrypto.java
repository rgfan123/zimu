package cn.zimu.fulfillment.connector.wecom;

import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;

/**
 * 企业微信长连接模式的媒体文件解密：AES-256-CBC、PKCS#7 填充至 32 字节块、IV 取 aeskey 前 16 字节。
 *
 * <p>与旧 HTTP 回调的统一 EncodingAESKey 信封不同：媒体密文是纯 CBC 文件字节，无 16B 随机前缀、
 * 无 4B 长度、无 receiveid，解密后直接是原始媒体字节。aeskey 为 43 字符 base64（解码 32 字节），
 * 每条媒体独立、5 分钟下载期内有效。
 */
@Service
public class WecomMediaCrypto {

    private static final int BLOCK_SIZE = 32;
    private static final int AES_KEY_BYTES = 32;
    private static final int IV_BYTES = 16;
    private static final int AES_KEY_BASE64_CHARS = 43;

    /** 媒体解密失败；不携带任何密钥材料或下载凭据。 */
    public static final class MediaCryptoException extends RuntimeException {
        public MediaCryptoException(String message) {
            super(message);
        }

        public MediaCryptoException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public byte[] decrypt(byte[] ciphertext, String aeskeyBase64) {
        byte[] aesKey = decodeKey(aeskeyBase64);
        if (ciphertext == null || ciphertext.length == 0) {
            throw new MediaCryptoException("媒体密文为空");
        }
        if (ciphertext.length % BLOCK_SIZE != 0) {
            throw new MediaCryptoException("媒体密文长度 " + ciphertext.length + " 不是 32 字节倍数");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    new SecretKeySpec(aesKey, "AES"),
                    new IvParameterSpec(aesKey, 0, IV_BYTES));
            return unpad(cipher.doFinal(ciphertext));
        } catch (GeneralSecurityException exception) {
            throw new MediaCryptoException("媒体解密失败", exception);
        }
    }

    private static byte[] decodeKey(String aeskeyBase64) {
        if (aeskeyBase64 == null || aeskeyBase64.length() != AES_KEY_BASE64_CHARS) {
            throw new MediaCryptoException("aeskey 必须包含 43 个 base64 字符");
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(aeskeyBase64 + "=");
            if (decoded.length != AES_KEY_BYTES) {
                throw new MediaCryptoException("aeskey 必须解码为 32 字节");
            }
            return decoded;
        } catch (IllegalArgumentException exception) {
            throw new MediaCryptoException("aeskey 不是合法 base64", exception);
        }
    }

    private static byte[] unpad(byte[] value) {
        if (value.length == 0) {
            throw new MediaCryptoException("解密结果无 PKCS#7 填充");
        }
        int padding = Byte.toUnsignedInt(value[value.length - 1]);
        if (padding < 1 || padding > BLOCK_SIZE || padding > value.length) {
            throw new MediaCryptoException("解密结果 PKCS#7 填充长度非法");
        }
        for (int index = value.length - padding; index < value.length; index++) {
            if (Byte.toUnsignedInt(value[index]) != padding) {
                throw new MediaCryptoException("解密结果 PKCS#7 填充不一致");
            }
        }
        return Arrays.copyOf(value, value.length - padding);
    }
}
