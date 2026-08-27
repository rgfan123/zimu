package cn.zimu.fulfillment.connector.wecom.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * task_id 是版本断言（wecom-card-review §B）：点旧卡必须能被识别为过期，
 * 且回调里的非法输入绝不能把回调线程炸掉。
 */
class WecomTaskIdTest {

    @Test
    void versionAndGenerationMarkersRenderPerSpec() {
        assertThat(WecomTaskId.ofVersion("review", 1234, 0).value()).isEqualTo("review_1234_v0");
        assertThat(WecomTaskId.ofVersion("alert", 5678, 2).value()).isEqualTo("alert_5678_v2");
        assertThat(WecomTaskId.ofGeneration("export", 99, 3).value()).isEqualTo("export_99_g3");
    }

    @Test
    void persistedDeliveryAddsAnOpaqueAuthorizationReferenceWithoutLosingVersion() {
        WecomTaskId persisted = WecomTaskId.ofVersion("followup-draft", 42, 3)
                .authorize("0123456789abcdef0123456789abcdef");

        assertThat(persisted.value())
                .isEqualTo("followup-draft_42_v3_0123456789abcdef0123456789abcdef");
        assertThat(WecomTaskId.parse(persisted.value()))
                .get()
                .satisfies(parsed -> {
                    assertThat(parsed.entityId()).isEqualTo(42);
                    assertThat(parsed.version()).isEqualTo(3);
                    assertThat(parsed.authorizationRef())
                            .isEqualTo("0123456789abcdef0123456789abcdef");
                });
    }

    @Test
    void parseRoundTripsEveryMarker() {
        for (String raw : new String[] {"review_1234_v0", "alert_5678_v2", "export_99_g3"}) {
            assertThat(WecomTaskId.parse(raw)).get().extracting(WecomTaskId::value).isEqualTo(raw);
        }
    }

    @Test
    void malformedCallbackInputYieldsEmptyInsteadOfThrowing() {
        // 回调线程收到的是外部输入：不认识就走「无法识别的卡片」路径，绝不抛
        for (String raw : new String[] {
            null, "", "   ", "review", "review_abc_v1", "review_1_x1", "review_1_v",
            "REVIEW_1_v1".repeat(40), "review_1_v1_extra", "9review_1_v1",
            "review_99999999999999999999_v1",
            // 冒号是 aibot 非法字符（errcode 42014）：旧格式必须解析不出来，
            // 否则改回冒号时测试会静默放行，而线上表现是「按钮卡一张都发不出去」
            "review:1234:v0"
        }) {
            assertThat(WecomTaskId.parse(raw)).as("raw=%s", raw).isEmpty();
        }
    }

    @Test
    void parseIsCaseInsensitiveAndTrims() {
        assertThat(WecomTaskId.parse("  REVIEW_12_V3  ")).get()
                .extracting(WecomTaskId::value).isEqualTo("review_12_v3");
    }

    @Test
    void staleCardIsDetectedByVersionMismatch() {
        WecomTaskId clicked = WecomTaskId.parse("review_1234_v0").orElseThrow();
        assertThat(clicked.matchesCurrent("review", 1234, 0)).isTrue();
        // 草稿已被处置过一次，旧卡上的 v0 不再是当前版本 → VERSION_CONFLICT
        assertThat(clicked.matchesCurrent("review", 1234, 1)).isFalse();
    }

    @Test
    void crossEntityAndCrossDomainNeverMatch() {
        WecomTaskId clicked = WecomTaskId.parse("review_1234_v0").orElseThrow();
        assertThat(clicked.matchesCurrent("review", 9999, 0)).isFalse();
        assertThat(clicked.matchesCurrent("alert", 1234, 0)).isFalse();
    }

    @Test
    void illegalDomainOrNegativeValuesAreRejectedAtConstruction() {
        assertThatThrownBy(() -> WecomTaskId.ofVersion("Review", 1, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WecomTaskId.ofVersion("review", -1, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WecomTaskId.ofVersion("review", 1, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
