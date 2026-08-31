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
 * A12 回归用例：{@code SourceImportService#upload} 一批内混有「已存在订单」与「真正新订单」
 * 时，此前会因 {@code OrderCreateService#doCreate} 抛出 DUPLICATE_ORDER 而把整个
 * {@code @Transactional upload()} 标记 rollback-only，连真正的新订单也一并丢弃——这正是
 * 彩食鲜/飞象等在线拉取反复拉到「待发货」列表中新旧订单混杂时，全部被判定为
 * OK+0 新数据（无任何报错）的根因（2026-08-27 生产事故）。
 *
 * <p>修复：{@code upload()} 逐组落库前先以 {@code orderExists} 预检测，命中时该组整体标记
 * REJECTED（error_code=ORDER_ALREADY_EXISTS）并跳过，不再让异常升出事务——与
 * {@code importStructured} 既有的重复订单处理方式一致。
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "app.message-worker.enabled=false",
            "app.file-store.root=${java.io.tmpdir}/zimu-source-import-duplicate-test"
        })
class SourceImportDuplicateOrderApiTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    cn.zimu.fulfillment.connector.wecom.WecomConnectionManager ignoredWecomConnectionManager;

    /** 确认放行会走京东导出路由，租户标识缺一个就失败关闭；与 SourcePartialConfirmApiTest 同一组测试值。 */
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
    void mixedBatchAcceptsNewOrderAndRejectsDuplicateInsteadOfAbortingWholeBatch() throws Exception {
        long skuId = jdbc.queryForObject(
                "SELECT sku_id FROM app.provider_skus WHERE provider_sku_code='JD-SKU-000001'", Long.class);
        jdbc.update(
                "INSERT INTO app.source_channel_skus(source_channel,source_sku_ref,source_product_name,"
                        + "quantity_multiplier,sku_id,active) VALUES ('DAZHE','TEST-SKU-NEW-001','测试保温杯单品',1,?,true)",
                skuId);

        // 第一次上传：单独建一个订单，模拟「上一次成功批次」已经把它拉进来。
        ResponseEntity<Map> first = upload(singleRowWorkbook(
                "TEST-DUP-0001", "TEST-SKU-NEW-001", "测试保温杯单品", "测试收货人甲", "13800000001"),
                "dup-seed-001");
        assertThat(first.getStatusCode())
                .withFailMessage("seed upload body: %s", first.getBody())
                .isEqualTo(HttpStatus.CREATED);
        // 候选流水线（2026-08-31）：上传只建候选，订单在确认放行时创建。
        ResponseEntity<Map> seedConfirmed = confirm(
                String.valueOf(first.getBody().get("id")), "dup-seed-confirm-001");
        assertThat(seedConfirmed.getStatusCode())
                .withFailMessage("seed confirm body: %s", seedConfirmed.getBody())
                .isEqualTo(HttpStatus.OK);

        // 第二次上传：同一渠道再次拉取「待发货」列表，这次列表里新旧订单混在一起——
        // TEST-DUP-0001 已存在，TEST-NEW-0001 是真正的新单。
        ResponseEntity<Map> second = upload(twoRowWorkbook(), "dup-mixed-002");

        assertThat(second.getStatusCode())
                .withFailMessage("mixed upload body: %s", second.getBody())
                .isEqualTo(HttpStatus.CREATED);
        Map<String, Object> batch = second.getBody();
        Map<?, ?> counts = (Map<?, ?>) batch.get("row_counts");
        assertThat(counts.get("total")).isEqualTo(2);
        assertThat(counts.get("rejected")).isEqualTo(1);
        assertThat(counts.get("need_review")).isEqualTo(0);

        // 上传阶段重复行已被挡下且未毒化批次；新单候选就绪，确认放行后必须落库。
        ResponseEntity<Map> mixedConfirmed = confirm(
                String.valueOf(batch.get("id")), "dup-mixed-confirm-002");
        assertThat(mixedConfirmed.getStatusCode())
                .withFailMessage("mixed confirm body: %s", mixedConfirmed.getBody())
                .isEqualTo(HttpStatus.OK);
        Long newOrderCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM app.orders WHERE source_channel='DAZHE' AND source_ref='TEST-NEW-0001'",
                Long.class);
        assertThat(newOrderCount).isEqualTo(1L);

        // 重复订单所在行应标记为 REJECTED + ORDER_ALREADY_EXISTS，而不是让整批失败。
        ResponseEntity<Map> rowsResponse = http.exchange(
                "/api/v1/import-batches/" + batch.get("id") + "/rows?page=0&size=20",
                HttpMethod.GET,
                new HttpEntity<>(operatorHeaders()),
                Map.class);
        assertThat(rowsResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) rowsResponse.getBody().get("items");
        Map<String, Object> duplicateRow = rows.stream()
                .filter(row -> "TEST-DUP-0001".equals(row.get("source_order_ref")))
                .findFirst()
                .orElseThrow();
        assertThat(duplicateRow.get("status")).isEqualTo("REJECTED");
        assertThat(duplicateRow.get("error_code")).isEqualTo("ORDER_ALREADY_EXISTS");

        Map<String, Object> newRow = rows.stream()
                .filter(row -> "TEST-NEW-0001".equals(row.get("source_order_ref")))
                .findFirst()
                .orElseThrow();
        assertThat(newRow.get("status")).isEqualTo("ACCEPTED");
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
        headers.set("X-Operator", "duplicate-order-e2e");
        headers.set("X-Request-Id", "req-duplicate-order-e2e");
        return headers;
    }

    private byte[] singleRowWorkbook(
            String orderRef, String skuRef, String productName, String receiverName, String receiverPhone)
            throws Exception {
        return workbook(List.of(row(orderRef, skuRef, productName, receiverName, receiverPhone)));
    }

    private byte[] twoRowWorkbook() throws Exception {
        return workbook(List.of(
                row("TEST-DUP-0001", "TEST-SKU-NEW-001", "测试保温杯单品", "测试收货人甲", "13800000001"),
                row("TEST-NEW-0001", "TEST-SKU-NEW-001", "测试保温杯单品", "测试收货人乙", "13800000002")));
    }

    private List<String> row(
            String orderRef, String skuRef, String productName, String receiverName, String receiverPhone) {
        return List.of(
                orderRef, skuRef, "北京大者国风科技有限公司", productName, "待发货", "99.00", "1",
                receiverName, receiverPhone, "北京市朝阳区测试路1号", "",
                "2026-08-27 10:00:00", "2026-08-27 09:59:00", "", "");
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
