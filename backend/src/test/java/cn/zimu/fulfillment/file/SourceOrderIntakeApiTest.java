package cn.zimu.fulfillment.file;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zimu.fulfillment.message.AsyncTaskStore;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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

    private ResponseEntity<Map> submit(String filename, byte[] bytes, String sourceChannel, String key) {
        ByteArrayResource resource = new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", resource);
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

    private byte[] workbook(List<String> headers, List<String> values) throws Exception {
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
}
