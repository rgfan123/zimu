package cn.zimu.fulfillment.followup;

import static cn.zimu.fulfillment.followup.KehuzxWriteException.Code.KEHUZX_WRITE_AUTH_REJECTED;
import static cn.zimu.fulfillment.followup.KehuzxWriteException.Code.KEHUZX_WRITE_CONTRACT_DRIFT;
import static cn.zimu.fulfillment.followup.KehuzxWriteException.Code.KEHUZX_WRITE_NOT_CONFIGURED;
import static cn.zimu.fulfillment.followup.KehuzxWriteException.Code.KEHUZX_WRITE_TIMEOUT;
import static cn.zimu.fulfillment.followup.KehuzxWriteException.Code.KEHUZX_WRITE_TOOL_REJECTED;
import static cn.zimu.fulfillment.followup.KehuzxWriteException.Code.KEHUZX_WRITE_UNREACHABLE;

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
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Strict Streamable-HTTP client for the independently credentialed Kehuzx writer. */
@Component
public class KehuzxMcpWriteClient implements KehuzxWriteGateway {

    private static final String PROTOCOL_VERSION = "2025-06-18";
    private static final ExecutorService BODY_READERS =
            Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("kehuzx-mcp-body-", 0).factory());
    private static final Pattern STABLE_REMOTE_CODE =
            Pattern.compile("\\b([A-Z][A-Z0-9_]{2,63})\\s*:");
    private static final Set<String> FAILED_CODES = Set.of(
            "APPROVAL_GRANT_INVALID",
            "APPROVAL_CONTEXT_CONFLICT",
            "REQUEST_ID_CONFLICT",
            "IDEMPOTENCY_CONFLICT",
            "ORDER_TYPE_INVALID",
            "ENTITY_REFERENCE_INVALID",
            "DATABASE_WRITE_REJECTED",
            "MCP_WRITE_REQUEST_LOOKUP_INVALID",
            "MCP_WRITE_REQUEST_NOT_FOUND",
            "MCP_WRITE_DISABLED");
    private static final Set<String> RETRYABLE_CODES = Set.of(
            "WRITE_FAILED_RETRYABLE", "DATABASE_CONFLICT_RETRYABLE");
    private static final Set<String> UNKNOWN_CODES = Set.of(
            "COMMIT_OUTCOME_UNKNOWN", "WRITE_RECOVERY_UNCERTAIN");
    private final KehuzxMcpWriteProperties properties;
    private final ObjectMapper mapper;
    private final KehuzxApprovalGrantSigner signer;
    private final HttpClient client;
    private final AtomicLong requestIds = new AtomicLong();

    @Autowired
    public KehuzxMcpWriteClient(
            KehuzxMcpWriteProperties properties,
            ObjectMapper mapper,
            KehuzxApprovalGrantSigner signer) {
        this(
                properties,
                mapper,
                signer,
                HttpClient.newBuilder()
                        .connectTimeout(properties.getConnectTimeout())
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build());
    }

    KehuzxMcpWriteClient(
            KehuzxMcpWriteProperties properties,
            ObjectMapper mapper,
            KehuzxApprovalGrantSigner signer,
            HttpClient client) {
        this.properties = properties;
        this.mapper = mapper;
        this.signer = signer;
        this.client = client;
    }

    @Override
    public KehuzxWriteResult execute(KehuzxApprovalGrantSigner.Approval approval) {
        if (approval == null || !EXECUTABLE_WRITE_TOOLS.contains(approval.operation())) {
            throw new KehuzxWriteException(KEHUZX_WRITE_TOOL_REJECTED);
        }
        requireReady();
        Session session = initializeAndVerifyWriter();
        try {
            ObjectNode arguments = mapper.valueToTree(
                    approval.payload() == null ? Map.of() : approval.payload());
            ObjectNode writeContext = mapper.createObjectNode();
            writeContext.put("idempotency_key", approval.idempotencyKey());
            writeContext.put("request_id", approval.requestId());
            writeContext.put("logical_target", approval.logicalTarget());
            writeContext.put("approval_grant", signer.sign(approval));
            arguments.set("write_context", writeContext);
            try {
                return parseWriteResult(
                        callTool(session.id(), approval.operation(), arguments),
                        approval.requestId(),
                        approval.idempotencyKey(),
                        approval.operation());
            } catch (RemoteToolOutcome outcome) {
                return new KehuzxWriteResult(
                        outcome.status(),
                        approval.requestId(),
                        approval.idempotencyKey(),
                        null,
                        outcome.code(),
                        "Kehuzx 明确返回写入结局",
                        null,
                        null);
            } catch (KehuzxWriteException uncertain) {
                if (uncertain.code() == KEHUZX_WRITE_AUTH_REJECTED) {
                    throw uncertain;
                }
                return KehuzxWriteResult.outcomeUnknown(
                        approval.requestId(), approval.idempotencyKey());
            }
        } finally {
            closeSession(session.id());
        }
    }

    @Override
    public KehuzxWriteResult reconcile(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("requestId must not be blank");
        }
        requireReady();
        Session session = initializeAndVerifyWriter();
        try {
            ObjectNode arguments = mapper.createObjectNode().put("request_id", requestId);
            try {
                return parseWriteResult(
                        callTool(session.id(), "get_mcp_write_request", arguments),
                        requestId,
                        null,
                        null);
            } catch (RemoteToolOutcome outcome) {
                return new KehuzxWriteResult(
                        outcome.status(),
                        requestId,
                        null,
                        null,
                        outcome.code(),
                        "Kehuzx 明确返回对账结局",
                        null,
                        null);
            }
        } finally {
            closeSession(session.id());
        }
    }

    private void requireReady() {
        if (!properties.isReady()) {
            throw new KehuzxWriteException(KEHUZX_WRITE_NOT_CONFIGURED);
        }
    }

    private Session initializeAndVerifyWriter() {
        ObjectNode params = mapper.createObjectNode();
        params.put("protocolVersion", PROTOCOL_VERSION);
        params.putObject("capabilities");
        params.putObject("clientInfo")
                .put("name", "zimu-deterministic-kehuzx-writer")
                .put("version", "1");
        ObjectNode request = rpcBody("initialize", params);
        ResponsePayload response = send(request, null);
        String sessionId = response.headers().firstValue("Mcp-Session-Id")
                .orElseThrow(() -> new KehuzxWriteException(KEHUZX_WRITE_CONTRACT_DRIFT));
        try {
            JsonNode result = rpcResult(response.body(), request.path("id"));
            if (!PROTOCOL_VERSION.equals(result.path("protocolVersion").asText())) {
                throw new KehuzxWriteException(KEHUZX_WRITE_CONTRACT_DRIFT);
            }
            notifyInitialized(sessionId);
            verifyWriterTools(sessionId);
            return new Session(sessionId);
        } catch (RuntimeException failure) {
            closeSession(sessionId);
            throw failure;
        }
    }

    private void notifyInitialized(String sessionId) {
        ObjectNode notification = mapper.createObjectNode();
        notification.put("jsonrpc", "2.0");
        notification.put("method", "notifications/initialized");
        send(notification, sessionId);
    }

    private void verifyWriterTools(String sessionId) {
        JsonNode tools = rpc(sessionId, "tools/list", mapper.createObjectNode()).path("tools");
        if (!tools.isArray()) {
            throw new KehuzxWriteException(KEHUZX_WRITE_CONTRACT_DRIFT);
        }
        Set<String> names = new HashSet<>();
        for (JsonNode tool : tools) {
            if (!tool.isObject()
                    || !tool.path("name").isTextual()
                    || tool.path("name").asText().isBlank()
                    || !names.add(tool.path("name").asText())) {
                throw new KehuzxWriteException(KEHUZX_WRITE_CONTRACT_DRIFT);
            }
        }
        if (names.stream().anyMatch(name -> !WRITER_ADVERTISED_TOOLS.contains(name))) {
            throw new KehuzxWriteException(KEHUZX_WRITE_AUTH_REJECTED);
        }
        if (!names.equals(WRITER_ADVERTISED_TOOLS)) {
            throw new KehuzxWriteException(KEHUZX_WRITE_CONTRACT_DRIFT);
        }
    }

    private JsonNode callTool(String sessionId, String toolName, ObjectNode arguments) {
        ObjectNode params = mapper.createObjectNode();
        params.put("name", toolName);
        params.set("arguments", arguments);
        JsonNode result = rpc(sessionId, "tools/call", params);
        if (result.path("isError").asBoolean(false)) {
            throw remoteToolOutcome(result.path("content"));
        }
        JsonNode content = result.path("content");
        if (!content.isArray()
                || content.isEmpty()
                || !content.get(0).path("text").isTextual()) {
            throw new KehuzxWriteException(KEHUZX_WRITE_CONTRACT_DRIFT);
        }
        try {
            return mapper.readTree(content.get(0).path("text").asText());
        } catch (IOException ex) {
            throw new KehuzxWriteException(KEHUZX_WRITE_CONTRACT_DRIFT, ex);
        }
    }

    private KehuzxWriteResult parseWriteResult(
            JsonNode payload,
            String expectedRequestId,
            String expectedIdempotencyKey,
            String operation) {
        if (payload == null || !payload.isObject()) {
            throw new KehuzxWriteException(KEHUZX_WRITE_CONTRACT_DRIFT);
        }
        KehuzxWriteStatus status;
        try {
            status = KehuzxWriteStatus.valueOf(payload.path("status").asText());
        } catch (IllegalArgumentException ex) {
            throw new KehuzxWriteException(KEHUZX_WRITE_CONTRACT_DRIFT, ex);
        }
        String requestId = requiredText(payload, "request_id");
        String idempotencyKey = requiredText(payload, "idempotency_key");
        if (!expectedRequestId.equals(requestId)
                || (expectedIdempotencyKey != null
                        && !expectedIdempotencyKey.equals(idempotencyKey))) {
            throw new KehuzxWriteException(KEHUZX_WRITE_CONTRACT_DRIFT);
        }
        JsonNode result = payload.path("result");
        if (result.isMissingNode() || result.isNull()) {
            result = null;
        }
        String externalType = optionalText(payload, "external_entity_type");
        String externalId = optionalText(payload, "external_entity_id");
        if (result != null && result.isObject()) {
            if (externalType == null && result.hasNonNull("customer_id")) {
                externalType = "customer";
                externalId = result.path("customer_id").asText();
            } else if (externalType == null && result.hasNonNull("sample_id")) {
                externalType = "sample";
                externalId = result.path("sample_id").asText();
            } else if (externalType == null && result.hasNonNull("order_id")) {
                externalType = "order";
                externalId = result.path("order_id").asText();
            }
        }
        if (status == KehuzxWriteStatus.SUCCEEDED) {
            if (result == null || !result.isObject()) {
                throw new KehuzxWriteException(KEHUZX_WRITE_CONTRACT_DRIFT);
            }
            if ("create_customer".equals(operation)
                    && (!"customer".equals(externalType)
                            || externalId == null
                            || externalId.isBlank())) {
                throw new KehuzxWriteException(KEHUZX_WRITE_CONTRACT_DRIFT);
            }
        }
        return new KehuzxWriteResult(
                status,
                requestId,
                idempotencyKey,
                result,
                optionalText(payload, "error_code"),
                optionalText(payload, "error_message"),
                externalType,
                externalId);
    }

    private RemoteToolOutcome remoteToolOutcome(JsonNode content) {
        String text = content.isArray()
                        && !content.isEmpty()
                        && content.get(0).path("text").isTextual()
                ? content.get(0).path("text").asText()
                : "";
        Matcher matcher = STABLE_REMOTE_CODE.matcher(text);
        String code = matcher.find() ? matcher.group(1) : null;
        if (code == null && text.contains("MCP 写功能未启用")) {
            code = "MCP_WRITE_DISABLED";
        }
        if (FAILED_CODES.contains(code)) {
            return new RemoteToolOutcome(KehuzxWriteStatus.FAILED, code);
        }
        if (RETRYABLE_CODES.contains(code)) {
            return new RemoteToolOutcome(KehuzxWriteStatus.FAILED_RETRYABLE, code);
        }
        if (UNKNOWN_CODES.contains(code)) {
            return new RemoteToolOutcome(KehuzxWriteStatus.RECONCILIATION_REQUIRED, code);
        }
        throw new KehuzxWriteException(KEHUZX_WRITE_CONTRACT_DRIFT);
    }

    private String requiredText(JsonNode node, String field) {
        String value = optionalText(node, field);
        if (value == null) {
            throw new KehuzxWriteException(KEHUZX_WRITE_CONTRACT_DRIFT);
        }
        return value;
    }

    private static String optionalText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }

    private JsonNode rpc(String sessionId, String method, JsonNode params) {
        ObjectNode request = rpcBody(method, params);
        return rpcResult(send(request, sessionId).body(), request.path("id"));
    }

    private ObjectNode rpcBody(String method, JsonNode params) {
        ObjectNode request = mapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", requestIds.incrementAndGet());
        request.put("method", method);
        request.set("params", params);
        return request;
    }

    private ResponsePayload send(JsonNode body, String sessionId) {
        long deadlineNanos = System.nanoTime() + properties.getReadTimeout().toNanos();
        HttpRequest.Builder request = HttpRequest.newBuilder(properties.getEndpoint())
                .timeout(properties.getReadTimeout())
                .header("Authorization", "Bearer " + properties.getWriteToken())
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
            byte[] bytes = readBody(response.body(), deadlineNanos);
            if (bytes.length > properties.getMaxResponseBytes()) {
                throw new KehuzxWriteException(KEHUZX_WRITE_CONTRACT_DRIFT);
            }
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                throw new KehuzxWriteException(KEHUZX_WRITE_AUTH_REJECTED);
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new KehuzxWriteException(KEHUZX_WRITE_UNREACHABLE);
            }
            if (!body.has("id") && response.statusCode() == 202) {
                return new ResponsePayload(response.headers(), "");
            }
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            if (!contentType.startsWith("application/json")
                    && !contentType.startsWith("text/event-stream")) {
                throw new KehuzxWriteException(KEHUZX_WRITE_CONTRACT_DRIFT);
            }
            return new ResponsePayload(
                    response.headers(), new String(bytes, StandardCharsets.UTF_8));
        } catch (HttpTimeoutException ex) {
            throw new KehuzxWriteException(KEHUZX_WRITE_TIMEOUT, ex);
        } catch (ConnectException ex) {
            throw new KehuzxWriteException(KEHUZX_WRITE_UNREACHABLE, ex);
        } catch (IOException ex) {
            throw new KehuzxWriteException(KEHUZX_WRITE_UNREACHABLE, ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new KehuzxWriteException(KEHUZX_WRITE_UNREACHABLE, ex);
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
                throw new KehuzxWriteException(KEHUZX_WRITE_CONTRACT_DRIFT);
            }
            return envelope.path("result");
        } catch (IOException ex) {
            throw new KehuzxWriteException(KEHUZX_WRITE_CONTRACT_DRIFT, ex);
        }
    }

    private void closeSession(String sessionId) {
        long deadlineNanos = System.nanoTime() + properties.getReadTimeout().toNanos();
        HttpRequest request = HttpRequest.newBuilder(properties.getEndpoint())
                .timeout(properties.getReadTimeout())
                .header("Authorization", "Bearer " + properties.getWriteToken())
                .header("Mcp-Session-Id", sessionId)
                .header("MCP-Protocol-Version", PROTOCOL_VERSION)
                .DELETE()
                .build();
        try {
            HttpResponse<InputStream> response = client.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());
            readBody(response.body(), deadlineNanos);
        } catch (IOException ignored) {
            // Cleanup cannot change the already observed business outcome.
        } catch (KehuzxWriteException ignored) {
            // Cleanup cannot change the already observed business outcome.
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private byte[] readBody(InputStream stream, long deadlineNanos) throws IOException {
        Future<byte[]> reader = BODY_READERS.submit(() -> {
            try (InputStream input = stream) {
                return input.readNBytes(properties.getMaxResponseBytes() + 1);
            }
        });
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
            reader.cancel(true);
            try {
                stream.close();
            } catch (IOException ignored) {
                // Preserve timeout semantics.
            }
            throw new KehuzxWriteException(KEHUZX_WRITE_TIMEOUT);
        }
        try {
            return reader.get(remainingNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException timeout) {
            reader.cancel(true);
            try {
                stream.close();
            } catch (IOException ignored) {
                // Preserve timeout semantics.
            }
            throw new KehuzxWriteException(KEHUZX_WRITE_TIMEOUT, timeout);
        } catch (InterruptedException interrupted) {
            reader.cancel(true);
            Thread.currentThread().interrupt();
            throw new KehuzxWriteException(KEHUZX_WRITE_UNREACHABLE, interrupted);
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            throw new KehuzxWriteException(KEHUZX_WRITE_UNREACHABLE, cause);
        }
    }

    private static boolean sameRpcId(JsonNode expected, JsonNode actual) {
        if (expected == null || actual == null || expected.isMissingNode() || actual.isMissingNode()) {
            return false;
        }
        if (expected.isIntegralNumber() && actual.isIntegralNumber()) {
            return expected.longValue() == actual.longValue();
        }
        return expected.isTextual() && actual.isTextual()
                && expected.textValue().equals(actual.textValue());
    }

    private record Session(String id) {}

    private record ResponsePayload(java.net.http.HttpHeaders headers, String body) {}

    private static final class RemoteToolOutcome extends RuntimeException {
        private final KehuzxWriteStatus status;
        private final String code;

        private RemoteToolOutcome(KehuzxWriteStatus status, String code) {
            super(code);
            this.status = status;
            this.code = code;
        }

        private KehuzxWriteStatus status() { return status; }
        private String code() { return code; }
    }
}
