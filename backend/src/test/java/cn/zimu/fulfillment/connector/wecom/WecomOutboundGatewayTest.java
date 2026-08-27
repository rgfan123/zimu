package cn.zimu.fulfillment.connector.wecom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.audit.AuditLog;
import cn.zimu.fulfillment.common.audit.AuditLogRepository;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.EntityManager;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WecomOutboundGatewayTest {

    @TempDir
    Path tempDir;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Rfc6455TestServer server;
    private ScheduledExecutorService scheduler;
    private WecomLongConnectionClient client;
    private WecomOutboundGateway gateway;
    private final List<AuditLog> storedAudits = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        server = new Rfc6455TestServer();
        scheduler = Executors.newSingleThreadScheduledExecutor();

        WecomProperties properties = new WecomProperties();
        properties.setEnabled(true);
        properties.setBotId("bot-123");
        properties.setSecret("secret-abc");
        properties.setWsUrl(server.wsUrl());
        properties.setHeartbeatIntervalSeconds(1);

        client = new WecomLongConnectionClient(
                properties,
                MAPPER,
                new WecomConnectionStateHolder(),
                HttpClient.newHttpClient(),
                scheduler,
                50,
                200,
                false,
                3_000,
                250);
        AuditLogRepository auditRepository = mock(AuditLogRepository.class);
        when(auditRepository.save(any(AuditLog.class))).thenAnswer(invocation -> {
            AuditLog audit = invocation.getArgument(0);
            storedAudits.add(audit);
            return audit;
        });
        gateway = new WecomOutboundGateway(
                client,
                new AuditLogService(auditRepository, MAPPER, mock(EntityManager.class)));
        client.start();
        org.awaitility.Awaitility.await()
                .atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> assertThat(client.stateHolder().state())
                        .isEqualTo(WecomConnectionState.SUBSCRIBED));
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.shutdown();
        }
        scheduler.shutdownNow();
        if (server != null) {
            server.close();
        }
    }

    @Test
    void markdownMessageSucceedsOnlyAfterMatchingAck() throws Exception {
        WecomSendResult result = gateway.send(WecomOutboundMessage.markdown("user-001", "订单已创建"));

        assertThat(result.status()).isEqualTo(WecomSendStatus.SUCCESS);
        assertThat(result.acknowledgedAt()).isNotNull();
        assertThat(result.requestId()).isNotBlank();

        JsonNode frame = MAPPER.readTree(server.awaitFrame("aibot_send_msg", 2_000));
        assertThat(frame.path("headers").path("req_id").asText()).isEqualTo(result.requestId());
        assertThat(frame.path("body").path("chatid").asText()).isEqualTo("user-001");
        assertThat(frame.path("body").path("msgtype").asText()).isEqualTo("markdown");
        assertThat(frame.path("body").path("markdown").path("content").asText()).isEqualTo("订单已创建");

        assertThat(storedAudits).hasSize(1);
        AuditLog audit = storedAudits.getFirst();
        assertThat(audit.getRequestId()).isEqualTo(result.requestId());
        assertThat(audit.getActorType()).isEqualTo(AuditActorType.SYSTEM);
        assertThat(audit.getService()).isEqualTo("wecom-outbound");
        assertThat(audit.getOperation()).isEqualTo("wecom.message.send");
        assertThat(audit.getRequestPayload())
                .containsEntry("chat_id", "user-001")
                .containsEntry("message_type", "markdown")
                .containsEntry("content_bytes", 15);
        assertThat((String) audit.getRequestPayload().get("content_sha256")).hasSize(64);
        assertThat(MAPPER.writeValueAsString(audit.getRequestPayload())).doesNotContain("订单已创建");
        assertThat(audit.getResponsePayload()).containsEntry("status", "SUCCESS");
        assertThat(audit.getBusinessCode()).isEqualTo("WECOM_SEND_SUCCESS");
    }

    @Test
    void connectionLossAfterSubmissionIsDeliveryUnknownAndNotBlindlyRetryable() throws Exception {
        server.autoSendMessageAck(false);

        try (var sender = Executors.newSingleThreadExecutor()) {
            Future<WecomSendResult> pending =
                    sender.submit(() -> gateway.send(WecomOutboundMessage.markdown("group-001", "请处理复核")));
            assertThat(server.awaitFrame("aibot_send_msg", 2_000)).isNotNull();

            server.abortConnection();

            WecomSendResult result = pending.get(2, TimeUnit.SECONDS);
            assertThat(result.status()).isEqualTo(WecomSendStatus.FAILED);
            assertThat(result.retryable()).isFalse();
            assertThat(result.errorMessage()).isEqualTo("CONNECTION_LOST_AFTER_SUBMIT");
        }
    }

    @Test
    void missingAckReturnsTimeoutInsteadOfSuccess() {
        server.autoSendMessageAck(false);

        WecomSendResult result = gateway.send(WecomOutboundMessage.markdown("group-002", "**待处理**"));

        assertThat(result.status()).isEqualTo(WecomSendStatus.TIMEOUT);
        assertThat(result.acknowledgedAt()).isNull();
        assertThat(result.retryable()).isFalse();
        assertThat(result.errorMessage()).isEqualTo("ACK_TIMEOUT");
        assertThat(storedAudits).singleElement().satisfies(audit -> {
            assertThat(audit.getResponsePayload()).containsEntry("status", "TIMEOUT");
            assertThat(audit.getBusinessCode()).isEqualTo("WECOM_SEND_TIMEOUT");
        });
    }

    @Test
    void rejectedAckReturnsFailedWithServerCode() {
        server.sendMessageErrcode(45009);

        WecomSendResult result = gateway.send(WecomOutboundMessage.markdown("group-003", "**发送过于频繁**"));

        assertThat(result.status()).isEqualTo(WecomSendStatus.FAILED);
        assertThat(result.errorCode()).isEqualTo(45009);
        assertThat(result.errorMessage()).isEqualTo("rejected");
        assertThat(result.acknowledgedAt()).isNull();
        assertThat(storedAudits).singleElement().satisfies(audit -> {
            assertThat(audit.getResponsePayload())
                    .containsEntry("status", "FAILED")
                    .containsEntry("error_code", 45009);
            assertThat(audit.getBusinessCode()).isEqualTo("WECOM_SEND_FAILED");
        });
    }

    @Test
    void disconnectedGatewayFailsFastWithoutSubmittingMessage() {
        client.shutdown();

        WecomSendResult result = gateway.send(WecomOutboundMessage.markdown("user-004", "不会发送"));

        assertThat(result.status()).isEqualTo(WecomSendStatus.FAILED);
        assertThat(result.errorMessage()).isEqualTo("CONNECTION_NOT_READY");
        assertThat(result.retryable()).isTrue();
        assertThat(server.textFrames().stream().filter(frame -> frame.contains("aibot_send_msg"))).isEmpty();
        assertThat(storedAudits).hasSize(1);
    }

    @Test
    void concurrentMessagesAreCorrelatedByTheirOwnRequestIds() throws Exception {
        server.autoSendMessageAck(false);

        try (var senders = Executors.newFixedThreadPool(2)) {
            Future<WecomSendResult> first =
                    senders.submit(() -> gateway.send(WecomOutboundMessage.markdown("user-a", "消息 A")));
            Future<WecomSendResult> second =
                    senders.submit(() -> gateway.send(WecomOutboundMessage.markdown("user-b", "消息 B")));

            org.awaitility.Awaitility.await()
                    .atMost(Duration.ofSeconds(2))
                    .untilAsserted(() -> assertThat(sendMessageFrames()).hasSize(2));
            List<JsonNode> frames = sendMessageFrames();
            String firstRequestId = frames.get(0).path("headers").path("req_id").asText();
            String secondRequestId = frames.get(1).path("headers").path("req_id").asText();

            server.sendAck(secondRequestId, 0);
            server.sendAck(firstRequestId, 0);

            assertThat(first.get(2, TimeUnit.SECONDS).status()).isEqualTo(WecomSendStatus.SUCCESS);
            assertThat(second.get(2, TimeUnit.SECONDS).status()).isEqualTo(WecomSendStatus.SUCCESS);
            assertThat(storedAudits).hasSize(2);
        }
    }


    // ---- 文件消息（Issue #84）----

    @Test
    void fileMessageUsesExactProtocolShapeAndServerAckReceiveTime() throws Exception {
        server.autoSendMessageAck(false);

        try (var sender = Executors.newSingleThreadExecutor()) {
            Future<WecomSendResult> pending = sender.submit(
                    () -> gateway.send(WecomOutboundMessage.file("group-100", "MEDIA-ABC123")));
            JsonNode frame = MAPPER.readTree(server.awaitFrame("aibot_send_msg", 2_000));

            // 官方帧：cmd + headers.req_id + body{chatid, msgtype:"file", file:{media_id}}
            assertThat(frame.path("cmd").asText()).isEqualTo("aibot_send_msg");
            assertThat(frame.path("headers").path("req_id").asText()).isNotBlank();
            assertThat(frame.path("body").path("chatid").asText()).isEqualTo("group-100");
            assertThat(frame.path("body").path("msgtype").asText()).isEqualTo("file");
            assertThat(frame.path("body").path("file").path("media_id").asText()).isEqualTo("MEDIA-ABC123");
            // 文件消息不带 content 文本字段
            assertThat(frame.path("body").path("file").path("content").isMissingNode()).isTrue();
            assertThat(frame.path("body").path("text").isMissingNode()).isTrue();

            // sent_at 必须是服务端 ack 接收时刻（ack 晚于帧提交，而不是发送/提交时刻）
            Instant afterFrameSubmitted = Instant.now();
            String requestId = frame.path("headers").path("req_id").asText();
            server.sendAck(requestId, 0);

            WecomSendResult result = pending.get(2, TimeUnit.SECONDS);
            assertThat(result.status()).isEqualTo(WecomSendStatus.SUCCESS);
            assertThat(result.requestId()).isEqualTo(requestId);
            assertThat(result.acknowledgedAt()).isNotNull().isAfterOrEqualTo(afterFrameSubmitted);
            assertThat(result.errorCode()).isNull();
            assertThat(result.retryable()).isFalse();
        }
    }

    @Test
    void fileMessageAuditStoresOnlyMediaIdDigestAndNeverPlaintext() throws Exception {
        WecomSendResult result = gateway.send(WecomOutboundMessage.file("group-101", "MEDIA-SECRET-9"));

        assertThat(result.status()).isEqualTo(WecomSendStatus.SUCCESS);
        AuditLog audit = storedAudits.getLast();
        assertThat(audit.getOperation()).isEqualTo("wecom.message.send");
        assertThat(audit.getRequestPayload())
                .containsEntry("chat_id", "group-101")
                .containsEntry("message_type", "file");
        // 审计只存 media_id 摘要（SHA-256），绝不落明文
        String digest = (String) audit.getRequestPayload().get("media_id_sha256");
        assertThat(digest).hasSize(64);
        assertThat(MAPPER.writeValueAsString(audit.getRequestPayload()))
                .doesNotContain("MEDIA-SECRET-9");
    }

    @Test
    void activeTextIsRejectedAndMarkdownKeepsItsProtocolShape() throws Exception {
        assertThatThrownBy(() -> WecomOutboundMessage.text("user-200", "普通文本"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not supported");

        WecomSendResult markdownResult = gateway.send(WecomOutboundMessage.markdown("user-201", "**加粗**"));
        assertThat(markdownResult.status()).isEqualTo(WecomSendStatus.SUCCESS);
        org.awaitility.Awaitility.await()
                .atMost(Duration.ofSeconds(2))
                .untilAsserted(() -> assertThat(sendMessageFrames()).hasSize(1));
        JsonNode markdownFrame = sendMessageFrames().getFirst();
        assertThat(markdownFrame.path("body").path("msgtype").asText()).isEqualTo("markdown");
        assertThat(markdownFrame.path("body").path("markdown").path("content").asText()).isEqualTo("**加粗**");
        assertThat(markdownFrame.path("body").path("file").isMissingNode()).isTrue();
    }

    @Test
    void templateCardMessageUsesOfficialButtonInteractionShapeAndAuditsOnlyDigest() throws Exception {
        ObjectNode card = MAPPER.createObjectNode();
        card.put("card_type", "button_interaction");
        card.putObject("main_title").put("title", "订单草稿待确认").put("desc", "草稿 OD-41");
        card.putArray("button_list")
                .addObject().put("text", "确认订单").put("key", "confirm_order").put("style", 1);
        card.put("task_id", "order-draft_41_v0");

        WecomSendResult result = gateway.send(WecomOutboundMessage.templateCard("group-card", card));

        assertThat(result.status()).isEqualTo(WecomSendStatus.SUCCESS);
        JsonNode frame = MAPPER.readTree(server.awaitFrame("aibot_send_msg", 2_000));
        assertThat(frame.path("body").path("chatid").asText()).isEqualTo("group-card");
        assertThat(frame.path("body").path("msgtype").asText()).isEqualTo("template_card");
        assertThat(frame.path("body").path("template_card").path("card_type").asText())
                .isEqualTo("button_interaction");
        assertThat(frame.path("body").path("template_card").path("task_id").asText())
                .isEqualTo("order-draft_41_v0");
        assertThat(frame.path("body").path("template_card").path("button_list").get(0).path("key").asText())
                .isEqualTo("confirm_order");
        AuditLog audit = storedAudits.getLast();
        assertThat(audit.getRequestPayload())
                .containsEntry("message_type", "template_card")
                .containsKey("template_card_sha256");
        assertThat(MAPPER.writeValueAsString(audit.getRequestPayload()))
                .doesNotContain("OD-41", "order-draft_41_v0", "确认订单");
    }

    // ---- 素材上传（Issue #82）----

    @Test
    void uploadReturnsDeepResultAndAuditsWithoutContentOrMediaIdLeak() throws Exception {
        byte[] content = new byte[700_000];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) (i * 7);
        }
        Path file = Files.write(tempDir.resolve("export.xlsx"), content);

        WecomUploadResult result = gateway.upload(file, "export.xlsx", WecomMediaType.FILE);

        assertThat(result.status()).isEqualTo(WecomUploadStatus.SUCCESS);
        assertThat(result.mediaId()).isNotBlank();
        assertThat(result.mediaType()).isEqualTo("file");
        assertThat(result.createdAt()).isNotNull();
        assertThat(result.acknowledgedAt()).isNotNull();
        assertThat(result.uploadId()).isNotBlank();
        assertThat(result.requestId()).isNotBlank();

        AuditLog audit = storedAudits.getLast();
        assertThat(audit.getActorType()).isEqualTo(AuditActorType.SYSTEM);
        assertThat(audit.getService()).isEqualTo("wecom-outbound");
        assertThat(audit.getOperation()).isEqualTo("wecom.media.upload");
        assertThat(audit.getBusinessCode()).isEqualTo("WECOM_UPLOAD_SUCCESS");
        assertThat(audit.getRequestPayload())
                .containsEntry("media_type", "file")
                .containsEntry("file_bytes", (long) content.length);
        assertThat(audit.getResponsePayload())
                .containsEntry("status", "SUCCESS")
                .containsEntry("step", "FINISH")
                .containsEntry("retryable", false)
                .containsEntry("upload_id", result.uploadId());

        // 审计不落文件内容与 media_id（结果携带 media_id，审计投影不携带）
        String requestJson = MAPPER.writeValueAsString(audit.getRequestPayload());
        String responseJson = MAPPER.writeValueAsString(audit.getResponsePayload());
        assertThat(requestJson).doesNotContain("export.xlsx").doesNotContain("base64");
        assertThat(responseJson).doesNotContain(result.mediaId()).doesNotContain("base64");
    }

    @Test
    void uploadValidationRejectsBeforeAnyFrameAndWritesNoAudit() throws Exception {
        Path file = Files.write(tempDir.resolve("tiny.xlsx"), new byte[] {1, 2, 3, 4});

        assertThatThrownBy(() -> gateway.upload(file, "tiny.xlsx", WecomMediaType.FILE))
                .isInstanceOf(WecomUploadValidationException.class)
                .hasMessageContaining("5")
                .satisfies(ex -> assertThat(((WecomUploadValidationException) ex).code())
                        .isEqualTo("UPLOAD_FILE_TOO_SMALL"));
        assertThat(server.textFrames().stream().filter(frame -> frame.contains("aibot_upload_media_init")))
                .isEmpty();
        assertThat(storedAudits).isEmpty();
    }

    @Test
    void uploadOnDisconnectedGatewayFailsFastWithoutFrames() throws Exception {
        client.shutdown();
        Path file = Files.write(tempDir.resolve("export.xlsx"), new byte[] {1, 2, 3, 4, 5, 6, 7, 8});

        WecomUploadResult result = gateway.upload(file, "export.xlsx", WecomMediaType.FILE);

        assertThat(result.status()).isEqualTo(WecomUploadStatus.FAILED);
        assertThat(result.step()).isEqualTo("INIT");
        assertThat(result.errorMessage()).isEqualTo("CONNECTION_NOT_READY");
        assertThat(result.retryable()).isTrue();
        assertThat(result.mediaId()).isNull();
        assertThat(server.textFrames().stream().filter(frame -> frame.contains("aibot_upload_media_init")))
                .isEmpty();
        assertThat(storedAudits).hasSize(1);
        assertThat(storedAudits.getLast().getBusinessCode()).isEqualTo("WECOM_UPLOAD_FAILED");
    }

    private List<JsonNode> sendMessageFrames() {
        return server.textFrames().stream()
                .map(frame -> {
                    try {
                        return MAPPER.readTree(frame);
                    } catch (Exception ex) {
                        throw new AssertionError(ex);
                    }
                })
                .filter(frame -> "aibot_send_msg".equals(frame.path("cmd").asText()))
                .toList();
    }
}
