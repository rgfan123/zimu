package cn.zimu.fulfillment.file;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zimu.fulfillment.connector.wecom.WecomMediaType;
import cn.zimu.fulfillment.connector.wecom.WecomOutboundMessage;
import cn.zimu.fulfillment.connector.wecom.WecomOutboundTransport;
import cn.zimu.fulfillment.connector.wecom.WecomSendResult;
import cn.zimu.fulfillment.connector.wecom.WecomSendStatus;
import cn.zimu.fulfillment.connector.wecom.WecomUploadResult;
import cn.zimu.fulfillment.connector.wecom.WecomUploadStatus;
import cn.zimu.fulfillment.connector.wecom.WecomUploadValidationException;
import cn.zimu.fulfillment.message.AsyncTaskStore;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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
 * Issue #84 核心流水线：生成同事务建状态+任务（普通/续发，JD/历史不入队）→ Worker
 * resolve chat → upload → 文件消息 send → sent_at/due；失败/未知/崩溃语义与告警。
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "app.message-worker.enabled=false",
            "app.wecom-export-worker.enabled=false",
            "app.wecom-reminder.enabled=false",
            "app.wecom-export-worker.backoff-seconds=1",
            "app.file-store.root=${java.io.tmpdir}/zimu-wecom-export-pipeline-test"
        })
@Import(FulfillmentExportWecomPipelineApiTest.ControlledWecomTransportConfig.class)
class FulfillmentExportWecomPipelineApiTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired AsyncTaskStore taskStore;
    @Autowired FulfillmentExportWecomDeliveryRunner runner;
    @Autowired FulfillmentExportWecomWorker worker;
    @Autowired ControlledWecomTransport wecom;

    @TestConfiguration
    static class ControlledWecomTransportConfig {
        @Bean
        @Primary
        ControlledWecomTransport controlledWecomTransport() {
            return new ControlledWecomTransport();
        }
    }

    /** 受控传输：替代真实长连接，测试按需注入 upload/send 结局并记录调用证据。 */
    static class ControlledWecomTransport implements WecomOutboundTransport {
        final List<WecomOutboundMessage> sentMessages = new CopyOnWriteArrayList<>();
        final List<String> uploadedFilenames = new CopyOnWriteArrayList<>();
        volatile WecomUploadResult uploadResult =
                uploadSuccess("MEDIA-PIPELINE-1");
        volatile WecomSendResult sendResult = sendSuccess("send-req-pipeline-1");
        /** 非 null 时 upload 直接抛前置校验异常（确定性失败路径）。 */
        volatile WecomUploadValidationException uploadValidationError;

        @Override
        public WecomSendResult send(WecomOutboundMessage message) {
            sentMessages.add(message);
            return sendResult;
        }

        @Override
        public WecomUploadResult upload(Path file, String filename, WecomMediaType type) {
            WecomUploadValidationException validation = uploadValidationError;
            if (validation != null) {
                throw validation;
            }
            uploadedFilenames.add(filename);
            return uploadResult;
        }
    }

    @BeforeEach
    void resetTransportAndConfigureProvider() {
        wecom.sentMessages.clear();
        wecom.uploadedFilenames.clear();
        wecom.uploadResult = uploadSuccess("MEDIA-PIPELINE-1");
        wecom.sendResult = sendSuccess("send-req-pipeline-1");
        wecom.uploadValidationError = null;
        // 飞象来源的客户与商品显式映射（与既有闭环测试同源）
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
        // 每次用例默认登记第三方履约方的企微群（未登记场景由用例显式清除）
        jdbc.update(
                """
                UPDATE app.fulfillment_providers
                SET config = config || '{"wecomGroupChatId":"wrJgVnTQAAD-TP-001"}'::jsonb
                WHERE provider_code='TP'
                """);
    }

    // ------------------------------------------------------------------
    // 生成：状态 + 任务同事务；JD/历史不入队
    // ------------------------------------------------------------------

    @Test
    void thirdPartyGenerationCreatesStateAndInitialTaskButJdAndLegacyNeverEnqueue() throws Exception {
        Map<String, Object> imported = importAndConfirm("FX-PIPELINE-001");
        String exportId = ((List<?>) imported.get("generated_fulfillment_export_ids")).getFirst().toString();

        // 状态行 PENDING + 快照（SLA 与提醒间隔默认=SLA）
        Map<String, Object> state = stateRow(exportId);
        assertThat(state.get("status")).isEqualTo("PENDING");
        assertThat(state.get("tracking_sla_minutes")).isEqualTo(state.get("reminder_interval_minutes"));
        assertThat(state.get("initial_sent_at")).isNull();
        assertThat(state.get("tracking_due_at")).isNull();
        // delivery INITIAL seq=1 PENDING（外部尝试上限 2）+ async task（总领取上限 3：第 3 次只做告警收口）
        assertThat(deliveryRow(exportId, "INITIAL", 1).get("status")).isEqualTo("PENDING");
        assertThat(deliveryRow(exportId, "INITIAL", 1).get("max_attempts")).isEqualTo(2);
        assertThat(taskFor("wecom-export-initial:" + exportId))
                .containsEntry("task_type", "WECOM_EXPORT_DELIVERY")
                .containsEntry("max_attempts", 3);
        // API/UI 不得展示假的「已到期时间」
        Map<String, Object> apiExport = get("/api/v1/fulfillment-exports/" + exportId);
        assertThat(apiExport.get("tracking_due_at")).isNull();
        assertThat(apiExport.get("wecom")).isNotNull();

        // JD 导出（文件路由）不建状态、不入队
        Map<String, Object> jdImported = importAndConfirmJd("FX-PIPELINE-JD-001");
        List<?> jdExportIds = (List<?>) jdImported.get("generated_fulfillment_export_ids");
        assertThat(jdExportIds).isNotEmpty();
        for (Object id : jdExportIds) {
            assertThat(stateRowCount(id.toString())).isZero();
            assertThat(taskCount("wecom-export-initial:" + id)).isZero();
        }

        // 历史第三方导出（迁移 LEGACY 语义）：有状态行但无 delivery、无任务、API 显示 LEGACY
        long legacyExportId = jdbc.queryForObject(
                """
                INSERT INTO app.fulfillment_exports
                    (export_batch_no, fulfillment_provider_id, export_kind, template_version,
                     file_ref, file_sha256, tracking_due_at, generated_by)
                VALUES ('EXP-LEGACY-001', (SELECT id FROM app.fulfillment_providers WHERE provider_code='TP'),
                        'THIRD_PARTY', 'v1-24-columns', '/tmp/legacy.xlsx',
                        REPEAT('a', 64), CURRENT_TIMESTAMP + INTERVAL '1 day', 'legacy')
                RETURNING id
                """,
                Long.class);
        jdbc.update(
                """
                INSERT INTO app.fulfillment_export_wecom_states
                    (export_id, provider_id, status, tracking_sla_minutes, reminder_interval_minutes)
                VALUES (?, (SELECT id FROM app.fulfillment_providers WHERE provider_code='TP'),
                        'LEGACY', 1440, 1440)
                """,
                legacyExportId);
        assertThat(deliveryCount(String.valueOf(legacyExportId), "INITIAL")).isZero();
        assertThat(deliveryCount(String.valueOf(legacyExportId), "REMINDER")).isZero();
        assertThat(taskCount("wecom-export-" + legacyExportId)).isZero();
        Map<String, Object> legacyApi = get("/api/v1/fulfillment-exports/" + legacyExportId);
        assertThat(((Map<?, ?>) legacyApi.get("wecom")).get("status")).isEqualTo("LEGACY");
        // LEGACY 保持旧 generated_at 派生 due（不展示新语义），且无任何任务
        assertThat(legacyApi.get("tracking_due_at")).isNotNull();
    }

    @Test
    void continuationExportRegistersItsOwnStateAndInitialTask() throws Exception {
        Map<String, Object> imported = importAndConfirm("FX-PIPELINE-CONT-001");
        String exportId = ((List<?>) imported.get("generated_fulfillment_export_ids")).getFirst().toString();
        String fulfillmentId = firstFulfillmentId(exportId);

        // 部分回传后创建续发批次
        byte[] instruction = downloadExport(exportId);
        byte[] partial = fillThirdPartyTracking(instruction, "PARTIAL", "1.000", "JDVA-CONT-001");
        ResponseEntity<Map> accepted = uploadTracking(exportId, partial, "tracking-cont-001");
        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long version = jdbc.queryForObject(
                "SELECT lock_version FROM app.fulfillments WHERE id=?", Long.class,
                Long.parseLong(fulfillmentId));
        ResponseEntity<Map> continuation = http.exchange(
                "/api/v1/fulfillments/" + fulfillmentId + "/continuation-exports",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "expected_version", version,
                        "instructed_quantity", "2.000",
                        "remark", "续发"),
                        writeHeaders("continuation-pipeline-001")),
                Map.class);
        assertThat(continuation.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String continuationExportId =
                continuation.getBody().get("fulfillment_export_id").toString();

        assertThat(stateRow(continuationExportId).get("status")).isEqualTo("PENDING");
        assertThat(deliveryRow(continuationExportId, "INITIAL", 1).get("status")).isEqualTo("PENDING");
        assertThat(taskFor("wecom-export-initial:" + continuationExportId))
                .containsEntry("max_attempts", 3);
    }

    // ------------------------------------------------------------------
    // Worker：成功路径（upload → send → sent_at/due）
    // ------------------------------------------------------------------

    @Test
    void initialDeliveryUploadsThenSendsFileMessageAndOnlyAckSetsSentAtAndDue() throws Exception {
        String exportId = generateThirdPartyExport("FX-PIPELINE-OK-001");
        Instant ackAt = Instant.now().plusSeconds(30);
        wecom.sendResult = new WecomSendResult(
                WecomSendStatus.SUCCESS, "send-req-ok-1", ackAt, null, null, false);
        int sla = (int) stateRow(exportId).get("tracking_sla_minutes");

        claimAndRun(exportId, "INITIAL", 1);

        // 上传证据：流式路径读取的是导出文件本身，文件名 = 批次号 + .xlsx
        assertThat(wecom.uploadedFilenames).hasSize(1);
        assertThat(wecom.uploadedFilenames.getFirst()).isEqualTo(exportBatchNo(exportId) + ".xlsx");
        // 发送的是 FILE 消息，群 = 快照群
        assertThat(wecom.sentMessages).hasSize(1);
        WecomOutboundMessage message = wecom.sentMessages.getFirst();
        assertThat(message.type()).isEqualTo(WecomOutboundMessage.Type.FILE);
        assertThat(message.chatId()).isEqualTo("wrJgVnTQAAD-TP-001");
        assertThat(message.mediaId()).isEqualTo("MEDIA-PIPELINE-1");

        Map<String, Object> delivery = deliveryRow(exportId, "INITIAL", 1);
        assertThat(delivery.get("status")).isEqualTo("SENT");
        assertThat(instant(delivery.get("ack_sent_at")).getEpochSecond())
                .isEqualTo(ackAt.getEpochSecond());
        assertThat(delivery.get("request_id")).isEqualTo("send-req-ok-1");
        // media_id 明文不落库，只存摘要
        assertThat(delivery.get("media_id_sha256")).isEqualTo(sha256("MEDIA-PIPELINE-1"));
        String deliveryJson = jdbc.queryForObject(
                "SELECT row_to_json(d)::text FROM app.fulfillment_export_wecom_deliveries d WHERE id=?",
                String.class, delivery.get("id"));
        assertThat(deliveryJson).doesNotContain("MEDIA-PIPELINE-1");

        Map<String, Object> state = stateRow(exportId);
        assertThat(state.get("status")).isEqualTo("ACTIVE");
        assertThat(instant(state.get("initial_sent_at")).getEpochSecond())
                .isEqualTo(ackAt.getEpochSecond());
        assertThat(instant(state.get("tracking_due_at")).getEpochSecond())
                .isEqualTo(ackAt.plus(Duration.ofMinutes(sla)).getEpochSecond());
        assertThat(instant(state.get("next_reminder_at"))).isEqualTo(instant(state.get("tracking_due_at")));
        assertThat(state.get("chat_id")).isEqualTo("wrJgVnTQAAD-TP-001");

        // API 投影：due 以 sent_at 派生（不再是 generated_at 派生）
        Map<String, Object> apiExport = get("/api/v1/fulfillment-exports/" + exportId);
        Map<?, ?> wecomView = (Map<?, ?>) apiExport.get("wecom");
        assertThat(wecomView.get("status")).isEqualTo("ACTIVE");
        assertThat(Instant.parse(apiExport.get("tracking_due_at").toString()).getEpochSecond())
                .isEqualTo(instant(state.get("tracking_due_at")).getEpochSecond());
        // 任务成功收口
        assertThat(taskFor("wecom-export-initial:" + exportId)).containsEntry("status", "SUCCEEDED");
    }

    @Test
    void reminderIntervalSnapshotIsTakenAtGenerationAndConfigChangeDoesNotRetroact() throws Exception {
        jdbc.update(
                """
                UPDATE app.fulfillment_providers
                SET config = config || '{"wecomReminderIntervalMinutes":30}'::jsonb
                WHERE provider_code='TP'
                """);
        String exportId = generateThirdPartyExport("FX-PIPELINE-SNAP-001");
        assertThat(stateRow(exportId).get("reminder_interval_minutes")).isEqualTo(30);

        // 生成后改配置不追溯既有导出
        jdbc.update(
                """
                UPDATE app.fulfillment_providers
                SET config = jsonb_set(config, '{wecomReminderIntervalMinutes}', '120'::jsonb, true)
                WHERE provider_code='TP'
                """);
        assertThat(stateRow(exportId).get("reminder_interval_minutes")).isEqualTo(30);
    }

    @Test
    void invalidStoredReminderIntervalFallsBackToSlaDefaultThroughConfigContract() throws Exception {
        // 存量脏值（非 JSON 数字）：契约模块显式回退 SLA 默认，生成不被阻断
        jdbc.update(
                """
                UPDATE app.fulfillment_providers
                SET config = config || '{"wecomReminderIntervalMinutes":"abc"}'::jsonb
                WHERE provider_code='TP'
                """);
        String exportId = generateThirdPartyExport("FX-PIPELINE-DIRTY-SNAP-001");
        assertThat(stateRow(exportId).get("reminder_interval_minutes"))
                .isEqualTo(stateRow(exportId).get("tracking_sla_minutes"));
    }

    @Test
    void uploadValidationFailureIsDeterministicTerminalFailedWithoutRetry() throws Exception {
        String exportId = generateThirdPartyExport("FX-PIPELINE-UPLOAD-INVALID-001");
        wecom.uploadValidationError =
                new WecomUploadValidationException("UPLOAD_FILE_TOO_LARGE", "文件大小超过企微素材上限");

        claimAndRun(exportId, "INITIAL", 1);

        // 前置校验失败：确定性终态 FAILED（不是 UNKNOWN），单次尝试、绝不重试
        Map<String, Object> delivery = deliveryRow(exportId, "INITIAL", 1);
        assertThat(delivery.get("status")).isEqualTo("FAILED");
        assertThat(delivery.get("attempts")).isEqualTo(1);
        assertThat(delivery.get("error_code")).isEqualTo("UPLOAD_FILE_TOO_LARGE");
        assertThat(delivery.get("error_message").toString()).contains("文件大小超过");
        assertThat(stateRow(exportId).get("status")).isEqualTo("FAILED");
        assertThat(wecom.uploadedFilenames).isEmpty(); // 校验在 init 之前，未发生任何上传
        assertThat(wecom.sentMessages).isEmpty();
        assertThat(openAlert(exportId).get("detail").toString()).contains("UPLOAD_FILE_TOO_LARGE");
        // 任务收口为 SUCCEEDED（终态事实已落库），不再重试
        assertThat(taskFor("wecom-export-initial:" + exportId)).containsEntry("status", "SUCCEEDED");
    }

    @Test
    void sendFailuresUseLocalStableCodesWithServerErrcodeEvidence() throws Exception {
        // retryable send 失败：error_code 用本地稳定码，服务端 errmsg 只进 error_message
        String exportId = generateThirdPartyExport("FX-PIPELINE-SEND-CODE-001");
        wecom.sendResult = new WecomSendResult(
                WecomSendStatus.FAILED, "send-req-code-1", null, null, "CONNECTION_NOT_READY", true);
        claimAndRun(exportId, "INITIAL", 1);
        assertThat(deliveryRow(exportId, "INITIAL", 1).get("error_code")).isEqualTo("WECOM_SEND_FAILED_RETRYABLE");

        // 第 2 次成功：错误证据被清除
        wecom.sendResult = sendSuccess("send-req-code-2");
        runDueTask(exportId, "INITIAL", 1);
        Map<String, Object> delivery = deliveryRow(exportId, "INITIAL", 1);
        assertThat(delivery.get("status")).isEqualTo("SENT");
        assertThat(delivery.get("error_code")).isNull();
        assertThat(delivery.get("error_message")).isNull();

        // 非 retryable send 失败（服务端拒绝）：本地码 WECOM_SEND_FAILED + errcode 组合进消息
        String secondExport = generateThirdPartyExport("FX-PIPELINE-SEND-REJECT-001");
        wecom.sendResult = new WecomSendResult(
                WecomSendStatus.FAILED, "send-req-reject-1", null, 60020, "invalid chatid", false);
        claimAndRun(secondExport, "INITIAL", 1);
        Map<String, Object> rejected = deliveryRow(secondExport, "INITIAL", 1);
        assertThat(rejected.get("status")).isEqualTo("UNKNOWN");
        assertThat(rejected.get("error_code")).isEqualTo("WECOM_SEND_FAILED");
        assertThat(rejected.get("error_message").toString())
                .contains("invalid chatid")
                .contains("errcode=60020");
    }

    // ------------------------------------------------------------------
    // Worker 租约：默认覆盖 #82 上传有界上界；租约活跃期绝不被重新领取
    // ------------------------------------------------------------------

    @Test
    void workerLeaseDefaultCoversBoundedUploadUpperBound() {
        assertThat(worker.lease()).isGreaterThanOrEqualTo(Duration.ofMinutes(30));
    }

    @Test
    void taskIsNotReclaimedWhileLeaseIsActiveAndOnlyAfterExpiry() {
        taskStore.enqueue(
                FulfillmentExportWecomService.TASK_TYPE,
                "export:999999:INITIAL:1",
                "lease-test-key-" + System.nanoTime(),
                FulfillmentExportWecomService.TASK_MAX_ATTEMPTS);
        var first = taskStore.claim(
                        FulfillmentExportWecomService.TASK_TYPE, "lease-test-owner-1", Duration.ofMinutes(30))
                .orElseThrow(() -> new AssertionError("expected claimable task"));
        // 租约活跃：第二实例（不同 owner）不得重新领取
        assertThat(taskStore.claim(
                        FulfillmentExportWecomService.TASK_TYPE, "lease-test-owner-2", Duration.ofMinutes(30)))
                .isEmpty();
        // 租约过期（真实执行已崩溃/超时）后才可被重新领取：SENDING 恢复的前提
        jdbc.update(
                "UPDATE app.async_tasks SET lease_until=CURRENT_TIMESTAMP - INTERVAL '1 minute' WHERE id=?",
                first.id());
        assertThat(taskStore.claim(
                        FulfillmentExportWecomService.TASK_TYPE, "lease-test-owner-2", Duration.ofMinutes(30)))
                .isPresent();
        // 清理：防止干扰同容器的其他用例（任务已无租约，直接终态收口）
        taskStore.succeed(first.id(), "lease-test-owner-2");
    }

    // ------------------------------------------------------------------
    // Worker：失败/未知/崩溃语义
    // ------------------------------------------------------------------

    @Test
    void missingGroupChatDoesNotUploadAndFailsAfterExactlyTwoAttemptsWithAlert() throws Exception {
        String exportId = generateThirdPartyExport("FX-PIPELINE-NOCHAT-001");
        jdbc.update(
                "UPDATE app.fulfillment_providers SET config=config-'wecomGroupChatId'::text "
                        + "WHERE provider_code='TP'");

        claimAndRun(exportId, "INITIAL", 1); // 第 1 次尝试：安全失败 → 退避重排
        assertThat(wecom.uploadedFilenames).isEmpty();
        assertThat(deliveryRow(exportId, "INITIAL", 1).get("status")).isEqualTo("PENDING");
        assertThat(taskFor("wecom-export-initial:" + exportId)).containsEntry("status", "PENDING");

        runDueTask(exportId, "INITIAL", 1); // 第 2 次尝试：总尝试 2 次 → 终态告警
        Map<String, Object> delivery = deliveryRow(exportId, "INITIAL", 1);
        assertThat(delivery.get("status")).isEqualTo("FAILED");
        assertThat(delivery.get("attempts")).isEqualTo(2);
        assertThat(delivery.get("error_code")).isEqualTo("WECOM_GROUP_CHAT_MISSING");
        assertThat(delivery.get("error_message").toString()).contains("请在履约方配置登记企微群");
        assertThat(stateRow(exportId).get("status")).isEqualTo("FAILED");
        assertThat(wecom.uploadedFilenames).isEmpty();

        Map<String, Object> alert = openAlert(exportId);
        assertThat(alert.get("severity")).isEqualTo("RED");
        assertThat(alert.get("fulfillment_id")).isNotNull();
        assertThat(alert.get("detail").toString())
                .contains("export_id")
                .contains(exportId)
                .contains("WECOM_GROUP_CHAT_MISSING")
                .contains("attempts")
                .doesNotContain("wrJgVnTQAAD")
                .doesNotContain("config");
        // 终态后不再有任务重跑
        assertThat(taskFor("wecom-export-initial:" + exportId)).containsEntry("status", "SUCCEEDED");
    }

    @Test
    void retryableUploadFailureRetriesOnceThenFailsTerminalWithoutSending() throws Exception {
        String exportId = generateThirdPartyExport("FX-PIPELINE-RETRY-001");
        wecom.uploadResult = new WecomUploadResult(
                WecomUploadStatus.FAILED, null, null, null, null, null, null,
                "INIT", null, "OUTBOUND_BACKPRESSURE", true);

        claimAndRun(exportId, "INITIAL", 1);
        assertThat(deliveryRow(exportId, "INITIAL", 1).get("status")).isEqualTo("PENDING");
        assertThat(wecom.uploadedFilenames).hasSize(1);
        assertThat(wecom.sentMessages).isEmpty();

        runDueTask(exportId, "INITIAL", 1);
        Map<String, Object> delivery = deliveryRow(exportId, "INITIAL", 1);
        assertThat(delivery.get("status")).isEqualTo("FAILED");
        assertThat(delivery.get("attempts")).isEqualTo(2);
        assertThat(stateRow(exportId).get("status")).isEqualTo("FAILED");
        assertThat(wecom.uploadedFilenames).hasSize(2); // 总尝试正好 2 次
        assertThat(wecom.sentMessages).isEmpty();
        assertThat(openAlert(exportId).get("detail").toString()).contains("OUTBOUND_BACKPRESSURE");
    }

    @Test
    void retryableFailureThenSuccessSendsExactlyOnceWithNoDuplicateDelivery() throws Exception {
        String exportId = generateThirdPartyExport("FX-PIPELINE-RECOVER-001");
        wecom.uploadResult = new WecomUploadResult(
                WecomUploadStatus.FAILED, null, null, null, null, null, null,
                "INIT", null, "OUTBOUND_BACKPRESSURE", true);

        claimAndRun(exportId, "INITIAL", 1);
        wecom.uploadResult = uploadSuccess("MEDIA-RECOVER-1");
        runDueTask(exportId, "INITIAL", 1);

        assertThat(deliveryRow(exportId, "INITIAL", 1).get("status")).isEqualTo("SENT");
        assertThat(stateRow(exportId).get("status")).isEqualTo("ACTIVE");
        assertThat(wecom.uploadedFilenames).hasSize(2);
        assertThat(wecom.sentMessages).hasSize(1); // 只发送一次
        assertThat(deliveryCount(exportId, "INITIAL")).isEqualTo(1); // 无重复 delivery
    }

    @Test
    void uploadUnknownIsNotRetriedAndBecomesUnknownWithAlert() throws Exception {
        String exportId = generateThirdPartyExport("FX-PIPELINE-UPLOAD-UNKNOWN-001");
        wecom.uploadResult = new WecomUploadResult(
                WecomUploadStatus.UNKNOWN, null, null, null, null,
                "upload-session-1", "req-upload-1", "FINISH", null, "FINISH_ACK_UNKNOWN", false);

        claimAndRun(exportId, "INITIAL", 1);
        // 终态后任务收口，不再重试
        assertThat(taskFor("wecom-export-initial:" + exportId)).containsEntry("status", "SUCCEEDED");

        Map<String, Object> delivery = deliveryRow(exportId, "INITIAL", 1);
        assertThat(delivery.get("status")).isEqualTo("UNKNOWN");
        assertThat(delivery.get("error_code")).isEqualTo("FINISH_ACK_UNKNOWN");
        assertThat(stateRow(exportId).get("status")).isEqualTo("UNKNOWN");
        assertThat(wecom.uploadedFilenames).hasSize(1); // UNKNOWN 不重试
        assertThat(wecom.sentMessages).isEmpty();
        assertThat(openAlert(exportId).get("detail").toString()).contains("FINISH_ACK_UNKNOWN");
    }

    @Test
    void sendTimeoutIsUnknownAndNeverBlindlyResent() throws Exception {
        String exportId = generateThirdPartyExport("FX-PIPELINE-TIMEOUT-001");
        wecom.sendResult = new WecomSendResult(
                WecomSendStatus.TIMEOUT, "send-req-timeout-1", null, null, "ACK_TIMEOUT", false);

        claimAndRun(exportId, "INITIAL", 1);
        assertThat(taskFor("wecom-export-initial:" + exportId)).containsEntry("status", "SUCCEEDED");

        Map<String, Object> delivery = deliveryRow(exportId, "INITIAL", 1);
        assertThat(delivery.get("status")).isEqualTo("UNKNOWN");
        assertThat(delivery.get("error_code")).isEqualTo("ACK_TIMEOUT");
        assertThat(stateRow(exportId).get("status")).isEqualTo("UNKNOWN");
        // 发送已提交后的不确定结局：只发一次，绝不盲重发
        assertThat(wecom.sentMessages).hasSize(1);
        assertThat(openAlert(exportId).get("detail").toString()).contains("ACK_TIMEOUT");
    }

    @Test
    void stuckSendingAfterCrashBecomesUnknownWithAlertAndNeverResends() throws Exception {
        String exportId = generateThirdPartyExport("FX-PIPELINE-CRASH-001");

        // 第 1 次领取（模拟 worker 崩溃前已 CAS 到 SENDING；外部发送结局未知）
        var firstClaim = taskStore.claim(
                        FulfillmentExportWecomService.TASK_TYPE, "pipeline-test", Duration.ofSeconds(30))
                .orElseThrow(() -> new AssertionError("expected claimable task"));
        assertThat(firstClaim.payloadRef()).isEqualTo("export:" + exportId + ":INITIAL:1");
        jdbc.update(
                "UPDATE app.fulfillment_export_wecom_deliveries SET status='SENDING' WHERE export_id=?",
                Long.parseLong(exportId));
        // 租约过期后重启：任务可被重新领取
        jdbc.update(
                "UPDATE app.async_tasks SET lease_until=CURRENT_TIMESTAMP - INTERVAL '1 minute' WHERE id=?",
                firstClaim.id());
        wecom.sentMessages.clear();
        wecom.uploadedFilenames.clear();

        runDueTask(exportId, "INITIAL", 1); // 重启后重新领取：不得盲重发

        Map<String, Object> delivery = deliveryRow(exportId, "INITIAL", 1);
        assertThat(delivery.get("status")).isEqualTo("UNKNOWN");
        assertThat(delivery.get("error_code")).isEqualTo("DELIVERY_STUCK_IN_SENDING");
        assertThat(stateRow(exportId).get("status")).isEqualTo("UNKNOWN");
        assertThat(wecom.uploadedFilenames).isEmpty();
        assertThat(wecom.sentMessages).isEmpty();
        assertThat(openAlert(exportId).get("detail").toString()).contains("DELIVERY_STUCK_IN_SENDING");
        assertThat(taskFor("wecom-export-initial:" + exportId)).containsEntry("status", "SUCCEEDED");
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    private String generateThirdPartyExport(String orderRef) throws Exception {
        Map<String, Object> imported = importAndConfirm(orderRef);
        return ((List<?>) imported.get("generated_fulfillment_export_ids")).getFirst().toString();
    }

    private Map<String, Object> importAndConfirm(String orderRef) throws Exception {
        ResponseEntity<Map> uploaded = uploadRaw(
                "pipeline.csv", feixiangSingleCsv(orderRef), "source-import-" + orderRef.toLowerCase());
        assertThat(uploaded.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> batch = uploaded.getBody();
        ResponseEntity<Map> confirmed = confirmBatch(
                batch.get("id").toString(), "confirm-" + orderRef.toLowerCase());
        assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.OK);
        return confirmed.getBody();
    }

    private Map<String, Object> importAndConfirmJd(String orderRef) throws Exception {
        jdbc.update(
                """
                INSERT INTO app.source_channel_skus
                    (source_channel, source_sku_ref, source_product_name, source_specification,
                     quantity_multiplier, sku_id, active)
                SELECT 'FEIXIANG', 'FX-PRODUCT-JD-001', '子牧羊小腿', '500g/盒', 1.000, sku_id, true
                FROM app.source_channel_skus WHERE source_channel='WECOM' AND source_sku_ref='WECOM-SKU-JD-001'
                ON CONFLICT (source_channel, source_sku_ref) DO NOTHING
                """);
        ResponseEntity<Map> uploaded = uploadRaw(
                "pipeline-jd.csv",
                feixiangSingleCsv(orderRef, "FX-PRODUCT-JD-001", "1"),
                "source-import-" + orderRef.toLowerCase());
        assertThat(uploaded.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return confirmBatch(
                uploaded.getBody().get("id").toString(), "confirm-" + orderRef.toLowerCase()).getBody();
    }

    /** 领取并执行指定 delivery 的任务（第 N 次尝试由 claim 计数决定）。 */
    private void claimAndRun(String exportId, String kind, int sequence) {
        String expected = "export:" + exportId + ":" + kind + ":" + sequence;
        var task = claimUntil(expected);
        runner.execute(task);
    }

    /** 按 payload 领取目标任务；其他用例遗留的未完成任务直接收口，避免串扰。 */
    private AsyncTaskStore.AsyncTask claimUntil(String expectedPayloadRef) {
        for (int i = 0; i < 10; i++) {
            var claimed = taskStore.claim(
                            FulfillmentExportWecomService.TASK_TYPE, "pipeline-test", Duration.ofSeconds(30))
                    .orElseThrow(() -> new AssertionError("expected claimable task " + expectedPayloadRef));
            if (expectedPayloadRef.equals(claimed.payloadRef())) {
                return claimed;
            }
            taskStore.succeed(claimed.id(), claimed.leaseOwner());
        }
        throw new AssertionError("task not claimable: " + expectedPayloadRef);
    }

    /** 把已退避的任务置为到期并重新领取执行（模拟第 2 次尝试）。 */
    private void runDueTask(String exportId, String kind, int sequence) {
        jdbc.update(
                "UPDATE app.async_tasks SET next_run_at=CURRENT_TIMESTAMP WHERE idempotency_key=?",
                kindKey(exportId, kind, sequence));
        claimAndRun(exportId, kind, sequence);
    }

    private String kindKey(String exportId, String kind, int sequence) {
        return FulfillmentExportWecomStore.INITIAL.equals(kind)
                ? "wecom-export-initial:" + exportId
                : "wecom-export-reminder:" + exportId + ":" + sequence;
    }

    private Map<String, Object> stateRow(String exportId) {
        return jdbc.queryForMap(
                "SELECT * FROM app.fulfillment_export_wecom_states WHERE export_id=?",
                Long.parseLong(exportId));
    }

    private int stateRowCount(String exportId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM app.fulfillment_export_wecom_states WHERE export_id=?",
                Integer.class, Long.parseLong(exportId));
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

    private Map<String, Object> taskFor(String idempotencyKey) {
        return jdbc.queryForMap(
                "SELECT id, task_type, status, attempts, max_attempts, idempotency_key "
                        + "FROM app.async_tasks WHERE idempotency_key=?",
                idempotencyKey);
    }

    private int taskCount(String idempotencyKeyPrefix) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM app.async_tasks WHERE idempotency_key LIKE ?",
                Integer.class, idempotencyKeyPrefix + "%");
    }

    private Map<String, Object> openAlert(String exportId) {
        List<Map<String, Object>> alerts = jdbc.query(
                "SELECT * FROM app.operational_alerts WHERE alert_type='FULFILLMENT_EXPORT_WECOM' "
                        + "AND status='OPEN' ORDER BY id DESC",
                (rs, row) -> {
                    Map<String, Object> alert = new LinkedHashMap<>();
                    alert.put("id", rs.getLong("id"));
                    alert.put("severity", rs.getString("severity"));
                    alert.put("fulfillment_id", rs.getObject("fulfillment_id"));
                    alert.put("detail", rs.getString("detail"));
                    return alert;
                });
        assertThat(alerts).as("该导出应存在 OPEN 告警").isNotEmpty();
        return alerts.getFirst();
    }

    private String exportBatchNo(String exportId) {
        return jdbc.queryForObject(
                "SELECT export_batch_no FROM app.fulfillment_exports WHERE id=?",
                String.class, Long.parseLong(exportId));
    }

    private String firstFulfillmentId(String exportId) {
        return jdbc.queryForObject(
                "SELECT MIN(fulfillment_id) FROM app.fulfillment_export_items WHERE fulfillment_export_id=?",
                String.class, Long.parseLong(exportId));
    }

    private byte[] downloadExport(String exportId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Operator", "pipeline-test");
        ResponseEntity<byte[]> response = http.exchange(
                "/api/v1/fulfillment-exports/" + exportId + "/file",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                byte[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private ResponseEntity<Map> uploadTracking(String exportId, byte[] returned, String idempotencyKey) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(returned) {
            @Override public String getFilename() { return "tracking.xlsx"; }
        });
        body.add("import_mode", "NEW");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.set("Idempotency-Key", idempotencyKey);
        headers.set("X-Operator", "pipeline-test");
        return http.exchange(
                "/api/v1/fulfillment-exports/" + exportId + "/tracking-imports",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class);
    }

    private byte[] fillThirdPartyTracking(byte[] instruction, String result, String quantity, String trackingNumber)
            throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(instruction));
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var row = workbook.getSheetAt(0).getRow(1);
            row.getCell(18).setCellValue(result);
            row.getCell(19).setCellValue(quantity);
            row.getCell(20).setCellValue("京东物流");
            row.getCell(21).setCellValue(trackingNumber);
            row.getCell(22).setCellValue("2026-08-12 12:00:00");
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
        headers.set("X-Operator", "pipeline-test");
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
        headers.set("X-Operator", "pipeline-test");
        headers.set("X-Request-Id", "req-" + idempotencyKey);
        return http.exchange(
                "/api/v1/import-batches/" + batchId + "/confirm",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(), headers),
                Map.class);
    }

    private byte[] feixiangSingleCsv(String orderRef) {
        return feixiangSingleCsv(orderRef, "FX-PRODUCT-001", "1.500");
    }

    private byte[] feixiangSingleCsv(String orderRef, String productRef, String quantity) {
        String header = String.join(",", List.of(
                "订单号", "会员名称", "商品名称", "商品ID", "订单商品ID", "可发货数量",
                "收货人姓名", "收货人手机号", "收货人地址", "下单时间", "物流状态", "物流公司", "物流单号"));
        String row = orderRef + ",FX-MEMBER-001,子牧羊小腿," + productRef + "," + orderRef
                + "-LINE," + quantity + ",张三,13800000000,上海市浦东新区测试路1号,2026-08-11 10:00:00,,,\r\n";
        return ("\uFEFF" + header + "\r\n" + row).getBytes(StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> get(String path) {
        ResponseEntity<Map> response = http.getForEntity(path, Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private HttpHeaders writeHeaders(String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", idempotencyKey);
        headers.set("X-Request-Id", "req-" + idempotencyKey);
        headers.set("X-Operator", "pipeline-test");
        return headers;
    }

    private static WecomUploadResult uploadSuccess(String mediaId) {
        return new WecomUploadResult(
                WecomUploadStatus.SUCCESS, mediaId, "file",
                Instant.now().minusSeconds(10), Instant.now().minusSeconds(5),
                "upload-session-ok", "req-upload-ok", "FINISH", null, null, false);
    }

    private static WecomSendResult sendSuccess(String requestId) {
        return new WecomSendResult(WecomSendStatus.SUCCESS, requestId, Instant.now(), null, null, false);
    }

    private static Instant instant(Object value) {
        return ((java.sql.Timestamp) value).toInstant();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest
                    .getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
