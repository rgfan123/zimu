package cn.zimu.fulfillment.fulfillment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

import cn.zimu.fulfillment.common.web.RequestContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Ticket 09 public acceptance seam for runtime-maintained carrier-prefix rules. */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "app.gateway.basic-auth.username=carrier-prefix-admin",
            "app.gateway.basic-auth.password=carrier-prefix-password",
            "app.message-worker.enabled=false",
            "app.carrier-prefixes.carriers.DISABLED_CARRIER.name=停用物流",
            "app.carrier-prefixes.carriers.DISABLED_CARRIER.enabled=false"
        })
class CarrierPrefixMappingApiTest {

    private static final String OPERATOR = "carrier-prefix-admin";
    private static final String PASSWORD = "carrier-prefix-password";
    private static final String KEY = "carrier-prefix-replace-001";
    private static final String REQUEST_ID = "req-" + KEY;

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoSpyBean
    private JdbcTemplate jdbc;

    @Autowired
    private CarrierPrefixMatcher matcher;

    @AfterEach
    void restoreMigrationSeedSoTestsAreOrderIndependent() {
        jdbc.update("DELETE FROM app.carrier_prefix_mappings");
        jdbc.update(
                "INSERT INTO app.carrier_prefix_mappings(prefix, carrier_code) VALUES ('JD', 'JD'), ('SF', 'SF_EXPRESS')");
        jdbc.update(
                """
                UPDATE app.carrier_prefix_mapping_sets
                SET lock_version=0, updated_by='test-reset', updated_at=CURRENT_TIMESTAMP
                WHERE singleton_id=1
                """);
    }

    @Test
    void runtimeMappingsRequireTrustedVersionedIdempotentWritesAndDriveMatching() throws Exception {
        ResponseEntity<String> initialResponse = exchange(HttpMethod.GET, null, new HttpHeaders());
        assertThat(initialResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> initial = json(initialResponse.getBody());
        assertThat(initial.get("version")).isEqualTo(0);
        assertMappings(initial, "JD", "JD", "SF", "SF_EXPRESS");

        Map<String, Object> command = Map.of(
                "expected_version", 0,
                "mappings", List.of(
                        Map.of("prefix", " zx ", "carrier_code", "SF_EXPRESS"),
                        Map.of("prefix", "JD", "carrier_code", "JD")));

        HttpHeaders spoofedHeaders = writeHeaders(KEY + "-spoofed", "req-" + KEY + "-spoofed", false);
        ResponseEntity<String> spoofed = exchange(HttpMethod.PUT, command, spoofedHeaders);
        assertThat(spoofed.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(json(spoofed.getBody()).get("business_code"))
                .isEqualTo("CARRIER_PREFIX_OPERATOR_UNAUTHORIZED");
        assertThat(json(exchange(HttpMethod.GET, null, new HttpHeaders()).getBody()).get("version"))
                .isEqualTo(0);

        HttpHeaders headers = writeHeaders(KEY, REQUEST_ID, true);
        ResponseEntity<String> updatedResponse = exchange(HttpMethod.PUT, command, headers);
        assertThat(updatedResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> updated = json(updatedResponse.getBody());
        assertThat(updated.get("version")).isEqualTo(1);
        assertMappings(updated, "JD", "JD", "ZX", "SF_EXPRESS");

        ResponseEntity<String> replay = exchange(HttpMethod.PUT, command, headers);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getBody()).isEqualTo(updatedResponse.getBody());

        Map<String, Object> changedPayload = Map.of(
                "expected_version", 0,
                "mappings", List.of(Map.of("prefix", "SF", "carrier_code", "SF_EXPRESS")));
        ResponseEntity<String> conflict = exchange(HttpMethod.PUT, changedPayload, headers);
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(json(conflict.getBody()).get("business_code")).isEqualTo("IDEMPOTENCY_CONFLICT");

        ResponseEntity<String> stale = exchange(
                HttpMethod.PUT,
                command,
                writeHeaders("carrier-prefix-stale-001", "req-carrier-prefix-stale-001", true));
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(json(stale.getBody()).get("business_code")).isEqualTo("VERSION_CONFLICT");

        assertThat(matcher.resolvePrefix("ZX123456789")).get().satisfies(carrier -> {
            assertThat(carrier.code()).isEqualTo("SF_EXPRESS");
            assertThat(carrier.name()).isEqualTo("顺丰速运");
        });
        assertThat(matcher.resolvePrefix("SF123456789")).isEmpty();

        List<String> auditPayloads = jdbc.queryForList(
                """
                SELECT request_payload::text
                FROM app.audit_logs
                WHERE request_id=? AND operation='carrier_prefix_mapping.replace'
                """,
                String.class,
                REQUEST_ID);
        assertThat(auditPayloads).singleElement().satisfies(payload -> {
            JsonNode audit = readTree(payload);
            assertThat(audit.get("old_version").asLong()).isZero();
            assertThat(audit.get("new_version").asLong()).isEqualTo(1);
            assertThat(audit.get("rule_count").asInt()).isEqualTo(2);
            assertThat(audit.get("change_summary").get("added").toString())
                    .isEqualTo("[\"ZX=SF_EXPRESS\"]");
            assertThat(audit.get("change_summary").get("removed").toString())
                    .isEqualTo("[\"SF=SF_EXPRESS\"]");
        });
    }

    @Test
    void runtimeMappingValidationCollapsesExactDuplicatesAndRejectsUnsafeRules() throws Exception {
        long version = ((Number) json(exchange(HttpMethod.GET, null, new HttpHeaders()).getBody())
                        .get("version"))
                .longValue();
        Map<String, Object> exactDuplicates = Map.of(
                "expected_version", version,
                "mappings", List.of(
                        Map.of("prefix", " sf ", "carrier_code", "sf_express"),
                        Map.of("prefix", "SF", "carrier_code", "SF_EXPRESS")));
        ResponseEntity<String> collapsed = exchange(
                HttpMethod.PUT,
                exactDuplicates,
                writeHeaders("carrier-prefix-dedupe-001", "req-carrier-prefix-dedupe-001", true));
        assertThat(collapsed.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> collapsedBody = json(collapsed.getBody());
        assertThat(((Number) collapsedBody.get("version")).longValue()).isEqualTo(version + 1);
        List<?> collapsedMappings = (List<?>) collapsedBody.get("mappings");
        assertThat(collapsedMappings).hasSize(1);
        Map<?, ?> collapsedRule = (Map<?, ?>) collapsedMappings.getFirst();
        assertThat(collapsedRule.get("prefix")).isEqualTo("SF");
        assertThat(collapsedRule.get("carrier_code")).isEqualTo("SF_EXPRESS");

        long collapsedVersion = version + 1;
        String emptyKey = "carrier-prefix-empty-001";
        ResponseEntity<String> emptied = exchange(
                HttpMethod.PUT,
                Map.of("expected_version", collapsedVersion, "mappings", List.of()),
                writeHeaders(emptyKey, "req-" + emptyKey, true));
        assertThat(emptied.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> emptiedBody = json(emptied.getBody());
        long emptyVersion = collapsedVersion + 1;
        assertThat(((Number) emptiedBody.get("version")).longValue()).isEqualTo(emptyVersion);
        assertThat((List<?>) emptiedBody.get("mappings")).isEmpty();
        assertThat(matcher.resolvePrefix("SF123456789")).isEmpty();
        assertThat(jdbc.queryForObject(
                        """
                        SELECT (request_payload ->> 'rule_count')::integer
                        FROM app.audit_logs
                        WHERE request_id=? AND operation='carrier_prefix_mapping.replace'
                        """,
                        Integer.class,
                        "req-" + emptyKey))
                .isZero();

        long unchangedVersion = emptyVersion;
        assertRejectedWithoutVersionChange(
                Map.of(
                        "expected_version", unchangedVersion,
                        "mappings", List.of(
                                Map.of("prefix", " sf ", "carrier_code", "SF_EXPRESS"),
                                Map.of("prefix", "SF", "carrier_code", "JD"))),
                "carrier-prefix-conflicting-duplicate-001",
                "CARRIER_PREFIX_DUPLICATE",
                unchangedVersion);
        assertRejectedWithoutVersionChange(
                Map.of(
                        "expected_version", unchangedVersion,
                        "mappings", List.of(Map.of("prefix", "OFF", "carrier_code", "DISABLED_CARRIER"))),
                "carrier-prefix-disabled-001",
                "CARRIER_NOT_ENABLED",
                unchangedVersion);
        assertRejectedWithoutVersionChange(
                Map.of(
                        "expected_version", unchangedVersion,
                        "mappings", List.of(Map.of(
                                "prefix", "ABCDEFGHIJKLMNOPQ", "carrier_code", "SF_EXPRESS"))),
                "carrier-prefix-overlong-001",
                "CARRIER_PREFIX_INVALID",
                unchangedVersion);
    }

    @Test
    void runtimeMappingReplacementRequiresAnExplicitExpectedVersion() throws Exception {
        Map<String, Object> commandWithoutVersion = Map.of(
                "mappings", List.of(Map.of("prefix", "SF", "carrier_code", "SF_EXPRESS")));

        ResponseEntity<String> rejected = exchange(
                HttpMethod.PUT,
                commandWithoutVersion,
                writeHeaders(
                        "carrier-prefix-missing-version-001",
                        "req-carrier-prefix-missing-version-001",
                        true));

        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(rejected.getBody()).get("business_code")).isEqualTo("VALIDATION_ERROR");
        assertThat(json(exchange(HttpMethod.GET, null, new HttpHeaders()).getBody()).get("version"))
                .isEqualTo(0);
    }

    @Test
    void concurrentNextVersionWriteAuditsTheSnapshotReadAfterAuthorityLock() throws Exception {
        String firstRequestId = "req-carrier-prefix-concurrent-first-001";
        String secondRequestId = "req-carrier-prefix-concurrent-second-001";
        CountDownLatch firstBeforeMutation = new CountDownLatch(1);
        CountDownLatch allowFirstMutation = new CountDownLatch(1);
        CountDownLatch secondAuthorityReadReady = new CountDownLatch(1);
        CountDownLatch allowSecondMutation = new CountDownLatch(1);

        doAnswer(invocation -> {
                    String sql = invocation.getArgument(0, String.class);
                    RequestContext context = RequestContext.current();
                    boolean secondRequest = context != null && secondRequestId.equals(context.getRequestId());
                    if (!secondRequest || !isAuthorityVersionRead(sql)) {
                        return invocation.callRealMethod();
                    }
                    if (sql.toUpperCase(java.util.Locale.ROOT).contains("FOR UPDATE")) {
                        // Correct implementation: signal before the real query blocks behind request A's row lock.
                        secondAuthorityReadReady.countDown();
                        return invocation.callRealMethod();
                    }
                    Object result = invocation.callRealMethod();
                    // Current implementation: signal only after B has captured the stale unlocked snapshot.
                    secondAuthorityReadReady.countDown();
                    return result;
                })
                .when(jdbc)
                .queryForObject(anyString(), eq(Long.class));

        doAnswer(invocation -> {
                    String sql = invocation.getArgument(0, String.class);
                    RequestContext context = RequestContext.current();
                    if (context != null && isAuthorityVersionMutation(sql)) {
                        if (firstRequestId.equals(context.getRequestId())) {
                            firstBeforeMutation.countDown();
                            await(allowFirstMutation, "release first mapping mutation");
                        } else if (secondRequestId.equals(context.getRequestId())) {
                            await(allowSecondMutation, "release second mapping mutation");
                        }
                    }
                    return invocation.callRealMethod();
                })
                .when(jdbc)
                .update(anyString(), any(Object[].class));

        Map<String, Object> firstCommand = Map.of(
                "expected_version", 0,
                "mappings", List.of(Map.of("prefix", "AA", "carrier_code", "JD")));
        Map<String, Object> secondCommand = Map.of(
                "expected_version", 1,
                "mappings", List.of(Map.of("prefix", "BB", "carrier_code", "SF_EXPRESS")));
        HttpHeaders firstHeaders = writeHeaders(
                "carrier-prefix-concurrent-first-001", firstRequestId, true);
        HttpHeaders secondHeaders = writeHeaders(
                "carrier-prefix-concurrent-second-001", secondRequestId, true);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ResponseEntity<String>> first = executor.submit(
                    () -> exchange(HttpMethod.PUT, firstCommand, firstHeaders));
            await(firstBeforeMutation, "first mapping request reached mutation boundary");

            Future<ResponseEntity<String>> second = executor.submit(
                    () -> exchange(HttpMethod.PUT, secondCommand, secondHeaders));
            await(secondAuthorityReadReady, "second mapping request reached authority read");

            allowFirstMutation.countDown();
            ResponseEntity<String> firstResponse = first.get(20, TimeUnit.SECONDS);
            assertThat(firstResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(json(firstResponse.getBody()).get("version")).isEqualTo(1);

            allowSecondMutation.countDown();
            ResponseEntity<String> secondResponse = second.get(20, TimeUnit.SECONDS);
            assertThat(secondResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(json(secondResponse.getBody()).get("version")).isEqualTo(2);

            ResponseEntity<String> replay = exchange(
                    HttpMethod.PUT, secondCommand, secondHeaders);
            assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(replay.getBody()).isEqualTo(secondResponse.getBody());

            List<String> secondAudits = jdbc.queryForList(
                    """
                    SELECT request_payload::text
                    FROM app.audit_logs
                    WHERE request_id=? AND operation='carrier_prefix_mapping.replace'
                    """,
                    String.class,
                    secondRequestId);
            assertThat(secondAudits).singleElement().satisfies(payload -> {
                JsonNode audit = readTree(payload);
                assertThat(audit.get("old_version").asLong()).isEqualTo(1);
                assertThat(audit.get("new_version").asLong()).isEqualTo(2);
                assertThat(audit.get("change_summary").get("added").toString())
                        .isEqualTo("[\"BB=SF_EXPRESS\"]");
                assertThat(audit.get("change_summary").get("removed").toString())
                        .isEqualTo("[\"AA=JD\"]");
            });
        } finally {
            allowFirstMutation.countDown();
            allowSecondMutation.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void concurrentGetReturnsVersionAndMappingsFromOneAuthoritySnapshot() throws Exception {
        String readRequestId = "req-carrier-prefix-concurrent-read-001";
        CountDownLatch readerAtSnapshotBoundary = new CountDownLatch(1);
        CountDownLatch writerCommitted = new CountDownLatch(1);

        doAnswer(invocation -> {
                    String sql = invocation.getArgument(0, String.class);
                    RequestContext context = RequestContext.current();
                    boolean reader = context != null && readRequestId.equals(context.getRequestId());
                    if (!reader || !isUnlockedAuthorityVersionRead(sql)) {
                        return invocation.callRealMethod();
                    }
                    Object version = invocation.callRealMethod();
                    // Legacy two-query GET: pause after version N was captured but before mappings are read.
                    readerAtSnapshotBoundary.countDown();
                    await(writerCommitted, "carrier mapping writer commit");
                    return version;
                })
                .when(jdbc)
                .queryForObject(anyString(), eq(Long.class));

        doAnswer(invocation -> {
                    String sql = invocation.getArgument(0, String.class);
                    RequestContext context = RequestContext.current();
                    boolean reader = context != null && readRequestId.equals(context.getRequestId());
                    if (!reader || !isSingleAuthoritySnapshotRead(sql)) {
                        return invocation.callRealMethod();
                    }
                    // Correct implementation: let the one statement take its snapshot after the writer commits.
                    readerAtSnapshotBoundary.countDown();
                    await(writerCommitted, "carrier mapping writer commit");
                    return invocation.callRealMethod();
                })
                .when(jdbc)
                .query(anyString(), any(ResultSetExtractor.class));

        HttpHeaders readHeaders = new HttpHeaders();
        readHeaders.set("X-Request-Id", readRequestId);
        Map<String, Object> command = Map.of(
                "expected_version", 0,
                "mappings", List.of(Map.of("prefix", "CC", "carrier_code", "JD")));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ResponseEntity<String>> read = executor.submit(
                    () -> exchange(HttpMethod.GET, null, readHeaders));
            await(readerAtSnapshotBoundary, "carrier mapping reader snapshot boundary");

            ResponseEntity<String> written = exchange(
                    HttpMethod.PUT,
                    command,
                    writeHeaders(
                            "carrier-prefix-concurrent-read-writer-001",
                            "req-carrier-prefix-concurrent-read-writer-001",
                            true));
            assertThat(written.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(json(written.getBody()).get("version")).isEqualTo(1);
            writerCommitted.countDown();

            ResponseEntity<String> readResponse = read.get(20, TimeUnit.SECONDS);
            assertThat(readResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
            Map<String, Object> snapshot = json(readResponse.getBody());
            assertThat(snapshot.get("version")).isEqualTo(1);
            List<?> mappings = (List<?>) snapshot.get("mappings");
            assertThat(mappings).singleElement().satisfies(entry -> {
                Map<?, ?> mapping = (Map<?, ?>) entry;
                assertThat(mapping.get("prefix")).isEqualTo("CC");
                assertThat(mapping.get("carrier_code")).isEqualTo("JD");
            });
        } finally {
            writerCommitted.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private ResponseEntity<String> exchange(HttpMethod method, Object body, HttpHeaders headers) {
        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        return http.exchange(
                "/api/v1/carrier-prefix-mappings",
                method,
                new HttpEntity<>(body, headers),
                String.class);
    }

    private static boolean isAuthorityVersionRead(String sql) {
        return sql != null
                && sql.contains("SELECT lock_version")
                && sql.contains("app.carrier_prefix_mapping_sets");
    }

    private static boolean isAuthorityVersionMutation(String sql) {
        return sql != null
                && sql.contains("UPDATE app.carrier_prefix_mapping_sets")
                && sql.contains("lock_version=lock_version+1");
    }

    private static boolean isUnlockedAuthorityVersionRead(String sql) {
        return isAuthorityVersionRead(sql)
                && !sql.toUpperCase(java.util.Locale.ROOT).contains("FOR UPDATE");
    }

    private static boolean isSingleAuthoritySnapshotRead(String sql) {
        return sql != null
                && sql.contains("app.carrier_prefix_mapping_sets")
                && sql.contains("app.carrier_prefix_mappings");
    }

    private static void await(CountDownLatch latch, String description) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting to " + description);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while waiting to " + description, exception);
        }
    }

    private static HttpHeaders writeHeaders(String key, String requestId, boolean authenticated) {
        HttpHeaders headers = new HttpHeaders();
        if (authenticated) {
            headers.setBasicAuth(OPERATOR, PASSWORD);
        }
        headers.set("Idempotency-Key", key);
        headers.set("X-Request-Id", requestId);
        headers.set("X-Operator", OPERATOR);
        return headers;
    }

    private void assertRejectedWithoutVersionChange(
            Map<String, Object> command,
            String key,
            String expectedBusinessCode,
            long expectedVersion) throws Exception {
        ResponseEntity<String> response = exchange(
                HttpMethod.PUT,
                command,
                writeHeaders(key, "req-" + key, true));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(response.getBody()).get("business_code")).isEqualTo(expectedBusinessCode);
        assertThat(((Number) json(exchange(HttpMethod.GET, null, new HttpHeaders()).getBody())
                                .get("version"))
                        .longValue())
                .isEqualTo(expectedVersion);
    }

    private Map<String, Object> json(String value) throws Exception {
        return objectMapper.readValue(value, new TypeReference<>() {});
    }

    private static void assertMappings(
            Map<String, Object> view,
            String firstPrefix,
            String firstCarrier,
            String secondPrefix,
            String secondCarrier) {
        List<?> mappings = (List<?>) view.get("mappings");
        assertThat(mappings).hasSize(2);
        Map<?, ?> first = (Map<?, ?>) mappings.get(0);
        Map<?, ?> second = (Map<?, ?>) mappings.get(1);
        assertThat(first.get("prefix")).isEqualTo(firstPrefix);
        assertThat(first.get("carrier_code")).isEqualTo(firstCarrier);
        assertThat(second.get("prefix")).isEqualTo(secondPrefix);
        assertThat(second.get("carrier_code")).isEqualTo(secondCarrier);
    }

    private JsonNode readTree(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (Exception exception) {
            throw new AssertionError("audit payload is not valid JSON", exception);
        }
    }
}
