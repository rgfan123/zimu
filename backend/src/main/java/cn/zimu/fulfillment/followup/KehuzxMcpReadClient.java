package cn.zimu.fulfillment.followup;

import static cn.zimu.fulfillment.followup.KehuzxReadException.Code.KEHUZX_AUTH_REJECTED;
import static cn.zimu.fulfillment.followup.KehuzxReadException.Code.KEHUZX_CONTRACT_DRIFT;
import static cn.zimu.fulfillment.followup.KehuzxReadException.Code.KEHUZX_NOT_CONFIGURED;
import static cn.zimu.fulfillment.followup.KehuzxReadException.Code.KEHUZX_TIMEOUT;
import static cn.zimu.fulfillment.followup.KehuzxReadException.Code.KEHUZX_TOOL_FAILED;
import static cn.zimu.fulfillment.followup.KehuzxReadException.Code.KEHUZX_UNREACHABLE;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Stateless Streamable-HTTP MCP client using only the deployment-provided read token. */
@Component
public class KehuzxMcpReadClient implements KehuzxReadGateway {

    private static final Logger log = LoggerFactory.getLogger(KehuzxMcpReadClient.class);
    private static final String PROTOCOL_VERSION = "2025-06-18";
    private static final int MAX_RESPONSE_BYTES = 1_048_576;
    private final KehuzxMcpProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient client;
    private final AtomicLong requestIds = new AtomicLong();

    @Autowired
    public KehuzxMcpReadClient(KehuzxMcpProperties properties, ObjectMapper mapper) {
        this(
                properties,
                mapper,
                HttpClient.newBuilder()
                        .connectTimeout(properties.getConnectTimeout())
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build());
    }

    KehuzxMcpReadClient(
            KehuzxMcpProperties properties, ObjectMapper mapper, HttpClient client) {
        this.properties = properties;
        this.mapper = mapper;
        this.client = client;
    }

    @Override
    public JsonNode call(String toolName, Map<String, Object> arguments) {
        if (!APPROVED_TOOLS.contains(toolName)) {
            throw new KehuzxReadException(KEHUZX_TOOL_FAILED);
        }
        if (!properties.isReady()) {
            throw new KehuzxReadException(KEHUZX_NOT_CONFIGURED);
        }
        Session session = initialize();
        try {
            notifyInitialized(session.id());
            Set<String> advertised = listTools(session.id());
            if (!advertised.contains(toolName)) {
                throw new KehuzxReadException(KEHUZX_CONTRACT_DRIFT);
            }
            ObjectNode params = mapper.createObjectNode();
            params.put("name", toolName);
            params.set("arguments", mapper.valueToTree(arguments == null ? Map.of() : arguments));
            JsonNode result = rpc(session.id(), "tools/call", params);
            if (result.path("isError").asBoolean(false)) {
                throw new KehuzxReadException(KEHUZX_TOOL_FAILED);
            }
            JsonNode content = result.path("content");
            if (!content.isArray() || content.isEmpty() || !content.get(0).path("text").isTextual()) {
                throw new KehuzxReadException(KEHUZX_CONTRACT_DRIFT);
            }
            try {
                JsonNode payload = mapper.readTree(content.get(0).path("text").asText());
                validateToolPayload(toolName, payload);
                return payload;
            } catch (IOException ex) {
                throw new KehuzxReadException(KEHUZX_CONTRACT_DRIFT, ex);
            }
        } finally {
            closeSession(session.id());
        }
    }

    private Session initialize() {
        ObjectNode params = mapper.createObjectNode();
        params.put("protocolVersion", PROTOCOL_VERSION);
        params.putObject("capabilities");
        ObjectNode clientInfo = params.putObject("clientInfo");
        clientInfo.put("name", "zimu-business-followup");
        clientInfo.put("version", "1");
        ObjectNode request = rpcBody("initialize", params);
        ResponsePayload response = send(request, null);
        String sessionId = response.headers().firstValue("Mcp-Session-Id")
                .orElseThrow(() -> new KehuzxReadException(KEHUZX_CONTRACT_DRIFT));
        try {
            JsonNode result = rpcResult(response.body(), request.path("id"));
            if (!PROTOCOL_VERSION.equals(result.path("protocolVersion").asText())) {
                throw new KehuzxReadException(KEHUZX_CONTRACT_DRIFT);
            }
            return new Session(sessionId);
        } catch (RuntimeException failure) {
            closeSession(sessionId);
            throw failure;
        }
    }

    private void notifyInitialized(String sessionId) {
        ObjectNode body = mapper.createObjectNode();
        body.put("jsonrpc", "2.0");
        body.put("method", "notifications/initialized");
        send(body, sessionId);
    }

    private Set<String> listTools(String sessionId) {
        JsonNode tools = rpc(sessionId, "tools/list", mapper.createObjectNode()).path("tools");
        if (!tools.isArray()) {
            throw new KehuzxReadException(KEHUZX_CONTRACT_DRIFT);
        }
        java.util.Set<String> names = new java.util.HashSet<>();
        for (JsonNode tool : tools) {
            if (!tool.isObject() || !tool.path("name").isTextual()
                    || tool.path("name").asText().isBlank()) {
                throw new KehuzxReadException(KEHUZX_CONTRACT_DRIFT);
            }
            if (!names.add(tool.path("name").asText())) {
                throw new KehuzxReadException(KEHUZX_CONTRACT_DRIFT);
            }
        }
        if (names.stream().anyMatch(name -> !APPROVED_TOOLS.contains(name))) {
            throw new KehuzxReadException(KEHUZX_AUTH_REJECTED);
        }
        if (!names.equals(APPROVED_TOOLS)) {
            throw new KehuzxReadException(KEHUZX_CONTRACT_DRIFT);
        }
        return Set.copyOf(names);
    }

    private JsonNode rpc(String sessionId, String method, JsonNode params) {
        ObjectNode request = rpcBody(method, params);
        return rpcResult(send(request, sessionId).body(), request.path("id"));
    }

    private void closeSession(String sessionId) {
        HttpRequest request = HttpRequest.newBuilder(properties.getEndpoint())
                .timeout(properties.getReadTimeout())
                .header("Authorization", "Bearer " + properties.getReadToken())
                .header("Mcp-Session-Id", sessionId)
                .header("MCP-Protocol-Version", PROTOCOL_VERSION)
                .DELETE()
                .build();
        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream stream = response.body()) {
                stream.readNBytes(MAX_RESPONSE_BYTES + 1);
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("Kehuzx MCP session cleanup failed with stable code KEHUZX_SESSION_CLEANUP_FAILED");
            }
        } catch (IOException ex) {
            log.warn("Kehuzx MCP session cleanup failed with stable code KEHUZX_SESSION_CLEANUP_FAILED");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("Kehuzx MCP session cleanup failed with stable code KEHUZX_SESSION_CLEANUP_FAILED");
        }
    }

    private ObjectNode rpcBody(String method, JsonNode params) {
        ObjectNode body = mapper.createObjectNode();
        body.put("jsonrpc", "2.0");
        body.put("id", requestIds.incrementAndGet());
        body.put("method", method);
        body.set("params", params);
        return body;
    }

    private ResponsePayload send(JsonNode body, String sessionId) {
        HttpRequest.Builder request = HttpRequest.newBuilder(properties.getEndpoint())
                .timeout(properties.getReadTimeout())
                .header("Authorization", "Bearer " + properties.getReadToken())
                .header("Accept", "application/json, text/event-stream")
                .header("Content-Type", "application/json");
        if (sessionId != null) {
            request.header("Mcp-Session-Id", sessionId);
            request.header("MCP-Protocol-Version", PROTOCOL_VERSION);
        }
        try {
            HttpResponse<InputStream> response = client.send(
                    request.POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            byte[] bytes;
            try (InputStream stream = response.body()) {
                bytes = stream.readNBytes(MAX_RESPONSE_BYTES + 1);
            }
            if (bytes.length > MAX_RESPONSE_BYTES) {
                throw new KehuzxReadException(KEHUZX_CONTRACT_DRIFT);
            }
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                throw new KehuzxReadException(KEHUZX_AUTH_REJECTED);
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new KehuzxReadException(KEHUZX_UNREACHABLE);
            }
            if (!body.has("id") && response.statusCode() == 202) {
                return new ResponsePayload(response.headers(), "");
            }
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            if (!contentType.startsWith("application/json")
                    && !contentType.startsWith("text/event-stream")) {
                throw new KehuzxReadException(KEHUZX_CONTRACT_DRIFT);
            }
            return new ResponsePayload(
                    response.headers(), new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
        } catch (HttpTimeoutException ex) {
            throw new KehuzxReadException(KEHUZX_TIMEOUT, ex);
        } catch (ConnectException ex) {
            throw new KehuzxReadException(KEHUZX_UNREACHABLE, ex);
        } catch (IOException ex) {
            throw new KehuzxReadException(KEHUZX_UNREACHABLE, ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new KehuzxReadException(KEHUZX_UNREACHABLE, ex);
        }
    }

    private JsonNode rpcResult(String responseBody, JsonNode expectedId) {
        try {
            String json = responseBody.lines()
                    .filter(line -> line.startsWith("data: "))
                    .map(line -> line.substring(6))
                    .findFirst()
                    .orElse(responseBody);
            JsonNode envelope = mapper.readTree(json);
            if (envelope.has("error")
                    || !envelope.has("result")
                    || !sameRpcId(expectedId, envelope.path("id"))) {
                throw new KehuzxReadException(KEHUZX_TOOL_FAILED);
            }
            return envelope.path("result");
        } catch (IOException ex) {
            throw new KehuzxReadException(KEHUZX_CONTRACT_DRIFT, ex);
        }
    }

    private static boolean sameRpcId(JsonNode expected, JsonNode actual) {
        if (expected == null || actual == null || expected.isMissingNode() || actual.isMissingNode()) {
            return false;
        }
        if (expected.isIntegralNumber() && actual.isIntegralNumber()) {
            return expected.longValue() == actual.longValue();
        }
        return expected.isTextual() && actual.isTextual() && expected.textValue().equals(actual.textValue());
    }

    private static void validateToolPayload(String toolName, JsonNode payload) {
        if (payload == null || !payload.isObject() || payload.has("error")) {
            throw new KehuzxReadException(KEHUZX_TOOL_FAILED);
        }
        if (toolName.startsWith("search_")) {
            JsonNode total = payload.path("total");
            JsonNode items = payload.path("items");
            if (!total.canConvertToInt()
                    || total.asInt() < 0
                    || !items.isArray()
                    || items.size() > 100) {
                throw new KehuzxReadException(KEHUZX_CONTRACT_DRIFT);
            }
            for (JsonNode item : items) {
                if (!item.isObject()) {
                    throw new KehuzxReadException(KEHUZX_CONTRACT_DRIFT);
                }
            }
            return;
        }
        if ("get_customer_detail".equals(toolName)) {
            if (!payload.path("customer").isObject()) {
                throw new KehuzxReadException(KEHUZX_CONTRACT_DRIFT);
            }
            return;
        }
        if ("get_order_detail".equals(toolName)
                && payload.path("id").asText("").isBlank()
                && payload.path("code").asText("").isBlank()) {
            throw new KehuzxReadException(KEHUZX_CONTRACT_DRIFT);
        }
    }

    private record Session(String id) {}

    private record ResponsePayload(java.net.http.HttpHeaders headers, String body) {}
}
