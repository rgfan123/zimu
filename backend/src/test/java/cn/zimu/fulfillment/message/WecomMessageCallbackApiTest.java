package cn.zimu.fulfillment.message;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WecomMessageCallbackApiTest {

    private static final String CONNECTION = "business-relay";
    private static final String CORP_ID = "ww-test-corp";
    private static final String BOT_ID = "AIBOT-ORDER-OPS";
    private static final String ALLOWED_GROUP = "CHAT-ORDER-OPS";
    private static final String TOKEN = "fixed-callback-token-for-tests";
    private static final String ENCODING_AES_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void wecomConfiguration(DynamicPropertyRegistry registry) {
        registry.add("app.wecom.connections." + CONNECTION + ".enabled", () -> "true");
        registry.add("app.wecom.connections." + CONNECTION + ".corp-id", () -> CORP_ID);
        registry.add("app.wecom.connections." + CONNECTION + ".bot-id", () -> BOT_ID);
        registry.add("app.wecom.connections." + CONNECTION + ".token", () -> TOKEN);
        registry.add(
                "app.wecom.connections." + CONNECTION + ".encoding-aes-key", () -> ENCODING_AES_KEY);
        registry.add(
                "app.wecom.connections." + CONNECTION + ".allowed-group-ids[0]", () -> ALLOWED_GROUP);
    }

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void urlVerificationReturnsTheDecryptedChallengeAndRejectsAnInvalidSignature() throws Exception {
        String timestamp = "1786500000";
        String nonce = "nonce-url-01";
        String challenge = "verify_business_relay";
        String encryptedChallenge = encrypt(challenge, HexFormat.of().parseHex("000102030405060708090a0b0c0d0e0f"));
        String signature = signature(timestamp, nonce, encryptedChallenge);

        ResponseEntity<String> verified = http.getForEntity(callbackUrl(
                signature, timestamp, nonce, encryptedChallenge), String.class);
        ResponseEntity<String> rejected = http.getForEntity(callbackUrl(
                "invalid-signature", timestamp, nonce, encryptedChallenge), String.class);

        assertThat(verified.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(verified.getBody()).isEqualTo(challenge);
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void encryptedGroupCallbacksAreFilteredAndIdempotentlyVisibleThroughTheAuthorizedAdminApi()
            throws Exception {
        ResponseEntity<Map> unauthenticated = http.getForEntity("/api/v1/channel-messages", Map.class);
        assertThat(unauthenticated.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        Map<String, Object> before = listMessages();
        long beforeCount = ((Number) before.get("total_elements")).longValue();

        String content = "@OrderBot 张三 13800000000 浦东新区 猪肉礼盒 2份";
        String firstPlaintext = textMessage(
                "MSG-TEXT-001", BOT_ID, ALLOWED_GROUP, "USER-FORWARDER-01", content, true);
        ResponseEntity<String> first = postEncrypted(firstPlaintext, "nonce-post-01", "1786500100", 1);
        ResponseEntity<String> duplicate = postEncrypted(firstPlaintext, "nonce-post-02", "1786500101", 2);

        String secondPlaintext = textMessage(
                "MSG-TEXT-002", BOT_ID, ALLOWED_GROUP, "USER-FORWARDER-01", content, false);
        ResponseEntity<String> sameContentNewId = postEncrypted(
                secondPlaintext, "nonce-post-03", "1786500102", 3);

        ResponseEntity<String> otherGroup = postEncrypted(
                textMessage("MSG-OTHER-GROUP", BOT_ID, "CHAT-NOT-ALLOWED", "USER-02", "不应入库", false),
                "nonce-post-04", "1786500103", 4);
        ResponseEntity<String> otherBot = postEncrypted(
                textMessage("MSG-OTHER-BOT", "AIBOT-OTHER", ALLOWED_GROUP, "USER-03", "不应入库", false),
                "nonce-post-05", "1786500104", 5);
        ResponseEntity<String> badSignature = postEncryptedWithSignature(
                firstPlaintext, "nonce-post-06", "1786500105", 6, "invalid-signature");

        assertReceipt(first, "nonce-post-01");
        assertReceipt(duplicate, "nonce-post-02");
        assertReceipt(sameContentNewId, "nonce-post-03");
        assertThat(otherGroup.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(otherGroup.getBody()).isNullOrEmpty();
        assertThat(otherBot.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(otherBot.getBody()).isNullOrEmpty();
        assertThat(badSignature.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        Map<String, Object> page = listMessages();
        assertThat(((Number) page.get("total_elements")).longValue()).isEqualTo(beforeCount + 2);
        List<Map<String, Object>> items = castList(page.get("items"));
        List<Map<String, Object>> created = items.stream()
                .filter(item -> List.of("MSG-TEXT-001", "MSG-TEXT-002").contains(item.get("message_id")))
                .toList();
        assertThat(created).hasSize(2);
        assertThat(created).allSatisfy(item -> {
            assertThat(item)
                    .containsEntry("corp_id", CORP_ID)
                    .containsEntry("connection_id", CONNECTION)
                    .containsEntry("bot_id", BOT_ID)
                    .containsEntry("chat_id", ALLOWED_GROUP)
                    .containsEntry("chat_type", "group")
                    .containsEntry("sender_user_id", "USER-FORWARDER-01")
                    .containsEntry("message_type", "text")
                    .containsEntry("content_preview", content);
            assertThat(item.get("received_at")).isNotNull();
            assertThat(item).doesNotContainKeys("raw_payload", "response_url", "token", "encoding_aes_key");
        });

        Map<String, Object> firstSummary = created.stream()
                .filter(item -> "MSG-TEXT-001".equals(item.get("message_id")))
                .findFirst()
                .orElseThrow();
        assertThat(firstSummary.get("id")).isInstanceOf(String.class);
        Map<String, Object> detail = getMessage(firstSummary.get("id").toString());
        assertThat(detail)
                .containsEntry("content", content)
                .containsEntry("quote_type", "text")
                .containsEntry("quote_content", "这是被转发的原始客户需求");
        assertThat(detail.get("raw_payload_ref").toString()).startsWith("channel-message-payload:");
        assertThat(objectMapper.writeValueAsString(detail))
                .doesNotContain("temporary-response.example", TOKEN, ENCODING_AES_KEY, "unknown_secret");
    }

    private Map<String, Object> listMessages() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Operator", "wecom-reviewer");
        ResponseEntity<Map> response = http.exchange(
                "/api/v1/channel-messages?page=0&size=50",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private Map<String, Object> getMessage(String id) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Operator", "wecom-reviewer");
        ResponseEntity<Map> response = http.exchange(
                "/api/v1/channel-messages/" + id,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private void assertReceipt(ResponseEntity<String> response, String callbackNonce) throws Exception {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        Map<String, Object> wrapper = objectMapper.readValue(response.getBody(), new TypeReference<>() {});
        assertThat(wrapper.keySet()).containsExactlyInAnyOrder("encrypt", "msgsignature", "timestamp", "nonce");
        assertThat(wrapper.get("nonce")).isEqualTo(callbackNonce);
        String encrypted = wrapper.get("encrypt").toString();
        String timestamp = wrapper.get("timestamp").toString();
        assertThat(wrapper.get("msgsignature"))
                .isEqualTo(signature(timestamp, callbackNonce, encrypted));

        Map<String, Object> plaintext = objectMapper.readValue(decrypt(encrypted), new TypeReference<>() {});
        assertThat(plaintext.get("msgtype")).isEqualTo("stream");
        Map<String, Object> stream = castMap(plaintext.get("stream"));
        assertThat(stream)
                .containsEntry("finish", true)
                .containsEntry("content", "已接收");
        assertThat(stream.get("id")).isNotNull();
        assertThat(objectMapper.writeValueAsString(plaintext))
                .doesNotContain("客户", "商品", "地址", "草稿", "订单");
    }

    private ResponseEntity<String> postEncrypted(
            String plaintext, String nonce, String timestamp, int randomSuffix) throws Exception {
        String encrypted = encrypt(plaintext, randomBytes(randomSuffix));
        return postWrapper(encrypted, nonce, timestamp, signature(timestamp, nonce, encrypted));
    }

    private ResponseEntity<String> postEncryptedWithSignature(
            String plaintext, String nonce, String timestamp, int randomSuffix, String requestSignature)
            throws Exception {
        String encrypted = encrypt(plaintext, randomBytes(randomSuffix));
        return postWrapper(encrypted, nonce, timestamp, requestSignature);
    }

    private ResponseEntity<String> postWrapper(
            String encrypted, String nonce, String timestamp, String requestSignature) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = objectMapper.writeValueAsString(Map.of("encrypt", encrypted));
        URI uri = URI.create(http.getRootUri() + "/wecom/callbacks/" + CONNECTION
                + "?msg_signature=" + encode(requestSignature)
                + "&timestamp=" + encode(timestamp)
                + "&nonce=" + encode(nonce));
        return http.exchange(uri, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private URI callbackUrl(String requestSignature, String timestamp, String nonce, String echo) {
        return URI.create(http.getRootUri() + "/wecom/callbacks/" + CONNECTION
                + "?msg_signature=" + encode(requestSignature)
                + "&timestamp=" + encode(timestamp)
                + "&nonce=" + encode(nonce)
                + "&echostr=" + encode(echo));
    }

    private static String textMessage(
            String messageId,
            String botId,
            String chatId,
            String sender,
            String content,
            boolean withQuote)
            throws Exception {
        String quote = withQuote
                ? ",\"quote\":{\"msgtype\":\"text\",\"text\":{\"content\":\"这是被转发的原始客户需求\"}}"
                : "";
        return "{\"msgid\":\"" + messageId + "\","
                + "\"aibotid\":\"" + botId + "\","
                + "\"chatid\":\"" + chatId + "\","
                + "\"chattype\":\"group\","
                + "\"from\":{\"userid\":\"" + sender + "\"},"
                + "\"response_url\":\"https://temporary-response.example/secret\","
                + "\"unknown_secret\":\"must-not-be-rendered\","
                + "\"msgtype\":\"text\","
                + "\"text\":{\"content\":\"" + content + "\"}"
                + quote + "}";
    }

    private static byte[] randomBytes(int suffix) {
        byte[] value = HexFormat.of().parseHex("101112131415161718191a1b1c1d1e1f");
        value[value.length - 1] = (byte) suffix;
        return value;
    }

    private static String signature(String timestamp, String nonce, String encrypted) throws Exception {
        List<String> values = new ArrayList<>(List.of(TOKEN, timestamp, nonce, encrypted));
        values.sort(String::compareTo);
        byte[] digest = MessageDigest.getInstance("SHA-1")
                .digest(String.join("", values).getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }

    private static String encrypt(String plaintext, byte[] randomPrefix) throws Exception {
        byte[] key = aesKey();
        byte[] message = plaintext.getBytes(StandardCharsets.UTF_8);
        ByteBuffer unpadded = ByteBuffer.allocate(16 + 4 + message.length);
        unpadded.put(randomPrefix);
        unpadded.putInt(message.length);
        unpadded.put(message);
        byte[] padded = pkcs7Pad(unpadded.array());

        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(key, 0, 16));
        return Base64.getEncoder().encodeToString(cipher.doFinal(padded));
    }

    private static String decrypt(String encrypted) throws Exception {
        byte[] key = aesKey();
        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(key, 0, 16));
        byte[] padded = cipher.doFinal(Base64.getDecoder().decode(encrypted));
        byte[] plain = pkcs7Unpad(padded);
        ByteBuffer buffer = ByteBuffer.wrap(plain);
        buffer.position(16);
        int length = buffer.getInt();
        byte[] message = new byte[length];
        buffer.get(message);
        return new String(message, StandardCharsets.UTF_8);
    }

    private static byte[] aesKey() {
        return Base64.getDecoder().decode(ENCODING_AES_KEY + "=");
    }

    private static byte[] pkcs7Pad(byte[] value) {
        int amount = 32 - value.length % 32;
        byte[] padded = Arrays.copyOf(value, value.length + amount);
        Arrays.fill(padded, value.length, padded.length, (byte) amount);
        return padded;
    }

    private static byte[] pkcs7Unpad(byte[] value) {
        int amount = Byte.toUnsignedInt(value[value.length - 1]);
        return Arrays.copyOf(value, value.length - amount);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castList(Object value) {
        return (List<Map<String, Object>>) value;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
