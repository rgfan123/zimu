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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
 * Issue #84 周期提醒：扫描器多实例/重复轮询只创建一个 sequence；重启可恢复；
 * ack 后按快照间隔再排；收齐/停止阻止提醒；失败暂停不轰炸；import 与 scanner 竞态不多发。
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "app.message-worker.enabled=false",
            "app.wecom-export-worker.enabled=false",
            "app.wecom-reminder.enabled=false",
            "app.wecom-export-worker.backoff-seconds=1",
            "app.file-store.root=${java.io.tmpdir}/zimu-wecom-reminder-test"
        })
@Import(FulfillmentExportWecomReminderScannerTest.ControlledWecomTransportConfig.class)
class FulfillmentExportWecomReminderScannerTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired AsyncTaskStore taskStore;
    @Autowired FulfillmentExportWecomService service;
    @Autowired FulfillmentExportWecomStore store;
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
                new WecomSendResult(WecomSendStatus.SUCCESS, "reminder-req-1", Instant.now(), null, null, false);
        /** 测试确定性 seam：外部 send 前回调（默认 no-op），用于在发送期间插入并发 import。 */
        volatile Runnable sendBlock = () -> {};

        @Override
        public WecomSendResult send(WecomOutboundMessage message) {
            sendBlock.run();
            sentMessages.add(message);
            return sendResult;
        }

        @Override
        public WecomUploadResult upload(Path file, String filename, WecomMediaType type) {
            // initial 阶段需要上传成功（提醒阶段不调用上传）
            return new WecomUploadResult(
                    WecomUploadStatus.SUCCESS, "MEDIA-REMINDER-1", "file",
                    Instant.now().minusSeconds(10), Instant.now().minusSeconds(5),
                    "upload-session-ok", "req-upload-ok", "FINISH", null, null, false);
        }
    }

    @BeforeEach
    void reset() {
        wecom.sentMessages.clear();
        wecom.sendResult = new WecomSendResult(
                WecomSendStatus.SUCCESS, "reminder-req-1", Instant.now(), null, null, false);
        wecom.sendBlock = () -> {};
        store.setAfterStateLock(null);
        jdbc.update(
                """
                UPDATE app.fulfillment_providers
                SET config = config || '{"wecomGroupChatId":"wrJgVnTQAAD-RM-001"}'::jsonb
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
    void duplicatePollsAndConcurrentScansCreateOnlyOneReminderSequence() throws Exception {
        String exportId = activeExport("FX-RM-DEDUP-001");
        makeDue(exportId);

        // 重复轮询：同一导出重复扫描只创建一个 delivery
        assertThat(service.scanDueReminders(10)).isEqualTo(1);
        assertThat(service.scanDueReminders(10)).isZero();
        assertThat(reminderCount(exportId)).isEqualTo(1);
        assertThat(reminderTaskCount(exportId, 1)).isEqualTo(1);

        // 多实例并发：10 个线程同时扫描仍只创建一个 sequence
        ExecutorService pool = Executors.newFixedThreadPool(5);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < 10; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return service.scanDueReminders(10);
                }));
            }
            start.countDown();
            for (Future<Integer> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(reminderCount(exportId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.async_tasks WHERE idempotency_key=?",
                Integer.class, "wecom-export-reminder:" + exportId + ":1")).isEqualTo(1);
    }

    @Test
    void reminderAckSchedulesNextBySnapshotIntervalAndRestartRecovers() throws Exception {
        String exportId = activeExport("FX-RM-INTERVAL-001");
        // 快照间隔 = 60 分钟（显式配置），与 SLA 不同
        jdbc.update(
                "UPDATE app.fulfillment_export_wecom_states SET reminder_interval_minutes=60 WHERE export_id=?",
                Long.parseLong(exportId));
        makeDue(exportId);

        service.scanDueReminders(10);
        Instant ackAt = Instant.now().plusSeconds(90);
        wecom.sendResult = new WecomSendResult(WecomSendStatus.SUCCESS, "rm-req-1", ackAt, null, null, false);
        claimAndRun(exportId, "REMINDER", 1);

        Map<String, Object> delivery = deliveryRow(exportId, "REMINDER", 1);
        assertThat(delivery.get("status")).isEqualTo("SENT");
        assertThat(instant(delivery.get("ack_sent_at")).getEpochSecond()).isEqualTo(ackAt.getEpochSecond());
        Map<String, Object> state = stateRow(exportId);
        assertThat(state.get("status")).isEqualTo("ACTIVE");
        assertThat(state.get("reminder_count")).isEqualTo(1);
        assertThat(instant(state.get("last_reminded_at")).getEpochSecond()).isEqualTo(ackAt.getEpochSecond());
        // 按快照间隔重排：ack + 60 分钟
        assertThat(instant(state.get("next_reminder_at")).getEpochSecond())
                .isEqualTo(ackAt.plus(Duration.ofMinutes(60)).getEpochSecond());

        // 提醒内容：批次/履约方/已等待时长/未回传数/可操作指引；发到 initial 快照群
        List<WecomOutboundMessage> reminders = wecom.sentMessages.stream()
                .filter(message -> message.type() == WecomOutboundMessage.Type.MARKDOWN)
                .toList();
        assertThat(reminders).hasSize(1);
        WecomOutboundMessage message = reminders.getFirst();
        assertThat(message.chatId()).isEqualTo("wrJgVnTQAAD-RM-001");
        assertThat(message.content())
                .contains(exportBatchNo(exportId))
                .contains("第三方履约")
                .contains("1")
                .contains("运单")
                .contains("回传");

        // 重启恢复：下次到期继续新 sequence（2），不重复也不漏
        jdbc.update(
                "UPDATE app.fulfillment_export_wecom_states SET next_reminder_at=CURRENT_TIMESTAMP - INTERVAL '1 minute' "
                        + "WHERE export_id=?",
                Long.parseLong(exportId));
        assertThat(service.scanDueReminders(10)).isEqualTo(1);
        assertThat(deliveryRow(exportId, "REMINDER", 2).get("status")).isEqualTo("PENDING");
        assertThat(reminderCount(exportId)).isEqualTo(2);
    }

    @Test
    void terminalReminderFailurePausesAutomaticRemindersWithAlert() throws Exception {
        String exportId = activeExport("FX-RM-PAUSE-001");
        makeDue(exportId);
        service.scanDueReminders(10);
        wecom.sendResult = new WecomSendResult(
                WecomSendStatus.FAILED, null, null, null, "CONNECTION_NOT_READY", true);

        claimAndRun(exportId, "REMINDER", 1); // 第 1 次：安全失败 → 退避
        assertThat(deliveryRow(exportId, "REMINDER", 1).get("status")).isEqualTo("PENDING");
        makeTaskDue(exportId, 1);
        claimAndRun(exportId, "REMINDER", 1); // 第 2 次：总尝试 2 次 → 暂停 + 告警

        assertThat(deliveryRow(exportId, "REMINDER", 1).get("status")).isEqualTo("FAILED");
        Map<String, Object> state = stateRow(exportId);
        assertThat(state.get("status")).isEqualTo("ACTIVE"); // 导出本身不被判失败
        assertThat(state.get("next_reminder_at")).isNull(); // 暂停自动提醒
        assertThat(openAlertCount(exportId)).isEqualTo(1);
        assertThat(openAlertDetail(exportId)).contains("CONNECTION_NOT_READY");

        // 暂停后扫描不再创建新提醒（不轰炸）；暂停态（next_reminder_at=NULL）本来就不会被扫到
        assertThat(service.scanDueReminders(10)).isZero();
        assertThat(reminderCount(exportId)).isEqualTo(1);
    }

    @Test
    void reminderTimeoutPreservesRecipientEvidenceOnDeliveryButNotContentOrSnapshot() throws Exception {
        // markdown 提醒发送 TIMEOUT（已提交未 ack）→ UNKNOWN：delivery 必须保留快照群的 recipient
        // 证据（发送前已写入 chat_id）；state.chat_id 快照不变（不悄悄换群、不置 NULL）；
        // 提醒 markdown 内容/批次号绝不落库。
        String exportId = activeExport("FX-RM-TIMEOUT-EVIDENCE-001");
        makeDue(exportId);
        service.scanDueReminders(10);
        wecom.sendResult = new WecomSendResult(
                WecomSendStatus.TIMEOUT, "rm-req-timeout-evidence-1", null, null, "ACK_TIMEOUT", false);
        wecom.sentMessages.clear();

        claimAndRun(exportId, "REMINDER", 1);

        Map<String, Object> delivery = deliveryRow(exportId, "REMINDER", 1);
        assertThat(delivery.get("status")).isEqualTo("UNKNOWN");
        assertThat(delivery.get("chat_id")).isEqualTo("wrJgVnTQAAD-RM-001");
        assertThat(delivery.get("stage")).isEqualTo("SEND");
        Map<String, Object> state = stateRow(exportId);
        assertThat(state.get("status")).isEqualTo("ACTIVE"); // reminder 失败不改导出状态
        assertThat(state.get("chat_id")).isEqualTo("wrJgVnTQAAD-RM-001"); // 快照不变
        assertThat(state.get("next_reminder_at")).isNull(); // 暂停自动提醒
        String deliveryJson = jdbc.queryForObject(
                "SELECT row_to_json(d)::text FROM app.fulfillment_export_wecom_deliveries d WHERE id=?",
                String.class, delivery.get("id"));
        assertThat(deliveryJson)
                .doesNotContain("运单回传提醒")
                .doesNotContain(exportBatchNo(exportId));
    }

    @Test
    void reminderRetryableFailureExhaustedPreservesRecipientEvidenceOnDelivery() throws Exception {
        // markdown 提醒 retryable 失败（CONNECTION_NOT_READY）耗尽 2 次 → FAILED：delivery 必须
        // 保留快照群 recipient 证据（第一次尝试发送前已写入 chat_id，重试不丢）；state.chat_id
        // 快照不变、导出仍 ACTIVE、提醒暂停；提醒 markdown 内容/批次号绝不落库。
        String exportId = activeExport("FX-RM-FAILED-EVIDENCE-001");
        makeDue(exportId);
        service.scanDueReminders(10);
        wecom.sendResult = new WecomSendResult(
                WecomSendStatus.FAILED, null, null, null, "CONNECTION_NOT_READY", true);
        wecom.sentMessages.clear();

        claimAndRun(exportId, "REMINDER", 1); // 第 1 次：安全失败 → 退避
        assertThat(deliveryRow(exportId, "REMINDER", 1).get("status")).isEqualTo("PENDING");
        makeTaskDue(exportId, 1);
        claimAndRun(exportId, "REMINDER", 1); // 第 2 次：总尝试 2 次 → FAILED

        Map<String, Object> delivery = deliveryRow(exportId, "REMINDER", 1);
        assertThat(delivery.get("status")).isEqualTo("FAILED");
        assertThat(delivery.get("attempts")).isEqualTo(2);
        assertThat(delivery.get("chat_id")).isEqualTo("wrJgVnTQAAD-RM-001");
        assertThat(delivery.get("stage")).isEqualTo("SEND");
        Map<String, Object> state = stateRow(exportId);
        assertThat(state.get("status")).isEqualTo("ACTIVE"); // 导出本身不被判失败
        assertThat(state.get("chat_id")).isEqualTo("wrJgVnTQAAD-RM-001"); // 快照不变
        assertThat(state.get("next_reminder_at")).isNull(); // 暂停自动提醒
        assertThat(openAlertCount(exportId)).isEqualTo(1);
        String deliveryJson = jdbc.queryForObject(
                "SELECT row_to_json(d)::text FROM app.fulfillment_export_wecom_deliveries d WHERE id=?",
                String.class, delivery.get("id"));
        assertThat(deliveryJson)
                .doesNotContain("运单回传提醒")
                .doesNotContain(exportBatchNo(exportId));
    }

    @Test
    void completedExportStopsRemindersAndInFlightReminderBecomesNoOp() throws Exception {
        String exportId = activeExport("FX-RM-COMPLETE-001");
        makeDue(exportId);
        service.scanDueReminders(10);

        // tracking 导入收齐 → 主动标 COMPLETED
        completeTracking(exportId);
        assertThat(stateRow(exportId).get("status")).isEqualTo("COMPLETED");

        // scanner 不再创建提醒（COMPLETED 不在扫描范围，next_reminder_at 已清空）
        assertThat(service.scanDueReminders(10)).isZero();
        assertThat(reminderCount(exportId)).isEqualTo(1);

        // 已入队的 reminder 任务执行时为幂等 no-op（发送前复查，不催已收齐）
        wecom.sentMessages.clear();
        claimAndRun(exportId, "REMINDER", 1);
        assertThat(wecom.sentMessages).isEmpty();
        assertThat(deliveryRow(exportId, "REMINDER", 1).get("status")).isEqualTo("PENDING");
        assertThat(taskStatus("wecom-export-reminder:" + exportId + ":1")).isEqualTo("SUCCEEDED");
    }

    @Test
    void confirmedWecomTrackingWithoutImportBatchAlsoStopsReminders() throws Exception {
        String exportId = activeExport("FX-RM-WECOM-CONFIRMED-001");
        makeDue(exportId);
        service.scanDueReminders(10);

        long shipmentId = store.firstShipmentId(Long.parseLong(exportId));
        jdbc.update(
                """
                INSERT INTO app.trackings
                    (shipment_id, logistics_company_code, logistics_company_name, tracking_number,
                     provider_tracking_batch_id, raw_payload)
                VALUES (?, 'JD', '京东物流', ?, NULL, '{"source":"WECOM_TRACKING_DRAFT"}'::jsonb)
                """,
                shipmentId,
                "JDVA-WECOM-" + exportId);

        service.markTrackingReceived(Long.parseLong(exportId));
        assertThat(stateRow(exportId).get("status")).isEqualTo("COMPLETED");
        assertThat(store.missingTrackingShipmentCount(Long.parseLong(exportId))).isZero();

        wecom.sentMessages.clear();
        claimAndRun(exportId, "REMINDER", 1);
        assertThat(wecom.sentMessages).isEmpty();
        assertThat(taskStatus("wecom-export-reminder:" + exportId + ":1")).isEqualTo("SUCCEEDED");
    }

    @Test
    void manuallyStoppedExportBlocksRemindersAndInFlightReminderIsNoOp() throws Exception {
        String exportId = activeExport("FX-RM-STOP-001");
        makeDue(exportId);
        service.scanDueReminders(10);

        ResponseEntity<Map> stopped = http.exchange(
                "/api/v1/fulfillment-exports/" + exportId + "/wecom-stop",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "expected_version", versionOf(exportId),
                        "reason", "该批次线下已催收"),
                        writeHeaders("rm-stop-001")),
                Map.class);
        assertThat(stopped.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(stateRow(exportId).get("status")).isEqualTo("MANUALLY_STOPPED");

        wecom.sentMessages.clear();
        claimAndRun(exportId, "REMINDER", 1);
        assertThat(wecom.sentMessages).isEmpty();
        assertThat(deliveryRow(exportId, "REMINDER", 1).get("status")).isEqualTo("PENDING");
        assertThat(taskStatus("wecom-export-reminder:" + exportId + ":1")).isEqualTo("SUCCEEDED");
    }

    // ------------------------------------------------------------------
    // 竞态线性化：reminder prepare 与 tracking import 在同一 state 行锁上互斥
    // ------------------------------------------------------------------

    @Test
    void trackingImportWinningTheStateLockBlocksReminderSendEntirely() throws Exception {
        String exportId = activeExport("FX-RM-RACE-IMPORT-001");
        makeDue(exportId);
        service.scanDueReminders(10);
        wecom.sentMessages.clear(); // 清掉 initial 阶段留下的 FILE 发送证据，只看 reminder
        AsyncTaskStore.AsyncTask task = claimUntil("export:" + exportId + ":REMINDER:1");

        // import 事务（markTrackingReceived）先拿到 state 行锁并保持：确定性地让 reminder
        // prepare 撞锁等待，直到 import 提交
        CountDownLatch importLocked = new CountDownLatch(1);
        CountDownLatch releaseImport = new CountDownLatch(1);
        store.setAfterStateLock(() -> {
            importLocked.countDown();
            try {
                releaseImport.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> importTx = pool.submit(() -> {
                try {
                    completeTrackingWithKey(exportId, "rm-race-import-tracking-001");
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            });
            assertThat(importLocked.await(10, TimeUnit.SECONDS)).as("import 必须已持有 state 行锁").isTrue();
            Future<?> reminderRun = pool.submit(() -> runner.execute(task));
            Thread.sleep(400); // 确定性：reminder 必然被行锁阻塞，绝不可能在 import 提交前完成
            assertThat(reminderRun.isDone()).as("reminder 必须阻塞在 state 行锁上").isFalse();
            releaseImport.countDown();
            importTx.get(10, TimeUnit.SECONDS);
            reminderRun.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        // import 先提交：提醒绝不发送；delivery 保持 PENDING；任务幂等收口
        assertThat(wecom.sentMessages).isEmpty();
        assertThat(stateRow(exportId).get("status")).isEqualTo("COMPLETED");
        assertThat(deliveryRow(exportId, "REMINDER", 1).get("status")).isEqualTo("PENDING");
        assertThat(taskStatus("wecom-export-reminder:" + exportId + ":1")).isEqualTo("SUCCEEDED");
    }

    @Test
    void reminderPrepareWinningTheLockStillSendsEvenIfImportCommitsBeforeAck() throws Exception {
        String exportId = activeExport("FX-RM-RACE-REMINDER-001");
        makeDue(exportId);
        service.scanDueReminders(10);
        wecom.sentMessages.clear(); // 清掉 initial 阶段留下的 FILE 发送证据，只看 reminder
        AsyncTaskStore.AsyncTask task = claimUntil("export:" + exportId + ":REMINDER:1");

        // 在外部 send 处阻塞：prepare 已提交（CLAIMED，发送决策线性化在收齐之前），
        // import 在外部发送期间提交收齐
        CountDownLatch sendEntered = new CountDownLatch(1);
        CountDownLatch releaseSend = new CountDownLatch(1);
        wecom.sendBlock = () -> {
            sendEntered.countDown();
            try {
                releaseSend.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> reminderRun = pool.submit(() -> runner.execute(task));
            assertThat(sendEntered.await(10, TimeUnit.SECONDS)).as("prepare 必须已提交并进入外部发送").isTrue();
            completeTrackingWithKey(exportId, "rm-race-reminder-tracking-001");
            releaseSend.countDown();
            reminderRun.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        // 发送决策合法（线性化在收齐前）：证据保留 SENT + ack；状态 COMPLETED，时间线不重复计数
        Map<String, Object> delivery = deliveryRow(exportId, "REMINDER", 1);
        assertThat(delivery.get("status")).isEqualTo("SENT");
        assertThat(delivery.get("ack_sent_at")).isNotNull();
        Map<String, Object> state = stateRow(exportId);
        assertThat(state.get("status")).isEqualTo("COMPLETED");
        assertThat(state.get("reminder_count")).isEqualTo(0);
        assertThat(state.get("next_reminder_at")).isNull();
    }

    // ------------------------------------------------------------------
    // stale-worker fence：reminder prepare 与 owner fence 同事务
    // ------------------------------------------------------------------

    @Test
    void staleOwnerLosingLeaseBeforeReminderClaimNeverTransitionsToSendingAndNewOwnerSendsOnce() throws Exception {
        String exportId = activeExport("FX-RM-STALE-CLAIM-001");
        makeDue(exportId);
        service.scanDueReminders(10);
        wecom.sentMessages.clear();
        String expected = "export:" + exportId + ":REMINDER:1";
        var oldTask = claimSpecific(expected, "rm-stale-old", Duration.ofMinutes(30));
        // 租约过期后第二实例重新领取；旧 Worker 手里仍握着旧 task 对象
        jdbc.update(
                "UPDATE app.async_tasks SET lease_until=CURRENT_TIMESTAMP - INTERVAL '1 minute' WHERE id=?",
                oldTask.id());
        var newTask = claimSpecific(expected, "rm-stale-new", Duration.ofMinutes(30));

        // 旧 Worker 执行：prepare 与 fence 同事务，租约已丢 → 绝不 PENDING→SENDING、绝不 send
        runner.execute(oldTask);
        assertThat(deliveryRow(exportId, "REMINDER", 1).get("status")).isEqualTo("PENDING");
        assertThat(wecom.sentMessages).isEmpty();

        // 新 owner 合法领取：prepare 成功 → 提醒恰好发送一次
        runner.execute(newTask);
        assertThat(deliveryRow(exportId, "REMINDER", 1).get("status")).isEqualTo("SENT");
        assertThat(wecom.sentMessages).hasSize(1);
    }

    // ------------------------------------------------------------------
    // 代际栅栏：新 INITIAL 成功后，旧 reminder 迟到结果必须 SUPERSEDED，绝不改新时间线
    // ------------------------------------------------------------------

    @Test
    void lateReminderSuccessAfterNewInitialIsSupersededWithoutTimelineChange() throws Exception {
        ReminderInterleave s = startReminderThenResendToNewGeneration("FX-RM-SUPERSEDE-SUCCESS-001");
        s.release().countDown();
        s.reminderRun().get(10, TimeUnit.SECONDS);
        assertSupersededNoTimelineChange(s.exportId(), s.timelineBefore());
        assertThat(deliveryRow(s.exportId(), "REMINDER", 1))
                .containsEntry("request_id", "resend-ack-2")
                .doesNotContainEntry("ack_sent_at", null);
    }

    @Test
    void lateReminderFailedAfterNewInitialIsSupersededWithoutAlert() throws Exception {
        ReminderInterleave s = startReminderThenResendToNewGeneration("FX-RM-SUPERSEDE-FAILED-001");
        wecom.sendResult = new WecomSendResult(
                WecomSendStatus.FAILED, null, null, null, "CONNECTION_NOT_READY", false);
        s.release().countDown();
        s.reminderRun().get(10, TimeUnit.SECONDS);
        assertSupersededNoTimelineChange(s.exportId(), s.timelineBefore());
    }

    @Test
    void lateReminderUnknownAfterNewInitialIsSupersededWithoutAlert() throws Exception {
        ReminderInterleave s = startReminderThenResendToNewGeneration("FX-RM-SUPERSEDE-UNKNOWN-001");
        wecom.sendResult = new WecomSendResult(
                WecomSendStatus.TIMEOUT, "rm-req-timeout-late", null, null, "ACK_TIMEOUT", false);
        s.release().countDown();
        s.reminderRun().get(10, TimeUnit.SECONDS);
        assertSupersededNoTimelineChange(s.exportId(), s.timelineBefore());
    }

    // ------------------------------------------------------------------
    // 工具
    // ------------------------------------------------------------------

    /** 生成第三方导出并驱动 initial 到 ACTIVE（快照群已登记）。 */
    private String activeExport(String orderRef) throws Exception {
        String exportId = generateThirdPartyExport(orderRef);
        claimAndRun(exportId, "INITIAL", 1);
        assertThat(stateRow(exportId).get("status")).isEqualTo("ACTIVE");
        return exportId;
    }

    private String generateThirdPartyExport(String orderRef) throws Exception {
        ResponseEntity<Map> uploaded = uploadRaw(
                "reminder.csv", feixiangSingleCsv(orderRef), "source-import-" + orderRef.toLowerCase());
        assertThat(uploaded.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> batch = uploaded.getBody();
        ResponseEntity<Map> confirmed = confirmBatch(
                batch.get("id").toString(), "confirm-" + orderRef.toLowerCase());
        assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.OK);
        return ((List<?>) confirmed.getBody().get("generated_fulfillment_export_ids")).getFirst().toString();
    }

    private void makeDue(String exportId) {
        jdbc.update(
                "UPDATE app.fulfillment_export_wecom_states SET next_reminder_at=CURRENT_TIMESTAMP - INTERVAL '1 minute' "
                        + "WHERE export_id=?",
                Long.parseLong(exportId));
    }

    private void makeTaskDue(String exportId, int sequence) {
        jdbc.update(
                "UPDATE app.async_tasks SET next_run_at=CURRENT_TIMESTAMP WHERE idempotency_key=?",
                "wecom-export-reminder:" + exportId + ":" + sequence);
    }

    private void completeTracking(String exportId) throws Exception {
        completeTrackingWithKey(exportId, "rm-tracking-complete-" + exportId);
    }

    private void completeTrackingWithKey(String exportId, String idempotencyKey) throws Exception {
        byte[] instruction = downloadExport(exportId);
        // 运单号唯一（trackings 有 (logistics_company_code, tracking_number) 全局唯一约束）
        byte[] returned = fillThirdPartyTracking(instruction, "SHIPPED", "4", "JDVA-RM-" + exportId);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(returned) {
            @Override public String getFilename() { return "tracking.xlsx"; }
        });
        body.add("import_mode", "NEW");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.set("Idempotency-Key", idempotencyKey);
        headers.set("X-Operator", "reminder-test");
        ResponseEntity<Map> tracking = http.exchange(
                "/api/v1/fulfillment-exports/" + exportId + "/tracking-imports",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class);
        assertThat(tracking.getStatusCode())
                .withFailMessage("tracking import failed: %s", tracking.getBody())
                .isEqualTo(HttpStatus.CREATED);
    }

    /** 领取指定 payload 的任务（其他用例遗留的未完成任务直接收口，避免串扰）。 */
    private AsyncTaskStore.AsyncTask claimUntil(String expectedPayloadRef) {
        for (int i = 0; i < 10; i++) {
            var claimed = taskStore.claim(
                            FulfillmentExportWecomService.TASK_TYPE, "reminder-test", Duration.ofSeconds(30))
                    .orElseThrow(() -> new AssertionError("expected claimable task " + expectedPayloadRef));
            if (expectedPayloadRef.equals(claimed.payloadRef())) {
                return claimed;
            }
            taskStore.succeed(claimed.id(), claimed.leaseOwner());
        }
        throw new AssertionError("task not claimable: " + expectedPayloadRef);
    }

    /** 以指定 owner/租约领取目标任务（stale-worker fence 用例需要控制 lease_owner 与租约时长）。 */
    private AsyncTaskStore.AsyncTask claimSpecific(String expectedPayloadRef, String owner, Duration lease) {
        for (int i = 0; i < 10; i++) {
            var claimed = taskStore.claim(FulfillmentExportWecomService.TASK_TYPE, owner, lease)
                    .orElseThrow(() -> new AssertionError("expected claimable task " + expectedPayloadRef));
            if (expectedPayloadRef.equals(claimed.payloadRef())) {
                return claimed;
            }
            taskStore.succeed(claimed.id(), claimed.leaseOwner());
        }
        throw new AssertionError("task not claimable: " + expectedPayloadRef);
    }

    /** reminder 与 newer INITIAL 代际竞态的确定性夹具：返回持有线程/释放闩/时间线快照。 */
    private record ReminderInterleave(
            Future<?> reminderRun, CountDownLatch release, String exportId, Map<String, Object> timelineBefore) {}

    /**
     * 制造「reminder 正在外部发送（SENDING）」时，人工重发的新 INITIAL 代际已成功：确定性
     * 阻塞第一个 send（reminder markdown），放行后续 send（新 INITIAL 文件消息）。
     */
    private ReminderInterleave startReminderThenResendToNewGeneration(String orderRef) throws Exception {
        String exportId = activeExport(orderRef); // INITIAL seq1 已 SENT，代际 1
        makeDue(exportId);
        service.scanDueReminders(10);
        wecom.sentMessages.clear(); // 只看 reminder 与新 INITIAL
        AsyncTaskStore.AsyncTask task =
                claimSpecific("export:" + exportId + ":REMINDER:1", "rm-supersede", Duration.ofMinutes(30));

        AtomicInteger sendCount = new AtomicInteger();
        CountDownLatch reminderEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        wecom.sendBlock = () -> {
            if (sendCount.incrementAndGet() == 1) {
                reminderEntered.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };

        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<?> reminderRun = pool.submit(() -> runner.execute(task));
        assertThat(reminderEntered.await(10, TimeUnit.SECONDS)).as("reminder 必须已进入外部发送").isTrue();

        // 新 INITIAL 代际成功（人工重发）：reminder 已 SENDING、代际已推进到 2
        wecom.sendResult = new WecomSendResult(
                WecomSendStatus.SUCCESS, "resend-ack-2", Instant.now().plusSeconds(120), null, null, false);
        ResponseEntity<Map> resend =
                resend(exportId, versionOf(exportId), "群内未收到文件，重新发送", "rm-supersede-" + orderRef.toLowerCase());
        assertThat(resend.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        int sequence = ((Number) resend.getBody().get("resend_sequence")).intValue();
        claimAndRun(exportId, "INITIAL", sequence);
        assertThat(stateRow(exportId).get("status")).isEqualTo("ACTIVE");
        return new ReminderInterleave(reminderRun, release, exportId, timelineSnapshot(exportId));
    }

    /** 只快照提醒时间线相关列（代际推进后、迟到 reminder 结果应用前的对照基准）。 */
    private Map<String, Object> timelineSnapshot(String exportId) {
        Map<String, Object> s = stateRow(exportId);
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("initial_sent_at", s.get("initial_sent_at"));
        t.put("tracking_due_at", s.get("tracking_due_at"));
        t.put("next_reminder_at", s.get("next_reminder_at"));
        t.put("last_reminded_at", s.get("last_reminded_at"));
        t.put("reminder_count", s.get("reminder_count"));
        return t;
    }

    /** 迟到 reminder 结果不得改新代际时间线，delivery 为 SUPERSEDED，且不产生/不改任何告警。 */
    private void assertSupersededNoTimelineChange(String exportId, Map<String, Object> before) {
        Map<String, Object> state = stateRow(exportId);
        assertThat(state.get("initial_sent_at")).isEqualTo(before.get("initial_sent_at"));
        assertThat(state.get("tracking_due_at")).isEqualTo(before.get("tracking_due_at"));
        assertThat(state.get("next_reminder_at")).isEqualTo(before.get("next_reminder_at"));
        assertThat(state.get("last_reminded_at")).isEqualTo(before.get("last_reminded_at"));
        assertThat(state.get("reminder_count")).isEqualTo(before.get("reminder_count"));
        assertThat(deliveryRow(exportId, "REMINDER", 1).get("status")).isEqualTo("SUPERSEDED");
        assertThat(openAlertCount(exportId)).isZero();
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

    private void claimAndRun(String exportId, String kind, int sequence) {
        String expected = "export:" + exportId + ":" + kind + ":" + sequence;
        for (int i = 0; i < 10; i++) {
            var claimed = taskStore.claim(
                            FulfillmentExportWecomService.TASK_TYPE, "reminder-test", Duration.ofSeconds(30))
                    .orElseThrow(() -> new AssertionError("expected claimable task " + expected));
            if (expected.equals(claimed.payloadRef())) {
                runner.execute(claimed);
                return;
            }
            taskStore.succeed(claimed.id(), claimed.leaseOwner());
        }
        throw new AssertionError("task not claimable: " + expected);
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

    private int reminderCount(String exportId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM app.fulfillment_export_wecom_deliveries "
                        + "WHERE export_id=? AND kind='REMINDER'",
                Integer.class, Long.parseLong(exportId));
    }

    private int reminderTaskCount(String exportId, int sequence) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM app.async_tasks WHERE idempotency_key=?",
                Integer.class, "wecom-export-reminder:" + exportId + ":" + sequence);
    }

    private String taskStatus(String idempotencyKey) {
        return jdbc.queryForObject(
                "SELECT status FROM app.async_tasks WHERE idempotency_key=?", String.class, idempotencyKey);
    }

    private long versionOf(String exportId) {
        return jdbc.queryForObject(
                "SELECT lock_version FROM app.fulfillment_export_wecom_states WHERE export_id=?",
                Long.class, Long.parseLong(exportId));
    }

    private int openAlertCount(String exportId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM app.operational_alerts WHERE alert_type='FULFILLMENT_EXPORT_WECOM' "
                        + "AND status='OPEN' AND detail->>'export_id'=?",
                Integer.class, exportId);
    }

    private String openAlertDetail(String exportId) {
        return jdbc.queryForObject(
                "SELECT detail::text FROM app.operational_alerts WHERE alert_type='FULFILLMENT_EXPORT_WECOM' "
                        + "AND status='OPEN' AND detail->>'export_id'=? ORDER BY id DESC LIMIT 1",
                String.class, exportId);
    }

    private String exportBatchNo(String exportId) {
        return jdbc.queryForObject(
                "SELECT export_batch_no FROM app.fulfillment_exports WHERE id=?",
                String.class, Long.parseLong(exportId));
    }

    private byte[] downloadExport(String exportId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Operator", "reminder-test");
        ResponseEntity<byte[]> response = http.exchange(
                "/api/v1/fulfillment-exports/" + exportId + "/file",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                byte[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
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
            // 双格式：v2 人读八列（运单号/快递公司）优先；v1 兼容 24 列按旧列名
            if (columns.containsKey("运单号")) {
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
        headers.set("X-Operator", "reminder-test");
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
        headers.set("X-Operator", "reminder-test");
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
        headers.set("X-Operator", "reminder-test");
        return headers;
    }

    private static Instant instant(Object value) {
        return ((java.sql.Timestamp) value).toInstant();
    }
}
