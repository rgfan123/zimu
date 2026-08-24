package cn.zimu.fulfillment.connector.jufubao;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/** 聚福宝单订单发货的外部 HTTP seam。 */
public interface JufubaoShipmentGateway {

    /** 在登记外部写意图前完成鉴权等可安全重试的前置检查。 */
    default void prepareWrite() {}

    OrderState findOrder(String subOrderId);

    ShipmentDetail shipmentDetail(String subOrderId);

    List<CarrierOption> carrierOptions();

    SubmitResult submit(ShipmentCommand command);

    record OrderState(String subOrderId, String status, boolean presentInNoDelivery) {
        public static OrderState noDelivery(String subOrderId) {
            return new OrderState(subOrderId, "NO_DELIVERY", true);
        }

        public static OrderState notPending(String subOrderId) {
            return new OrderState(subOrderId, "NOT_PENDING", false);
        }
    }

    record ShipmentDetail(List<ObjectNode> products) {
        public ShipmentDetail {
            products = products == null ? List.of() : List.copyOf(products);
        }
    }

    record CarrierOption(String label, int value) {}

    record ShipmentCommand(
            String subOrderId,
            List<ObjectNode> products,
            int companyId,
            String trackingNo) {
        public ShipmentCommand {
            products = List.copyOf(products);
        }
    }

    record SubmitResult(Outcome outcome, String businessCode, String message, String platformRef) {
        public enum Outcome {
            ACCEPTED,
            REJECTED,
            UNKNOWN
        }

        public static SubmitResult accepted(String platformRef) {
            return new SubmitResult(Outcome.ACCEPTED, "OK", "accepted", platformRef);
        }

        public static SubmitResult rejected(String businessCode, String message, String platformRef) {
            return new SubmitResult(Outcome.REJECTED, businessCode, message, platformRef);
        }

        public static SubmitResult unknown(String message, String platformRef) {
            return new SubmitResult(Outcome.UNKNOWN, "RECONCILIATION_REQUIRED", message, platformRef);
        }
    }
}
