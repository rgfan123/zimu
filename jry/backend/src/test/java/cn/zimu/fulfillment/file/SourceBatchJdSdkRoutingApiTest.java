package cn.zimu.fulfillment.file;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zimu.fulfillment.connector.jd.JdResult;
import cn.zimu.fulfillment.connector.jd.MockJdWarehouseClient;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * Ticket 05: 导入批次确认后京东履约按显式配置路由到 SDK 建单（而非导单文件）。
 * 覆盖：真实闭环（确认→地址确认→批量建单成功→幂等）、前置未就绪不阻断批次、FILE 回退、配置校验。
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "app.jd.client-mode=MOCK",
            "app.jd.write-mode=ON",
            "app.jd.outbound-authorized-operators=source-batch-sdk-e2e",
            "app.gateway.basic-auth.username=source-batch-sdk-e2e",
            "app.gateway.basic-auth.password=source-batch-sdk-e2e-password",
            "app.jd.tracking-backfill.enabled=false",
            "app.message-worker.enabled=false",
            "app.file-store.root=${java.io.tmpdir}/zimu-source-batch-sdk-routing-test"
        })
@Import(SourceBatchJdSdkRoutingApiTest.ControlledJdConfig.class)
class SourceBatchJdSdkRoutingApiTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    @TestConfiguration
    static class ControlledJdConfig {
        @Bean
        @Primary
        ControlledJdClient controlledJdClient() {
            return new ControlledJdClient();
        }
    }

    static class ControlledJdClient extends MockJdWarehouseClient {
        @Override
        public JdResult queryStock(Map<String, Object> request) {
            return new JdResult(true, "1000", "ok", "jd-stock-sdk-routing-001", Map.of("resultList", List.of(
                    Map.of(
                            "goodsNo", "JD-SKU-000001",
                            "warehouseNo", "WH-SDK-001",
                            "goodsLevel", "100",
                            "stockStatus", "1",
                            "stockType", "1",
                            "stockNum", "100",
                            "usableNum", "100"))));
        }
    }

    @BeforeEach
    void configureJdProviderWithSdkRouting() {
        jdbc.update(
                """
                UPDATE app.fulfillment_providers
                SET config=('{' ||
                    '"sourceNo":"ISV-SDK-001","warehouseNo":"WH-SDK-001",' ||
                    '"erpShopNo":"SHOP-SDK-001","shopNo":"SHOP-SDK-001",' ||
                    '"ownerNo":"OWNER-SDK-001",' ||
                    '"pin":"PIN-SDK-001","carrierNo":"JD","salesPlatformSource":"6",' ||
                    '"townRequired":false,"outboundMode":"SDK"}')::jsonb
                WHERE provider_code='JD'
                """);
        jdbc.update(
                """
                UPDATE app.customers
                SET profile = jsonb_set(profile, '{jd_customer_code}', '"CUST-SDK-001"'::jsonb, true)
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
    void sdkRoutingConfirmCreatesShipmentsThenAddressConfirmAndBatchSubmitCompletesRealLoop() throws Exception {
        long batchId = uploadAndConfirm("SDK-LOOP-001");

        // 确认路由到 SDK：不生成导单文件，响应带 SDK 发货批次
        assertThat(exportCount(batchId)).isZero();
        long shipmentId = sdkShipmentId(batchId);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.shipments WHERE id=?", Integer.class, shipmentId)).isEqualTo(1);

        // 确认时地址尚未人工确认：自动建单尝试不阻断批次，shipment 未被标记提交
        assertThat(jdbc.queryForObject(
                "SELECT jd_receiver_confirmed_at IS NULL FROM app.shipments WHERE id=?",
                Boolean.class, shipmentId)).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.shipment_jd_outbounds WHERE shipment_id=? AND sync_status='SUBMITTED'",
                Integer.class, shipmentId)).isZero();

        // 运营确认结构化地址（04 面板语义）后批量建单成功
        ResponseEntity<Map> addressConfirmed = http.exchange(
                "/api/v1/shipments/" + shipmentId + "/jd-receiver-address",
                HttpMethod.PUT,
                new HttpEntity<>(Map.of(
                        "expected_version", 0,
                        "province", "上海市",
                        "city", "上海市",
                        "county", "浦东新区",
                        "detail_address", "测试路1号"),
                        writeHeaders("sdk-address-001", "req-sdk-address-001")),
                Map.class);
        assertThat(addressConfirmed.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> submitted = batchSubmit(batchId, "sdk-batch-submit-001");
        assertThat(submitted.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(submitted.getBody())
                .containsEntry("submitted_count", 1)
                .containsEntry("skipped_count", 0);
        Map<String, Object> item = ((List<Map<String, Object>>) submitted.getBody().get("items")).getFirst();
        assertThat(item)
                .containsEntry("shipment_id", String.valueOf(shipmentId))
                .containsEntry("sync_status", "SUBMITTED")
                .containsEntry("jd_delivery_no", "MOCK-DELIVERY-001");

        // 已提交项在新调用下跳过、不重复建单（每次批量调用使用新幂等段，防重复由业务校验保证）
        ResponseEntity<Map> second = batchSubmit(batchId, "sdk-batch-submit-002");
        assertThat(second.getBody())
                .containsEntry("submitted_count", 0)
                .containsEntry("skipped_count", 1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.shipment_jd_outbounds WHERE shipment_id=?",
                Integer.class, shipmentId)).isEqualTo(1);

        // 确认批次幂等重放不重复建发货批次
        ResponseEntity<Map> confirmReplay = confirm(batchId, "sdk-confirm-001");
        assertThat(confirmReplay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.shipments WHERE id=?",
                Integer.class, shipmentId)).isEqualTo(1);
    }

    @Test
    void unreadyShipmentFallsToPendingWithoutBlockingBatchConfirmation() throws Exception {
        long batchId = uploadAndConfirm("SDK-PENDING-001");
        long shipmentId = sdkShipmentId(batchId);

        // 前置未就绪（地址未确认）：批量建单逐条给出可读原因，不伪造任何成功事实
        ResponseEntity<Map> attempted = batchSubmit(batchId, "sdk-pending-submit-001");
        assertThat(attempted.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> item = ((List<Map<String, Object>>) attempted.getBody().get("items")).getFirst();
        assertThat(item)
                .containsEntry("shipment_id", String.valueOf(shipmentId))
                .containsEntry("business_code", "JD_SHIPMENT_OUTBOUND_RECEIVER_ADDRESS_NOT_CONFIRMED");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.shipment_jd_outbounds WHERE shipment_id=? AND sync_status='SUBMITTED'",
                Integer.class, shipmentId)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.order_lines ol "
                        + "JOIN app.fulfillments f ON f.order_line_id=ol.id "
                        + "JOIN app.shipment_items si ON si.fulfillment_id=f.id "
                        + "WHERE si.shipment_id=? AND ol.processing_stage='WAITING_PROVIDER'",
                Integer.class, shipmentId)).isZero();
    }

    @Test
    void fileModeKeepsLegacyExportWhenOutboundModeIsFile() throws Exception {
        jdbc.update(
                "UPDATE app.fulfillment_providers SET config=config||'{\"outboundMode\":\"FILE\"}'::jsonb "
                        + "WHERE provider_code='JD'");
        long batchId = upload("SDK-FILE-001");
        ResponseEntity<Map> confirmed = confirm(batchId, "sdk-confirm-sdk-file-001");
        assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(confirmed.getBody().get("confirmed_at")).isNotNull();
        // FILE 回退：不产生 SDK 路由摘要，保持既有导单文件
        assertThat(confirmed.getBody()).doesNotContainKey("outbound_routing");
        assertThat(exportCount(batchId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.fulfillment_export_items fei "
                        + "JOIN app.raw_import_rows rir ON rir.id=fei.raw_import_row_id "
                        + "WHERE rir.import_batch_id=?",
                Integer.class, batchId)).isEqualTo(1);
    }

    @Test
    void invalidOutboundModeIsRejectedByConfigSeam() {
        Map<String, Object> jdProvider = Arrays.stream(
                http.getForObject("/api/v1/fulfillment-providers", Map[].class))
                .filter(provider -> "JD_WAREHOUSE".equals(provider.get("provider_type")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("JD provider missing"));
        ResponseEntity<Map> response = http.exchange(
                "/api/v1/fulfillment-providers/" + jdProvider.get("id"),
                HttpMethod.PATCH,
                new HttpEntity<>(Map.of(
                        "expected_version", ((Number) jdProvider.get("version")).longValue(),
                        "config", Map.of("outboundMode", "TELEPORT")),
                        writeHeaders("sdk-config-invalid-001", "req-sdk-config-invalid-001")),
                Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody())
                .containsEntry("business_code", "FULFILLMENT_PROVIDER_CONFIG_OUTBOUND_MODE_INVALID");
    }

    private long uploadAndConfirm(String suffix) throws Exception {
        long batchId = upload(suffix);
        // 导入会为新收货人创建客户档案（profile 可能为 NULL），确认前补齐京东客户编码（02 门禁）
        jdbc.update(
                """
                UPDATE app.customers
                SET profile = jsonb_set(COALESCE(profile, '{}'::jsonb), '{jd_customer_code}',
                                        '"CUST-SDK-001"'::jsonb, true)
                WHERE data_scope='BUSINESS'
                """);
        ResponseEntity<Map> confirmed = confirm(batchId, "sdk-confirm-" + suffix.toLowerCase());
        assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(confirmed.getBody().get("confirmed_at")).isNotNull();
        // SDK 路由：确认响应带 jd_sdk_shipment_ids，不再产出导单文件
        Map<String, Object> routing = castMap(confirmed.getBody().get("outbound_routing"));
        assertThat(routing).containsKey("jd_sdk_shipment_ids");
        return batchId;
    }

    /** 批次内第一个京东发货批次（SDK 路由由确认创建）。 */
    private long sdkShipmentId(long batchId) {
        return jdbc.queryForObject(
                """
                SELECT s.id
                FROM app.shipments s
                JOIN app.raw_import_rows rir ON rir.order_id=s.order_id
                WHERE rir.import_batch_id=? AND rir.status='ACCEPTED'
                ORDER BY s.id LIMIT 1
                """,
                Long.class,
                batchId);
    }

    private int exportCount(long batchId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM app.fulfillment_exports fe "
                        + "JOIN app.fulfillment_export_items fei ON fei.fulfillment_export_id=fe.id "
                        + "JOIN app.raw_import_rows rir ON rir.id=fei.raw_import_row_id "
                        + "WHERE rir.import_batch_id=?",
                Integer.class, batchId);
    }

    private ResponseEntity<Map> confirm(long batchId, String idempotencyKey) {
        HttpHeaders headers = operatorHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", idempotencyKey);
        headers.set("X-Request-Id", "req-" + idempotencyKey);
        headers.setBasicAuth("source-batch-sdk-e2e", "source-batch-sdk-e2e-password");
        return http.exchange(
                "/api/v1/import-batches/" + batchId + "/confirm",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(), headers),
                Map.class);
    }

    private ResponseEntity<Map> batchSubmit(long batchId, String idempotencyKey) {
        HttpHeaders headers = operatorHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", idempotencyKey);
        headers.set("X-Request-Id", "req-" + idempotencyKey);
        headers.setBasicAuth("source-batch-sdk-e2e", "source-batch-sdk-e2e-password");
        return http.exchange(
                "/api/v1/import-batches/" + batchId + "/jd-outbound-submit",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(), headers),
                Map.class);
    }

    private long upload(String suffix) throws Exception {
        HttpHeaders headers = operatorHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.set("Idempotency-Key", "sdk-upload-" + suffix.toLowerCase());
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new org.springframework.core.io.ByteArrayResource(
                workbook("SDK-" + suffix, "SDK-LINE-" + suffix)) {
            @Override
            public String getFilename() {
                return "sdk-" + suffix.toLowerCase() + ".xlsx";
            }
        });
        body.add("import_mode", "NEW");
        ResponseEntity<Map> response = http.exchange(
                "/api/v1/import-batches/source-orders",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return Long.parseLong(response.getBody().get("id").toString());
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

    private HttpHeaders operatorHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Operator", "source-batch-sdk-e2e");
        return headers;
    }

    private HttpHeaders writeHeaders(String idempotencyKey, String requestId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", idempotencyKey);
        headers.set("X-Request-Id", requestId);
        headers.set("X-Operator", "source-batch-sdk-e2e");
        headers.setBasicAuth("source-batch-sdk-e2e", "source-batch-sdk-e2e-password");
        return headers;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }
}
