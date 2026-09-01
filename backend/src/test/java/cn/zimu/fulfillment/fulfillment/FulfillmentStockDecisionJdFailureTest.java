package cn.zimu.fulfillment.fulfillment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.web.CommandContext;
import cn.zimu.fulfillment.connector.jd.JdResult;
import cn.zimu.fulfillment.connector.jd.stock.JDStockService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 京东实时库存查询失败/返回无法解析时的降级路径：拒绝履约，告警与审计独立落库，
 * 业务事实不推进（不静默放行）。整个测试类用 @Primary Mock 替换 JDStockService 以注入故障。
 */
@Testcontainers
@Disabled("旧 JD 决策入口已 fail closed；当前失败语义由 ShipmentJdStockCheckApiTest 覆盖")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(FulfillmentStockDecisionJdFailureTest.JdStockMockConfig.class)
class FulfillmentStockDecisionJdFailureTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired FulfillmentStockDecisionService service;
    @Autowired JDStockService jdStock;

    @TestConfiguration
    static class JdStockMockConfig {
        @Bean
        @Primary
        JDStockService jdStockService() {
            return mock(JDStockService.class);
        }
    }

    @Test
    void jdQueryFailureRejectsKeepsAlertAndAuditAndDoesNotAdvanceBusiness() {
        when(jdStock.queryStockSnapshot(any())).thenReturn(
                new JdResult(false, "2001", "无权限", "jd-req-fail-001", null));
        Fact fact = createOrder("FAIL");

        assertThatThrownBy(() -> service.decide(
                fact.fulfillmentId(), envelope(), "stock-decision-fail-001",
                new CommandContext("req-stock-fail-001", "trace-stock-fail-001", "stock-test")))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getBusinessCode())
                        .isEqualTo("JD_STOCK_QUERY_FAILED"));
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.operational_alerts WHERE fulfillment_id=? "
                        + "AND alert_type='JD_STOCK_QUERY_FAILED' AND status='OPEN'",
                Long.class, fact.fulfillmentId())).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.audit_logs WHERE request_id='req-stock-fail-001' "
                        + "AND business_code='JD_STOCK_QUERY_FAILED'",
                Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT processing_stage FROM app.order_lines WHERE id=?", String.class, fact.orderLineId()))
                .isEqualTo("READY_TO_EXPORT");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.order_events WHERE order_id=? AND event_type_code='JD_STOCK_CHECKED'",
                Long.class, fact.orderId())).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.procurement_tickets WHERE fulfillment_id=?",
                Long.class, fact.fulfillmentId())).isZero();
    }

    @Test
    void jdMalformedPayloadRejectsWithAlertAndAudit() {
        when(jdStock.queryStockSnapshot(any())).thenReturn(
                new JdResult(true, "1000", "ok", "jd-req-malformed-001", Map.of("total", 1)));
        Fact fact = createOrder("MALFORMED");

        assertThatThrownBy(() -> service.decide(
                fact.fulfillmentId(), envelope(), "stock-decision-malformed-001",
                new CommandContext("req-stock-malformed-001", "trace-stock-malformed-001", "stock-test")))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getBusinessCode())
                        .isEqualTo("JD_STOCK_QUERY_FAILED"));
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.operational_alerts WHERE fulfillment_id=? "
                        + "AND alert_type='JD_STOCK_QUERY_FAILED' AND status='OPEN'",
                Long.class, fact.fulfillmentId())).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.audit_logs WHERE request_id='req-stock-malformed-001' "
                        + "AND business_code='JD_STOCK_QUERY_FAILED'",
                Long.class)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT processing_stage FROM app.order_lines WHERE id=?", String.class, fact.orderLineId()))
                .isEqualTo("READY_TO_EXPORT");
    }

    private StockDecisionCommand envelope() {
        return new StockDecisionCommand(
                StockDecisionCommand.Decision.AVAILABLE, Instant.parse("2026-08-12T03:00:00Z"), null);
    }

    private Fact createOrder(String suffix) {
        Map<String, Object> request = Map.of(
                "source", "WECOM",
                "source_ref", "WECOM-STOCK-" + suffix,
                "customer", Map.of("source_customer_ref", "WECOM-CUSTOMER-001", "name", "测试客户"),
                "receiver", Map.of("name", "张三", "phone", "13800000000", "address", "上海市浦东新区测试路 1 号"),
                "items", List.of(Map.of(
                        "line_type", "SINGLE", "source_sku_ref", "WECOM-SKU-JD-001",
                        "product_name", "子牧羊小腿", "specification", "500g/盒", "unit", "盒", "quantity", 2)),
                "settlement", Map.of("method", "MONTHLY", "settlement_time", "2026-08-11T10:00:00+08:00"));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", "stock-order-" + suffix.toLowerCase());
        headers.set("X-Request-Id", "req-stock-order-" + suffix.toLowerCase());
        headers.set("X-Operator", "stock-test");
        ResponseEntity<Map<String, Object>> response = http.exchange(
                "/internal/v1/orders", HttpMethod.POST, new HttpEntity<>(request, headers),
                new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {});
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long orderId = Long.parseLong(response.getBody().get("id").toString());
        Map<String, Object> fact = jdbc.queryForMap(
                """
                SELECT f.id fulfillment_id, ol.id order_line_id
                FROM app.fulfillments f JOIN app.order_lines ol ON ol.id=f.order_line_id
                WHERE ol.order_id=?
                """, orderId);
        return new Fact(orderId, ((Number) fact.get("fulfillment_id")).longValue(),
                ((Number) fact.get("order_line_id")).longValue());
    }

    private record Fact(long orderId, long fulfillmentId, long orderLineId) {}
}
