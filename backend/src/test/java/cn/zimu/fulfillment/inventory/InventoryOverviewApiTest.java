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
        properties = "app.message-worker.enabled=false")
class InventoryOverviewApiTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    @Test
    void distinguishesExplicitZeroFromNoObservation() {
        long observedProviderId = createProvider("INVZERO", "零库存履约方", true);
        long unobservedProviderId = createProvider("INVNONE", "未观测履约方", false);
        long observedSkuId = createSku(observedProviderId, "零库存规格");
        createSku(unobservedProviderId, "未观测规格");
        jdbc.update(
                """
                INSERT INTO app.provider_stock_snapshots
                    (fulfillment_provider_id, sku_id, warehouse_code, stock_num, usable_num,
                     quantity_unit, source_type, synced_at, source_ref, raw_payload)
                VALUES (?, ?, 'WH-ZERO', 0.000, 0.000,
                        'INTERNAL_UNIT', 'NORMALIZED_PROVIDER_SNAPSHOT', ?::timestamptz,
                        'private-source-ref', '{"secret":"never-leak"}'::jsonb)
                """,
                observedProviderId,
                observedSkuId,
                Instant.parse("2026-08-13T01:02:03Z").toString());

        ResponseEntity<Map> response = http.getForEntity(
                "/api/v1/inventory/overview?page=0&size=20&provider_id=" + observedProviderId,
                Map.class);
        ResponseEntity<Map> noObservationResponse = http.getForEntity(
                "/api/v1/inventory/overview?page=0&size=20&provider_id=" + unobservedProviderId,
                Map.class);
        ResponseEntity<Map> crossProviderResponse = http.getForEntity(
                "/api/v1/inventory/overview?page=0&size=200",
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> observed = firstItem(response.getBody());
        assertThat(observed)
                .containsEntry("provider_id", String.valueOf(observedProviderId))
                .containsEntry("sku_id", String.valueOf(observedSkuId))
                .containsEntry("warehouse_code", "WH-ZERO")
                .containsEntry("observation_status", "OBSERVED")
                .containsEntry("total_quantity", "0.000")
                .containsEntry("available_quantity", "0.000")
                .containsEntry("unavailable_quantity", "0.000")
                .containsEntry("quantity_unit", "INTERNAL_UNIT")
                .containsEntry("source_type", "NORMALIZED_PROVIDER_SNAPSHOT")
                .containsEntry("freshness_status", "STALE");

        assertThat(noObservationResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> unobserved = firstItem(noObservationResponse.getBody());
        assertThat(unobserved)
                .containsEntry("provider_id", String.valueOf(unobservedProviderId))
                .containsEntry("warehouse_code", null)
                .containsEntry("observation_status", "NOT_OBSERVED")
                .containsEntry("total_quantity", null)
                .containsEntry("available_quantity", null)
                .containsEntry("unavailable_quantity", null)
                .containsEntry("source_type", null);
        Map<?, ?> noObservationCoverage = (Map<?, ?>) noObservationResponse.getBody().get("coverage");
        assertThat(noObservationCoverage.get("provider_count")).isEqualTo(1);
        assertThat(noObservationCoverage.get("observed_provider_count")).isEqualTo(0);
        assertThat(noObservationCoverage.get("sku_count")).isEqualTo(1);
        assertThat(noObservationCoverage.get("observed_sku_count")).isEqualTo(0);
        assertThat(noObservationCoverage.get("stale_count")).isEqualTo(0);
        assertThat(noObservationCoverage.get("oldest_observed_at")).isNull();
        assertThat(noObservationCoverage.get("partial")).isEqualTo(true);
        assertThat(noObservationCoverage.get("freshness_policy")).isEqualTo("PT15M");

        assertThat(crossProviderResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(items(crossProviderResponse.getBody()))
                .extracting(item -> item.get("provider_id"))
                .contains(String.valueOf(observedProviderId), String.valueOf(unobservedProviderId));

        String publicPayload = String.valueOf(response.getBody());
        assertThat(publicPayload).doesNotContain("private-source-ref", "never-leak", "raw_payload", "source_ref");
    }

    @Test
    void filtersLatestWarehouseFactsAndPaginatesWithoutHidingPartialSkuCoverage() {
        long providerId = createProvider("INVPAGE", "分页履约方", true);
        long observedSkuId = createSku(providerId, "已观测规格");
        createSku(providerId, "未观测规格");
        insertSnapshot(providerId, observedSkuId, "WH-OLDER", "9.000", "7.000", "2026-08-12T01:00:00Z");
        insertSnapshot(providerId, observedSkuId, "WH-OLDER", "5.000", "3.000", "2026-08-13T01:00:00Z");
        insertSnapshot(providerId, observedSkuId, "WH-OTHER", "4.000", "4.000", "2026-08-13T02:00:00Z");

        Map<String, Object> providerPage = http.getForObject(
                "/api/v1/inventory/overview?page=0&size=1&provider_id=" + providerId,
                Map.class);
        assertThat(providerPage)
                .containsEntry("page", 0)
                .containsEntry("size", 1)
                .containsEntry("total_elements", 3)
                .containsEntry("total_pages", 3);
        Map<?, ?> providerCoverage = (Map<?, ?>) providerPage.get("coverage");
        assertThat(providerCoverage.get("provider_count")).isEqualTo(1);
        assertThat(providerCoverage.get("observed_provider_count")).isEqualTo(1);
        assertThat(providerCoverage.get("sku_count")).isEqualTo(2);
        assertThat(providerCoverage.get("observed_sku_count")).isEqualTo(1);
        assertThat(providerCoverage.get("warehouse_count")).isEqualTo(2);
        assertThat(providerCoverage.get("stale_count")).isEqualTo(2);
        assertThat(providerCoverage.get("oldest_observed_at")).isEqualTo("2026-08-13T01:00:00Z");
        assertThat(providerCoverage.get("partial")).isEqualTo(true);

        Map<String, Object> filtered = http.getForObject(
                "/api/v1/inventory/overview?page=0&size=20&provider_id=" + providerId
                        + "&sku_id=" + observedSkuId + "&warehouse_code=WH-OLDER",
                Map.class);
        assertThat(filtered).containsEntry("total_elements", 1);
        assertThat(firstItem(filtered))
                .containsEntry("warehouse_code", "WH-OLDER")
                .containsEntry("total_quantity", "5.000")
                .containsEntry("available_quantity", "3.000")
                .containsEntry("unavailable_quantity", "2.000")
                .containsEntry("observed_at", "2026-08-13T01:00:00Z");

        Map<String, Object> missingTargetWarehouse = http.getForObject(
                "/api/v1/inventory/overview?page=0&size=20&provider_id=" + providerId
                        + "&sku_id=" + observedSkuId + "&warehouse_code=WH-NEVER-OBSERVED",
                Map.class);
        assertThat(missingTargetWarehouse).containsEntry("total_elements", 1);
        assertThat(firstItem(missingTargetWarehouse))
                .containsEntry("warehouse_code", null)
                .containsEntry("observation_status", "NOT_OBSERVED")
                .containsEntry("total_quantity", null)
                .containsEntry("available_quantity", null);
        Map<?, ?> missingWarehouseCoverage = (Map<?, ?>) missingTargetWarehouse.get("coverage");
        assertThat(missingWarehouseCoverage.get("provider_count")).isEqualTo(1);
        assertThat(missingWarehouseCoverage.get("observed_provider_count")).isEqualTo(0);
        assertThat(missingWarehouseCoverage.get("sku_count")).isEqualTo(1);
        assertThat(missingWarehouseCoverage.get("observed_sku_count")).isEqualTo(0);
        assertThat(missingWarehouseCoverage.get("warehouse_count")).isEqualTo(0);
        assertThat(missingWarehouseCoverage.get("partial")).isEqualTo(true);

        Map<String, Object> absentSku = http.getForObject(
                "/api/v1/inventory/overview?page=0&size=20&provider_id=" + providerId
                        + "&sku_id=9223372036854775806&warehouse_code=WH-NEVER-OBSERVED",
                Map.class);
        assertThat(absentSku).containsEntry("total_elements", 0);
        assertThat(items(absentSku)).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstItem(Map<?, ?> response) {
        return items(response).getFirst();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<?, ?> response) {
        return (List<Map<String, Object>>) response.get("items");
    }

    private long createProvider(String code, String name, boolean inventoryManaged) {
        return jdbc.queryForObject(
                """
                INSERT INTO app.fulfillment_providers
                    (provider_code, provider_name, provider_type, inventory_managed_by_us, tracking_sla_minutes)
                VALUES (?, ?, 'THIRD_PARTY', ?, 1440)
                RETURNING id
                """,
                Long.class,
                code,
                name,
                inventoryManaged);
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

    private void insertSnapshot(
            long providerId,
            long skuId,
            String warehouseCode,
            String stock,
            String usable,
            String observedAt) {
        jdbc.update(
                """
                INSERT INTO app.provider_stock_snapshots
                    (fulfillment_provider_id, sku_id, warehouse_code, stock_num, usable_num,
                     quantity_unit, source_type, synced_at)
                VALUES (?, ?, ?, ?::numeric, ?::numeric,
                        'INTERNAL_UNIT', 'NORMALIZED_PROVIDER_SNAPSHOT', ?::timestamptz)
                """,
                providerId,
                skuId,
                warehouseCode,
                stock,
                usable,
                observedAt);
    }
}
