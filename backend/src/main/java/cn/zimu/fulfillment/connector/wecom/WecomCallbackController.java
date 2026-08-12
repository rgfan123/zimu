package cn.zimu.fulfillment.connector.wecom;

import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.message.ChannelMessageCommand;
import cn.zimu.fulfillment.message.ChannelMessageIntakeService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Public, signature-authenticated enterprise WeChat intelligent-bot callback seam. */
@RestController
@RequestMapping("/wecom/callbacks/{connection_id}")
public class WecomCallbackController {

    private final WecomProperties properties;
    private final ChannelMessageIntakeService intakeService;
    private final ObjectMapper objectMapper;

    public WecomCallbackController(
            WecomProperties properties, ChannelMessageIntakeService intakeService, ObjectMapper objectMapper) {
        this.properties = properties;
        this.intakeService = intakeService;
        this.objectMapper = objectMapper;
    }

    @GetMapping(produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> verifyUrl(
            @PathVariable("connection_id") String connectionId,
            @RequestParam("msg_signature") String signature,
            @RequestParam String timestamp,
            @RequestParam String nonce,
            @RequestParam String echostr) {
        WecomCallbackCrypto crypto = crypto(connectionId);
        requireSignature(crypto, signature, timestamp, nonce, echostr);
        try {
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(crypto.decrypt(echostr));
        } catch (WecomCallbackCrypto.CryptoException ex) {
            throw BusinessException.badRequest("WECOM_CALLBACK_INVALID", "企业微信验证串无法解密");
        }
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> receive(
            @PathVariable("connection_id") String connectionId,
            @RequestParam("msg_signature") String signature,
            @RequestParam String timestamp,
            @RequestParam String nonce,
            @RequestBody EncryptedCallback callback) {
        if (callback.encrypt() == null || callback.encrypt().isBlank()) {
            throw BusinessException.badRequest("WECOM_CALLBACK_INVALID", "企业微信回调缺少 encrypt");
        }
        WecomProperties.Connection connection = properties.requireEnabled(connectionId);
        WecomCallbackCrypto crypto = configuredCrypto(connection);
        requireSignature(crypto, signature, timestamp, nonce, callback.encrypt());

        JsonNode payload;
        try {
            payload = objectMapper.readTree(crypto.decrypt(callback.encrypt()));
        } catch (JsonProcessingException | WecomCallbackCrypto.CryptoException ex) {
            throw BusinessException.badRequest("WECOM_CALLBACK_INVALID", "企业微信回调内容无法解密或不是合法 JSON");
        }

        String botId = text(payload, "aibotid");
        String chatId = text(payload, "chatid");
        String chatType = text(payload, "chattype");
        String messageType = text(payload, "msgtype");
        if (!connection.getBotId().equals(botId)
                || !"group".equals(chatType)
                || !connection.acceptsGroup(chatId)
                || !"text".equals(messageType)) {
            return ResponseEntity.ok().build();
        }

        String messageId = required(payload, "msgid");
        String senderUserId = required(payload.path("from"), "userid");
        String content = required(payload.path("text"), "content");
        JsonNode quote = payload.path("quote");
        String quoteType = text(quote, "msgtype");
        String quoteContent = quoteType == null ? null : text(quote.path(quoteType), "content");

        intakeService.store(new ChannelMessageCommand(
                connection.getCorpId(),
                connectionId,
                botId,
                messageId,
                chatId,
                chatType,
                senderUserId,
                messageType,
                content,
                quoteType,
                quoteContent,
                payload));

        String plaintext = receiptPlaintext(connectionId, messageId);
        String encrypted = crypto.encrypt(plaintext);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("encrypt", encrypted);
        response.put("msgsignature", crypto.signature(timestamp, nonce, encrypted));
        response.put("timestamp", timestamp);
        response.put("nonce", nonce);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(response);
    }

    private WecomCallbackCrypto crypto(String connectionId) {
        return configuredCrypto(properties.requireEnabled(connectionId));
    }

    private static WecomCallbackCrypto configuredCrypto(WecomProperties.Connection connection) {
        try {
            return new WecomCallbackCrypto(connection.getToken(), connection.getEncodingAesKey());
        } catch (WecomCallbackCrypto.CryptoException ex) {
            throw new BusinessException(503, "WECOM_CONNECTION_NOT_READY", "企业微信回调密钥配置无效");
        }
    }

    private static void requireSignature(
            WecomCallbackCrypto crypto, String signature, String timestamp, String nonce, String encrypted) {
        if (!crypto.signatureMatches(signature, timestamp, nonce, encrypted)) {
            throw new BusinessException(401, "WECOM_SIGNATURE_INVALID", "企业微信回调签名无效");
        }
    }

    private String receiptPlaintext(String connectionId, String messageId) {
        try {
            String source = connectionId + "\u0000" + messageId;
            String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8)));
            return objectMapper.writeValueAsString(Map.of(
                    "msgtype", "stream",
                    "stream", Map.of("id", "receipt-" + digest.substring(0, 24), "finish", true, "content", "已接收")));
        } catch (GeneralSecurityException | JsonProcessingException ex) {
            throw new IllegalStateException("could not create enterprise WeChat receipt", ex);
        }
    }

    private static String required(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null || value.isBlank()) {
            throw BusinessException.badRequest("WECOM_CALLBACK_INVALID", "企业微信回调缺少必要字段: " + field);
        }
        return value;
    }

    private static String text(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() || !value.isValueNode() ? null : value.asText();
    }

    public record EncryptedCallback(String encrypt) {}
}
