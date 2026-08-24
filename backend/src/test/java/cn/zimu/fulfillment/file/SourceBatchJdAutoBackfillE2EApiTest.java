package cn.zimu.fulfillment.file;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zimu.fulfillment.connector.jd.JdResult;
import cn.zimu.fulfillment.connector.jd.MockJdWarehouseClient;
import cn.zimu.fulfillment.fulfillment.ShipmentJdTrackingPoller;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.WorkbookFactory;
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
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Ticket 06: 京东出库单建好后系统自动取回运单并产出彩食鲜格式回填表。
 * 端到端：SDK 路由确认 → 自动建单 → 轮询器自动回填 → 运单落库 → 来源回填文件生成并可下载。
 */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "app.jd.client-mode=MOCK",
            "app.scheduling.enabled=true",
            "app.jd.write-mode=ON",
            "app.jd.outbound-authorized-operators=source-batch-e2e",
            "app.gateway.basic-auth.username=source-batch-e2e",
            "app.gateway.basic-auth.password=source-batch-e2e-password",
            "app.jd.tracking-backfill.enabled=true",
            "app.jd.tracking-backfill.poll-ms=200",
            "app.jd.tracking-backfill.batch-size=20",
            "app.jd.tracking-backfill.min-interval=PT0S",
            "app.message-worker.enabled=false",
            "app.file-store.root=${java.io.tmpdir}/zimu-source-batch-auto-backfill-test"
        })
@Import(SourceBatchJdAutoBackfillE2EApiTest.ControlledJdConfig.class)
class SourceBatchJdAutoBackfillE2EApiTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;
    @Autowired ControlledJdClient jd;
    @Autowired ShipmentJdTrackingPoller poller;

    @TestConfiguration
    static class ControlledJdConfig {
        @Bean
        @Primary
        ControlledJdClient controlledJdClient() {
            return new ControlledJdClient();
        }
    }

    /** 库存查询恒定充足；出库单查询在测试内受控设置（模拟京东运单就绪）。 */
    static class ControlledJdClient extends MockJdWarehouseClient {
        private final AtomicLong outboundQueryCalls = new AtomicLong();
        private volatile JdResult outboundResult;

        void outboundResult(JdResult value) {
            outboundResult = value;
        }

        long queryCalls() {
            return outboundQueryCalls.get();
        }

        @Override
        public JdResult queryStock(Map<String, Object> request) {
            return new JdResult(true, "1000", "ok", "jd-stock-e2e-001", Map.of("resultList", List.of(
                    Map.of(
                            "goodsNo", "JD-SKU-000001",
                            "warehouseNo", "WH-E2E-001",
                            "goodsLevel", "100",
                            "stockStatus", "1",
                            "stockType", "1",
                            "stockNum", "100",
                            "usableNum", "100"))));
        }

        @Override
        public JdResult queryOutboundOrder(Map<String, Object> request) {
            outboundQueryCalls.incrementAndGet();
            JdResult value = outboundResult;
            if (value == null) {
                throw new IllegalStateException("controlled JD outbound query result missing");
            }
            return value;
        }
    }

    @BeforeEach
    void configureJdProviderWithSdkRouting() {
        jdbc.update(
                """
                UPDATE app.fulfillment_providers
                SET config=('{' ||
                    '"sourceNo":"ISV-E2E-001","warehouseNo":"WH-E2E-001",' ||
                    '"erpShopNo":"SHOP-E2E-001","shopNo":"SHOP-E2E-001",' ||
                    '"ownerNo":"OWNER-E2E-001",' ||
                    '"pin":"PIN-E2E-001","carrierNo":"JD","salesPlatformSource":"6",' ||
                    '"townRequired":false,"outboundMode":"SDK"}')::jsonb
                WHERE provider_code='JD'
                """);
        jdbc.update(
                """
                UPDATE app.customers
                SET profile = jsonb_set(COALESCE(profile, '{}'::jsonb), '{jd_customer_code}',
                                        '"CUST-E2E-001"'::jsonb, true)
                WHERE data_scope='BUSINESS'
                """);
        jdbc.update(
                """
                UPDATE app.provider_skus
                SET external_codes=jsonb_set(external_codes, '{jd_pieces_per_unit}', '1'::jsonb, true)
                WHERE fulfillment_provider_id=(SELECT id FROM app.fulfillment_providers WHERE provider_code='JD')
                """);
        jdbc.update(
                """
                INSERT INTO app.source_channel_skus
                    (source_channel, source_sku_ref, source_product_name, source_specification,
                     quantity_multiplier, sku_id, active)
                SELECT 'CAISHIXIAN', '2047705', '子牧牛腱子(谷饲牛腱子)500g*2', '500g*2',
                       1.000, sku_id, true
                FROM app.source_channel_skus
                WHERE source_channel='WECOM' AND source_sku_ref='WECOM-SKU-JD-001'
                ON CONFLICT (source_channel, source_sku_ref) DO UPDATE
                SET sku_id=EXCLUDED.sku_id, quantity_multiplier=EXCLUDED.quantity_multiplier,
                    source_product_name=EXCLUDED.source_product_name,
                    source_specification=EXCLUDED.source_specification, active=true
                """);
    }

    @Test
    void sdkRoutingSubmitThenAutoPollBackfillsTrackingAndProducesDownloadableCaishixianReturnFile() throws Exception {
        // 建单：上传 → 确认（SDK 路由自动建单，前置在确认前补齐）
        String batchId = upload("E2E-AUTO-001");
        jdbc.update(
                """
                UPDATE app.customers
                SET profile = jsonb_set(COALESCE(profile, '{}'::jsonb), '{jd_customer_code}',
                                        '"CUST-E2E-001"'::jsonb, true)
                WHERE data_scope='BUSINESS'
                """);
        ResponseEntity<Map> confirmed = confirm(batchId, "e2e-confirm-001");
        assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.OK);
        long shipmentId = jdbc.queryForObject(
                """
                SELECT s.id FROM app.shipments s
                JOIN app.raw_import_rows rir ON rir.order_id=s.order_id
                WHERE rir.import_batch_id=? AND rir.status='ACCEPTED' ORDER BY s.id LIMIT 1
                """,
                Long.class, Long.parseLong(batchId));
        // 确认地址后批量建单（03/04 前置就绪是人工步骤，测试里直接补齐）
        ResponseEntity<Map> address = http.exchange(
                "/api/v1/shipments/" + shipmentId + "/jd-receiver-address",
                HttpMethod.PUT,
                new HttpEntity<>(Map.of(
                        "expected_version", 0,
                        "province", "上海市",
                        "city", "上海市",
                        "county", "浦东新区",
                        "detail_address", "测试路1号"),
                        writeHeaders("e2e-address-001", "req-e2e-address-001")),
                Map.class);
        assertThat(address.getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> submitted = http.exchange(
                "/api/v1/import-batches/" + batchId + "/jd-outbound-submit",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(), operatorHeaders()),
                Map.class);
        assertThat(submitted.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(submitted.getBody()).containsEntry("submitted_count", 1);
        assertThat(jdbc.queryForObject(
                "SELECT sync_status FROM app.shipment_jd_outbounds WHERE shipment_id=?",
                String.class, shipmentId)).isEqualTo("SUBMITTED");

        // 京东运单就绪；轮询器自动取回（deliveryNo 必须与建单时京东返回的出库单号一致）
        String erpDeliveryNo = jdbc.queryForObject(
                "SELECT erp_delivery_no FROM app.shipment_jd_outbounds WHERE shipment_id=?",
                String.class, shipmentId);
        String jdDeliveryNo = jdbc.queryForObject(
                "SELECT jd_delivery_no FROM app.shipment_jd_outbounds WHERE shipment_id=?",
                String.class, shipmentId);
        String waybillNo = "JD-AUTO-E2E-001";
        jd.outboundResult(new JdResult(true, "1000", "成功", "jd-query-e2e-001",
                remote(shipmentId, erpDeliveryNo, jdDeliveryNo, waybillNo)));

        // 轮询器驱动自动回填（与既有调度测试同一入口：ShipmentJdTrackingPoller.poll）
        poller.poll();
        awaitUntil("auto backfill tracks the shipment", Duration.ofSeconds(20), () -> jdbc.queryForObject(
                "SELECT count(*) FROM app.trackings WHERE shipment_id=?", Integer.class, shipmentId) == 1);
        assertThat(jdbc.queryForObject(
                "SELECT tracking_number FROM app.trackings WHERE shipment_id=?",
                String.class, shipmentId)).isEqualTo(waybillNo);
        assertThat(jdbc.queryForObject(
                "SELECT logistics_company_name FROM app.trackings WHERE shipment_id=?",
                String.class, shipmentId)).isEqualTo("京东物流");
        assertThat(jdbc.queryForObject(
                "SELECT tracking_query_status FROM app.shipment_jd_outbounds WHERE shipment_id=?",
                String.class, shipmentId)).isEqualTo("TRACKED");

        // 自动生成彩食鲜格式回填文件（只追加版本，不覆盖）
        awaitUntil("source return export generated", Duration.ofSeconds(20), () -> jdbc.queryForObject(
                "SELECT count(*) FROM app.source_return_exports WHERE import_batch_id=?",
                Integer.class, Long.parseLong(batchId)) >= 1);
        List<?> returns = http.getForObject(
                "/api/v1/import-batches/" + batchId + "/source-return-exports", List.class);
        assertThat(returns).singleElement().satisfies(item ->
                assertThat(((Map<?, ?>) item).get("is_final")).isEqualTo(true));
        String returnId = ((Map<?, ?>) returns.getFirst()).get("id").toString();
        byte[] workbookBytes = http.exchange(
                "/api/v1/source-return-exports/" + returnId + "/file",
                HttpMethod.GET,
                new HttpEntity<>(operatorHeaders()),
                byte[].class).getBody();
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(workbookBytes))) {
            var sheet = workbook.getSheetAt(0);
            var header = sheet.getRow(0);
            var row = sheet.getRow(1);
            DataFormatter formatter = new DataFormatter();
            Map<String, Integer> columns = new LinkedHashMap<>();
            for (int index = 0; index < header.getLastCellNum(); index++) {
                columns.put(formatter.formatCellValue(header.getCell(index)), index);
            }
            assertThat(formatter.formatCellValue(row.getCell(columns.get("物流公司代码")))).isEqualTo("JD");
            assertThat(formatter.formatCellValue(row.getCell(columns.get("物流单号")))).isEqualTo(waybillNo);
            assertThat(formatter.formatCellValue(row.getCell(columns.get("发货数量")))).isEqualTo("1");
        }

        // 回填完成后轮询器不再反复外调（TRACKED 终态）
        long queriesAfterTracked = jd.queryCalls();
        poller.poll();
        poller.poll();
        assertThat(jd.queryCalls()).isEqualTo(queriesAfterTracked);
    }

    private Map<String, Object> remote(
            long shipmentId, String erpDeliveryNo, String deliveryNo, String waybillNo)
            throws Exception {
        Map<String, Object> remote = new LinkedHashMap<>();
        remote.put("erpDeliveryNo", erpDeliveryNo);
        remote.put("deliveryNo", deliveryNo);
        remote.put("warehouseNo", "WH-E2E-001");
        remote.put("status", "10020");
        remote.put("isSplit", "0");
        remote.put("splitDeliveryNos", "");
        remote.put("carrierInfo", Map.of(
                "carrierNo", "JD", "carrierName", "京东物流", "waybillNo", waybillNo));
        String cargoJson = jdbc.queryForObject(
                "SELECT submitted_cargo_snapshot::text FROM app.shipment_jd_outbounds WHERE shipment_id=?",
                String.class, shipmentId);
        List<Map<String, Object>> cargos = objectMapper.readValue(cargoJson, new TypeReference<>() {});
        remote.put("deliveryItemList", cargos.stream().map(cargo -> {
            Map<String, Object> row = new LinkedHashMap<>(cargo);
            row.put("realQuantity", cargo.get("planQuantity"));
            return Map.copyOf(row);
        }).toList());
        return remote;
    }

    private String upload(String suffix) throws Exception {
        HttpHeaders headers = operatorHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.set("Idempotency-Key", "e2e-upload-" + suffix.toLowerCase());
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(workbook("E2E-" + suffix, "E2E-LINE-" + suffix)) {
            @Override
            public String getFilename() {
                return "e2e-" + suffix.toLowerCase() + ".xlsx";
            }
        });
        body.add("import_mode", "NEW");
        ResponseEntity<Map> response = http.exchange(
                "/api/v1/import-batches/source-orders",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().get("id").toString();
    }

    private byte[] workbook(String orderRef, String lineRef) throws Exception {
        List<String> headers = List.of(
                "主订单编号", "子订单编号", "供应商编码", "站点编码", "商品编号", "商品名称",
                "规格", "单位", "下单数量", "收货人", "联系电话", "省", "市", "区", "详细地址",
                "订单备注", "发货数量", "物流公司代码", "物流单号", "错误原因");
        List<String> values = List.of(
                orderRef, lineRef, "SUPPLIER-ZIMU", "CSX-SITE-001",
                "2047705", "子牧牛腱子(谷饲牛腱子)500g*2", "500g*2", "件", "1.000",
                "张三", "13800000000", "上海市", "上海市", "浦东新区", "测试路1号", "", "", "", "", "");
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("待发货订单");
            var header = sheet.createRow(0);
            var row = sheet.createRow(1);
            for (int index = 0; index < headers.size(); index++) {
                header.createCell(index).setCellValue(headers.get(index));
                row.createCell(index).setCellValue(values.get(index));
            }
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private ResponseEntity<Map> confirm(String batchId, String idempotencyKey) {
        HttpHeaders headers = operatorHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", idempotencyKey);
        headers.set("X-Request-Id", "req-" + idempotencyKey);
        headers.setBasicAuth("source-batch-e2e", "source-batch-e2e-password");
        return http.exchange(
                "/api/v1/import-batches/" + batchId + "/confirm",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(), headers),
                Map.class);
    }

    private HttpHeaders operatorHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Operator", "source-batch-e2e");
        headers.setBasicAuth("source-batch-e2e", "source-batch-e2e-password");
        return headers;
    }

    private HttpHeaders writeHeaders(String idempotencyKey, String requestId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", idempotencyKey);
        headers.set("X-Request-Id", requestId);
        headers.set("X-Operator", "source-batch-e2e");
        headers.setBasicAuth("source-batch-e2e", "source-batch-e2e-password");
        return headers;
    }

    private static void awaitUntil(String description, Duration timeout, java.util.function.BooleanSupplier condition)
            throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("condition not met within " + timeout + ": " + description);
    }
}
