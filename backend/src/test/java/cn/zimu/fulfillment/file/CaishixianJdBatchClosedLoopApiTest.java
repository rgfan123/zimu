package cn.zimu.fulfillment.file;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zimu.fulfillment.common.web.CommandContext;
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
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 彩食鲜文件到京东 Mock 发货、运单回填及唯一原模板导出的批次级公开闭环。 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "app.jd.write-mode=ON",
            // 显式钉住 MOCK，避免操作者环境里的 JD_LOP_CLIENT_MODE=REAL 泄漏进测试：
            // 提交前实时库存判定里的京东商品只读核验会因真实客户端缺凭据而阻断（JD_STOCK_CHECK_BLOCKED）。
            "app.jd.client-mode=MOCK",
            "app.jd.outbound-authorized-operators=caishixian-e2e",
            "app.gateway.basic-auth.username=caishixian-e2e",
            "app.gateway.basic-auth.password=caishixian-e2e-password",
            "app.jd.tracking-backfill.enabled=false",
            "app.message-worker.enabled=false",
            "app.file-store.root=${java.io.tmpdir}/zimu-caishixian-jd-closed-loop-test"
        })
@Import(CaishixianJdBatchClosedLoopApiTest.ControlledJdConfig.class)
class CaishixianJdBatchClosedLoopApiTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;
    @Autowired ControlledJdClient jd;
    @Autowired SourceOrderCandidateMaterializer candidateMaterializer;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    cn.zimu.fulfillment.connector.wecom.WecomConnectionManager ignoredWecomConnectionManager;

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
    void configureCatalogAndJd() {
        jdbc.update(
                """
                UPDATE app.fulfillment_providers
                SET config=('{' ||
                    '"sourceNo":"ISV-CSX-001","warehouseNo":"WH-CSX-001",' ||
                    '"erpShopNo":"SHOP-CSX-001","shopNo":"SHOP-CSX-001",' ||
                    '"ownerNo":"OWNER-CSX-001","customerCode":"CUST-CSX-001",' ||
                    '"pin":"PIN-CSX-001","carrierNo":"JD","salesPlatformSource":"6",' ||
                    '"townRequired":false}')::jsonb
                WHERE provider_code='JD'
                """);
        // jd-real-sdk-switch 02: 京东客户编码按订单客户取值,由客户档案维护
        jdbc.update(
                """
                UPDATE app.customers
                SET profile = jsonb_set(profile, '{jd_customer_code}', '"CUST-CSX-001"'::jsonb, true)
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
    void oneImportOneConfirmationAndCompletedJdBackfillProduceOneFilledOriginalWorkbook() throws Exception {
        ResponseEntity<Map> uploaded = uploadSource(caishixianWorkbook());
        assertThat(uploaded.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> batch = uploaded.getBody();
        String batchId = batch.get("id").toString();
        Map<?, ?> rowCounts = (Map<?, ?>) batch.get("row_counts");
        assertThat(rowCounts.get("accepted")).isEqualTo(0);
        assertThat(rowCounts.get("total")).isEqualTo(1);
        assertThat(rowCounts.get("need_review")).isEqualTo(0);
        assertThat(batch.get("confirmed_at")).isNull();
        assertThat((List<?>) batch.get("generated_fulfillment_export_ids")).isEmpty();

        ResponseEntity<Map> confirmed = confirm(batchId, "csx-batch-confirm-001");
        ResponseEntity<Map> replayed = confirm(batchId, "csx-batch-confirm-001");
        assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replayed.getBody()).isEqualTo(confirmed.getBody());
        assertThat(confirmed.getBody().get("confirmed_at")).isNotNull();
        assertThat((List<?>) confirmed.getBody().get("generated_fulfillment_export_ids")).hasSize(1);

        long shipmentId = jdbc.queryForObject(
                """
                SELECT s.id
                FROM app.raw_import_rows rir
                JOIN app.shipments s ON s.order_id=rir.order_id
                WHERE rir.import_batch_id=?
                """,
                Long.class,
                Long.parseLong(batchId));
        ResponseEntity<Map> addressConfirmed = http.exchange(
                "/api/v1/shipments/" + shipmentId + "/jd-receiver-address",
                HttpMethod.PUT,
                new HttpEntity<>(Map.of(
                        "expected_version", 0,
                        "province", "上海市",
                        "city", "上海市",
                        "county", "浦东新区",
                        "detail_address", "测试路1号"),
                        writeHeaders("csx-jd-address-001", "req-csx-jd-address-001")),
                Map.class);
        assertThat(addressConfirmed.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> submitted = http.exchange(
                "/api/v1/shipments/" + shipmentId + "/jd-so-order",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(), writeHeaders("csx-jd-submit-001", "req-csx-jd-submit-001")),
                Map.class);
        assertThat(submitted.getStatusCode())
                .as("submit body: %s", submitted.getBody())
                .isEqualTo(HttpStatus.CREATED);

        String cargoJson = jdbc.queryForObject(
                "SELECT submitted_cargo_snapshot::text FROM app.shipment_jd_outbounds WHERE shipment_id=?",
                String.class,
                shipmentId);
        List<Map<String, Object>> cargos = objectMapper.readValue(cargoJson, new TypeReference<>() {});
        List<Map<String, Object>> delivered = cargos.stream().map(cargo -> {
            Map<String, Object> row = new LinkedHashMap<>(cargo);
            row.put("realQuantity", cargo.get("planQuantity"));
            return Map.copyOf(row);
        }).toList();
        Map<String, Object> remote = new LinkedHashMap<>();
        remote.put("erpDeliveryNo", submitted.getBody().get("erp_delivery_no"));
        remote.put("deliveryNo", submitted.getBody().get("jd_delivery_no"));
        remote.put("warehouseNo", "WH-CSX-001");
        remote.put("status", "10020");
        remote.put("isSplit", "0");
        remote.put("splitDeliveryNos", "");
        remote.put("carrierInfo", Map.of(
                "carrierNo", "JD", "carrierName", "京东物流", "waybillNo", "JD-CSX-E2E-001"));
        remote.put("deliveryItemList", delivered);
        jd.queryResult(new JdResult(true, "1000", "成功", "jd-query-csx-e2e-001", remote));

        ResponseEntity<Map> backfilled = http.exchange(
                "/api/v1/shipments/" + shipmentId + "/jd-tracking-backfill",
                HttpMethod.POST,
                new HttpEntity<>(null, writeHeaders("csx-jd-backfill-001", "req-csx-jd-backfill-001")),
                Map.class);
        assertThat(backfilled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(backfilled.getBody())
                .containsEntry("poll_status", "TRACKED")
                .containsEntry("tracking_number", "JD-CSX-E2E-001");
        assertThat((List<?>) backfilled.getBody().get("generated_source_return_export_ids")).hasSize(1);

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
            assertThat(formatter.formatCellValue(row.getCell(columns.get("物流单号")))).isEqualTo("JD-CSX-E2E-001");
            assertThat(formatter.formatCellValue(row.getCell(columns.get("发货数量")))).isEqualTo("1");
        }

        ResponseEntity<Map> replayBackfill = http.exchange(
                "/api/v1/shipments/" + shipmentId + "/jd-tracking-backfill",
                HttpMethod.POST,
                new HttpEntity<>(null, writeHeaders("csx-jd-backfill-001", "req-csx-jd-backfill-replay")),
                Map.class);
        assertThat(replayBackfill.getBody()).isEqualTo(backfilled.getBody());
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.source_return_exports WHERE import_batch_id=? AND is_final",
                Long.class,
                Long.parseLong(batchId))).isEqualTo(1L);
    }

    @Test
    void sourceImportPersistsCanonicalFactsAndConfirmationReplayDoesNotDuplicateExports() throws Exception {
        ResponseEntity<Map> uploaded = uploadSource(
                caishixianWorkbook("CSX-IMPORT-PERSIST-001", "CSX-IMPORT-PERSIST-LINE-001"),
                "csx-source-upload-persist-001");
        assertThat(uploaded.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String batchId = uploaded.getBody().get("id").toString();
        Map<?, ?> counts = (Map<?, ?>) uploaded.getBody().get("row_counts");
        assertThat(counts.get("accepted")).isEqualTo(0);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.orders WHERE source_import_batch_id=?",
                Integer.class,
                Long.parseLong(batchId))).isZero();

        ResponseEntity<Map> confirmed = confirm(batchId, "csx-batch-confirm-persist-001");
        ResponseEntity<Map> replayed = confirm(batchId, "csx-batch-confirm-persist-001");
        assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replayed.getBody()).isEqualTo(confirmed.getBody());
        assertThat(confirmed.getBody().get("confirmed_at")).isNotNull();
        assertThat((List<?>) confirmed.getBody().get("generated_fulfillment_export_ids")).hasSize(1);

        Map<String, Object> acceptedRows = http.exchange(
                "/api/v1/import-batches/" + batchId + "/rows?page=0&size=20&status=ACCEPTED",
                HttpMethod.GET,
                new HttpEntity<>(operatorHeaders()),
                Map.class).getBody();
        Map<?, ?> accepted = (Map<?, ?>) ((List<?>) acceptedRows.get("items")).getFirst();
        assertThat(accepted.get("order_id")).isNotNull();
        assertThat(accepted.get("order_line_id")).isNotNull();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.orders WHERE source_import_batch_id=?",
                Integer.class,
                Long.parseLong(batchId))).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.order_lines ol JOIN app.orders o ON o.id=ol.order_id WHERE o.source_import_batch_id=?",
                Integer.class,
                Long.parseLong(batchId))).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.fulfillments f JOIN app.order_lines ol ON ol.id=f.order_line_id JOIN app.orders o ON o.id=ol.order_id WHERE o.source_import_batch_id=?",
                Integer.class,
                Long.parseLong(batchId))).isEqualTo(1);

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.fulfillment_exports fe JOIN app.fulfillment_export_items fei ON fei.fulfillment_export_id=fe.id JOIN app.raw_import_rows rir ON rir.id=fei.raw_import_row_id WHERE rir.import_batch_id=?",
                Integer.class,
                Long.parseLong(batchId))).isEqualTo(1);
    }

    @Test
    void importRowsExposeTheExactJdSdkCargoQuantitiesAfterAtomicConfirmation() throws Exception {
        jdbc.update(
                """
                UPDATE app.source_channel_skus
                SET quantity_multiplier=2.000
                WHERE source_channel='CAISHIXIAN' AND source_sku_ref='2047705'
                """);
        jdbc.update(
                """
                UPDATE app.provider_skus
                SET external_codes=jsonb_set(external_codes, '{jd_pieces_per_unit}', '3'::jsonb, true)
                WHERE fulfillment_provider_id=(SELECT id FROM app.fulfillment_providers WHERE provider_code='JD')
                  AND sku_id=(SELECT sku_id FROM app.source_channel_skus
                              WHERE source_channel='CAISHIXIAN' AND source_sku_ref='2047705')
                """);

        ResponseEntity<Map> uploaded = uploadSource(
                caishixianWorkbook("CSX-JD-CARGO-001", "CSX-JD-CARGO-LINE-001"),
                "csx-source-upload-jd-cargo-001");
        assertThat(uploaded.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String batchId = uploaded.getBody().get("id").toString();

        // 本用例走京东 SDK 建单闭环：确认前把该批次路由切到 SDK（其余用例保持 FILE 路径）。
        // 自动提交会因收货地址尚未人工确认而失败留痕，随后由本用例补齐地址并安全重试。
        jdbc.update(
                """
                UPDATE app.fulfillment_providers
                SET config=config || '{"outboundMode":"SDK"}'::jsonb
                WHERE provider_code='JD'
                """);
        ResponseEntity<Map> confirmed = confirm(batchId, "csx-batch-confirm-jd-cargo-001");
        assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> rows = http.exchange(
                "/api/v1/import-batches/" + batchId + "/rows?page=0&size=20&status=ACCEPTED",
                HttpMethod.GET,
                new HttpEntity<>(operatorHeaders()),
                Map.class).getBody();
        Map<String, Object> row = (Map<String, Object>) ((List<?>) rows.get("items")).getFirst();
        List<Map<String, Object>> rowCargos = (List<Map<String, Object>>) row.get("jd_cargos");
        assertThat(rowCargos).singleElement().satisfies(cargo -> assertThat(cargo)
                .containsEntry("provider_sku_code", "JD-SKU-000001")
                .containsEntry("plan_quantity", 6));

        long shipmentId = Long.parseLong(((List<?>) ((Map<?, ?>) confirmed.getBody()
                .get("outbound_routing")).get("jd_sdk_shipment_ids")).getFirst().toString());
        ResponseEntity<Map> addressConfirmed = http.exchange(
                "/api/v1/shipments/" + shipmentId + "/jd-receiver-address",
                HttpMethod.PUT,
                new HttpEntity<>(Map.of(
                        "expected_version", 0,
                        "province", "上海市",
                        "city", "上海市",
                        "county", "浦东新区",
                        "detail_address", "测试路1号"),
                        writeHeaders("csx-jd-cargo-address-001", "req-csx-jd-cargo-address-001")),
                Map.class);
        assertThat(addressConfirmed.getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> submitted = http.exchange(
                "/api/v1/shipments/" + shipmentId + "/jd-so-order",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(), writeHeaders(
                        "csx-jd-cargo-submit-001", "req-csx-jd-cargo-submit-001")),
                Map.class);
        assertThat(submitted.getStatusCode())
                .as("submit body: %s", submitted.getBody())
                .isEqualTo(HttpStatus.CREATED);

        String cargoJson = jdbc.queryForObject(
                "SELECT submitted_cargo_snapshot::text FROM app.shipment_jd_outbounds WHERE shipment_id=?",
                String.class,
                shipmentId);
        List<Map<String, Object>> submittedCargos = objectMapper.readValue(cargoJson, new TypeReference<>() {});
        assertThat(submittedCargos).singleElement().satisfies(cargo -> assertThat(cargo)
                .containsEntry("goodsNo", rowCargos.getFirst().get("provider_sku_code"))
                .containsEntry("planQuantity", rowCargos.getFirst().get("plan_quantity")));

        // 提交后行投影优先冻结实际提交值：映射再变（jd_pieces_per_unit 3→9）也不漂移
        jdbc.update(
                """
                UPDATE app.provider_skus
                SET external_codes=jsonb_set(external_codes, '{jd_pieces_per_unit}', '9'::jsonb, true)
                WHERE fulfillment_provider_id=(SELECT id FROM app.fulfillment_providers WHERE provider_code='JD')
                  AND sku_id=(SELECT sku_id FROM app.source_channel_skus
                              WHERE source_channel='CAISHIXIAN' AND source_sku_ref='2047705')
                """);
        Map<String, Object> rowsAfterSubmit = http.exchange(
                "/api/v1/import-batches/" + batchId + "/rows?page=0&size=20&status=ACCEPTED",
                HttpMethod.GET,
                new HttpEntity<>(operatorHeaders()),
                Map.class).getBody();
        Map<String, Object> rowAfterSubmit =
                (Map<String, Object>) ((List<?>) rowsAfterSubmit.get("items")).getFirst();
        List<Map<String, Object>> frozenCargos = (List<Map<String, Object>>) rowAfterSubmit.get("jd_cargos");
        assertThat(frozenCargos).singleElement().satisfies(cargo -> assertThat(cargo)
                .containsEntry("provider_sku_code", "JD-SKU-000001")
                .containsEntry("plan_quantity", 6));
    }

    @Test
    void confirmationRejectsLegacyMaterializedRowsWhoseOrdersStillHaveOpenReviewCases() throws Exception {
        ResponseEntity<Map> uploaded = uploadSource(
                caishixianWorkbook("CSX-IMPORT-BLOCKED-001", "CSX-IMPORT-BLOCKED-LINE-001"),
                "csx-source-upload-blocked-001");
        assertThat(uploaded.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String batchId = uploaded.getBody().get("id").toString();
        assertThat(((Map<?, ?>) uploaded.getBody().get("row_counts")).get("accepted")).isEqualTo(0);

        // 模拟 Ticket 04 之前已经成单、但尚未确认的历史批次；新批次不会在确认前走到这里。
        assertThat(candidateMaterializer.materializeStaged(
                Long.parseLong(batchId),
                new CommandContext("legacy-review-materialize", "legacy-review-materialize", "caishixian-e2e")))
                .isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.raw_import_rows WHERE import_batch_id=? AND status='ACCEPTED'",
                Integer.class,
                Long.parseLong(batchId))).isEqualTo(1);
        long orderId = jdbc.queryForObject(
                "SELECT order_id FROM app.raw_import_rows WHERE import_batch_id=?",
                Long.class,
                Long.parseLong(batchId));
        jdbc.update(
                """
                INSERT INTO app.review_cases
                    (case_no, case_type, responsible_team, reason_code, order_id, detail)
                VALUES (?, 'CUSTOMER_MATCH', 'CUSTOMER_OPS', 'CUSTOMER_MATCH_REQUIRED', ?, '{}'::jsonb)
                """,
                "RC-CSX-IMPORT-BLOCKED-001",
                orderId);

        ResponseEntity<Map> blocked = confirm(batchId, "csx-batch-confirm-blocked-001");

        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(blocked.getBody()).containsEntry("business_code", "IMPORT_BATCH_EXPORT_INCOMPLETE");
        assertThat(jdbc.queryForObject(
                "SELECT confirmed_at IS NULL FROM app.import_batches WHERE id=?",
                Boolean.class,
                Long.parseLong(batchId))).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.fulfillment_export_items fei JOIN app.raw_import_rows rir ON rir.id=fei.raw_import_row_id WHERE rir.import_batch_id=?",
                Integer.class,
                Long.parseLong(batchId))).isZero();
    }

    @Test
    void providerFailureRollsBackOrdersAndAllowsImmediateRetryAfterRepair() throws Exception {
        ResponseEntity<Map> uploaded = uploadSource(
                caishixianWorkbook("CSX-ATOMIC-RETRY-001", "CSX-ATOMIC-RETRY-LINE-001"),
                "csx-source-upload-atomic-retry-001");
        assertThat(uploaded.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String batchId = uploaded.getBody().get("id").toString();
        jdbc.update(
                "UPDATE app.fulfillment_providers SET config=config-'warehouseNo' WHERE provider_code='JD'");

        ResponseEntity<Map> blocked = confirm(batchId, "csx-confirm-atomic-retry-blocked-001");
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(blocked.getBody()).containsEntry("business_code", "JD_EXPORT_PROVIDER_CONFIG_MISSING");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.orders WHERE source_import_batch_id=?",
                Integer.class,
                Long.parseLong(batchId))).isZero();

        jdbc.update(
                """
                UPDATE app.fulfillment_providers
                SET config=jsonb_set(config, '{warehouseNo}', '"WH-CSX-001"'::jsonb, true)
                WHERE provider_code='JD'
                """);
        ResponseEntity<Map> recovered = confirm(batchId, "csx-confirm-atomic-retry-recovered-001");

        assertThat(recovered.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.orders WHERE source_import_batch_id=?",
                Integer.class,
                Long.parseLong(batchId))).isEqualTo(1);
    }

    private ResponseEntity<Map> uploadSource(byte[] bytes) {
        return uploadSource(bytes, "csx-source-upload-001");
    }

    private ResponseEntity<Map> uploadSource(byte[] bytes, String idempotencyKey) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(bytes) {
            @Override public String getFilename() { return "彩食鲜待发货订单.xlsx"; }
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

    private ResponseEntity<Map> confirm(String batchId, String key) {
        HttpHeaders headers = operatorHeaders();
        headers.set("Idempotency-Key", key);
        return http.exchange(
                "/api/v1/import-batches/" + batchId + "/confirm",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(), headers),
                Map.class);
    }

    private HttpHeaders operatorHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Operator", "caishixian-e2e");
        return headers;
    }

    private HttpHeaders writeHeaders(String key, String requestId) {
        HttpHeaders headers = operatorHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", key);
        headers.set("X-Request-Id", requestId);
        headers.setBasicAuth("caishixian-e2e", "caishixian-e2e-password");
        return headers;
    }

    private byte[] caishixianWorkbook() throws Exception {
        return caishixianWorkbook("CSX-E2E-ORDER-001", "CSX-E2E-LINE-001");
    }

    private byte[] caishixianWorkbook(String orderRef, String lineRef) throws Exception {
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
}
