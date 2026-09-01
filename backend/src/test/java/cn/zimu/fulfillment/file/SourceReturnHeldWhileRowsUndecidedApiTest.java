package cn.zimu.fulfillment.file;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zimu.fulfillment.connector.jd.JdResult;
import cn.zimu.fulfillment.connector.jd.MockJdWarehouseClient;
import cn.zimu.fulfillment.fulfillment.ShipmentJdTrackingPoller;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 部分确认之后，只要批次里还有未定论的行，就不能生成来源回填文件。
 *
 * <p>这是部分确认最要紧的安全性质。回填文件按已接收行数判完整
 * （{@code returns.size() == acceptedRows}），而部分确认让「已接收行」只是批次的一部分——
 * 就绪行一发完就会满足这个等式，照常生成的话出去的是一份只含首批的 is_final 文件。
 * excel-closed-loop-spec.md 明令「不得生成只含首批的 is_final=true 文件」，而且 V41 触发器
 * {@code validate_source_return_invalidation} 禁止推送成功的文件再失效——半份结果一旦推出去
 * 就再也收不回来。
 *
 * <p>本用例把就绪行一路跑到运单落库（正常情况下这就该产出回填文件了），断言回填文件仍未生成。
 * 正常路径（没有阻断行时照常生成）由 {@code SourceBatchJdAutoBackfillE2EApiTest} 覆盖。
 */
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "app.jd.client-mode=MOCK",
            "app.scheduling.enabled=true",
            "app.jd.write-mode=ON",
            "app.jd.outbound-authorized-operators=source-hold-e2e",
            "app.gateway.basic-auth.username=source-hold-e2e",
            "app.gateway.basic-auth.password=source-hold-e2e-password",
            "app.jd.tracking-backfill.enabled=true",
            "app.jd.tracking-backfill.poll-ms=200",
            "app.jd.tracking-backfill.batch-size=20",
            "app.jd.tracking-backfill.min-interval=PT0S",
            "app.message-worker.enabled=false",
            "app.file-store.root=${java.io.tmpdir}/zimu-source-return-hold-test"
        })
@Import(SourceReturnHeldWhileRowsUndecidedApiTest.ControlledJdConfig.class)
class SourceReturnHeldWhileRowsUndecidedApiTest {

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

    static class ControlledJdClient extends MockJdWarehouseClient {
        private final AtomicLong outboundQueryCalls = new AtomicLong();
        private volatile JdResult outboundResult;

        void outboundResult(JdResult value) {
            outboundResult = value;
        }

        @Override
        public JdResult queryStock(Map<String, Object> request) {
            return new JdResult(true, "1000", "ok", "jd-stock-hold-001", Map.of("resultList", List.of(
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
            if (Integer.valueOf(0).equals(request.get("deliveryItemFlag"))
                    && Integer.valueOf(0).equals(request.get("deliveryPackageFlag"))
                    && Integer.valueOf(0).equals(request.get("deliveryStatusFlag"))) {
                return super.queryOutboundOrder(request);
            }
            outboundQueryCalls.incrementAndGet();
            JdResult value = outboundResult;
            if (value == null) {
                return super.queryOutboundOrder(request);
            }
            return value;
        }
    }

    @BeforeEach
    void seedJdRoutingAndSkuMapping() {
        // 与 SourceBatchJdAutoBackfillE2EApiTest 用同一组京东标识（含 erpShopNo，缺它建单会失败关闭）。
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
                UPDATE app.provider_skus
                SET external_codes=jsonb_set(external_codes, '{jd_pieces_per_unit}', '1'::jsonb, true)
                WHERE fulfillment_provider_id=(SELECT id FROM app.fulfillment_providers WHERE provider_code='JD')
                """);
        // 只映射就绪行的来源 SKU；另一行的 ref 没有映射，会落成待复核。
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
    @SuppressWarnings("unchecked")
    void doesNotEmitAFirstWaveOnlyReturnFileWhileABlockedRowIsStillUndecided() throws Exception {
        String batchId = uploadTwoRows();
        // 客户是在上传时才建的，所以京东客户编码必须在上传之后、确认之前补。
        jdbc.update(
                """
                UPDATE app.customers
                SET profile = jsonb_set(COALESCE(profile, '{}'::jsonb), '{jd_customer_code}',
                                        '"CUST-HOLD-001"'::jsonb, true)
                WHERE data_scope='BUSINESS'
                """);

        // 一行就绪、一行待复核——部分确认放行就绪的那一行。
        Map<String, Object> readiness = (Map<String, Object>) getBatch(batchId).get("confirm_readiness");
        assertThat(readiness.get("ready_rows")).isEqualTo(1);
        assertThat(readiness.get("blocked_rows")).isEqualTo(1);

        ResponseEntity<Map> confirmed = confirm(batchId, "hold-confirm-001");
        assertThat(confirmed.getStatusCode())
                .withFailMessage("confirm body: %s", confirmed.getBody())
                .isEqualTo(HttpStatus.OK);

        // 就绪行一路跑到运单落库：正常情况下这一步就会产出回填文件。
        long shipmentId = jdbc.queryForObject(
                """
                SELECT s.id FROM app.shipments s
                JOIN app.raw_import_rows rir ON rir.order_id=s.order_id
                WHERE rir.import_batch_id=? AND rir.status='ACCEPTED' ORDER BY s.id LIMIT 1
                """,
                Long.class, Long.parseLong(batchId));
        ResponseEntity<Map> address = http.exchange(
                "/api/v1/shipments/" + shipmentId + "/jd-receiver-address",
                HttpMethod.PUT,
                new HttpEntity<>(Map.of(
                        "expected_version", 0,
                        "province", "上海市",
                        "city", "上海市",
                        "county", "浦东新区",
                        "detail_address", "测试路1号"),
                        writeHeaders("hold-address-001", "req-hold-address-001")),
                Map.class);
        assertThat(address.getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> submitted = http.exchange(
                "/api/v1/import-batches/" + batchId + "/jd-outbound-submit",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(), operatorHeaders()),
                Map.class);
        assertThat(submitted.getStatusCode()).isEqualTo(HttpStatus.OK);
        // submitted_count 把失败项也计入（items.size()），所以要看逐条结果里有没有 business_code。
        List<Map<String, Object>> submitItems = (List<Map<String, Object>>) submitted.getBody().get("items");
        assertThat(submitItems)
                .withFailMessage("就绪行必须真的建单成功，否则后面的断言证明不了任何事: %s", submitted.getBody())
                .isNotEmpty()
                .allSatisfy(item -> assertThat(item.get("business_code")).isNull());

        String erpDeliveryNo = jdbc.queryForObject(
                "SELECT erp_delivery_no FROM app.shipment_jd_outbounds WHERE shipment_id=?",
                String.class, shipmentId);
        String jdDeliveryNo = jdbc.queryForObject(
                "SELECT jd_delivery_no FROM app.shipment_jd_outbounds WHERE shipment_id=?",
                String.class, shipmentId);
        jd.outboundResult(new JdResult(true, "1000", "成功", "jd-query-hold-001",
                remote(shipmentId, erpDeliveryNo, jdDeliveryNo, "JD-HOLD-E2E-001")));

        poller.poll();
        awaitUntil("就绪行运单落库", Duration.ofSeconds(20), () -> jdbc.queryForObject(
                "SELECT count(*) FROM app.trackings WHERE shipment_id=?", Integer.class, shipmentId) == 1);

        // 核心断言：批次里还有待复核行，回填文件必须仍未生成。
        // 给自动生成留出与正常路径同样的时间窗，确保不是「还没轮到」而是真的被闸住。
        Thread.sleep(1_000);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.source_return_exports WHERE import_batch_id=?",
                Integer.class, Long.parseLong(batchId)))
                .withFailMessage("批次仍有待复核行时不得生成只含首批的回填文件")
                .isZero();
        List<?> returns = http.getForObject(
                "/api/v1/import-batches/" + batchId + "/source-return-exports", List.class);
        assertThat(returns).isEmpty();

        // 阻断行确实还在原地等处理，不是被悄悄丢掉。
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.raw_import_rows WHERE import_batch_id=? AND status='NEED_REVIEW'",
                Integer.class, Long.parseLong(batchId))).isEqualTo(1);
    }

    // ---------- helpers ----------

    private Map<String, Object> getBatch(String batchId) {
        return http.exchange(
                "/api/v1/import-batches/" + batchId,
                HttpMethod.GET,
                new HttpEntity<>(operatorHeaders()),
                Map.class).getBody();
    }

    private Map<String, Object> remote(
            long shipmentId, String erpDeliveryNo, String deliveryNo, String waybillNo) throws Exception {
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

    private String uploadTwoRows() throws Exception {
        HttpHeaders headers = operatorHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.set("Idempotency-Key", "hold-upload-001");
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(workbook()) {
            @Override
            public String getFilename() {
                return "caishixian-deliver-hold.xlsx";
            }
        });
        body.add("import_mode", "NEW");
        ResponseEntity<Map> response = http.exchange(
                "/api/v1/import-batches/source-orders",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class);
        assertThat(response.getStatusCode())
                .withFailMessage("upload body: %s", response.getBody())
                .isEqualTo(HttpStatus.CREATED);
        return response.getBody().get("id").toString();
    }

    /** 两行：第一行来源 SKU 已映射（就绪），第二行没有映射（待复核）。 */
    private byte[] workbook() throws Exception {
        List<String> headers = List.of(
                "主订单编号", "子订单编号", "供应商编码", "站点编码", "商品编号", "商品名称",
                "规格", "单位", "下单数量", "收货人", "联系电话", "省", "市", "区", "详细地址",
                "订单备注", "发货数量", "物流公司代码", "物流单号", "错误原因");
        List<List<String>> rows = List.of(
                List.of("HOLD-READY-001", "HOLD-READY-LINE-001", "SUPPLIER-ZIMU", "CSX-SITE-001",
                        "2047705", "子牧牛腱子(谷饲牛腱子)500g*2", "500g*2", "件", "1.000",
                        "张三", "13800000000", "上海市", "上海市", "浦东新区", "测试路1号", "", "", "", "", ""),
                List.of("HOLD-BLOCKED-001", "HOLD-BLOCKED-LINE-001", "SUPPLIER-ZIMU", "CSX-SITE-001",
                        "9999999", "未映射测试商品", "500g*1", "件", "1.000",
                        "李四", "13800000002", "上海市", "上海市", "浦东新区", "测试路2号", "", "", "", "", ""));
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("待发货订单");
            var header = sheet.createRow(0);
            for (int index = 0; index < headers.size(); index++) {
                header.createCell(index).setCellValue(headers.get(index));
            }
            for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
                var dataRow = sheet.createRow(rowIndex + 1);
                for (int index = 0; index < headers.size(); index++) {
                    dataRow.createCell(index).setCellValue(rows.get(rowIndex).get(index));
                }
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
        return http.exchange(
                "/api/v1/import-batches/" + batchId + "/confirm",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(), headers),
                Map.class);
    }

    private HttpHeaders operatorHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Operator", "source-hold-e2e");
        headers.setBasicAuth("source-hold-e2e", "source-hold-e2e-password");
        return headers;
    }

    private HttpHeaders writeHeaders(String idempotencyKey, String requestId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", idempotencyKey);
        headers.set("X-Request-Id", requestId);
        headers.set("X-Operator", "source-hold-e2e");
        headers.setBasicAuth("source-hold-e2e", "source-hold-e2e-password");
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
