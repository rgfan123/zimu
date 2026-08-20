package cn.zimu.fulfillment.connector.wecom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.audit.AuditLog;
import cn.zimu.fulfillment.common.audit.AuditLogRepository;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WecomOutboundGatewayTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Rfc6455TestServer server;
    private ScheduledExecutorService scheduler;
    private WecomLongConnectionClient client;
    private WecomOutboundGateway gateway;
    private final List<AuditLog> storedAudits = new ArrayList<>();

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
    void textMessageSucceedsOnlyAfterMatchingAck() throws Exception {
        WecomSendResult result = gateway.send(WecomOutboundMessage.text("user-001", "订单已创建"));

        assertThat(result.status()).isEqualTo(WecomSendStatus.SUCCESS);
        assertThat(result.acknowledgedAt()).isNotNull();
        assertThat(result.requestId()).isNotBlank();

        JsonNode frame = MAPPER.readTree(server.awaitFrame("aibot_send_msg", 2_000));
        assertThat(frame.path("headers").path("req_id").asText()).isEqualTo(result.requestId());
        assertThat(frame.path("body").path("chatid").asText()).isEqualTo("user-001");
        assertThat(frame.path("body").path("msgtype").asText()).isEqualTo("text");
        assertThat(frame.path("body").path("text").path("content").asText()).isEqualTo("订单已创建");

        assertThat(storedAudits).hasSize(1);
        AuditLog audit = storedAudits.getFirst();
        assertThat(audit.getRequestId()).isEqualTo(result.requestId());
        assertThat(audit.getActorType()).isEqualTo(AuditActorType.SYSTEM);
        assertThat(audit.getService()).isEqualTo("wecom-outbound");
        assertThat(audit.getOperation()).isEqualTo("wecom.message.send");
        assertThat(audit.getRequestPayload())
                .containsEntry("chat_id", "user-001")
                .containsEntry("message_type", "text")
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
                    sender.submit(() -> gateway.send(WecomOutboundMessage.text("group-001", "请处理复核")));
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

        WecomSendResult result = gateway.send(WecomOutboundMessage.text("user-004", "不会发送"));

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
                    senders.submit(() -> gateway.send(WecomOutboundMessage.text("user-a", "消息 A")));
            Future<WecomSendResult> second =
                    senders.submit(() -> gateway.send(WecomOutboundMessage.text("user-b", "消息 B")));

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
