package cn.zimu.fulfillment.followup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class KehuzxMcpWriteClientTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<String> calledTools = new ArrayList<>();
    private HttpServer server;
    private KehuzxMcpWriteProperties properties;
    private String observedAuthorization;
    private JsonNode observedArguments;
    private boolean omitAdvertisedTool;
    private boolean advertiseUnknownTool;
    private boolean delayToolCall;
    private boolean stallToolBody;
    private boolean omitSuccessResult;
    private String toolErrorCode;
    private String responseStatus = "SUCCEEDED";
    private String responseRequestId = "request-0001";
    private String responseIdempotencyKey = "idempotency-0001";

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", this::handle);
        server.start();
        properties = new KehuzxMcpWriteProperties();
        properties.setEnabled(true);
        properties.setEndpoint(URI.create(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp"));
        properties.setAllowedHost("127.0.0.1");
        properties.setAllowedPort(server.getAddress().getPort());
        properties.setWriteToken("writer-token-with-at-least-32-bytes");
        properties.setApprovalSigningKey("writer-signing-key-with-at-least-32-bytes");
        properties.setConnectTimeout(Duration.ofSeconds(1));
        properties.setReadTimeout(Duration.ofSeconds(1));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void createCustomerUsesTheIndependentWriterSessionAndApprovalContext() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("customer_name", "海港食品");
        payload.put("phone", null);

        KehuzxWriteResult result = client().execute(approval("create_customer", payload));

        assertThat(result.status()).isEqualTo(KehuzxWriteStatus.SUCCEEDED);
        assertThat(result.requestId()).isEqualTo("request-0001");
        assertThat(result.externalEntityType()).isEqualTo("customer");
        assertThat(result.externalEntityId()).isEqualTo("customer-9");
        assertThat(observedAuthorization).isEqualTo("Bearer writer-token-with-at-least-32-bytes");
        assertThat(calledTools).containsExactly("create_customer");
        assertThat(observedArguments.path("customer_name").asText()).isEqualTo("海港食品");
        assertThat(observedArguments.path("write_context").path("request_id").asText())
                .isEqualTo("request-0001");
        assertThat(observedArguments.path("write_context").path("approval_grant").asText())
                .hasSizeGreaterThan(32);
    }

    @Test
    void exactWriterAdvertisementIsRequiredBeforeAnyWriteToolCall() {
        omitAdvertisedTool = true;

        assertThatThrownBy(() -> client().execute(approval(
                        "create_customer", Map.of("customer_name", "海港食品"))))
                .isInstanceOf(KehuzxWriteException.class)
                .extracting(error -> ((KehuzxWriteException) error).code())
                .isEqualTo(KehuzxWriteException.Code.KEHUZX_WRITE_CONTRACT_DRIFT);
        assertThat(calledTools).isEmpty();
    }

    @Test
    void anyUnknownWriterAdvertisementFailsClosed() {
        advertiseUnknownTool = true;

        assertThatThrownBy(() -> client().execute(approval(
                        "create_customer", Map.of("customer_name", "海港食品"))))
                .isInstanceOf(KehuzxWriteException.class)
                .extracting(error -> ((KehuzxWriteException) error).code())
                .isEqualTo(KehuzxWriteException.Code.KEHUZX_WRITE_AUTH_REJECTED);
        assertThat(calledTools).isEmpty();
    }

    @Test
    void reconciliationReadsTheOriginalRequestAndNeverResubmitsTheWrite() {
        responseStatus = "RECONCILIATION_REQUIRED";

        KehuzxWriteResult result = client().reconcile("request-0001");

        assertThat(result.status()).isEqualTo(KehuzxWriteStatus.RECONCILIATION_REQUIRED);
        assertThat(result.errorCode()).isEqualTo("COMMIT_OUTCOME_UNKNOWN");
        assertThat(calledTools).containsExactly("get_mcp_write_request");
        assertThat(observedArguments.path("request_id").asText()).isEqualTo("request-0001");
        assertThat(observedArguments.has("write_context")).isFalse();
    }

    @Test
    void reconciliationRejectsAResponseOwnedByAnotherRequest() {
        responseRequestId = "request-from-another-assignment";

        assertThatThrownBy(() -> client().reconcile("request-0001"))
                .isInstanceOf(KehuzxWriteException.class)
                .extracting(error -> ((KehuzxWriteException) error).code())
                .isEqualTo(KehuzxWriteException.Code.KEHUZX_WRITE_CONTRACT_DRIFT);
    }

    @Test
    void reconciliationNormalizesAStableRemoteNotFoundOutcome() {
        toolErrorCode = "MCP_WRITE_REQUEST_NOT_FOUND";

        KehuzxWriteResult result = client().reconcile("request-0001");

        assertThat(result.status()).isEqualTo(KehuzxWriteStatus.FAILED);
        assertThat(result.requestId()).isEqualTo("request-0001");
        assertThat(result.errorCode()).isEqualTo("MCP_WRITE_REQUEST_NOT_FOUND");
        assertThat(calledTools).containsExactly("get_mcp_write_request");
    }

    @Test
    void persistedFailedRetryableAndInProgressStatusesAreReturnedWithoutInference() {
        for (KehuzxWriteStatus expected : List.of(
                KehuzxWriteStatus.FAILED,
                KehuzxWriteStatus.FAILED_RETRYABLE,
                KehuzxWriteStatus.IN_PROGRESS)) {
            responseStatus = expected.name();

            KehuzxWriteResult result = client().execute(approval(
                    "create_customer", Map.of("customer_name", "海港食品")));

            assertThat(result.status()).isEqualTo(expected);
            assertThat(result.requestId()).isEqualTo("request-0001");
        }
        assertThat(calledTools).containsExactly(
                "create_customer", "create_customer", "create_customer");
    }

    @Test
    void uncertainToolCallOutcomeReturnsReconciliationRequiredInsteadOfRetrying() {
        delayToolCall = true;
        properties.setReadTimeout(Duration.ofMillis(50));

        KehuzxWriteResult result = client().execute(approval(
                "create_customer", Map.of("customer_name", "海港食品")));

        assertThat(result.status()).isEqualTo(KehuzxWriteStatus.RECONCILIATION_REQUIRED);
        assertThat(result.requestId()).isEqualTo("request-0001");
        assertThat(result.errorCode()).isEqualTo("WRITE_OUTCOME_UNKNOWN");
        assertThat(calledTools).containsExactly("create_customer");
    }

    @Test
    void mismatchedResponseIdentityAndInvalidSuccessAreNeverTrusted() {
        responseRequestId = "request-from-another-assignment";
        KehuzxWriteResult mismatched = client().execute(approval(
                "create_customer", Map.of("customer_name", "海港食品")));
        assertThat(mismatched.status()).isEqualTo(KehuzxWriteStatus.RECONCILIATION_REQUIRED);
        assertThat(mismatched.requestId()).isEqualTo("request-0001");

        responseRequestId = "request-0001";
        omitSuccessResult = true;
        KehuzxWriteResult invalidSuccess = client().execute(approval(
                "create_customer", Map.of("customer_name", "海港食品")));
        assertThat(invalidSuccess.status()).isEqualTo(KehuzxWriteStatus.RECONCILIATION_REQUIRED);
    }

    @Test
    void explicitRemoteRejectionAndRetryableFailureKeepTheirConclusiveStatus() {
        toolErrorCode = "APPROVAL_GRANT_INVALID";
        KehuzxWriteResult rejected = client().execute(approval(
                "create_customer", Map.of("customer_name", "海港食品")));
        assertThat(rejected.status()).isEqualTo(KehuzxWriteStatus.FAILED);
        assertThat(rejected.errorCode()).isEqualTo("APPROVAL_GRANT_INVALID");

        toolErrorCode = "WRITE_FAILED_RETRYABLE";
        KehuzxWriteResult retryable = client().execute(approval(
                "create_customer", Map.of("customer_name", "海港食品")));
        assertThat(retryable.status()).isEqualTo(KehuzxWriteStatus.FAILED_RETRYABLE);
        assertThat(retryable.errorCode()).isEqualTo("WRITE_FAILED_RETRYABLE");
    }

    @Test
    void responseBodyReadHonorsTheEndToEndDeadline() {
        stallToolBody = true;
        properties.setReadTimeout(Duration.ofMillis(300));
        long started = System.nanoTime();

        KehuzxWriteResult result = client().execute(approval(
                "create_customer", Map.of("customer_name", "海港食品")));

        assertThat(result.status()).isEqualTo(KehuzxWriteStatus.RECONCILIATION_REQUIRED);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(800));
    }

    @Test
    void unapprovedLocalToolNeverCrossesTheNetwork() {
        assertThatThrownBy(() -> client().execute(approval(
                        "future_write_tool", Map.of("customer_name", "海港食品"))))
                .isInstanceOf(KehuzxWriteException.class)
                .extracting(error -> ((KehuzxWriteException) error).code())
                .isEqualTo(KehuzxWriteException.Code.KEHUZX_WRITE_TOOL_REJECTED);
        assertThat(calledTools).isEmpty();
    }

    private KehuzxMcpWriteClient client() {
        var signer = new KehuzxApprovalGrantSigner(
                mapper,
                properties.getApprovalSigningKey(),
                Clock.fixed(Instant.parse("2026-08-26T02:00:00Z"), ZoneOffset.UTC),
                120);
        return new KehuzxMcpWriteClient(properties, mapper, signer);
    }

    private static KehuzxApprovalGrantSigner.Approval approval(
            String operation, Map<String, Object> payload) {
        return new KehuzxApprovalGrantSigner.Approval(
                "approval-1",
                "operator-7",
                "王审批",
                "customer:assignment-1",
                operation,
                payload,
                3,
                null,
                "request-0001",
                "idempotency-0001");
    }

    private void handle(HttpExchange exchange) throws IOException {
        observedAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
        if ("DELETE".equals(exchange.getRequestMethod())) {
            respondNoContent(exchange, 200);
            return;
        }
        JsonNode request = mapper.readTree(exchange.getRequestBody());
        String method = request.path("method").asText();
        switch (method) {
            case "initialize" -> {
                exchange.getResponseHeaders().add("Mcp-Session-Id", "writer-session-1");
                respond(exchange, 200, envelope(request, mapper.createObjectNode()
                        .put("protocolVersion", "2025-06-18")));
            }
            case "notifications/initialized" -> respondNoContent(exchange, 202);
            case "tools/list" -> {
                var tools = mapper.createArrayNode();
                int index = 0;
                for (String tool : KehuzxWriteGateway.WRITER_ADVERTISED_TOOLS) {
                    if (!omitAdvertisedTool || index++ > 0) {
                        tools.addObject().put("name", tool);
                    }
                }
                if (advertiseUnknownTool) {
                    tools.addObject().put("name", "future_remote_tool");
                }
                respond(exchange, 200, envelope(request, mapper.createObjectNode().set("tools", tools)));
            }
            case "tools/call" -> {
                String tool = request.path("params").path("name").asText();
                calledTools.add(tool);
                observedArguments = request.path("params").path("arguments");
                if (delayToolCall) {
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
                if (stallToolBody) {
                    respondStalled(exchange);
                    return;
                }
                if (toolErrorCode != null) {
                    var content = mapper.createArrayNode();
                    content.addObject().put("type", "text").put("text", toolErrorCode + ": rejected");
                    respond(exchange, 200, envelope(request, mapper.createObjectNode()
                            .put("isError", true)
                            .set("content", content)));
                    return;
                }
                var payload = mapper.createObjectNode()
                        .put("status", responseStatus)
                        .put("request_id", responseRequestId)
                        .put("idempotency_key", responseIdempotencyKey);
                if ("SUCCEEDED".equals(responseStatus) && !omitSuccessResult) {
                    payload.putObject("result")
                            .put("customer_id", "customer-9")
                            .put("code", "KH-009")
                            .put("name", "海港食品");
                } else {
                    payload.put("error_code", "COMMIT_OUTCOME_UNKNOWN");
                    payload.put("error_message", "必须回读");
                }
                var content = mapper.createArrayNode();
                content.addObject().put("type", "text").put("text", mapper.writeValueAsString(payload));
                respond(exchange, 200, envelope(request, mapper.createObjectNode()
                        .put("isError", false)
                        .set("content", content)));
            }
            default -> respond(exchange, 400, "{}");
        }
    }

    private String envelope(JsonNode request, JsonNode result) throws IOException {
        var envelope = mapper.createObjectNode();
        envelope.put("jsonrpc", "2.0");
        envelope.set("id", request.path("id"));
        envelope.set("result", result);
        return "data: " + mapper.writeValueAsString(envelope) + "\n\n";
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(status, bytes.length);
        try {
            exchange.getResponseBody().write(bytes);
        } catch (IOException ignored) {
            // Client timeout is intentional in the uncertainty test.
        }
        exchange.close();
    }

    private static void respondNoContent(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }

    private static void respondStalled(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 1024);
        exchange.getResponseBody().write("d".getBytes(StandardCharsets.UTF_8));
        exchange.getResponseBody().flush();
        try {
            Thread.sleep(1000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        exchange.close();
    }
}
