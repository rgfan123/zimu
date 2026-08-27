package cn.zimu.fulfillment.file;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zimu.fulfillment.connector.jd.JdResult;
import cn.zimu.fulfillment.connector.jd.MockJdWarehouseClient;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
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
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 一个来源静态礼包按履约方拆行：京东直连、第三方文件，来源回填等待全部分片完成。 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "app.jd.client-mode=MOCK",
            "app.jd.write-mode=ON",
            "app.jd.outbound-authorized-operators=mixed-bundle-test",
            "app.gateway.basic-auth.username=mixed-bundle-test",
            "app.gateway.basic-auth.password=mixed-bundle-password",
            "app.jd.tracking-backfill.enabled=false",
            "app.message-worker.enabled=false",
            "app.file-store.root=${java.io.tmpdir}/zimu-mixed-provider-bundle-test"
        })
@Import(MixedProviderStaticBundlePipelineApiTest.ControlledJdConfig.class)
class MixedProviderStaticBundlePipelineApiTest {

    private static final String SOURCE_ORDER_REF = "WQ-MIXED-ORDER-001";
    private static final String SOURCE_BUNDLE_REF = "WQ-MIXED-BUNDLE-001";
    private static final String THIRD_PARTY_SKU_CODE = "TP-OSTRICH-FIXTURE-001";
    private static final String SECOND_THIRD_PARTY_SKU_CODE = "TP-SAUCE-FIXTURE-001";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;
    @Autowired ControlledJdClient jd;

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
            if (queryResult == null) throw new IllegalStateException("controlled JD query result missing");
            return queryResult;
        }
    }

    @BeforeEach
    void configureJdSdkRoute() {
        jdbc.update(
                "UPDATE app.fulfillment_providers SET config=config||"
                        + "'{\"sourceNo\":\"ISV-MIX-001\",\"warehouseNo\":\"WH-MIX-001\","
                        + "\"erpShopNo\":\"SHOP-MIX-001\",\"shopNo\":\"SHOP-MIX-001\","
                        + "\"ownerNo\":\"OWNER-MIX-001\",\"pin\":\"PIN-MIX-001\","
                        + "\"carrierNo\":\"JD\",\"salesPlatformSource\":\"6\","
                        + "\"townRequired\":false,\"outboundMode\":\"SDK\"}'::jsonb "
                        + "WHERE provider_code='JD'");
        jdbc.update(
                "UPDATE app.provider_skus SET external_codes=jsonb_set(external_codes,"
                        + "'{jd_pieces_per_unit}','1'::jsonb,true) WHERE provider_sku_code='JD-SKU-000001'");
    }

    @Test
    void mixedProviderBundleCreatesJdShipmentAndThirdPartyExportButNoFinalReturnAfterOnlyJdShips()
            throws Exception {
        Map<String, Object> jdSku = firstSkuForProvider("JD_WAREHOUSE");
        Map<String, Object> tpSku = createThirdPartySkuFixture();
        Map<String, Object> secondTpSku = createSecondThirdPartySkuFixture();
        String bundleId = createMixedBundle(
                "BUNDLE-MIXED-PROVIDER-001",
                "羊蝎子鸵鸟测试礼包",
                List.of(
                        jdSku.get("id").toString(),
                        tpSku.get("id").toString(),
                        secondTpSku.get("id").toString()),
                "mix-bundle-001");
        createSourceBundleMapping(bundleId);

        ResponseEntity<Map> uploaded = upload(workbook());
        assertThat(uploaded.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String batchId = uploaded.getBody().get("id").toString();
        assertThat(castMap(uploaded.getBody().get("row_counts"))).containsEntry("accepted", 1);

        Map<String, Object> orderPage = get("/api/v1/orders?query=" + SOURCE_ORDER_REF + "&page=0&size=20");
        String orderId = castMap(castList(orderPage.get("items")).getFirst()).get("id").toString();
        Map<String, Object> order = get("/api/v1/orders/" + orderId);
        List<Map<String, Object>> lines = castList(order.get("lines"));
        assertThat(lines).hasSize(2);
        assertThat(lines).allSatisfy(line -> assertThat(line)
                .containsEntry("line_type", "CUSTOM_BUNDLE")
                .containsEntry("bundle_id", bundleId)
                .containsEntry("processing_stage", "READY_TO_EXPORT"));
        assertThat(lines).extracting(line -> line.get("provider_id")).doesNotHaveDuplicates();
        assertThat(lines).extracting(line -> ((List<?>) line.get("components")).size())
                .containsExactlyInAnyOrder(1, 2);

        ResponseEntity<Map> confirmed = confirm(batchId);
        assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<?> jdShipmentIds = (List<?>) castMap(confirmed.getBody().get("outbound_routing"))
                .get("jd_sdk_shipment_ids");
        assertThat(jdShipmentIds).hasSize(1);
        List<?> exportIds = (List<?>) confirmed.getBody().get("generated_fulfillment_export_ids");
        assertThat(exportIds).hasSize(1);

        ResponseEntity<byte[]> thirdPartyDownload = http.exchange(
                "/api/v1/fulfillment-exports/" + exportIds.getFirst() + "/file",
                HttpMethod.GET,
                new HttpEntity<>(operatorHeaders()),
                byte[].class);
        assertThat(thirdPartyDownload.getStatusCode()).isEqualTo(HttpStatus.OK);
        byte[] thirdPartyFile = thirdPartyDownload.getBody();
        try (var exported = WorkbookFactory.create(new java.io.ByteArrayInputStream(thirdPartyFile))) {
            var sheet = exported.getSheetAt(0);
            DataFormatter formatter = new DataFormatter();
            assertThat(sheet.getLastRowNum()).isEqualTo(2);
            // v2 人读八列：渠道/礼包分组/履约方SKU 不再是文件列（对齐事实在导出明细表）；
            // 礼包同组体现在两行共用同一出库单号。
            java.util.Map<String, Integer> columns = new java.util.LinkedHashMap<>();
            for (int index = 0; index < sheet.getRow(0).getLastCellNum(); index++) {
                columns.put(formatter.formatCellValue(sheet.getRow(0).getCell(index)).strip(), index);
            }
            assertThat(columns.keySet())
                    .containsExactlyElementsOf(ProviderFileService.HUMAN_THIRD_PARTY_HEADERS);
            String firstOutbound = formatter.formatCellValue(sheet.getRow(1).getCell(columns.get("出库单号")));
            assertThat(firstOutbound).isNotBlank();
            assertThat(formatter.formatCellValue(sheet.getRow(2).getCell(columns.get("出库单号"))))
                    .isEqualTo(firstOutbound);
            assertThat(formatter.formatCellValue(sheet.getRow(1).getCell(columns.get("品名")))).isNotBlank();
        }

        String shipmentId = jdShipmentIds.getFirst().toString();
        jdbc.update(
                "UPDATE app.customers SET profile=jsonb_set(COALESCE(profile,'{}'::jsonb),"
                        + "'{jd_customer_code}','\"CUST-MIX-001\"'::jsonb,true) "
                        + "WHERE id=(SELECT customer_id FROM app.orders WHERE id=?)",
                Long.parseLong(orderId));
        ResponseEntity<Map> address = http.exchange(
                "/api/v1/shipments/" + shipmentId + "/jd-receiver-address",
                HttpMethod.PUT,
                new HttpEntity<>(Map.of(
                        "expected_version", 0,
                        "province", "上海市",
                        "city", "上海市",
                        "county", "浦东新区",
                        "detail_address", "测试路1号"), writeHeaders("mix-address-001")),
                Map.class);
        assertThat(address.getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> submitted = http.exchange(
                "/api/v1/shipments/" + shipmentId + "/jd-so-order",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(), writeHeaders("mix-submit-001")),
                Map.class);
        assertThat(submitted.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        String cargoJson = jdbc.queryForObject(
                "SELECT submitted_cargo_snapshot::text FROM app.shipment_jd_outbounds WHERE shipment_id=?",
                String.class,
                Long.parseLong(shipmentId));
        List<Map<String, Object>> cargos = objectMapper.readValue(cargoJson, new TypeReference<>() {});
        Map<String, Object> remote = new LinkedHashMap<>();
        remote.put("erpDeliveryNo", submitted.getBody().get("erp_delivery_no"));
        remote.put("deliveryNo", submitted.getBody().get("jd_delivery_no"));
        remote.put("warehouseNo", "WH-MIX-001");
        remote.put("status", "10020");
        remote.put("isSplit", "0");
        remote.put("splitDeliveryNos", "");
        remote.put("carrierInfo", Map.of(
                "carrierNo", "JD", "carrierName", "京东物流", "waybillNo", "JD-MIX-001"));
        remote.put("deliveryItemList", cargos.stream().map(cargo -> {
            Map<String, Object> delivered = new LinkedHashMap<>(cargo);
            delivered.put("realQuantity", cargo.get("planQuantity"));
            return Map.copyOf(delivered);
        }).toList());
        jd.queryResult(new JdResult(true, "1000", "成功", "jd-query-mix-001", remote));
        ResponseEntity<Map> backfilled = http.exchange(
                "/api/v1/shipments/" + shipmentId + "/jd-tracking-backfill",
                HttpMethod.POST,
                new HttpEntity<>(null, writeHeaders("mix-backfill-001")),
                Map.class);
        assertThat(backfilled.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> partial = get("/api/v1/orders/" + orderId);
        assertThat(partial.get("order_status")).isNotEqualTo("SHIPPED");
        assertThat(jdbc.queryForList(
                """
                SELECT f.shipping_progress
                FROM app.fulfillments f
                JOIN app.order_lines ol ON ol.id=f.order_line_id
                WHERE ol.order_id=?
                ORDER BY f.shipping_progress
                """,
                String.class,
                Long.parseLong(orderId))).containsExactly("NOT_SHIPPED", "SHIPPED");
        List<?> returns = http.getForObject(
                "/api/v1/import-batches/" + batchId + "/source-return-exports", List.class);
        assertThat(returns).isEmpty();

        ResponseEntity<Map> tracking = uploadTracking(
                exportIds.getFirst().toString(), fillThirdPartyTracking(thirdPartyFile));
        assertThat(tracking.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(castMap(tracking.getBody().get("business_results"))).containsEntry("shipped", 1);
        Map<String, Object> trackingReplay = get(
                "/api/v1/tracking-imports/" + tracking.getBody().get("id"));
        assertThat(trackingReplay.get("business_results")).isEqualTo(tracking.getBody().get("business_results"));
        long thirdPartyShipmentId = jdbc.queryForObject(
                """
                SELECT DISTINCT fei.shipment_id
                FROM app.fulfillment_export_items fei
                WHERE fei.fulfillment_export_id=?
                """,
                Long.class,
                Long.parseLong(exportIds.getFirst().toString()));
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.trackings WHERE shipment_id=?",
                Integer.class,
                thirdPartyShipmentId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                """
                SELECT f.shipping_progress
                FROM app.shipment_items si
                JOIN app.fulfillments f ON f.id=si.fulfillment_id
                WHERE si.shipment_id=?
                """,
                String.class,
                thirdPartyShipmentId)).isEqualTo("SHIPPED");
        assertThat(http.getForObject(
                "/api/v1/import-batches/" + batchId + "/source-return-exports", List.class)).isEmpty();
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*) FROM app.review_cases
                WHERE order_id=? AND status='OPEN' AND reason_code='MULTI_SHIPMENT_SOURCE_FOLLOWUP'
                """,
                Integer.class,
                Long.parseLong(orderId))).isEqualTo(1);
    }

    @Test
    void importRowsExposeJdBundlePartitionCargosAndFavorFrozenSubmittedValues() throws Exception {
        Map<String, Object> jdSku = firstSkuForProvider("JD_WAREHOUSE");
        Map<String, Object> tpSku = createThirdPartySkuFixture(
                "PROD-TP-CARGO-OSTRICH", "鸵鸟测试组件", "TP-CARGO-OSTRICH-001");
        Map<String, Object> secondTpSku = createThirdPartySkuFixture(
                "PROD-TP-CARGO-SAUCE", "测试礼包配料", "TP-CARGO-SAUCE-001");
        String bundleId = createMixedBundle(
                "BUNDLE-MIXED-CARGO-001", "羊蝎子鸵鸟测试礼包",
                List.of(jdSku.get("id").toString(), tpSku.get("id").toString(), secondTpSku.get("id").toString()),
                "mix-bundle-cargo-001");
        createSourceBundleMapping(
                "WQ-MIXED-CARGO-BUNDLE-001", "羊蝎子鸵鸟测试礼包", bundleId, "mix-source-bundle-cargo-001");

        ResponseEntity<Map> uploaded = upload(
                workbook("WQ-MIXED-CARGO-ORDER-001", "WQ-MIXED-CARGO-BUNDLE-001", "羊蝎子鸵鸟测试礼包"),
                "mix-upload-cargo-001");
        assertThat(uploaded.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String batchId = uploaded.getBody().get("id").toString();
        assertThat(castMap(uploaded.getBody().get("row_counts"))).containsEntry("accepted", 1);

        // 混合履约方礼包按 provider 分片：行投影只暴露京东分片的货品（数量 = 购买 1 ×
        // quantity_per_bundle 1 × jd_pieces_per_unit 1），第三方组件不得出现
        Map<String, Object> rows = get("/api/v1/import-batches/" + batchId + "/rows?page=0&size=20&status=ACCEPTED");
        Map<String, Object> row = castMap(castList(rows.get("items")).getFirst());
        List<Map<String, Object>> cargos = castList(row.get("jd_cargos"));
        assertThat(cargos).singleElement().satisfies(cargo -> assertThat(cargo)
                .containsEntry("provider_sku_code", "JD-SKU-000001")
                .containsEntry("plan_quantity", 1)
                .containsKey("product_name"));

        // 确认 → 地址确认 → 提交建单：提交后行投影优先冻结实际提交值
        ResponseEntity<Map> confirmed = confirm(batchId, "mix-confirm-cargo-001");
        assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<?> jdShipmentIds = (List<?>) castMap(confirmed.getBody().get("outbound_routing"))
                .get("jd_sdk_shipment_ids");
        assertThat(jdShipmentIds).hasSize(1);
        String shipmentId = jdShipmentIds.getFirst().toString();
        jdbc.update(
                "UPDATE app.customers SET profile=jsonb_set(COALESCE(profile,'{}'::jsonb),"
                        + "'{jd_customer_code}','\"CUST-MIX-CARGO-001\"'::jsonb,true) "
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
                        "detail_address", "测试路1号"), writeHeaders("mix-cargo-address-001")),
                Map.class);
        assertThat(address.getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> submitted = http.exchange(
                "/api/v1/shipments/" + shipmentId + "/jd-so-order",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(), writeHeaders("mix-cargo-submit-001")),
                Map.class);
        assertThat(submitted.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // 映射漂移（jd_pieces_per_unit 1→5）后行投影仍展示冻结的实际提交值 1 件
        jdbc.update(
                "UPDATE app.provider_skus SET external_codes=jsonb_set(external_codes,"
                        + "'{jd_pieces_per_unit}','5'::jsonb,true) WHERE provider_sku_code='JD-SKU-000001'");
        Map<String, Object> rowsAfterSubmit = get(
                "/api/v1/import-batches/" + batchId + "/rows?page=0&size=20&status=ACCEPTED");
        List<Map<String, Object>> frozenCargos =
                castList(castList(rowsAfterSubmit.get("items")).getFirst().get("jd_cargos"));
        assertThat(frozenCargos).singleElement().satisfies(cargo -> assertThat(cargo)
                .containsEntry("provider_sku_code", "JD-SKU-000001")
                .containsEntry("plan_quantity", 1));
    }

    @Test
    void missingThirdPartyProviderSkuLeavesOnlyThatPartitionInReviewAndStillRoutesJd() throws Exception {
        String orderRef = "WQ-MIXED-HOLD-ORDER-001";
        String bundleRef = "WQ-MIXED-HOLD-BUNDLE-001";
        Map<String, Object> jdSku = firstSkuForProvider("JD_WAREHOUSE");
        Map<String, Object> tpSku = createThirdPartySkuFixtureWithoutProviderMapping();
        String bundleId = createMixedBundle(
                "BUNDLE-MIXED-HOLD-001", "羊蝎子鸵鸟待映射礼包",
                jdSku.get("id").toString(), tpSku.get("id").toString(), "mix-bundle-hold-001");
        createSourceBundleMapping(
                bundleRef, "羊蝎子鸵鸟待映射礼包", bundleId, "mix-source-bundle-hold-001");

        ResponseEntity<Map> uploaded = upload(
                workbook(orderRef, bundleRef, "羊蝎子鸵鸟待映射礼包"), "mix-upload-hold-001");
        assertThat(uploaded.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String batchId = uploaded.getBody().get("id").toString();
        assertThat(castMap(uploaded.getBody().get("row_counts"))).containsEntry("accepted", 1);

        ResponseEntity<Map> confirmed = confirm(batchId, "mix-confirm-hold-001");
        assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((List<?>) confirmed.getBody().get("generated_fulfillment_export_ids")).isEmpty();
        List<?> jdShipmentIds = (List<?>) castMap(confirmed.getBody().get("outbound_routing"))
                .get("jd_sdk_shipment_ids");
        assertThat(jdShipmentIds).hasSize(1);

        Map<String, Object> orderPage = get("/api/v1/orders?query=" + orderRef + "&page=0&size=20");
        String orderId = castMap(castList(orderPage.get("items")).getFirst()).get("id").toString();
        Map<String, Object> order = get("/api/v1/orders/" + orderId);
        List<Map<String, Object>> lines = castList(order.get("lines"));
        assertThat(lines).filteredOn(line -> "NEED_REVIEW".equals(line.get("processing_stage")))
                .singleElement()
                .satisfies(line -> assertThat(line)
                        .containsEntry("exception_code", "PROVIDER_SKU_MAPPING_REQUIRED"));
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*) FROM app.shipments s
                JOIN app.fulfillment_providers fp ON fp.id=s.fulfillment_provider_id
                WHERE s.order_id=? AND fp.provider_type='THIRD_PARTY'
                """,
                Integer.class,
                Long.parseLong(orderId))).isZero();
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*) FROM app.review_cases
                WHERE order_id=? AND status='OPEN' AND reason_code='PROVIDER_SKU_MAPPING_REQUIRED'
                """,
                Integer.class,
                Long.parseLong(orderId))).isEqualTo(1);
        String reviewDetailJson = jdbc.queryForObject(
                """
                SELECT detail::text FROM app.review_cases
                WHERE order_id=? AND status='OPEN' AND reason_code='PROVIDER_SKU_MAPPING_REQUIRED'
                """,
                String.class,
                Long.parseLong(orderId));
        Map<String, Object> reviewDetail = objectMapper.readValue(reviewDetailJson, new TypeReference<>() {});
        assertThat(reviewDetail)
                .containsEntry("source_channel", "WANQI")
                .containsEntry("line_no", 2)
                .containsEntry("source_product_name", "羊蝎子鸵鸟待映射礼包")
                .containsEntry("source_specification", "规格:1080g;")
                .containsEntry("source_unit", "件")
                .containsEntry("source_quantity", "1.000")
                .containsEntry("source_sheet_name", "订单")
                .containsEntry("source_row_index", 2);
        assertThat(reviewDetail.get("missing_source_sku_refs"))
                .isEqualTo(List.of(bundleRef));
        assertThat((List<?>) reviewDetail.get("evidence_items")).singleElement().satisfies(item -> {
            assertThat(castMap(item))
                    .containsEntry("source_sku_ref", bundleRef)
                    .containsEntry("product_name", "鸵鸟80g待映射组件")
                    .containsEntry("specification", "80g/袋")
                    .containsEntry("unit", "袋")
                    .containsEntry("quantity", "1.000");
        });
        List<?> returns = http.getForObject(
                "/api/v1/import-batches/" + batchId + "/source-return-exports", List.class);
        assertThat(returns).isEmpty();
    }

    private Map<String, Object> createThirdPartySkuFixture() {
        return createThirdPartySkuFixture(
                "PROD-TP-OSTRICH-FIXTURE", "鸵鸟测试组件", THIRD_PARTY_SKU_CODE);
    }

    /** 每个用例必须使用独立的商品/编码，避免跨用例主数据唯一约束冲突。 */
    private Map<String, Object> createThirdPartySkuFixture(
            String productCode, String productName, String providerSkuCode) {
        long providerId = jdbc.queryForObject(
                "SELECT id FROM app.fulfillment_providers WHERE provider_type='THIRD_PARTY' ORDER BY id LIMIT 1",
                Long.class);
        long productId = jdbc.queryForObject(
                "INSERT INTO app.products(product_code,product_name) VALUES (?,?) RETURNING id",
                Long.class,
                productCode,
                productName);
        long skuId = jdbc.queryForObject(
                "INSERT INTO app.skus(product_id,fulfillment_provider_id,specification,unit) "
                        + "VALUES (?,?,'80g/袋','袋') RETURNING id",
                Long.class,
                productId,
                providerId);
        jdbc.update(
                "INSERT INTO app.provider_skus(fulfillment_provider_id,sku_id,provider_sku_code,active) "
                        + "VALUES (?,?,?,true)",
                providerId,
                skuId,
                providerSkuCode);
        return get("/api/v1/skus/" + skuId);
    }

    private Map<String, Object> createThirdPartySkuFixtureWithoutProviderMapping() {
        long providerId = jdbc.queryForObject(
                "SELECT id FROM app.fulfillment_providers WHERE provider_type='THIRD_PARTY' ORDER BY id LIMIT 1",
                Long.class);
        long productId = jdbc.queryForObject(
                "INSERT INTO app.products(product_code,product_name) "
                        + "VALUES ('PROD-TP-OSTRICH-HOLD','鸵鸟80g待映射组件') RETURNING id",
                Long.class);
        long skuId = jdbc.queryForObject(
                "INSERT INTO app.skus(product_id,fulfillment_provider_id,specification,unit) "
                        + "VALUES (?,?,'80g/袋','袋') RETURNING id",
                Long.class,
                productId,
                providerId);
        return get("/api/v1/skus/" + skuId);
    }

    private Map<String, Object> createSecondThirdPartySkuFixture() {
        long providerId = jdbc.queryForObject(
                "SELECT id FROM app.fulfillment_providers WHERE provider_type='THIRD_PARTY' ORDER BY id LIMIT 1",
                Long.class);
        long productId = jdbc.queryForObject(
                "INSERT INTO app.products(product_code,product_name) "
                        + "VALUES ('PROD-TP-SAUCE-FIXTURE','测试礼包配料') RETURNING id",
                Long.class);
        long skuId = jdbc.queryForObject(
                "INSERT INTO app.skus(product_id,fulfillment_provider_id,specification,unit) "
                        + "VALUES (?,?,'1袋','袋') RETURNING id",
                Long.class,
                productId,
                providerId);
        jdbc.update(
                "INSERT INTO app.provider_skus(fulfillment_provider_id,sku_id,provider_sku_code,active) "
                        + "VALUES (?,?,?,true)",
                providerId,
                skuId,
                SECOND_THIRD_PARTY_SKU_CODE);
        return get("/api/v1/skus/" + skuId);
    }

    private String createMixedBundle(String jdSkuId, String tpSkuId) {
        return createMixedBundle(
                "BUNDLE-MIXED-PROVIDER-001", "羊蝎子鸵鸟测试礼包",
                List.of(jdSkuId, tpSkuId), "mix-bundle-001");
    }

    private String createMixedBundle(
            String bundleCode, String bundleName, String jdSkuId, String tpSkuId, String idempotencyKey) {
        return createMixedBundle(bundleCode, bundleName, List.of(jdSkuId, tpSkuId), idempotencyKey);
    }

    private String createMixedBundle(
            String bundleCode, String bundleName, List<String> skuIds, String idempotencyKey) {
        Map<String, Object> body = Map.of(
                "bundle_code", bundleCode,
                "bundle_name", bundleName,
                "status", "ACTIVE",
                "items", skuIds.stream()
                        .map(skuId -> Map.of("sku_id", skuId, "quantity_per_bundle", "1"))
                        .toList());
        ResponseEntity<Map> response = http.exchange(
                "/api/v1/product-bundles",
                HttpMethod.POST,
                new HttpEntity<>(body, writeHeaders(idempotencyKey)),
                Map.class);
        assertThat(response.getStatusCode())
                .withFailMessage("bundle body: %s", response.getBody())
                .isEqualTo(HttpStatus.CREATED);
        return response.getBody().get("id").toString();
    }

    private void createSourceBundleMapping(String bundleId) {
        createSourceBundleMapping(
                SOURCE_BUNDLE_REF, "羊蝎子鸵鸟测试礼包", bundleId, "mix-source-bundle-001");
    }

    private void createSourceBundleMapping(
            String sourceBundleRef, String sourceBundleName, String bundleId, String idempotencyKey) {
        Map<String, Object> body = Map.of(
                "source_channel", "WANQI",
                "source_bundle_ref", sourceBundleRef,
                "source_bundle_name", sourceBundleName,
                "quantity_multiplier", "1",
                "bundle_id", bundleId,
                "active", true);
        ResponseEntity<Map> response = http.exchange(
                "/api/v1/source-bundle-mappings",
                HttpMethod.POST,
                new HttpEntity<>(body, writeHeaders(idempotencyKey)),
                Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private Map<String, Object> firstSkuForProvider(String providerType) {
        long skuId = jdbc.queryForObject(
                "SELECT s.id FROM app.skus s JOIN app.fulfillment_providers fp "
                        + "ON fp.id=s.fulfillment_provider_id WHERE fp.provider_type=? ORDER BY s.id LIMIT 1",
                Long.class,
                providerType);
        return get("/api/v1/skus/" + skuId);
    }

    private ResponseEntity<Map> upload(byte[] bytes) {
        return upload(bytes, "mix-upload-001");
    }

    private ResponseEntity<Map> upload(byte[] bytes, String idempotencyKey) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(bytes) {
            @Override public String getFilename() { return "mixed-bundle.xlsx"; }
        });
        body.add("import_mode", "NEW");
        HttpHeaders headers = operatorHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.set("Idempotency-Key", idempotencyKey);
        return http.exchange("/api/v1/import-batches/source-orders", HttpMethod.POST,
                new HttpEntity<>(body, headers), Map.class);
    }

    private ResponseEntity<Map> confirm(String batchId) {
        return confirm(batchId, "mix-confirm-001");
    }

    private ResponseEntity<Map> confirm(String batchId, String idempotencyKey) {
        return http.exchange(
                "/api/v1/import-batches/" + batchId + "/confirm",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(), writeHeaders(idempotencyKey)),
                Map.class);
    }

    private ResponseEntity<Map> uploadTracking(String exportId, byte[] bytes) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(bytes) {
            @Override public String getFilename() { return "mixed-bundle-tracking.xlsx"; }
        });
        body.add("import_mode", "NEW");
        HttpHeaders headers = operatorHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.set("Idempotency-Key", "mix-tracking-001");
        return http.exchange(
                "/api/v1/fulfillment-exports/" + exportId + "/tracking-imports",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class);
    }

    private byte[] fillThirdPartyTracking(byte[] bytes) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(new java.io.ByteArrayInputStream(bytes));
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.getSheetAt(0);
            for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                var row = sheet.getRow(rowIndex);
                row.getCell(18).setCellValue("SHIPPED");
                row.getCell(19).setCellValue(row.getCell(17).getStringCellValue());
                row.getCell(20).setCellValue("京东物流");
                row.getCell(21).setCellValue("TP-MIXED-BUNDLE-001");
                row.getCell(22).setCellValue("");
                row.getCell(23).setCellValue("");
            }
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private HttpHeaders operatorHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Operator", "mixed-bundle-test");
        return headers;
    }

    private HttpHeaders writeHeaders(String key) {
        HttpHeaders headers = operatorHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", key);
        headers.set("X-Request-Id", "req-" + key);
        headers.setBasicAuth("mixed-bundle-test", "mixed-bundle-password");
        return headers;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> get(String path) { return http.getForObject(path, Map.class); }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) { return (Map<String, Object>) value; }
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castList(Object value) { return (List<Map<String, Object>>) value; }

    private byte[] workbook() throws Exception {
        return workbook(SOURCE_ORDER_REF, SOURCE_BUNDLE_REF, "羊蝎子鸵鸟测试礼包");
    }

    private byte[] workbook(String sourceOrderRef, String sourceBundleRef, String productName) throws Exception {
        List<String> headers = List.of(
                "收货人姓名", "收货人手机号", "详细地址", "商品名称", "规格信息", "商品类型", "品牌",
                "一级分类", "二级分类", "三级分类", "一级逻辑分类", "二级逻辑分类", "三级逻辑分类",
                "售价", "购买数量", "成本价", "结算价", "优惠类型", "优惠金额", "供应商", "商品来源",
                "子订单状态", "售后状态", "退款类型", "供应商发货时间", "确认收货时间", "申请退款时间",
                "售后完成时间", "用户备注", "商家/客服备注", "订单处理形式", "订单ID", "聚合ID", "子订单ID",
                "供应商单号", "商品id", "供应商商品id", "门店id", "供应商sku id", "服务时效", "期望时间",
                "物流信息", "crm 单号", "订单总金额", "skuid", "sku名称", "不含运毛利额", "不含运毛利率",
                "含运毛利额", "含运毛利率", "订单类型", "实物售后");
        List<String> values = new ArrayList<>(java.util.Collections.nCopies(headers.size(), ""));
        values.set(0, "测试收货人");
        values.set(1, "13800000000");
        values.set(2, "上海/浦东新区/测试街道 测试路1号");
        values.set(3, productName);
        values.set(4, "规格:1080g;");
        values.set(5, "实体商品");
        values.set(6, "子牧");
        values.set(10, "节日礼包");
        values.set(11, "测试档期");
        values.set(12, "测试档");
        values.set(13, "100.00");
        values.set(14, "1");
        values.set(15, "75.00");
        values.set(16, "75.00");
        values.set(18, "0.00");
        values.set(19, "测试供应商");
        values.set(20, "自建商品");
        values.set(21, "超时未发货");
        values.set(30, "自动完成订单");
        values.set(31, sourceOrderRef);
        values.set(33, sourceOrderRef + "-LINE");
        values.set(35, "TEST-PRODUCT-ID");
        values.set(43, "100.00");
        values.set(44, sourceBundleRef);
        values.set(45, productName);
        values.set(48, "25.00");
        values.set(49, "0.25");
        values.set(50, "销售订单");
        values.set(51, "支持");
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
