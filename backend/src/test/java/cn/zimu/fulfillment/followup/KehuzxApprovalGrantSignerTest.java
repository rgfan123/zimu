package cn.zimu.fulfillment.followup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class KehuzxApprovalGrantSignerTest {

    private static final String KEY = "test-signing-key-with-at-least-32-bytes";
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void canonicalPayloadMatchesThePythonContractForNullsEmptyArraysUnicodeAndWholeFloats() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("drop", null);
        nested.put("empty", List.of());
        nested.put("quantity", 2.0d);
        nested.put("ratio", 2.5d);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("z", nested);
        payload.put("customer_name", "海港食品");
        payload.put("omit", null);

        String canonical = KehuzxApprovalGrantSigner.canonicalPayload(mapper, payload);

        assertThat(canonical).isEqualTo(
                "{\"customer_name\":\"海港食品\",\"z\":{\"empty\":[],\"quantity\":2,\"ratio\":2.5}}");
        assertThat(KehuzxApprovalGrantSigner.payloadHash(mapper, payload))
                .isEqualTo("9382b8263fd4e8776813def46b61bd56d756cb3c6fc6f8605b61464538cc48e9");
    }

    @Test
    void signedGrantCarriesOnlyTheShortLivedApprovalClaimsRequiredByKehuzx() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-08-26T02:00:00Z"), ZoneOffset.UTC);
        KehuzxApprovalGrantSigner signer = new KehuzxApprovalGrantSigner(mapper, KEY, clock, 120);
        Map<String, Object> payload = Map.of("customer_name", "海港食品");

        String token = signer.sign(new KehuzxApprovalGrantSigner.Approval(
                "approval-1",
                "operator-7",
                "王审批",
                "customer:assignment-1",
                "create_customer",
                payload,
                3,
                null,
                "request-0001",
                "idempotency-0001"));

        String[] parts = token.split("\\.");
        assertThat(parts).hasSize(3);
        JsonNode header = decode(parts[0]);
        JsonNode claims = decode(parts[1]);
        assertThat(header.path("alg").asText()).isEqualTo("HS256");
        assertThat(claims.path("iss").asText()).isEqualTo("zimu");
        assertThat(claims.path("aud").asText()).isEqualTo("kehuzx-mcp");
        assertThat(claims.path("exp").asLong()).isEqualTo(Instant.parse("2026-08-26T02:02:00Z").getEpochSecond());
        assertThat(claims.path("approval_id").asText()).isEqualTo("approval-1");
        assertThat(claims.path("operator_id").asText()).isEqualTo("operator-7");
        assertThat(claims.path("operator_name").asText()).isEqualTo("王审批");
        assertThat(claims.path("logical_target").asText()).isEqualTo("customer:assignment-1");
        assertThat(claims.path("operation").asText()).isEqualTo("create_customer");
        assertThat(claims.path("payload_hash").asText())
                .isEqualTo(KehuzxApprovalGrantSigner.payloadHash(mapper, payload));
        assertThat(claims.path("draft_version").asInt()).isEqualTo(3);
        assertThat(claims.has("target_version")).isFalse();
        assertThat(claims.path("request_id").asText()).isEqualTo("request-0001");
        assertThat(claims.path("idempotency_key").asText()).isEqualTo("idempotency-0001");

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String expectedSignature = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII)));
        assertThat(parts[2]).isEqualTo(expectedSignature);
    }

    @Test
    void canonicalPayloadMatchesPythonFloatFormattingAtScientificAndIntegralBoundaries() {
        Map<String, Object> payload = Map.of(
                "small", 1e-5d,
                "threshold", 0.0001d,
                "large", 1e23d,
                "nonwhole_large", 1_000_000_000_000_000.5d);

        assertThat(KehuzxApprovalGrantSigner.canonicalPayload(mapper, payload))
                .isEqualTo("{\"large\":99999999999999991611392,"
                        + "\"nonwhole_large\":1000000000000000.5,"
                        + "\"small\":1e-05,\"threshold\":0.0001}");
        assertThat(KehuzxApprovalGrantSigner.payloadHash(mapper, payload))
                .isEqualTo("ceeb5ef49bed0eb7c85b4f3a38702f6948c9721bf9425f48e95ed3df852b885d");
    }

    @Test
    void subnormalFloatsAreRejectedInsteadOfSigningAHashThatCanDivergeFromPython() {
        assertThatThrownBy(() -> KehuzxApprovalGrantSigner.canonicalPayload(
                        mapper, Map.of("quantity", Double.MIN_VALUE)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("subnormal");
    }

    private JsonNode decode(String part) throws Exception {
        return mapper.readTree(Base64.getUrlDecoder().decode(part));
    }
}
