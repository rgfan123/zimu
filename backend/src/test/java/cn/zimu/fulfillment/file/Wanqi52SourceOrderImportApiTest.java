package cn.zimu.fulfillment.file;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
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
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;

/** 万齐订单管理导出 52 列文件经公共 multipart 接口进入来源订单批次。 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "app.message-worker.enabled=false",
            "app.file-store.root=${java.io.tmpdir}/zimu-wanqi-52-source-import-test"
        })
class Wanqi52SourceOrderImportApiTest {

    private static final List<String> HEADERS = List.of(
            "收货人姓名", "收货人手机号", "详细地址", "商品名称", "规格信息", "商品类型", "品牌",
            "一级分类", "二级分类", "三级分类", "一级逻辑分类", "二级逻辑分类", "三级逻辑分类",
            "售价", "购买数量", "成本价", "结算价", "优惠类型", "优惠金额", "供应商", "商品来源",
            "子订单状态", "售后状态", "退款类型", "供应商发货时间", "确认收货时间", "申请退款时间",
            "售后完成时间", "用户备注", "商家/客服备注", "订单处理形式", "订单ID", "聚合ID", "子订单ID",
            "供应商单号", "商品id", "供应商商品id", "门店id", "供应商sku id", "服务时效", "期望时间",
            "物流信息", "crm 单号", "订单总金额", "skuid", "sku名称", "不含运毛利额", "不含运毛利率",
            "含运毛利额", "含运毛利率", "订单类型", "实物售后");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired TrackingFileService trackingFileService;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    cn.zimu.fulfillment.connector.wecom.WecomConnectionManager ignoredWecomConnectionManager;

    @Test
    @SuppressWarnings("unchecked")
    void exact52ColumnsPreserveOrderLineAndSkuFactsAndKeepMissingSettlementAuditable() throws Exception {
        ResponseEntity<Map> uploaded = upload(workbook(List.of(
                sourceRow(
                        "1248941457073590272", "1248941457073590273", "1161501915637485568",
                        "测试收货人", "13800000001", "北京/丰台区/卢沟桥街道 测试地址1号",
                        "子牧 子牧牛羊精选礼包 6000g 1套", "规格:6000g;"),
                sourceRow(
                        "1248941457073590272", "1248941457073590274", "1120591554394853376",
                        "测试收货人", "13800000001", "北京/丰台区/卢沟桥街道 测试地址1号",
                        "子牧 羊蝎子鸵鸟肉排组合 1080g 1套", "规格:1080g;"))));

        assertThat(uploaded.getStatusCode())
                .withFailMessage("upload body: %s", uploaded.getBody())
                .isEqualTo(HttpStatus.CREATED);
        Map<String, Object> batch = uploaded.getBody();
        assertThat(batch)
                .containsEntry("source_channel", "WANQI")
                .containsEntry("template_family", "WANQI_SOURCE_ORDER")
                .containsEntry("template_version", "v1-52-columns")
                .containsEntry("settlement_missing", true)
                .containsEntry("status", "COMPLETED_WITH_REVIEW");
        assertThat((Map<String, Object>) batch.get("row_counts")).containsAllEntriesOf(Map.of(
                "total", 2, "accepted", 0, "need_review", 2, "rejected", 0));

        ResponseEntity<Map> rowsResponse = http.exchange(
                "/api/v1/import-batches/" + batch.get("id") + "/rows?page=0&size=20",
                HttpMethod.GET,
                new HttpEntity<>(operatorHeaders()),
                Map.class);

        assertThat(rowsResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) rowsResponse.getBody().get("items");
        assertThat(rows).hasSize(2).allSatisfy(row -> {
            assertThat(row)
                    .containsEntry("source_order_ref", "1248941457073590272")
                    .containsEntry("status", "NEED_REVIEW")
                    .containsEntry("error_code", "SKU_MATCH")
                    .doesNotContainEntry("order_id", null)
                    .doesNotContainEntry("order_line_id", null);
        });
        assertThat(rows).extracting(row -> row.get("order_id")).containsOnly(rows.getFirst().get("order_id"));
        assertThat(rows).extracting(row -> row.get("order_line_id")).doesNotHaveDuplicates();

        assertThat((Map<String, Object>) rows.getFirst().get("parsed")).containsAllEntriesOf(Map.of(
                "source_line_ref", "1248941457073590273",
                "receiver_name", "测试收货人",
                "receiver_phone", "13800000001",
                "receiver_address", "北京/丰台区/卢沟桥街道 测试地址1号",
                "product_name", "子牧 子牧牛羊精选礼包 6000g 1套",
                "specification", "规格:6000g;",
                "quantity", "1",
                "source_sku_ref", "1161501915637485568"));
        assertThat((Map<String, Object>) rows.get(1).get("parsed")).containsAllEntriesOf(Map.of(
                "source_line_ref", "1248941457073590274",
                "source_sku_ref", "1120591554394853376"));

        ResponseEntity<Map> orderResponse = http.exchange(
                "/api/v1/orders/" + rows.getFirst().get("order_id"),
                HttpMethod.GET,
                new HttpEntity<>(operatorHeaders()),
                Map.class);
        assertThat(orderResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((Map<String, Object>) orderResponse.getBody().get("settlement"))
                .containsEntry("method", null)
                .containsEntry("settlement_time", null);
    }

    @Test
    @SuppressWarnings("unchecked")
    void duplicateChildOrderIdWithinOneOrderFailsClosed() throws Exception {
        ResponseEntity<Map> uploaded = upload(workbook(List.of(
                sourceRow(
                        "1248941457073590300", "1248941457073590301", "1161501915637485568",
                        "测试收货人", "13800000002", "北京/丰台区/卢沟桥街道 测试地址2号",
                        "子牧 子牧牛羊精选礼包 6000g 1套", "规格:6000g;"),
                sourceRow(
                        "1248941457073590300", "1248941457073590301", "1120591554394853376",
                        "测试收货人", "13800000002", "北京/丰台区/卢沟桥街道 测试地址2号",
                        "子牧 羊蝎子鸵鸟肉排组合 1080g 1套", "规格:1080g;"))));

        assertThat(uploaded.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> batch = uploaded.getBody();
        ResponseEntity<Map> rowsResponse = http.exchange(
                "/api/v1/import-batches/" + batch.get("id") + "/rows?page=0&size=20",
                HttpMethod.GET,
                new HttpEntity<>(operatorHeaders()),
                Map.class);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) rowsResponse.getBody().get("items");

        assertThat(rows).hasSize(2).allSatisfy(row -> assertThat(row)
                .containsEntry("source_order_ref", "1248941457073590300")
                .containsEntry("error_code", "SOURCE_LINE_REF_DUPLICATE")
                .containsEntry("order_id", null));
    }

    @Test
    @SuppressWarnings("unchecked")
    void skuidBundleMappingUsesAuthoritativeInternalSkuWithoutSettlementValues() throws Exception {
        String sourceSkuId = "1161501915637485999";
        long skuId = jdbc.queryForObject(
                "SELECT sku_id FROM app.provider_skus WHERE provider_sku_code='JD-SKU-000001'",
                Long.class);
        long bundleId = jdbc.queryForObject(
                "INSERT INTO app.product_bundles(bundle_code,bundle_name,status) "
                        + "VALUES ('BUNDLE-WANQI-52-TEST','万齐测试礼包','DRAFT') RETURNING id",
                Long.class);
        jdbc.update(
                "INSERT INTO app.bundle_items(bundle_id,sort_no,sku_id,quantity_per_bundle) VALUES (?,?,?,?)",
                bundleId,
                1,
                skuId,
                1);
        jdbc.update("UPDATE app.product_bundles SET status='ACTIVE' WHERE id=?", bundleId);
        jdbc.update(
                "INSERT INTO app.source_channel_bundles(source_channel,source_bundle_ref,source_bundle_name,"
                        + "quantity_multiplier,bundle_id,active) VALUES ('WANQI',?,?,1,?,true)",
                sourceSkuId,
                "万齐测试礼包",
                bundleId);

        ResponseEntity<Map> uploaded = upload(workbook(List.of(sourceRow(
                "1248941457073590400", "1248941457073590401", sourceSkuId,
                "测试收货人", "13800000003", "北京/丰台区/卢沟桥街道 测试地址3号",
                "万齐测试礼包", "规格:1套;"))));

        assertThat(uploaded.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> batch = uploaded.getBody();
        assertThat(batch)
                .containsEntry("status", "COMPLETED")
                .containsEntry("settlement_missing", true);
        assertThat((Map<String, Object>) batch.get("row_counts")).containsAllEntriesOf(Map.of(
                "total", 1, "accepted", 1, "need_review", 0, "rejected", 0));

        ResponseEntity<Map> rowsResponse = http.exchange(
                "/api/v1/import-batches/" + batch.get("id") + "/rows?page=0&size=20",
                HttpMethod.GET,
                new HttpEntity<>(operatorHeaders()),
                Map.class);
        Map<String, Object> importedRow = (Map<String, Object>) ((List<?>) rowsResponse.getBody().get("items")).getFirst();
        assertThat(importedRow).containsEntry("status", "ACCEPTED");

        ResponseEntity<Map> orderResponse = http.exchange(
                "/api/v1/orders/" + importedRow.get("order_id"),
                HttpMethod.GET,
                new HttpEntity<>(operatorHeaders()),
                Map.class);
        Map<String, Object> order = orderResponse.getBody();
        assertThat((Map<String, Object>) order.get("settlement"))
                .containsEntry("method", null)
                .containsEntry("settlement_time", null);
        Map<String, Object> line = (Map<String, Object>) ((List<?>) order.get("lines")).getFirst();
        assertThat(line)
                .containsEntry("line_type", "CUSTOM_BUNDLE")
                .containsEntry("bundle_id", Long.toString(bundleId))
                .containsEntry("processing_stage", "READY_TO_EXPORT");
        assertThat((List<Map<String, Object>>) line.get("components"))
                .singleElement()
                .satisfies(component -> assertThat(component).containsEntry("sku_id", Long.toString(skuId)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void shippedCancelledRefundAfterSalesAndLogisticsFactsFailClosed() throws Exception {
        Map<String, String> shipped = sourceRow(
                "1248941457073590500", "1248941457073590501", "1161501915637485501",
                "测试收货人", "13800000004", "北京/丰台区/卢沟桥街道 测试地址4号", "商品一", "规格:1件;");
        shipped.put("子订单状态", "已发货");
        Map<String, String> cancelled = sourceRow(
                "1248941457073590510", "1248941457073590511", "1161501915637485511",
                "测试收货人", "13800000004", "北京/丰台区/卢沟桥街道 测试地址4号", "商品二", "规格:1件;");
        cancelled.put("子订单状态", "已取消");
        Map<String, String> refund = sourceRow(
                "1248941457073590520", "1248941457073590521", "1161501915637485521",
                "测试收货人", "13800000004", "北京/丰台区/卢沟桥街道 测试地址4号", "商品三", "规格:1件;");
        refund.put("退款类型", "仅退款");
        Map<String, String> afterSales = sourceRow(
                "1248941457073590530", "1248941457073590531", "1161501915637485531",
                "测试收货人", "13800000004", "北京/丰台区/卢沟桥街道 测试地址4号", "商品四", "规格:1件;");
        afterSales.put("售后状态", "处理中");
        Map<String, String> logistics = sourceRow(
                "1248941457073590540", "1248941457073590541", "1161501915637485541",
                "测试收货人", "13800000004", "北京/丰台区/卢沟桥街道 测试地址4号", "商品五", "规格:1件;");
        logistics.put("物流信息", "京东物流 JDVA00000000000");

        ResponseEntity<Map> uploaded = upload(workbook(List.of(shipped, cancelled, refund, afterSales, logistics)));
        assertThat(uploaded.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> batch = uploaded.getBody();
        ResponseEntity<Map> rowsResponse = http.exchange(
                "/api/v1/import-batches/" + batch.get("id") + "/rows?page=0&size=20",
                HttpMethod.GET,
                new HttpEntity<>(operatorHeaders()),
                Map.class);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) rowsResponse.getBody().get("items");

        assertThat(rows).extracting(row -> row.get("error_code")).containsExactly(
                "SOURCE_ORDER_STATUS_BLOCKED",
                "SOURCE_ORDER_STATUS_BLOCKED",
                "SOURCE_ORDER_REFUND_BLOCKED",
                "SOURCE_ORDER_AFTER_SALES_BLOCKED",
                "SOURCE_ORDER_ALREADY_FULFILLED");
        assertThat(rows).allSatisfy(row -> assertThat(row)
                .containsEntry("status", "NEED_REVIEW")
                .containsEntry("order_id", null));
    }

    @Test
    @SuppressWarnings("unchecked")
    void missingChildOrderIdFailsClosed() throws Exception {
        Map<String, String> missing = sourceRow(
                "1248941457073590550", "", "1161501915637485551",
                "测试收货人", "13800000004", "北京/丰台区/卢沟桥街道 测试地址4号", "商品六", "规格:1件;");

        ResponseEntity<Map> uploaded = upload(workbook(List.of(missing)));
        Map<String, Object> batch = uploaded.getBody();
        ResponseEntity<Map> rowsResponse = http.exchange(
                "/api/v1/import-batches/" + batch.get("id") + "/rows?page=0&size=20",
                HttpMethod.GET,
                new HttpEntity<>(operatorHeaders()),
                Map.class);
        Map<String, Object> row = (Map<String, Object>) ((List<?>) rowsResponse.getBody().get("items")).getFirst();

        assertThat(row)
                .containsEntry("status", "NEED_REVIEW")
                .containsEntry("error_code", "SOURCE_LINE_REF_REQUIRED")
                .containsEntry("order_id", null);
    }

    @Test
    void reordered52ColumnsDoNotMatchTheKnownTemplateVersion() throws Exception {
        List<String> reordered = new ArrayList<>(HEADERS);
        Collections.swap(reordered, 31, 33);
        ResponseEntity<Map> uploaded = upload(workbook(
                reordered,
                List.of(sourceRow(
                        "1248941457073590600", "1248941457073590601", "1161501915637485601",
                        "测试收货人", "13800000005", "北京/丰台区/卢沟桥街道 测试地址5号",
                        "商品六", "规格:1件;"))));

        assertThat(uploaded.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(uploaded.getBody()).containsEntry("business_code", "TEMPLATE_FINGERPRINT_AMBIGUOUS");
    }

    @Test
    void sourceReturnGenerationFailsClosedBeforeWritingUnknownColumns() {
        long providerId = jdbc.queryForObject(
                "SELECT id FROM app.fulfillment_providers WHERE provider_type='JD_WAREHOUSE' ORDER BY id LIMIT 1",
                Long.class);
        long skuId = jdbc.queryForObject(
                "SELECT sku_id FROM app.provider_skus WHERE fulfillment_provider_id=? ORDER BY id LIMIT 1",
                Long.class,
                providerId);
        long batchId = jdbc.queryForObject(
                """
                INSERT INTO app.import_batches
                    (batch_no,batch_type,import_mode,revision_no,source_channel,template_family,template_version,
                     template_fingerprint,original_file_name,content_sha256,file_ref,status,uploaded_by,settlement_missing)
                VALUES ('IMP-WANQI-RETURN-TEST','SOURCE_ORDER','NEW',1,'WANQI','WANQI_SOURCE_ORDER','v1-52-columns',
                        'WANQI-v1-52-columns-test','return-test.xlsx',repeat('a',64),'/not-read/return-test.xlsx',
                        'COMPLETED','wanqi-return-test',true)
                RETURNING id
                """,
                Long.class);
        long orderId = jdbc.queryForObject(
                """
                INSERT INTO app.orders
                    (order_no,source_channel,source_ref,source_ref_kind,source_import_batch_id,order_status,
                     settlement_method,settlement_time,receiver_name,receiver_phone,receiver_address)
                VALUES ('ORD-WANQI-RETURN-TEST','WANQI','WANQI-RETURN-TEST','PROVIDED',?,'NEED_REVIEW',
                        'UNSPECIFIED',NULL,'测试收货人','13800000006','测试地址6号')
                RETURNING id
                """,
                Long.class,
                batchId);
        long lineId = jdbc.queryForObject(
                """
                INSERT INTO app.order_lines
                    (order_id,line_no,line_type,sku_id,fulfillment_provider_id,product_name_snapshot,
                     sku_code_snapshot,specification_snapshot,unit_snapshot,requested_quantity,processing_stage)
                SELECT ?,1,'SINGLE',s.id,?,'测试商品',s.sku_code,'测试规格','件',1,'WAITING_PROVIDER'
                FROM app.skus s WHERE s.id=? RETURNING id
                """,
                Long.class,
                orderId,
                providerId,
                skuId);
        jdbc.update(
                """
                INSERT INTO app.raw_import_rows
                    (import_batch_id,sheet_name,sheet_index,row_index,raw_cells,source_order_ref,status,order_id,order_line_id)
                VALUES (?,'Sheet0',0,2,'{}'::jsonb,'WANQI-RETURN-TEST','ACCEPTED',?,?)
                """,
                batchId,
                orderId,
                lineId);
        long fulfillmentId = jdbc.queryForObject(
                """
                INSERT INTO app.fulfillments
                    (fulfillment_no,order_line_id,fulfillment_provider_id,requested_quantity)
                VALUES ('FUL-WANQI-RETURN-TEST',?,?,1) RETURNING id
                """,
                Long.class,
                lineId,
                providerId);
        long shipmentId = jdbc.queryForObject(
                """
                INSERT INTO app.shipments
                    (shipment_no,order_id,fulfillment_provider_id,shipment_sequence,
                     receiver_name_snapshot,receiver_phone_snapshot,receiver_address_snapshot)
                VALUES ('SHP-WANQI-RETURN-TEST',?,?,1,'测试收货人','13800000006','测试地址6号')
                RETURNING id
                """,
                Long.class,
                orderId,
                providerId);
        jdbc.update(
                "INSERT INTO app.shipment_items(shipment_id,fulfillment_id,instructed_quantity) VALUES (?,?,1)",
                shipmentId,
                fulfillmentId);

        assertThat(trackingFileService.finalizeReadySourceReturnsForShipment(
                        shipmentId, "wanqi-return-test"))
                .isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.source_return_exports WHERE import_batch_id=?",
                Integer.class,
                batchId)).isZero();
    }

    private ResponseEntity<Map> upload(byte[] bytes) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return "订单管理导出.xlsx";
            }
        });
        body.add("import_mode", "NEW");
        HttpHeaders headers = operatorHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.set("Idempotency-Key", "wanqi-52-source-upload-001");
        return http.exchange(
                "/api/v1/import-batches/source-orders",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class);
    }

    private HttpHeaders operatorHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Operator", "wanqi-import-test");
        headers.set("X-Request-Id", "req-wanqi-52-source-upload-001");
        return headers;
    }

    private Map<String, String> sourceRow(
            String orderId,
            String childOrderId,
            String skuId,
            String receiver,
            String phone,
            String address,
            String productName,
            String specification) {
        Map<String, String> cells = new LinkedHashMap<>();
        HEADERS.forEach(header -> cells.put(header, ""));
        cells.put("收货人姓名", receiver);
        cells.put("收货人手机号", phone);
        cells.put("详细地址", address);
        cells.put("商品名称", productName);
        cells.put("规格信息", specification);
        cells.put("商品类型", "实体商品");
        cells.put("品牌", "子牧");
        cells.put("售价", "588.00");
        cells.put("购买数量", "1");
        cells.put("成本价", "¥400.00");
        cells.put("结算价", "400.00");
        cells.put("优惠金额", "0.00");
        cells.put("供应商", "京诚乾元（北京）供应链管理有限公司");
        cells.put("商品来源", "自建商品");
        cells.put("子订单状态", "超时未发货");
        cells.put("订单处理形式", "自动完成订单");
        cells.put("订单ID", orderId);
        cells.put("子订单ID", childOrderId);
        cells.put("商品id", skuId.substring(0, skuId.length() - 3) + "000");
        cells.put("订单总金额", "588.00");
        cells.put("skuid", skuId);
        cells.put("sku名称", productName);
        cells.put("订单类型", "销售订单");
        cells.put("实物售后", "支持");
        return cells;
    }

    private byte[] workbook(List<Map<String, String>> rows) throws Exception {
        return workbook(HEADERS, rows);
    }

    private byte[] workbook(List<String> headers, List<Map<String, String>> rows) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("Sheet0");
            var header = sheet.createRow(0);
            for (int column = 0; column < headers.size(); column++) {
                header.createCell(column).setCellValue(headers.get(column));
            }
            for (int index = 0; index < rows.size(); index++) {
                var row = sheet.createRow(index + 1);
                for (int column = 0; column < headers.size(); column++) {
                    row.createCell(column).setCellValue(rows.get(index).get(headers.get(column)));
                }
            }
            workbook.write(output);
            return output.toByteArray();
        }
    }
}
