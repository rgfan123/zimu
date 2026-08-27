package cn.zimu.fulfillment.file;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zimu.fulfillment.connector.wecom.WecomMediaType;
import cn.zimu.fulfillment.connector.wecom.WecomOutboundMessage;
import cn.zimu.fulfillment.connector.wecom.WecomOutboundTransport;
import cn.zimu.fulfillment.connector.wecom.WecomSendResult;
import cn.zimu.fulfillment.connector.wecom.WecomSendStatus;
import cn.zimu.fulfillment.connector.wecom.WecomUploadResult;
import cn.zimu.fulfillment.connector.wecom.WecomUploadStatus;
import cn.zimu.fulfillment.message.AsyncTaskStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Issue #84 人工停止/重发 REST 写端点：认证、幂等、版本、审计；COMPLETED 拒重发；
 * LEGACY 拒绝；停止幂等；重发重置时间线并自动关闭告警。
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "app.message-worker.enabled=false",
            "app.wecom-export-worker.enabled=false",
            "app.wecom-reminder.enabled=false",
            "app.wecom-export-worker.backoff-seconds=1",
            "app.file-store.root=${java.io.tmpdir}/zimu-wecom-resend-test"
        })
@Import(FulfillmentExportWecomResendStopApiTest.ControlledWecomTransportConfig.class)
class FulfillmentExportWecomResendStopApiTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired AsyncTaskStore taskStore;
    @Autowired FulfillmentExportWecomDeliveryRunner runner;
    @Autowired ControlledWecomTransport wecom;

    @TestConfiguration
    static class ControlledWecomTransportConfig {
        @Bean
        @Primary
        ControlledWecomTransport controlledWecomTransport() {
            return new ControlledWecomTransport();
        }
    }

    static class ControlledWecomTransport implements WecomOutboundTransport {
        final List<WecomOutboundMessage> sentMessages = new CopyOnWriteArrayList<>();
        volatile WecomSendResult sendResult =
                new WecomSendResult(WecomSendStatus.SUCCESS, "resend-req-1", Instant.now(), null, null, false);

        @Override
        public WecomSendResult send(WecomOutboundMessage message) {
            sentMessages.add(message);
            return sendResult;
        }

        @Override
        public WecomUploadResult upload(Path file, String filename, WecomMediaType type) {
            return new WecomUploadResult(
                    WecomUploadStatus.SUCCESS, "MEDIA-RESEND-1", "file",
                    Instant.now().minusSeconds(10), Instant.now().minusSeconds(5),
                    "upload-session-ok", "req-upload-ok", "FINISH", null, null, false);
        }
    }

    @BeforeEach
    void reset() {
        wecom.sentMessages.clear();
        wecom.sendResult = new WecomSendResult(
                WecomSendStatus.SUCCESS, "resend-req-1", Instant.now(), null, null, false);
        jdbc.update(
                """
                UPDATE app.fulfillment_providers
                SET config = config || '{"wecomGroupChatId":"wrJgVnTQAAD-RS-001"}'::jsonb
                WHERE provider_code='TP'
                """);
        jdbc.update(
                """
                INSERT INTO app.customer_source_refs(customer_id, source_channel, source_customer_ref)
                SELECT customer_id, 'FEIXIANG', 'FX-MEMBER-001'
                FROM app.customer_source_refs WHERE source_channel='WECOM'
                ON CONFLICT (source_channel, source_customer_ref) DO NOTHING
                """);
        jdbc.update(
                """
                INSERT INTO app.source_channel_skus
                    (source_channel, source_sku_ref, source_product_name, source_specification,
                     quantity_multiplier, sku_id, active)
                SELECT 'FEIXIANG', 'FX-PRODUCT-001', '子牧羊小腿', '标准箱', 2.000, sku_id, true
                FROM app.source_channel_skus WHERE source_channel='WECOM' AND source_sku_ref='WECOM-SKU-TP-001'
                ON CONFLICT (source_channel, source_sku_ref) DO NOTHING
                """);
    }

    // ------------------------------------------------------------------
    // 停止
    // ------------------------------------------------------------------

    @Test
    void stopPersistsOperatorReasonAndTimeWithVersionAndIdempotency() throws Exception {
        String exportId = activeExport("FX-RS-STOP-001");
        long version = versionOf(exportId);

        ResponseEntity<Map> stopped = stop(exportId, version, "该批次已线下结清", "rs-stop-001");
        assertThat(stopped.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> state = stateRow(exportId);
        assertThat(state.get("status")).isEqualTo("MANUALLY_STOPPED");
        assertThat(state.get("stopped_by")).isEqualTo("resend-test");
        assertThat(state.get("stopped_reason")).isEqualTo("该批次已线下结清");
        assertThat(state.get("stopped_at")).isNotNull();
        assertThat(state.get("next_reminder_at")).isNull();
        assertThat(state.get("lock_version")).isEqualTo(version + 1);

        // 同 Idempotency-Key 重放返回首次结果
        ResponseEntity<Map> replayed = stop(exportId, version, "该批次已线下结清", "rs-stop-001");
        assertThat(replayed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replayed.getBody()).isEqualTo(stopped.getBody());
        // 审计可追溯
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.audit_logs WHERE operation='wecom_export.stop' "
                        + "AND operator='resend-test' AND request_id='req-rs-stop-001'",
                Integer.class)).isEqualTo(1);

        // 已停止再停（新 key）幂等 no-op 返回现状，不报错
        ResponseEntity<Map> again = stop(exportId, version + 1, "再次停止", "rs-stop-002");
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(stateRow(exportId).get("status")).isEqualTo("MANUALLY_STOPPED");
        assertThat(stateRow(exportId).get("stopped_reason")).isEqualTo("该批次已线下结清"); // 不覆盖

        // 已停止后用旧版本再停：幂等 no-op（已停止语义优先于版本检查）
        ResponseEntity<Map> staleStopped = stop(exportId, version, "旧版本", "rs-stop-003");
        assertThat(staleStopped.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 未停止的导出用过期版本停止：409 版本冲突
        String freshExportId = activeExport("FX-RS-STOP-STALE-001");
        ResponseEntity<Map> stale = stop(freshExportId, version - 1, "旧版本", "rs-stop-004");
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(stale.getBody()).containsEntry("business_code", "VERSION_CONFLICT");

        // 缺认证头 400（携带 Idempotency-Key，确保到达 X-Operator 校验）
        HttpHeaders noOperatorHeaders = new HttpHeaders();
        noOperatorHeaders.setContentType(MediaType.APPLICATION_JSON);
        noOperatorHeaders.set("Idempotency-Key", "rs-stop-noop-001");
        ResponseEntity<Map> noOperator = http.exchange(
                "/api/v1/fulfillment-exports/" + exportId + "/wecom-stop",
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of("expected_version", versionOf(exportId), "reason", "无认证"),
                        noOperatorHeaders),
                Map.class);
        assertThat(noOperator.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(noOperator.getBody()).containsEntry("business_code", "OPERATOR_REQUIRED");

        // 缺理由 422
        HttpHeaders headers = writeHeaders("rs-stop-005");
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> noReason = http.exchange(
                "/api/v1/fulfillment-exports/" + exportId + "/wecom-stop",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("expected_version", versionOf(exportId)), headers),
                Map.class);
        assertThat(noReason.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(noReason.getBody()).containsEntry("business_code", "WECOM_STOP_REASON_REQUIRED");
    }

    @Test
    void stopBeforeInitialSendBlocksPendingTaskAndLegacyStopIsRejected() throws Exception {
        // PENDING 导出（未发送）也可停止：行为明确 = 后续任务 no-op 不发送
        String exportId = generateThirdPartyExport("FX-RS-STOP-PENDING-001");
        assertThat(stateRow(exportId).get("status")).isEqualTo("PENDING");
        ResponseEntity<Map> stopped = stop(exportId, versionOf(exportId), "生成后立即停用", "rs-stop-pending-001");
        assertThat(stopped.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(stateRow(exportId).get("status")).isEqualTo("MANUALLY_STOPPED");

        wecom.sentMessages.clear();
        claimAndRun(exportId, "INITIAL", 1);
        assertThat(wecom.sentMessages).isEmpty();
        assertThat(deliveryRow(exportId, "INITIAL", 1).get("status")).isEqualTo("PENDING");
        assertThat(taskStatus("wecom-export-initial:" + exportId)).isEqualTo("SUCCEEDED");

        // LEGACY：明确拒绝
        long legacyExportId = legacyExport();
        ResponseEntity<Map> legacyStop = stop(String.valueOf(legacyExportId), 0, "试图停止历史导出", "rs-stop-legacy-001");
        assertThat(legacyStop.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(legacyStop.getBody()).containsEntry("business_code", "WECOM_EXPORT_LEGACY");
    }

    // ------------------------------------------------------------------
    // 重发
    // ------------------------------------------------------------------

    @Test
    void resendCreatesNewInitialDeliveryAndTaskAndResetsTimelineOnAckResolvingAlerts() throws Exception {
        // 先让导出 FAILED 并产生告警
        String exportId = generateThirdPartyExport("FX-RS-RESEND-001");
        wecom.sendResult = new WecomSendResult(
                WecomSendStatus.FAILED, null, null, null, "CONNECTION_NOT_READY", true);
        claimAndRun(exportId, "INITIAL", 1);
        runDueTask(exportId, "INITIAL", 1);
        assertThat(stateRow(exportId).get("status")).isEqualTo("FAILED");
        assertThat(openAlertCount(exportId)).isEqualTo(1);
        long failedVersion = versionOf(exportId);

        // 重发：只生成新 delivery + 任务，不直接发送
        wecom.sendResult = new WecomSendResult(
                WecomSendStatus.SUCCESS, "resend-ack-2", Instant.now().plusSeconds(60), null, null, false);
        wecom.sentMessages.clear();
        ResponseEntity<Map> resend = resend(exportId, failedVersion, "群内未收到文件，重新发送", "rs-resend-001");
        assertThat(resend.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(wecom.sentMessages).isEmpty(); // HTTP 线程不直接发
        Map<String, Object> state = stateRow(exportId);
        assertThat(state.get("status")).isEqualTo("PENDING");
        assertThat(state.get("lock_version")).isEqualTo(failedVersion + 1);
        assertThat(resend.getBody()).containsEntry("resend_sequence", 2);
        Map<String, Object> delivery2 = deliveryRow(exportId, "INITIAL", 2);
        assertThat(delivery2.get("status")).isEqualTo("PENDING");
        assertThat(taskStatus("wecom-export-initial:" + exportId + ":" + delivery2.get("id"))).isEqualTo("PENDING");
        // 历史 delivery 保留（可追溯）
        assertThat(deliveryRow(exportId, "INITIAL", 1).get("status")).isEqualTo("FAILED");
        // 审计
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.audit_logs WHERE operation='wecom_export.resend' "
                        + "AND request_id='req-rs-resend-001'",
                Integer.class)).isEqualTo(1);

        // 同 Idempotency-Key 重放返回首次结果
        ResponseEntity<Map> replayed = resend(exportId, failedVersion, "群内未收到文件，重新发送", "rs-resend-001");
        assertThat(replayed.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(replayed.getBody()).isEqualTo(resend.getBody());
        assertThat(deliveryCount(exportId, "INITIAL")).isEqualTo(2);

        // 新任务执行成功：sent_at/due/提醒时间线以新 ack 重置；告警自动关闭
        Instant expectedAckAt = Instant.now().plusSeconds(60);
        wecom.sendResult = new WecomSendResult(
                WecomSendStatus.SUCCESS, "resend-ack-2", expectedAckAt, null, null, false);
        claimAndRun(exportId, "INITIAL", 2);
        state = stateRow(exportId);
        assertThat(state.get("status")).isEqualTo("ACTIVE");
        assertThat(instant(state.get("initial_sent_at")).getEpochSecond())
                .isEqualTo(expectedAckAt.getEpochSecond());
        assertThat(state.get("next_reminder_at")).isEqualTo(state.get("tracking_due_at"));
        assertThat(openAlertCount(exportId)).isZero();
        assertThat(resolvedAlertCount(exportId)).isEqualTo(1);
    }

    @Test
    void resendRejectsCompletedLegacyAndStaleVersion() throws Exception {
        String exportId = activeExport("FX-RS-RESEND-COMPLETE-001");
        // 收齐回传 → COMPLETED
        completeTracking(exportId);
        assertThat(stateRow(exportId).get("status")).isEqualTo("COMPLETED");
        ResponseEntity<Map> completed = resend(exportId, versionOf(exportId), null, "rs-resend-complete-001");
        assertThat(completed.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(completed.getBody()).containsEntry("business_code", "WECOM_EXPORT_TRACKING_COMPLETE");

        long legacyExportId = legacyExport();
        ResponseEntity<Map> legacy = resend(String.valueOf(legacyExportId), 0, null, "rs-resend-legacy-001");
        assertThat(legacy.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(legacy.getBody()).containsEntry("business_code", "WECOM_EXPORT_LEGACY");

        String freshExportId = activeExport("FX-RS-RESEND-STALE-001");
        ResponseEntity<Map> stale = resend(freshExportId, 999L, null, "rs-resend-stale-001");
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(stale.getBody()).containsEntry("business_code", "VERSION_CONFLICT");
    }

    // ------------------------------------------------------------------
    // 快照群（§6）：initial 成功后 chat_id 快照；履约方改群后人工重发仍发到快照群
    // ------------------------------------------------------------------

    @Test
    void resendAfterProviderGroupChangeStillSendsToSnapshotGroup() throws Exception {
        // 1) 成功发送到群 A（快照建立）
        String exportId = activeExport("FX-RS-SNAPSHOT-001");
        assertThat(stateRow(exportId).get("status")).isEqualTo("ACTIVE");
        assertThat(stateRow(exportId).get("chat_id")).isEqualTo("wrJgVnTQAAD-RS-001");
        assertThat(wecom.sentMessages).hasSize(1);
        assertThat(wecom.sentMessages.getFirst().chatId()).isEqualTo("wrJgVnTQAAD-RS-001");
        long snapshotVersion = versionOf(exportId);

        // 2) 履约方配置改到群 B（真实配置 seam：改完 resolver 立即生效）
        jdbc.update(
                """
                UPDATE app.fulfillment_providers
                SET config = config || '{"wecomGroupChatId":"wrJgVnTQAAD-RS-GROUP-B"}'::jsonb
                WHERE provider_code='TP'
                """);
        assertThat(jdbc.queryForObject(
                "SELECT config->>'wecomGroupChatId' FROM app.fulfillment_providers WHERE provider_code='TP'",
                String.class)).isEqualTo("wrJgVnTQAAD-RS-GROUP-B");

        // 3) 人工重发并运行其 delivery
        wecom.sentMessages.clear();
        ResponseEntity<Map> resend = resend(exportId, snapshotVersion, "群内未收到文件，重发", "rs-snapshot-001");
        assertThat(resend.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        int sequence = ((Number) resend.getBody().get("resend_sequence")).intValue();
        assertThat(sequence).isEqualTo(2);
        claimAndRun(exportId, "INITIAL", sequence);

        // 4) 重发文件消息仍发到快照群 A，而不是新配置的群 B（§6：不重新解析）
        assertThat(wecom.sentMessages).hasSize(1);
        WecomOutboundMessage resent = wecom.sentMessages.getFirst();
        assertThat(resent.type()).isEqualTo(WecomOutboundMessage.Type.FILE);
        assertThat(resent.chatId()).isEqualTo("wrJgVnTQAAD-RS-001");
        assertThat(stateRow(exportId).get("chat_id")).isEqualTo("wrJgVnTQAAD-RS-001");
        assertThat(deliveryRow(exportId, "INITIAL", sequence).get("chat_id"))
                .isEqualTo("wrJgVnTQAAD-RS-001");
    }

    @Test
    void resendWithoutSuccessfulSnapshotResolvesCurrentConfiguredGroup() throws Exception {
        // 1) 初始发送在 ack 前失败（无快照：state.chat_id 为 NULL）
        String exportId = generateThirdPartyExport("FX-RS-FALLBACK-001");
        wecom.sendResult = new WecomSendResult(
                WecomSendStatus.FAILED, null, null, null, "CONNECTION_NOT_READY", true);
        claimAndRun(exportId, "INITIAL", 1);
        runDueTask(exportId, "INITIAL", 1);
        assertThat(stateRow(exportId).get("status")).isEqualTo("FAILED");
        assertThat(stateRow(exportId).get("chat_id")).isNull();
        long failedVersion = versionOf(exportId);

        // 2) 履约方配置改到群 B
        jdbc.update(
                """
                UPDATE app.fulfillment_providers
                SET config = config || '{"wecomGroupChatId":"wrJgVnTQAAD-RS-GROUP-B"}'::jsonb
                WHERE provider_code='TP'
                """);

        // 3) 安全重发：无快照 → 允许按当前配置正常解析（只影响本次成功后的新快照）
        wecom.sendResult = new WecomSendResult(
                WecomSendStatus.SUCCESS, "resend-ack-fallback-1", Instant.now(), null, null, false);
        wecom.sentMessages.clear();
        ResponseEntity<Map> resend = resend(exportId, failedVersion, "无快照重发", "rs-fallback-001");
        assertThat(resend.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        int sequence = ((Number) resend.getBody().get("resend_sequence")).intValue();
        assertThat(sequence).isEqualTo(2);
        claimAndRun(exportId, "INITIAL", sequence);

        // 4) 重发按当前配置解析到群 B 并成为新快照
        assertThat(wecom.sentMessages).hasSize(1);
        assertThat(wecom.sentMessages.getFirst().chatId()).isEqualTo("wrJgVnTQAAD-RS-GROUP-B");
        assertThat(stateRow(exportId).get("chat_id")).isEqualTo("wrJgVnTQAAD-RS-GROUP-B");
    }

    // ------------------------------------------------------------------
    // 契约：expected_version 必填 / null body / reason 长度 / in-flight 阻止 / 并发
    // ------------------------------------------------------------------

    @Test
    void missingExpectedVersionIsRejectedEvenWhenStateVersionIsZero() throws Exception {
        // 未发送导出 version=0：缺 expected_version 也必须 400，不能静默绑定 0 绕过锁
        String exportId = generateThirdPartyExport("FX-RS-VERSION-001");
        assertThat(stateRow(exportId).get("lock_version")).isEqualTo(0L);
        HttpHeaders headers = writeHeaders("rs-missing-version-001");
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Map> stopResp = http.exchange(
                "/api/v1/fulfillment-exports/" + exportId + "/wecom-stop",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("reason", "理由"), headers),
                Map.class);
        assertThat(stopResp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(stopResp.getBody()).containsEntry("business_code", "VALIDATION_ERROR");

        ResponseEntity<Map> resendResp = http.exchange(
                "/api/v1/fulfillment-exports/" + exportId + "/wecom-resend",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(), headers),
                Map.class);
        assertThat(resendResp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resendResp.getBody()).containsEntry("business_code", "VALIDATION_ERROR");

        // 状态未被静默改写：仍 PENDING、version=0、无新 delivery
        assertThat(stateRow(exportId).get("status")).isEqualTo("PENDING");
        assertThat(stateRow(exportId).get("lock_version")).isEqualTo(0L);
        assertThat(deliveryCount(exportId, "INITIAL")).isEqualTo(1);
    }

    @Test
    void nullJsonBodyIsRejectedWithoutServerError() throws Exception {
        String exportId = activeExport("FX-RS-NULL-001");
        HttpHeaders headers = writeHeaders("rs-null-001");
        headers.setContentType(MediaType.APPLICATION_JSON);

        // JSON null body：明确 400/422 拒绝，绝不允许 500（Map.of NPE）
        ResponseEntity<Map> stopResp = http.exchange(
                "/api/v1/fulfillment-exports/" + exportId + "/wecom-stop",
                HttpMethod.POST,
                new HttpEntity<>("null", headers),
                Map.class);
        assertThat(stopResp.getStatusCode()).isIn(HttpStatus.BAD_REQUEST, HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(stopResp.getBody()).containsKey("business_code");

        ResponseEntity<Map> resendResp = http.exchange(
                "/api/v1/fulfillment-exports/" + exportId + "/wecom-resend",
                HttpMethod.POST,
                new HttpEntity<>("null", headers),
                Map.class);
        assertThat(resendResp.getStatusCode()).isIn(HttpStatus.BAD_REQUEST, HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(resendResp.getBody()).containsKey("business_code");
    }

    @Test
    void oversizedReasonIsRejectedOnBothCommands() throws Exception {
        String exportId = activeExport("FX-RS-REASON-001");
        HttpHeaders headers = writeHeaders("rs-reason-001");
        headers.setContentType(MediaType.APPLICATION_JSON);
        String longReason = "x".repeat(501);

        ResponseEntity<Map> stopResp = http.exchange(
                "/api/v1/fulfillment-exports/" + exportId + "/wecom-stop",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("expected_version", versionOf(exportId), "reason", longReason), headers),
                Map.class);
        assertThat(stopResp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(stopResp.getBody()).containsEntry("business_code", "VALIDATION_ERROR");

        ResponseEntity<Map> resendResp = http.exchange(
                "/api/v1/fulfillment-exports/" + exportId + "/wecom-resend",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("expected_version", versionOf(exportId), "reason", longReason), headers),
                Map.class);
        assertThat(resendResp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resendResp.getBody()).containsEntry("business_code", "VALIDATION_ERROR");
    }

    @Test
    void resendIsBlockedWhileAnyInitialDeliveryIsInFlight() throws Exception {
        // 初始自动发送仍在队列（INITIAL PENDING）：重发必须 409，禁止并发双发
        String exportId = generateThirdPartyExport("FX-RS-INFLIGHT-001");
        assertThat(stateRow(exportId).get("status")).isEqualTo("PENDING");
        ResponseEntity<Map> blocked = resend(exportId, versionOf(exportId), "并发重发", "rs-inflight-001");
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(blocked.getBody()).containsEntry("business_code", "WECOM_RESEND_IN_FLIGHT");
        assertThat(deliveryCount(exportId, "INITIAL")).isEqualTo(1);
        assertThat(stateRow(exportId).get("status")).isEqualTo("PENDING");
    }

    @Test
    void concurrentResendsCreateExactlyOneNewDelivery() throws Exception {
        String exportId = generateThirdPartyExport("FX-RS-CONCURRENT-001");
        wecom.sendResult = new WecomSendResult(
                WecomSendStatus.FAILED, null, null, null, "CONNECTION_NOT_READY", true);
        claimAndRun(exportId, "INITIAL", 1);
        runDueTask(exportId, "INITIAL", 1);
        assertThat(stateRow(exportId).get("status")).isEqualTo("FAILED");
        long version = versionOf(exportId);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<ResponseEntity<Map>>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < 2; i++) {
                int idx = i;
                futures.add(pool.submit(() -> {
                    start.await();
                    return resend(exportId, version, "并发重发-" + idx, "rs-concurrent-" + idx);
                }));
            }
            start.countDown();
            List<ResponseEntity<Map>> results = new java.util.ArrayList<>();
            for (Future<ResponseEntity<Map>> future : futures) {
                results.add(future.get(15, TimeUnit.SECONDS));
            }
            long accepted = results.stream().filter(r -> r.getStatusCode() == HttpStatus.ACCEPTED).count();
            long conflicts = results.stream().filter(r -> r.getStatusCode() == HttpStatus.CONFLICT).count();
            assertThat(accepted).as("并发重发只允许一个成功").isEqualTo(1);
            assertThat(conflicts).as("另一个必须是确定性的 409（版本冲突或在途）").isEqualTo(1);
            assertThat(deliveryCount(exportId, "INITIAL")).isEqualTo(2);
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private String activeExport(String orderRef) throws Exception {
        String exportId = generateThirdPartyExport(orderRef);
        claimAndRun(exportId, "INITIAL", 1);
        assertThat(stateRow(exportId).get("status")).isEqualTo("ACTIVE");
        return exportId;
    }

    private String generateThirdPartyExport(String orderRef) throws Exception {
        ResponseEntity<Map> uploaded = uploadRaw(
                "resend.csv", feixiangSingleCsv(orderRef), "source-import-" + orderRef.toLowerCase());
        assertThat(uploaded.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> batch = uploaded.getBody();
        ResponseEntity<Map> confirmed = confirmBatch(
                batch.get("id").toString(), "confirm-" + orderRef.toLowerCase());
        assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.OK);
        return ((List<?>) confirmed.getBody().get("generated_fulfillment_export_ids")).getFirst().toString();
    }

    private long legacyExport() {
        // 与 V46 迁移一致：历史第三方导出登记为 LEGACY 状态行（无 delivery、无任务）
        return jdbc.queryForObject(
                """
                WITH inserted AS (
                    INSERT INTO app.fulfillment_exports
                        (export_batch_no, fulfillment_provider_id, export_kind, template_version,
                         file_ref, file_sha256, tracking_due_at, generated_by)
                    VALUES ('EXP-RS-LEGACY-' || substring(md5(random()::text) from 1 for 8),
                            (SELECT id FROM app.fulfillment_providers WHERE provider_code='TP'),
                            'THIRD_PARTY', 'v1-24-columns', '/tmp/legacy-rs.xlsx',
                            REPEAT('b', 64), CURRENT_TIMESTAMP + INTERVAL '1 day', 'legacy')
                    RETURNING id
                )
                INSERT INTO app.fulfillment_export_wecom_states
                    (export_id, provider_id, status, tracking_sla_minutes, reminder_interval_minutes)
                SELECT id, (SELECT id FROM app.fulfillment_providers WHERE provider_code='TP'),
                       'LEGACY', 1440, 1440
                FROM inserted
                RETURNING export_id
                """,
                Long.class);
    }

    private void completeTracking(String exportId) throws Exception {
        byte[] instruction = downloadExport(exportId);
        byte[] returned = fillThirdPartyTracking(instruction);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(returned) {
            @Override public String getFilename() { return "tracking.xlsx"; }
        });
        body.add("import_mode", "NEW");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.set("Idempotency-Key", "rs-tracking-complete-001");
        headers.set("X-Operator", "resend-test");
        ResponseEntity<Map> tracking = http.exchange(
                "/api/v1/fulfillment-exports/" + exportId + "/tracking-imports",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class);
        assertThat(tracking.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private ResponseEntity<Map> stop(String exportId, long version, String reason, String idempotencyKey) {
        HttpHeaders headers = writeHeaders(idempotencyKey);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(
                "/api/v1/fulfillment-exports/" + exportId + "/wecom-stop",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("expected_version", version, "reason", reason), headers),
                Map.class);
    }

    private ResponseEntity<Map> resend(String exportId, long version, String reason, String idempotencyKey) {
        HttpHeaders headers = writeHeaders(idempotencyKey);
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("expected_version", version);
        if (reason != null) {
            body.put("reason", reason);
        }
        return http.exchange(
                "/api/v1/fulfillment-exports/" + exportId + "/wecom-resend",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class);
    }

    private void claimAndRun(String exportId, String kind, int sequence) {
        String expected = "export:" + exportId + ":" + kind + ":" + sequence;
        for (int i = 0; i < 10; i++) {
            var claimed = taskStore.claim(
                            FulfillmentExportWecomService.TASK_TYPE, "resend-test", Duration.ofSeconds(30))
                    .orElseThrow(() -> new AssertionError("expected claimable task " + expected));
            if (expected.equals(claimed.payloadRef())) {
                runner.execute(claimed);
                return;
            }
            taskStore.succeed(claimed.id(), claimed.leaseOwner());
        }
        throw new AssertionError("task not claimable: " + expected);
    }

    private void runDueTask(String exportId, String kind, int sequence) {
        jdbc.update(
                "UPDATE app.async_tasks SET next_run_at=CURRENT_TIMESTAMP WHERE idempotency_key=?",
                FulfillmentExportWecomStore.INITIAL.equals(kind)
                        ? "wecom-export-initial:" + exportId
                        : "wecom-export-reminder:" + exportId + ":" + sequence);
        claimAndRun(exportId, kind, sequence);
    }

    private Map<String, Object> stateRow(String exportId) {
        return jdbc.queryForMap(
                "SELECT * FROM app.fulfillment_export_wecom_states WHERE export_id=?",
                Long.parseLong(exportId));
    }

    private Map<String, Object> deliveryRow(String exportId, String kind, int sequence) {
        return jdbc.queryForMap(
                "SELECT * FROM app.fulfillment_export_wecom_deliveries "
                        + "WHERE export_id=? AND kind=? AND sequence=?",
                Long.parseLong(exportId), kind, sequence);
    }

    private int deliveryCount(String exportId, String kind) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM app.fulfillment_export_wecom_deliveries "
                        + "WHERE export_id=? AND kind=?",
                Integer.class, Long.parseLong(exportId), kind);
    }

    private long versionOf(String exportId) {
        return jdbc.queryForObject(
                "SELECT lock_version FROM app.fulfillment_export_wecom_states WHERE export_id=?",
                Long.class, Long.parseLong(exportId));
    }

    private String taskStatus(String idempotencyKey) {
        return jdbc.queryForObject(
                "SELECT status FROM app.async_tasks WHERE idempotency_key=?", String.class, idempotencyKey);
    }

    private int openAlertCount(String exportId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM app.operational_alerts WHERE alert_type='FULFILLMENT_EXPORT_WECOM' "
                        + "AND status='OPEN' AND detail->>'export_id'=?",
                Integer.class, exportId);
    }

    private int resolvedAlertCount(String exportId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM app.operational_alerts WHERE alert_type='FULFILLMENT_EXPORT_WECOM' "
                        + "AND status='RESOLVED' AND detail->>'export_id'=?",
                Integer.class, exportId);
    }

    private byte[] downloadExport(String exportId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Operator", "resend-test");
        ResponseEntity<byte[]> response = http.exchange(
                "/api/v1/fulfillment-exports/" + exportId + "/file",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                byte[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private byte[] fillThirdPartyTracking(byte[] instruction) throws Exception {
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook workbook =
                        new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(instruction));
                java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            var sheet = workbook.getSheetAt(0);
            var header = sheet.getRow(0);
            var fmt = new org.apache.poi.ss.usermodel.DataFormatter();
            java.util.Map<String, Integer> columns = new java.util.LinkedHashMap<>();
            for (int index = 0; index < header.getLastCellNum(); index++) {
                columns.put(fmt.formatCellValue(header.getCell(index)).strip(), index);
            }
            var row = sheet.getRow(1);
            java.util.function.BiConsumer<Integer, String> put = (col, value) -> {
                if (col == null) return;
                var cell = row.getCell(col);
                if (cell == null) cell = row.createCell(col);
                cell.setCellValue(value == null ? "" : value);
            };
            if (columns.containsKey("运单号")) {
                put.accept(columns.get("快递公司"), "京东物流");
                put.accept(columns.get("运单号"), "JDVA-RS-COMPLETE-001");
            } else {
                put.accept(columns.get("结果"), "SHIPPED");
                put.accept(columns.get("实际发货数量"), "3.000");
                put.accept(columns.get("快递公司"), "京东物流");
                put.accept(columns.get("物流单号"), "JDVA-RS-COMPLETE-001");
                put.accept(columns.get("发货时间"), "2026-08-12 12:00:00");
            }
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private ResponseEntity<Map> uploadRaw(String filename, byte[] bytes, String idempotencyKey) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(bytes) {
            @Override public String getFilename() { return filename; }
        });
        body.add("import_mode", "NEW");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.set("Idempotency-Key", idempotencyKey);
        headers.set("X-Operator", "resend-test");
        headers.set("X-Request-Id", "req-" + idempotencyKey);
        return http.exchange(
                "/api/v1/import-batches/source-orders",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class);
    }

    private ResponseEntity<Map> confirmBatch(String batchId, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", idempotencyKey);
        headers.set("X-Operator", "resend-test");
        headers.set("X-Request-Id", "req-" + idempotencyKey);
        return http.exchange(
                "/api/v1/import-batches/" + batchId + "/confirm",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(), headers),
                Map.class);
    }

    private byte[] feixiangSingleCsv(String orderRef) {
        String header = String.join(",", List.of(
                "订单号", "会员名称", "商品名称", "商品ID", "订单商品ID", "可发货数量",
                "收货人姓名", "收货人手机号", "收货人地址", "下单时间", "物流状态", "物流公司", "物流单号"));
        String row = orderRef + ",FX-MEMBER-001,子牧羊小腿,FX-PRODUCT-001," + orderRef
                + "-LINE,1.500,张三,13800000000,上海市浦东新区测试路1号,2026-08-11 10:00:00,,,\r\n";
        return ("\uFEFF" + header + "\r\n" + row).getBytes(StandardCharsets.UTF_8);
    }

    private HttpHeaders writeHeaders(String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", idempotencyKey);
        headers.set("X-Request-Id", "req-" + idempotencyKey);
        headers.set("X-Operator", "resend-test");
        return headers;
    }

    private static Instant instant(Object value) {
        return ((java.sql.Timestamp) value).toInstant();
    }
}
