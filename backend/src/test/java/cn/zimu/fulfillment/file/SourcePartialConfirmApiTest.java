package cn.zimu.fulfillment.file;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
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
 * 部分确认：批次里有阻断行时，就绪的行照发，阻断行留在批次里等补做。
 *
 * <p>旧闸门是全有或全无——一行有问题整批不能确认（IMPORT_BATCH_BLOCKED）。2026-08-28
 * 生产实例：一批 5 行里 4 张就绪新单被 1 行挡住。阻断行的修复往往要等外部信息，
 * 就绪的货没有理由陪着一起等。
 *
 * <p>同时钉住补做闭环：阻断行被修好后再次确认，新就绪的行必须被捡起来，不能变成孤儿。
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "app.message-worker.enabled=false",
            "app.file-store.root=${java.io.tmpdir}/zimu-source-partial-confirm-test"
        })
class SourcePartialConfirmApiTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    @org.springframework.test.context.bean.override.mockito.MockitoBean
    cn.zimu.fulfillment.connector.wecom.WecomConnectionManager ignoredWecomConnectionManager;

    /** 京东导单需要租户标识，缺一个就失败关闭；与 ExcelClosedLoopApiTest 用同一组测试值。 */
    @org.junit.jupiter.api.BeforeEach
    void seedJdProviderIdentifiers() {
        jdbc.update(
                """
                UPDATE app.fulfillment_providers
                SET config = config || ?::jsonb
                WHERE provider_type='JD_WAREHOUSE'
                """,
                """
                {"sourceNo":"ISV0020000000079","ownerNo":"EBU4418056064528",
                 "shopNo":"ESP0020008943717","customerCode":"010K5064550",
                 "warehouseNo":"118085840","carrierNo":"CYS0000010",
                 "pin":"京诚乾元01","salesPlatformSource":"6","townRequired":false}
                """);
    }

    @Test
    @SuppressWarnings("unchecked")
    void confirmsReadyRowsAndLeavesBlockedRowsInTheBatchForLaterCompletion() throws Exception {
        long skuId = jdbc.queryForObject(
                "SELECT sku_id FROM app.provider_skus WHERE provider_sku_code='JD-SKU-000001'", Long.class);
        // 只给 A 行的来源 SKU 建映射；B 行的 ref 没有映射，会落成待复核。
        jdbc.update(
                "INSERT INTO app.source_channel_skus(source_channel,source_sku_ref,source_product_name,"
                        + "quantity_multiplier,sku_id,active) VALUES ('DAZHE','TEST-PARTIAL-READY','测试保温杯单品',1,?,true)",
                skuId);

        ResponseEntity<Map> uploaded = upload(twoRowWorkbook(), "partial-confirm-001");
        assertThat(uploaded.getStatusCode())
                .withFailMessage("upload body: %s", uploaded.getBody())
                .isEqualTo(HttpStatus.CREATED);
        String batchId = String.valueOf(uploaded.getBody().get("id"));

        // 前置：确实是「一行就绪、一行阻断」的混合批次，否则本用例证明不了任何事。
        Map<String, Object> beforeConfirm = getBatch(batchId);
        Map<String, Object> readiness = (Map<String, Object>) beforeConfirm.get("confirm_readiness");
        assertThat(readiness)
                .withFailMessage("批次详情必须带确认闸门判据: %s", beforeConfirm)
                .isNotNull();
        assertThat(readiness.get("ready_rows")).isEqualTo(1);
        assertThat(readiness.get("blocked_rows")).isEqualTo(1);
        assertThat(readiness.get("confirmable")).isEqualTo(true);
        assertThat(readiness.get("partial")).isEqualTo(true);

        // 阻断原因要能看到，不是只给一个数字。
        List<Map<String, Object>> blockers = (List<Map<String, Object>>) readiness.get("blockers");
        assertThat(blockers).hasSize(1);
        assertThat(String.valueOf(blockers.getFirst().get("source_order_ref"))).isEqualTo("TEST-PARTIAL-B");
        assertThat(String.valueOf(blockers.getFirst().get("reason"))).isNotBlank();

        // 旧闸门在这里会抛 IMPORT_BATCH_BLOCKED；现在应当放行就绪的那一行。
        ResponseEntity<Map> confirmed = confirm(batchId, "partial-confirm-go-001");
        assertThat(confirmed.getStatusCode())
                .withFailMessage("confirm body: %s", confirmed.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(confirmed.getBody().get("confirmed_at")).isNotNull();

        // 被跳过的行必须当场报出来，否则部分确认等于静默丢单。
        List<Map<String, Object>> skipped = (List<Map<String, Object>>) confirmed.getBody().get("skipped_rows");
        assertThat(skipped).hasSize(1);
        assertThat(String.valueOf(skipped.getFirst().get("source_order_ref"))).isEqualTo("TEST-PARTIAL-B");

        // 就绪行真的进了履约：订单已建，且已落到导出或发货批次上。
        assertThat(rowStatus(batchId, "TEST-PARTIAL-READY-A")).isEqualTo("ACCEPTED");
        assertThat(coveredRows(batchId))
                .withFailMessage("就绪行必须进入履约导出或发货批次")
                .isEqualTo(1L);

        // 阻断行原地不动：状态与复核事项都不许被确认动作改掉。
        assertThat(rowStatus(batchId, "TEST-PARTIAL-B")).isEqualTo("NEED_REVIEW");

        // ---------- 补做：阻断行修好后，新就绪的行必须被捡起来 ----------
        long caseId = openSkuReviewCaseId(batchId);
        ResponseEntity<Map> resolved = resolveSku(caseId, skuId, "TEST-PARTIAL-UNMAPPED");
        assertThat(resolved.getStatusCode())
                .withFailMessage("resolve-sku body: %s", resolved.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(rowStatus(batchId, "TEST-PARTIAL-B"))
                .withFailMessage("复核解决后该行应转为已接收")
                .isEqualTo("ACCEPTED");

        // 补做必须用不同的幂等键：沿用首次确认的键会被判为重放，补做会静默变成空操作。
        Map<String, Object> beforeReconfirm = getBatch(batchId);
        Map<String, Object> reconfirmReadiness =
                (Map<String, Object>) beforeReconfirm.get("confirm_readiness");
        assertThat(reconfirmReadiness.get("pending_rows"))
                .withFailMessage("修好的行应当变成待发货: %s", reconfirmReadiness)
                .isEqualTo(1);

        ResponseEntity<Map> reconfirmed = confirm(batchId, "partial-confirm-followup-001");
        assertThat(reconfirmed.getStatusCode())
                .withFailMessage("reconfirm body: %s", reconfirmed.getBody())
                .isEqualTo(HttpStatus.OK);

        // 两行都进了履约，补做没留下孤儿。
        assertThat(coveredRows(batchId))
                .withFailMessage("补做后两行都应进入履约导出或发货批次")
                .isEqualTo(2L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void refusesConfirmationWhenEveryRowIsBlocked() throws Exception {
        // 没有任何就绪行时仍应拒绝：部分确认不是「无论如何都放行」。
        ResponseEntity<Map> uploaded = upload(
                workbook(List.of(row("TEST-ALLBLOCKED-1", "TEST-PARTIAL-UNMAPPED-2", "测试保温杯单品", "收货人甲", "13800000101"))),
                "partial-confirm-blocked-001");
        assertThat(uploaded.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String batchId = String.valueOf(uploaded.getBody().get("id"));

        Map<String, Object> readiness = (Map<String, Object>) getBatch(batchId).get("confirm_readiness");
        assertThat(readiness.get("ready_rows")).isEqualTo(0);
        assertThat(readiness.get("confirmable")).isEqualTo(false);

        ResponseEntity<Map> confirmed = confirm(batchId, "partial-confirm-blocked-go-001");
        assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(String.valueOf(confirmed.getBody().get("business_code"))).isEqualTo("IMPORT_BATCH_BLOCKED");
    }

    // ---------- helpers ----------

    private Map<String, Object> getBatch(String batchId) {
        ResponseEntity<Map> response = http.exchange(
                "/api/v1/import-batches/" + batchId,
                HttpMethod.GET,
                new HttpEntity<>(operatorHeaders()),
                Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private ResponseEntity<Map> confirm(String batchId, String idempotencyKey) {
        HttpHeaders headers = operatorHeaders();
        headers.set("Idempotency-Key", idempotencyKey);
        return http.exchange(
                "/api/v1/import-batches/" + batchId + "/confirm",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(), headers),
                Map.class);
    }

    private ResponseEntity<Map> resolveSku(long caseId, long skuId, String sourceSkuRef) {
        HttpHeaders headers = operatorHeaders();
        headers.set("Idempotency-Key", "partial-confirm-resolve-" + caseId);
        headers.setContentType(MediaType.APPLICATION_JSON);
        Long version = jdbc.queryForObject(
                "SELECT resolution_version FROM app.review_cases WHERE id=?", Long.class, caseId);
        return http.exchange(
                "/api/v1/review-cases/" + caseId + "/resolve-sku",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "expected_version", version,
                        "sku_id", String.valueOf(skuId),
                        "source_channel", "DAZHE",
                        "source_sku_ref", sourceSkuRef,
                        "quantity_multiplier", "1"),
                        headers),
                Map.class);
    }

    /** 该批次上仍未关闭的 SKU 映射复核事项。 */
    private long openSkuReviewCaseId(String batchId) {
        List<Long> ids = jdbc.queryForList(
                """
                SELECT DISTINCT rc.id
                FROM app.review_cases rc
                JOIN app.raw_import_rows rir ON rir.order_id=rc.order_id
                WHERE rir.import_batch_id=? AND rc.status='OPEN'
                  AND rc.reason_code IN ('SKU_MAPPING_REQUIRED', 'SKU_MAPPING_CONFLICT')
                ORDER BY rc.id
                """,
                Long.class,
                Long.parseLong(batchId));
        assertThat(ids).withFailMessage("应存在待解决的 SKU 映射复核事项").isNotEmpty();
        return ids.getFirst();
    }

    private String rowStatus(String batchId, String sourceOrderRef) {
        return jdbc.queryForObject(
                "SELECT status FROM app.raw_import_rows WHERE import_batch_id=? AND source_order_ref=?",
                String.class,
                Long.parseLong(batchId),
                sourceOrderRef);
    }

    /** 已进入履约导出或发货批次的已接收行数。 */
    private long coveredRows(String batchId) {
        return jdbc.queryForObject(
                """
                WITH raw_line_links AS (
                    SELECT rir.id raw_row_id, rir.order_line_id
                    FROM app.raw_import_rows rir
                    WHERE rir.import_batch_id=? AND rir.order_line_id IS NOT NULL
                    UNION
                    SELECT rirol.raw_import_row_id, rirol.order_line_id
                    FROM app.raw_import_row_order_lines rirol
                    JOIN app.raw_import_rows rir ON rir.id=rirol.raw_import_row_id
                    WHERE rir.import_batch_id=?
                )
                SELECT count(DISTINCT rir.id)
                FROM app.raw_import_rows rir
                JOIN raw_line_links rll ON rll.raw_row_id=rir.id
                WHERE rir.import_batch_id=? AND rir.status='ACCEPTED'
                  AND (
                    EXISTS (SELECT 1 FROM app.fulfillment_export_items fei
                            WHERE fei.raw_import_row_id=rir.id AND fei.order_line_id=rll.order_line_id)
                    OR EXISTS (SELECT 1 FROM app.shipment_items si
                               JOIN app.fulfillments f ON f.id=si.fulfillment_id
                               WHERE f.order_line_id=rll.order_line_id)
                  )
                """,
                Long.class,
                Long.parseLong(batchId),
                Long.parseLong(batchId),
                Long.parseLong(batchId));
    }

    private ResponseEntity<Map> upload(byte[] bytes, String idempotencyKey) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return "京诚乾元发货单.xlsx";
            }
        });
        body.add("import_mode", "NEW");
        HttpHeaders headers = operatorHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.set("Idempotency-Key", idempotencyKey);
        return http.exchange(
                "/api/v1/import-batches/source-orders",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class);
    }

    private HttpHeaders operatorHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Operator", "partial-confirm-e2e");
        headers.set("X-Request-Id", "req-partial-confirm-e2e");
        return headers;
    }

    private byte[] twoRowWorkbook() throws Exception {
        return workbook(List.of(
                row("TEST-PARTIAL-READY-A", "TEST-PARTIAL-READY", "测试保温杯单品", "测试收货人甲", "13800000001"),
                row("TEST-PARTIAL-B", "TEST-PARTIAL-UNMAPPED", "测试未映射单品", "测试收货人乙", "13800000002")));
    }

    private List<String> row(
            String orderRef, String skuRef, String productName, String receiverName, String receiverPhone) {
        return List.of(
                orderRef, skuRef, "北京大者国风科技有限公司", productName, "待发货", "99.00", "1",
                receiverName, receiverPhone, "北京市朝阳区测试路1号", "",
                "2026-08-28 10:00:00", "2026-08-28 09:59:00", "", "");
    }

    private byte[] workbook(List<List<String>> values) throws Exception {
        List<String> headers = List.of(
                "渠道订单号", "主商品编码", "供应商商品名称", "商品名称", "订单商品状态",
                "采购单价（元）", "商品数量", "收货人", "收货人手机", "收货人详细地址",
                "预计到货时间", "渠道下单时间", "渠道支付时间", "快递单号", "快递公司");
        try (XSSFWorkbook workbook = new XSSFWorkbook();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("订单列表");
            var header = sheet.createRow(0);
            for (int column = 0; column < headers.size(); column++) {
                header.createCell(column).setCellValue(headers.get(column));
            }
            for (int rowIndex = 0; rowIndex < values.size(); rowIndex++) {
                var dataRow = sheet.createRow(rowIndex + 1);
                for (int column = 0; column < values.get(rowIndex).size(); column++) {
                    dataRow.createCell(column).setCellValue(values.get(rowIndex).get(column));
                }
            }
            workbook.write(output);
            return output.toByteArray();
        }
    }
}
