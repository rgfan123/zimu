package cn.zimu.fulfillment.fulfillment;

/** Shipment 物流事实的接受结果；重放或冲突都不产生新 Event/Version/Audit。 */
public record ShipmentTrackingAcceptance(boolean replayed, boolean conflicted, String trackingNumber) {

    public static ShipmentTrackingAcceptance accepted(String trackingNumber) {
        return new ShipmentTrackingAcceptance(false, false, trackingNumber);
    }

    public static ShipmentTrackingAcceptance replayed(String trackingNumber) {
        return new ShipmentTrackingAcceptance(true, false, trackingNumber);
    }

    public static ShipmentTrackingAcceptance conflict(String trackingNumber) {
        return new ShipmentTrackingAcceptance(false, true, trackingNumber);
    }
}
