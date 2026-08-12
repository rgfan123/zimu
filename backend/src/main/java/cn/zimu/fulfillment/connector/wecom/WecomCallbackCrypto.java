package cn.zimu.fulfillment.connector.wecom;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Official enterprise WeChat callback envelope algorithm for an internal intelligent bot. */
final class WecomCallbackCrypto {

    private static final int RANDOM_PREFIX_BYTES = 16;
    private static final int LENGTH_BYTES = 4;
    private static final int PKCS7_BLOCK_SIZE = 32;

    private final String token;
    private final byte[] aesKey;
    private final SecureRandom secureRandom;

    WecomCallbackCrypto(String token, String encodingAesKey) {
        this(token, encodingAesKey, new SecureRandom());
    }

    WecomCallbackCrypto(String token, String encodingAesKey, SecureRandom secureRandom) {
        this.token = token;
        this.aesKey = decodeKey(encodingAesKey);
        this.secureRandom = secureRandom;
    }

    boolean signatureMatches(String expected, String timestamp, String nonce, String encrypted) {
        if (expected == null || timestamp == null || nonce == null || encrypted == null) {
            return false;
        }
        byte[] actualBytes = signature(timestamp, nonce, encrypted).getBytes(StandardCharsets.US_ASCII);
        byte[] expectedBytes = expected.getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(actualBytes, expectedBytes);
    }

    String signature(String timestamp, String nonce, String encrypted) {
        try {
            List<String> parts = new ArrayList<>(List.of(token, timestamp, nonce, encrypted));
            parts.sort(String::compareTo);
            byte[] digest = MessageDigest.getInstance("SHA-1")
                    .digest(String.join("", parts).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (GeneralSecurityException ex) {
            throw new CryptoException("SHA-1 unavailable", ex);
        }
    }

    String decrypt(String encrypted) {
        try {
            byte[] padded = crypt(Cipher.DECRYPT_MODE, Base64.getDecoder().decode(encrypted));
            byte[] plain = unpad(padded);
            if (plain.length < RANDOM_PREFIX_BYTES + LENGTH_BYTES) {
                throw new CryptoException("decrypted callback is too short");
            }
            ByteBuffer buffer = ByteBuffer.wrap(plain);
            buffer.position(RANDOM_PREFIX_BYTES);
            int messageLength = buffer.getInt();
            if (messageLength < 0 || messageLength > buffer.remaining()) {
                throw new CryptoException("decrypted callback has an invalid message length");
            }
            byte[] message = new byte[messageLength];
            buffer.get(message);
            // Internal intelligent bots use an empty ReceiveId. Refuse a payload for another receiver.
            if (buffer.hasRemaining()) {
                throw new CryptoException("decrypted callback has an unexpected receive id");
            }
            return new String(message, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException | GeneralSecurityException ex) {
            throw new CryptoException("callback decryption failed", ex);
        }
    }

    String encrypt(String plaintext) {
        try {
            byte[] message = plaintext.getBytes(StandardCharsets.UTF_8);
            byte[] randomPrefix = new byte[RANDOM_PREFIX_BYTES];
            secureRandom.nextBytes(randomPrefix);
            ByteBuffer raw = ByteBuffer.allocate(RANDOM_PREFIX_BYTES + LENGTH_BYTES + message.length);
            raw.put(randomPrefix);
            raw.putInt(message.length);
            raw.put(message);
            return Base64.getEncoder().encodeToString(crypt(Cipher.ENCRYPT_MODE, pad(raw.array())));
        } catch (GeneralSecurityException ex) {
            throw new CryptoException("callback encryption failed", ex);
        }
    }

    private byte[] crypt(int mode, byte[] value) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(mode, new SecretKeySpec(aesKey, "AES"), new IvParameterSpec(aesKey, 0, 16));
        return cipher.doFinal(value);
    }

    private static byte[] decodeKey(String encodingAesKey) {
        try {
            if (encodingAesKey == null || encodingAesKey.length() != 43) {
                throw new CryptoException("EncodingAESKey must contain 43 characters");
            }
            byte[] decoded = Base64.getDecoder().decode(encodingAesKey + "=");
            if (decoded.length != 32) {
                throw new CryptoException("EncodingAESKey must decode to 32 bytes");
            }
            return decoded;
        } catch (IllegalArgumentException ex) {
            throw new CryptoException("EncodingAESKey is not valid base64", ex);
        }
    }

    private static byte[] pad(byte[] value) {
        int padding = PKCS7_BLOCK_SIZE - value.length % PKCS7_BLOCK_SIZE;
        byte[] result = Arrays.copyOf(value, value.length + padding);
        Arrays.fill(result, value.length, result.length, (byte) padding);
        return result;
    }

    private static byte[] unpad(byte[] value) {
        if (value.length == 0) {
            throw new CryptoException("decrypted callback has no padding");
        }
        int padding = Byte.toUnsignedInt(value[value.length - 1]);
        if (padding < 1 || padding > PKCS7_BLOCK_SIZE || padding > value.length) {
            throw new CryptoException("decrypted callback has invalid padding");
        }
        for (int index = value.length - padding; index < value.length; index++) {
            if (Byte.toUnsignedInt(value[index]) != padding) {
                throw new CryptoException("decrypted callback has inconsistent padding");
            }
        }
        return Arrays.copyOf(value, value.length - padding);
    }

    static final class CryptoException extends RuntimeException {
        CryptoException(String message) {
            super(message);
        }

        CryptoException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
