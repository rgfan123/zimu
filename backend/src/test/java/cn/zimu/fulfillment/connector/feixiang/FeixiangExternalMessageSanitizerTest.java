package cn.zimu.fulfillment.connector.feixiang;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * 平台原文落库前的净化。
 *
 * <p>{@code SourceSyncStore} 会把 message 原样写进 {@code shipment_syncs.last_error_message}，
 * 而按键名脱敏的 {@code SecretRedactor} 管不到自由文本——飞象会话失效时回的是整页登录 HTML。</p>
 */
class FeixiangExternalMessageSanitizerTest {

    @Test
    void collapsesLineBreaksAndTrimsEdges() {
        assertThat(FeixiangExternalMessageSanitizer.sanitize("  运单被拒绝\n请复核  ", "兜底"))
                .isEqualTo("运单被拒绝 请复核");
    }

    @Test
    void redactsAnythingThatLooksLikeMarkupOrCredentials() {
        assertThat(FeixiangExternalMessageSanitizer.sanitize("<html>login</html>", "兜底"))
                .isEqualTo("兜底");
        assertThat(FeixiangExternalMessageSanitizer.sanitize("fxqf_sess=abc", "兜底"))
                .isEqualTo("兜底");
        assertThat(FeixiangExternalMessageSanitizer.sanitize("Set-Cookie: a=b", "兜底"))
                .isEqualTo("兜底");
        assertThat(FeixiangExternalMessageSanitizer.sanitize("密码错误", "兜底"))
                .isEqualTo("兜底");
    }

    @Test
    void blankInputFallsBackWithoutThrowing() {
        assertThat(FeixiangExternalMessageSanitizer.sanitize(null, "兜底")).isEqualTo("兜底");
        assertThat(FeixiangExternalMessageSanitizer.sanitize("   ", "兜底")).isEqualTo("兜底");
        assertThat(FeixiangExternalMessageSanitizer.sanitize("x", null)).isEqualTo("x");
    }

    @Test
    void truncatesOverlongPlatformText() {
        String sanitized = FeixiangExternalMessageSanitizer.sanitize("啊".repeat(500), "兜底");

        assertThat(sanitized).hasSize(200);
    }
}
