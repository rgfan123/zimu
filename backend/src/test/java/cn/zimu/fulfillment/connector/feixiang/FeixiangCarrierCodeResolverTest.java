package cn.zimu.fulfillment.connector.feixiang;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

/**
 * 承运商显示名到平台代码的映射。
 *
 * <p>核心安全属性：<b>绝不回落成显示名</b>。carrier_mappings 的值是给回填 CSV 用的中文，
 * 把它当 express_code 发出去，平台看不懂；这里任何一种未命中都必须判「未映射」并阻断。</p>
 */
class FeixiangCarrierCodeResolverTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void resolvesDisplayNameToPlatformCodeThroughInternalCarrierCode() {
        FeixiangCarrierCodeResolver.Resolution resolution = FeixiangCarrierCodeResolver.resolveFrom(
                config("{\"carrier_mappings\":{\"JD\":\"京东物流\"},"
                        + "\"carrier_api_codes\":{\"JD\":\"jingdong\"}}"),
                "京东物流");

        assertThat(resolution.resolved()).isTrue();
        assertThat(resolution.expressCode()).isEqualTo("jingdong");
    }

    @Test
    void missingApiCodeTableBlocksInsteadOfFallingBackToDisplayName() {
        FeixiangCarrierCodeResolver.Resolution resolution = FeixiangCarrierCodeResolver.resolveFrom(
                config("{\"carrier_mappings\":{\"JD\":\"京东物流\"}}"), "京东物流");

        assertThat(resolution.resolved()).isFalse();
        assertThat(resolution.businessCode()).isEqualTo("FEIXIANG_CARRIER_API_CODE_MISSING");
    }

    @Test
    void apiCodeNotCoveringThisCarrierBlocks() {
        FeixiangCarrierCodeResolver.Resolution resolution = FeixiangCarrierCodeResolver.resolveFrom(
                config("{\"carrier_mappings\":{\"JD\":\"京东物流\",\"SF\":\"顺丰\"},"
                        + "\"carrier_api_codes\":{\"JD\":\"jingdong\"}}"),
                "顺丰");

        assertThat(resolution.resolved()).isFalse();
        assertThat(resolution.businessCode()).isEqualTo("FEIXIANG_CARRIER_API_CODE_MISSING");
    }

    @Test
    void chineseValueMisconfiguredAsApiCodeIsRejectedBeforeAnyHttp() {
        FeixiangCarrierCodeResolver.Resolution resolution = FeixiangCarrierCodeResolver.resolveFrom(
                config("{\"carrier_mappings\":{\"JD\":\"京东物流\"},"
                        + "\"carrier_api_codes\":{\"JD\":\"京东物流\"}}"),
                "京东物流");

        assertThat(resolution.resolved()).isFalse();
        assertThat(resolution.businessCode()).isEqualTo("FEIXIANG_CARRIER_API_CODE_INVALID");
    }

    @Test
    void unknownDisplayNameIsUnmapped() {
        FeixiangCarrierCodeResolver.Resolution resolution = FeixiangCarrierCodeResolver.resolveFrom(
                config("{\"carrier_mappings\":{\"JD\":\"京东物流\"},"
                        + "\"carrier_api_codes\":{\"JD\":\"jingdong\"}}"),
                "德邦");

        assertThat(resolution.resolved()).isFalse();
        assertThat(resolution.businessCode()).isEqualTo("FEIXIANG_CARRIER_UNMAPPED");
    }

    @Test
    void ambiguousReverseLookupIsRefusedRatherThanPickingOne() {
        FeixiangCarrierCodeResolver.Resolution resolution = FeixiangCarrierCodeResolver.resolveFrom(
                config("{\"carrier_mappings\":{\"JD\":\"京东物流\",\"JDX\":\"京东物流\"},"
                        + "\"carrier_api_codes\":{\"JD\":\"jingdong\",\"JDX\":\"jingdongx\"}}"),
                "京东物流");

        assertThat(resolution.resolved()).isFalse();
        assertThat(resolution.businessCode()).isEqualTo("FEIXIANG_CARRIER_AMBIGUOUS");
    }

    private static JsonNode config(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
