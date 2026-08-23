package cn.zimu.fulfillment.fulfillment;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** HTTP 预览投影：保留现有响应契约，但不暴露内部计划中的京东凭据。 */
record ShipmentJdOutboundPreview(
        String shipmentId,
        long shipmentVersion,
        String erpDeliveryNo,
        String requestHash,
        boolean submittable,
        Map<String, Object> request,
        List<Map<String, Object>> validations,
        List<Map<String, Object>> blockers,
        String manualCorrectionSource) {

    static ShipmentJdOutboundPreview from(JdShipmentSubmissionPlan plan) {
        Map<String, Object> displayRequest = new LinkedHashMap<>(plan.request());
        if (displayRequest.containsKey("pin")) {
            displayRequest.put("pin", "***");
        }
        boolean needsAddressCorrection = plan.blockers().stream()
                .anyMatch(blocker -> "JD_SHIPMENT_OUTBOUND_RECEIVER_ADDRESS_NOT_CONFIRMED".equals(blocker.code()));
        return new ShipmentJdOutboundPreview(
                String.valueOf(plan.shipmentId()),
                plan.shipmentVersion(),
                plan.erpDeliveryNo(),
                plan.requestHash(),
                plan.submittable(),
                immutableMap(displayRequest),
                plan.validations().stream().map(ShipmentJdOutboundPreview::validationMap).toList(),
                plan.blockers().stream()
                        .map(ShipmentJdOutboundPreparer::blockerMap)
                        .map(ShipmentJdOutboundPreview::immutableMap)
                        .toList(),
                needsAddressCorrection && ShipmentJdOutboundPreparer.hasText(plan.manualCorrectionSource())
                        ? plan.manualCorrectionSource()
                        : null);
    }

    Map<String, Object> toResponse() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("shipment_id", shipmentId);
        response.put("shipment_version", shipmentVersion);
        response.put("erp_delivery_no", erpDeliveryNo);
        response.put("request_hash", requestHash);
        response.put("submittable", submittable);
        response.put("request", request);
        response.put("validations", validations);
        response.put("blockers", blockers);
        if (manualCorrectionSource != null) {
            response.put("manual_correction_source", manualCorrectionSource);
        }
        return Collections.unmodifiableMap(response);
    }

    private static Map<String, Object> validationMap(JdShipmentSubmissionPlan.Validation validation) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", validation.path());
        result.put("status", validation.status());
        result.put("source", validation.source());
        if (validation.message() != null) {
            result.put("message", validation.message());
        }
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, Object> immutableMap(Map<String, Object> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
