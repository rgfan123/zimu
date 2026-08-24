package cn.zimu.fulfillment.connector.jufubao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.connector.SourceShipmentResult;
import cn.zimu.fulfillment.connector.SourceSyncResult;
import cn.zimu.fulfillment.connector.ConnectorRuntime;
import cn.zimu.fulfillment.file.SourceImportService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class JufubaoConnectorTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void reportsOneConnectorWithOnlinePullAndPushUsingTheSharedLoginProbe() {
        JufubaoConnector connector = connector(successfulGateway());

        var result = connector.testConnection(new ConnectorRuntime("REAL", "API", true, null, false));

        assertThat(result.success()).isTrue();
        assertThat(result.businessCode()).isEqualTo("OK");
        assertThat(connector.capabilities().onlinePull()).isTrue();
        assertThat(connector.capabilities().onlinePush()).isTrue();
    }

    @Test
    void marksShipmentSyncedOnlyAfterPlatformOrderLeavesNoDelivery() {
        ObjectNode product = mapper.createObjectNode()
                .put("product_id", "p-1")
                .put("allow_send_num", 1)
                .put("fd-FnhFHVU1Xi", "browser-only");
        FakeGateway gateway = new FakeGateway(
                List.of(
                        JufubaoShipmentGateway.OrderState.noDelivery("sub-1"),
                        JufubaoShipmentGateway.OrderState.notPending("sub-1")),
                new JufubaoShipmentGateway.ShipmentDetail(List.of(product)),
                List.of(new JufubaoShipmentGateway.CarrierOption("京东物流", 17)),
                JufubaoShipmentGateway.SubmitResult.accepted("req-1"));
        JufubaoConnector connector = connector(gateway);

        SourceSyncResult result = connector.pushShipmentResult(shipment("sub-1", "京东物流", "JDVA123"));

        assertThat(result.success()).isTrue();
        assertThat(result.businessCode()).isEqualTo("OK");
        assertThat(result.platformRef()).isEqualTo("req-1");
        assertThat(connector.capabilities().onlinePush()).isTrue();
        assertThat(gateway.submittedProducts).singleElement().satisfies(submitted -> {
            assertThat(submitted.has("fd-FnhFHVU1Xi")).isFalse();
            assertThat(submitted.path("allow_send_num").asInt()).isEqualTo(1);
        });
        assertThat(gateway.submitCount).isEqualTo(1);
        assertThat(gateway.stateReadCount).isEqualTo(2);
    }

    @Test
    void replaysTheFirstResultWithoutSubmittingTheSameShipmentTwice() {
        FakeGateway gateway = successfulGateway();
        JufubaoConnector connector = connector(gateway);
        SourceShipmentResult command = shipment("sub-1", "京东物流", "JDVA123");

        SourceSyncResult first = connector.pushShipmentResult(command);
        SourceSyncResult replay = connector.pushShipmentResult(command);

        assertThat(first.success()).isTrue();
        assertThat(replay).isEqualTo(first);
        assertThat(gateway.submitCount).isEqualTo(1);
    }

    @Test
    void refusesToSubmitWhenTheOrderIsNotInNoDelivery() {
        FakeGateway gateway = new FakeGateway(
                List.of(JufubaoShipmentGateway.OrderState.notPending("sub-1")),
                detail(1),
                carriers(),
                JufubaoShipmentGateway.SubmitResult.accepted("req-1"));

        SourceSyncResult result = connector(gateway)
                .pushShipmentResult(shipment("sub-1", "京东物流", "JDVA123"));

        assertThat(result.success()).isFalse();
        assertThat(result.businessCode()).isEqualTo("JUFUBAO_ORDER_NOT_SHIPPABLE");
        assertThat(gateway.submitCount).isZero();
    }

    @Test
    void failsClosedWhenTheCarrierOrQuantityDoesNotMatchPlatformFacts() {
        FakeGateway quantityGateway = new FakeGateway(
                List.of(JufubaoShipmentGateway.OrderState.noDelivery("sub-1")),
                detail(2),
                carriers(),
                JufubaoShipmentGateway.SubmitResult.accepted("req-1"));
        FakeGateway carrierGateway = new FakeGateway(
                List.of(JufubaoShipmentGateway.OrderState.noDelivery("sub-2")),
                detail(1),
                carriers(),
                JufubaoShipmentGateway.SubmitResult.accepted("req-2"));

        SourceSyncResult quantity = connector(quantityGateway)
                .pushShipmentResult(shipment("sub-1", "京东物流", "JDVA123"));
        SourceSyncResult carrier = connector(carrierGateway)
                .pushShipmentResult(shipment("sub-2", "未知快递", "JDVA456"));

        assertThat(quantity.businessCode()).isEqualTo("JUFUBAO_SHIPMENT_QUANTITY_MISMATCH");
        assertThat(carrier.businessCode()).isEqualTo("JUFUBAO_CARRIER_UNMAPPED");
        assertThat(quantityGateway.submitCount).isZero();
        assertThat(carrierGateway.submitCount).isZero();
    }

    @Test
    void distinguishesPlatformRejectionFromAnUncertainOutcome() {
        FakeGateway rejectedGateway = new FakeGateway(
                List.of(JufubaoShipmentGateway.OrderState.noDelivery("sub-1")),
                detail(1),
                carriers(),
                JufubaoShipmentGateway.SubmitResult.rejected("InvalidArgument", "快递单号非法", "req-r"));
        FakeGateway unknownGateway = new FakeGateway(
                List.of(JufubaoShipmentGateway.OrderState.noDelivery("sub-2")),
                detail(1),
                carriers(),
                JufubaoShipmentGateway.SubmitResult.unknown("连接中断", "req-u"));

        SourceSyncResult rejected = connector(rejectedGateway)
                .pushShipmentResult(shipment("sub-1", "京东物流", "JDVA123"));
        SourceSyncResult unknown = connector(unknownGateway)
                .pushShipmentResult(shipment("sub-2", "京东物流", "JDVA456"));

        assertThat(rejected.businessCode()).isEqualTo("InvalidArgument");
        assertThat(rejected.message()).isEqualTo("聚福宝拒绝发货请求（业务码：InvalidArgument）");
        assertThat(rejected.platformRef()).isEqualTo("req-r");
        assertThat(unknown.businessCode()).isEqualTo("RECONCILIATION_REQUIRED");
        assertThat(unknown.message()).contains("禁止盲目重提");
        assertThat(unknown.platformRef()).isEqualTo("req-u");
    }

    @Test
    void replaysAnUncertainOutcomeWithoutBlindlySubmittingAgain() {
        FakeGateway gateway = new FakeGateway(
                List.of(JufubaoShipmentGateway.OrderState.noDelivery("sub-1")),
                detail(1),
                carriers(),
                JufubaoShipmentGateway.SubmitResult.unknown("超时", "req-u"));
        JufubaoConnector connector = connector(gateway);
        SourceShipmentResult command = shipment("sub-1", "京东物流", "JDVA123");

        SourceSyncResult first = connector.pushShipmentResult(command);
        SourceSyncResult replay = connector.pushShipmentResult(command);

        assertThat(replay).isEqualTo(first);
        assertThat(replay.businessCode()).isEqualTo("RECONCILIATION_REQUIRED");
        assertThat(gateway.submitCount).isEqualTo(1);
    }

    @Test
    void rejectsAChangedPayloadThatReusesTheSameShipmentKey() {
        FakeGateway gateway = successfulGateway();
        JufubaoConnector connector = connector(gateway);

        SourceSyncResult first = connector.pushShipmentResult(shipment("sub-1", "京东物流", "JDVA123"));
        SourceSyncResult conflict = connector.pushShipmentResult(new SourceShipmentResult(
                SourceChannel.JUFUBAO,
                "different-main",
                "sub-1",
                BigDecimal.ONE,
                "SHIPPED",
                "17",
                "JDVA123",
                null));

        assertThat(first.success()).isTrue();
        assertThat(conflict.businessCode()).isEqualTo("JUFUBAO_IDEMPOTENCY_CONFLICT");
        assertThat(gateway.submitCount).isEqualTo(1);
    }

    @Test
    void acceptsAWholeQuantityEvenWhenBigDecimalHasTrailingZeroes() {
        FakeGateway gateway = successfulGateway();
        SourceShipmentResult command = new SourceShipmentResult(
                SourceChannel.JUFUBAO,
                "main-1",
                "sub-1",
                new BigDecimal("1.0"),
                "SHIPPED",
                "京东物流",
                "JDVA123",
                null);

        SourceSyncResult result = connector(gateway).pushShipmentResult(command);

        assertThat(result.success()).isTrue();
    }

    @Test
    void reportsReconciliationWhenAcceptedOrderRemainsPending() {
        FakeGateway gateway = new FakeGateway(
                List.of(
                        JufubaoShipmentGateway.OrderState.noDelivery("sub-1"),
                        JufubaoShipmentGateway.OrderState.noDelivery("sub-1")),
                detail(1),
                carriers(),
                JufubaoShipmentGateway.SubmitResult.accepted("req-1"));

        SourceSyncResult result = connector(gateway)
                .pushShipmentResult(shipment("sub-1", "京东物流", "JDVA123"));

        assertThat(result.businessCode()).isEqualTo("RECONCILIATION_REQUIRED");
        assertThat(result.success()).isFalse();
        assertThat(gateway.submitCount).isEqualTo(1);
    }

    @Test
    void allowsSafeRetryAfterAPreSubmitFailureIsCorrected() {
        FakeGateway gateway = new FakeGateway(
                List.of(
                        JufubaoShipmentGateway.OrderState.noDelivery("sub-1"),
                        JufubaoShipmentGateway.OrderState.noDelivery("sub-1"),
                        JufubaoShipmentGateway.OrderState.notPending("sub-1")),
                detail(1),
                List.of(),
                JufubaoShipmentGateway.SubmitResult.accepted("req-1"));
        JufubaoConnector connector = connector(gateway);
        SourceShipmentResult command = shipment("sub-1", "京东物流", "JDVA123");

        SourceSyncResult first = connector.pushShipmentResult(command);
        gateway.carriers = carriers();
        SourceSyncResult retried = connector.pushShipmentResult(command);

        assertThat(first.businessCode()).isEqualTo("JUFUBAO_CARRIER_UNMAPPED");
        assertThat(retried.success()).isTrue();
        assertThat(gateway.submitCount).isEqualTo(1);
    }

    @Test
    void doesNotSubmitAFailedFulfillmentOutcome() {
        FakeGateway gateway = successfulGateway();
        SourceShipmentResult failed = new SourceShipmentResult(
                SourceChannel.JUFUBAO,
                "main-1",
                "sub-1",
                BigDecimal.ONE,
                "FAILED",
                "京东物流",
                "JDVA123",
                "履约失败");

        SourceSyncResult result = connector(gateway).pushShipmentResult(failed);

        assertThat(result.businessCode()).isEqualTo("JUFUBAO_OUTCOME_NOT_SHIPPABLE");
        assertThat(gateway.stateReadCount).isZero();
        assertThat(gateway.submitCount).isZero();
    }

    private FakeGateway successfulGateway() {
        return new FakeGateway(
                List.of(
                        JufubaoShipmentGateway.OrderState.noDelivery("sub-1"),
                        JufubaoShipmentGateway.OrderState.notPending("sub-1")),
                detail(1),
                carriers(),
                JufubaoShipmentGateway.SubmitResult.accepted("req-1"));
    }

    private JufubaoConnector connector(JufubaoShipmentGateway gateway) {
        return new JufubaoConnector(
                mock(SourceImportService.class),
                new ReadyPullClient(),
                new JufubaoOrderTransform(),
                gateway,
                new InMemoryJufubaoShipmentAttemptStore());
    }

    private JufubaoShipmentGateway.ShipmentDetail detail(int allowedQuantity) {
        return new JufubaoShipmentGateway.ShipmentDetail(List.of(
                mapper.createObjectNode().put("product_id", "p-1").put("allow_send_num", allowedQuantity)));
    }

    private List<JufubaoShipmentGateway.CarrierOption> carriers() {
        return List.of(new JufubaoShipmentGateway.CarrierOption("京东物流", 17));
    }

    private SourceShipmentResult shipment(String subOrderId, String carrier, String trackingNo) {
        return new SourceShipmentResult(
                SourceChannel.JUFUBAO,
                "main-1",
                subOrderId,
                BigDecimal.ONE,
                "SHIPPED",
                carrier,
                trackingNo,
                null);
    }

    private static final class FakeGateway implements JufubaoShipmentGateway {
        private final List<OrderState> states;
        private final ShipmentDetail detail;
        private List<CarrierOption> carriers;
        private final SubmitResult submitResult;
        private int stateReadCount;
        private int submitCount;
        private List<ObjectNode> submittedProducts = List.of();

        private FakeGateway(
                List<OrderState> states,
                ShipmentDetail detail,
                List<CarrierOption> carriers,
                SubmitResult submitResult) {
            this.states = states;
            this.detail = detail;
            this.carriers = carriers;
            this.submitResult = submitResult;
        }

        @Override
        public OrderState findOrder(String subOrderId) {
            OrderState state = states.get(Math.min(stateReadCount, states.size() - 1));
            stateReadCount++;
            return state;
        }

        @Override
        public ShipmentDetail shipmentDetail(String subOrderId) {
            return detail;
        }

        @Override
        public List<CarrierOption> carrierOptions() {
            return carriers;
        }

        @Override
        public SubmitResult submit(ShipmentCommand command) {
            submitCount++;
            submittedProducts = command.products();
            return submitResult;
        }
    }

    private static final class ReadyPullClient implements JufubaoPullClient {
        @Override
        public LoginResult login() {
            return new LoginResult(true, "OK", "登录成功");
        }

        @Override
        public List<java.util.Map<String, Object>> pullOrders(long startEpoch, long endEpoch) {
            return List.of();
        }
    }
}
