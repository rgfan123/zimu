package cn.zimu.fulfillment.file;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zimu.fulfillment.common.web.CommandContext;
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
    @Autowired SourceOrderCandidateMaterializer candidateMaterializer;
    @Autowired ProviderFileService providerFileService;

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
        assertThat(castMap(uploaded.getBody().get("row_counts")))
                .containsEntry("accepted", 0)
                .containsEntry("total", 1);
        assertThat(uploaded.getBody()).containsEntry("error_detail", null);

        ResponseEntity<Map> confirmed = confirm(batchId);
        assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> orderPage = get("/api/v1/orders?query=" + SOURCE_ORDER_REF + "&page=0&size=20");
        String orderId = castMap(castList(orderPage.get("items")).getFirst()).get("id").toString();
        Map<String, Object> order = get("/api/v1/orders/" + orderId);
        List<Map<String, Object>> lines = castList(order.get("lines"));
        assertThat(lines).hasSize(2);
        assertThat(lines).allSatisfy(line -> assertThat(line)
                .containsEntry("line_type", "CUSTOM_BUNDLE")
                .containsEntry("bundle_id", bundleId));
        assertThat(lines).extracting(line -> line.get("processing_stage"))
                .containsExactlyInAnyOrder("READY_TO_EXPORT", "WAITING_PROVIDER");
        assertThat(lines).extracting(line -> line.get("provider_id")).doesNotHaveDuplicates();
        assertThat(lines).extracting(line -> ((List<?>) line.get("components")).size())
                .containsExactlyInAnyOrder(1, 2);

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
            assertThat(formatter.formatCellValue(sheet.getRow(1).getCell(6))).isEqualTo("万齐");
            assertThat(formatter.formatCellValue(sheet.getRow(1).getCell(9))).isNotBlank();
            assertThat(formatter.formatCellValue(sheet.getRow(1).getCell(13))).isEqualTo(THIRD_PARTY_SKU_CODE);
            assertThat(formatter.formatCellValue(sheet.getRow(2).getCell(9)))
                    .isEqualTo(formatter.formatCellValue(sheet.getRow(1).getCell(9)));
            assertThat(formatter.formatCellValue(sheet.getRow(2).getCell(13)))
                    .isEqualTo(SECOND_THIRD_PARTY_SKU_CODE);
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
    void unreadyThirdPartyComponentBlocksOnlyItsBundlePartition() throws Exception {
        String orderRef = "WQ-MIXED-PARTITION-BLOCK-ORDER-001";
        String bundleRef = "WQ-MIXED-PARTITION-BLOCK-BUNDLE-001";
        Map<String, Object> jdSku = firstSkuForProvider("JD_WAREHOUSE");
        Map<String, Object> tpSku = createThirdPartySkuFixture(
                "PROD-TP-PARTITION-BLOCK", "鸵鸟分片阻断组件", "TP-PARTITION-BLOCK-001");
        String bundleId = createMixedBundle(
                "BUNDLE-MIXED-PARTITION-BLOCK-001",
                "羊蝎子鸵鸟分片阻断礼包",
                jdSku.get("id").toString(),
                tpSku.get("id").toString(),
                "mix-bundle-partition-block-001");
        createSourceBundleMapping(
                bundleRef,
                "羊蝎子鸵鸟分片阻断礼包",
                bundleId,
                "mix-source-bundle-partition-block-001");

        ResponseEntity<Map> uploaded = upload(
                workbook(orderRef, bundleRef, "羊蝎子鸵鸟分片阻断礼包"),
                "mix-upload-partition-block-001");
        long batchId = Long.parseLong(uploaded.getBody().get("id").toString());
        candidateMaterializer.materializeStaged(
                batchId,
                new CommandContext(
                        "ticket05-materialize-request",
                        "ticket05-materialize-trace",
                        "ticket05-provider-ops"));

        jdbc.update("UPDATE app.skus SET active=FALSE WHERE id=?",
                Long.parseLong(tpSku.get("id").toString()));
        Map<String, Object> routing;
        try {
            routing = providerFileService.routeForSourceBatch(batchId, "ticket05-provider-ops");
        } finally {
            jdbc.update("UPDATE app.skus SET active=TRUE WHERE id=?",
                    Long.parseLong(tpSku.get("id").toString()));
        }

        assertThat((List<?>) routing.get("jd_sdk_shipment_ids")).hasSize(1);
        assertThat((List<?>) routing.get("file_export_ids")).isEmpty();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocked = (List<Map<String, Object>>) routing.get("blocked_partitions");
        assertThat(blocked).singleElement().satisfies(partition ->
                assertThat(((List<?>) partition.get("reason_codes")).stream().map(String::valueOf).toList())
                        .contains("SKU_INACTIVE"));
        List<Map<String, Object>> stages = jdbc.queryForList(
                """
                SELECT fp.provider_type, ol.processing_stage, ol.exception_code
                FROM app.order_lines ol
                JOIN app.fulfillments f ON f.order_line_id=ol.id
                JOIN app.fulfillment_providers fp ON fp.id=f.fulfillment_provider_id
                JOIN app.orders o ON o.id=ol.order_id
                WHERE o.source_import_batch_id=?
                ORDER BY fp.provider_type
                """,
                batchId);
        assertThat(stages).anySatisfy(line -> assertThat(line)
                .containsEntry("provider_type", "THIRD_PARTY")
                .containsEntry("processing_stage", "NEED_REVIEW")
                .containsEntry("exception_code", "SKU_INACTIVE"));
        assertThat(stages).anySatisfy(line -> assertThat(line)
                .containsEntry("provider_type", "JD_WAREHOUSE")
                .containsEntry("processing_stage", "READY_TO_EXPORT"));
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*) FROM app.shipments s
                JOIN app.fulfillment_providers fp ON fp.id=s.fulfillment_provider_id
                JOIN app.orders o ON o.id=s.order_id
                WHERE o.source_import_batch_id=? AND fp.provider_type='JD_WAREHOUSE'
                """,
                Integer.class,
                batchId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                """
                SELECT count(*) FROM app.fulfillment_exports fe
                JOIN app.fulfillment_export_items fei ON fei.fulfillment_export_id=fe.id
                JOIN app.raw_import_rows rir ON rir.id=fei.raw_import_row_id
                WHERE rir.import_batch_id=? AND fe.export_kind='THIRD_PARTY'
                """,
                Integer.class,
                batchId)).isZero();
    }

    @Test
    void thirdPartyInternalSelfMappingIsRoutableWithoutExternalVerificationClaim() throws Exception {
        String orderRef = "WQ-MIXED-TP-SELF-MAP-ORDER-001";
        String bundleRef = "WQ-MIXED-TP-SELF-MAP-BUNDLE-001";
        Map<String, Object> jdSku = firstSkuForProvider("JD_WAREHOUSE");
        Map<String, Object> tpSku = createThirdPartySelfMappedSkuFixture();
        String bundleId = createMixedBundle(
                "BUNDLE-MIXED-TP-SELF-MAP-001",
                "羊蝎子鸵鸟内部路由礼包",
                jdSku.get("id").toString(),
                tpSku.get("id").toString(),
                "mix-bundle-tp-self-map-001");
        createSourceBundleMapping(
                bundleRef,
                "羊蝎子鸵鸟内部路由礼包",
                bundleId,
                "mix-source-bundle-tp-self-map-001");

        ResponseEntity<Map> uploaded = upload(
                workbook(orderRef, bundleRef, "羊蝎子鸵鸟内部路由礼包"),
                "mix-upload-tp-self-map-001");
        ResponseEntity<Map> confirmed = confirm(
                uploaded.getBody().get("id").toString(),
                "mix-confirm-tp-self-map-001");

        assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<?> exportIds = (List<?>) confirmed.getBody().get("generated_fulfillment_export_ids");
        assertThat(exportIds).hasSize(1);
        Map<String, Object> export = get("/api/v1/fulfillment-exports/" + exportIds.getFirst());
        Map<String, Object> line = castMap(castList(export.get("lines")).getFirst());
        assertThat(line)
                .containsEntry("provider_sku_code", tpSku.get("sku_code"))
                .containsEntry("provider_sku_code_scope", "INTERNAL_ROUTING")
                .doesNotContainKey("provider_sku_externally_verified");
        Map<String, Object> evidence = objectMapper.readValue(
                jdbc.queryForObject(
                        "SELECT output_cells::text FROM app.fulfillment_export_items "
                                + "WHERE fulfillment_export_id=?",
                        String.class,
                        Long.parseLong(exportIds.getFirst().toString())),
                new TypeReference<>() {});
        assertThat(evidence)
                .containsEntry("_provider_sku_code_scope", "INTERNAL_ROUTING")
                .doesNotContainKey("provider_sku_externally_verified");

        // 模拟 V1 允许的历史数组证据；生产事实仍保持 append-only，测试只临时绕过更新触发器造夹具。
        jdbc.execute("ALTER TABLE app.fulfillment_export_items "
                + "DISABLE TRIGGER trg_fulfillment_export_item_append_only");
        try {
            jdbc.update(
                    "UPDATE app.fulfillment_export_items SET output_cells='[]'::jsonb "
                            + "WHERE fulfillment_export_id=?",
                    Long.parseLong(exportIds.getFirst().toString()));
        } finally {
            jdbc.execute("ALTER TABLE app.fulfillment_export_items "
                    + "ENABLE TRIGGER trg_fulfillment_export_item_append_only");
        }
        Map<String, Object> legacyArrayExport = get(
                "/api/v1/fulfillment-exports/" + exportIds.getFirst());
        assertThat(castMap(castList(legacyArrayExport.get("lines")).getFirst()))
                .containsEntry("provider_sku_code", tpSku.get("sku_code"))
                .doesNotContainKey("provider_sku_code_scope");
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
        assertThat(castMap(uploaded.getBody().get("row_counts")))
                .containsEntry("accepted", 0)
                .containsEntry("total", 1);

        ResponseEntity<Map> confirmed = confirm(batchId, "mix-confirm-cargo-001");
        assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.OK);

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
    void manualConfirmationRechecksEveryBundleComponentAgainstCurrentReadiness() throws Exception {
        Map<String, Object> jdSku = firstSkuForProvider("JD_WAREHOUSE");
        Map<String, Object> tpSku = createThirdPartySkuFixture(
                "PROD-TP-RECHECK-OSTRICH", "鸵鸟复核组件", "TP-RECHECK-OSTRICH-001");
        String bundleId = createMixedBundle(
                "BUNDLE-MIXED-RECHECK-001",
                "羊蝎子鸵鸟复核礼包",
                jdSku.get("id").toString(),
                tpSku.get("id").toString(),
                "mix-bundle-recheck-001");
        createSourceBundleMapping(
                "WQ-MIXED-RECHECK-BUNDLE-001",
                "羊蝎子鸵鸟复核礼包",
                bundleId,
                "mix-source-bundle-recheck-001");

        ResponseEntity<Map> uploaded = upload(
                workbook(
                        "WQ-MIXED-RECHECK-ORDER-001",
                        "WQ-MIXED-RECHECK-BUNDLE-001",
                        "羊蝎子鸵鸟复核礼包"),
                "mix-upload-recheck-001");
        String batchId = uploaded.getBody().get("id").toString();
        assertThat(castMap(uploaded.getBody().get("row_counts")))
                .containsEntry("accepted", 0)
                .containsEntry("total", 1);

        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM app.orders WHERE source_import_batch_id=?",
                        Integer.class,
                        Long.parseLong(batchId)))
                .as("上传只保存候选，人工确认前不得提前创建正式订单")
                .isZero();
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*) FROM app.fulfillments f
                        JOIN app.order_lines ol ON ol.id=f.order_line_id
                        JOIN app.orders o ON o.id=ol.order_id
                        WHERE o.source_import_batch_id=?
                        """,
                        Integer.class,
                        Long.parseLong(batchId)))
                .isZero();

        jdbc.update("UPDATE app.skus SET active=FALSE WHERE id=?",
                Long.parseLong(tpSku.get("id").toString()));

        ResponseEntity<Map> blocked = confirm(batchId, "mix-confirm-recheck-001");
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(blocked.getBody()).containsEntry("business_code", "IMPORT_BATCH_BLOCKED");
        assertThat(jdbc.queryForObject(
                        "SELECT confirmed_at IS NULL FROM app.import_batches WHERE id=?",
                        Boolean.class,
                        Long.parseLong(batchId)))
                .isTrue();
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*) FROM app.fulfillment_export_items fei
                        JOIN app.raw_import_rows rir ON rir.id=fei.raw_import_row_id
                        WHERE rir.import_batch_id=?
                        """,
                        Integer.class,
                        Long.parseLong(batchId)))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM app.orders WHERE source_import_batch_id=?",
                        Integer.class,
                        Long.parseLong(batchId)))
                .as("确认阶段任一组件阻断时整批仍不得留下正式订单")
                .isZero();
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*) FROM app.fulfillments f
                        JOIN app.order_lines ol ON ol.id=f.order_line_id
                        JOIN app.orders o ON o.id=ol.order_id
                        WHERE o.source_import_batch_id=?
                        """,
                        Integer.class,
                        Long.parseLong(batchId)))
                .isZero();

        jdbc.update("UPDATE app.skus SET active=TRUE WHERE id=?",
                Long.parseLong(tpSku.get("id").toString()));
        ResponseEntity<Map> recovered = confirm(batchId, "mix-confirm-recheck-recovered-001");
        assertThat(recovered.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM app.orders WHERE source_import_batch_id=?",
                        Integer.class,
                        Long.parseLong(batchId)))
                .isEqualTo(1);
    }

    @Test
    void sourceBundleMappingAndBomDriftBlockBeforeCreatingOrders() throws Exception {
        Map<String, Object> jdSku = firstSkuForProvider("JD_WAREHOUSE");
        Map<String, Object> tpSku = createThirdPartySkuFixture(
                "PROD-TP-BUNDLE-DRIFT", "鸵鸟礼包漂移组件", "TP-BUNDLE-DRIFT-001");
        String bundleId = createMixedBundle(
                "BUNDLE-MIXED-DRIFT-001",
                "羊蝎子鸵鸟漂移礼包",
                jdSku.get("id").toString(),
                tpSku.get("id").toString(),
                "mix-bundle-drift-001");
        String bundleRef = "WQ-MIXED-DRIFT-BUNDLE-001";
        createSourceBundleMapping(
                bundleRef, "羊蝎子鸵鸟漂移礼包", bundleId, "mix-source-bundle-drift-001");

        ResponseEntity<Map> mappingCandidate = upload(
                workbook("WQ-MIXED-DRIFT-MAPPING-001", bundleRef, "羊蝎子鸵鸟漂移礼包"),
                "mix-upload-drift-mapping-001");
        long mappingBatchId = Long.parseLong(mappingCandidate.getBody().get("id").toString());
        jdbc.update(
                "UPDATE app.source_channel_bundles SET active=FALSE "
                        + "WHERE source_channel='WANQI' AND source_bundle_ref=?",
                bundleRef);

        ResponseEntity<Map> mappingBlocked = confirm(
                Long.toString(mappingBatchId), "mix-confirm-drift-mapping-001");
        assertThat(mappingBlocked.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(mappingBlocked.getBody()).containsEntry("business_code", "IMPORT_BATCH_BLOCKED");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.orders WHERE source_import_batch_id=?",
                Integer.class,
                mappingBatchId)).isZero();

        jdbc.update(
                "UPDATE app.source_channel_bundles SET active=TRUE "
                        + "WHERE source_channel='WANQI' AND source_bundle_ref=?",
                bundleRef);
        ResponseEntity<Map> bomCandidate = upload(
                workbook("WQ-MIXED-DRIFT-BOM-001", bundleRef, "羊蝎子鸵鸟漂移礼包"),
                "mix-upload-drift-bom-001");
        long bomBatchId = Long.parseLong(bomCandidate.getBody().get("id").toString());
        jdbc.update(
                "UPDATE app.bundle_items SET quantity_per_bundle=quantity_per_bundle+1 "
                        + "WHERE bundle_id=? AND sku_id=?",
                Long.parseLong(bundleId),
                Long.parseLong(tpSku.get("id").toString()));

        ResponseEntity<Map> bomBlocked = confirm(
                Long.toString(bomBatchId), "mix-confirm-drift-bom-001");
        assertThat(bomBlocked.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(bomBlocked.getBody()).containsEntry("business_code", "IMPORT_BATCH_BLOCKED");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.orders WHERE source_import_batch_id=?",
                Integer.class,
                bomBatchId)).isZero();
    }

    @Test
    void unversionedPendingCandidateFailsClosedInsteadOfReinterpretingCurrentMappings() throws Exception {
        Map<String, Object> jdSku = firstSkuForProvider("JD_WAREHOUSE");
        Map<String, Object> tpSku = createThirdPartySkuFixture(
                "PROD-TP-UNVERSIONED", "鸵鸟旧候选组件", "TP-UNVERSIONED-001");
        String bundleId = createMixedBundle(
                "BUNDLE-MIXED-UNVERSIONED-001",
                "羊蝎子鸵鸟旧候选礼包",
                jdSku.get("id").toString(),
                tpSku.get("id").toString(),
                "mix-bundle-unversioned-001");
        String bundleRef = "WQ-MIXED-UNVERSIONED-BUNDLE-001";
        createSourceBundleMapping(
                bundleRef, "羊蝎子鸵鸟旧候选礼包", bundleId, "mix-source-bundle-unversioned-001");
        ResponseEntity<Map> uploaded = upload(
                workbook("WQ-MIXED-UNVERSIONED-ORDER-001", bundleRef, "羊蝎子鸵鸟旧候选礼包"),
                "mix-upload-unversioned-001");
        long batchId = Long.parseLong(uploaded.getBody().get("id").toString());
        jdbc.update(
                "UPDATE app.import_batches SET error_detail=error_detail-'candidate_snapshot_version' WHERE id=?",
                batchId);

        ResponseEntity<Map> blocked = confirm(Long.toString(batchId), "mix-confirm-unversioned-001");

        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(blocked.getBody()).containsEntry("business_code", "IMPORT_BATCH_BLOCKED");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.orders WHERE source_import_batch_id=?",
                Integer.class,
                batchId)).isZero();
    }

    @Test
    void legacyMaterializedBundleWithoutCandidateSnapshotStillRechecksEveryComponent() throws Exception {
        Map<String, Object> jdSku = firstSkuForProvider("JD_WAREHOUSE");
        Map<String, Object> tpSku = createThirdPartySkuFixture(
                "PROD-TP-LEGACY-OSTRICH", "鸵鸟遗留组件", "TP-LEGACY-OSTRICH-001");
        String bundleId = createMixedBundle(
                "BUNDLE-MIXED-LEGACY-001",
                "羊蝎子鸵鸟遗留礼包",
                jdSku.get("id").toString(),
                tpSku.get("id").toString(),
                "mix-bundle-legacy-001");
        createSourceBundleMapping(
                "WQ-MIXED-LEGACY-BUNDLE-001",
                "羊蝎子鸵鸟遗留礼包",
                bundleId,
                "mix-source-bundle-legacy-001");

        ResponseEntity<Map> uploaded = upload(
                workbook(
                        "WQ-MIXED-LEGACY-ORDER-001",
                        "WQ-MIXED-LEGACY-BUNDLE-001",
                        "羊蝎子鸵鸟遗留礼包"),
                "mix-upload-legacy-001");
        long batchId = Long.parseLong(uploaded.getBody().get("id").toString());
        assertThat(confirm(Long.toString(batchId), "mix-confirm-legacy-seed-001").getStatusCode())
                .isEqualTo(HttpStatus.OK);

        // 模拟升级前已经成单、尚待确认且没有 ticket-04 候选快照的历史批次。
        jdbc.update(
                "UPDATE app.import_batches SET confirmed_at=NULL, confirmed_by=NULL, error_detail=NULL WHERE id=?",
                batchId);
        jdbc.update("UPDATE app.skus SET active=FALSE WHERE id=?",
                Long.parseLong(tpSku.get("id").toString()));

        ResponseEntity<Map> blocked = confirm(Long.toString(batchId), "mix-confirm-legacy-recheck-001");
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(blocked.getBody()).containsEntry("business_code", "IMPORT_BATCH_BLOCKED");
        assertThat(jdbc.queryForObject(
                        "SELECT confirmed_at IS NULL FROM app.import_batches WHERE id=?",
                        Boolean.class,
                        batchId))
                .isTrue();
    }

    @Test
    void missingThirdPartyProviderSkuBlocksTheWholeMixedBundleBeforeOrdersAndRouting() throws Exception {
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
        assertThat(castMap(uploaded.getBody().get("row_counts")))
                .containsEntry("accepted", 0)
                .containsEntry("need_review", 1);

        ResponseEntity<Map> confirmed = confirm(batchId, "mix-confirm-hold-001");
        assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(confirmed.getBody()).containsEntry("business_code", "IMPORT_BATCH_BLOCKED");

        Map<String, Object> orderPage = get("/api/v1/orders?query=" + orderRef + "&page=0&size=20");
        assertThat(orderPage.get("total_elements")).isEqualTo(0);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM app.shipments s JOIN app.orders o ON o.id=s.order_id "
                                + "WHERE o.source_import_batch_id=?",
                        Integer.class,
                        Long.parseLong(batchId)))
                .isZero();
        String reviewDetailJson = jdbc.queryForObject(
                """
                SELECT detail::text FROM app.review_cases
                WHERE import_batch_id=? AND case_type='SOURCE_ORDER_CANDIDATE'
                  AND status='OPEN' AND reason_code='PROVIDER_MAPPING_REQUIRED'
                """,
                String.class,
                Long.parseLong(batchId));
        Map<String, Object> reviewDetail = objectMapper.readValue(reviewDetailJson, new TypeReference<>() {});
        assertThat(reviewDetail)
                .containsEntry("source_channel", "WANQI")
                .containsEntry("sku_id", tpSku.get("id").toString())
                .containsEntry("source_sheet_name", "订单")
                .containsEntry("source_row_index", 2);
        assertThat(reviewDetail.get("sku_code")).as("阻断结果必须标明具体内部 SKU").isNotNull();
        assertThat(((List<?>) reviewDetail.get("reason_codes")).stream().map(String::valueOf).toList())
                .contains("PROVIDER_MAPPING_REQUIRED");
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
                "INSERT INTO app.skus(product_id,fulfillment_provider_id,specification,unit,"
                        + "net_content_value,net_content_unit,package_count,package_unit) "
                        + "VALUES (?,?,'80g/袋','袋',80,'g',1,'袋') RETURNING id",
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
                "INSERT INTO app.skus(product_id,fulfillment_provider_id,specification,unit,"
                        + "net_content_value,net_content_unit,package_count,package_unit) "
                        + "VALUES (?,?,'80g/袋','袋',80,'g',1,'袋') RETURNING id",
                Long.class,
                productId,
                providerId);
        return get("/api/v1/skus/" + skuId);
    }

    private Map<String, Object> createThirdPartySelfMappedSkuFixture() {
        long providerId = jdbc.queryForObject(
                "SELECT id FROM app.fulfillment_providers WHERE provider_type='THIRD_PARTY' ORDER BY id LIMIT 1",
                Long.class);
        long productId = jdbc.queryForObject(
                "INSERT INTO app.products(product_code,product_name) "
                        + "VALUES ('PROD-TP-SELF-MAP','鸵鸟内部路由组件') RETURNING id",
                Long.class);
        Map<String, Object> sku = jdbc.queryForMap(
                """
                INSERT INTO app.skus(
                    product_id, fulfillment_provider_id, specification, unit,
                    net_content_value, net_content_unit, package_count, package_unit)
                VALUES (?,?,'80g/袋','袋',80,'g',1,'袋')
                RETURNING id, sku_code
                """,
                productId,
                providerId);
        jdbc.update(
                "INSERT INTO app.provider_skus(fulfillment_provider_id,sku_id,provider_sku_code,active) "
                        + "VALUES (?,?,?,true)",
                providerId,
                ((Number) sku.get("id")).longValue(),
                sku.get("sku_code").toString());
        Map<String, Object> result = new LinkedHashMap<>(get("/api/v1/skus/" + sku.get("id")));
        result.put("sku_code", sku.get("sku_code").toString());
        return Map.copyOf(result);
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
                "INSERT INTO app.skus(product_id,fulfillment_provider_id,specification,unit,"
                        + "net_content_value,net_content_unit,package_count,package_unit) "
                        + "VALUES (?,?,'1袋','袋',1,'袋',1,'袋') RETURNING id",
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
