package cn.zimu.fulfillment.connector.caishixian;

import cn.zimu.fulfillment.connector.SourceShipmentArtifact;
import cn.zimu.fulfillment.connector.ExternalWritePermit;
import java.util.List;

/** 彩食鲜 Shipment 发货的真实外部端口。 */
public interface CaishixianShipmentGateway {

    PlatformOrderSnapshot inspect(String sourceRef, String sourceLineRef);

    List<CarrierOption> carrierOptions();

    UploadAck upload(SourceShipmentArtifact artifact, ExternalWritePermit permit);

    Verification awaitVerified(String platformOrderId, String carrierCode, String trackingNumber);

    record PlatformOrderSnapshot(
            boolean present,
            String platformOrderId,
            String orderCode,
            String orderKey,
            int orderStatus,
            String orderStatusName,
            String receiverName,
            String receiverPhone,
            String receiverAddress,
            Long sendableQuantity) {}

    record CarrierOption(String code, String name) {}

    record UploadAck(Outcome outcome, String platformCode, String safeMessage) {
        public enum Outcome {
            ACCEPTED_PENDING_VERIFICATION,
            REJECTED,
            UNKNOWN
        }

        public static UploadAck accepted(String code) {
            return new UploadAck(Outcome.ACCEPTED_PENDING_VERIFICATION, code, "平台已受理，等待终态核验");
        }

        public static UploadAck rejected(String code, String message) {
            return new UploadAck(Outcome.REJECTED, code, message);
        }

        public static UploadAck unknown(String message) {
            return new UploadAck(Outcome.UNKNOWN, null, message);
        }
    }

    record Verification(boolean verified, String platformOrderId, int orderStatus, String safeMessage) {
        public static Verification verified(String platformOrderId) {
            return new Verification(true, platformOrderId, 4, "状态与正式运单均已核验");
        }

        public static Verification notVerified(String platformOrderId, int status, String message) {
            return new Verification(false, platformOrderId, status, message);
        }
    }
}
