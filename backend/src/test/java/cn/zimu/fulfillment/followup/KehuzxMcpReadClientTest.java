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
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class KehuzxMcpReadClientTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private KehuzxMcpProperties properties;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger deletes = new AtomicInteger();
    private volatile boolean advertiseSearchCustomers = true;
    private volatile boolean delayInitialize;
    private volatile boolean invalidToolPayload;
    private volatile boolean advertiseWriteTool;
    private volatile boolean advertiseUnknownTool;
    private volatile boolean invalidProtocolVersion;
    private volatile String observedAuthorization;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", this::handle);
        server.start();
        properties = new KehuzxMcpProperties();
        properties.setEnabled(true);
        properties.setEndpoint(URI.create(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp"));
        properties.setAllowedHost("127.0.0.1");
        properties.setAllowedPort(server.getAddress().getPort());
        properties.setReadToken("read-token-must-never-be-written");
        properties.setConnectTimeout(Duration.ofSeconds(1));
        properties.setReadTimeout(Duration.ofSeconds(1));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void approvedReadToolUsesBearerSessionAndParsesTheStructuredResult() {
        JsonNode result = new KehuzxMcpReadClient(properties, mapper).call(
                "search_customers", Map.of("keyword", "海港"));

        assertThat(result.path("total").asInt()).isEqualTo(1);
        assertThat(result.path("items").get(0).path("customer_id").asText())
                .isEqualTo("customer-1");
        assertThat(observedAuthorization).isEqualTo("Bearer read-token-must-never-be-written");
        assertThat(calls).hasValue(5);
        assertThat(deletes).hasValue(1);
    }

    @Test
    void writeToolsAreRejectedLocallyWithoutAnyNetworkRequest() {
        assertThatThrownBy(() -> new KehuzxMcpReadClient(properties, mapper).call(
                        "create_customer", Map.of("customer_name", "不应发送")))
                .isInstanceOf(KehuzxReadException.class)
                .extracting(error -> ((KehuzxReadException) error).code())
                .isEqualTo(KehuzxReadException.Code.KEHUZX_TOOL_FAILED);
        assertThat(calls).hasValue(0);
    }

    @Test
    void overprivilegedTokenFailsClosedWhenWriteToolsAreAdvertised() {
        advertiseWriteTool = true;

        assertThatThrownBy(() -> new KehuzxMcpReadClient(properties, mapper).call(
                        "search_customers", Map.of()))
                .isInstanceOf(KehuzxReadException.class)
                .extracting(error -> ((KehuzxReadException) error).code())
                .isEqualTo(KehuzxReadException.Code.KEHUZX_AUTH_REJECTED);
        assertThat(deletes).hasValue(1);
    }

    @Test
    void tokenWithAnyUnapprovedRemoteToolFailsClosed() {
        advertiseUnknownTool = true;

        assertThatThrownBy(() -> new KehuzxMcpReadClient(properties, mapper).call(
                        "search_customers", Map.of()))
                .isInstanceOf(KehuzxReadException.class)
                .extracting(error -> ((KehuzxReadException) error).code())
                .isEqualTo(KehuzxReadException.Code.KEHUZX_AUTH_REJECTED);
        assertThat(deletes).hasValue(1);
    }

    @Test
    void missingApprovedToolIsContractDriftNotAnAgentGuessingOpportunity() {
        advertiseSearchCustomers = false;

        assertThatThrownBy(() -> new KehuzxMcpReadClient(properties, mapper).call(
                        "search_customers", Map.of()))
                .isInstanceOf(KehuzxReadException.class)
                .extracting(error -> ((KehuzxReadException) error).code())
                .isEqualTo(KehuzxReadException.Code.KEHUZX_CONTRACT_DRIFT);
    }

    @Test
    void requestTimeoutIsStableAndDoesNotExposeTheToken() {
        delayInitialize = true;
        properties.setReadTimeout(Duration.ofMillis(50));

        assertThatThrownBy(() -> new KehuzxMcpReadClient(properties, mapper).call(
                        "search_customers", Map.of()))
                .isInstanceOf(KehuzxReadException.class)
                .hasMessage("KEHUZX_TIMEOUT")
                .hasMessageNotContaining("read-token");
    }

    @Test
    void absentDeploymentConfigurationFailsBeforeNetworkAccess() {
        properties.setEnabled(false);

        assertThatThrownBy(() -> new KehuzxMcpReadClient(properties, mapper).call(
                        "search_customers", Map.of()))
                .isInstanceOf(KehuzxReadException.class)
                .extracting(error -> ((KehuzxReadException) error).code())
                .isEqualTo(KehuzxReadException.Code.KEHUZX_NOT_CONFIGURED);
        assertThat(calls).hasValue(0);
    }

    @Test
    void endpointHostMustMatchTheDedicatedDeploymentAllowlistBeforeTokenUse() {
        properties.setAllowedHost("kehuzx-mcp");

        assertThatThrownBy(() -> new KehuzxMcpReadClient(properties, mapper).call(
                        "search_customers", Map.of()))
                .isInstanceOf(KehuzxReadException.class)
                .extracting(error -> ((KehuzxReadException) error).code())
                .isEqualTo(KehuzxReadException.Code.KEHUZX_NOT_CONFIGURED);
        assertThat(calls).hasValue(0);
    }

    @Test
    void connectionRefusedIsStableUnreachableWithoutTokenLeakage() {
        KehuzxMcpProperties unreachable = new KehuzxMcpProperties();
        unreachable.setEnabled(true);
        unreachable.setEndpoint(URI.create("http://127.0.0.1:1/mcp"));
        unreachable.setAllowedHost("127.0.0.1");
        unreachable.setAllowedPort(1);
        unreachable.setReadToken("unreachable-secret-token");
        unreachable.setConnectTimeout(Duration.ofMillis(100));
        unreachable.setReadTimeout(Duration.ofMillis(100));

        assertThatThrownBy(() -> new KehuzxMcpReadClient(unreachable, mapper).call(
                        "search_customers", Map.of()))
                .isInstanceOf(KehuzxReadException.class)
                .extracting(error -> ((KehuzxReadException) error).code())
                .isEqualTo(KehuzxReadException.Code.KEHUZX_UNREACHABLE);
    }

    @Test
    void toolPayloadShapeDriftFailsClosedAfterSuccessfulProtocolExchange() {
        invalidToolPayload = true;

        assertThatThrownBy(() -> new KehuzxMcpReadClient(properties, mapper).call(
                        "search_customers", Map.of()))
                .isInstanceOf(KehuzxReadException.class)
                .extracting(error -> ((KehuzxReadException) error).code())
                .isEqualTo(KehuzxReadException.Code.KEHUZX_CONTRACT_DRIFT);
    }

    @Test
    void initializeContractDriftStillTerminatesTheAllocatedSession() {
        invalidProtocolVersion = true;

        assertThatThrownBy(() -> new KehuzxMcpReadClient(properties, mapper).call(
                        "search_customers", Map.of()))
                .isInstanceOf(KehuzxReadException.class)
                .extracting(error -> ((KehuzxReadException) error).code())
                .isEqualTo(KehuzxReadException.Code.KEHUZX_CONTRACT_DRIFT);
        assertThat(deletes).hasValue(1);
    }

    private void handle(HttpExchange exchange) throws IOException {
        calls.incrementAndGet();
        observedAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
        if ("DELETE".equals(exchange.getRequestMethod())) {
            deletes.incrementAndGet();
            assertThat(exchange.getRequestHeaders().getFirst("MCP-Protocol-Version"))
                    .isEqualTo("2025-06-18");
            respondNoContent(exchange, 200);
            return;
        }
        JsonNode request = mapper.readTree(exchange.getRequestBody());
        String method = request.path("method").asText();
        if (!"initialize".equals(method)) {
            assertThat(exchange.getRequestHeaders().getFirst("MCP-Protocol-Version"))
                    .isEqualTo("2025-06-18");
        }
        if (delayInitialize && "initialize".equals(method)) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        switch (method) {
            case "initialize" -> {
                exchange.getResponseHeaders().add("Mcp-Session-Id", "session-1");
                respond(exchange, 200, envelope(request, mapper.createObjectNode()
                        .put("protocolVersion", invalidProtocolVersion ? "2025-03-26" : "2025-06-18")));
            }
            case "notifications/initialized" -> respondNoContent(exchange, 202);
            case "tools/list" -> {
                var tools = mapper.createArrayNode();
                for (String toolName : KehuzxReadGateway.APPROVED_TOOLS) {
                    if (advertiseSearchCustomers || !"search_customers".equals(toolName)) {
                        tools.addObject().put("name", toolName);
                    }
                }
                if (advertiseWriteTool) {
                    tools.addObject().put("name", "create_customer");
                }
                if (advertiseUnknownTool) {
                    tools.addObject().put("name", "future_remote_tool");
                }
                respond(exchange, 200, envelope(request, mapper.createObjectNode().set("tools", tools)));
            }
            case "tools/call" -> {
                assertThat(exchange.getRequestHeaders().getFirst("Mcp-Session-Id"))
                        .isEqualTo("session-1");
                assertThat(request.path("params").path("name").asText())
                        .isEqualTo("search_customers");
                var payload = invalidToolPayload
                        ? mapper.createObjectNode().put("unexpected", true)
                        : mapper.createObjectNode().put("total", 1);
                if (!invalidToolPayload) {
                    payload.putArray("items").addObject().put("customer_id", "customer-1");
                }
                var content = mapper.createArrayNode();
                content.addObject().put("type", "text").put("text", mapper.writeValueAsString(payload));
                respond(exchange, 200, envelope(
                        request,
                        mapper.createObjectNode().put("isError", false).set("content", content)));
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

    private static void respond(HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void respondNoContent(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }
}
