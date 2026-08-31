package cn.zimu.fulfillment.sku;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class JdGoodsVerificationEvidenceTest {

    @Test
    void onlyCurrentRealEnabledGoodsEvidenceIsTrusted() {
        ProviderSku mapping = mapping("JD-GOODS-001", Map.of(
                "jd_goods_verification", Map.of(
                        "goods_no", "JD-GOODS-001",
                        "source", "JD_QUERY_GOODS_INFO",
                        "client_mode", "REAL",
                        "enable_flag", 2,
                        "verified_at", "2026-08-31T00:00:00Z")));

        assertThat(JdGoodsVerificationEvidence.isCurrent(mapping)).isTrue();

        mapping.setProviderSkuCode("JD-GOODS-CHANGED");
        assertThat(JdGoodsVerificationEvidence.isCurrent(mapping)).isFalse();
        mapping.setProviderSkuCode("JD-GOODS-001");
        mapping.setExternalCodes(Map.of(
                "jd_goods_verification", Map.of(
                        "goods_no", "JD-GOODS-001",
                        "source", "JD_QUERY_GOODS_INFO",
                        "client_mode", "MOCK",
                        "enable_flag", 2,
                        "verified_at", "2026-08-31T00:00:00Z")));
        assertThat(JdGoodsVerificationEvidence.isCurrent(mapping)).isFalse();
    }

    @Test
    void onlyRealMatchingEnabledQueryResultCanBeRecorded() {
        JdGoodsReadOnlyVerifier.Verification enabled = JdGoodsReadOnlyVerifier.Verification.found(
                "1000", "request-1", "JD-GOODS-001", "ERP-001", "商品", 2);
        assertThat(JdGoodsVerificationEvidence.canRecord("REAL", "JD-GOODS-001", enabled)).isTrue();
        assertThat(JdGoodsVerificationEvidence.canRecord("MOCK", "JD-GOODS-001", enabled)).isFalse();
        assertThat(JdGoodsVerificationEvidence.canRecord("REAL", "JD-GOODS-OTHER", enabled)).isFalse();
        assertThat(JdGoodsVerificationEvidence.canRecord(
                        "REAL",
                        "JD-GOODS-001",
                        JdGoodsReadOnlyVerifier.Verification.found(
                                "1000", "request-1", "JD-GOODS-001", "ERP-001", "商品", 1)))
                .isFalse();
    }

    private static ProviderSku mapping(String code, Map<String, Object> evidence) {
        ProviderSku mapping = new ProviderSku();
        mapping.setProviderSkuCode(code);
        mapping.setExternalCodes(evidence);
        mapping.setActive(true);
        return mapping;
    }
}
