package cn.zimu.fulfillment.file;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zimu.fulfillment.connector.jd.JdResult;
import cn.zimu.fulfillment.connector.jd.MockJdWarehouseClient;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import org.springframework.http.ContentDisposition;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 大者静态礼包从来源文件进入 CanonicalOrder，再确认生成京东 Shipment 的公共 seam。 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "app.jd.client-mode=MOCK",
            "app.jd.write-mode=ON",
            "app.jd.outbound-authorized-operators=wangqi-bundle-pipeline-test",
            "app.gateway.basic-auth.username=wangqi-bundle-pipeline-test",
            "app.gateway.basic-auth.password=wangqi-bundle-pipeline-password",
            "app.jd.tracking-backfill.enabled=false",
            "app.message-worker.enabled=false",
            "app.file-store.root=${java.io.tmpdir}/zimu-wangqi-bundle-pipeline-test"
        })
@Import(WangqiBundlePipelineApiTest.ControlledJdConfig.class)
class WangqiBundlePipelineApiTest {

    private static final String SOURCE_ORDER_REF = "WQ-BUNDLE-ORDER-001";
    private static final String SOURCE_BUNDLE_REF = "WQ-BUNDLE-REF-001";
    private static final String FIRST_EMG = "EMG-WANGQI-BUNDLE-001";
    private static final String SECOND_EMG = "EMG-WANGQI-BUNDLE-002";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;
    @Autowired ControlledJdClient jd;
    private long bundleId;

    @TestConfiguration
    static class ControlledJdConfig {
        @Bean
        @Primary
        ControlledJdClient controlledJdClient() {
            return new ControlledJdClient();
        }
    }

    static class ControlledJdClient extends MockJdWarehouseClient {
        private volatile JdResult queryResult;

        void queryResult(JdResult value) {
            queryResult = value;
        }

        @Override
        public JdResult queryOutboundOrder(Map<String, Object> request) {
            if (queryResult == null) {
                throw new IllegalStateException("controlled JD query result missing");
            }
            return queryResult;
        }
    }

    @BeforeEach
    void seedActiveJdBundleAndSourceMapping() {
        long jdProviderId = jdbc.queryForObject(
                "SELECT id FROM app.fulfillment_providers WHERE provider_code='JD'", Long.class);
        long firstSkuId = jdbc.queryForObject(
                "SELECT sku_id FROM app.provider_skus WHERE fulfillment_provider_id=? "
                        + "AND provider_sku_code='JD-SKU-000001'",
                Long.class,
                jdProviderId);
        // 同类多用例共享同一静态容器：所有主数据种子必须幂等（upsert / 先查后插），
        // 与 MixedProvider 用例「每个用例独立编码」的约定等价的另一种写法。
        long secondProductId = jdbc.queryForObject(
                "INSERT INTO app.products(product_code,product_name) "
                        + "VALUES ('PROD-WANGQI-COMP-002','礼包组件二') "
                        + "ON CONFLICT (product_code) DO UPDATE SET product_name=EXCLUDED.product_name "
                        + "RETURNING id",
                Long.class);
        Long existingSecondSku = jdbc.queryForList(
                "SELECT id FROM app.skus WHERE product_id=? AND fulfillment_provider_id=?",
                Long.class,
                secondProductId,
                jdProviderId).stream().findFirst().orElse(null);
        long secondSkuId;
        if (existingSecondSku == null) {
            secondSkuId = jdbc.queryForObject(
                    "INSERT INTO app.skus(product_id,fulfillment_provider_id,specification,unit) "
                            + "VALUES (?,?, '250g/袋','袋') RETURNING id",
                    Long.class,
                    secondProductId,
                    jdProviderId);
        } else {
            secondSkuId = existingSecondSku;
        }
        jdbc.update(
                "UPDATE app.provider_skus SET external_codes=jsonb_set(external_codes, "
                        + "'{jd_pieces_per_unit}','1'::jsonb,true) WHERE id=(SELECT id FROM app.provider_skus "
                        + "WHERE fulfillment_provider_id=? AND sku_id=?)",
                jdProviderId,
                firstSkuId);
        jdbc.update(
                "INSERT INTO app.provider_skus(fulfillment_provider_id,sku_id,provider_sku_code,external_codes) "
                        + "VALUES (?,?,?, '{\"jd_pieces_per_unit\":1}'::jsonb) "
                        + "ON CONFLICT (fulfillment_provider_id, sku_id) DO UPDATE SET "
                        + "provider_sku_code=EXCLUDED.provider_sku_code, external_codes=EXCLUDED.external_codes",
                jdProviderId,
                secondSkuId,
                SECOND_EMG);

        addWangqiSkuMapping(FIRST_EMG, firstSkuId, "礼包组件一", "500g/盒");
        addWangqiSkuMapping(SECOND_EMG, secondSkuId, "礼包组件二", "250g/袋");

        bundleId = jdbc.queryForObject(
                "INSERT INTO app.product_bundles(bundle_code,bundle_name,status) "
                        + "VALUES ('BUNDLE-WANGQI-001','万齐静态礼包','DRAFT') "
                        + "ON CONFLICT (bundle_code) DO UPDATE SET "
                        + "bundle_name=EXCLUDED.bundle_name, status='DRAFT' RETURNING id",
                Long.class);
        jdbc.update("DELETE FROM app.bundle_items WHERE bundle_id=?", bundleId);
        jdbc.update(
                "INSERT INTO app.bundle_items(bundle_id,sort_no,sku_id,quantity_per_bundle,emg_code_snapshot) "
                        + "VALUES (?,?,?,?,?)",
                bundleId,
                1,
                firstSkuId,
                1,
                FIRST_EMG);
        jdbc.update(
                "INSERT INTO app.bundle_items(bundle_id,sort_no,sku_id,quantity_per_bundle,emg_code_snapshot) "
                        + "VALUES (?,?,?,?,?)",
                bundleId,
                2,
                secondSkuId,
                2,
                SECOND_EMG);
        jdbc.update("UPDATE app.product_bundles SET status='ACTIVE' WHERE id=?", bundleId);
        jdbc.update(
                "INSERT INTO app.source_channel_bundles(source_channel,source_bundle_ref,source_bundle_name,"
                        + "quantity_multiplier,bundle_id,active) VALUES ('DAZHE',?,?,1,?,true) "
                        + "ON CONFLICT (source_channel, source_bundle_ref) DO UPDATE SET "
                        + "source_bundle_name=EXCLUDED.source_bundle_name, bundle_id=EXCLUDED.bundle_id, active=true",
                SOURCE_BUNDLE_REF,
                "来源大者礼包",
                bundleId);

        jdbc.update(
                "UPDATE app.fulfillment_providers SET config=config||"
                        + "'{\"sourceNo\":\"ISV-WQ-001\",\"warehouseNo\":\"WH-WQ-001\","
                        + "\"erpShopNo\":\"SHOP-WQ-001\",\"shopNo\":\"SHOP-WQ-001\","
                        + "\"ownerNo\":\"OWNER-WQ-001\",\"pin\":\"PIN-WQ-001\","
                        + "\"carrierNo\":\"JD\",\"salesPlatformSource\":\"6\","
                        + "\"townRequired\":false,\"outboundMode\":\"SDK\"}'::jsonb "
                        + "WHERE id=?",
                jdProviderId);
    }

    @Test
    void activeSourceBundleUploadsAsCustomBundleAndConfirmationCreatesJdShipment() throws Exception {
        ResponseEntity<Map> uploaded = upload(workbook());

        assertThat(uploaded.getStatusCode())
                .withFailMessage("upload body: %s", uploaded.getBody())
                .isEqualTo(HttpStatus.CREATED);
        Map<String, Object> batch = uploaded.getBody();
        assertThat(batch).containsEntry("source_channel", "DAZHE");
        assertThat(castMap(batch.get("row_counts")))
                .containsEntry("accepted", 1)
                .containsEntry("need_review", 0);
        String batchId = batch.get("id").toString();

        Map<String, Object> batchRows = get("/api/v1/import-batches/" + batchId + "/rows?page=0&size=20");
        assertThat(batchRows).containsEntry("total_elements", 1);
        Map<String, Object> batchRow = castMap(((List<?>) batchRows.get("items")).getFirst());
        assertThat(batchRow)
                .containsEntry("status", "ACCEPTED")
                .containsKeys("order_id", "order_line_id");
        assertThat(castMap(batchRow.get("parsed"))).containsEntry("source_sku_ref", SOURCE_BUNDLE_REF);

        Map<String, Object> orderPage = get("/api/v1/orders?query=" + SOURCE_ORDER_REF + "&page=0&size=20");
        assertThat(orderPage).containsEntry("total_elements", 1);
        String orderId = ((Map<?, ?>) ((List<?>) orderPage.get("items")).getFirst()).get("id").toString();
        Map<String, Object> order = get("/api/v1/orders/" + orderId);
        assertThat(castMap(order.get("settlement")))
                .containsEntry("method", "IMMEDIATE")
                .containsEntry("settlement_time", "2026-08-20T02:01:00Z");
        Map<String, Object> line = castMap(((List<?>) order.get("lines")).getFirst());
        assertThat(line)
                .containsEntry("line_type", "CUSTOM_BUNDLE")
                .containsEntry("bundle_id", Long.toString(bundleId))
                .containsEntry("product_name", "来源大者礼包")
                .containsEntry("requested_quantity", "2.000")
                .containsEntry("processing_stage", "READY_TO_EXPORT");
        List<Map<String, Object>> components = castList(line.get("components"));
        assertThat(components).hasSize(2);
        assertThat(components).extracting(item -> item.get("quantity_per_bundle"))
                .containsExactly("1.000", "2.000");
        assertThat(components).extracting(item -> item.get("total_quantity"))
                .containsExactly("2.000", "4.000");

        ResponseEntity<Map> confirmed = confirm(batchId);
        assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> routing = castMap(confirmed.getBody().get("outbound_routing"));
        List<?> shipmentIds = (List<?>) routing.get("jd_sdk_shipment_ids");
        assertThat(shipmentIds).hasSize(1);

        Map<String, Object> shipment = get("/api/v1/shipments/" + shipmentIds.getFirst());
        assertThat(shipment)
                .containsEntry("order_id", orderId)
                .containsEntry("shipment_status", "CREATED");
        assertThat(castList(shipment.get("items"))).singleElement().satisfies(item -> assertThat(item)
                .containsEntry("order_line_id", line.get("id"))
                .containsEntry("instructed_quantity", "2.000"));

        jdbc.update(
                "UPDATE app.customers SET profile=jsonb_set(COALESCE(profile,'{}'::jsonb),"
                        + "'{jd_customer_code}','\"CUST-WQ-001\"'::jsonb,true) "
                        + "WHERE id=(SELECT customer_id FROM app.orders WHERE id=?)",
                Long.parseLong(orderId));
        String shipmentId = shipmentIds.getFirst().toString();
        ResponseEntity<Map> address = http.exchange(
                "/api/v1/shipments/" + shipmentId + "/jd-receiver-address",
                HttpMethod.PUT,
                new HttpEntity<>(Map.of(
                        "expected_version", 0,
                        "province", "上海市",
                        "city", "上海市",
                        "county", "浦东新区",
                        "detail_address", "测试路1号"),
                        writeHeaders("wangqi-address-001", "req-wangqi-address-001")),
                Map.class);
        assertThat(address.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> submitted = http.exchange(
                "/api/v1/shipments/" + shipmentId + "/jd-so-order",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(), writeHeaders("wangqi-submit-001", "req-wangqi-submit-001")),
                Map.class);
        assertThat(submitted.getStatusCode())
                .withFailMessage("submit body: %s", submitted.getBody())
                .isEqualTo(HttpStatus.CREATED);

        String cargoJson = jdbc.queryForObject(
                "SELECT submitted_cargo_snapshot::text FROM app.shipment_jd_outbounds WHERE shipment_id=?",
                String.class,
                Long.parseLong(shipmentId));
        List<Map<String, Object>> cargos = objectMapper.readValue(cargoJson, new TypeReference<>() {});
        Map<String, Object> remote = new LinkedHashMap<>();
        remote.put("erpDeliveryNo", submitted.getBody().get("erp_delivery_no"));
        remote.put("deliveryNo", submitted.getBody().get("jd_delivery_no"));
        remote.put("warehouseNo", "WH-WQ-001");
        remote.put("status", "10020");
        remote.put("isSplit", "0");
        remote.put("splitDeliveryNos", "");
        remote.put("carrierInfo", Map.of(
                "carrierNo", "JD", "carrierName", "京东物流", "waybillNo", "JD-WANGQI-001"));
        remote.put("deliveryItemList", cargos.stream().map(cargo -> {
            Map<String, Object> delivered = new LinkedHashMap<>(cargo);
            delivered.put("realQuantity", cargo.get("planQuantity"));
            return Map.copyOf(delivered);
        }).toList());
        jd.queryResult(new JdResult(true, "1000", "成功", "jd-query-wangqi-001", remote));

        ResponseEntity<Map> backfilled = http.exchange(
                "/api/v1/shipments/" + shipmentId + "/jd-tracking-backfill",
                HttpMethod.POST,
                new HttpEntity<>(null, writeHeaders("wangqi-backfill-001", "req-wangqi-backfill-001")),
                Map.class);
        assertThat(backfilled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(backfilled.getBody())
                .containsEntry("poll_status", "TRACKED")
                .containsEntry("tracking_number", "JD-WANGQI-001");

        List<?> returns = http.getForObject(
                "/api/v1/import-batches/" + batchId + "/source-return-exports", List.class);
        assertThat(returns).singleElement();
        String returnId = ((Map<?, ?>) returns.getFirst()).get("id").toString();
        ResponseEntity<byte[]> returnedDownload = http.exchange(
                "/api/v1/source-return-exports/" + returnId + "/file",
                HttpMethod.GET,
                new HttpEntity<>(operatorHeaders()),
                byte[].class);
        assertThat(ContentDisposition.parse(returnedDownload.getHeaders()
                        .getFirst(HttpHeaders.CONTENT_DISPOSITION)).getFilename())
                .isEqualTo("大者-来源回填-" + returnId + ".xlsx");
        byte[] returned = returnedDownload.getBody();
        try (var returnedWorkbook = WorkbookFactory.create(new ByteArrayInputStream(returned))) {
            var returnedSheet = returnedWorkbook.getSheetAt(0);
            var header = returnedSheet.getRow(0);
            var row = returnedSheet.getRow(1);
            DataFormatter formatter = new DataFormatter();
            Map<String, Integer> columns = new LinkedHashMap<>();
            for (int index = 0; index < header.getLastCellNum(); index++) {
                columns.put(formatter.formatCellValue(header.getCell(index)), index);
            }
            assertThat((int) header.getLastCellNum()).isEqualTo(15);
            assertThat(formatter.formatCellValue(row.getCell(columns.get("订单商品状态")))).isEqualTo("已发货");
            assertThat(formatter.formatCellValue(row.getCell(columns.get("快递单号")))).isEqualTo("JD-WANGQI-001");
            assertThat(formatter.formatCellValue(row.getCell(columns.get("快递公司")))).isEqualTo("京东物流");
        }

    }

    private void addWangqiSkuMapping(String emg, long skuId, String productName, String specification) {
        jdbc.update(
                "INSERT INTO app.source_channel_skus(source_channel,source_sku_ref,source_product_name,"
                        + "source_specification,quantity_multiplier,sku_id,active) "
                        + "VALUES ('DAZHE',?,?,?,?,?,true) "
                        + "ON CONFLICT (source_channel, source_sku_ref) DO UPDATE SET "
                        + "source_product_name=EXCLUDED.source_product_name, "
                        + "source_specification=EXCLUDED.source_specification, "
                        + "quantity_multiplier=EXCLUDED.quantity_multiplier, sku_id=EXCLUDED.sku_id, active=true",
                emg,
                productName,
                specification,
                1,
                skuId);
    }

    @Test
    void importRowsExposeAllJdBundleComponentCargosAndFavorFrozenSubmittedValues() throws Exception {
        // 独立渠道订单号：避免与既有用例同内容（sha256 去重）复用同一批次
        ResponseEntity<Map> uploaded = upload(workbook("WQ-BUNDLE-ORDER-002"), "wangqi-cargo-upload-002");
        assertThat(uploaded.getStatusCode())
                .withFailMessage("upload body: %s", uploaded.getBody())
                .isEqualTo(HttpStatus.CREATED);
        String batchId = uploaded.getBody().get("id").toString();

        // 同一原始行投影出 2 条京东货品（与 SDK 建单 cargoInfos 同序、同量）：
        // 组件一 = 2 件（购买 2 × quantity_per_bundle 1 × jd_pieces_per_unit 1），
        // 组件二 = 4 件（2 × 2 × 1）；两者都必须是冻结前实时换算的精确整数。
        Map<String, Object> rows = get("/api/v1/import-batches/" + batchId + "/rows?page=0&size=20");
        Map<String, Object> row = castMap(((List<?>) rows.get("items")).getFirst());
        List<Map<String, Object>> cargos = castList(row.get("jd_cargos"));
        assertThat(cargos).hasSize(2);
        assertThat(cargos).extracting(cargo -> cargo.get("provider_sku_code"))
                .containsExactly("JD-SKU-000001", SECOND_EMG);
        assertThat(cargos).extracting(cargo -> cargo.get("plan_quantity"))
                .containsExactly(2, 4);

        // 确认 → 地址确认 → 提交建单：实际提交快照同样包含两条货品
        ResponseEntity<Map> confirmed = confirm(
                batchId, "wangqi-cargo-confirm-002", "req-wangqi-cargo-confirm-002");
        assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<?> jdShipmentIds = (List<?>) castMap(confirmed.getBody().get("outbound_routing"))
                .get("jd_sdk_shipment_ids");
        assertThat(jdShipmentIds).hasSize(1);
        String shipmentId = jdShipmentIds.getFirst().toString();
        jdbc.update(
                "UPDATE app.customers SET profile=jsonb_set(COALESCE(profile,'{}'::jsonb),"
                        + "'{jd_customer_code}','\"CUST-WQ-CARGO-002\"'::jsonb,true) "
                        + "WHERE id=(SELECT customer_id FROM app.orders "
                        + "WHERE id=(SELECT order_id FROM app.shipments WHERE id=?))",
                Long.parseLong(shipmentId));
        ResponseEntity<Map> address = http.exchange(
                "/api/v1/shipments/" + shipmentId + "/jd-receiver-address",
                HttpMethod.PUT,
                new HttpEntity<>(Map.of(
                        "expected_version", 0,
                        "province", "上海市",
                        "city", "上海市",
                        "county", "浦东新区",
                        "detail_address", "测试路1号"),
                        writeHeaders("wangqi-cargo-address-002", "req-wangqi-cargo-address-002")),
                Map.class);
        assertThat(address.getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> submitted = http.exchange(
                "/api/v1/shipments/" + shipmentId + "/jd-so-order",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(), writeHeaders("wangqi-cargo-submit-002", "req-wangqi-cargo-submit-002")),
                Map.class);
        assertThat(submitted.getStatusCode())
                .withFailMessage("submit body: %s", submitted.getBody())
                .isEqualTo(HttpStatus.CREATED);

        String cargoJson = jdbc.queryForObject(
                "SELECT submitted_cargo_snapshot::text FROM app.shipment_jd_outbounds WHERE shipment_id=?",
                String.class,
                Long.parseLong(shipmentId));
        List<Map<String, Object>> submittedCargos = objectMapper.readValue(cargoJson, new TypeReference<>() {});
        assertThat(submittedCargos).extracting(cargo -> cargo.get("goodsNo"))
                .containsExactly("JD-SKU-000001", SECOND_EMG);
        assertThat(submittedCargos).extracting(cargo -> cargo.get("planQuantity"))
                .containsExactly(2, 4);

        // 提交后行投影优先冻结实际提交值：两组件 jd_pieces_per_unit 漂移（1→5 / 1→7）也不漂移
        jdbc.update(
                "UPDATE app.provider_skus SET external_codes=jsonb_set(external_codes,"
                        + "'{jd_pieces_per_unit}','5'::jsonb,true) WHERE provider_sku_code='JD-SKU-000001'");
        jdbc.update(
                "UPDATE app.provider_skus SET external_codes=jsonb_set(external_codes,"
                        + "'{jd_pieces_per_unit}','7'::jsonb,true) WHERE provider_sku_code=?",
                SECOND_EMG);
        Map<String, Object> rowsAfterSubmit = get(
                "/api/v1/import-batches/" + batchId + "/rows?page=0&size=20");
        List<Map<String, Object>> frozenCargos =
                castList(castMap(((List<?>) rowsAfterSubmit.get("items")).getFirst()).get("jd_cargos"));
        assertThat(frozenCargos).extracting(cargo -> cargo.get("provider_sku_code"))
                .containsExactly("JD-SKU-000001", SECOND_EMG);
        assertThat(frozenCargos).extracting(cargo -> cargo.get("plan_quantity"))
                .containsExactly(2, 4);
    }

    private ResponseEntity<Map> upload(byte[] bytes) {
        return upload(bytes, "wangqi-bundle-upload-001");
    }

    private ResponseEntity<Map> upload(byte[] bytes, String idempotencyKey) {
        HttpHeaders headers = operatorHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.set("Idempotency-Key", idempotencyKey);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return "wangqi-bundle.xlsx";
            }
        });
        body.add("import_mode", "NEW");
        return http.exchange(
                "/api/v1/import-batches/source-orders",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class);
    }

    private ResponseEntity<Map> confirm(String batchId) {
        return confirm(batchId, "wangqi-bundle-confirm-001", "req-wangqi-bundle-confirm-001");
    }

    private ResponseEntity<Map> confirm(String batchId, String idempotencyKey, String requestId) {
        HttpHeaders headers = operatorHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", idempotencyKey);
        headers.set("X-Request-Id", requestId);
        headers.setBasicAuth("wangqi-bundle-pipeline-test", "wangqi-bundle-pipeline-password");
        return http.exchange(
                "/api/v1/import-batches/" + batchId + "/confirm",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(), headers),
                Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> get(String path) {
        return http.getForObject(path, Map.class);
    }

    private HttpHeaders operatorHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Operator", "wangqi-bundle-pipeline-test");
        return headers;
    }

    private HttpHeaders writeHeaders(String idempotencyKey, String requestId) {
        HttpHeaders headers = operatorHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", idempotencyKey);
        headers.set("X-Request-Id", requestId);
        headers.setBasicAuth("wangqi-bundle-pipeline-test", "wangqi-bundle-pipeline-password");
        return headers;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castList(Object value) {
        return (List<Map<String, Object>>) value;
    }

    private byte[] workbook() throws Exception {
        return workbook(SOURCE_ORDER_REF);
    }

    private byte[] workbook(String orderRef) throws Exception {
        List<String> headers = List.of(
                "渠道订单号", "主商品编码", "供应商商品名称", "商品名称", "订单商品状态", "采购单价（元）",
                "商品数量", "收货人", "收货人手机", "收货人详细地址", "预计到货时间", "渠道下单时间",
                "渠道支付时间", "快递单号", "快递公司");
        List<String> values = List.of(
                orderRef, SOURCE_BUNDLE_REF, "来源供应商礼包", "来源大者礼包", "待发货", "100.00",
                "2", "测试收货人", "13800000000", "上海市浦东新区测试路1号", "2026-08-22 12:00:00",
                "2026-08-20 10:00:00", "2026-08-20 10:01:00", "", "");
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("订单");
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
}
