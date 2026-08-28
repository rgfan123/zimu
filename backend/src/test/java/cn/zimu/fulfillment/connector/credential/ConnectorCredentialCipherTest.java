package cn.zimu.fulfillment.connector.credential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class ConnectorCredentialCipherTest {

    private static final String KEY_A =
            Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
    private static final String KEY_B =
            Base64.getEncoder().encodeToString("fedcba9876543210fedcba9876543210".getBytes(StandardCharsets.UTF_8));

    @Test
    void roundTripsPasswordAndNeverStoresPlaintext() {
        ConnectorCredentialCipher cipher = new ConnectorCredentialCipher(KEY_A);

        String stored = cipher.encrypt("JUFUBAO", "秘密口令-001");

        assertThat(stored).startsWith("v1:");
        assertThat(stored).doesNotContain("秘密口令-001");
        assertThat(cipher.decrypt("JUFUBAO", stored)).isEqualTo("秘密口令-001");
    }

    @Test
    void missingKeyFailsClosedOnBothEncryptAndDecrypt() {
        ConnectorCredentialCipher cipher = new ConnectorCredentialCipher("");

        assertThat(cipher.keyConfigured()).isFalse();
        assertThatThrownBy(() -> cipher.encrypt("JUFUBAO", "x"))
                .isInstanceOf(ConnectorCredentialException.class)
                .satisfies(ex -> assertThat(((ConnectorCredentialException) ex).businessCode())
                        .isEqualTo("CREDENTIAL_KEY_MISSING"))
                .hasMessageContaining("CONNECTOR_CREDENTIAL_KEY");
        assertThatThrownBy(() -> cipher.decrypt("JUFUBAO", "v1:AAAA:BBBB"))
                .isInstanceOf(ConnectorCredentialException.class)
                .satisfies(ex -> assertThat(((ConnectorCredentialException) ex).businessCode())
                        .isEqualTo("CREDENTIAL_KEY_MISSING"));
    }

    @Test
    void invalidKeyDoesNotCrashConstructionButFailsClosedOnUse() {
        // 密钥配错（坏 Base64 / 长度不对）不应把整个应用炸掉，但任何使用都必须报清楚。
        ConnectorCredentialCipher badBase64 = new ConnectorCredentialCipher("!!!not-base64!!!");
        ConnectorCredentialCipher badLength = new ConnectorCredentialCipher(
                Base64.getEncoder().encodeToString("short".getBytes(StandardCharsets.UTF_8)));

        for (ConnectorCredentialCipher cipher : new ConnectorCredentialCipher[] {badBase64, badLength}) {
            assertThat(cipher.keyConfigured()).isFalse();
            assertThatThrownBy(() -> cipher.encrypt("JUFUBAO", "x"))
                    .isInstanceOf(ConnectorCredentialException.class)
                    .satisfies(ex -> assertThat(((ConnectorCredentialException) ex).businessCode())
                            .isEqualTo("CREDENTIAL_KEY_INVALID"));
        }
    }

    @Test
    void rotatedKeyIsReportedAsDecryptFailureAskingForReentry() {
        String stored = new ConnectorCredentialCipher(KEY_A).encrypt("JUFUBAO", "口令");

        assertThatThrownBy(() -> new ConnectorCredentialCipher(KEY_B).decrypt("JUFUBAO", stored))
                .isInstanceOf(ConnectorCredentialException.class)
                .satisfies(ex -> assertThat(((ConnectorCredentialException) ex).businessCode())
                        .isEqualTo("CREDENTIAL_DECRYPT_FAILED"))
                .hasMessageContaining("重新保存");
    }

    @Test
    void ciphertextIsBoundToItsChannelByAad() {
        ConnectorCredentialCipher cipher = new ConnectorCredentialCipher(KEY_A);
        String stored = cipher.encrypt("JUFUBAO", "口令");

        assertThatThrownBy(() -> cipher.decrypt("CAISHIXIAN", stored))
                .isInstanceOf(ConnectorCredentialException.class)
                .satisfies(ex -> assertThat(((ConnectorCredentialException) ex).businessCode())
                        .isEqualTo("CREDENTIAL_DECRYPT_FAILED"));
    }

    @Test
    void malformedCiphertextIsRejectedWithDistinctBusinessCode() {
        ConnectorCredentialCipher cipher = new ConnectorCredentialCipher(KEY_A);

        for (String malformed : new String[] {"", "plaintext-residue", "v1:only-one-part", "v1:x:y"}) {
            assertThatThrownBy(() -> cipher.decrypt("JUFUBAO", malformed))
                    .isInstanceOf(ConnectorCredentialException.class)
                    .satisfies(ex -> assertThat(((ConnectorCredentialException) ex).businessCode())
                            .isEqualTo("CREDENTIAL_CIPHERTEXT_INVALID"));
        }
    }
}
