package cn.zimu.fulfillment.connector.wecom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.zimu.fulfillment.connector.wecom.WecomMediaCrypto.MediaCryptoException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/**
 * 长连接媒体解密规范：AES-256-CBC、PKCS#7 填充至 32 字节块、IV=aeskey 前 16 字节、无信封。
 *
 * <p>样本按规范用 JDK Cipher 独立构造（不调用被测类），验证解密正确性、错误密钥/非法填充/
 * 截断/非法 aeskey 的报错。
 */
class WecomMediaCryptoTest {

    /** 43 字符 base64，解码为 32 字节密钥（"0123456789abcdef0123456789abcdef" 的 UTF-8 字节）。 */
    private static final String AES_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY";
    /** 同样的 43 字符长度但不同的密钥，用于错误密钥样本。 */
    private static final String WRONG_AES_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWX";

    private final WecomMediaCrypto crypto = new WecomMediaCrypto();

    @Test
    void decryptsPkcs7PaddedMediaCiphertext() {
        byte[] plaintext = "企微长连接媒体证据-纯CBC无信封-".getBytes(StandardCharsets.UTF_8);
        byte[] ciphertext = encrypt(plaintext, AES_KEY);

        assertThat(ciphertext.length % 32).isZero();
        assertThat(crypto.decrypt(ciphertext, AES_KEY)).isEqualTo(plaintext);
    }

    @Test
    void decryptsBinaryPayloadWhosePlaintextLengthIsBlockAligned() {
        // 明文恰好 32 字节倍数：PKCS#7 会补满一个整块，解密后应剥掉
        byte[] plaintext = new byte[32];
        for (int i = 0; i < plaintext.length; i++) {
            plaintext[i] = (byte) i;
        }
        byte[] ciphertext = encrypt(plaintext, AES_KEY);

        assertThat(ciphertext.length).isEqualTo(64);
        assertThat(crypto.decrypt(ciphertext, AES_KEY)).isEqualTo(plaintext);
    }

    @Test
    void rejectsTruncatedCiphertext() {
        byte[] ciphertext = encrypt("被截断的媒体密文样本".getBytes(StandardCharsets.UTF_8), AES_KEY);
        byte[] truncated = Arrays.copyOf(ciphertext, ciphertext.length - 1);
        assertThat(truncated.length % 32).isNotZero();

        assertThatThrownBy(() -> crypto.decrypt(truncated, AES_KEY))
                .isInstanceOf(MediaCryptoException.class)
                .hasMessageContaining("32 字节倍数");
    }

    @Test
    void rejectsEmptyCiphertext() {
        assertThatThrownBy(() -> crypto.decrypt(new byte[0], AES_KEY))
                .isInstanceOf(MediaCryptoException.class)
                .hasMessageContaining("为空");
    }

    @Test
    void rejectsWrongAesKey() {
        byte[] ciphertext = encrypt("用正确密钥加密的密文".getBytes(StandardCharsets.UTF_8), AES_KEY);

        assertThatThrownBy(() -> crypto.decrypt(ciphertext, WRONG_AES_KEY))
                .isInstanceOf(MediaCryptoException.class);
    }

    @Test
    void rejectsCiphertextWithoutValidPkcs7Padding() {
        // 无填充密文：明文最后字节为 0，解密后 padding 值 0 非法
        byte[] unpadded = new byte[64];
        Cipher cipher = cipher(Cipher.ENCRYPT_MODE, AES_KEY);
        byte[] ciphertext;
        try {
            ciphertext = cipher.doFinal(unpadded);
        } catch (Exception exception) {
            throw new IllegalStateException("样本构造失败", exception);
        }

        assertThatThrownBy(() -> crypto.decrypt(ciphertext, AES_KEY))
                .isInstanceOf(MediaCryptoException.class)
                .hasMessageContaining("填充");
    }

    @Test
    void rejectsAesKeyWithWrongLength() {
        assertThatThrownBy(() -> crypto.decrypt(new byte[32], "too-short"))
                .isInstanceOf(MediaCryptoException.class)
                .hasMessageContaining("43");
    }

    @Test
    void rejectsAesKeyThatIsNotValidBase64() {
        assertThatThrownBy(() -> crypto.decrypt(new byte[32], "!".repeat(43)))
                .isInstanceOf(MediaCryptoException.class)
                .hasMessageContaining("base64");
    }

    // ------------------------------------------------------------------
    // 样本构造：按规范独立实现（AES/CBC/NoPadding + PKCS#7 32 字节块 + IV=aeskey 前 16 字节）
    // ------------------------------------------------------------------

    private static byte[] encrypt(byte[] plaintext, String aeskeyBase64) {
        byte[] padded = pad(plaintext);
        Cipher cipher = cipher(Cipher.ENCRYPT_MODE, aeskeyBase64);
        try {
            return cipher.doFinal(padded);
        } catch (Exception exception) {
            throw new IllegalStateException("样本加密失败", exception);
        }
    }

    private static Cipher cipher(int mode, String aeskeyBase64) {
        byte[] key = Base64.getDecoder().decode(aeskeyBase64 + "=");
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(mode, new SecretKeySpec(key, "AES"), new IvParameterSpec(key, 0, 16));
            return cipher;
        } catch (Exception exception) {
            throw new IllegalStateException("样本 Cipher 初始化失败", exception);
        }
    }

    private static byte[] pad(byte[] value) {
        int padding = 32 - value.length % 32;
        byte[] result = Arrays.copyOf(value, value.length + padding);
        Arrays.fill(result, value.length, result.length, (byte) padding);
        return result;
    }
}
