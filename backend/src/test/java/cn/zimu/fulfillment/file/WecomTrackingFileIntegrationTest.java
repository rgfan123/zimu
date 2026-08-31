package cn.zimu.fulfillment.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

import cn.zimu.fulfillment.message.AsyncTaskStore;
import cn.zimu.fulfillment.message.ChannelMessageCommand;
import cn.zimu.fulfillment.message.MessageInterpreter;
import cn.zimu.fulfillment.message.MessageSubmissionService;
import cn.zimu.fulfillment.connector.wecom.WecomMediaEvidenceService;
import cn.zimu.fulfillment.connector.wecom.LocalMediaDownloaderConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Issue #86：单聊 file 的真实下载/解密/解析/草稿/人工确认纵切片。 */
@Testcontainers
@Import(LocalMediaDownloaderConfiguration.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "app.message-worker.enabled=false",
            "app.wecom-tracking-file-worker.enabled=false",
            "app.wecom-export-worker.enabled=false",
            "app.wecom-reminder.enabled=false",
            "app.agent-worker.enabled=false",
            "app.file-store.root=${java.io.tmpdir}/zimu-wecom-tracking-file-test"
        })
class WecomTrackingFileIntegrationTest {

    private static final String AES_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY";
    private static final String OPERATOR = "wecom-file-test";
    private static final String ADMIN_PASSWORD = "wecom-file-test-password";
    private static final Path MEDIA_DIR = createMediaDir();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.media.dir", () -> MEDIA_DIR.toString());
        registry.add("app.gateway.basic-auth.username", () -> OPERATOR);
        registry.add("app.gateway.basic-auth.password", () -> ADMIN_PASSWORD);
        registry.add("app.carrier-prefixes.carriers.JD.name", () -> "京东物流");
        registry.add("app.carrier-prefixes.carriers.JD.enabled", () -> "true");
    }

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired MessageSubmissionService submissions;
    @Autowired AsyncTaskStore tasks;
    @Autowired WecomTrackingFileProcessor processor;
    @Autowired WecomTrackingFileDraftService draftService;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean MessageInterpreter interpreter;

    private HttpServer server;
    private String mediaUrl;
    private final AtomicReference<byte[]> responseBody = new AtomicReference<>();
    private final AtomicInteger responseStatus = new AtomicInteger(200);
    private final AtomicInteger downloadHits = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        cleanMediaDir();
        jdbc.update(
                """
                UPDATE app.fulfillment_providers
                SET config = config || '{"wecomGroupChatId":"wrJgVnTQAAD-WF-001"}'::jsonb
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
                -- V99 商品数量整数化移植：来源数量必须为正整数，旧夹具「1.5 × 2」改为「1 × 3」保持履约量 3 不变。
                SELECT 'FEIXIANG', 'FX-PRODUCT-001', '子牧羊小腿', '标准箱', 3.000, sku_id, true
                FROM app.source_channel_skus
                WHERE source_channel='WECOM' AND source_sku_ref='WECOM-SKU-TP-001'
                ON CONFLICT (source_channel, source_sku_ref) DO NOTHING
                """);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/tracking", exchange -> {
            downloadHits.incrementAndGet();
            byte[] body = responseBody.get();
            int status = responseStatus.get();
            exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
            exchange.sendResponseHeaders(status, body == null ? 0 : body.length);
            if (body != null) {
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        mediaUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/tracking";
        responseStatus.set(200);
        responseBody.set(new byte[0]);
        downloadHits.set(0);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void duplicateFileCallbackCreatesOneEvidenceAndDraftThenHumanConfirmationCreatesTracking() throws Exception {
        String exportId = generateExport("FX-WECOM-FILE-E2E-001");
        byte[] returned = fillTracking(
                downloadExport(exportId), "JDVA-WECOM-FILE-E2E-001");
        responseBody.set(encrypt(returned));

        ChannelMessageCommand command = fileMessage("WF-MSG-E2E-001", mediaUrl, "single");
        long submissionId = submissions.submit(command);
        assertThat(submissions.submit(command)).isEqualTo(submissionId);

        worker().poll();

        assertThat(downloadHits).hasValue(1);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM app.async_tasks WHERE payload_ref=? AND task_type='WECOM_TRACKING_FILE'",
                        Long.class,
                        "submission:" + submissionId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM app.async_tasks WHERE payload_ref=?",
                        String.class,
                        "submission:" + submissionId))
                .isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM app.async_tasks WHERE payload_ref=? AND task_type='INTERPRET_MESSAGE'",
                        Long.class,
                        "submission:" + submissionId))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM app.message_interpretations WHERE submission_id=?",
                        Long.class,
                        submissionId))
                .isZero();
        verifyNoInteractions(interpreter);

        Map<String, Object> draft = jdbc.queryForMap(
                """
                SELECT d.id, d.revision, d.task_id, d.carrier_code, d.tracking_no,
                       rc.resolution_version case_version, rc.detail::text detail
                FROM app.provider_tracking_drafts d
                JOIN app.review_cases rc ON rc.provider_tracking_draft_id=d.id
                WHERE d.submission_id=?
                """,
                submissionId);
        assertThat(draft)
                .containsEntry("carrier_code", "JD")
                .containsEntry("tracking_no", "JDVA-WECOM-FILE-E2E-001");
        assertThat(draft.get("detail").toString())
                .contains("WECOM_TRACKING_FILE", "message_media_id")
                .doesNotContain(AES_KEY, mediaUrl);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM app.trackings WHERE tracking_number='JDVA-WECOM-FILE-E2E-001'",
                        Long.class))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM app.import_batches WHERE batch_type='PROVIDER_TRACKING' "
                                + "AND source_fulfillment_export_id=?",
                        Long.class,
                        Long.parseLong(exportId)))
                .isZero();

        // 初始发送尚未执行时状态为 PENDING；人工确认仍必须由 batchless tracking 主动推进到 COMPLETED。
        ResponseEntity<Map> confirmed = batchConfirm(draft);
        assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) confirmed.getBody().get("success_count")).intValue()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT provider_tracking_batch_id IS NULL FROM app.trackings "
                                + "WHERE tracking_number='JDVA-WECOM-FILE-E2E-001'",
                        Boolean.class))
                .isTrue();
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM app.fulfillment_export_wecom_states WHERE export_id=?",
                        String.class,
                        Long.parseLong(exportId)))
                .isEqualTo("COMPLETED");
    }

    /**
     * 防回归（2026-08-31 中汇生产事故）：企微单聊发来的<b>来源订单表</b>必须走
     * 「先认模板再分岔」的导入分支——生成 SOURCE_ORDER 导入批次并当场收口任务
     * （SUCCEEDED + submission DRAFTED）。事故形态：导入成功但任务未收口，
     * 租约到期被重领、attempts 耗尽后被兜底判成 WECOM_TRACKING_FILE_PROCESSING_FAILED。
     */
    @Test
    void wecomSourceOrderFileImportsABatchAndSucceedsTheTaskInPlace() throws Exception {
        responseBody.set(encrypt(zhonghuiSourceOrderWorkbook("S-WECOM-SOURCE-001")));
        long submissionId = submissions.submit(fileMessage("WF-MSG-SOURCE-001", mediaUrl, "single"));

        worker().poll();

        assertThat(jdbc.queryForObject(
                        "SELECT status FROM app.async_tasks WHERE payload_ref=? AND task_type='WECOM_TRACKING_FILE'",
                        String.class,
                        "submission:" + submissionId))
                .as("导入成功必须当场收口任务，不得留给租约重领打成 PROCESSING_FAILED")
                .isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM app.message_submissions WHERE id=?",
                        String.class,
                        submissionId))
                .isEqualTo("DRAFTED");
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*) FROM app.import_batches
                        WHERE batch_type='SOURCE_ORDER' AND source_channel='ZHONGHUI'
                          AND original_file_name LIKE 'zhonghui%'
                        """,
                        Long.class))
                .as("订单表必须落成来源订单导入批次（与后台上传同一条链路）")
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM app.raw_import_rows rir JOIN app.import_batches ib "
                                + "ON ib.id=rir.import_batch_id WHERE ib.source_channel='ZHONGHUI' "
                                + "AND rir.source_order_ref='S-WECOM-SOURCE-001'",
                        Long.class))
                .isEqualTo(1);
        // 订单表不是运单回传：不得产出运单草稿，也不得触发消息解读。
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM app.provider_tracking_drafts WHERE submission_id=?",
                        Long.class,
                        submissionId))
                .isZero();
        verifyNoInteractions(interpreter);
    }

    /** 中汇来源订单表（与 SourceFileParser 的 ZHONGHUI 指纹必填集一致）。 */
    private byte[] zhonghuiSourceOrderWorkbook(String orderNo) throws Exception {
        List<String> headers = List.of(
                "订单号", "商品编号", "商品名称", "件数", "收件人", "收件电话", "收件地址",
                "包装规格", "单位", "下单时间");
        List<String> values = List.of(
                orderNo, "60049901", "子牧企微订单表测试商品", "1",
                "企微订单表收件人", "13000000010", "北京朝阳区示例路10号1001",
                "500g*2", "份", "2026-08-30 10:30:00");
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("Sheet1");
            var header = sheet.createRow(0);
            for (int index = 0; index < headers.size(); index++) {
                header.createCell(index).setCellValue(headers.get(index));
            }
            var row = sheet.createRow(1);
            for (int index = 0; index < values.size(); index++) {
                row.createCell(index).setCellValue(values.get(index));
            }
            workbook.write(output);
            return output.toByteArray();
        }
    }

    @Test
    void dataRowWithTwentyFifthCellRetriesWithoutRedownloadAndEndsInReadableReview() throws Exception {
        String exportId = generateExport("FX-WECOM-FILE-BAD-001");
        byte[] returned = fillTracking(downloadExport(exportId), "JDVA-WECOM-FILE-BAD-001");
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(returned));
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            workbook.getSheetAt(0).getRow(1).createCell(24).setCellValue("不应被静默忽略的额外数据");
            workbook.write(output);
            responseBody.set(encrypt(output.toByteArray()));
        }
        long submissionId = submissions.submit(fileMessage("WF-MSG-BAD-001", mediaUrl, "single"));

        worker().poll();

        assertThat(downloadHits).hasValue(1);
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM app.async_tasks WHERE payload_ref=?",
                        String.class,
                        "submission:" + submissionId))
                .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject(
                        "SELECT last_error FROM app.async_tasks WHERE payload_ref=?",
                        String.class,
                        "submission:" + submissionId))
                .isEqualTo("WECOM_TRACKING_FILE_INVALID");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM app.provider_tracking_drafts WHERE submission_id=?",
                        Long.class,
                        submissionId))
                .isZero();
        Map<String, Object> review = jdbc.queryForMap(
                "SELECT id, detail::text FROM app.review_cases WHERE message_submission_id=? AND reason_code=?",
                submissionId,
                WecomTrackingFileDraftService.FAILURE_REASON);
        assertThat(review.get("detail").toString()).contains("WECOM_TRACKING_FILE_INVALID", "精确 24 列模板");

        ResponseEntity<Map> reviewDetail = http.exchange(
                "/api/v1/review-cases/" + review.get("id"),
                HttpMethod.GET,
                new HttpEntity<>(writeHeaders("wecom-file-review-detail")),
                Map.class);
        assertThat(reviewDetail.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reviewDetail.getBody()).containsEntry("reason_code", WecomTrackingFileDraftService.FAILURE_REASON);
        assertThat(((List<?>) reviewDetail.getBody().get("allowed_actions")).stream()
                        .map(Object::toString)
                        .toList())
                .containsExactly("RESOLVE_MANUALLY", "DISMISS");

        ResponseEntity<Map> reinterpreted = http.exchange(
                "/api/v1/message-submissions/" + submissionId + "/reinterpret",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(), writeHeaders("wecom-file-reinterpret-invalid")),
                Map.class);
        assertThat(reinterpreted.getStatusCode()).isEqualTo(HttpStatus.OK);
        worker().poll();

        assertThat(downloadHits).hasValue(1);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM app.review_cases WHERE message_submission_id=? AND reason_code=?",
                        Long.class,
                        submissionId,
                        WecomTrackingFileDraftService.FAILURE_REASON))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM app.review_cases WHERE message_submission_id=? AND reason_code=? "
                                + "AND status='OPEN'",
                        Long.class,
                        submissionId,
                        WecomTrackingFileDraftService.FAILURE_REASON))
                .isEqualTo(1);
        Map<String, Object> currentReview = jdbc.queryForMap(
                "SELECT id, resolution_version FROM app.review_cases "
                        + "WHERE message_submission_id=? AND reason_code=? AND status='OPEN'",
                submissionId,
                WecomTrackingFileDraftService.FAILURE_REASON);
        ResponseEntity<Map> resolved = http.exchange(
                "/api/v1/review-cases/" + currentReview.get("id") + "/resolve",
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of(
                                "expected_version", currentReview.get("resolution_version"),
                                "note", "已联系履约方重新发送文件"),
                        writeHeaders("wecom-file-review-resolve")),
                Map.class);
        assertThat(resolved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resolved.getBody()).containsEntry("status", "RESOLVED");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM app.message_media WHERE submission_id=? AND download_status='AVAILABLE'",
                        Long.class,
                        submissionId))
                .isEqualTo(1);
    }

    @Test
    void downloadFailureIsRetriedAndCreatesReviewWithoutDraft() {
        responseStatus.set(503);
        responseBody.set("unavailable".getBytes(StandardCharsets.UTF_8));
        long submissionId = submissions.submit(fileMessage("WF-MSG-DOWN-001", mediaUrl, "single"));

        worker().poll();

        assertThat(downloadHits).hasValue(3);
        assertThat(jdbc.queryForObject(
                        "SELECT download_status FROM app.message_media WHERE submission_id=?",
                        String.class,
                        submissionId))
                .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject(
                        "SELECT last_error FROM app.async_tasks WHERE payload_ref=?",
                        String.class,
                        "submission:" + submissionId))
                .isEqualTo("WECOM_TRACKING_FILE_DOWNLOAD_FAILED");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM app.review_cases WHERE message_submission_id=? AND reason_code=?",
                        Long.class,
                        submissionId,
                        WecomTrackingFileDraftService.FAILURE_REASON))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM app.provider_tracking_drafts WHERE submission_id=?",
                        Long.class,
                        submissionId))
                .isZero();
    }

    @Test
    void oversizedFileIsRejectedFromContentLengthAndLeavesNoStoredPlaintext() {
        responseBody.set(encrypt(new byte[WecomMediaEvidenceService.MAX_MEDIA_BYTES + 1]));
        long submissionId = submissions.submit(fileMessage("WF-MSG-LARGE-001", mediaUrl, "single"));

        worker().poll();

        assertThat(jdbc.queryForObject(
                        "SELECT last_error FROM app.async_tasks WHERE payload_ref=?",
                        String.class,
                        "submission:" + submissionId))
                .isEqualTo("WECOM_TRACKING_FILE_TOO_LARGE");
        Map<String, Object> media = jdbc.queryForMap(
                "SELECT download_status, attempts, content_ref FROM app.message_media WHERE submission_id=?",
                submissionId);
        assertThat(media).containsEntry("download_status", "FAILED").containsEntry("attempts", 3);
        assertThat(media.get("content_ref")).isNull();
        assertThat(jdbc.queryForObject(
                        "SELECT detail->>'message' FROM app.review_cases "
                                + "WHERE message_submission_id=? AND reason_code=?",
                        String.class,
                        submissionId,
                        WecomTrackingFileDraftService.FAILURE_REASON))
                .contains("20MB");
    }

    @Test
    void partialFileDraftCarriesItsActualQuantityThroughHumanConfirmation() throws Exception {
        String exportId = generateExport("FX-WECOM-FILE-PARTIAL-001");
        responseBody.set(encrypt(fillPartialTracking(
                downloadExport(exportId), "1.000", "JDVA-WECOM-FILE-PARTIAL-001")));
        long submissionId = submissions.submit(fileMessage("WF-MSG-PARTIAL-001", mediaUrl, "single"));

        worker().poll();

        Map<String, Object> draft = jdbc.queryForMap(
                """
                SELECT d.id, d.revision, d.task_id, d.carrier_code, d.actual_quantity,
                       d.shipment_judgment, rc.resolution_version case_version
                FROM app.provider_tracking_drafts d
                JOIN app.review_cases rc ON rc.provider_tracking_draft_id=d.id
                WHERE d.submission_id=?
                """,
                submissionId);
        assertThat(draft)
                .containsEntry("shipment_judgment", "PARTIAL")
                .containsEntry("actual_quantity", 1);
        ResponseEntity<Map> detail = http.exchange(
                "/api/v1/tracking-drafts/" + draft.get("id"),
                HttpMethod.GET,
                new HttpEntity<>(writeHeaders("wecom-file-partial-detail")),
                Map.class);
        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(detail.getBody())
                .containsEntry("source", "WECOM_TRACKING_FILE")
                .containsEntry("confirmation_scope", "SINGLE_TASK")
                .containsEntry("actual_quantity", 1);

        ResponseEntity<Map> confirmed = batchConfirm(draft);

        assertThat(((Number) confirmed.getBody().get("success_count")).intValue()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM app.trackings WHERE tracking_number=?",
                        Long.class,
                        "JDVA-WECOM-FILE-PARTIAL-001"))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT shipped_quantity FROM app.shipment_items si "
                                + "JOIN app.fulfillment_export_items fei ON fei.shipment_id=si.shipment_id "
                                + "WHERE fei.fulfillment_export_id=?",
                        java.math.BigDecimal.class,
                        Long.parseLong(exportId)))
                .isEqualByComparingTo("1.000");
    }

    @Test
    void multiItemShipmentCreatesOneDraftAndOneTrackingAfterConfirmation() throws Exception {
        jdbc.queryForObject(
                "SELECT setval(pg_get_serial_sequence('app.fulfillments', 'id'), "
                        + "GREATEST((SELECT COALESCE(max(id), 0) FROM app.fulfillments), 1000), true)",
                Long.class);
        String orderRef = "FX-WECOM-FILE-MULTI-001";
        ResponseEntity<Map> uploaded = uploadRaw(
                "wecom-file-multi.csv",
                feixiangTwoLineCsv(orderRef),
                "source-import-wecom-file-multi-001");
        ResponseEntity<Map> exportResponse = confirmBatch(
                uploaded.getBody().get("id").toString(), "confirm-wecom-file-multi-001");
        String exportId = ((List<?>) exportResponse.getBody().get("generated_fulfillment_export_ids"))
                .getFirst()
                .toString();
        responseBody.set(encrypt(fillAllTracking(
                downloadExport(exportId), "JDVA-WECOM-FILE-MULTI-001")));
        long submissionId = submissions.submit(fileMessage("WF-MSG-MULTI-001", mediaUrl, "single"));

        worker().poll();

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM app.provider_tracking_drafts WHERE submission_id=?",
                        Long.class,
                        submissionId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT jsonb_array_length(task_candidates) FROM app.provider_tracking_drafts "
                                + "WHERE submission_id=?",
                        Integer.class,
                        submissionId))
                .isEqualTo(2);
        Map<String, Object> draft = jdbc.queryForMap(
                """
                SELECT d.id, d.revision, d.task_id, d.carrier_code,
                       rc.resolution_version case_version
                FROM app.provider_tracking_drafts d
                JOIN app.review_cases rc ON rc.provider_tracking_draft_id=d.id
                WHERE d.submission_id=?
                """,
                submissionId);
        assertThat(((Number) draft.get("task_id")).longValue()).isGreaterThan(127L);
        ResponseEntity<Map> detail = http.exchange(
                "/api/v1/tracking-drafts/" + draft.get("id"),
                HttpMethod.GET,
                new HttpEntity<>(writeHeaders("wecom-file-multi-detail")),
                Map.class);
        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(detail.getBody())
                .containsEntry("source", "WECOM_TRACKING_FILE")
                .containsEntry("confirmation_scope", "ATOMIC_SHIPMENT");
        ResponseEntity<Map> confirmed = batchConfirm(draft);
        assertThat(((Number) confirmed.getBody().get("success_count")).intValue()).isEqualTo(1);
        long shipmentId = jdbc.queryForObject(
                "SELECT min(shipment_id) FROM app.fulfillment_export_items WHERE fulfillment_export_id=?",
                Long.class,
                Long.parseLong(exportId));
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM app.trackings WHERE shipment_id=?",
                        Long.class,
                        shipmentId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM app.shipment_items WHERE shipment_id=? AND shipped_quantity IS NOT NULL",
                        Long.class,
                        shipmentId))
                .isEqualTo(2);
    }

    private WecomTrackingFileWorker worker() {
        return new WecomTrackingFileWorker(tasks, processor, draftService, true, 60, 0);
    }

    private String generateExport(String orderRef) {
        ResponseEntity<Map> uploaded = uploadRaw(
                "wecom-file.csv", feixiangSingleCsv(orderRef), "source-import-" + orderRef.toLowerCase());
        assertThat(uploaded.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<Map> confirmed = confirmBatch(
                uploaded.getBody().get("id").toString(), "confirm-" + orderRef.toLowerCase());
        assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.OK);
        return ((List<?>) confirmed.getBody().get("generated_fulfillment_export_ids"))
                .getFirst()
                .toString();
    }

    private ChannelMessageCommand fileMessage(String messageId, String url, String chatType) {
        String frame = "{\"cmd\":\"aibot_msg_callback\",\"headers\":{\"req_id\":\"req-file\"},\"body\":{"
                + "\"msgid\":\"" + messageId + "\",\"aibotid\":\"bot-1\",\"chattype\":\""
                + chatType + "\",\"from\":{\"userid\":\"user-file\"},\"msgtype\":\"file\","
                + "\"file\":{\"url\":\"" + url + "\",\"aeskey\":\"" + AES_KEY
                + "\",\"filename\":\"tracking.xlsx\"}}}";
        return new ChannelMessageCommand(
                "bot-1", "wecom-long-connection", "bot-1", messageId, "single:user-file",
                chatType, "user-file", "file", "", null, null, json(frame));
    }

    private JsonNode json(String raw) {
        try {
            return objectMapper.readTree(raw);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private byte[] downloadExport(String exportId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBasicAuth(OPERATOR, ADMIN_PASSWORD);
        headers.set("X-Operator", OPERATOR);
        ResponseEntity<byte[]> response = http.exchange(
                "/api/v1/fulfillment-exports/" + exportId + "/file",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                byte[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private byte[] fillTracking(byte[] instruction, String trackingNumber) throws Exception {
        return fillRows(instruction, null, trackingNumber, 1);
    }

    /** 人读格式的「部分发货」= 把数量改成实发数。 */
    private byte[] fillPartialTracking(byte[] instruction, String quantity, String trackingNumber) throws Exception {
        return fillRows(instruction, quantity, trackingNumber, 1);
    }

    private byte[] fillAllTracking(byte[] instruction, String trackingNumber) throws Exception {
        return fillRows(instruction, null, trackingNumber, Integer.MAX_VALUE);
    }

    private byte[] fillRows(byte[] instruction, String quantity, String trackingNumber, int rowLimit) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(instruction));
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.getSheetAt(0);
            var header = sheet.getRow(0);
            Map<String, Integer> columns = new java.util.LinkedHashMap<>();
            var fmt = new org.apache.poi.ss.usermodel.DataFormatter();
            for (int index = 0; index < header.getLastCellNum(); index++) {
                columns.put(fmt.formatCellValue(header.getCell(index)).strip(), index);
            }
            boolean human = columns.containsKey("运单号");
            int filled = 0;
            for (int index = 1; index <= sheet.getLastRowNum() && filled < rowLimit; index++) {
                var row = sheet.getRow(index);
                if (row == null) continue;
                filled++;
                if (human) {
                    if (quantity != null) put(row, columns.get("数量"), quantity);
                    put(row, columns.get("快递公司"), "京东物流");
                    put(row, columns.get("运单号"), trackingNumber);
                } else {
                    put(row, columns.get("结果"), quantity == null ? "SHIPPED" : "PARTIAL");
                    put(row, columns.get("实际发货数量"), quantity == null ? "3.000" : quantity);
                    put(row, columns.get("快递公司"), "京东物流");
                    put(row, columns.get("物流单号"), trackingNumber);
                    put(row, columns.get("发货时间"), "");
                }
            }
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private static void put(org.apache.poi.ss.usermodel.Row row, Integer index, String value) {
        if (index == null) return;
        var cell = row.getCell(index);
        if (cell == null) cell = row.createCell(index);
        cell.setCellValue(value == null ? "" : value);
    }

    private ResponseEntity<Map> uploadRaw(String filename, byte[] bytes, String idempotencyKey) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(bytes) {
            @Override public String getFilename() { return filename; }
        });
        body.add("import_mode", "NEW");
        HttpHeaders headers = writeHeaders(idempotencyKey);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return http.exchange(
                "/api/v1/import-batches/source-orders",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class);
    }

    private ResponseEntity<Map> confirmBatch(String batchId, String idempotencyKey) {
        return http.exchange(
                "/api/v1/import-batches/" + batchId + "/confirm",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(), writeHeaders(idempotencyKey)),
                Map.class);
    }

    private ResponseEntity<Map> batchConfirm(Map<String, Object> draft) {
        String suffix = draft.get("id").toString();
        Map<String, Object> line = new java.util.LinkedHashMap<>();
        line.put("draft_id", draft.get("id").toString());
        line.put("idempotency_key", "wecom-file-confirm-line-" + suffix);
        line.put("expected_draft_revision", ((Number) draft.get("revision")).longValue());
        line.put("expected_case_version", ((Number) draft.get("case_version")).longValue());
        line.put("task_id", draft.get("task_id").toString());
        line.put("carrier_code", draft.get("carrier_code").toString());
        if (draft.get("actual_quantity") != null) {
            line.put("actual_quantity", draft.get("actual_quantity").toString());
        }
        line.put("remark", "企微文件人工确认");
        HttpHeaders headers = writeHeaders("wecom-file-batch-confirm-" + suffix);
        return http.exchange(
                "/api/v1/tracking-drafts/batch-confirm",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("lines", List.of(line)), headers),
                Map.class);
    }

    private HttpHeaders writeHeaders(String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBasicAuth(OPERATOR, ADMIN_PASSWORD);
        headers.set("Idempotency-Key", idempotencyKey);
        headers.set("X-Request-Id", "req-" + idempotencyKey);
        headers.set("X-Operator", OPERATOR);
        return headers;
    }

    private byte[] feixiangSingleCsv(String orderRef) {
        String header = String.join(",", List.of(
                "订单号", "会员名称", "商品名称", "商品ID", "订单商品ID", "可发货数量",
                "收货人姓名", "收货人手机号", "收货人地址", "下单时间", "物流状态", "物流公司", "物流单号"));
        String row = orderRef + ",FX-MEMBER-001,子牧羊小腿,FX-PRODUCT-001," + orderRef
                + "-LINE,1,张三,13800000000,上海市浦东新区测试路1号,2026-08-11 10:00:00,,,\r\n";
        return ("\uFEFF" + header + "\r\n" + row).getBytes(StandardCharsets.UTF_8);
    }

    private byte[] feixiangTwoLineCsv(String orderRef) {
        String header = String.join(",", List.of(
                "订单号", "会员名称", "商品名称", "商品ID", "订单商品ID", "可发货数量",
                "收货人姓名", "收货人手机号", "收货人地址", "下单时间", "物流状态", "物流公司", "物流单号"));
        String first = orderRef + ",FX-MEMBER-001,子牧羊小腿,FX-PRODUCT-001," + orderRef
                + "-LINE-1,1,张三,13800000000,上海市浦东新区测试路1号,2026-08-11 10:00:00,,,";
        String second = orderRef + ",FX-MEMBER-001,子牧羊小腿,FX-PRODUCT-001," + orderRef
                + "-LINE-2,1,张三,13800000000,上海市浦东新区测试路1号,2026-08-11 10:00:00,,,";
        return ("\uFEFF" + header + "\r\n" + first + "\r\n" + second + "\r\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] encrypt(byte[] plaintext) {
        byte[] key = Base64.getDecoder().decode(AES_KEY + "=");
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(key, 0, 16));
            int padding = 32 - plaintext.length % 32;
            byte[] padded = Arrays.copyOf(plaintext, plaintext.length + padding);
            Arrays.fill(padded, plaintext.length, padded.length, (byte) padding);
            return cipher.doFinal(padded);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Path createMediaDir() {
        try {
            return Files.createTempDirectory("wecom-tracking-file-test-");
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void cleanMediaDir() throws IOException {
        if (Files.exists(MEDIA_DIR)) {
            try (var paths = Files.list(MEDIA_DIR)) {
                for (Path path : paths.toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }
}
