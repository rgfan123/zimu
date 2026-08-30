package cn.zimu.fulfillment.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import cn.zimu.fulfillment.message.AsyncTaskStore;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Workbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "app.source-order-intake-worker.enabled=false",
            "app.file-store.root=${java.io.tmpdir}/zimu-source-order-intake-test"
        })
class SourceOrderIntakeApiTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired AsyncTaskStore tasks;
    @Autowired SourceOrderIntakeProcessor processor;
    @Autowired SourceOrderIntakeService intake;
    @MockitoSpyBean SourceImportService imports;

    @AfterEach
    void removeTrustedTemplateFixtures() {
        jdbc.update("DELETE FROM app.source_template_profiles");
    }

    @Test
    void exhaustedWorkerRetriesMarkTheBusinessJobFailed() throws Exception {
        byte[] workbook = workbook(
                List.of("新订单编号", "客户姓名", "联系电话", "商品描述", "数量"),
                List.of("BROKEN-001", "张三", "13800000000", "羊肉礼盒", "1"));
        ResponseEntity<Map> submitted = submit("broken.xlsx", workbook, "DAZHE", "intake-broken-0001");
        long jobId = Long.parseLong(submitted.getBody().get("id").toString());
        jdbc.update(
                "UPDATE app.source_order_intake_jobs SET file_ref=? WHERE id=?",
                Path.of(System.getProperty("java.io.tmpdir"), "zimu-source-order-intake-test",
                                "source-order-intake", "missing.xlsx")
                        .toString(),
                jobId);

        new SourceOrderIntakeWorker(tasks, processor, intake, true, 30, 0).poll();

        assertThat(jdbc.queryForObject(
                        "SELECT status FROM app.source_order_intake_jobs WHERE id=?", String.class, jobId))
                .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM app.async_tasks WHERE payload_ref=?",
                        String.class,
                        "source-order-intake:" + jobId))
                .isEqualTo("FAILED");
    }

    @Test
    void failedJobOriginalCanBeDownloadedThroughTheControlledJobEndpoint() throws Exception {
        byte[] workbook = workbook(
                List.of("新订单编号", "客户姓名", "联系电话", "商品描述", "数量"),
                List.of("DOWNLOAD-001", "张三", "13800000000", "羊肉礼盒", "1"));
        ResponseEntity<Map> submitted = submit("失败订单.xlsx", workbook, "DAZHE", "intake-download-0001");
        long jobId = Long.parseLong(submitted.getBody().get("id").toString());
        jdbc.update("UPDATE app.source_order_intake_jobs SET status='FAILED' WHERE id=?", jobId);

        HttpHeaders downloadHeaders = new HttpHeaders();
        downloadHeaders.set("X-Operator", "intake-api-test");
        ResponseEntity<byte[]> downloaded = http.exchange(
                "/api/v1/source-order-intake-jobs/" + jobId + "/file",
                org.springframework.http.HttpMethod.GET,
                new HttpEntity<>(downloadHeaders),
                byte[].class);

        assertThat(downloaded.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(downloaded.getBody()).isEqualTo(workbook);
        assertThat(downloaded.getHeaders().getContentDisposition().getType()).isEqualTo("attachment");
        assertThat(downloaded.getHeaders().getContentDisposition().getFilename()).isEqualTo("失败订单.xlsx");
        assertThat(downloaded.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(downloaded.getHeaders().getCacheControl()).isEqualTo("no-store, private");
    }

    @Test
    void mimeExtensionAndMagicMustDescribeTheSameFormat() throws Exception {
        byte[] workbook = workbook(dazheHeaders(), dazheRow("DAZHE-MIME-001"));

        ResponseEntity<Map> response = submit(
                "dazhe.xlsx", workbook, MediaType.TEXT_PLAIN, "DAZHE", "intake-mime-0001");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().get("business_code")).isEqualTo("SOURCE_FILE_CONTENT_TYPE_MISMATCH");

        ResponseEntity<Map> disguisedWorkbook = submit(
                "dazhe.csv", workbook, MediaType.APPLICATION_OCTET_STREAM, "DAZHE", "intake-magic-0001");
        assertThat(disguisedWorkbook.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(disguisedWorkbook.getBody().get("business_code"))
                .isEqualTo("SOURCE_FILE_FORMAT_UNSUPPORTED");

        ResponseEntity<Map> arbitraryZip = submit(
                "fake.xlsx", arbitraryZip(), MediaType.APPLICATION_OCTET_STREAM, "DAZHE", "intake-zip-0001");
        assertThat(arbitraryZip.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(arbitraryZip.getBody().get("business_code"))
                .isEqualTo("SOURCE_FILE_FORMAT_UNSUPPORTED");

        ResponseEntity<Map> binaryCsv = submit(
                "binary.csv", new byte[] {(byte) 0x81}, MediaType.TEXT_PLAIN, "DAZHE", "intake-csv-0001");
        assertThat(binaryCsv.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(binaryCsv.getBody().get("business_code"))
                .isEqualTo("SOURCE_FILE_FORMAT_UNSUPPORTED");
    }

    @Test
    void damagedButIdentifiableWorkbookIsRetainedBeforeBusinessParsing() throws Exception {
        byte[] damagedWorkbook = damagedXlsxContainer();

        ResponseEntity<Map> submitted = submit(
                "damaged.xlsx", damagedWorkbook, "DAZHE", "intake-damaged-0001");

        assertThat(submitted.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        long jobId = Long.parseLong(submitted.getBody().get("id").toString());
        String fileRef = jdbc.queryForObject(
                "SELECT file_ref FROM app.source_order_intake_jobs WHERE id=?", String.class, jobId);
        assertThat(Files.readAllBytes(Path.of(fileRef))).isEqualTo(damagedWorkbook);

        new SourceOrderIntakeWorker(tasks, processor, intake, true, 30, 0).poll();
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM app.source_order_intake_jobs WHERE id=?", String.class, jobId))
                .isEqualTo("FAILED");

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Operator", "intake-api-test");
        ResponseEntity<byte[]> downloaded = http.exchange(
                "/api/v1/source-order-intake-jobs/" + jobId + "/file",
                org.springframework.http.HttpMethod.GET,
                new HttpEntity<>(headers),
                byte[].class);
        assertThat(downloaded.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(downloaded.getBody()).isEqualTo(damagedWorkbook);
    }

    @Test
    void unknownWorkbookIsRetainedBeforeExtractionAndDuplicateSubmissionReusesJob() throws Exception {
        byte[] workbook = workbook(
                List.of("新订单编号", "客户姓名", "联系电话", "商品描述", "数量"),
                List.of("NEW-001", "张三", "13800000000", "羊肉礼盒", "1"));

        ResponseEntity<Map> first = submit("unknown.xlsx", workbook, "DAZHE", "intake-unknown-0001");
        ResponseEntity<Map> replay = submit("unknown.xlsx", workbook, "DAZHE", "intake-unknown-0002");

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(replay.getBody().get("id")).isEqualTo(first.getBody().get("id"));
        long jobId = Long.parseLong(first.getBody().get("id").toString());
        Map<String, Object> stored = jdbc.queryForMap(
                "SELECT status, file_ref FROM app.source_order_intake_jobs WHERE id=?", jobId);
        assertThat(stored.get("status")).isEqualTo("RECEIVED");
        assertThat(Files.exists(Path.of(stored.get("file_ref").toString()))).isTrue();

        AsyncTaskStore.AsyncTask task = tasks.claim(
                        SourceOrderIntakeService.TASK_TYPE, "test-owner", Duration.ofSeconds(30))
                .orElseThrow();
        processor.process(task);
        tasks.succeed(task.id(), "test-owner");

        ResponseEntity<Map> result = http.getForEntity(
                "/api/v1/source-order-intake-jobs/" + jobId, Map.class);
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(result.getBody().get("status")).isEqualTo("NEEDS_EXTRACTION");
        assertThat(result.getBody().get("error_code")).isEqualTo("TEMPLATE_FINGERPRINT_NOT_FOUND");
    }

    @Test
    void sameIdempotencyKeyRejectsDifferentFileContent() throws Exception {
        byte[] firstWorkbook = workbook(
                List.of("新订单编号", "客户姓名", "联系电话", "商品描述", "数量"),
                List.of("IDEMPOTENT-001", "张三", "13800000000", "羊肉礼盒", "1"));
        byte[] differentWorkbook = workbook(
                List.of("新订单编号", "客户姓名", "联系电话", "商品描述", "数量"),
                List.of("IDEMPOTENT-002", "李四", "13900000000", "牛肉礼盒", "2"));

        ResponseEntity<Map> first = submit(
                "first.xlsx", firstWorkbook, "DAZHE", "intake-same-key-0001");
        ResponseEntity<Map> conflict = submit(
                "different.xlsx", differentWorkbook, "DAZHE", "intake-same-key-0001");

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.getBody().get("business_code")).isEqualTo("IDEMPOTENCY_CONFLICT");
    }

    @Test
    void domainFileSizeLimitReturnsAStableBusinessError() {
        byte[] oversized = new byte[(int) SourceOrderIntakeFileStore.MAX_BYTES + 1];

        ResponseEntity<Map> response = submit(
                "oversized.csv", oversized, MediaType.TEXT_PLAIN, "DAZHE", "intake-size-0001");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody().get("business_code")).isEqualTo("SOURCE_FILE_TOO_LARGE");
    }

    @Test
    void knownWorkbookIsImportedByTheBackgroundProcessor() throws Exception {
        List<String> headers = List.of(
                "渠道订单号", "主商品编码", "供应商商品名称", "商品名称", "订单商品状态",
                "采购单价(元)", "商品数量", "收货人", "收货人手机", "收货人详细地址",
                "预计到货时间", "渠道下单时间", "渠道支付时间", "快递单号", "快递公司");
        byte[] workbook = workbook(headers, List.of(
                "DAZHE-ASYNC-001", "DZ-SKU-001", "羊肉礼盒", "羊肉礼盒", "待发货",
                "99", "1", "李四", "13900000000", "北京市朝阳区测试路 1 号",
                "", "2026-08-27 08:00:00", "2026-08-27 08:01:00", "", ""));
        ResponseEntity<Map> submitted = submit("dazhe.xlsx", workbook, "DAZHE", "intake-known-0001");
        long jobId = Long.parseLong(submitted.getBody().get("id").toString());

        AsyncTaskStore.AsyncTask task = tasks.claim(
                        SourceOrderIntakeService.TASK_TYPE, "known-owner", Duration.ofSeconds(30))
                .orElseThrow();
        processor.process(task);
        tasks.succeed(task.id(), "known-owner");

        Map<String, Object> job = jdbc.queryForMap(
                "SELECT status, import_batch_id FROM app.source_order_intake_jobs WHERE id=?", jobId);
        assertThat(job.get("status")).isEqualTo("SUCCEEDED");
        assertThat(job.get("import_batch_id")).isNotNull();
        assertThat(jdbc.queryForObject(
                "SELECT source_channel FROM app.import_batches WHERE id=?",
                String.class,
                job.get("import_batch_id"))).isEqualTo("DAZHE");
    }

    @Test
    void legacyXlsWorkbookUsesTheWorkbookParserInsteadOfCsv() throws Exception {
        List<String> headers = dazheHeaders();
        byte[] workbook = legacyWorkbook(headers, dazheRow("DAZHE-XLS-001"));
        ResponseEntity<Map> submitted = submit("dazhe.xls", workbook, "DAZHE", "intake-xls-0001");
        long jobId = Long.parseLong(submitted.getBody().get("id").toString());

        AsyncTaskStore.AsyncTask task = tasks.claim(
                        SourceOrderIntakeService.TASK_TYPE, "xls-owner", Duration.ofSeconds(30))
                .orElseThrow();
        processor.process(task);
        tasks.succeed(task.id(), "xls-owner");

        Map<String, Object> job = jdbc.queryForMap(
                "SELECT status, import_batch_id FROM app.source_order_intake_jobs WHERE id=?", jobId);
        assertThat(job.get("status")).isEqualTo("SUCCEEDED");
        assertThat(job.get("import_batch_id")).isNotNull();
    }

    @Test
    void trustedTemplateAutomaticallyConfirmsAndRunsThePairedOutboundStep() throws Exception {
        clearInvocations(imports);
        upsertReadyDazheMapping("DZ-SKU-AUTO-READY");
        String suffix = java.util.UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        byte[] firstWorkbook = workbook(
                dazheHeaders(),
                dazheRow("DAZHE-TRUST-FIRST-" + suffix, "DZ-SKU-AUTO-READY", "羊小腿自动放行测试"));
        ResponseEntity<Map> first = uploadSource(
                "dazhe-trust-first.xlsx", firstWorkbook, "ticket-04-trust-first-" + suffix);
        long firstBatchId = Long.parseLong(first.getBody().get("id").toString());
        assertThat(confirmSourceBatch(firstBatchId, "ticket-04-confirm-first-" + suffix).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> trusted = trustTemplate(
                firstBatchId, "ticket-04-trust-template-" + suffix);
        assertThat(trusted.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(trusted.getBody())
                .containsEntry("status", "TRUSTED")
                .containsEntry("source_channel", "DAZHE");

        byte[] nextWorkbook = workbook(
                dazheHeaders(),
                dazheRow("DAZHE-TRUST-NEXT-" + suffix, "DZ-SKU-AUTO-READY", "羊小腿自动放行测试"));
        ResponseEntity<Map> submitted = submit(
                "dazhe-trust-next.xlsx", nextWorkbook, "DAZHE", "ticket-04-auto-intake-" + suffix);
        long jobId = Long.parseLong(submitted.getBody().get("id").toString());
        AsyncTaskStore.AsyncTask task = tasks.claim(
                        SourceOrderIntakeService.TASK_TYPE, "auto-release-owner", Duration.ofSeconds(30))
                .orElseThrow();
        processor.process(task);
        tasks.succeed(task.id(), "auto-release-owner");

        Map<String, Object> job = jdbc.queryForMap(
                "SELECT status, import_batch_id FROM app.source_order_intake_jobs WHERE id=?", jobId);
        assertThat(job.get("status")).isEqualTo("SUCCEEDED");
        long batchId = ((Number) job.get("import_batch_id")).longValue();
        assertThat(jdbc.queryForObject(
                        "SELECT confirmed_at IS NOT NULL FROM app.import_batches WHERE id=?",
                        Boolean.class,
                        batchId))
                .isTrue();
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*) FROM app.fulfillment_export_items fei
                        JOIN app.raw_import_rows rir ON rir.id=fei.raw_import_row_id
                        WHERE rir.import_batch_id=?
                        """,
                        Integer.class,
                        batchId))
                .isPositive();
        verify(imports).submitJdOutboundsForSourceBatch(org.mockito.ArgumentMatchers.eq(batchId), any());

        // 模拟“确认已提交、出站步骤或任务收尾前进程中断”后的同任务重入；
        // confirm 会幂等重放，但配对出站步骤仍须再次进入自身的幂等/围栏逻辑。
        jdbc.update("UPDATE app.source_order_intake_jobs SET status='PROCESSING' WHERE id=?", jobId);
        processor.process(task);
        verify(imports, times(2))
                .submitJdOutboundsForSourceBatch(org.mockito.ArgumentMatchers.eq(batchId), any());
    }

    @Test
    void trustedTemplateWithUnreadySkuStopsBeforeOrdersAndOutbound() throws Exception {
        clearInvocations(imports);
        upsertReadyDazheMapping("DZ-SKU-AUTO-TRUST-SEED");
        long blockedSkuId = upsertDazheSkuWithoutProviderMapping("DZ-SKU-AUTO-BLOCKED");
        String suffix = java.util.UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        ResponseEntity<Map> first = uploadSource(
                "dazhe-block-trust-first.xlsx",
                workbook(
                        dazheHeaders(),
                        dazheRow(
                                "DAZHE-BLOCK-TRUST-FIRST-" + suffix,
                                "DZ-SKU-AUTO-TRUST-SEED",
                                "羊小腿自动阻断模板")),
                "ticket-04-block-trust-first-" + suffix);
        long firstBatchId = Long.parseLong(first.getBody().get("id").toString());
        confirmSourceBatch(firstBatchId, "ticket-04-block-confirm-first-" + suffix);
        trustTemplate(firstBatchId, "ticket-04-block-trust-template-" + suffix);

        ResponseEntity<Map> submitted = submit(
                "dazhe-block-next.xlsx",
                workbook(
                        dazheHeaders(),
                        dazheRow(
                                "DAZHE-BLOCK-NEXT-" + suffix,
                                "DZ-SKU-AUTO-BLOCKED",
                                "羊小腿自动阻断模板")),
                "DAZHE",
                "ticket-04-block-auto-intake-" + suffix);
        long jobId = Long.parseLong(submitted.getBody().get("id").toString());
        AsyncTaskStore.AsyncTask task = tasks.claim(
                        SourceOrderIntakeService.TASK_TYPE, "auto-block-owner", Duration.ofSeconds(30))
                .orElseThrow();
        processor.process(task);
        tasks.succeed(task.id(), "auto-block-owner");

        Map<String, Object> job = jdbc.queryForMap(
                "SELECT status, error_code, import_batch_id FROM app.source_order_intake_jobs WHERE id=?",
                jobId);
        assertThat(job)
                .containsEntry("status", "NEEDS_REVIEW")
                .containsEntry("error_code", "IMPORT_BATCH_BLOCKED");
        long batchId = ((Number) job.get("import_batch_id")).longValue();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM app.orders WHERE source_import_batch_id=?",
                        Integer.class,
                        batchId))
                .isZero();
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*) FROM app.fulfillment_export_items fei
                        JOIN app.raw_import_rows rir ON rir.id=fei.raw_import_row_id
                        WHERE rir.import_batch_id=?
                        """,
                        Integer.class,
                        batchId))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM app.provider_skus WHERE sku_id=? AND active",
                        Integer.class,
                        blockedSkuId))
                .isZero();
    }

    private ResponseEntity<Map> submit(String filename, byte[] bytes, String sourceChannel, String key) {
        return submit(filename, bytes, MediaType.APPLICATION_OCTET_STREAM, sourceChannel, key);
    }

    private ResponseEntity<Map> submit(
            String filename, byte[] bytes, MediaType partContentType, String sourceChannel, String key) {
        ByteArrayResource resource = new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(partContentType);
        form.add("file", new HttpEntity<>(resource, partHeaders));
        form.add("source_channel", sourceChannel);
        form.add("import_mode", "NEW");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.set("Idempotency-Key", key);
        headers.set("X-Operator", "intake-api-test");
        return http.postForEntity(
                "/api/v1/source-order-intake-jobs",
                new HttpEntity<>(form, headers),
                Map.class);
    }

    private ResponseEntity<Map> uploadSource(String filename, byte[] bytes, String key) {
        ByteArrayResource resource = new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", resource);
        form.add("import_mode", "NEW");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.set("Idempotency-Key", key);
        headers.set("X-Operator", "intake-api-test");
        return http.postForEntity(
                "/api/v1/import-batches/source-orders",
                new HttpEntity<>(form, headers),
                Map.class);
    }

    private ResponseEntity<Map> confirmSourceBatch(long batchId, String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", key);
        headers.set("X-Operator", "intake-api-test");
        return http.postForEntity(
                "/api/v1/import-batches/" + batchId + "/confirm",
                new HttpEntity<>(null, headers),
                Map.class);
    }

    private ResponseEntity<Map> trustTemplate(long batchId, String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", key);
        headers.set("X-Operator", "intake-api-test");
        return http.postForEntity(
                "/api/v1/import-batches/" + batchId + "/trust-template",
                new HttpEntity<>(null, headers),
                Map.class);
    }

    private byte[] workbook(List<String> headers, List<String> values) throws Exception {
        return workbookBytes(new XSSFWorkbook(), headers, values);
    }

    private byte[] legacyWorkbook(List<String> headers, List<String> values) throws Exception {
        return workbookBytes(new HSSFWorkbook(), headers, values);
    }

    private byte[] workbookBytes(Workbook workbook, List<String> headers, List<String> values) throws Exception {
        try (workbook; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
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

    private byte[] arbitraryZip() throws Exception {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
                ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("not-a-workbook.txt"));
            zip.write("fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.finish();
            return output.toByteArray();
        }
    }

    private byte[] damagedXlsxContainer() throws Exception {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
                ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write("not valid content types xml".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("xl/workbook.xml"));
            zip.write("not valid workbook xml".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.finish();
            return output.toByteArray();
        }
    }

    private List<String> dazheHeaders() {
        return List.of(
                "渠道订单号", "主商品编码", "供应商商品名称", "商品名称", "订单商品状态",
                "采购单价(元)", "商品数量", "收货人", "收货人手机", "收货人详细地址",
                "预计到货时间", "渠道下单时间", "渠道支付时间", "快递单号", "快递公司");
    }

    private List<String> dazheRow(String orderNo) {
        return dazheRow(orderNo, "DZ-SKU-001", "羊肉礼盒");
    }

    private List<String> dazheRow(String orderNo, String sourceSkuRef, String productName) {
        return List.of(
                orderNo, sourceSkuRef, productName, productName, "待发货",
                "99", "1", "李四", "13900000000", "北京市朝阳区测试路 1 号",
                "", "2026-08-27 08:00:00", "2026-08-27 08:01:00", "", "");
    }

    private void upsertReadyDazheMapping(String sourceSkuRef) {
        long skuId = jdbc.queryForObject(
                """
                SELECT sku_id FROM app.source_channel_skus
                WHERE source_channel='WECOM' AND source_sku_ref='WECOM-SKU-TP-001'
                """,
                Long.class);
        jdbc.update(
                """
                INSERT INTO app.source_channel_skus
                    (source_channel, source_sku_ref, source_product_name, source_specification,
                     quantity_multiplier, sku_id, active)
                VALUES ('DAZHE', ?, '羊小腿自动放行测试', '标准箱', 1.000, ?, TRUE)
                ON CONFLICT (source_channel, source_sku_ref) DO UPDATE
                SET sku_id=EXCLUDED.sku_id, quantity_multiplier=1.000, active=TRUE
                """,
                sourceSkuRef,
                skuId);
    }

    private long upsertDazheSkuWithoutProviderMapping(String sourceSkuRef) {
        long providerId = jdbc.queryForObject(
                "SELECT id FROM app.fulfillment_providers WHERE provider_code='TP'",
                Long.class);
        long productId = jdbc.queryForObject(
                """
                INSERT INTO app.products(product_code, product_name, active)
                VALUES ('PROD-TICKET04-AUTO-BLOCKED', '自动放行缺履约映射商品', TRUE)
                ON CONFLICT (product_code) DO UPDATE SET active=TRUE
                RETURNING id
                """,
                Long.class);
        long sequence = jdbc.queryForObject("SELECT nextval('app.sku_code_seq')", Long.class);
        String skuCode = "SKU-TP-" + String.format("%06d", sequence);
        long skuId = jdbc.queryForObject(
                """
                INSERT INTO app.skus
                    (sku_sequence_no, sku_code, product_id, fulfillment_provider_id,
                     specification, unit, net_content_value, net_content_unit,
                     package_count, package_unit, active)
                VALUES (?, ?, ?, ?, '500g/件', '件', 500, 'g', 1, '件', TRUE)
                RETURNING id
                """,
                Long.class,
                sequence,
                skuCode,
                productId,
                providerId);
        jdbc.update(
                """
                INSERT INTO app.source_channel_skus
                    (source_channel, source_sku_ref, source_product_name, source_specification,
                     quantity_multiplier, sku_id, active)
                VALUES ('DAZHE', ?, '羊小腿自动阻断模板', '标准箱', 1.000, ?, TRUE)
                ON CONFLICT (source_channel, source_sku_ref) DO UPDATE
                SET sku_id=EXCLUDED.sku_id, quantity_multiplier=1.000, active=TRUE
                """,
                sourceSkuRef,
                skuId);
        return skuId;
    }
}
