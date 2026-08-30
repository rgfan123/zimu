package cn.zimu.fulfillment.schema;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Ticket 08 application-level acceptance over the same production-shaped repair matrix. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SkuCanonicalizationAcceptanceApiTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired JdbcTemplate jdbc;
    @Autowired TestRestTemplate http;

    @Test
    void canonicalAndLegacyNamesResolveToReadyCanonicalSkusWhileDuplicatesRemainAuditable()
            throws Exception {
        try (Connection connection = jdbc.getDataSource().getConnection()) {
            SkuCanonicalizationTestFixture.seed(connection);
        }
        String protectedFactsBefore;
        try (Connection connection = jdbc.getDataSource().getConnection()) {
            protectedFactsBefore = SkuCanonicalizationTestFixture.protectedFacts(connection);
        }

        String migrationSql = new ClassPathResource(
                        "db/migration/V75__canonicalize_duplicate_skus_and_names.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        jdbc.execute(migrationSql);

        Map<String, Object> beef = itemByCode(page("/api/v1/skus?query=原切牛肉卷"), "SKU-JD-000019");
        assertThat(beef)
                .containsEntry("name", "精选牛肉卷")
                .containsEntry("active", true);
        assertThat(attributes(beef))
                .containsEntry("specification", "300g")
                .containsEntry("barcode", "06977872890432")
                .containsEntry("purchase_price", "14.43")
                .containsEntry("retail_price", "23.00");
        assertThat(readiness(beef)).containsEntry("ready", true);

        Map<String, Object> wagyu = itemByCode(page("/api/v1/skus?query=M5霜降肥牛卷"), "SKU-JD-000048");
        assertThat(wagyu)
                .containsEntry("name", "澳洲和牛霜降肥牛卷（澳标油花5级）")
                .containsEntry("active", true);
        assertThat(readiness(wagyu)).containsEntry("ready", true);

        Map<String, Object> ostrich = itemByCode(page("/api/v1/skus?query=SKU-TP-000062"), "SKU-TP-000062");
        assertThat(readiness(ostrich)).containsEntry("ready", true);
        assertThat(itemByCode(page("/api/v1/skus?query=SKU-JD-000043"), "SKU-JD-000043"))
                .containsEntry("active", false);
        assertThat(itemByCode(page("/api/v1/skus?query=SKU-JD-000091"), "SKU-JD-000091"))
                .containsEntry("active", false);

        Map<String, Object> tpMapping = providerMappings().stream()
                .filter(mapping -> attributes(mapping).get("sku_id").equals(ostrich.get("id")))
                .findFirst()
                .orElseThrow();
        assertThat(attributes(tpMapping))
                .containsEntry("provider_sku_code", "SKU-TP-000062")
                .containsEntry("provider_sku_code_scope", "INTERNAL_ROUTING");

        try (Connection connection = jdbc.getDataSource().getConnection()) {
            assertThat(SkuCanonicalizationTestFixture.protectedFacts(connection))
                    .isEqualTo(protectedFactsBefore);
        }
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM app.sku_aliases WHERE alias_value='A5澳洲和牛霜降肥牛卷'",
                        Integer.class))
                .isZero();
    }

    private Map<String, Object> page(String path) {
        Map<String, Object> result = http.getForObject(path + "&page=0&size=50", Map.class);
        assertThat(result).isNotNull();
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> itemByCode(Map<String, Object> page, String code) {
        return ((List<Map<String, Object>>) page.get("items")).stream()
                .filter(item -> code.equals(item.get("code")))
                .findFirst()
                .orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> providerMappings() {
        Map<String, Object> page = http.getForObject(
                "/api/v1/provider-sku-mappings?page=0&size=200", Map.class);
        return (List<Map<String, Object>>) page.get("items");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> attributes(Map<String, Object> record) {
        return (Map<String, Object>) record.get("attributes");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readiness(Map<String, Object> record) {
        return (Map<String, Object>) attributes(record).get("readiness");
    }
}
