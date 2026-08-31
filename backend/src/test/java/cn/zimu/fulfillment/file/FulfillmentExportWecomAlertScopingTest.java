package cn.zimu.fulfillment.file;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zimu.fulfillment.connector.wecom.WecomMediaType;
import cn.zimu.fulfillment.connector.wecom.WecomOutboundMessage;
import cn.zimu.fulfillment.connector.wecom.WecomOutboundTransport;
import cn.zimu.fulfillment.connector.wecom.WecomSendResult;
import cn.zimu.fulfillment.connector.wecom.WecomSendStatus;
import cn.zimu.fulfillment.connector.wecom.WecomUploadResult;
import cn.zimu.fulfillment.connector.wecom.WecomUploadStatus;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.idempotency.IdempotencyService;
import cn.zimu.fulfillment.message.AsyncTaskStore;
import cn.zimu.fulfillment.order.CreateOperationalAlertCommand;
import cn.zimu.fulfillment.order.OperationalAlertService;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
 * Issue #84 告警隔离与持久收口：续发导出共享 fulfillment（shipment 各自独立）时各自持有
 * 独立活动告警（subject = 各自真实 shipment，dedup/关闭按 shipment + detail.export_id
 * 隔离，不跨导出误关）；重发 ack 只关闭同导出告警；告警创建失败使任务退避重试（第 3 次
 * 领取只做告警收口），持续失败则任务 FAILED 可见，不静默吞掉。
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "app.message-worker.enabled=false",
            "app.wecom-export-worker.enabled=false",
            "app.wecom-reminder.enabled=false",
            "app.wecom-export-worker.backoff-seconds=1",
            "app.file-store.root=${java.io.tmpdir}/zimu-wecom-alert-scope-test"
        })
@Import({FulfillmentExportWecomAlertScopingTest.ControlledWecomTransportConfig.class,
        FulfillmentExportWecomAlertScopingTest.AlertServiceSpyConfig.class})
class FulfillmentExportWecomAlertScopingTest {

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

    @TestConfiguration
    static class AlertServiceSpyConfig {
        /** 可注入失败的告警服务引用（原始 double；注入侧会被事务 CGLIB 代理包裹）。 */
        static volatile FailingAlertService failing;

        @Bean
        @Primary
        OperationalAlertService alertService(
                OperationalAlertService real,
                org.springframework.jdbc.core.JdbcTemplate jdbc,
                com.fasterxml.jackson.databind.ObjectMapper objectMapper,
                IdempotencyService idempotency,
                AuditLogService audits) {
            failing = new FailingAlertService(real, jdbc, objectMapper, idempotency, audits);
            return failing;
        }
    }

    /**
     * 可注入失败的告警服务（不用 Mockito spy：事务 CGLIB 代理会包裹 mock，reset/stub 落在
     * 代理上不可靠）。createSystem / resolveWecomExportAlerts 可被注入失败；其余方法委托
     * 真实实现（真实代理的 @Transactional 会加入调用方事务）。
     */
    static class FailingAlertService extends OperationalAlertService {
        private final OperationalAlertService delegate;
        private volatile RuntimeException failure;
        private volatile RuntimeException resolveFailure;

        FailingAlertService(
                OperationalAlertService delegate,
                org.springframework.jdbc.core.JdbcTemplate jdbc,
                com.fasterxml.jackson.databind.ObjectMapper objectMapper,
                IdempotencyService idempotency,
                AuditLogService audits) {
            super(jdbc, objectMapper, idempotency, audits);
            this.delegate = delegate;
        }

        @Override
        public long createSystem(CreateOperationalAlertCommand command) {
            RuntimeException current = failure;
            if (current != null) {
                throw current;
            }
            return delegate.createSystem(command);
        }

        @Override
        public void resolveWecomExportAlerts(Long shipmentId, long exportId, int maxGeneration, String reason) {
            RuntimeException current = resolveFailure;
            if (current != null) {
                throw current;
            }
            delegate.resolveWecomExportAlerts(shipmentId, exportId, maxGeneration, reason);
        }

        void failWith(RuntimeException ex) {
            this.failure = ex;
        }

        void failResolveWith(RuntimeException ex) {
            this.resolveFailure = ex;
        }

        void restore() {
            this.failure = null;
            this.resolveFailure = null;
        }
    }

    static class ControlledWecomTransport implements WecomOutboundTransport {
        final List<WecomOutboundMessage> sentMessages = new CopyOnWriteArrayList<>();
        final List<String> uploadedFilenames = new CopyOnWriteArrayList<>();
        volatile WecomSendResult sendResult =
                new WecomSendResult(WecomSendStatus.SUCCESS, "alert-req-1", Instant.now(), null, null, false);

        @Override
        public WecomSendResult send(WecomOutboundMessage message) {
            sentMessages.add(message);
            return sendResult;
        }

        @Override
        public WecomUploadResult upload(Path file, String filename, WecomMediaType type) {
            uploadedFilenames.add(filename);
            return new WecomUploadResult(
                    WecomUploadStatus.SUCCESS, "MEDIA-ALERT-1", "file",
                    Instant.now().minusSeconds(10), Instant.now().minusSeconds(5),
                    "upload-session-ok", "req-upload-ok", "FINISH", null, null, false);
        }
    }

    @BeforeEach
    void reset() {
        wecom.sentMessages.clear();
        wecom.uploadedFilenames.clear();
        wecom.sendResult = new WecomSendResult(
                WecomSendStatus.SUCCESS, "alert-req-1", Instant.now(), null, null, false);
        AlertServiceSpyConfig.failing.restore();
        jdbc.update(
                """
                UPDATE app.fulfillment_providers
                SET config = config || '{"wecomGroupChatId":"wrJgVnTQAAD-AL-001"}'::jsonb
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

    @Test
    void continuationExportFailureKeepsItsOwnAlertWithoutClosingTheOriginalExportSAlert() throws Exception {
        String exportA = failExport("FX-ALERT-A-001");
        Long sharedFulfillment = firstFulfillmentId(exportA);
        Long sharedShipment = firstShipmentId(exportA);

        // 部分回传后创建续发导出：共享 fulfillment，shipment 各自独立（续发新开 shipment）
        String exportB = continuationExportOf(exportA, "FX-ALERT-B-001", "alert-continuation-001");
        assertThat(firstFulfillmentId(exportB)).isEqualTo(sharedFulfillment); // 共享 fulfillment
        assertThat(firstShipmentId(exportB)).isNotEqualTo(sharedShipment);

        // 两个导出都确定性失败：各自持有一条 OPEN 告警，绝不互相误关（旧实现按 fulfillment
        // supersede 会把 A 的告警误关）
        failExport("FX-ALERT-B-001", exportB);
        assertThat(openAlertCount(exportA)).isEqualTo(1);
        assertThat(openAlertCount(exportB)).isEqualTo(1);
        assertThat(openAlertFor(exportA)).containsEntry("fulfillment_id", sharedFulfillment);
        assertThat(openAlertFor(exportB)).containsEntry("fulfillment_id", sharedFulfillment);
        // 每条告警 subject = 各自导出的真实 shipment（dedup/关闭按 shipment + detail.export_id 隔离）
        assertThat(jdbc.queryForObject(
                "SELECT shipment_id FROM app.operational_alerts WHERE detail->>'export_id'=? AND status='OPEN'",
                Long.class, exportA)).isEqualTo(sharedShipment);
        assertThat(jdbc.queryForObject(
                "SELECT shipment_id FROM app.operational_alerts WHERE detail->>'export_id'=? AND status='OPEN'",
                Long.class, exportB)).isEqualTo(firstShipmentId(exportB));
    }

    @Test
    void resendAckResolvesOnlyTheSameExportSAlertsNotOtherSharedExports() throws Exception {
        // A 失败出告警；部分回传创建续发导出 B（共享 fulfillment；A 的 state 被收齐判定
        // 置 COMPLETED，但 A 的 OPEN 告警保留）；B 再失败出告警
        String exportA = failExport("FX-ALERT-RESEND-A-001");
        String exportB = continuationExportOf(exportA, "FX-ALERT-RESEND-B-001", "alert-continuation-rs-001");
        assertThat(stateRow(exportA).get("status")).isEqualTo("COMPLETED");
        failExport("FX-ALERT-RESEND-B-001", exportB);
        assertThat(firstFulfillmentId(exportA)).isEqualTo(firstFulfillmentId(exportB)); // 共享 fulfillment
        assertThat(firstShipmentId(exportA)).isNotEqualTo(firstShipmentId(exportB)); // shipment 各自独立
        assertThat(openAlertCount(exportA)).isEqualTo(1);
        assertThat(openAlertCount(exportB)).isEqualTo(1);

        // 成功人工重发 B：只关闭 B 的告警（shipment + detail.export_id），A 的保持 OPEN
        // （旧实现按 fulfillment 关闭会把 A 的告警一并误关）
        ResponseEntity<Map> resend = http.exchange(
                "/api/v1/fulfillment-exports/" + exportB + "/wecom-resend",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "expected_version", versionOf(exportB),
                        "reason", "群内未收到文件"),
                        writeHeaders("alert-resend-b-001")),
                Map.class);
        assertThat(resend.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        wecom.sendResult = new WecomSendResult(
                WecomSendStatus.SUCCESS, "alert-ack-b-2", Instant.now().plusSeconds(60), null, null, false);
        claimAndRun(exportB, "INITIAL", 2);

        assertThat(openAlertCount(exportB)).isZero();
        assertThat(resolvedAlertCount(exportB)).isEqualTo(1);
        assertThat(openAlertCount(exportA)).isEqualTo(1);
        assertThat(resolvedAlertCount(exportA)).isZero();
    }

    @Test
    void alertResolutionFailureAfterSentKeepsTaskRecoverableAndDoesNotResendOrCrossClose() throws Exception {
        // A 失败出告警；续发 B（共享 fulfillment、独立 shipment）也失败出告警
        String exportA = failExport("FX-ALERT-RECOVER-A-001");
        String exportB = continuationExportOf(exportA, "FX-ALERT-RECOVER-B-001", "alert-recover-cont-001");
        failExport("FX-ALERT-RECOVER-B-001", exportB);
        assertThat(openAlertCount(exportA)).isEqualTo(1);
        assertThat(openAlertCount(exportB)).isEqualTo(1);

        // 成功人工重发 B，但「关闭该导出告警」失败（两阶段之间 crash/异常）：
        // B delivery 已 SENT、B 任务未 SUCCEEDED、B 告警保持 OPEN、A 告警不受影响
        ResponseEntity<Map> resend = http.exchange(
                "/api/v1/fulfillment-exports/" + exportB + "/wecom-resend",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "expected_version", versionOf(exportB),
                        "reason", "群内未收到文件"),
                        writeHeaders("alert-recover-resend-001")),
                Map.class);
        assertThat(resend.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        wecom.sendResult = new WecomSendResult(
                WecomSendStatus.SUCCESS, "alert-ack-recover-b-2", Instant.now().plusSeconds(60), null, null, false);
        AlertServiceSpyConfig.failing.failResolveWith(new IllegalStateException("alert resolve db down"));
        claimAndRun(exportB, "INITIAL", 2);

        assertThat(deliveryRow(exportB, "INITIAL", 2).get("status")).isEqualTo("SENT");
        assertThat(taskForPayload(exportB, "INITIAL", 2))
                .containsEntry("status", "PENDING")
                .containsEntry("attempts", 1);
        assertThat(openAlertCount(exportB)).isEqualTo(1); // 告警保持 OPEN（任务可重试，未吞掉）
        assertThat(openAlertCount(exportA)).isEqualTo(1);

        // 恢复后下一次 worker 领取：见 SENT → 零 upload/send，只关 B 告警 + B 任务 SUCCEEDED；
        // A 告警保持 OPEN（正确隔离：绝不跨导出误关）
        AlertServiceSpyConfig.failing.restore();
        wecom.sentMessages.clear();
        wecom.uploadedFilenames.clear();
        runDueResendTask(exportB, "INITIAL", 2);
        assertThat(wecom.sentMessages).isEmpty(); // 绝不重新 send
        assertThat(wecom.uploadedFilenames).isEmpty(); // 绝不重新 upload
        assertThat(openAlertCount(exportB)).isZero();
        assertThat(resolvedAlertCount(exportB)).isEqualTo(1);
        assertThat(openAlertCount(exportA)).isEqualTo(1);
        assertThat(resolvedAlertCount(exportA)).isZero();
        assertThat(taskForPayload(exportB, "INITIAL", 2))
                .containsEntry("status", "SUCCEEDED")
                .containsEntry("attempts", 2);
    }

    @Test
    void alertCreationFailureBacksOffAndFinalizesOnTheThirdClaimThenSucceeds() throws Exception {
        String exportId = generateThirdPartyExport("FX-ALERT-FINALIZE-001");
        wecom.sendResult = new WecomSendResult(
                WecomSendStatus.FAILED, null, null, null, "CONNECTION_NOT_READY", true);
        AlertServiceSpyConfig.failing.failWith(new IllegalStateException("alert db down"));

        // 第 1 次：安全失败 → 退避（无告警介入）
        claimAndRun(exportId, "INITIAL", 1);
        assertThat(taskFor(exportId)).containsEntry("status", "PENDING").containsEntry("attempts", 1);

        // 第 2 次：终态 FAILED，但告警创建失败 → 任务退避（attempts=2 < 3，不静默吞掉）
        runDueTask(exportId, "INITIAL", 1);
        assertThat(taskFor(exportId)).containsEntry("status", "PENDING").containsEntry("attempts", 2);
        assertThat(deliveryRow(exportId, "INITIAL", 1).get("status")).isEqualTo("FAILED");
        assertThat(openAlertCount(exportId)).isZero();

        // 告警恢复后第 3 次领取：只做幂等告警收口（绝不外部发送），任务 SUCCEEDED
        AlertServiceSpyConfig.failing.restore();
        wecom.sentMessages.clear();
        runDueTask(exportId, "INITIAL", 1);
        assertThat(wecom.sentMessages).isEmpty(); // 第 3 次绝不再外部发送
        assertThat(taskFor(exportId)).containsEntry("status", "SUCCEEDED").containsEntry("attempts", 3);
        assertThat(openAlertCount(exportId)).isEqualTo(1);
        assertThat(deliveryRow(exportId, "INITIAL", 1).get("status")).isEqualTo("FAILED");
    }

    @Test
    void persistentAlertCreationFailureMakesTheTaskFailedVisibly() throws Exception {
        String exportId = generateThirdPartyExport("FX-ALERT-PERSISTENT-001");
        wecom.sendResult = new WecomSendResult(
                WecomSendStatus.FAILED, null, null, null, "CONNECTION_NOT_READY", true);
        AlertServiceSpyConfig.failing.failWith(new IllegalStateException("alert db down"));

        claimAndRun(exportId, "INITIAL", 1); // 第 1 次：安全失败 → 退避
        runDueTask(exportId, "INITIAL", 1); // 第 2 次：终态 + 告警失败 → 退避
        runDueTask(exportId, "INITIAL", 1); // 第 3 次（告警收口）仍失败 → 任务 FAILED 可见
        assertThat(taskFor(exportId))
                .containsEntry("status", "FAILED")
                .containsEntry("attempts", 3)
                .containsEntry("last_error", FulfillmentExportWecomDeliveryRunner.ALERT_FINALIZE_ERROR);
        assertThat(deliveryRow(exportId, "INITIAL", 1).get("status")).isEqualTo("FAILED");
        assertThat(openAlertCount(exportId)).isZero(); // 告警确实未能创建（事实可见，可人工补建）
    }

    @Test
    void oldPhase2ResumeAfterNewerResendFailureKeepsNewerRedAlertOpen() throws Exception {
        // 1) 初次发送成功（phase1 已落 SENT），但 phase2（关告警）崩溃：delivery SENT、state ACTIVE、
        //    任务未收口（退避 PENDING）。旧代际（seq1）成功绝不能关闭新代际（seq2）失败的红告警。
        String exportId = generateThirdPartyExport("FX-ALERT-PHASE2-CRASH-001");
        AlertServiceSpyConfig.failing.failResolveWith(new IllegalStateException("alert resolve db down"));
        claimAndRun(exportId, "INITIAL", 1);
        assertThat(deliveryRow(exportId, "INITIAL", 1).get("status")).isEqualTo("SENT");
        assertThat(stateRow(exportId).get("status")).isEqualTo("ACTIVE");
        assertThat(taskFor(exportId)).containsEntry("status", "PENDING").containsEntry("attempts", 1);
        AlertServiceSpyConfig.failing.restore();

        // 2) 人工重发（新代际 seq2）失败 → 新代际 RED 告警
        ResponseEntity<Map> resend = resend(exportId, versionOf(exportId), "群内未收到文件", "alert-phase2-resend-001");
        assertThat(resend.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        wecom.sendResult = new WecomSendResult(
                WecomSendStatus.FAILED, null, null, 60020, "invalid chatid", false);
        claimAndRun(exportId, "INITIAL", 2);
        assertThat(deliveryRow(exportId, "INITIAL", 2).get("status")).isEqualTo("UNKNOWN");
        assertThat(openAlertCount(exportId)).isEqualTo(1); // 新代际 RED

        // 3) 旧任务（seq1）重新领取走 phase2 收口：只关 <= 代际 1 的告警，新代际 RED 保持 OPEN
        runDueResendTask(exportId, "INITIAL", 1);
        assertThat(deliveryRow(exportId, "INITIAL", 1).get("status")).isEqualTo("SENT");
        assertThat(openAlertCount(exportId)).isEqualTo(1); // 新代际 RED 未被旧代际成功误关
        assertThat(resolvedAlertCount(exportId)).isZero();
        assertThat(openAlertFor(exportId)).containsEntry("shipment_id", firstShipmentId(exportId));
    }

    @Test
    void oldGenerationAlertRetryKeepsNewerGenerationRedAlertOpen() throws Exception {
        String exportId = generateThirdPartyExport("FX-ALERT-OLD-RETRY-001");

        // gen1 已终态 UNKNOWN，但告警落库失败，任务退避等待幂等补建。
        wecom.sendResult = new WecomSendResult(
                WecomSendStatus.FAILED, null, null, 60020, "invalid chatid", false);
        AlertServiceSpyConfig.failing.failWith(new IllegalStateException("alert db down"));
        claimAndRun(exportId, "INITIAL", 1);
        assertThat(deliveryRow(exportId, "INITIAL", 1).get("status")).isEqualTo("UNKNOWN");
        assertThat(taskForPayload(exportId, "INITIAL", 1))
                .containsEntry("status", "PENDING")
                .containsEntry("attempts", 1);
        assertThat(openAlertCount(exportId)).isZero();

        // gen2 人工重发也失败，但告警服务已恢复，因此新代际 RED 告警成功落库。
        AlertServiceSpyConfig.failing.restore();
        ResponseEntity<Map> resend = resend(
                exportId, versionOf(exportId), "群内未收到文件", "alert-old-generation-resend-001");
        assertThat(resend.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        claimAndRun(exportId, "INITIAL", 2);
        Map<String, Object> newerDelivery = deliveryRow(exportId, "INITIAL", 2);
        assertThat(newerDelivery.get("status")).isEqualTo("UNKNOWN");
        assertThat(openAlertCount(exportId)).isEqualTo(1);

        // gen1 终态任务重入只能把自身任务收口；不得关闭或替换 gen2 的活动告警。
        runDueResendTask(exportId, "INITIAL", 1);
        assertThat(taskForPayload(exportId, "INITIAL", 1)).containsEntry("status", "SUCCEEDED");
        assertThat(openAlertCount(exportId)).isEqualTo(1);
        assertThat(resolvedAlertCount(exportId)).isZero();
        assertThat(openAlertFor(exportId).get("detail").toString())
                .contains("\"delivery_id\": \"" + newerDelivery.get("id") + "\"")
                .contains("\"initial_generation\": 2");
    }

    @Test
    void concurrentOldAndNewGenerationEnsureLeavesNewerAlertActive() throws Exception {
        String exportId = generateThirdPartyExport("FX-ALERT-CONCURRENT-GENERATION-001");
        long oldDeliveryId = ((Number) deliveryRow(exportId, "INITIAL", 1).get("id")).longValue();
        jdbc.update(
                "UPDATE app.fulfillment_export_wecom_deliveries "
                        + "SET status='UNKNOWN', stage='FINALIZED', attempts=2, "
                        + "error_code='OLD', error_message='old generation' WHERE id=?",
                oldDeliveryId);
        long newDeliveryId = jdbc.queryForObject(
                """
                INSERT INTO app.fulfillment_export_wecom_deliveries
                    (export_id, kind, sequence, initial_generation, status, attempts, max_attempts,
                     stage, error_code, error_message)
                VALUES (?, 'INITIAL', 2, 2, 'UNKNOWN', 2, 2, 'FINALIZED', 'NEW', 'new generation')
                RETURNING id
                """,
                Long.class,
                Long.parseLong(exportId));
        Long shipmentId = firstShipmentId(exportId);
        CreateOperationalAlertCommand oldAlert = generationAlert(exportId, oldDeliveryId, 1, shipmentId, "old");
        CreateOperationalAlertCommand newAlert = generationAlert(exportId, newDeliveryId, 2, shipmentId, "new");

        // AFTER INSERT 延迟旧代际事务：旧唯一键已写但未提交时启动新代际 ensure。没有 export
        // 串行锁时，新事务的 UPDATE 看不见旧行，随后唯一键冲突只会返回旧告警；有锁时新事务
        // 先等待旧事务提交，再 resolve gen1 并把 gen2 置为唯一活动告警。
        jdbc.execute(
                """
                CREATE OR REPLACE FUNCTION app.test_delay_old_wecom_alert() RETURNS trigger
                LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.detail->>'delivery_id' = '%d' THEN
                        PERFORM pg_sleep(5);
                    END IF;
                    RETURN NEW;
                END
                $$
                """.formatted(oldDeliveryId));
        jdbc.execute(
                """
                CREATE TRIGGER test_delay_old_wecom_alert
                AFTER INSERT ON app.operational_alerts
                FOR EACH ROW EXECUTE FUNCTION app.test_delay_old_wecom_alert()
                """);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Long> oldResult = executor.submit(() -> AlertServiceSpyConfig.failing.createSystem(oldAlert));
            awaitOldAlertTriggerSleep();
            Future<Long> newResult = executor.submit(() -> AlertServiceSpyConfig.failing.createSystem(newAlert));
            assertThat(oldResult.get(15, TimeUnit.SECONDS)).isPositive();
            assertThat(newResult.get(15, TimeUnit.SECONDS)).isPositive();
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS test_delay_old_wecom_alert ON app.operational_alerts");
            jdbc.execute("DROP FUNCTION IF EXISTS app.test_delay_old_wecom_alert()");
        }

        assertThat(openAlertCount(exportId)).isEqualTo(1);
        assertThat(openAlertFor(exportId).get("detail").toString())
                .contains("\"delivery_id\": \"" + newDeliveryId + "\"")
                .contains("\"initial_generation\": 2");
    }

    private CreateOperationalAlertCommand generationAlert(
            String exportId, long deliveryId, int generation, Long shipmentId, String label) {
        return new CreateOperationalAlertCommand(
                "FULFILLMENT_EXPORT_WECOM",
                cn.zimu.fulfillment.order.OperationalAlertSeverity.RED,
                null,
                null,
                null,
                shipmentId,
                label + " generation alert",
                Map.of(
                        "export_id", exportId,
                        "delivery_id", String.valueOf(deliveryId),
                        "initial_generation", generation));
    }

    private void awaitOldAlertTriggerSleep() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Integer sleeping = jdbc.queryForObject(
                    """
                    SELECT count(*) FROM pg_stat_activity
                    WHERE wait_event = 'PgSleep'
                      AND query LIKE '%INSERT INTO app.operational_alerts%'
                    """,
                    Integer.class);
            if (sleeping != null && sleeping > 0) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("old generation alert insert never entered deterministic trigger sleep");
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 生成第三方导出并驱动到 FAILED（确定性 send 失败，2 次外部尝试）→ 一条 OPEN 告警。 */
    private String failExport(String orderRef) throws Exception {
        String exportId = generateThirdPartyExport(orderRef);
        failExport(orderRef, exportId);
        return exportId;
    }

    /** 部分回传后创建续发导出（与原导出共享 fulfillment/shipment）。 */
    private String continuationExportOf(String exportA, String orderRef, String idempotencyKey) throws Exception {
        byte[] instruction = downloadExport(exportA);
        // 运单号唯一（trackings 有 (logistics_company_code, tracking_number) 全局唯一约束）
        byte[] partial = fillThirdPartyTracking(instruction, "PARTIAL", "1", "JDVA-CONT-" + idempotencyKey);
        assertThat(uploadTracking(exportA, partial, "alert-cont-tracking-" + idempotencyKey).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);
        Long fulfillment = firstFulfillmentId(exportA);
        long version = jdbc.queryForObject(
                "SELECT lock_version FROM app.fulfillments WHERE id=?", Long.class, fulfillment);
        ResponseEntity<Map> continuation = http.exchange(
                "/api/v1/fulfillments/" + fulfillment + "/continuation-exports",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "expected_version", version,
                        "instructed_quantity", "2",
                        "remark", "续发"),
                        writeHeaders(idempotencyKey)),
                Map.class);
        assertThat(continuation.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return continuation.getBody().get("fulfillment_export_id").toString();
    }

    private void failExport(String orderRef, String exportId) {
        wecom.sendResult = new WecomSendResult(
                WecomSendStatus.FAILED, null, null, null, "CONNECTION_NOT_READY", true);
        claimAndRun(exportId, "INITIAL", 1);
        runDueTask(exportId, "INITIAL", 1);
        assertThat(stateRow(exportId).get("status")).isEqualTo("FAILED");
    }

    private String generateThirdPartyExport(String orderRef) throws Exception {
        ResponseEntity<Map> uploaded = uploadRaw(
                "alert.csv", feixiangSingleCsv(orderRef), "source-import-" + orderRef.toLowerCase());
        assertThat(uploaded.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> batch = uploaded.getBody();
        ResponseEntity<Map> confirmed = confirmBatch(
                batch.get("id").toString(), "confirm-" + orderRef.toLowerCase());
        assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.OK);
        return ((List<?>) confirmed.getBody().get("generated_fulfillment_export_ids")).getFirst().toString();
    }

    private void claimAndRun(String exportId, String kind, int sequence) {
        String expected = "export:" + exportId + ":" + kind + ":" + sequence;
        for (int i = 0; i < 10; i++) {
            var claimed = taskStore.claim(
                            FulfillmentExportWecomService.TASK_TYPE, "alert-test", Duration.ofSeconds(30))
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

    private Map<String, Object> taskFor(String exportId) {
        return jdbc.queryForMap(
                "SELECT status, attempts, max_attempts, last_error FROM app.async_tasks "
                        + "WHERE idempotency_key=?",
                "wecom-export-initial:" + exportId);
    }

    /** 按 payload_ref 读取指定 delivery（含人工重发）的 async task 状态。 */
    private Map<String, Object> taskForPayload(String exportId, String kind, int sequence) {
        return jdbc.queryForMap(
                "SELECT status, attempts, max_attempts, last_error FROM app.async_tasks "
                        + "WHERE task_type=? AND payload_ref=?",
                FulfillmentExportWecomService.TASK_TYPE,
                FulfillmentExportWecomService.payloadRef(Long.parseLong(exportId), kind, sequence));
    }

    /** 把已退避的重发任务置为到期并重新领取执行（重发 idempotency_key 含 deliveryId，按 payload_ref 定位）。 */
    private void runDueResendTask(String exportId, String kind, int sequence) {
        jdbc.update(
                "UPDATE app.async_tasks SET next_run_at=CURRENT_TIMESTAMP WHERE task_type=? AND payload_ref=?",
                FulfillmentExportWecomService.TASK_TYPE,
                FulfillmentExportWecomService.payloadRef(Long.parseLong(exportId), kind, sequence));
        claimAndRun(exportId, kind, sequence);
    }

    private long versionOf(String exportId) {
        return jdbc.queryForObject(
                "SELECT lock_version FROM app.fulfillment_export_wecom_states WHERE export_id=?",
                Long.class, Long.parseLong(exportId));
    }

    /** 人工重发 REST 写端点（代际栅栏测试需要真实重发创建新 INITIAL delivery + task）。 */
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

    private Map<String, Object> openAlertFor(String exportId) {
        return jdbc.queryForMap(
                "SELECT fulfillment_id, shipment_id, detail::text AS detail FROM app.operational_alerts "
                        + "WHERE alert_type='FULFILLMENT_EXPORT_WECOM' AND status='OPEN' "
                        + "AND detail->>'export_id'=?",
                exportId);
    }

    private Long firstFulfillmentId(String exportId) {
        return jdbc.queryForObject(
                "SELECT MIN(fulfillment_id) FROM app.fulfillment_export_items WHERE fulfillment_export_id=?",
                Long.class, Long.parseLong(exportId));
    }

    private Long firstShipmentId(String exportId) {
        return jdbc.queryForObject(
                "SELECT MIN(shipment_id) FROM app.fulfillment_export_items WHERE fulfillment_export_id=?",
                Long.class, Long.parseLong(exportId));
    }

    private byte[] downloadExport(String exportId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Operator", "alert-test");
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
        headers.set("X-Operator", "alert-test");
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
            // 双格式：v2 人读八列优先；v1 兼容 24 列按旧列名。v2 没有「结果/实际发货数量」
            // 两列——「数量」列承载实发（03b296f9：与指令相等=全部发出，改小=部分发货），
            // 所以必须把 quantity 回写进「数量」，否则 PARTIAL 会被解析成全量发货。
            if (columns.containsKey("运单号")) {
                put.accept(columns.get("数量"), quantity);
                put.accept(columns.get("快递公司"), "京东物流");
                put.accept(columns.get("运单号"), trackingNumber);
            } else {
                put.accept(columns.get("结果"), result);
                put.accept(columns.get("实际发货数量"), quantity);
                put.accept(columns.get("快递公司"), "京东物流");
                put.accept(columns.get("物流单号"), trackingNumber);
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
        headers.set("X-Operator", "alert-test");
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
        headers.set("X-Operator", "alert-test");
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
                + "-LINE,2,张三,13800000000,上海市浦东新区测试路1号,2026-08-11 10:00:00,,,\r\n";
        return ("\uFEFF" + header + "\r\n" + row).getBytes(StandardCharsets.UTF_8);
    }

    private HttpHeaders writeHeaders(String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", idempotencyKey);
        headers.set("X-Request-Id", "req-" + idempotencyKey);
        headers.set("X-Operator", "alert-test");
        return headers;
    }
}
