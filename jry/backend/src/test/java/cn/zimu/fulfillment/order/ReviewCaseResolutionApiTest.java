package cn.zimu.fulfillment.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
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

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReviewCaseResolutionApiTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TestRestTemplate http;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void resolvesCustomerCaseWithAnExistingBusinessCustomerAndRejectsAStaleVersion() {
        Map<String, Object> createdOrder = createOrderNeedingCustomerReview();
        Map<String, Object> createdCase = firstReviewCase(createdOrder);
        String caseId = createdCase.get("id").toString();
        String customerId = jdbc.queryForObject(
                "SELECT id::text FROM app.customers WHERE customer_code='CUST-WECOM-0001' AND data_scope='BUSINESS'",
                String.class);
        Map<String, Object> command = Map.of(
                "expected_version", createdCase.get("version"),
                "customer_id", customerId,
                "source_channel", "WECOM",
                "source_customer_ref", "WECOM-CUSTOMER-REVIEW-001",
                "remark", "人工核对客户合同后确认");

        ResponseEntity<Map> resolved = http.exchange(
                "/api/v1/review-cases/" + caseId + "/resolve-customer",
                HttpMethod.POST,
                new HttpEntity<>(command, writeHeaders("review-customer-001", "req-review-customer-001")),
                Map.class);

        assertThat(resolved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resolved.getBody()).isNotNull();
        assertThat(resolved.getBody().get("status")).isEqualTo("RESOLVED");
        assertThat(resolved.getBody().get("version")).isEqualTo(1);
        assertThat(resolved.getBody().get("resolved_by")).isEqualTo("integration-test");
        assertThat(resolved.getBody().get("resolved_at")).isNotNull();
        assertThat((Map<String, Object>) resolved.getBody().get("resolution"))
                .containsEntry("resolution_type", "CUSTOMER_CONFIRMED")
                .containsEntry("customer_id", customerId)
                .containsEntry("remark", "人工核对客户合同后确认");

        ResponseEntity<Map> detail = http.getForEntity("/api/v1/review-cases/" + caseId, Map.class);
        assertThat(detail.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(detail.getBody()).containsEntry("status", "RESOLVED");
        assertThat(jdbc.queryForObject(
                "SELECT customer_id::text FROM app.customer_source_refs WHERE source_channel='WECOM' AND source_customer_ref=?",
                String.class,
                "WECOM-CUSTOMER-REVIEW-001"))
                .isEqualTo(customerId);
        assertThat(jdbc.queryForObject(
                "SELECT customer_id::text || ':' || order_status FROM app.orders WHERE id=?",
                String.class,
                Long.parseLong(createdOrder.get("id").toString())))
                .isEqualTo(customerId + ":SKU_MAPPED");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.fulfillments f JOIN app.order_lines ol ON ol.id=f.order_line_id WHERE ol.order_id=?",
                Integer.class,
                Long.parseLong(createdOrder.get("id").toString())))
                .isEqualTo(1);

        ResponseEntity<Map> audits = http.getForEntity(
                "/api/v1/audit-logs?request_id=req-review-customer-001", Map.class);
        assertThat((List<Map<String, Object>>) audits.getBody().get("items"))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.get("business_code")).isEqualTo("REVIEW_CASE_RESOLVED");
                    assertThat(item.get("operation")).isEqualTo("review_case.resolve_customer");
                });

        ResponseEntity<Map> conflict = http.exchange(
                "/api/v1/review-cases/" + caseId + "/resolve-customer",
                HttpMethod.POST,
                new HttpEntity<>(command, writeHeaders("review-customer-stale-001", "req-review-customer-stale-001")),
                Map.class);
        assertThat(conflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(conflict.getBody()).containsEntry("business_code", "VERSION_CONFLICT");
    }

    @Test
    void resolvesSingleSkuCaseWithAnExistingActiveSkuWithoutCreatingSkuMasterData() {
        Map<String, Object> createdOrder = createOrderNeedingSkuReview();
        Map<String, Object> createdCase = firstReviewCase(createdOrder);
        String caseId = createdCase.get("id").toString();
        String skuId = jdbc.queryForObject(
                "SELECT id::text FROM app.skus WHERE active=true ORDER BY id LIMIT 1", String.class);
        Integer skuCountBefore = jdbc.queryForObject("SELECT count(*) FROM app.skus", Integer.class);
        Map<String, Object> command = Map.of(
                "expected_version", createdCase.get("version"),
                "sku_id", skuId,
                "source_channel", "WECOM",
                "source_sku_ref", "WECOM-SKU-REVIEW-001",
                "quantity_multiplier", "2.000",
                "remark", "人工按已确认装箱规格选择既有 SKU");

        ResponseEntity<Map> resolved = http.exchange(
                "/api/v1/review-cases/" + caseId + "/resolve-sku",
                HttpMethod.POST,
                new HttpEntity<>(command, writeHeaders("review-sku-001", "req-review-sku-001")),
                Map.class);

        assertThat(resolved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resolved.getBody()).containsEntry("status", "RESOLVED");
        assertThat((Map<String, Object>) resolved.getBody().get("resolution"))
                .containsEntry("resolution_type", "SKU_CONFIRMED")
                .containsEntry("sku_id", skuId)
                .containsEntry("quantity_multiplier", "2.000");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app.skus", Integer.class)).isEqualTo(skuCountBefore);
        assertThat(jdbc.queryForMap(
                        "SELECT sku_id::text sku_id, quantity_multiplier::text multiplier FROM app.source_channel_skus WHERE source_channel='WECOM' AND source_sku_ref=?",
                        "WECOM-SKU-REVIEW-001"))
                .containsEntry("sku_id", skuId)
                .containsEntry("multiplier", "2.000");
        assertThat(jdbc.queryForMap(
                        "SELECT sku_id::text sku_id, requested_quantity::text quantity, processing_stage FROM app.order_lines WHERE order_id=?",
                        Long.parseLong(createdOrder.get("id").toString())))
                .containsEntry("sku_id", skuId)
                .containsEntry("quantity", "4.000")
                .containsEntry("processing_stage", "READY_TO_EXPORT");
        assertThat(jdbc.queryForObject(
                "SELECT order_status FROM app.orders WHERE id=?",
                String.class,
                Long.parseLong(createdOrder.get("id").toString())))
                .isEqualTo("SKU_MAPPED");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.audit_logs WHERE request_id=? AND operation='review_case.resolve_sku'",
                Integer.class,
                "req-review-sku-001"))
                .isEqualTo(1);
    }

    @Test
    void completesSourceFollowupOnlyAfterTerminalFulfillmentAndTrackingForEveryRealShipment() {
        SourceFollowupFixture fixture = sourceFollowupFixture();
        Map<String, Object> command = Map.of(
                "expected_version", 0,
                "note", "已在来源平台逐票补录第二运单并复核完成");

        ResponseEntity<Map> blocked = http.exchange(
                "/api/v1/review-cases/" + fixture.caseId() + "/complete-source-followup",
                HttpMethod.POST,
                new HttpEntity<>(command, writeHeaders("followup-blocked-001", "req-followup-blocked-001")),
                Map.class);
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(blocked.getBody()).containsEntry("business_code", "SOURCE_FOLLOWUP_NOT_READY");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM app.review_cases WHERE id=?", String.class, fixture.caseId()))
                .isEqualTo("OPEN");

        jdbc.update(
                "INSERT INTO app.trackings (shipment_id, logistics_company_code, logistics_company_name, tracking_number) VALUES (?, 'SF', '顺丰', 'SF-FOLLOWUP-002')",
                fixture.untrackedShipmentId());

        ResponseEntity<Map> completed = http.exchange(
                "/api/v1/review-cases/" + fixture.caseId() + "/complete-source-followup",
                HttpMethod.POST,
                new HttpEntity<>(command, writeHeaders("followup-complete-001", "req-followup-complete-001")),
                Map.class);
        ResponseEntity<Map> replayed = http.exchange(
                "/api/v1/review-cases/" + fixture.caseId() + "/complete-source-followup",
                HttpMethod.POST,
                new HttpEntity<>(command, writeHeaders("followup-complete-001", "req-followup-replay-001")),
                Map.class);

        assertThat(completed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replayed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replayed.getBody()).isEqualTo(completed.getBody());
        assertThat(completed.getBody()).containsEntry("status", "RESOLVED");
        assertThat(completed.getBody()).containsEntry("resolved_by", "integration-test");
        assertThat((Map<String, Object>) completed.getBody().get("resolution"))
                .containsEntry("resolution_type", "SOURCE_FOLLOWUP_COMPLETED")
                .containsEntry("note", "已在来源平台逐票补录第二运单并复核完成");
        assertThat(jdbc.queryForObject(
                "SELECT order_status FROM app.orders WHERE id=?", String.class, fixture.orderId()))
                .isEqualTo("CLOSED");
        assertThat(jdbc.queryForList(
                        "SELECT processing_stage FROM app.order_lines WHERE order_id=?", fixture.orderId()))
                .allSatisfy(row -> assertThat(row.get("processing_stage")).isEqualTo("COMPLETED"));
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.order_events WHERE order_id=? AND event_type_code='MANUAL_SOURCE_FOLLOWUP_COMPLETED'",
                Integer.class,
                fixture.orderId()))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.order_versions WHERE order_id=?", Integer.class, fixture.orderId()))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.audit_logs WHERE request_id=? AND business_code='MANUAL_SOURCE_FOLLOWUP_COMPLETED'",
                Integer.class,
                "req-followup-complete-001"))
                .isEqualTo(1);
    }

    @Test
    void resolvesManuallyOnlyForWhitelistedReasonsWithOptionalNoteAndRejectsStaleVersion() {
        Map<String, Object> order = createMappedOrder("WECOM-ORDER-MANUAL-REVIEW-001");
        long orderId = Long.parseLong(order.get("id").toString());
        long caseId = insertBusinessReviewCase(orderId, "RC-MANUAL-001", "SKU_MAPPING_CONFLICT");

        Map<String, Object> command = Map.of("expected_version", 0, "note", "已在主数据页修正冲突映射");
        ResponseEntity<Map> resolved = http.exchange(
                "/api/v1/review-cases/" + caseId + "/resolve",
                HttpMethod.POST,
                new HttpEntity<>(command, writeHeaders("manual-resolve-001", "req-manual-resolve-001")),
                Map.class);

        assertThat(resolved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resolved.getBody()).containsEntry("status", "RESOLVED");
        assertThat(resolved.getBody()).containsEntry("resolved_by", "integration-test");
        assertThat((Map<String, Object>) resolved.getBody().get("resolution"))
                .containsEntry("resolution_type", "MANUAL_RESOLVED")
                .containsEntry("note", "已在主数据页修正冲突映射");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.audit_logs WHERE request_id=? AND operation='review_case.resolve'",
                Integer.class,
                "req-manual-resolve-001"))
                .isEqualTo(1);

        // 同一幂等键重放返回同一结果，且不重复落审计
        ResponseEntity<Map> replayed = http.exchange(
                "/api/v1/review-cases/" + caseId + "/resolve",
                HttpMethod.POST,
                new HttpEntity<>(command, writeHeaders("manual-resolve-001", "req-manual-resolve-replay-001")),
                Map.class);
        assertThat(replayed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replayed.getBody()).isEqualTo(resolved.getBody());

        // 备注可选：空备注同样可解决
        long secondCaseId = insertBusinessReviewCase(orderId, "RC-MANUAL-002", "REVISION_AFTER_EXPORT");
        ResponseEntity<Map> noNote = http.exchange(
                "/api/v1/review-cases/" + secondCaseId + "/resolve",
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of("expected_version", 0, "note", ""),
                        writeHeaders("manual-resolve-002", "req-manual-resolve-002")),
                Map.class);
        assertThat(noNote.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(noNote.getBody()).containsEntry("status", "RESOLVED");

        // 过期版本被拒
        ResponseEntity<Map> stale = http.exchange(
                "/api/v1/review-cases/" + secondCaseId + "/resolve",
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of("expected_version", 0, "note", "再点一次"),
                        writeHeaders("manual-resolve-stale-001", "req-manual-resolve-stale-001")),
                Map.class);
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(stale.getBody()).containsEntry("business_code", "VERSION_CONFLICT");

        // 有专用动作的事项不允许走通用解决
        Map<String, Object> customerOrder = createOrderNeedingCustomerReview();
        Map<String, Object> customerCase = firstReviewCase(customerOrder);
        ResponseEntity<Map> notAllowed = http.exchange(
                "/api/v1/review-cases/" + customerCase.get("id") + "/resolve",
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of("expected_version", customerCase.get("version"), "note", ""),
                        writeHeaders("manual-resolve-customer-001", "req-manual-resolve-customer-001")),
                Map.class);
        assertThat(notAllowed.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(notAllowed.getBody()).containsEntry("business_code", "REVIEW_ACTION_NOT_ALLOWED");
    }

    @Test
    void dismissesOpenCaseAndProtectsMessageChainCases() {
        Map<String, Object> order = createMappedOrder("WECOM-ORDER-DISMISS-001");
        long orderId = Long.parseLong(order.get("id").toString());
        long caseId = insertBusinessReviewCase(orderId, "RC-DISMISS-001", "FULFILLMENT_EXCEPTION");

        ResponseEntity<Map> dismissed = http.exchange(
                "/api/v1/review-cases/" + caseId + "/dismiss",
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of("expected_version", 0, "note", "误建，线下已处理"),
                        writeHeaders("dismiss-001", "req-dismiss-001")),
                Map.class);
        assertThat(dismissed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(dismissed.getBody()).containsEntry("status", "DISMISSED");
        assertThat(dismissed.getBody()).containsEntry("resolved_by", "integration-test");
        assertThat((Map<String, Object>) dismissed.getBody().get("resolution"))
                .containsEntry("resolution_type", "DISMISSED")
                .containsEntry("note", "误建，线下已处理");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.audit_logs WHERE request_id=? AND operation='review_case.dismiss'",
                Integer.class,
                "req-dismiss-001"))
                .isEqualTo(1);

        // 消息链路事项禁止在工作台直接关闭，避免草稿或提交被孤立
        long draftCaseId = insertBusinessReviewCase(orderId, "RC-DISMISS-002", "WECOM_ORDER_DRAFT");
        ResponseEntity<Map> protectedCase = http.exchange(
                "/api/v1/review-cases/" + draftCaseId + "/dismiss",
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of("expected_version", 0, "note", "想关掉"),
                        writeHeaders("dismiss-002", "req-dismiss-002")),
                Map.class);
        assertThat(protectedCase.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(protectedCase.getBody()).containsEntry("business_code", "REVIEW_DISMISS_NOT_ALLOWED");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM app.review_cases WHERE id=?", String.class, draftCaseId))
                .isEqualTo("OPEN");
    }

    @Test
    void listsOnlyBusinessAlertsAndAcknowledgesWithoutAdvancingTheOrder() {
        Map<String, Object> businessOrder = createMappedOrderForAlert();
        long businessOrderId = Long.parseLong(businessOrder.get("id").toString());
        long businessAlertId = jdbc.queryForObject(
                """
                INSERT INTO app.operational_alerts
                    (alert_no, alert_type, severity, order_id, message, detail)
                VALUES ('ALERT-REVIEW-001', 'TRACKING_DELAY', 'RED', ?, '运单回传超过 SLA', '{"sla_minutes":60}'::jsonb)
                RETURNING id
                """,
                Long.class,
                businessOrderId);
        long demoOrderId = createDemoOrder();
        assertThatThrownBy(() -> jdbc.update(
                        """
                        INSERT INTO app.operational_alerts
                            (alert_no, alert_type, severity, order_id, message, detail)
                        VALUES ('ALERT-DEMO-001', 'TRACKING_DELAY', 'RED', ?, '演示提醒', '{}'::jsonb)
                        """,
                        demoOrderId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("demo orders cannot create business review cases or operational alerts");

        ResponseEntity<Map> listed = http.getForEntity(
                "/api/v1/operational-alerts?status=OPEN&severity=RED&page=0&size=1", Map.class);

        assertThat(listed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listed.getBody()).containsEntry("total_elements", 1);
        assertThat((List<Map<String, Object>>) listed.getBody().get("items"))
                .singleElement()
                .satisfies(item -> {
                    assertThat(item.get("id")).isEqualTo(String.valueOf(businessAlertId));
                    assertThat(item.get("order_id")).isEqualTo(String.valueOf(businessOrderId));
                    assertThat(item.get("status")).isEqualTo("OPEN");
                });
        String orderStatusBefore = jdbc.queryForObject(
                "SELECT order_status FROM app.orders WHERE id=?", String.class, businessOrderId);
        Map<String, Object> command = Map.of("expected_version", 0, "note", "已通知对应运营同学跟进");

        ResponseEntity<Map> acknowledged = http.exchange(
                "/api/v1/operational-alerts/" + businessAlertId + "/acknowledge",
                HttpMethod.POST,
                new HttpEntity<>(command, writeHeaders("alert-ack-001", "req-alert-ack-001")),
                Map.class);

        assertThat(acknowledged.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(acknowledged.getBody())
                .containsEntry("status", "ACKNOWLEDGED")
                .containsEntry("acknowledged_by", "integration-test")
                .containsEntry("version", 1);
        assertThat(acknowledged.getBody().get("acknowledged_at")).isNotNull();
        assertThat(jdbc.queryForObject(
                "SELECT detail->>'acknowledgement_note' FROM app.operational_alerts WHERE id=?",
                String.class,
                businessAlertId))
                .isEqualTo("已通知对应运营同学跟进");
        assertThat(jdbc.queryForObject(
                "SELECT order_status FROM app.orders WHERE id=?", String.class, businessOrderId))
                .isEqualTo(orderStatusBefore);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.audit_logs WHERE request_id=? AND operation='operational_alert.acknowledge'",
                Integer.class,
                "req-alert-ack-001"))
                .isEqualTo(1);

        ResponseEntity<Map> stale = http.exchange(
                "/api/v1/operational-alerts/" + businessAlertId + "/acknowledge",
                HttpMethod.POST,
                new HttpEntity<>(command, writeHeaders("alert-ack-stale-001", "req-alert-ack-stale-001")),
                Map.class);
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(stale.getBody()).containsEntry("business_code", "VERSION_CONFLICT");

    }

    private long insertBusinessReviewCase(long orderId, String caseNo, String reasonCode) {
        return jdbc.queryForObject(
                """
                INSERT INTO app.review_cases
                    (case_no, case_type, status, responsible_team, reason_code, order_id, detail)
                VALUES (?, 'ORDER', 'OPEN', 'ORDER_OPS', ?, ?, '{}'::jsonb)
                RETURNING id
                """,
                Long.class,
                caseNo,
                reasonCode,
                orderId);
    }

    private Map<String, Object> createMappedOrder(String sourceRef) {
        Map<String, Object> request = Map.of(
                "source", "WECOM",
                "source_ref", sourceRef,
                "customer", Map.of("source_customer_ref", "WECOM-CUSTOMER-001", "name", "子牧测试客户"),
                "receiver", Map.of("name", "王五", "phone", "13700000000", "address", "上海市测试地址"),
                "items", List.of(Map.of(
                        "line_type", "SINGLE", "source_sku_ref", "WECOM-SKU-JD-001",
                        "product_name", "子牧羊小腿", "specification", "500g/盒", "unit", "盒", "quantity", "2.000")),
                "settlement", Map.of("method", "MONTHLY", "settlement_time", "2026-08-12T10:00:00+08:00"));
        ResponseEntity<Map> response = http.exchange(
                "/internal/v1/orders", HttpMethod.POST,
                new HttpEntity<>(request, writeHeaders("order-" + sourceRef, "req-" + sourceRef)), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private Map<String, Object> createOrderNeedingCustomerReview() {
        Map<String, Object> request = Map.of(
                "source", "WECOM",
                "source_ref", "WECOM-ORDER-CUSTOMER-REVIEW-001",
                "customer", Map.of(
                        "source_customer_ref", "WECOM-CUSTOMER-REVIEW-001",
                        "name", "待确认客户"),
                "receiver", Map.of(
                        "name", "张三",
                        "phone", "13800000000",
                        "address", "上海市浦东新区测试路 1 号"),
                "items", List.of(Map.of(
                        "line_type", "SINGLE",
                        "source_sku_ref", "WECOM-SKU-JD-001",
                        "product_name", "子牧羊小腿",
                        "specification", "500g/盒",
                        "unit", "盒",
                        "quantity", "1.000")),
                "settlement", Map.of(
                        "method", "MONTHLY",
                        "settlement_time", "2026-08-12T10:00:00+08:00"));
        ResponseEntity<Map> response = http.exchange(
                "/internal/v1/orders",
                HttpMethod.POST,
                new HttpEntity<>(request, writeHeaders("order-review-customer-001", "req-order-review-customer-001")),
                Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private Map<String, Object> createOrderNeedingSkuReview() {
        Map<String, Object> request = Map.of(
                "source", "WECOM",
                "source_ref", "WECOM-ORDER-SKU-REVIEW-001",
                "customer", Map.of(
                        "source_customer_ref", "WECOM-CUSTOMER-001",
                        "name", "子牧测试客户"),
                "receiver", Map.of(
                        "name", "李四",
                        "phone", "13900000000",
                        "address", "上海市浦东新区测试路 2 号"),
                "items", List.of(Map.of(
                        "line_type", "SINGLE",
                        "source_sku_ref", "WECOM-SKU-REVIEW-001",
                        "product_name", "待映射商品",
                        "specification", "2盒/组",
                        "unit", "组",
                        "quantity", "2.000")),
                "settlement", Map.of(
                        "method", "MONTHLY",
                        "settlement_time", "2026-08-12T10:00:00+08:00"));
        ResponseEntity<Map> response = http.exchange(
                "/internal/v1/orders",
                HttpMethod.POST,
                new HttpEntity<>(request, writeHeaders("order-review-sku-001", "req-order-review-sku-001")),
                Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private SourceFollowupFixture sourceFollowupFixture() {
        Map<String, Object> order = createMappedOrderForFollowup();
        long orderId = Long.parseLong(order.get("id").toString());
        Map<String, Object> context = jdbc.queryForMap(
                """
                SELECT ol.id line_id, f.id fulfillment_id, f.fulfillment_provider_id provider_id
                FROM app.order_lines ol JOIN app.fulfillments f ON f.order_line_id=ol.id
                WHERE ol.order_id=?
                """,
                orderId);
        long lineId = ((Number) context.get("line_id")).longValue();
        long fulfillmentId = ((Number) context.get("fulfillment_id")).longValue();
        long providerId = ((Number) context.get("provider_id")).longValue();
        jdbc.update("UPDATE app.order_lines SET processing_stage='NEED_REVIEW' WHERE id=?", lineId);
        jdbc.update("UPDATE app.orders SET order_status='NEED_REVIEW' WHERE id=?", orderId);
        long firstShipmentId = shipment(orderId, providerId, fulfillmentId, 1, "SHP-FOLLOWUP-001");
        long secondShipmentId = shipment(orderId, providerId, fulfillmentId, 2, "SHP-FOLLOWUP-002");
        jdbc.update(
                "UPDATE app.fulfillments SET cumulative_shipped_quantity=requested_quantity, shipping_progress='SHIPPED', outcome='FULLY_FULFILLED' WHERE id=?",
                fulfillmentId);
        jdbc.update(
                "INSERT INTO app.trackings (shipment_id, logistics_company_code, logistics_company_name, tracking_number) VALUES (?, 'SF', '顺丰', 'SF-FOLLOWUP-001')",
                firstShipmentId);
        long caseId = jdbc.queryForObject(
                """
                INSERT INTO app.review_cases
                    (case_no, case_type, status, responsible_team, reason_code,
                     order_id, order_line_id, fulfillment_id, detail)
                VALUES ('RC-FOLLOWUP-001', 'SOURCE_FOLLOWUP', 'OPEN', 'ORDER_OPS',
                        'MULTI_SHIPMENT_SOURCE_FOLLOWUP', ?, ?, ?, '{}'::jsonb)
                RETURNING id
                """,
                Long.class,
                orderId,
                lineId,
                fulfillmentId);
        return new SourceFollowupFixture(orderId, caseId, secondShipmentId);
    }

    private long shipment(long orderId, long providerId, long fulfillmentId, int sequence, String shipmentNo) {
        long shipmentId = jdbc.queryForObject(
                """
                INSERT INTO app.shipments
                    (shipment_no, order_id, fulfillment_provider_id, shipment_sequence,
                     receiver_name_snapshot, receiver_phone_snapshot, receiver_address_snapshot,
                     shipment_status, shipped_at)
                VALUES (?, ?, ?, ?, '王五', '13700000000', '上海市测试地址', 'SHIPPED', CURRENT_TIMESTAMP)
                RETURNING id
                """,
                Long.class,
                shipmentNo,
                orderId,
                providerId,
                sequence);
        jdbc.update(
                "INSERT INTO app.shipment_items (shipment_id, fulfillment_id, instructed_quantity, shipped_quantity) VALUES (?, ?, 1.000, 1.000)",
                shipmentId,
                fulfillmentId);
        return shipmentId;
    }

    private Map<String, Object> createMappedOrderForFollowup() {
        Map<String, Object> request = Map.of(
                "source", "WECOM",
                "source_ref", "WECOM-ORDER-FOLLOWUP-001",
                "customer", Map.of("source_customer_ref", "WECOM-CUSTOMER-001", "name", "子牧测试客户"),
                "receiver", Map.of("name", "王五", "phone", "13700000000", "address", "上海市测试地址"),
                "items", List.of(Map.of(
                        "line_type", "SINGLE", "source_sku_ref", "WECOM-SKU-JD-001",
                        "product_name", "子牧羊小腿", "specification", "500g/盒", "unit", "盒", "quantity", "2.000")),
                "settlement", Map.of("method", "MONTHLY", "settlement_time", "2026-08-12T10:00:00+08:00"));
        ResponseEntity<Map> response = http.exchange(
                "/internal/v1/orders", HttpMethod.POST,
                new HttpEntity<>(request, writeHeaders("order-followup-001", "req-order-followup-001")), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private Map<String, Object> createMappedOrderForAlert() {
        Map<String, Object> request = Map.of(
                "source", "WECOM",
                "source_ref", "WECOM-ORDER-ALERT-001",
                "customer", Map.of("source_customer_ref", "WECOM-CUSTOMER-001", "name", "子牧测试客户"),
                "receiver", Map.of("name", "赵六", "phone", "13600000000", "address", "上海市测试地址"),
                "items", List.of(Map.of(
                        "line_type", "SINGLE", "source_sku_ref", "WECOM-SKU-JD-001",
                        "product_name", "子牧羊小腿", "specification", "500g/盒", "unit", "盒", "quantity", "1.000")),
                "settlement", Map.of("method", "MONTHLY", "settlement_time", "2026-08-12T10:00:00+08:00"));
        ResponseEntity<Map> response = http.exchange(
                "/internal/v1/orders", HttpMethod.POST,
                new HttpEntity<>(request, writeHeaders("order-alert-001", "req-order-alert-001")), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private long createDemoOrder() {
        ResponseEntity<Map[]> scenarios = http.getForEntity("/demo/v1/scenarios", Map[].class);
        String scenarioCode = scenarios.getBody()[0].get("scenario_code").toString();
        ResponseEntity<Map> run = http.exchange(
                "/demo/v1/scenarios",
                HttpMethod.POST,
                new HttpEntity<>(
                        Map.of("scenario_code", scenarioCode),
                        writeHeaders("demo-alert-run-001", "req-demo-alert-run-001")),
                Map.class);
        assertThat(run.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return Long.parseLong(run.getBody().get("order_id").toString());
    }

    private static Map<String, Object> firstReviewCase(Map<String, Object> order) {
        List<Map<String, Object>> cases = (List<Map<String, Object>>) order.get("review_cases");
        assertThat(cases).singleElement();
        return cases.getFirst();
    }

    private static HttpHeaders writeHeaders(String key, String requestId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", key);
        headers.set("X-Operator", "integration-test");
        headers.set("X-Request-Id", requestId);
        return headers;
    }

    private record SourceFollowupFixture(long orderId, long caseId, long untrackedShipmentId) {}
}
