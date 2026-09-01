package cn.zimu.fulfillment.fulfillment;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zimu.fulfillment.connector.jd.JdResult;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class JdOutboundReadbackVerifierTest {

    private static final JdOutboundReadbackVerifier.Expected EXPECTED =
            new JdOutboundReadbackVerifier.Expected(
                    "ZIMU-SO-20260831-000000000001-ABCDEF12",
                    "ESL00000025540305777",
                    "PIN-API-001",
                    "OWNER-API-001",
                    "WH-API-001",
                    List.of(
                            new JdOutboundReadbackVerifier.Cargo("1", "JD-SKU-BEEF-BRISKET", 2),
                            new JdOutboundReadbackVerifier.Cargo("2", "JD-SKU-SIRLOIN", 4)),
                    true);

    @Test
    void exactTenantWarehouseReferencesAndCargoMatchEvenWhenRemoteCargoOrderDiffers() {
        JdOutboundReadbackVerifier.Verification verification = JdOutboundReadbackVerifier.verify(
                EXPECTED,
                success(response(
                        "PIN-API-001",
                        "OWNER-API-001",
                        "WH-API-001",
                        List.of(cargo("2", "JD-SKU-SIRLOIN", 4), cargo("1", "JD-SKU-BEEF-BRISKET", 2)))));

        assertThat(verification.status()).isEqualTo(JdOutboundReadbackVerifier.Status.MATCHED);
        assertThat(verification.deliveryNo()).isEqualTo("ESL00000025540305777");
        assertThat(verification.mismatchFields()).isEmpty();
    }

    @Test
    void outboundPlanQuantityUsesInt64AggregationWithoutOverflow() {
        JdOutboundReadbackVerifier.Expected large = new JdOutboundReadbackVerifier.Expected(
                "ZIMU-SO-20260831-000000000002-ABCDEF12",
                "ESL00000025540305778",
                "PIN-API-001",
                "OWNER-API-001",
                "WH-API-001",
                List.of(
                        new JdOutboundReadbackVerifier.Cargo("1", "JD-SKU-A", 1_500_000_000),
                        new JdOutboundReadbackVerifier.Cargo("2", "JD-SKU-B", 1_500_000_000)),
                true);

        assertThat(ShipmentJdOutboundService.planQuantity(large)).isEqualTo(3_000_000_000L);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("mismatches")
    void everyFrozenIdentityDimensionMustMatch(
            String ignored,
            Map<String, Object> response,
            String expectedMismatchField) {
        JdOutboundReadbackVerifier.Verification verification =
                JdOutboundReadbackVerifier.verify(EXPECTED, success(response));

        assertThat(verification.status()).isEqualTo(JdOutboundReadbackVerifier.Status.MISMATCHED);
        assertThat(verification.mismatchFields()).contains(expectedMismatchField);
    }

    @Test
    void missingStructuredTenantResponseIsMalformedInsteadOfImplicitlyMatchingQueryInput() {
        Map<String, Object> response = response(
                "PIN-API-001",
                "OWNER-API-001",
                "WH-API-001",
                expectedCargos());
        response.remove("customerInfo");

        assertThat(JdOutboundReadbackVerifier.verify(EXPECTED, success(response)).status())
                .isEqualTo(JdOutboundReadbackVerifier.Status.MALFORMED);
    }

    @Test
    void optionalPinAccountIsAnOperatorFactAndDoesNotReplaceFrozenQueryPinAuthority() {
        Map<String, Object> response = response(
                "SOME-JD-ORDER-OPERATOR",
                "OWNER-API-001",
                "WH-API-001",
                expectedCargos());

        assertThat(JdOutboundReadbackVerifier.verify(EXPECTED, success(response)).status())
                .isEqualTo(JdOutboundReadbackVerifier.Status.MATCHED);
        response.remove("pinAccount");
        assertThat(JdOutboundReadbackVerifier.verify(EXPECTED, success(response)).status())
                .isEqualTo(JdOutboundReadbackVerifier.Status.MATCHED);
    }

    @Test
    void failedQueryNeverConfirmsAnExternalWrite() {
        JdResult failed = new JdResult(false, "2342", "not found", "request-2342", null);

        assertThat(JdOutboundReadbackVerifier.verify(EXPECTED, failed).status())
                .isEqualTo(JdOutboundReadbackVerifier.Status.QUERY_FAILED);
    }

    private static Stream<Arguments> mismatches() {
        Map<String, Object> wrongErp = response("PIN-API-001", "OWNER-API-001", "WH-API-001", expectedCargos());
        wrongErp.put("erpDeliveryNo", "ZIMU-SO-ANOTHER");
        Map<String, Object> wrongDelivery = response("PIN-API-001", "OWNER-API-001", "WH-API-001", expectedCargos());
        wrongDelivery.put("deliveryNo", "ESL-ANOTHER");
        return Stream.of(
                Arguments.of("merchant reference", wrongErp, "erp_delivery_no"),
                Arguments.of("JD reference", wrongDelivery, "jd_delivery_no"),
                Arguments.of("owner", response("PIN-API-001", "OWNER-ANOTHER", "WH-API-001", expectedCargos()), "owner_no"),
                Arguments.of("warehouse", response("PIN-API-001", "OWNER-API-001", "WH-ANOTHER", expectedCargos()), "warehouse_no"),
                Arguments.of("goodsNo", response(
                        "PIN-API-001", "OWNER-API-001", "WH-API-001",
                        List.of(cargo("1", "JD-SKU-OLD", 2), cargo("2", "JD-SKU-SIRLOIN", 4))), "cargo"),
                Arguments.of("orderLine", response(
                        "PIN-API-001", "OWNER-API-001", "WH-API-001",
                        List.of(cargo("9", "JD-SKU-BEEF-BRISKET", 2), cargo("2", "JD-SKU-SIRLOIN", 4))), "cargo"),
                Arguments.of("planQuantity", response(
                        "PIN-API-001", "OWNER-API-001", "WH-API-001",
                        List.of(cargo("1", "JD-SKU-BEEF-BRISKET", 1), cargo("2", "JD-SKU-SIRLOIN", 4))), "cargo"),
                Arguments.of("extra cargo", response(
                        "PIN-API-001", "OWNER-API-001", "WH-API-001",
                        List.of(
                                cargo("1", "JD-SKU-BEEF-BRISKET", 2),
                                cargo("2", "JD-SKU-SIRLOIN", 4),
                                cargo("3", "JD-SKU-OLD", 1))), "cargo"));
    }

    private static Map<String, Object> response(
            String pin,
            String ownerNo,
            String warehouseNo,
            List<Map<String, Object>> cargos) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("erpDeliveryNo", EXPECTED.erpDeliveryNo());
        result.put("deliveryNo", EXPECTED.jdDeliveryNo());
        result.put("pinAccount", pin);
        result.put("customerInfo", Map.of("ownerNo", ownerNo));
        result.put("warehouseNo", warehouseNo);
        result.put("deliveryItemList", cargos);
        return result;
    }

    private static List<Map<String, Object>> expectedCargos() {
        return List.of(
                cargo("1", "JD-SKU-BEEF-BRISKET", 2),
                cargo("2", "JD-SKU-SIRLOIN", 4));
    }

    private static Map<String, Object> cargo(String orderLine, String goodsNo, int quantity) {
        return Map.of("orderLine", orderLine, "goodsNo", goodsNo, "planQuantity", quantity);
    }

    private static JdResult success(Map<String, Object> data) {
        return new JdResult(true, "1000", "success", "request-success", data);
    }
}
