package cn.zimu.fulfillment.connector.jufubao;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;

/** 聚福宝单订单发货的外部 HTTP seam。 */
public interface JufubaoShipmentGateway {

    int DEFAULT_POLL_ATTEMPTS = 5;

    /** 在登记外部写意图前完成鉴权等可安全重试的前置检查。 */
    default void prepareWrite() {}

    OrderState findOrder(String subOrderId);

    default OrderState awaitNoDelivery(String subOrderId) {
        OrderState latest = findOrder(subOrderId);
        for (int attempt = 1;
                attempt < DEFAULT_POLL_ATTEMPTS && !"NO_DELIVERY".equals(latest.status());
                attempt++) {
            latest = findOrder(subOrderId);
        }
        return latest;
    }

    default OrderState awaitNotPending(String subOrderId) {
        OrderState latest = findOrder(subOrderId);
        for (int attempt = 1;
                attempt < DEFAULT_POLL_ATTEMPTS && latest.presentInNoDelivery();
                attempt++) {
            latest = findOrder(subOrderId);
        }
        return latest;
    }

    default ReceiveResult receiveOrder(String subOrderId) {
        return ReceiveResult.unknown("聚福宝接单能力未接入", null);
    }

    default AddressCheck checkShipmentAddress(String subOrderId) {
        return AddressCheck.unknown("聚福宝地址检查能力未接入");
    }

    ShipmentDetail shipmentDetail(String subOrderId);

    List<CarrierOption> carrierOptions();

    SubmitResult submit(ShipmentCommand command);

    record OrderState(String subOrderId, String status, boolean presentInNoDelivery) {
        public static OrderState noReceipt(String subOrderId) {
            return new OrderState(subOrderId, "NO_RECEIPT", true);
        }

        public static OrderState noDelivery(String subOrderId) {
            return new OrderState(subOrderId, "NO_DELIVERY", true);
        }

        public static OrderState notPending(String subOrderId) {
            return new OrderState(subOrderId, "NOT_PENDING", false);
        }
    }

    record ShipmentDetail(List<ObjectNode> products, ReceiverSnapshot receiver, String deliveryMethod) {
        public ShipmentDetail {
            products = products == null ? List.of() : List.copyOf(products);
        }

        public ShipmentDetail(List<ObjectNode> products) {
            this(products, null, null);
        }
    }

    record ReceiverSnapshot(String name, String phone, String address) {}

    record CarrierOption(String label, int value) {}

    record AddressCheck(boolean known, boolean confirmationRequired, String message) {
        public static AddressCheck clear() {
            return new AddressCheck(true, false, "");
        }

        public static AddressCheck confirmationRequired(String message) {
            return new AddressCheck(true, true, message == null ? "" : message);
        }

        public static AddressCheck unknown(String message) {
            return new AddressCheck(false, false, message == null ? "" : message);
        }
    }

    record ReceiveResult(Outcome outcome, String businessCode, String message, String platformRef) {
        public enum Outcome {
            ACCEPTED,
            REJECTED,
            UNKNOWN
        }

        public static ReceiveResult accepted(String platformRef) {
            return new ReceiveResult(Outcome.ACCEPTED, "OK", "accepted", platformRef);
        }

        public static ReceiveResult rejected(String businessCode, String message, String platformRef) {
            return new ReceiveResult(Outcome.REJECTED, businessCode, message, platformRef);
        }

        public static ReceiveResult unknown(String message, String platformRef) {
            return new ReceiveResult(Outcome.UNKNOWN, "RECONCILIATION_REQUIRED", message, platformRef);
        }
    }

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
