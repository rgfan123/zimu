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

/** 大者十五列表格经公开上传接口进入来源订单批次。 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "app.message-worker.enabled=false",
            "app.file-store.root=${java.io.tmpdir}/zimu-wangqi-source-import-test"
        })
class WangqiSourceOrderImportApiTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    cn.zimu.fulfillment.connector.wecom.WecomConnectionManager ignoredWecomConnectionManager;

    @Test
    @SuppressWarnings("unchecked")
    void exactFifteenColumnWorkbookImportsTwoOrdersAndSkipsPurchasePriceSummary() throws Exception {
        // 即使错误维护了普通 SKU 映射，名称明确的礼包也必须等待 bundle mapping，禁止降级 SINGLE 误发。
        long skuId = jdbc.queryForObject(
                "SELECT sku_id FROM app.provider_skus WHERE provider_sku_code='JD-SKU-000001'", Long.class);
        jdbc.update(
                "INSERT INTO app.source_channel_skus(source_channel,source_sku_ref,source_product_name,"
                        + "quantity_multiplier,sku_id,active) VALUES ('DAZHE','P26011900044','错误普通映射',1,?,true)",
                skuId);
        ResponseEntity<Map> uploaded = upload(wangqiWorkbook());

        assertThat(uploaded.getStatusCode())
                .withFailMessage("upload body: %s", uploaded.getBody())
                .isEqualTo(HttpStatus.CREATED);
        Map<String, Object> batch = uploaded.getBody();
        assertThat(batch).containsEntry("source_channel", "DAZHE");
        Map<?, ?> counts = (Map<?, ?>) batch.get("row_counts");
        assertThat(counts.get("total")).isEqualTo(2);
        assertThat(counts.get("rejected")).isEqualTo(0);
        assertThat(counts.get("accepted")).isEqualTo(0);
        assertThat(counts.get("need_review")).isEqualTo(2);

        ResponseEntity<Map> rowsResponse = http.exchange(
                "/api/v1/import-batches/" + batch.get("id") + "/rows?page=0&size=20",
                HttpMethod.GET,
                new HttpEntity<>(operatorHeaders()),
                Map.class);
        assertThat(rowsResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rowsResponse.getBody().get("total_elements")).isEqualTo(2);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) rowsResponse.getBody().get("items");
        assertThat(rows).allSatisfy(row -> assertThat(row.get("status")).isEqualTo("NEED_REVIEW"));
        assertThat(rows.stream().map(item -> ((Number) item.get("row_index")).intValue()))
                .containsExactly(2, 3);

        Map<String, Object> first = rows.getFirst();
        assertThat(first.get("source_order_ref")).isEqualTo("spr01-LPC26329467000001");
        assertThat((Map<String, Object>) first.get("parsed")).containsAllEntriesOf(Map.of(
                "receiver_name", "测试收货人甲",
                "receiver_phone", "13800000001",
                "receiver_address", "北京市朝阳区测试路1号",
                "product_name", "子牧原切羊肉礼包6300g（BJ）",
                "quantity", "1",
                "source_sku_ref", "P26011900044"));
        assertThat((Map<String, Object>) first.get("raw_cells")).containsAllEntriesOf(Map.of(
                "供应商商品名称", "北京大者国风科技有限公司",
                "订单商品状态", "待发货",
                "渠道下单时间", "2026-08-17 19:07:36",
                "渠道支付时间", "2026-08-17 19:07:35"));

        Map<String, Object> second = rows.get(1);
        assertThat(second.get("source_order_ref")).isEqualTo("spr01-LPC26330427000001");
        assertThat((Map<String, Object>) second.get("parsed")).containsAllEntriesOf(Map.of(
                "receiver_name", "测试收货人乙",
                "receiver_phone", "13800000002",
                "receiver_address", "北京市海淀区测试路2号",
                "product_name", "精选内蒙原切牛羊肉大礼包3100g（BJ）",
                "quantity", "1",
                "source_sku_ref", "P26012100060"));
    }

    private ResponseEntity<Map> upload(byte[] bytes) {
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
        headers.set("Idempotency-Key", "wangqi-source-upload-001");
        return http.exchange(
                "/api/v1/import-batches/source-orders",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class);
    }

    private HttpHeaders operatorHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Operator", "wangqi-e2e");
        headers.set("X-Request-Id", "req-wangqi-source-upload-001");
        return headers;
    }

    private byte[] wangqiWorkbook() throws Exception {
        List<String> headers = List.of(
                "渠道订单号", "主商品编码", "供应商商品名称", "商品名称", "订单商品状态",
                "采购单价（元）", "商品数量", "收货人", "收货人手机", "收货人详细地址",
                "预计到货时间", "渠道下单时间", "渠道支付时间", "快递单号", "快递公司");
        List<List<String>> values = List.of(
                List.of(
                        "spr01-LPC26329467000001", "P26011900044", "北京大者国风科技有限公司",
                        "子牧原切羊肉礼包6300g（BJ）", "待发货", "397.70", "1",
                        "测试收货人甲", "13800000001", "北京市朝阳区测试路1号", "",
                        "2026-08-17 19:07:36", "2026-08-17 19:07:35", "", ""),
                List.of(
                        "spr01-LPC26330427000001", "P26012100060", "北京大者国风科技有限公司",
                        "精选内蒙原切牛羊肉大礼包3100g（BJ）", "待发货", "241.53", "1",
                        "测试收货人乙", "13800000002", "北京市海淀区测试路2号", "",
                        "2026-08-18 11:07:29", "2026-08-18 11:07:28", "", ""));
        try (XSSFWorkbook workbook = new XSSFWorkbook();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("订单列表");
            var header = sheet.createRow(0);
            for (int column = 0; column < headers.size(); column++) {
                header.createCell(column).setCellValue(headers.get(column));
            }
            for (int rowIndex = 0; rowIndex < values.size(); rowIndex++) {
                var row = sheet.createRow(rowIndex + 1);
                for (int column = 0; column < values.get(rowIndex).size(); column++) {
                    row.createCell(column).setCellValue(values.get(rowIndex).get(column));
                }
            }
            sheet.createRow(3).createCell(5).setCellValue(639.23);
            workbook.write(output);
            return output.toByteArray();
        }
    }
}
