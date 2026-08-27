package cn.zimu.fulfillment.followup;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Signs the short-lived, Approval-attributed HS256 grant verified by Kehuzx. */
@Component
public class KehuzxApprovalGrantSigner {

    private final ObjectMapper mapper;
    private final byte[] signingKey;
    private final Clock clock;
    private final int ttlSeconds;

    @Autowired
    public KehuzxApprovalGrantSigner(ObjectMapper mapper, KehuzxMcpWriteProperties properties) {
        this(
                mapper,
                properties.getApprovalSigningKey(),
                Clock.systemUTC(),
                properties.getApprovalTtlSeconds());
    }

    KehuzxApprovalGrantSigner(ObjectMapper mapper, String signingKey, Clock clock, int ttlSeconds) {
        this.mapper = mapper;
        this.signingKey = signingKey == null
                ? new byte[0]
                : signingKey.getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
        this.ttlSeconds = ttlSeconds;
    }

    public String sign(Approval approval) {
        requireNonBlank(approval.approvalId(), "approval_id");
        requireNonBlank(approval.operatorId(), "operator_id");
        requireNonBlank(approval.operatorName(), "operator_name");
        requireNonBlank(approval.logicalTarget(), "logical_target");
        requireNonBlank(approval.operation(), "operation");
        requireNonBlank(approval.requestId(), "request_id");
        requireNonBlank(approval.idempotencyKey(), "idempotency_key");
        if (signingKey.length == 0 || ttlSeconds < 1 || ttlSeconds > 300 || approval.draftVersion() < 1) {
            throw new IllegalStateException("Kehuzx Approval grant signing is not safely configured");
        }
        ObjectNode header = mapper.createObjectNode().put("alg", "HS256").put("typ", "JWT");
        ObjectNode claims = mapper.createObjectNode();
        claims.put("iss", "zimu");
        claims.put("aud", "kehuzx-mcp");
        claims.put("approval_id", approval.approvalId().strip());
        claims.put("operator_id", approval.operatorId().strip());
        claims.put("operator_name", approval.operatorName().strip());
        claims.put("logical_target", approval.logicalTarget());
        claims.put("operation", approval.operation());
        claims.put("payload_hash", payloadHash(mapper, approval.payload()));
        claims.put("draft_version", approval.draftVersion());
        if (approval.targetVersion() != null) {
            if (approval.targetVersion() < 1) {
                throw new IllegalArgumentException("target_version must be positive");
            }
            claims.put("target_version", approval.targetVersion());
        }
        claims.put("request_id", approval.requestId());
        claims.put("idempotency_key", approval.idempotencyKey());
        claims.put("exp", clock.instant().getEpochSecond() + ttlSeconds);
        try {
            String encodedHeader = encode(mapper.writeValueAsBytes(header));
            String encodedClaims = encode(mapper.writeValueAsBytes(claims));
            String unsigned = encodedHeader + "." + encodedClaims;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            return unsigned + "." + encode(mac.doFinal(unsigned.getBytes(StandardCharsets.US_ASCII)));
        } catch (JsonProcessingException | GeneralSecurityException ex) {
            throw new IllegalStateException("Kehuzx Approval grant signing failed", ex);
        }
    }

    public static String payloadHash(ObjectMapper mapper, Map<String, Object> payload) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(canonicalPayload(mapper, payload).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    static String canonicalPayload(ObjectMapper mapper, Map<String, Object> payload) {
        try {
            JsonNode source = mapper.valueToTree(payload == null ? Map.of() : payload);
            StringBuilder canonical = new StringBuilder();
            appendCanonical(mapper, canonical, source);
            return canonical.toString();
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Kehuzx payload is not JSON serializable", ex);
        }
    }

    private static void appendCanonical(ObjectMapper mapper, StringBuilder out, JsonNode value)
            throws JsonProcessingException {
        if (value.isObject()) {
            out.append('{');
            java.util.List<String> names = new java.util.ArrayList<>();
            value.fieldNames().forEachRemaining(names::add);
            names.sort(String::compareTo);
            boolean first = true;
            for (String name : names) {
                JsonNode child = value.get(name);
                if (child.isNull()) {
                    continue;
                }
                if (!first) {
                    out.append(',');
                }
                first = false;
                out.append(mapper.writeValueAsString(name)).append(':');
                appendCanonical(mapper, out, child);
            }
            out.append('}');
            return;
        }
        if (value.isArray()) {
            out.append('[');
            for (int index = 0; index < value.size(); index++) {
                if (index > 0) {
                    out.append(',');
                }
                appendCanonical(mapper, out, value.get(index));
            }
            out.append(']');
            return;
        }
        if (value.isFloatingPointNumber()) {
            out.append(pythonFloat(value.doubleValue()));
            return;
        }
        if (value.isNumber() || value.isBoolean() || value.isNull()) {
            out.append(value.toString());
            return;
        }
        if (value.isTextual()) {
            out.append(mapper.writeValueAsString(value.textValue()));
            return;
        }
        throw new IllegalArgumentException("Kehuzx payload contains a non-JSON value");
    }

    private static String pythonFloat(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("Kehuzx payload contains a non-finite number");
        }
        if (value != 0.0d && Math.abs(value) < Double.MIN_NORMAL) {
            throw new IllegalArgumentException("Kehuzx payload contains an unsupported subnormal number");
        }
        if (Math.rint(value) == value) {
            return new BigDecimal(value).toBigIntegerExact().toString();
        }
        BigDecimal decimal = new BigDecimal(Double.toString(value)).stripTrailingZeros();
        int exponent = decimal.precision() - decimal.scale() - 1;
        if (exponent >= -4 && exponent < 16) {
            return decimal.toPlainString();
        }
        String digits = decimal.unscaledValue().abs().toString();
        StringBuilder out = new StringBuilder();
        if (decimal.signum() < 0) {
            out.append('-');
        }
        out.append(digits.charAt(0));
        if (digits.length() > 1) {
            out.append('.').append(digits.substring(1));
        }
        out.append('e').append(exponent >= 0 ? '+' : '-');
        int magnitude = Math.abs(exponent);
        if (magnitude < 10) {
            out.append('0');
        }
        return out.append(magnitude).toString();
    }

    private static String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    public record Approval(
            String approvalId,
            String operatorId,
            String operatorName,
            String logicalTarget,
            String operation,
            Map<String, Object> payload,
            int draftVersion,
            Integer targetVersion,
            String requestId,
            String idempotencyKey) {}
}
