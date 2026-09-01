package cn.zimu.fulfillment.fulfillment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Ticket 02: 从公开 HTTP seam 验证 Shipment 级京东出库请求预览与结构化地址确认。 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "app.jd.write-mode=ON")
class ShipmentJdOutboundPreviewApiTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired ShipmentJdOutboundPreparer planner;

    @BeforeEach
    void configureJdProviderAndExplicitBoxConversion() {
        jdbc.update(
                """
                UPDATE app.fulfillment_providers
                SET config = ('{"sourceNo":"ISV-API-001","warehouseNo":"WH-API-001",' ||
                              '"erpShopNo":"ERP-SHOP-001","shopNo":"SHOP-API-001",' ||
                              '"ownerNo":"OWNER-API-001",' ||
                              '"salesPlatformSource":"6","pin":"PIN-API-001",' ||
                              '"carrierNo":"JD","townRequired":false}')::jsonb
                WHERE provider_code='JD'
                """);
        // jd-real-sdk-switch 02: 京东客户编码按订单客户取值,由客户档案维护
        jdbc.update(
                """
                UPDATE app.customers
                SET profile = jsonb_set(profile, '{jd_customer_code}', '"CUST-API-001"'::jsonb, true)
                WHERE customer_code='CUST-WECOM-0001'
                """);
        jdbc.update(
                """
                UPDATE app.provider_skus
                SET active=true,
                    external_codes = jsonb_set(external_codes, '{jd_pieces_per_unit}', '1'::jsonb, true)
                WHERE fulfillment_provider_id=(SELECT id FROM app.fulfillment_providers WHERE provider_code='JD')
                  AND provider_sku_code='JD-SKU-000001'
                """);
    }

    @Test
    void operatorConfirmsStructuredAddressThenPreviewsExactMultiLineRequestWithoutCallingJd() {
        Fact fact = createOrder("READY", List.of(item(1), item(2)), "待人工修正的自由文本地址");
        long shipmentId = createShipment(fact, "待人工修正的自由文本地址");

        ResponseEntity<Map> confirmation = http.exchange(
                "/api/v1/shipments/" + shipmentId + "/jd-receiver-address",
                HttpMethod.PUT,
                new HttpEntity<>(Map.of(
                        "expected_version", 0,
                        "province", "上海市",
                        "city", "上海市",
                        "county", "浦东新区",
                        "town", "张江镇",
                        "detail_address", "测试路1号"),
                        writeHeaders("jd-address-ready-001", "req-jd-address-ready-001")),
                Map.class);
        assertThat(confirmation.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(confirmation.getBody()).containsEntry("version", 1);

        ResponseEntity<Map> response = preview(shipmentId, "req-jd-preview-ready-001");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = response.getBody();
        String outboundOrderNo = jdbc.queryForObject(
                "SELECT outbound_order_no FROM app.shipments WHERE id=?", String.class, shipmentId);
        assertThat(body).containsEntry("shipment_id", String.valueOf(shipmentId));
        assertThat(body).containsEntry("shipment_version", 1);
        assertThat(body.get("erp_delivery_no").toString())
                .matches("ZIMU-SO-[0-9]{8}-[0-9]{12}-[0-9A-F]{8}")
                .isNotEqualTo(outboundOrderNo);
        assertThat(body).containsEntry("submittable", true);
        assertThat(body).doesNotContainKey("manual_correction_source");
        assertThat((List<?>) body.get("blockers")).isEmpty();

        Map<String, Object> request = castMap(body.get("request"));
        assertThat(request)
                .containsEntry("sourceNo", "ISV-API-001")
                .containsEntry("erpDeliveryNo", body.get("erp_delivery_no"))
                .containsEntry("warehouseNo", "WH-API-001")
                // 真实建单模板（2026-08-18）：订单类型留空（京东默认 B2C=1）
                .doesNotContainKey("orderType")
                .containsEntry("orderMark", "0".repeat(50))
                .containsEntry("pin", "***");
        assertThat(body.toString()).doesNotContain("PIN-API-001");
        assertThat(castMap(request.get("channelInfo")))
                .containsEntry("erpShopNo", "ERP-SHOP-001")
                // 真实建单模板（2026-08-18）：销售平台来源 6 不传平台单号
                .doesNotContainKey("salesPlatformDeliveryNo")
                .containsEntry("salesPlatformSource", "6");
        assertThat(castMap(request.get("customerInfo")))
                .containsEntry("customerCode", "CUST-API-001")
                .containsEntry("ownerNo", "OWNER-API-001")
                .containsEntry("shopNo", "SHOP-API-001");
        assertThat(castMap(request.get("receiverInfo")))
                .containsEntry("name", "张三")
                .containsEntry("mobile", "13800000000")
                .containsEntry("province", "上海市")
                .containsEntry("city", "上海市")
                .containsEntry("county", "浦东新区")
                .containsEntry("town", "张江镇")
                .containsEntry("detailAddress", "测试路1号");
        assertThat(castMap(request.get("carrierInfo"))).containsEntry("carrierNo", "JD");
        List<?> cargos = (List<?>) request.get("cargoInfos");
        assertThat(cargos).hasSize(2);
        assertThat(castMap(cargos.get(0)))
                .containsEntry("goodsNo", "JD-SKU-000001")
                .containsEntry("planQuantity", 1)
                .containsEntry("goodsLevel", "100")
                .containsEntry("orderLine", "1");
        assertThat(castMap(cargos.get(1)))
                .containsEntry("goodsNo", "JD-SKU-000001")
                .containsEntry("planQuantity", 2)
                .containsEntry("goodsLevel", "100")
                .containsEntry("orderLine", "2");

        List<Map<String, Object>> validations = castList(body.get("validations"));
        Map<String, Map<String, Object>> byPath = validations.stream()
                .collect(Collectors.toMap(row -> row.get("path").toString(), Function.identity()));
        assertThat(byPath.get("erpDeliveryNo"))
                .containsEntry("status", "PASS")
                .containsEntry("source", "shipment_jd_outbounds.erp_delivery_no");
        assertThat(byPath.get("receiverInfo.province"))
                .containsEntry("status", "PASS")
                .containsEntry("source", "shipments.jd_receiver_province (operator confirmed)");
        assertThat(byPath.get("cargoInfos[0].planQuantity"))
                .containsEntry("status", "PASS")
                .containsEntry("source", "shipment_items.instructed_quantity × provider_skus.external_codes.jd_pieces_per_unit");
        assertThat(validations).allSatisfy(row -> assertThat(row.get("source")).isNotNull());
        Set<String> requestLeafPaths = new LinkedHashSet<>();
        collectLeafPaths(request, "", requestLeafPaths);
        assertThat(byPath.keySet()).containsAll(requestLeafPaths);

        // 预览可重复且稳定：只保留独占的京东外部单号，不调用京东写接口。
        ResponseEntity<Map> repeated = preview(shipmentId, "req-jd-preview-ready-002");
        assertThat(repeated.getBody().get("erp_delivery_no")).isEqualTo(body.get("erp_delivery_no"));
        assertThat(((Map<?, ?>) repeated.getBody().get("request")).get("erpDeliveryNo"))
                .isEqualTo(body.get("erp_delivery_no"));
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.shipment_jd_outbounds WHERE shipment_id=?", Long.class, shipmentId))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.audit_logs WHERE operation='orderSoCreate'", Long.class)).isZero();

        // 确认和两次预览都可追溯；审计载荷不保存收货 PII 或密钥。
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.audit_logs WHERE operation='shipment.jd_receiver_address.confirm' "
                        + "AND request_id='req-jd-address-ready-001'",
                Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.audit_logs WHERE operation='shipment.jd_outbound.preview' "
                        + "AND request_id IN ('req-jd-preview-ready-001','req-jd-preview-ready-002')",
                Long.class)).isEqualTo(2L);
        String auditPayloads = jdbc.queryForObject(
                "SELECT string_agg(coalesce(request_payload::text,'') || coalesce(response_payload::text,''), ' ') "
                        + "FROM app.audit_logs WHERE request_id IN "
                        + "('req-jd-address-ready-001','req-jd-preview-ready-001','req-jd-preview-ready-002')",
                String.class);
        assertThat(auditPayloads)
                .doesNotContain("张三", "13800000000", "浦东新区", "测试路1号", "appSecret", "accessToken");
        String confirmationAudit = jdbc.queryForObject(
                "SELECT request_payload::text FROM app.audit_logs "
                        + "WHERE operation='shipment.jd_receiver_address.confirm' "
                        + "AND request_id='req-jd-address-ready-001'",
                String.class);
        assertThat(confirmationAudit)
                .contains("province_present", "city_present", "county_present", "detail_address_present")
                .doesNotContain("receiverInfo", "上海市", "张江镇", "测试路1号");
    }

    @Test
    void submissionPlanKeepsInternalRequestAndHashSeparateFromMaskedPreview() {
        Fact fact = createOrder("SNAPSHOT", List.of(item(2)), "待人工确认");
        long shipmentId = createShipment(fact, "待人工确认");
        confirmAddress(shipmentId, "snapshot");
        long auditCountBefore = jdbc.queryForObject("SELECT count(*) FROM app.audit_logs", Long.class);

        JdShipmentSubmissionPlan first = planner.plan(shipmentId);
        JdShipmentSubmissionPlan second = planner.plan(shipmentId);

        long providerId = jdbc.queryForObject(
                "SELECT fulfillment_provider_id FROM app.shipments WHERE id=?", Long.class, shipmentId);
        assertThat(first.shipmentId()).isEqualTo(shipmentId);
        assertThat(first.orderId()).isEqualTo(fact.orderId());
        assertThat(first.providerId()).isEqualTo(providerId);
        assertThat(first.shipmentVersion()).isEqualTo(1);
        assertThat(first.submittable()).isTrue();
        assertThat(first.request()).containsEntry("pin", "PIN-API-001");
        assertThat(first.request()).isEqualTo(second.request());
        assertThat(first.requestHash())
                .matches("[0-9a-f]{64}")
                .isEqualTo(second.requestHash());
        assertThat(first.request().get("erpDeliveryNo")).isEqualTo(first.erpDeliveryNo());
        assertThatThrownBy(() -> first.request().put("warehouseNo", "changed"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> castMap(first.request().get("customerInfo")).put("ownerNo", "changed"))
                .isInstanceOf(UnsupportedOperationException.class);

        Map<String, Object> previewBody = preview(shipmentId, "req-jd-preview-plan-projection").getBody();
        assertThat(castMap(previewBody.get("request"))).containsEntry("pin", "***");
        assertThat(previewBody).containsEntry("request_hash", first.requestHash());
        assertThat(previewBody.toString()).doesNotContain("PIN-API-001");
        JdShipmentSubmissionPlan afterProjection = planner.plan(shipmentId);
        assertThat(afterProjection.request()).isEqualTo(first.request());
        assertThat(afterProjection.requestHash()).isEqualTo(first.requestHash());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app.audit_logs", Long.class))
                .isEqualTo(auditCountBefore + 1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.shipment_jd_outbounds WHERE shipment_id=?", Long.class, shipmentId))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.audit_logs WHERE operation='orderSoCreate'", Long.class)).isZero();

    }

    @Test
    void previewBlocksMissingAndInactiveMappingsAndInvalidExplicitFactorsThroughHttp() {
        String missingRef = seedSourceSkuWithoutProviderMapping();
        Fact missingFact = createOrder("MAPPING-MISSING", List.of(item(missingRef, 1)), "待人工确认");
        long missingShipment = createShipment(missingFact, "待人工确认");
        confirmAddress(missingShipment, "mapping-missing");
        assertThat(blockerCodes(preview(missingShipment, "req-jd-preview-mapping-missing")))
                .contains("JD_SHIPMENT_OUTBOUND_SKU_MAPPING_MISSING");

        Fact fact = createOrder("MAPPING-INVALID", List.of(item(1)), "待人工确认");
        long shipmentId = createShipment(fact, "待人工确认");
        confirmAddress(shipmentId, "mapping-invalid");
        jdbc.update("UPDATE app.provider_skus SET active=false WHERE provider_sku_code='JD-SKU-000001'");
        assertThat(blockerCodes(preview(shipmentId, "req-jd-preview-mapping-inactive")))
                .contains("JD_SHIPMENT_OUTBOUND_SKU_MAPPING_MISSING");

        jdbc.update(
                "UPDATE app.provider_skus SET active=true, "
                        + "external_codes=jsonb_set(external_codes, '{jd_pieces_per_unit}', '0'::jsonb, true) "
                        + "WHERE provider_sku_code='JD-SKU-000001'");
        assertThat(blockerCodes(preview(shipmentId, "req-jd-preview-factor-zero")))
                .contains("JD_SHIPMENT_OUTBOUND_UNIT_CONFIG_INVALID");

        jdbc.update(
                "UPDATE app.provider_skus "
                        + "SET external_codes=jsonb_set(external_codes, '{jd_pieces_per_unit}', '\"not-a-number\"'::jsonb, true) "
                        + "WHERE provider_sku_code='JD-SKU-000001'");
        assertThat(blockerCodes(preview(shipmentId, "req-jd-preview-factor-nonnumeric")))
                .contains("JD_SHIPMENT_OUTBOUND_UNIT_CONFIG_INVALID");
    }

    @Test
    void receiverAddressConfirmationUsesShipmentCasAndRejectsAStaleWriter() {
        Fact fact = createOrder("ADDRESS-CAS", List.of(item(1)), "待人工确认");
        long shipmentId = createShipment(fact, "待人工确认");

        ResponseEntity<Map> accepted = putAddress(
                shipmentId, 0, "上海市", "上海市", "浦东新区", null, "测试路1号", "cas-first");
        ResponseEntity<Map> stale = putAddress(
                shipmentId, 0, "江苏省", "南京市", "鼓楼区", null, "中山路2号", "cas-stale");

        assertThat(accepted.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(accepted.getBody()).containsEntry("version", 1);
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(stale.getBody()).containsEntry("business_code", "VERSION_CONFLICT");
        assertThat(jdbc.queryForMap(
                "SELECT lock_version, jd_receiver_province, jd_receiver_detail_address "
                        + "FROM app.shipments WHERE id=?",
                shipmentId))
                .containsEntry("lock_version", 1L)
                .containsEntry("jd_receiver_province", "上海市")
                .containsEntry("jd_receiver_detail_address", "测试路1号");
    }

    @Test
    void addressAnalysisOffByDefaultKeepsTheOperatorConfirmedFourLevelPath() {
        // 缺省不配 = 老路径一字不变。这条是防回归：新功能不得悄悄改变既有行为。
        Fact fact = createOrder("ADDR-DEFAULT", List.of(item(1)), "待人工确认");
        long shipmentId = createShipment(fact, "待人工确认");

        ResponseEntity<Map> before = preview(shipmentId, "req-addr-default-unconfirmed");
        assertThat(castList(before.getBody().get("blockers")))
                .as("未确认地址时仍按原样阻断")
                .anySatisfy(blocker -> assertThat(blocker)
                        .containsEntry("code", "JD_SHIPMENT_OUTBOUND_RECEIVER_ADDRESS_NOT_CONFIRMED"));

        confirmAddress(shipmentId, "addr-default");
        ResponseEntity<Map> after = preview(shipmentId, "req-addr-default-confirmed");
        Map<String, Object> receiver = castMap(castMap(after.getBody().get("request")).get("receiverInfo"));
        assertThat(receiver).containsKeys("province", "city", "county", "detailAddress");
        assertThat(receiver).doesNotContainKey("addressAnalysis");
    }

    @Test
    void addressAnalysisTwoDelegatesFourLevelParsingToJdAndStopsSendingLevels() {
        Fact fact = createOrder("ADDR-JD-PARSE", List.of(item(1)), "待人工确认");
        long shipmentId = createShipment(fact, "待人工确认");
        confirmAddress(shipmentId, "addr-jd-parse");

        jdbc.update(
                "UPDATE app.fulfillment_providers "
                        + "SET config=jsonb_set(config, '{addressAnalysis}', '\"2\"'::jsonb, true) "
                        + "WHERE provider_code='JD'");
        ResponseEntity<Map> analyzed = preview(shipmentId, "req-addr-analysis-on");

        Map<String, Object> receiver = castMap(castMap(analyzed.getBody().get("request")).get("receiverInfo"));
        assertThat(receiver).containsEntry("addressAnalysis", 2);
        assertThat(receiver)
                .as("四级交给京东解析后，我方不再下发，避免与京东解析结果冲突")
                .doesNotContainKeys("province", "city", "county", "town");

        // 即便人工已确认过详细地址，送出去的仍是**原始快照全文**。
        // 生产实证（飞象 D2026825436038809722）：我方拆完的 detail 丢了「北京市朝阳区」
        // 这类定位锚点、却留着「太阳宫地区太阳宫」的重复片段——半成品比原文更难被京东解对。
        String rawSnapshot = jdbc.queryForObject(
                "SELECT receiver_address_snapshot FROM app.shipments WHERE id=?", String.class, shipmentId);
        String confirmedDetail = jdbc.queryForObject(
                "SELECT jd_receiver_detail_address FROM app.shipments WHERE id=?", String.class, shipmentId);
        assertThat(confirmedDetail).as("前置：这一单确实人工确认过").isNotBlank();
        assertThat(receiver).containsEntry("detailAddress", rawSnapshot);
        assertThat(analyzed.getBody()).containsEntry("submittable", true);

        jdbc.update("UPDATE app.fulfillment_providers SET config=config-'addressAnalysis' WHERE provider_code='JD'");
    }

    @Test
    void addressAnalysisTwoFallsBackToTheRawSnapshotWhenOperatorHasNotConfirmed() {
        // 未人工确认时不再阻断，而是把原始快照全文交给京东解析——这正是启用该模式的目的：
        // 把"能不能拆四级"这件事从我方词典手里交出去。
        // （收货人快照由 DB 触发器保护为不可变，因此空地址场景无法在此构造，
        //   改由 putJdAnalyzedAddress 的 hasText 分支在单元层面保证，不在此重复。）
        Fact fact = createOrder("ADDR-RAW", List.of(item(1)), "待人工确认");
        long shipmentId = createShipment(fact, "待人工确认");
        jdbc.update(
                "UPDATE app.fulfillment_providers "
                        + "SET config=jsonb_set(config, '{addressAnalysis}', '\"2\"'::jsonb, true) "
                        + "WHERE provider_code='JD'");

        ResponseEntity<Map> analyzed = preview(shipmentId, "req-addr-analysis-raw");
        Map<String, Object> receiver = castMap(castMap(analyzed.getBody().get("request")).get("receiverInfo"));
        assertThat(receiver).containsEntry("addressAnalysis", 2);
        assertThat(receiver.get("detailAddress"))
                .as("未确认时送原始快照全文，交京东解析")
                .isNotNull();
        assertThat(castList(analyzed.getBody().get("blockers")))
                .as("启用京东解析后，未确认地址不再是阻断项")
                .noneSatisfy(blocker -> assertThat(blocker)
                        .containsEntry("code", "JD_SHIPMENT_OUTBOUND_RECEIVER_ADDRESS_NOT_CONFIRMED"));

        jdbc.update("UPDATE app.fulfillment_providers SET config=config-'addressAnalysis' WHERE provider_code='JD'");
    }

    @Test
    void townRequirementMustBeAnExplicitProviderPolicyAndSupportsBothBranches() {
        Fact fact = createOrder("TOWN-POLICY", List.of(item(1)), "待人工确认");
        long shipmentId = createShipment(fact, "待人工确认");
        confirmAddress(shipmentId, "town-policy");

        jdbc.update(
                "UPDATE app.fulfillment_providers "
                        + "SET config=jsonb_set(config, '{townRequired}', 'true'::jsonb, true) "
                        + "WHERE provider_code='JD'");
        ResponseEntity<Map> required = preview(shipmentId, "req-jd-preview-town-required");
        assertThat(castList(required.getBody().get("blockers")))
                .anySatisfy(blocker -> assertThat(blocker)
                        .containsEntry("code", "JD_SHIPMENT_OUTBOUND_RECEIVER_ADDRESS_NOT_CONFIRMED")
                        .containsEntry("path", "receiverInfo.town"));

        jdbc.update(
                "UPDATE app.fulfillment_providers "
                        + "SET config=jsonb_set(config, '{townRequired}', 'false'::jsonb, true) "
                        + "WHERE provider_code='JD'");
        ResponseEntity<Map> optional = preview(shipmentId, "req-jd-preview-town-optional");
        assertThat(optional.getBody()).containsEntry("submittable", true);
        assertThat(castMap(castMap(optional.getBody().get("request")).get("receiverInfo")))
                .doesNotContainKey("town");

        jdbc.update("UPDATE app.fulfillment_providers SET config=config-'townRequired' WHERE provider_code='JD'");
        ResponseEntity<Map> unknown = preview(shipmentId, "req-jd-preview-town-policy-missing");
        assertThat(castList(unknown.getBody().get("blockers")))
                .anySatisfy(blocker -> assertThat(blocker)
                        .containsEntry("code", "JD_SHIPMENT_OUTBOUND_CONFIG_MISSING")
                        .containsEntry("path", "receiverInfo.townPolicy"));
    }

    @Test
    void previewExpandsOnlyTheBundleQuantityAllocatedToThisShipment() {
        Fact fact = createOrder("PARTIAL-BUNDLE", List.of(Map.of(
                "line_type", "CUSTOM_BUNDLE",
                "product_name", "子牧定制礼包",
                "specification", "礼包",
                "unit", "份",
                "quantity", 2,
                "components", List.of(Map.of(
                        "source_sku_ref", "WECOM-SKU-JD-001",
                        "product_name", "子牧羊小腿",
                        "specification", "500g/盒",
                        "unit", "盒",
                        "quantity_per_bundle", 3)))), "待人工确认");
        long shipmentId = createShipment(fact, "待人工确认", new BigDecimal("1"));
        confirmAddress(shipmentId, "partial-bundle");

        ResponseEntity<Map> response = preview(shipmentId, "req-jd-preview-partial-bundle-001");

        assertThat(response.getBody()).containsEntry("submittable", true);
        Map<String, Object> cargo = castMap(((List<?>) castMap(response.getBody().get("request"))
                .get("cargoInfos")).getFirst());
        assertThat(cargo)
                .containsEntry("orderLine", "1-1")
                .containsEntry("planQuantity", 3);
        Map<String, Object> planQuantityValidation = castList(response.getBody().get("validations")).stream()
                .filter(row -> "cargoInfos[0].planQuantity".equals(row.get("path")))
                .findFirst()
                .orElseThrow();
        assertThat(planQuantityValidation.get("source")).isEqualTo(
                "shipment_items.instructed_quantity × order_line_components.quantity_per_bundle "
                        + "× provider_skus.external_codes.jd_pieces_per_unit");
    }

    @Test
    void previewReturnsAllActionableBlockersWithoutGuessingAddressOrDefaultingBoxConversion() {
        Fact fact = createOrder("BLOCKED", List.of(item(1)), "上海市浦东新区测试路1号");
        long shipmentId = createShipment(fact, "上海市浦东新区测试路1号");
        jdbc.update(
                "UPDATE app.fulfillment_providers SET config=config-'warehouseNo' WHERE provider_code='JD'");
        jdbc.update(
                "UPDATE app.provider_skus SET external_codes=external_codes-'jd_pieces_per_unit' "
                        + "WHERE provider_sku_code='JD-SKU-000001'");

        ResponseEntity<Map> response = preview(shipmentId, "req-jd-preview-blocked-001");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("submittable", false);
        List<Map<String, Object>> blockers = castList(response.getBody().get("blockers"));
        assertThat(blockers).extracting(row -> row.get("code"))
                .contains(
                        "JD_SHIPMENT_OUTBOUND_CONFIG_MISSING",
                        "JD_SHIPMENT_OUTBOUND_RECEIVER_ADDRESS_NOT_CONFIRMED",
                        "JD_SHIPMENT_OUTBOUND_UNIT_CONVERSION_MISSING");
        assertThat(blockers).allSatisfy(row -> {
            assertThat(row.get("path")).isNotNull();
            assertThat(row.get("source")).isNotNull();
            assertThat(row.get("correction_target")).isNotNull();
        });
        Map<String, Object> request = castMap(response.getBody().get("request"));
        assertThat(request).doesNotContainKey("warehouseNo");
        Map<String, Object> receiver = castMap(request.get("receiverInfo"));
        assertThat(receiver).doesNotContainKeys("province", "city", "county", "town", "detailAddress");
        assertThat(response.getBody().get("manual_correction_source")).isEqualTo("上海市浦东新区测试路1号");
        assertThat(((List<?>) request.get("cargoInfos"))).singleElement().satisfies(row ->
                assertThat(castMap(row)).doesNotContainKey("planQuantity"));
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.audit_logs WHERE operation='orderSoCreate'", Long.class)).isZero();

        preview(shipmentId, "req-jd-preview-blocked-002");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.review_cases "
                        + "WHERE shipment_id=? AND reason_code='JD_SHIPMENT_OUTBOUND_PREVIEW_BLOCKED' "
                        + "AND status='OPEN'",
                Long.class, shipmentId)).isEqualTo(1L);

        jdbc.update(
                "UPDATE app.fulfillment_providers "
                        + "SET config=jsonb_set(config, '{warehouseNo}', '\"WH-API-001\"'::jsonb, true) "
                        + "WHERE provider_code='JD'");
        jdbc.update(
                "UPDATE app.provider_skus "
                        + "SET external_codes=jsonb_set(external_codes, '{jd_pieces_per_unit}', '1'::jsonb, true) "
                        + "WHERE provider_sku_code='JD-SKU-000001'");
        confirmAddress(shipmentId, "blocked-resolved");
        assertThat(preview(shipmentId, "req-jd-preview-blocked-resolved").getBody())
                .containsEntry("submittable", true);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.review_cases "
                        + "WHERE shipment_id=? AND reason_code='JD_SHIPMENT_OUTBOUND_PREVIEW_BLOCKED' "
                        + "AND status='RESOLVED'",
                Long.class, shipmentId)).isEqualTo(1L);
    }

    @Test
    void jdIdentifiersWrittenThroughTheApiClearConfigBlockersInPreview() {
        Fact fact = createOrder("API-CONFIG", List.of(item(1)), "待人工确认");
        long shipmentId = createShipment(fact, "待人工确认");
        jdbc.update("UPDATE app.fulfillment_providers SET config='{}'::jsonb WHERE provider_code='JD'");
        jdbc.update(
                "UPDATE app.provider_skus SET external_codes=external_codes-'jd_pieces_per_unit' "
                        + "WHERE provider_sku_code='JD-SKU-000001'");

        long providerId = jdbc.queryForObject(
                "SELECT id FROM app.fulfillment_providers WHERE provider_code='JD'", Long.class);
        long version = jdbc.queryForObject(
                "SELECT lock_version FROM app.fulfillment_providers WHERE id=?", Long.class, providerId);
        ResponseEntity<Map> configured = http.exchange(
                "/api/v1/fulfillment-providers/" + providerId,
                HttpMethod.PATCH,
                new HttpEntity<>(Map.of(
                        "expected_version", version,
                        "config", Map.of(
                                "sourceNo", "ISV-API-001",
                                "warehouseNo", "WH-API-001",
                                "pin", "PIN-API-001",
                                "erpShopNo", "ERP-SHOP-001",
                                "salesPlatformSource", "6",
                                "ownerNo", "OWNER-API-001",
                                "shopNo", "SHOP-API-001",
                                "carrierNo", "JD",
                                "townRequired", false)),
                        writeHeaders("provider-jd-config-preview-001", "req-provider-jd-config-preview-001")),
                Map.class);
        assertThat(configured.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> response = preview(shipmentId, "req-jd-preview-api-config-001");

        assertThat(response.getBody()).containsEntry("submittable", false);
        List<Map<String, Object>> blockers = castList(response.getBody().get("blockers"));
        // 本票覆盖的 9 项标识不再产生 config 阻塞；customerCode 真实建单裁决后回配置面
        // （2026-08-18 京东 2157：青龙业主号按事业部维护），无配置值时回退客户档案
        assertThat(blockers).extracting(row -> row.get("code"))
                .contains(
                        "JD_SHIPMENT_OUTBOUND_RECEIVER_ADDRESS_NOT_CONFIRMED",
                        "JD_SHIPMENT_OUTBOUND_UNIT_CONVERSION_MISSING")
                .doesNotContain(
                        "JD_SHIPMENT_OUTBOUND_CONFIG_MISSING",
                        "JD_SHIPMENT_OUTBOUND_CUSTOMER_CODE_MISSING");
        assertThat(castList(response.getBody().get("validations")))
                .filteredOn(row -> "customerInfo.customerCode".equals(row.get("path")))
                .singleElement()
                .satisfies(row -> assertThat(row).containsEntry(
                        "source", "customers.profile.jd_customer_code (customer archive, deprecated)"));
    }

    @Test
    void providerConfigCustomerCodeWinsOverCustomerArchive() {
        jdbc.update(
                """
                UPDATE app.fulfillment_providers
                SET config = config || '{"customerCode":"010K-API-001"}'::jsonb
                WHERE provider_code='JD'
                """);
        Fact fact = createOrder("CONFIG-CUSTOMER-CODE", List.of(item(1)), "待人工确认");
        long shipmentId = createShipment(fact, "待人工确认");
        confirmAddress(shipmentId, "config-customer-code");

        ResponseEntity<Map> response = preview(shipmentId, "req-jd-preview-config-customer-code-001");

        Map<String, Object> request = castMap(response.getBody().get("request"));
        assertThat(castMap(request.get("customerInfo"))).containsEntry("customerCode", "010K-API-001");
        assertThat(castList(response.getBody().get("validations")))
                .filteredOn(row -> "customerInfo.customerCode".equals(row.get("path")))
                .singleElement()
                .satisfies(row -> assertThat(row).containsEntry(
                        "source", "fulfillment_providers.config.customerCode"));
    }

    @Test
    void missingJdCustomerCodeBlocksPreviewPointingAtProviderConfig() {
        Fact fact = createOrder("NO-CUSTOMER-CODE", List.of(item(1)), "待人工确认");
        long shipmentId = createShipment(fact, "待人工确认");
        jdbc.update(
                "UPDATE app.customers SET profile=profile-'jd_customer_code' "
                        + "WHERE customer_code='CUST-WECOM-0001'");

        ResponseEntity<Map> response = preview(shipmentId, "req-jd-preview-no-customer-code-001");

        assertThat(response.getBody()).containsEntry("submittable", false);
        List<Map<String, Object>> blockers = castList(response.getBody().get("blockers"));
        assertThat(blockers).extracting(row -> row.get("code"))
                .contains("JD_SHIPMENT_OUTBOUND_CUSTOMER_CODE_MISSING");
        assertThat(blockers)
                .filteredOn(row -> "JD_SHIPMENT_OUTBOUND_CUSTOMER_CODE_MISSING".equals(row.get("code")))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row).containsEntry("path", "customerInfo.customerCode");
                    assertThat(row).containsEntry("source", "fulfillment_providers.config.customerCode");
                    assertThat(row.get("message").toString()).contains("customerCode");
                });
    }

    @Test
    void previewBlocksNonIntegralConversionInsteadOfRounding() {
        // 03 起小数换算系数（如 0.5 件/盒）在配置校验阶段即阻断（UNIT_CONFIG_INVALID）。
        // 整数契约：小数 JSON token 在订单创建入口即被反序列化门禁拒绝，
        // 不再进入 shipment/预览层被四舍五入；
        // 预览层 JD_SHIPMENT_OUTBOUND_NON_INTEGRAL_QUANTITY 判据保留为纵深防御。
        Map<String, Object> request = Map.of(
                "source", "WECOM",
                "source_ref", "WECOM-JD-PREVIEW-FRACTION",
                "customer", Map.of("source_customer_ref", "WECOM-CUSTOMER-001", "name", "测试客户"),
                "receiver", Map.of("name", "张三", "phone", "13800000000", "address", "待人工确认"),
                "items", List.of(item(new BigDecimal("1.500"))),
                "settlement", Map.of("method", "MONTHLY", "settlement_time", "2026-08-13T10:00:00+08:00"));
        ResponseEntity<Map> response = http.exchange(
                "/internal/v1/orders",
                HttpMethod.POST,
                new HttpEntity<>(request, writeHeaders(
                        "jd-preview-order-fraction", "req-jd-preview-order-fraction")),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).containsEntry("business_code", "MALFORMED_REQUEST");
    }

    private ResponseEntity<Map> preview(long shipmentId, String requestId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Request-Id", requestId);
        headers.set("X-Operator", "shipment-jd-preview-test");
        return http.exchange(
                "/api/v1/shipments/" + shipmentId + "/jd-so-order-preview",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                Map.class);
    }

    private void confirmAddress(long shipmentId, String suffix) {
        long version = jdbc.queryForObject(
                "SELECT lock_version FROM app.shipments WHERE id=?", Long.class, shipmentId);
        ResponseEntity<Map> response = putAddress(
                shipmentId, version, "上海市", "上海市", "浦东新区", null, "测试路1号", suffix);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private ResponseEntity<Map> putAddress(
            long shipmentId,
            long expectedVersion,
            String province,
            String city,
            String county,
            String town,
            String detailAddress,
            String suffix) {
        Map<String, Object> command = new java.util.LinkedHashMap<>();
        command.put("expected_version", expectedVersion);
        command.put("province", province);
        command.put("city", city);
        command.put("county", county);
        if (town != null) {
            command.put("town", town);
        }
        command.put("detail_address", detailAddress);
        return http.exchange(
                "/api/v1/shipments/" + shipmentId + "/jd-receiver-address",
                HttpMethod.PUT,
                new HttpEntity<>(command,
                        writeHeaders("jd-address-" + suffix + "-001", "req-jd-address-" + suffix + "-001")),
                Map.class);
    }

    private Fact createOrder(String suffix, List<Map<String, Object>> items, String address) {
        String sourceRef = "WECOM-JD-PREVIEW-" + suffix;
        Map<String, Object> request = Map.of(
                "source", "WECOM",
                "source_ref", sourceRef,
                "customer", Map.of("source_customer_ref", "WECOM-CUSTOMER-001", "name", "测试客户"),
                "receiver", Map.of("name", "张三", "phone", "13800000000", "address", address),
                "items", items,
                "settlement", Map.of("method", "MONTHLY", "settlement_time", "2026-08-13T10:00:00+08:00"));
        ResponseEntity<Map> response = http.exchange(
                "/internal/v1/orders",
                HttpMethod.POST,
                new HttpEntity<>(request, writeHeaders(
                        "jd-preview-order-" + suffix.toLowerCase(),
                        "req-jd-preview-order-" + suffix.toLowerCase())),
                Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long orderId = Long.parseLong(response.getBody().get("id").toString());
        List<Long> fulfillmentIds = jdbc.queryForList(
                """
                SELECT f.id FROM app.fulfillments f
                JOIN app.order_lines ol ON ol.id=f.order_line_id
                WHERE ol.order_id=? ORDER BY ol.line_no
                """,
                Long.class,
                orderId);
        return new Fact(orderId, sourceRef, fulfillmentIds);
    }

    private Map<String, Object> item(Object quantity) {
        return item("WECOM-SKU-JD-001", quantity);
    }

    private Map<String, Object> item(String sourceSkuRef, Object quantity) {
        return Map.of(
                "line_type", "SINGLE",
                "source_sku_ref", sourceSkuRef,
                "product_name", "子牧羊小腿",
                "specification", "500g/盒",
                "unit", "盒",
                "quantity", quantity);
    }

    private String seedSourceSkuWithoutProviderMapping() {
        String token = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        long productId = jdbc.queryForObject(
                """
                SELECT sku.product_id
                FROM app.provider_skus mapping
                JOIN app.fulfillment_providers provider ON provider.id = mapping.fulfillment_provider_id
                JOIN app.skus sku ON sku.id = mapping.sku_id
                WHERE provider.provider_code='JD' AND mapping.provider_sku_code='JD-SKU-000001'
                """,
                Long.class);
        long providerId = jdbc.queryForObject(
                "SELECT id FROM app.fulfillment_providers WHERE provider_code='JD'", Long.class);
        long skuId = jdbc.queryForObject(
                """
                INSERT INTO app.skus (product_id, fulfillment_provider_id, specification, unit)
                VALUES (?, ?, ?, '盒') RETURNING id
                """,
                Long.class, productId, providerId, "未配置京东映射-" + token);
        String sourceRef = "WECOM-SKU-JD-MISSING-" + token;
        jdbc.update(
                """
                INSERT INTO app.source_channel_skus
                    (source_channel, source_sku_ref, source_product_name, quantity_multiplier, sku_id)
                VALUES ('WECOM', ?, '未配置京东映射商品', 1.000, ?)
                """,
                sourceRef, skuId);
        return sourceRef;
    }

    private List<Object> blockerCodes(ResponseEntity<Map> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsEntry("submittable", false);
        return castList(response.getBody().get("blockers")).stream()
                .map(row -> row.get("code"))
                .toList();
    }

    private long createShipment(Fact fact, String address) {
        return createShipment(fact, address, null);
    }

    private long createShipment(Fact fact, String address, BigDecimal instructedOverride) {
        long providerId = jdbc.queryForObject(
                "SELECT fulfillment_provider_id FROM app.fulfillments WHERE id=?",
                Long.class,
                fact.fulfillmentIds().getFirst());
        long shipmentId = jdbc.queryForObject(
                """
                INSERT INTO app.shipments
                    (shipment_no, order_id, fulfillment_provider_id, shipment_sequence,
                     receiver_name_snapshot, receiver_phone_snapshot, receiver_address_snapshot)
                VALUES (?, ?, ?, 1, ?, ?, ?) RETURNING id
                """,
                Long.class,
                "SHIP-PREVIEW-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12),
                fact.orderId(), providerId, "张三", "13800000000", address);
        for (long fulfillmentId : fact.fulfillmentIds()) {
            BigDecimal instructed = instructedOverride == null
                    ? jdbc.queryForObject(
                            "SELECT requested_quantity FROM app.fulfillments WHERE id=?",
                            BigDecimal.class,
                            fulfillmentId)
                    : instructedOverride;
            jdbc.update(
                    "INSERT INTO app.shipment_items(shipment_id, fulfillment_id, instructed_quantity) VALUES (?, ?, ?)",
                    shipmentId, fulfillmentId, instructed);
        }
        return shipmentId;
    }

    private HttpHeaders writeHeaders(String idempotencyKey, String requestId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", idempotencyKey);
        headers.set("X-Request-Id", requestId);
        headers.set("X-Operator", "shipment-jd-preview-test");
        return headers;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> castList(Object value) {
        return (List<Map<String, Object>>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    private static void collectLeafPaths(Object value, String prefix, Set<String> paths) {
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, nested) -> collectLeafPaths(
                    nested,
                    prefix.isEmpty() ? key.toString() : prefix + "." + key,
                    paths));
            return;
        }
        if (value instanceof List<?> list) {
            for (int index = 0; index < list.size(); index++) {
                collectLeafPaths(list.get(index), prefix + "[" + index + "]", paths);
            }
            return;
        }
        paths.add(prefix);
    }

    private record Fact(long orderId, String sourceRef, List<Long> fulfillmentIds) {
    }
}
