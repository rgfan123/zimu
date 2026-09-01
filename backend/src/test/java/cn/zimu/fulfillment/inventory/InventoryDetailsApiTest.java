package cn.zimu.fulfillment.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "app.message-worker.enabled=false",
            "app.jd.client-mode=MOCK"
        })
class InventoryDetailsApiTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    @Test
    void exposesCachedObservationAndOnlyIntegratedProviderCapabilities() {
        long providerId = createProvider("JDDETAIL", "京东明细仓", "JD_WAREHOUSE");
        long skuId = createSku(providerId, "明细规格");
        jdbc.update(
                """
                INSERT INTO app.provider_skus
                    (fulfillment_provider_id, sku_id, provider_sku_code, external_codes)
                VALUES (?, ?, 'JD-GOODS-DETAIL', '{"private_alias":"never-leak"}'::jsonb)
                """,
                providerId,
                skuId);
        jdbc.update(
                """
                INSERT INTO app.provider_stock_snapshots
                    (fulfillment_provider_id, sku_id, warehouse_code, stock_num, usable_num,
                     quantity_unit, source_type, synced_at, source_ref, raw_payload)
                VALUES (?, ?, 'WH-DETAIL', 8.000, 5.000,
                        'JD_PIECE', 'JD_ISC_QUERY_STOCK', ?::timestamptz,
                        'private-source-ref', '{"secret":"never-leak"}'::jsonb)
                """,
                providerId,
                skuId,
                Instant.now().minusSeconds(1_800).toString());

        ResponseEntity<Map> response = http.getForEntity(
                "/api/v1/inventory/details?provider_id=" + providerId
                        + "&sku_id=" + skuId + "&warehouse_code=WH-DETAIL",
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("query_time")).isNotNull();
        assertThat(body.get("freshness_policy")).isEqualTo("PT15M");

        Map<String, Object> context = object(body, "context");
        assertThat(context)
                .containsEntry("provider_id", String.valueOf(providerId))
                .containsEntry("provider_code", "JDDETAIL")
                .containsEntry("provider_name", "京东明细仓")
                .containsEntry("provider_type", "JD_WAREHOUSE")
                .containsEntry("sku_id", String.valueOf(skuId))
                .containsEntry("provider_sku_code", "JD-GOODS-DETAIL")
                .containsEntry("warehouse_code", "WH-DETAIL");

        Map<String, Object> observation = object(body, "observation");
        assertThat(observation)
                .containsEntry("observation_status", "OBSERVED")
                .containsEntry("total_quantity", 8)
                .containsEntry("available_quantity", 5)
                .containsEntry("unavailable_quantity", 3)
                .containsEntry("quantity_unit", "JD_PIECE")
                .containsEntry("source_type", "JD_ISC_QUERY_STOCK")
                .containsEntry("data_mode", "CACHED_SNAPSHOT")
                .containsEntry("freshness_status", "STALE");
        assertThat(observation.get("observed_at")).isNotNull();
        assertThat(observation.get("expires_at")).isNotNull();

        List<Map<String, Object>> capabilities = capabilities(body);
        assertThat(capabilities).extracting(item -> item.get("group"))
                .containsExactly("BATCH_AND_SHELF_LIFE", "INVENTORY_FLOW", "SERIAL_NUMBER");
        assertThat(capabilities).allSatisfy(capability -> {
            assertThat(capability)
                    .containsEntry("integration_status", "INTEGRATED")
                    .containsEntry("runtime_mode", "MOCK")
                    .containsEntry("source_type", "JD_ISC_READ_ONLY");
            assertThat((List<?>) capability.get("tools")).isNotEmpty();
        });

        String publicPayload = String.valueOf(body);
        assertThat(publicPayload).doesNotContain(
                "private-source-ref", "never-leak", "raw_payload", "source_ref", "private_alias");
    }

    @Test
    void marksUnsupportedProviderCapabilitiesAndMissingObservationsWithoutInventingData() {
        long providerId = createProvider("TPDETAIL", "第三方明细仓", "THIRD_PARTY");
        long skuId = createSku(providerId, "未接入规格");

        ResponseEntity<Map> response = http.getForEntity(
                "/api/v1/inventory/details?provider_id=" + providerId
                        + "&sku_id=" + skuId + "&warehouse_code=WH-NOT-OBSERVED",
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(object(body, "context"))
                .containsEntry("provider_type", "THIRD_PARTY")
                .containsEntry("warehouse_code", "WH-NOT-OBSERVED")
                .containsEntry("provider_sku_code", null);
        assertThat(object(body, "observation"))
                .containsEntry("observation_status", "NOT_OBSERVED")
                .containsEntry("total_quantity", null)
                .containsEntry("available_quantity", null)
                .containsEntry("unavailable_quantity", null)
                .containsEntry("observed_at", null)
                .containsEntry("expires_at", null)
                .containsEntry("freshness_status", "NOT_OBSERVED")
                .containsEntry("source_type", null)
                .containsEntry("data_mode", "NO_OBSERVATION");
        assertThat(capabilities(body)).allSatisfy(capability -> {
            assertThat(capability.get("integration_status")).isEqualTo("NOT_INTEGRATED");
            assertThat(capability.get("runtime_mode")).isEqualTo("NOT_APPLICABLE");
            assertThat(capability.get("source_type")).isNull();
            assertThat((List<?>) capability.get("tools")).isEmpty();
        });
        assertThat(String.valueOf(body)).doesNotContain("在途", "预留");
    }

    @Test
    void failsClosedWhenTheSkuDoesNotBelongToTheRequestedProvider() {
        long requestedProviderId = createProvider("DETAILA", "明细履约方 A", "THIRD_PARTY");
        long actualProviderId = createProvider("DETAILB", "明细履约方 B", "THIRD_PARTY");
        long skuId = createSku(actualProviderId, "另一履约方规格");

        ResponseEntity<Map> response = http.getForEntity(
                "/api/v1/inventory/details?provider_id=" + requestedProviderId + "&sku_id=" + skuId,
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        Map<String, Object> error = response.getBody();
        assertThat(error)
                .containsEntry("business_code", "NOT_FOUND")
                .doesNotContainKeys("context", "observation", "capabilities");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> capabilities(Map<String, Object> body) {
        return (List<Map<String, Object>>) body.get("capabilities");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Map<String, Object> body, String key) {
        return (Map<String, Object>) body.get(key);
    }

    private long createProvider(String code, String name, String type) {
        return jdbc.queryForObject(
                """
                INSERT INTO app.fulfillment_providers
                    (provider_code, provider_name, provider_type, inventory_managed_by_us, tracking_sla_minutes)
                VALUES (?, ?, ?, false, 1440)
                RETURNING id
                """,
                Long.class,
                code,
                name,
                type);
    }

    private long createSku(long providerId, String specification) {
        return jdbc.queryForObject(
                """
                INSERT INTO app.skus (product_id, fulfillment_provider_id, specification, unit)
                SELECT sku.product_id, ?, ?, '件'
                FROM app.provider_skus mapping
                JOIN app.fulfillment_providers provider ON provider.id = mapping.fulfillment_provider_id
                JOIN app.skus sku ON sku.id = mapping.sku_id
                WHERE provider.provider_code='JD' AND mapping.provider_sku_code='JD-SKU-000001'
                RETURNING id
                """,
                Long.class,
                providerId,
                specification);
    }
}
