package cn.zimu.fulfillment.seed;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "app.seed.demo-enabled=true",
            "app.seed.reference-date=2026-08-12",
            "spring.data.redis.repositories.enabled=false",
            // Full-suite startup can briefly saturate Docker Desktop while several
            // PostgreSQL contexts are being created. Keep the production timeout
            // strict, but do not let that unrelated scheduling jitter make the
            // public health acceptance flaky.
            "spring.data.redis.connect-timeout=2s",
            "spring.data.redis.timeout=2s"
        })
class DemoSeedHttpAcceptanceTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    private TestRestTemplate http;

    @Test
    void deterministicThirtyDayDatasetIsVisibleThroughPublicHttpApis() {
        ResponseEntity<Map> health = http.getForEntity("/actuator/health", Map.class);
        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(health.getBody()).containsEntry("status", "UP");

        ResponseEntity<Map> dashboard = http.getForEntity(
                "/api/v1/dashboard/summary?business_date=2026-08-12", Map.class);
        assertThat(dashboard.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(dashboard.getBody()).containsEntry("business_date", "2026-08-12");
        assertThat(((Number) dashboard.getBody().get("order_count")).longValue()).isEqualTo(7);
        assertThat((Iterable<?>) dashboard.getBody().get("trend")).hasSize(7);

        ResponseEntity<Map[]> channels = http.getForEntity(
                "/api/v1/analytics/channels?date_from=2026-07-14&date_to=2026-08-12", Map[].class);
        assertThat(channels.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(channels.getBody()).hasSize(120);
        Set<String> sourceChannels = Arrays.stream(channels.getBody())
                .map(row -> row.get("source_channel").toString())
                .collect(Collectors.toSet());
        assertThat(sourceChannels).containsExactlyInAnyOrder("CAISHIXIAN", "JUFUBAO", "FEIXIANG", "WECOM");
        assertThat(Arrays.stream(channels.getBody()).map(row -> row.get("metric_date")).distinct()).hasSize(30);

        ResponseEntity<Map[]> products = http.getForEntity(
                "/api/v1/analytics/products?date_from=2026-07-14&date_to=2026-08-12", Map[].class);
        assertThat(products.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(products.getBody()).isNotEmpty();

        assertOrderStatusIsSeeded("OUT_OF_STOCK");
        assertOrderStatusIsSeeded("PROCUREMENT_PENDING");
        assertOrderStatusIsSeeded("FULFILLMENT_EXCEPTION");
        assertOrderStatusIsSeeded("SYNC_FAILED");

        ResponseEntity<Map> reviews = http.getForEntity(
                "/api/v1/review-cases?status=OPEN&page=0&size=200", Map.class);
        assertThat(reviews.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) reviews.getBody().get("total_elements")).longValue()).isPositive();

        ResponseEntity<Map> audits = http.getForEntity(
                "/api/v1/audit-logs?operation=seed.demo-dataset&page=0&size=20", Map.class);
        assertThat(audits.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) audits.getBody().get("total_elements")).longValue()).isEqualTo(1);
    }

    @Test
    void threeFreshWalkthroughOrdersExposeTheirNamedScenarios() {
        assertFreshOrder("SEED-FRESH-RECEIVED", "RECEIVED");
        assertFreshOrder("SEED-FRESH-PROCUREMENT", "PROCUREMENT_PENDING");
        assertFreshOrder("SEED-FRESH-EXCEPTION", "FULFILLMENT_EXCEPTION");
    }

    private void assertOrderStatusIsSeeded(String status) {
        ResponseEntity<Map> response = http.getForEntity(
                "/api/v1/orders?order_status=" + status + "&page=0&size=1", Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) response.getBody().get("total_elements")).longValue()).isPositive();
    }

    private void assertFreshOrder(String sourceRef, String status) {
        ResponseEntity<Map> response = http.getForEntity(
                "/api/v1/orders?query=" + sourceRef + "&page=0&size=20", Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Iterable<?>) response.getBody().get("items")).singleElement().satisfies(item -> {
            Map<?, ?> order = (Map<?, ?>) item;
            assertThat(order.get("source_ref")).isEqualTo(sourceRef);
            assertThat(order.get("order_status")).isEqualTo(status);
        });
    }
}
