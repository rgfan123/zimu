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
        assertThat(WecomTaskId.ofVersion("review", 1234, 0).value()).isEqualTo("review:1234:v0");
        assertThat(WecomTaskId.ofVersion("alert", 5678, 2).value()).isEqualTo("alert:5678:v2");
        assertThat(WecomTaskId.ofGeneration("export", 99, 3).value()).isEqualTo("export:99:g3");
    }

    @Test
    void parseRoundTripsEveryMarker() {
        for (String raw : new String[] {"review:1234:v0", "alert:5678:v2", "export:99:g3"}) {
            assertThat(WecomTaskId.parse(raw)).get().extracting(WecomTaskId::value).isEqualTo(raw);
        }
    }

    @Test
    void malformedCallbackInputYieldsEmptyInsteadOfThrowing() {
        // 回调线程收到的是外部输入：不认识就走「无法识别的卡片」路径，绝不抛
        for (String raw : new String[] {
            null, "", "   ", "review", "review:abc:v1", "review:1:x1", "review:1:v",
            "REVIEW:1:v1".repeat(40), "review:1:v1:extra", "9review:1:v1",
            "review:99999999999999999999:v1"
        }) {
            assertThat(WecomTaskId.parse(raw)).as("raw=%s", raw).isEmpty();
        }
    }

    @Test
    void parseIsCaseInsensitiveAndTrims() {
        assertThat(WecomTaskId.parse("  REVIEW:12:V3  ")).get()
                .extracting(WecomTaskId::value).isEqualTo("review:12:v3");
    }

    @Test
    void staleCardIsDetectedByVersionMismatch() {
        WecomTaskId clicked = WecomTaskId.parse("review:1234:v0").orElseThrow();
        assertThat(clicked.matchesCurrent("review", 1234, 0)).isTrue();
        // 草稿已被处置过一次，旧卡上的 v0 不再是当前版本 → VERSION_CONFLICT
        assertThat(clicked.matchesCurrent("review", 1234, 1)).isFalse();
    }

    @Test
    void crossEntityAndCrossDomainNeverMatch() {
        WecomTaskId clicked = WecomTaskId.parse("review:1234:v0").orElseThrow();
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
