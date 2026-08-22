package cn.zimu.fulfillment.file;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
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

@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.file-store.root=${java.io.tmpdir}/zimu-file-closed-loop-test")
class ExcelClosedLoopApiTest {

    private static final Path REPOSITORY_ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final List<String> JD_GOLDEN_HEADERS = List.of(
            "*isv出库单号", "*ISV来源编号", "*事业部编号", "*店铺编号", "青龙业主号", "*仓库编号", "*承运商编号",
            "*授权码pin", "销售平台订单号", "*销售平台来源", "销售平台下单时间", "订单类型", "*订单标记位", "*收货人姓名",
            "*收货人手机", "收货人电话", "收货人电话邮箱", "收货人省", "收货人市", "收货人县", "收货人镇", "*收货人地址", "收货人邮编",
            "商家门店编号", "是否地址解析", "期望发货时间", "订单应收金额", "客户留言", "商家留言", "模板备注", "三方运单号", "大头笔", "顺丰E标",
            "业务类型", "目的地代码", "目的地名称", "发件网点代码", "发件网点名称", "寄件方式", "收件方式", "预约配送时间", "运费支付方式", "月结账号", "是否保价",
            "保价声明价值", "寄托物", "预约号", "入仓时间", "进仓备注", "签单返还收件人名称", "签单返还收件人电话", "签单返还收件人手机", "签单返还收件人地址",
            "验货方式", "*京东商品编号", "*商家商品编号", "安维标识", "*商品金额", "*商品的出库数量", "商品行号", "包装细数", "包装批号", "采购单号", "生产日期", "到期日期",
            "生产批号", "商品等级", "计量单位", "是否卸车(仓配冷链整车冷链城配)", "有无动物检疫证", "车型", "派送服务", "仓配产品", "送仓类型", "是否送货入仓", "商家三方", "商家意愿");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void addExplicitFeixiangMappings() {
        jdbc.update(
                """
                INSERT INTO app.customer_source_refs(customer_id, source_channel, source_customer_ref)
                SELECT customer_id, 'FEIXIANG', 'FX-MEMBER-001'
                FROM app.customer_source_refs WHERE source_channel='WECOM'
                ON CONFLICT (source_channel, source_customer_ref) DO NOTHING
                """);
        jdbc.update(
                """
                INSERT INTO app.source_channel_skus
                    (source_channel, source_sku_ref, source_product_name, source_specification,
                     quantity_multiplier, sku_id, active)
                SELECT 'FEIXIANG', 'FX-PRODUCT-001', '子牧羊小腿', '标准箱', 2.000, sku_id, true
                FROM app.source_channel_skus WHERE source_channel='WECOM' AND source_sku_ref='WECOM-SKU-TP-001'
                ON CONFLICT (source_channel, source_sku_ref) DO NOTHING
                """);
    }

    private void addExplicitJdFeixiangMapping() {
        jdbc.update(
                """
                INSERT INTO app.source_channel_skus
                    (source_channel, source_sku_ref, source_product_name, source_specification,
                     quantity_multiplier, sku_id, active)
                SELECT 'FEIXIANG', 'FX-PRODUCT-JD-001', '子牧羊小腿', '500g/盒', 1.000, sku_id, true
                FROM app.source_channel_skus WHERE source_channel='WECOM' AND source_sku_ref='WECOM-SKU-JD-001'
                ON CONFLICT (source_channel, source_sku_ref) DO NOTHING
                """);
    }

    @Test
    void allThreeSourceFingerprintsUseMagicAndExactHeadersInsteadOfFileNames() throws Exception {
        Map<String, Object> caishixian = upload(
                "anything.csv", xlsx("ignored-name", List.of(
                        "主订单编号", "子订单编号", "供应商编码", "站点编码", "商品编号", "下单数量")),
                "fingerprint-csx-001");
        Map<String, Object> jufubao = upload(
                "not-an-xlsx.bin", xlsx("sheet1", List.of(
                        "主单号", "拆单号", "供货商", "渠道订单号", "结算方式", "需结算总额")),
                "fingerprint-jfb-001");
        Map<String, Object> feixiang = upload(
                "source.xlsx", feixiangCsv(false), "fingerprint-fx-001");

        assertThat(caishixian).containsEntry("source_channel", "CAISHIXIAN");
        assertThat(jufubao).containsEntry("source_channel", "JUFUBAO");
        assertThat(feixiang).containsEntry("source_channel", "FEIXIANG");
        assertThat(caishixian.get("template_fingerprint").toString()).contains("CAISHIXIAN");
        assertThat(jufubao.get("template_fingerprint").toString()).contains("JUFUBAO");
        assertThat(feixiang.get("template_fingerprint").toString()).contains("FEIXIANG");
    }

    @Test
    void zhonghuiSourceReturnMarksTheOriginalShippingStatusAsShipped() throws Exception {
        jdbc.update(
                """
                INSERT INTO app.source_channel_skus
                    (source_channel, source_sku_ref, source_product_name, source_specification,
                     quantity_multiplier, sku_id, active)
                SELECT 'ZHONGHUI', 'ZH-TP-STATUS-001', '中汇回填状态测试商品', '500g',
                       1.000, sku_id, true
                FROM app.source_channel_skus
                WHERE source_channel='WECOM' AND source_sku_ref='WECOM-SKU-TP-001'
                ON CONFLICT (source_channel, source_sku_ref) DO UPDATE
                SET sku_id=EXCLUDED.sku_id, quantity_multiplier=EXCLUDED.quantity_multiplier,
                    source_product_name=EXCLUDED.source_product_name,
                    source_specification=EXCLUDED.source_specification, active=true
                """);
        ResponseEntity<Map> uploaded = uploadRaw(
                "中汇订单.xlsx", zhonghuiStatusWorkbook(), "source-import-zhonghui-status-001");
        assertThat(uploaded.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String batchId = uploaded.getBody().get("id").toString();
        ResponseEntity<Map> confirmed = confirmBatch(batchId, "confirm-zhonghui-status-001");
        assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.OK);
        String exportId = ((List<?>) confirmed.getBody().get("generated_fulfillment_export_ids"))
                .getFirst().toString();
        String waybillNo = "JDVA-ZHONGHUI-STATUS-001";
        ResponseEntity<Map> tracked = uploadTracking(
                exportId,
                fillThirdPartyTracking(downloadExport(exportId), "SHIPPED", "1.000", waybillNo),
                "tracking-import-zhonghui-status-001");
        assertThat(tracked.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String returnId = ((List<?>) tracked.getBody().get("generated_source_return_export_ids"))
                .getFirst().toString();
        assertThat(jdbc.queryForObject(
                        """
                        SELECT DISTINCT ol.processing_stage
                        FROM app.raw_import_rows rir
                        JOIN app.order_lines ol ON ol.id=rir.order_line_id
                        WHERE rir.import_batch_id=? AND rir.order_line_id IS NOT NULL
                        """,
                        String.class,
                        Long.parseLong(batchId)))
                .isEqualTo("RETURN_FILE_READY");

        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(downloadSourceReturn(returnId)))) {
            var header = workbook.getSheetAt(0).getRow(0);
            var row = workbook.getSheetAt(0).getRow(1);
            DataFormatter formatter = new DataFormatter();
            Map<String, Integer> columns = new LinkedHashMap<>();
            for (int index = 0; index < header.getLastCellNum(); index++) {
                columns.put(formatter.formatCellValue(header.getCell(index)), index);
            }
            assertThat(formatter.formatCellValue(row.getCell(columns.get("发货状态"))))
                    .isEqualTo("已发货");
            assertThat(formatter.formatCellValue(row.getCell(columns.get("物流单号"))))
                    .isEqualTo(waybillNo);
        }
    }

    @Test
    void feixiangCsvRetainsRowsCreatesCanonicalOrderAndReplaysByContentHash() {
        byte[] file = feixiangCsv(true);
        Map<String, Object> first = upload("batch.csv", file, "source-import-fx-001");
        Map<String, Object> replay = upload("renamed.csv", file, "source-import-fx-002");

        assertThat(replay.get("id")).isEqualTo(first.get("id"));
        assertThat(first.get("content_sha256").toString()).hasSize(64);
        Map<?, ?> counts = (Map<?, ?>) first.get("row_counts");
        assertThat(counts.get("total")).isEqualTo(2);
        assertThat(counts.get("accepted")).isEqualTo(1);
        assertThat(counts.get("need_review")).isEqualTo(1);

        Map<String, Object> rows = get("/api/v1/import-batches/" + first.get("id") + "/rows?page=0&size=20");
        assertThat(rows.get("total_elements")).isEqualTo(2);
        List<?> rawRows = (List<?>) rows.get("items");
        assertThat(rawRows.stream()
                        .map(item -> ((Number) ((Map<?, ?>) item).get("row_index")).intValue())
                        .toList())
                .containsExactly(2, 3);
        assertThat(rawRows).allSatisfy(item -> {
            Map<?, ?> rawRow = (Map<?, ?>) item;
            assertThat(rawRow.get("sheet_name")).isNotNull();
            assertThat(rawRow.get("sheet_index")).isNotNull();
            assertThat(rawRow.get("raw_cells")).isNotNull();
            assertThat(rawRow.get("source_order_ref")).isNotNull();
        });
        // 确认明细解析投影：白名单字段按渠道模板从原始单元格提取，供核对解析是否正确
        assertThat(rawRows).allSatisfy(item -> {
            Map<?, ?> rawRow = (Map<?, ?>) item;
            Map<?, ?> parsed = (Map<?, ?>) rawRow.get("parsed");
            java.util.Set<String> parsedKeys = parsed.keySet().stream()
                    .map(String::valueOf)
                    .collect(java.util.stream.Collectors.toSet());
            assertThat(parsedKeys).contains("receiver_name", "receiver_phone", "receiver_address", "product_name");
            assertThat(parsedKeys).isSubsetOf(java.util.Set.of(
                    "receiver_name", "receiver_phone", "receiver_address",
                    "product_name", "quantity", "specification", "source_sku_ref"));
            // SKU 履约方归属：无映射为 null，有映射必须是白名单结构
            Object skuFulfillment = rawRow.get("sku_fulfillment");
            if (skuFulfillment != null) {
                Map<?, ?> fulfillment = (Map<?, ?>) skuFulfillment;
                assertThat(String.valueOf(fulfillment.get("provider_type")))
                        .isIn("JD_WAREHOUSE", "THIRD_PARTY");
                assertThat(fulfillment.keySet().stream().map(String::valueOf)
                        .collect(java.util.stream.Collectors.toSet()))
                        .containsExactlyInAnyOrder("provider_type", "provider_name", "sku_specification");
            }
        });

        Map<String, Object> orders = get("/api/v1/orders?query=FX-ORDER-001&page=0&size=20");
        assertThat(orders.get("total_elements")).isEqualTo(1);
        Map<?, ?> orderSummary = (Map<?, ?>) ((List<?>) orders.get("items")).getFirst();
        Map<String, Object> order = get("/api/v1/orders/" + orderSummary.get("id"));
        assertThat(order.get("source_channel")).isEqualTo("FEIXIANG");
        assertThat(((List<?>) order.get("lines")).stream()
                        .map(line -> ((Map<?, ?>) line).get("requested_quantity").toString())
                        .toList())
                .contains("3.000");
        assertThat((List<?>) order.get("review_cases")).hasSize(1);
    }

    @Test
    void uploadAutoCreatesCustomerByNameAndPhoneAndOneBatchConfirmationGeneratesExportsOnce() {
        byte[] firstFile = new String(
                        feixiangSingleCsv("FX-BATCH-CONFIRM-001"), StandardCharsets.UTF_8)
                .replace("FX-MEMBER-001", "LEGACY-MEMBER-A")
                .replace("张三", "批次客户")
                .replace("13800000000", "13911112222")
                .getBytes(StandardCharsets.UTF_8);
        ResponseEntity<Map> uploadResponse = uploadRaw(
                "batch-confirm.csv", firstFile, "source-import-batch-confirm-001");
        assertThat(uploadResponse.getStatusCode())
                .as(String.valueOf(uploadResponse.getBody()))
                .isEqualTo(HttpStatus.CREATED);
        Map<String, Object> imported = uploadResponse.getBody();

        assertThat((List<?>) imported.get("generated_fulfillment_export_ids")).isEmpty();
        assertThat(imported.get("confirmed_at")).isNull();
        String orderId = ((Map<?, ?>) ((List<?>) get(
                "/api/v1/orders?query=FX-BATCH-CONFIRM-001&page=0&size=20").get("items")).getFirst())
                .get("id").toString();
        Map<String, Object> order = get("/api/v1/orders/" + orderId);
        assertThat(order.get("customer_id")).isNotNull();
        assertThat((List<?>) order.get("review_cases")).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.customers WHERE customer_name='批次客户' AND profile->>'identity_phone'='13911112222'",
                Integer.class)).isEqualTo(1);

        ResponseEntity<Map> confirmed = confirmBatch(imported.get("id").toString(), "confirm-batch-001");
        ResponseEntity<Map> replay = confirmBatch(imported.get("id").toString(), "confirm-batch-001");
        assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getBody()).isEqualTo(confirmed.getBody());
        assertThat(confirmed.getBody().get("confirmed_at")).isNotNull();
        assertThat((List<?>) confirmed.getBody().get("generated_fulfillment_export_ids")).hasSize(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.fulfillment_exports fe JOIN app.fulfillment_export_items fei ON fei.fulfillment_export_id=fe.id JOIN app.raw_import_rows rir ON rir.id=fei.raw_import_row_id WHERE rir.import_batch_id=?",
                Integer.class,
                Long.valueOf(imported.get("id").toString()))).isEqualTo(1);

        byte[] secondFile = new String(
                        feixiangSingleCsv("FX-BATCH-CONFIRM-002"), StandardCharsets.UTF_8)
                .replace("FX-MEMBER-001", "LEGACY-MEMBER-B")
                .replace("张三", "批次客户")
                .replace("13800000000", "13911112222")
                .getBytes(StandardCharsets.UTF_8);
        Map<String, Object> second = uploadRaw(
                "batch-confirm-2.csv", secondFile, "source-import-batch-confirm-002").getBody();
        String secondOrderId = ((Map<?, ?>) ((List<?>) get(
                "/api/v1/orders?query=FX-BATCH-CONFIRM-002&page=0&size=20").get("items")).getFirst())
                .get("id").toString();
        assertThat(get("/api/v1/orders/" + secondOrderId).get("customer_id")).isEqualTo(order.get("customer_id"));
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.customers WHERE customer_name='批次客户' AND profile->>'identity_phone'='13911112222'",
                Integer.class)).isEqualTo(1);
        assertThat(second.get("confirmed_at")).isNull();
    }

    @Test
    void resolvingAllImportMappingsResumesTheSameBatchAndGeneratesItsProviderExport() {
        byte[] source = new String(
                        feixiangSingleCsv("FX-REVIEW-RESUME-001"), StandardCharsets.UTF_8)
                .replace("FX-MEMBER-001", "FX-MEMBER-REVIEW-001")
                .replace("FX-PRODUCT-001", "FX-PRODUCT-REVIEW-001")
                .getBytes(StandardCharsets.UTF_8);
        Map<String, Object> imported = upload(
                "review-resume.csv", source, "source-import-review-resume-001");
        assertThat((List<?>) imported.get("generated_fulfillment_export_ids")).isEmpty();
        assertThat(((Map<?, ?>) imported.get("row_counts")).get("need_review")).isEqualTo(1);

        Map<String, Object> orders = get("/api/v1/orders?query=FX-REVIEW-RESUME-001&page=0&size=20");
        String orderId = ((Map<?, ?>) ((List<?>) orders.get("items")).getFirst()).get("id").toString();
        Map<String, Object> order = get("/api/v1/orders/" + orderId);
        Map<String, Map<String, Object>> cases = ((List<Map<String, Object>>) order.get("review_cases")).stream()
                .collect(java.util.stream.Collectors.toMap(
                        item -> item.get("reason_code").toString(), item -> item));
        String skuId = jdbc.queryForObject(
                "SELECT sku_id::text FROM app.source_channel_skus WHERE source_channel='WECOM' AND source_sku_ref='WECOM-SKU-TP-001'",
                String.class);

        Map<String, Object> skuCase = cases.get("SKU_MAPPING_REQUIRED");
        @SuppressWarnings("unchecked")
        Map<String, Object> skuDetail = (Map<String, Object>) skuCase.get("detail");
        // 复核抽屉逐条展示来源商品信息：名称/数量来自行快照，sheet/行号来自 raw_import_rows。
        assertThat(skuDetail).containsEntry("source_channel", "FEIXIANG");
        assertThat(skuDetail.get("source_product_name")).isEqualTo("子牧羊小腿");
        assertThat(skuDetail.get("source_quantity")).isEqualTo("1.500");
        assertThat(skuDetail.get("source_sheet_name")).isEqualTo("CSV");
        assertThat(skuDetail.get("source_row_index")).isEqualTo(2);
        assertThat((List<?>) skuDetail.get("evidence_items")).singleElement().satisfies(item -> {
            Map<?, ?> evidence = (Map<?, ?>) item;
            assertThat(evidence.get("source_sku_ref")).isEqualTo("FX-PRODUCT-REVIEW-001");
            assertThat(evidence.get("product_name")).isEqualTo("子牧羊小腿");
            assertThat(evidence.get("quantity")).isEqualTo("1.500");
        });
        // 复核事项直连原始文件行外键，原始单元格值可达。
        assertThat(jdbc.queryForObject(
                "SELECT raw_import_row_id FROM app.review_cases WHERE id=?",
                Object.class, Long.parseLong(skuCase.get("id").toString()))).isNotNull();
        ResponseEntity<Map> skuResolved = resolveReview(
                skuCase.get("id").toString(),
                "resolve-sku",
                Map.of(
                        "expected_version", skuCase.get("version"),
                        "sku_id", skuId,
                        "source_channel", "FEIXIANG",
                        "source_sku_ref", "FX-PRODUCT-REVIEW-001",
                        "quantity_multiplier", "2.000",
                        "remark", "核对包装倍率后确认 SKU"),
                "resolve-import-sku-001");
        ResponseEntity<Map> skuResolvedReplay = resolveReview(
                skuCase.get("id").toString(),
                "resolve-sku",
                Map.of(
                        "expected_version", skuCase.get("version"),
                        "sku_id", skuId,
                        "source_channel", "FEIXIANG",
                        "source_sku_ref", "FX-PRODUCT-REVIEW-001",
                        "quantity_multiplier", "2.000",
                        "remark", "核对包装倍率后确认 SKU"),
                "resolve-import-sku-001");
        assertThat(skuResolved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(skuResolvedReplay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(skuResolvedReplay.getBody()).isEqualTo(skuResolved.getBody());
        Map<String, Object> ready = get("/api/v1/import-batches/" + imported.get("id"));
        assertThat((List<?>) ready.get("generated_fulfillment_export_ids")).isEmpty();
        assertThat(ready.get("confirmed_at")).isNull();

        Map<String, Object> resumed = confirmBatch(
                imported.get("id").toString(), "confirm-reviewed-import-001").getBody();
        assertThat((List<?>) resumed.get("generated_fulfillment_export_ids")).hasSize(1);
        assertThat(resumed.get("status")).isEqualTo("COMPLETED");
        assertThat((Map<String, Object>) resumed.get("row_counts"))
                .containsEntry("accepted", 1)
                .containsEntry("need_review", 0);
        Map<String, Object> resumedOrder = get("/api/v1/orders/" + orderId);
        assertThat(((List<?>) resumedOrder.get("lines")).getFirst()).satisfies(item ->
                assertThat(((Map<?, ?>) item).get("processing_stage")).isEqualTo("WAITING_PROVIDER"));
    }

    @Test
    void thirdPartyExportTrackingImportAndTrueCsvSourceReturnFormAClosedLoop() throws Exception {
        byte[] source = feixiangSingleCsv("FX-CLOSED-LOOP-001");
        Map<String, Object> imported = upload("closed-loop.csv", source, "source-import-closed-001");
        List<?> generated = (List<?>) imported.get("generated_fulfillment_export_ids");
        assertThat(generated).hasSize(1);
        String exportId = generated.getFirst().toString();

        Map<String, Object> export = get("/api/v1/fulfillment-exports/" + exportId);
        assertThat(export.get("export_kind")).isEqualTo("THIRD_PARTY");
        assertThat((List<?>) export.get("lines")).hasSize(1);
        String shipmentId = ((Map<?, ?>) ((List<?>) export.get("lines")).getFirst())
                .get("shipment_id").toString();
        HttpHeaders downloadHeaders = new HttpHeaders();
        downloadHeaders.set("X-Operator", "excel-test");
        ResponseEntity<byte[]> instructionResponse = http.exchange(
                "/api/v1/fulfillment-exports/" + exportId + "/file",
                HttpMethod.GET,
                new HttpEntity<>(downloadHeaders),
                byte[].class);
        assertThat(instructionResponse.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains("attachment", ".xlsx");
        byte[] instruction = instructionResponse.getBody();
        assertThat(instruction).startsWith((byte) 'P', (byte) 'K');
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(instruction))) {
            DataFormatter formatter = new DataFormatter();
            var sheet = workbook.getSheetAt(0);
            Map<String, Integer> columns = new LinkedHashMap<>();
            for (int index = 0; index < sheet.getRow(0).getLastCellNum(); index++) {
                columns.put(formatter.formatCellValue(sheet.getRow(0).getCell(index)), index);
            }
            assertThat(formatter.formatCellValue(sheet.getRow(1).getCell(columns.get("来源渠道"))))
                    .isEqualTo("飞象");
        }

        byte[] returned = fillThirdPartyTracking(instruction);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(returned) {
            @Override public String getFilename() { return "tracking.xlsx"; }
        });
        body.add("import_mode", "NEW");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.set("Idempotency-Key", "tracking-import-closed-001");
        headers.set("X-Operator", "excel-test");
        ResponseEntity<Map> tracking = http.exchange(
                "/api/v1/fulfillment-exports/" + exportId + "/tracking-imports",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class);

        assertThat(tracking.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat((Map<?, ?>) tracking.getBody().get("business_results"))
                .satisfies(results -> assertThat(results.get("shipped")).isEqualTo(1));
        assertThat(get("/api/v1/shipments/" + shipmentId).get("shipped_at")).isNotNull();
        List<?> returnExports = http.getForObject(
                "/api/v1/import-batches/" + imported.get("id") + "/source-return-exports", List.class);
        assertThat(returnExports).hasSize(1);
        String returnId = ((Map<?, ?>) returnExports.getFirst()).get("id").toString();
        ResponseEntity<byte[]> csvResponse = http.exchange(
                "/api/v1/source-return-exports/" + returnId + "/file",
                HttpMethod.GET,
                new HttpEntity<>(downloadHeaders),
                byte[].class);
        assertThat(csvResponse.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).contains(".csv");
        byte[] csv = csvResponse.getBody();
        assertThat(csv.length >= 2 && csv[0] == 'P' && csv[1] == 'K').isFalse();
        String text = new String(csv, StandardCharsets.UTF_8);
        assertThat(text).contains("已发货", "京东物流", "JDVAFX-CLOSED-LOOP-001");
    }

    @Test
    void thirdPartyTrackingCanConfirmShipmentWithoutAnActualShipmentTime() throws Exception {
        Map<String, Object> imported = upload(
                "unknown-shipped-at.csv",
                feixiangSingleCsv("FX-UNKNOWN-SHIPPED-AT-001"),
                "source-import-unknown-shipped-at-001");
        String exportId = ((List<?>) imported.get("generated_fulfillment_export_ids")).getFirst().toString();
        Map<?, ?> exportLine = (Map<?, ?>) ((List<?>) get(
                "/api/v1/fulfillment-exports/" + exportId).get("lines")).getFirst();
        String shipmentId = exportLine.get("shipment_id").toString();
        String channelAnalyticsPath =
                "/api/v1/analytics/channels?date_from=2020-01-01&date_to=2099-12-31&source_channel=FEIXIANG";
        String productAnalyticsPath =
                "/api/v1/analytics/products?date_from=2020-01-01&date_to=2099-12-31&source_channel=FEIXIANG";
        BigDecimal channelActualQuantityBefore = actualShippedQuantity(channelAnalyticsPath);
        BigDecimal productActualQuantityBefore = actualShippedQuantity(productAnalyticsPath);
        byte[] returned = fillThirdPartyTracking(
                downloadExport(exportId), "SHIPPED", "3.000", "SF-UNKNOWN-TIME-001", "");

        ResponseEntity<Map> accepted = uploadTracking(
                exportId,
                returned,
                "tracking-import-unknown-shipped-at-001",
                "req-unknown-shipped-at-001");

        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> shipment = get("/api/v1/shipments/" + shipmentId);
        assertThat(shipment)
                .containsEntry("shipment_status", "SHIPPED")
                .containsEntry("shipped_at", null);
        Map<?, ?> tracking = (Map<?, ?>) shipment.get("tracking");
        assertThat(tracking.get("tracking_number")).isEqualTo("SF-UNKNOWN-TIME-001");
        assertThat(tracking.get("received_at")).isNotNull();

        Map<String, Object> auditPage = get(
                "/api/v1/audit-logs?request_id=req-unknown-shipped-at-001&operation=tracking.accept");
        assertThat((List<?>) auditPage.get("items")).singleElement().satisfies(item ->
                assertThat(((Map<?, ?>) item).get("created_at")).isNotNull());

        assertThat(actualShippedQuantity(channelAnalyticsPath))
                .isEqualByComparingTo(channelActualQuantityBefore);
        assertThat(actualShippedQuantity(productAnalyticsPath))
                .isEqualByComparingTo(productActualQuantityBefore);
    }

    @Test
    void partialTrackingCreatesOneOpenMultiShipmentFollowupAndNoPrematureReturnFile() throws Exception {
        Map<String, Object> imported = upload(
                "multi-partial.csv",
                feixiangSingleCsv("FX-MULTI-PARTIAL-001"),
                "source-import-multi-partial-001");
        String exportId = ((List<?>) imported.get("generated_fulfillment_export_ids")).getFirst().toString();
        Map<String, Object> export = get("/api/v1/fulfillment-exports/" + exportId);
        Map<?, ?> exportLine = (Map<?, ?>) ((List<?>) export.get("lines")).getFirst();
        String fulfillmentId = exportLine.get("fulfillment_id").toString();
        byte[] instruction = downloadExport(exportId);
        byte[] partial = fillThirdPartyTracking(instruction, "PARTIAL", "1.000", "JDVA-FIRST-PARCEL");

        ResponseEntity<Map> accepted = uploadTracking(
                exportId, partial, "tracking-import-multi-partial-001");
        ResponseEntity<Map> replay = uploadTracking(
                exportId, partial, "tracking-import-multi-partial-replay-001");

        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getBody().get("id")).isEqualTo(accepted.getBody().get("id"));
        Map<String, Object> fulfillment = get("/api/v1/fulfillments/" + fulfillmentId);
        assertThat(fulfillment)
                .containsEntry("shipping_progress", "PARTIALLY_SHIPPED")
                .containsEntry("outcome", "IN_PROGRESS");

        Map<String, Object> orders = get("/api/v1/orders?query=FX-MULTI-PARTIAL-001&page=0&size=20");
        String orderId = ((Map<?, ?>) ((List<?>) orders.get("items")).getFirst()).get("id").toString();
        Map<String, Object> order = get("/api/v1/orders/" + orderId);
        assertThat(order.get("order_status")).isEqualTo("FULFILLING");
        assertThat(((List<?>) order.get("lines")).getFirst()).satisfies(item ->
                assertThat(((Map<?, ?>) item).get("processing_stage")).isEqualTo("WAITING_PROVIDER"));
        assertThat((List<?>) order.get("review_cases")).singleElement().satisfies(item -> {
            Map<?, ?> reviewCase = (Map<?, ?>) item;
            assertThat(reviewCase.get("reason_code")).isEqualTo("MULTI_SHIPMENT_SOURCE_FOLLOWUP");
            assertThat(reviewCase.get("status")).isEqualTo("OPEN");
            assertThat(reviewCase.get("order_line_id")).isNotNull();
        });

        List<?> returns = http.getForObject(
                "/api/v1/import-batches/" + imported.get("id") + "/source-return-exports", List.class);
        assertThat(returns).isEmpty();
    }

    @Test
    void cancellingRemainderAfterFirstParcelMakesMultiShipmentFollowupReady() throws Exception {
        Map<String, Object> imported = upload(
                "multi-cancel.csv",
                feixiangSingleCsv("FX-MULTI-CANCEL-001"),
                "source-import-multi-cancel-001");
        String exportId = ((List<?>) imported.get("generated_fulfillment_export_ids")).getFirst().toString();
        Map<?, ?> exportLine = (Map<?, ?>) ((List<?>) get(
                "/api/v1/fulfillment-exports/" + exportId).get("lines")).getFirst();
        String fulfillmentId = exportLine.get("fulfillment_id").toString();
        ResponseEntity<Map> firstTracking = uploadTracking(
                exportId,
                fillThirdPartyTracking(downloadExport(exportId), "PARTIAL", "1.000", "JDVA-CANCEL-FIRST"),
                "tracking-import-multi-cancel-001");
        assertThat(firstTracking.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        long procurementTicketId = createCancellationFixture(fulfillmentId, "PROC-MULTI-CANCEL-001", "2.000");
        ResponseEntity<Map> cancelled = cancelRemaining(procurementTicketId, 0, "客户确认不再续发剩余数量");
        assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.OK);

        Map<String, Object> fulfillment = get("/api/v1/fulfillments/" + fulfillmentId);
        assertThat(fulfillment)
                .containsEntry("outcome", "PARTIALLY_FULFILLED")
                .containsEntry("cancelled_quantity", "2.000");
        Map<String, Object> orders = get("/api/v1/orders?query=FX-MULTI-CANCEL-001&page=0&size=20");
        String orderId = ((Map<?, ?>) ((List<?>) orders.get("items")).getFirst()).get("id").toString();
        Map<String, Object> order = get("/api/v1/orders/" + orderId);
        assertThat(order.get("order_status")).isEqualTo("NEED_REVIEW");
        assertThat(((List<?>) order.get("lines")).getFirst()).satisfies(item ->
                assertThat(((Map<?, ?>) item).get("processing_stage")).isEqualTo("NEED_REVIEW"));
    }

    @Test
    void continuationExportRejectsStaleVersionOverAllocationAndDemoScope() throws Exception {
        Map<String, Object> imported = upload(
                "continuation-guards.csv",
                feixiangSingleCsv("FX-CONTINUATION-GUARDS-001"),
                "source-import-continuation-guards-001");
        String exportId = ((List<?>) imported.get("generated_fulfillment_export_ids")).getFirst().toString();
        Map<?, ?> exportLine = (Map<?, ?>) ((List<?>) get(
                "/api/v1/fulfillment-exports/" + exportId).get("lines")).getFirst();
        String fulfillmentId = exportLine.get("fulfillment_id").toString();
        ResponseEntity<Map> partial = uploadTracking(
                exportId,
                fillThirdPartyTracking(downloadExport(exportId), "PARTIAL", "1.000", "JDVA-GUARD-FIRST"),
                "tracking-import-continuation-guards-001");
        assertThat(partial.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> fulfillment = get("/api/v1/fulfillments/" + fulfillmentId);
        long version = ((Number) fulfillment.get("version")).longValue();

        ResponseEntity<Map> overAllocated = createContinuation(
                fulfillmentId, version, "2.001", "超量门禁", "continuation-over-allocation-001");
        ResponseEntity<Map> stale = createContinuation(
                fulfillmentId, version + 1, "2.000", "版本门禁", "continuation-stale-version-001");

        assertThat(overAllocated.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(overAllocated.getBody()).containsEntry("business_code", "CONTINUATION_QUANTITY_EXCEEDS_REMAINING");
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(stale.getBody()).containsEntry("business_code", "VERSION_CONFLICT");

        String demoFulfillmentId = createDemoFulfillmentFixture();
        ResponseEntity<Map> demo = createContinuation(
                demoFulfillmentId, 0, "1.000", "不得跨数据域", "continuation-demo-scope-001");
        assertThat(demo.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void twoShipmentPublicHttpFlowKeepsFirstReturnAndRequiresManualFollowup() throws Exception {
        Map<String, Object> imported = upload(
                "multi-complete.csv",
                feixiangSingleCsv("FX-MULTI-COMPLETE-001"),
                "source-import-multi-complete-001");
        String firstExportId = ((List<?>) imported.get("generated_fulfillment_export_ids")).getFirst().toString();
        Map<?, ?> firstExportLine = (Map<?, ?>) ((List<?>) get(
                "/api/v1/fulfillment-exports/" + firstExportId).get("lines")).getFirst();
        String fulfillmentId = firstExportLine.get("fulfillment_id").toString();
        ResponseEntity<Map> firstTracking = uploadTracking(
                firstExportId,
                fillThirdPartyTracking(downloadExport(firstExportId), "PARTIAL", "1.000", "JDVA-FIRST-ONLY"),
                "tracking-import-multi-complete-first-001");
        assertThat(firstTracking.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> partial = get("/api/v1/fulfillments/" + fulfillmentId);

        ResponseEntity<Map> continuation = createContinuation(
                fulfillmentId,
                ((Number) partial.get("version")).longValue(),
                "2.000",
                "首批少发，创建续发批次",
                "continuation-multi-complete-001");
        ResponseEntity<Map> continuationReplay = createContinuation(
                fulfillmentId,
                ((Number) partial.get("version")).longValue(),
                "2.000",
                "首批少发，创建续发批次",
                "continuation-multi-complete-001");
        assertThat(continuation.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(continuationReplay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> continuationBody = continuation.getBody();
        assertThat(continuationReplay.getBody().get("shipment_id"))
                .isEqualTo(continuationBody.get("shipment_id"));
        assertThat(continuationBody.get("shipment_sequence")).isEqualTo(2);
        String secondExportId = continuationBody.get("fulfillment_export_id").toString();
        byte[] secondTrackingFile = fillThirdPartyTracking(
                downloadExport(secondExportId), "SHIPPED", "2.000", "JDVA-SECOND-INTERNAL");
        ResponseEntity<Map> secondTracking = uploadTracking(
                secondExportId,
                secondTrackingFile,
                "tracking-import-multi-complete-second-001");
        ResponseEntity<Map> secondTrackingReplay = uploadTracking(
                secondExportId,
                secondTrackingFile,
                "tracking-import-multi-complete-second-replay-001");
        assertThat(secondTracking.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(secondTrackingReplay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(secondTrackingReplay.getBody()).isEqualTo(secondTracking.getBody());
        assertThat((Map<String, Object>) secondTracking.getBody().get("business_results"))
                .containsEntry("shipped", 1)
                .containsEntry("partial", 0);
        assertThat((List<?>) secondTracking.getBody().get("generated_source_return_export_ids")).isEmpty();

        Map<String, Object> terminal = get("/api/v1/fulfillments/" + fulfillmentId);
        assertThat(terminal)
                .containsEntry("shipping_progress", "SHIPPED")
                .containsEntry("outcome", "FULLY_FULFILLED");
        Map<String, Object> orders = get("/api/v1/orders?query=FX-MULTI-COMPLETE-001&page=0&size=20");
        String orderId = ((Map<?, ?>) ((List<?>) orders.get("items")).getFirst()).get("id").toString();
        Map<String, Object> beforeCompletion = get("/api/v1/orders/" + orderId);
        assertThat(beforeCompletion.get("order_status")).isEqualTo("NEED_REVIEW");
        Map<?, ?> openCase = (Map<?, ?>) ((List<?>) beforeCompletion.get("review_cases")).getFirst();
        assertThat(openCase.get("status")).isEqualTo("OPEN");
        assertThat(((List<?>) beforeCompletion.get("lines")).getFirst()).satisfies(item ->
                assertThat(((Map<?, ?>) item).get("processing_stage")).isEqualTo("NEED_REVIEW"));

        List<?> sourceReturns = http.getForObject(
                "/api/v1/import-batches/" + imported.get("id") + "/source-return-exports", List.class);
        assertThat(sourceReturns).isEmpty();

        ResponseEntity<Map> completed = completeSourceFollowup(
                openCase.get("id").toString(),
                ((Number) openCase.get("version")).longValue(),
                "已在来源平台补充第二批运单");
        assertThat(completed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(completed.getBody()).containsEntry("status", "RESOLVED");
        Map<String, Object> closed = get("/api/v1/orders/" + orderId);
        assertThat(closed.get("order_status")).isEqualTo("CLOSED");
        List<?> timeline = (List<?>) http.getForObject("/api/v1/orders/" + orderId + "/timeline", List.class);
        assertThat(timeline).anySatisfy(item ->
                assertThat(((Map<?, ?>) item).get("event_type_code"))
                        .isEqualTo("MANUAL_SOURCE_FOLLOWUP_COMPLETED"));
    }

    @Test
    void realMappingReferenceMatchesOnlyExactSourceProductsAndNeverActsAsAnOrderTemplate() throws Exception {
        Path reference = Path.of(System.getProperty(
                "zimu.jd-sku-reference",
                REPOSITORY_ROOT.resolve("京东商品编号.xlsx").toString()));
        Path sourceRoot = Path.of(System.getProperty("zimu.source-sample-root", REPOSITORY_ROOT.toString()));
        Path caishixian = sourceRoot.resolve("彩食鲜待发货订单.xlsx");
        Path jufubao = sourceRoot.resolve("聚福宝待发货订单.xlsx");
        Path feixiang = sourceRoot.resolve("飞象待发货订单.csv");
        Assumptions.assumeTrue(List.of(reference, caishixian, jufubao, feixiang).stream().allMatch(Files::isRegularFile));

        Map<String, Object> csx = previewReference(reference, caishixian);
        Map<String, Object> jfb = previewReference(reference, jufubao);
        Map<String, Object> fx = previewReference(reference, feixiang);

        assertThat(csx).containsEntry("source_channel", "CAISHIXIAN");
        assertThat(jfb).containsEntry("source_channel", "JUFUBAO");
        assertThat(fx).containsEntry("source_channel", "FEIXIANG");
        assertThat(((Map<?, ?>) csx.get("summary")).get("matched")).isEqualTo(4);
        assertThat(((Map<?, ?>) csx.get("summary")).get("need_review")).isEqualTo(2);
        assertThat(((Map<?, ?>) jfb.get("summary")).get("matched")).isEqualTo(0);
        assertThat(((Map<?, ?>) jfb.get("summary")).get("need_review")).isEqualTo(2);
        assertThat(((Map<?, ?>) jfb.get("summary")).get("conflict")).isEqualTo(0);
        assertThat(((Map<?, ?>) fx.get("summary")).get("matched")).isEqualTo(0);
        assertThat(((Map<?, ?>) fx.get("summary")).get("need_review")).isEqualTo(1);
        Map<?, ?> quality = (Map<?, ?>) csx.get("reference_quality");
        assertThat(quality.get("provider_sku_count")).isEqualTo(61);
        assertThat(quality.get("duplicate_provider_codes")).isEqualTo(2);
        assertThat(quality.get("bundle_count")).isEqualTo(25);
        assertThat(quality.get("ambiguous_bundle_rows")).isEqualTo(19);

        List<?> csxRows = (List<?>) csx.get("rows");
        assertThat(csxRows).anySatisfy(item -> {
            Map<?, ?> row = (Map<?, ?>) item;
            assertThat(row.get("source_sku_ref")).isEqualTo("2047705");
            assertThat(row.get("provider_sku_code")).isEqualTo("EMG4418824976893");
            assertThat(row.get("provider_sku_name")).isEqualTo("牛腱子(谷饲牛腱子)");
            assertThat(row.get("quantity_multiplier")).isEqualTo("2.000");
            assertThat(row.get("match_status")).isEqualTo("MATCHED");
        });
        assertThat(csxRows).anySatisfy(item -> {
            Map<?, ?> row = (Map<?, ?>) item;
            assertThat(row.get("source_sku_ref")).isEqualTo("2066622");
            assertThat(row.get("match_status")).isEqualTo("NEED_REVIEW");
        });
        assertThat((List<?>) fx.get("rows")).singleElement().satisfies(item -> {
            Map<?, ?> row = (Map<?, ?>) item;
            assertThat(row.get("source_sku_ref")).isEqualTo("6629889");
            assertThat(row.get("match_status")).isEqualTo("NEED_REVIEW");
            assertThat(row.get("reason_code")).isEqualTo("NO_EXACT_NAME_MATCH");
        });

        ResponseEntity<Map> rejectedAsOrder = uploadRaw(
                "mapping-reference.xlsx", Files.readAllBytes(reference), "mapping-reference-is-not-order");
        assertThat(rejectedAsOrder.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(rejectedAsOrder.getBody()).containsEntry("business_code", "TEMPLATE_FINGERPRINT_AMBIGUOUS");
    }

    @Test
    void jdExportUsesBundledSanitizedRealGoldenHeadersWithoutCopyingExampleRows() throws Exception {
        addExplicitJdFeixiangMapping();

        Map<String, Object> imported = upload(
                "jd-source.csv",
                feixiangSingleCsv("FX-JD-EXPORT-001", "FX-PRODUCT-JD-001", "1"),
                "source-import-jd-export-001");
        List<?> generated = (List<?>) imported.get("generated_fulfillment_export_ids");
        assertThat(generated).hasSize(1);
        String exportId = generated.getFirst().toString();
        assertThat(get("/api/v1/fulfillment-exports/" + exportId))
                .containsEntry("export_kind", "JD_WAREHOUSE");

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Operator", "excel-test");
        ResponseEntity<byte[]> response = http.exchange(
                "/api/v1/fulfillment-exports/" + exportId + "/file",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                byte[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(zipXmlText(response.getBody()))
                .doesNotContain(
                        "__STYLE_ROW__", "C:\\Users", "霍云弟", "18010037262",
                        "刘家窑", "202608030052", "EMG4419026221532");

        try (XSSFWorkbook workbook = new XSSFWorkbook(new java.io.ByteArrayInputStream(response.getBody()))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(2);
            assertThat(workbook.getSheetName(0)).isEqualTo("导入数据");
            assertThat(workbook.getSheetName(1)).isEqualTo("导入说明");
            var sheet = workbook.getSheetAt(0);
            for (int column = 16; column <= 21; column++) assertThat(sheet.isColumnHidden(column)).isTrue();
            for (int column = 23; column <= 54; column++) assertThat(sheet.isColumnHidden(column)).isTrue();
            for (int column = 56; column <= 58; column++) assertThat(sheet.isColumnHidden(column)).isTrue();
            for (int column = 60; column <= 77; column++) assertThat(sheet.isColumnHidden(column)).isTrue();
            assertThat(sheet.isColumnHidden(22)).isFalse();
            assertThat(sheet.isColumnHidden(55)).isFalse();
            assertThat(sheet.isColumnHidden(59)).isFalse();
            assertThat(sheet.getCellComments()).hasSize(68);
            assertThat(workbook.getSheetAt(1).getCellComments()).hasSize(136);
            var header = sheet.getRow(0);
            assertThat(header.getLastCellNum()).isEqualTo((short) 78);
            assertThat(new DataFormatter().formatCellValue(header.getCell(0))).isEmpty();
            Map<String, Integer> columns = headerColumns(header);
            assertThat(columns.keySet()).containsExactlyElementsOf(JD_GOLDEN_HEADERS);
            assertThat(sheet.getLastRowNum()).isEqualTo(1);

            var row = sheet.getRow(1);
            assertThat(new DataFormatter().formatCellValue(row.getCell(0))).isEmpty();
            assertText(row, columns, "*isv出库单号");
            assertText(row, columns, "销售平台订单号");
            assertText(row, columns, "*收货人手机");
            assertText(row, columns, "*京东商品编号");
            assertThat(value(row, columns, "*ISV来源编号")).isEqualTo("ISV0020000000079");
            assertThat(value(row, columns, "*事业部编号")).isEqualTo("EBU4418056064528");
            assertThat(value(row, columns, "*店铺编号")).isEqualTo("ESP0020008943717");
            assertThat(value(row, columns, "青龙业主号")).isEqualTo("010K5064550");
            assertThat(value(row, columns, "*仓库编号")).isEqualTo("118085840");
            assertThat(value(row, columns, "*承运商编号")).isEqualTo("CYS0000010");
            assertThat(value(row, columns, "*授权码pin")).isEqualTo("京诚乾元01");
            assertThat(row.getCell(columns.get("*销售平台来源")).getNumericCellValue()).isEqualTo(6);
            assertThat(value(row, columns, "*订单标记位")).isEqualTo("0".repeat(50));
            assertThat(row.getCell(columns.get("*商品金额")).getNumericCellValue()).isZero();
            assertThat(row.getCell(columns.get("*商品的出库数量")).getNumericCellValue()).isEqualTo(1);
            assertThat(value(row, columns, "仓配产品")).isEqualTo("LL-HD-M");
            assertThat(value(row, columns, "*收货人姓名")).isEqualTo("张三");
            assertThat(value(row, columns, "*收货人地址")).isEqualTo("上海市浦东新区测试路1号");
            assertThat(value(row, columns, "*京东商品编号")).isEqualTo("JD-SKU-000001");
        }
    }

    @Test
    void jdNonIntegerQuantityCreatesAnActionableReviewCaseInsteadOfAnExport() {
        addExplicitJdFeixiangMapping();

        ResponseEntity<Map> uploadResponse = uploadRaw(
                "jd-decimal-source.csv",
                feixiangSingleCsv("FX-JD-DECIMAL-001", "FX-PRODUCT-JD-001", "1.5"),
                "source-import-jd-decimal-001");
        assertThat(uploadResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String batchId = uploadResponse.getBody().get("id").toString();

        ResponseEntity<Map> confirmation = confirmBatch(
                batchId, "confirm-after-source-import-jd-decimal-001");
        assertThat(confirmation.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(confirmation.getBody()).containsEntry("business_code", "IMPORT_BATCH_EXPORT_INCOMPLETE");

        Map<String, Object> imported = get("/api/v1/import-batches/" + batchId);

        assertThat((List<?>) imported.get("generated_fulfillment_export_ids")).isEmpty();
        Map<String, Object> orders = get("/api/v1/orders?query=FX-JD-DECIMAL-001&page=0&size=20");
        Map<?, ?> summary = (Map<?, ?>) ((List<?>) orders.get("items")).getFirst();
        Map<String, Object> order = get("/api/v1/orders/" + summary.get("id"));
        List<?> reviewCases = (List<?>) order.get("review_cases");
        assertThat(reviewCases).singleElement().satisfies(item -> {
            Map<?, ?> reviewCase = (Map<?, ?>) item;
            assertThat(reviewCase.get("reason_code")).isEqualTo("QUANTITY_SCALE");
            assertThat(reviewCase.get("status")).isEqualTo("OPEN");
            assertThat(reviewCase.get("order_line_id")).isNotNull();
            Map<String, Object> detail = (Map<String, Object>) reviewCase.get("detail");
            assertThat(detail)
                    .containsEntry("reject_reason", "京东出库数量必须为正整数")
                    // 飞象样表无单位列：解析器回退标记「来源数量单位」即行快照里的真实事实。
                    .containsEntry("source_unit", "来源数量单位");
            assertThat(new java.math.BigDecimal((String) detail.get("source_quantity")))
                    .isEqualByComparingTo("1.5");
            assertThat(new java.math.BigDecimal((String) detail.get("quantity_multiplier")))
                    .isEqualByComparingTo("1.000");
            assertThat(new java.math.BigDecimal((String) detail.get("converted_quantity")))
                    .isEqualByComparingTo("1.5");
        });
    }

    private String zipXmlText(byte[] bytes) throws Exception {
        StringBuilder result = new StringBuilder();
        try (ZipInputStream input = new ZipInputStream(new java.io.ByteArrayInputStream(bytes))) {
            for (var entry = input.getNextEntry(); entry != null; entry = input.getNextEntry()) {
                if (entry.getName().endsWith(".xml")
                        || entry.getName().endsWith(".vml")
                        || entry.getName().endsWith(".rels")) {
                    result.append(new String(input.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        }
        return result.toString();
    }

    @Test
    void realFeixiangV2Gb18030CsvIsRecognizedWithoutAddingColumns() throws Exception {
        Path sample = Path.of(System.getProperty("zimu.feixiang-v2-sample", ""));
        Assumptions.assumeTrue(Files.isRegularFile(sample));

        Map<String, Object> imported = upload(
                "feixiang-v2.csv", Files.readAllBytes(sample), "source-import-real-fx-v2-001");
        assertThat(imported).containsEntry("source_channel", "FEIXIANG");
        assertThat(imported.get("template_version")).isEqualTo("v2-gb18030-lf");
        assertThat(((Map<?, ?>) imported.get("row_counts")).get("total")).isEqualTo(1);
        Map<String, Object> rows = get("/api/v1/import-batches/" + imported.get("id") + "/rows?page=0&size=20");
        Map<?, ?> raw = (Map<?, ?>) ((List<?>) rows.get("items")).getFirst();
        Map<?, ?> cells = (Map<?, ?>) raw.get("raw_cells");
        assertThat(cells).hasSize(40);
        assertThat(cells.containsKey("商品数量")).isTrue();
        assertThat(cells.containsKey("物流状态")).isTrue();
        assertThat(cells.containsKey("物流单号")).isTrue();
        assertThat(cells.containsKey("物流公司")).isFalse();
    }

    @Test
    void feixiangV2SourceReturnPreservesGb18030LfAndFortyColumns() throws Exception {
        Map<String, Object> imported = upload(
                "feixiang-v2.csv", feixiangV2Csv("FX-V2-CLOSED-001"), "source-import-fx-v2-closed-001");
        String exportId = ((List<?>) imported.get("generated_fulfillment_export_ids")).getFirst().toString();
        HttpHeaders downloadHeaders = new HttpHeaders();
        downloadHeaders.set("X-Operator", "excel-test");
        byte[] instruction = http.exchange(
                "/api/v1/fulfillment-exports/" + exportId + "/file",
                HttpMethod.GET,
                new HttpEntity<>(downloadHeaders),
                byte[].class).getBody();

        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(fillThirdPartyTracking(instruction)) {
            @Override public String getFilename() { return "tracking-v2.xlsx"; }
        });
        body.add("import_mode", "NEW");
        HttpHeaders trackingHeaders = new HttpHeaders();
        trackingHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);
        trackingHeaders.set("Idempotency-Key", "tracking-import-fx-v2-001");
        trackingHeaders.set("X-Operator", "excel-test");
        ResponseEntity<Map> trackingResponse = http.exchange(
                "/api/v1/fulfillment-exports/" + exportId + "/tracking-imports",
                HttpMethod.POST,
                new HttpEntity<>(body, trackingHeaders),
                Map.class);
        assertThat(trackingResponse.getStatusCode())
                .withFailMessage("tracking import failed: %s", trackingResponse.getBody())
                .isEqualTo(HttpStatus.CREATED);

        List<?> returnExports = http.getForObject(
                "/api/v1/import-batches/" + imported.get("id") + "/source-return-exports", List.class);
        String returnId = ((Map<?, ?>) returnExports.getFirst()).get("id").toString();
        ResponseEntity<byte[]> returnResponse = http.exchange(
                "/api/v1/source-return-exports/" + returnId + "/file",
                HttpMethod.GET,
                new HttpEntity<>(downloadHeaders),
                byte[].class);
        assertThat(returnResponse.getHeaders().getContentType()).isEqualTo(
                MediaType.parseMediaType("text/csv;charset=GB18030"));
        byte[] returned = returnResponse.getBody();
        assertThat(returned.length >= 3
                && returned[0] == (byte) 0xEF
                && returned[1] == (byte) 0xBB
                && returned[2] == (byte) 0xBF).isFalse();
        String text = new String(returned, java.nio.charset.Charset.forName("GB18030"));
        assertThat(text).doesNotContain("\r");
        String header = text.substring(0, text.indexOf('\n'));
        assertThat(header.split(",", -1)).hasSize(40);
        assertThat(header).doesNotContain("物流公司");
        assertThat(text).contains("物流状态", "已发货", "JDVAFX-V2-CLOSED-001");
    }

    private Map<String, Object> upload(String filename, byte[] bytes, String idempotencyKey) {
        ResponseEntity<Map> response = uploadRaw(filename, bytes, idempotencyKey);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map<String, Object> batch = response.getBody();
        Map<?, ?> counts = (Map<?, ?>) batch.get("row_counts");
        if (((Number) counts.get("need_review")).intValue() == 0
                && ((Number) counts.get("rejected")).intValue() == 0
                && batch.get("confirmed_at") == null) {
            ResponseEntity<Map> confirmed = confirmBatch(
                    batch.get("id").toString(), "confirm-after-" + idempotencyKey);
            assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.OK);
            return confirmed.getBody();
        }
        return batch;
    }

    private ResponseEntity<Map> uploadRaw(String filename, byte[] bytes, String idempotencyKey) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(bytes) {
            @Override public String getFilename() { return filename; }
        });
        body.add("import_mode", "NEW");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.set("Idempotency-Key", idempotencyKey);
        headers.set("X-Operator", "excel-test");
        headers.set("X-Request-Id", "req-" + idempotencyKey);
        return http.exchange(
                "/api/v1/import-batches/source-orders",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class);
    }

    private ResponseEntity<Map> confirmBatch(String batchId, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", idempotencyKey);
        headers.set("X-Operator", "excel-test");
        headers.set("X-Request-Id", "req-" + idempotencyKey);
        return http.exchange(
                "/api/v1/import-batches/" + batchId + "/confirm",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(), headers),
                Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> previewReference(Path reference, Path source) throws Exception {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("reference_file", resource(reference));
        body.add("source_file", resource(source));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<Map> response = http.exchange(
                "/api/v1/provider-sku-mapping-references/preview",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private ByteArrayResource resource(Path path) throws Exception {
        return new ByteArrayResource(Files.readAllBytes(path)) {
            @Override public String getFilename() { return path.getFileName().toString(); }
        };
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> get(String path) {
        ResponseEntity<Map> response = http.getForEntity(path, Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private BigDecimal actualShippedQuantity(String path) {
        List<?> rows = http.getForObject(path, List.class);
        assertThat(rows).isNotNull();
        return rows.stream()
                .map(row -> ((Map<?, ?>) row).get("actual_shipped_quantity"))
                .filter(value -> value != null)
                .map(value -> new BigDecimal(value.toString()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private byte[] xlsx(String sheetName, List<String> headers) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var row = workbook.createSheet(sheetName).createRow(0);
            for (int index = 0; index < headers.size(); index++) {
                row.createCell(index).setCellValue(headers.get(index));
            }
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private byte[] zhonghuiStatusWorkbook() throws Exception {
        List<String> headers = List.of(
                "订单号", "下单时间", "支付时间", "完成时间", "商品编号", "商品名称", "税率",
                "一级分类", "二级分类", "三级分类", "订单状态", "商品状态", "件数", "商家单价",
                "商家金额", "上游成本价", "商家结算金额", "商家优惠", "商家运费", "收件人",
                "收件电话", "收件地址", "发货状态", "包装规格", "单位");
        List<String> values = List.of(
                "S-ZHONGHUI-STATUS-001", "2026-08-21 10:00:00", "2026-08-21 10:00:01", "",
                "ZH-TP-STATUS-001", "中汇回填状态测试商品", "9", "生鲜食品", "猪牛羊肉", "牛肉",
                "待发货", "正常", "1", "100", "100", "60", "60", "0", "0", "状态测试客户",
                "13800000001", "北京市丰台区测试路1号", "未发货", "500g", "份");
        try (XSSFWorkbook workbook = new XSSFWorkbook();
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("Sheet1");
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

    private byte[] feixiangCsv(boolean includeRows) {
        String header = String.join(",", List.of(
                "订单号", "会员名称", "商品名称", "商品ID", "订单商品ID", "可发货数量",
                "收货人姓名", "收货人手机号", "收货人地址", "下单时间", "物流状态", "物流公司", "物流单号"));
        if (!includeRows) {
            return ("\uFEFF" + header + "\r\n").getBytes(StandardCharsets.UTF_8);
        }
        String row1 = "FX-ORDER-001,FX-MEMBER-001,子牧羊小腿,FX-PRODUCT-001,FX-LINE-001,1.500,张三,13800000000,\"上海市浦东新区测试路1号\",2026-08-11 10:00:00,,,";
        String row2 = "FX-ORDER-001,FX-MEMBER-001,未映射商品,FX-MISSING,FX-LINE-002,1.000,张三,13800000000,\"上海市浦东新区测试路1号\",2026-08-11 10:00:00,,,";
        return ("\uFEFF" + header + "\r\n" + row1 + "\r\n" + row2 + "\r\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    private byte[] feixiangSingleCsv(String orderRef) {
        return feixiangSingleCsv(orderRef, "FX-PRODUCT-001", "1.500");
    }

    private byte[] feixiangSingleCsv(String orderRef, String productRef, String quantity) {
        String all = new String(feixiangCsv(false), StandardCharsets.UTF_8);
        String row = orderRef + ",FX-MEMBER-001,子牧羊小腿," + productRef + "," + orderRef
                + "-LINE," + quantity + ",张三,13800000000,上海市浦东新区测试路1号,2026-08-11 10:00:00,,,\r\n";
        return (all + row).getBytes(StandardCharsets.UTF_8);
    }

    private byte[] feixiangV2Csv(String orderRef) {
        List<String> headers = List.of(
                "订单号", "会员名称", "商品名称", "发货方式", "自提点", "商品ID", "商品规格", "订单商品ID", "商品货号", "skuID",
                "商品69码", "商品税率", "商品数量", "市场价", "成本价/协议价", "会员价", "会员运费", "订单状态", "售后状态", "退款数量",
                "物流状态", "物流单号", "配送方式", "门店名称", "自提/配送时间", "门店地址", "预约发货时间", "购买人账号", "收货人姓名", "收货人手机号",
                "收货人地址", "下单时间", "会员支付时间", "发货时间", "预定发货时间", "订单完成时间", "商品标记", "核销人", "祝福语", "备注");
        Map<String, String> cells = new LinkedHashMap<>();
        headers.forEach(header -> cells.put(header, ""));
        cells.put("订单号", orderRef);
        cells.put("会员名称", "FX-MEMBER-001");
        cells.put("商品名称", "子牧羊小腿");
        cells.put("商品ID", "FX-PRODUCT-001");
        cells.put("商品规格", "标准箱");
        cells.put("订单商品ID", orderRef + "-LINE");
        cells.put("商品数量", "1.500");
        cells.put("收货人姓名", "张三");
        cells.put("收货人手机号", "13800000000");
        cells.put("收货人地址", "上海市浦东新区测试路1号");
        cells.put("下单时间", "2026-08-11 10:00:00");
        String csv = String.join(",", headers) + "\n"
                + String.join(",", headers.stream().map(cells::get).toList()) + "\n";
        return csv.getBytes(java.nio.charset.Charset.forName("GB18030"));
    }

    private Map<String, Integer> headerColumns(org.apache.poi.ss.usermodel.Row header) {
        Map<String, Integer> columns = new LinkedHashMap<>();
        DataFormatter formatter = new DataFormatter();
        for (int index = 1; index < header.getLastCellNum(); index++) {
            columns.put(formatter.formatCellValue(header.getCell(index)), index);
        }
        return columns;
    }

    private void assertText(org.apache.poi.ss.usermodel.Row row, Map<String, Integer> columns, String header) {
        assertThat(row.getCell(columns.get(header)).getCellType()).isEqualTo(CellType.STRING);
    }

    private String value(org.apache.poi.ss.usermodel.Row row, Map<String, Integer> columns, String header) {
        return new DataFormatter().formatCellValue(row.getCell(columns.get(header)));
    }

    private byte[] downloadExport(String exportId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Operator", "excel-test");
        ResponseEntity<byte[]> response = http.exchange(
                "/api/v1/fulfillment-exports/" + exportId + "/file",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                byte[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private byte[] downloadSourceReturn(String returnId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Operator", "excel-test");
        ResponseEntity<byte[]> response = http.exchange(
                "/api/v1/source-return-exports/" + returnId + "/file",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                byte[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private ResponseEntity<Map> uploadTracking(String exportId, byte[] returned, String idempotencyKey) {
        return uploadTracking(exportId, returned, idempotencyKey, null);
    }

    private ResponseEntity<Map> uploadTracking(
            String exportId, byte[] returned, String idempotencyKey, String requestId) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(returned) {
            @Override public String getFilename() { return "tracking.xlsx"; }
        });
        body.add("import_mode", "NEW");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.set("Idempotency-Key", idempotencyKey);
        headers.set("X-Operator", "excel-test");
        if (requestId != null) headers.set("X-Request-Id", requestId);
        return http.exchange(
                "/api/v1/fulfillment-exports/" + exportId + "/tracking-imports",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class);
    }

    private ResponseEntity<Map> createContinuation(
            String fulfillmentId,
            long expectedVersion,
            String instructedQuantity,
            String remark,
            String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", idempotencyKey);
        headers.set("X-Operator", "excel-test");
        return http.exchange(
                "/api/v1/fulfillments/" + fulfillmentId + "/continuation-exports",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "expected_version", expectedVersion,
                        "instructed_quantity", instructedQuantity,
                        "remark", remark), headers),
                Map.class);
    }

    private ResponseEntity<Map> completeSourceFollowup(String caseId, long expectedVersion, String note) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", "complete-source-followup-" + caseId);
        headers.set("X-Operator", "excel-test");
        return http.exchange(
                "/api/v1/review-cases/" + caseId + "/complete-source-followup",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("expected_version", expectedVersion, "note", note), headers),
                Map.class);
    }

    private ResponseEntity<Map> resolveReview(
            String caseId, String action, Map<String, Object> body, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", idempotencyKey);
        headers.set("X-Operator", "excel-test");
        return http.exchange(
                "/api/v1/review-cases/" + caseId + "/" + action,
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class);
    }

    private long createCancellationFixture(String fulfillmentId, String ticketNo, String quantity) {
        long id = Long.parseLong(fulfillmentId);
        long providerId = jdbc.queryForObject(
                "SELECT fulfillment_provider_id FROM app.fulfillments WHERE id=?", Long.class, id);
        jdbc.update(
                "UPDATE app.fulfillment_providers SET inventory_managed_by_us=true WHERE id=?", providerId);
        try {
            long ticketId = jdbc.queryForObject(
                    """
                    INSERT INTO app.procurement_tickets
                        (ticket_no, fulfillment_id, delivery_address, remark, created_by)
                    SELECT ?, f.id, o.receiver_address, '多包裹取消剩余量回归夹具', 'excel-test'
                    FROM app.fulfillments f
                    JOIN app.order_lines ol ON ol.id=f.order_line_id
                    JOIN app.orders o ON o.id=ol.order_id
                    WHERE f.id=?
                    RETURNING id
                    """,
                    Long.class,
                    ticketNo,
                    id);
            jdbc.update(
                    """
                    INSERT INTO app.procurement_ticket_items
                        (procurement_ticket_id, sku_id, requested_quantity, unit_snapshot)
                    SELECT ?, ol.sku_id, ?::numeric, ol.unit_snapshot
                    FROM app.fulfillments f JOIN app.order_lines ol ON ol.id=f.order_line_id
                    WHERE f.id=?
                    """,
                    ticketId,
                    quantity,
                    id);
            return ticketId;
        } finally {
            jdbc.update(
                    "UPDATE app.fulfillment_providers SET inventory_managed_by_us=false WHERE id=?", providerId);
        }
    }

    private ResponseEntity<Map> cancelRemaining(long ticketId, long expectedVersion, String reason) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", "cancel-remaining-" + ticketId);
        headers.set("X-Operator", "excel-test");
        return http.exchange(
                "/api/v1/procurement-tickets/" + ticketId + "/cancel-remaining",
                HttpMethod.POST,
                new HttpEntity<>(Map.of("expected_version", expectedVersion, "reason", reason), headers),
                Map.class);
    }

    private String createDemoFulfillmentFixture() {
        Long providerId = jdbc.queryForObject(
                "SELECT id FROM app.fulfillment_providers WHERE provider_type='THIRD_PARTY' ORDER BY id LIMIT 1",
                Long.class);
        Long customerId = jdbc.queryForObject(
                """
                INSERT INTO app.customers
                    (customer_code, customer_name, data_scope, status, profile)
                VALUES ('DEMO-CONTINUATION-CUSTOMER', '演示客户', 'DEMO', 'ACTIVE', '{}'::jsonb)
                RETURNING id
                """,
                Long.class);
        Long demoOrderId = jdbc.queryForObject(
                """
                INSERT INTO app.orders
                    (order_no, data_scope, source_channel, source_ref, source_ref_kind,
                     customer_id, order_status, settlement_method, settlement_time,
                     receiver_name, receiver_phone, receiver_address, evidence_refs)
                VALUES ('DEMO-CONTINUATION-ORDER', 'DEMO', 'WECOM', 'DEMO-CONTINUATION-SOURCE',
                        'SYNTHETIC', ?, 'FULFILLING', 'OTHER', CURRENT_TIMESTAMP,
                        '演示客户', '00000000000', 'DEMO 隔离地址', '[]'::jsonb)
                RETURNING id
                """,
                Long.class,
                customerId);
        Long lineId = jdbc.queryForObject(
                """
                INSERT INTO app.order_lines
                    (order_id, line_no, line_type, fulfillment_provider_id,
                     product_name_snapshot, specification_snapshot, unit_snapshot,
                     requested_quantity, processing_stage)
                VALUES (?, 1, 'SINGLE', ?, '演示商品', '演示规格', '件', 2.000, 'WAITING_PROVIDER')
                RETURNING id
                """,
                Long.class,
                demoOrderId,
                providerId);
        return jdbc.queryForObject(
                """
                INSERT INTO app.fulfillments
                    (fulfillment_no, order_line_id, fulfillment_provider_id, requested_quantity)
                VALUES ('DEMO-CONTINUATION-FULFILLMENT', ?, ?, 2.000)
                RETURNING id::text
                """,
                String.class,
                lineId,
                providerId);
    }

    private byte[] fillThirdPartyTracking(byte[] instruction) throws Exception {
        return fillThirdPartyTracking(instruction, "SHIPPED", "3.000", null);
    }

    private byte[] fillThirdPartyTracking(
            byte[] instruction, String result, String quantity, String trackingNumber) throws Exception {
        return fillThirdPartyTracking(
                instruction, result, quantity, trackingNumber, "2026-08-12 12:00:00");
    }

    private byte[] fillThirdPartyTracking(
            byte[] instruction, String result, String quantity, String trackingNumber, String shippedAt) throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(new java.io.ByteArrayInputStream(instruction));
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var row = workbook.getSheetAt(0).getRow(1);
            row.getCell(18).setCellValue(result);
            row.getCell(19).setCellValue(quantity);
            row.getCell(20).setCellValue("京东物流");
            row.getCell(21).setCellValue(
                    trackingNumber == null ? "JDVA" + row.getCell(7).getStringCellValue() : trackingNumber);
            row.getCell(22).setCellValue(shippedAt);
            workbook.write(output);
            return output.toByteArray();
        }
    }
}
