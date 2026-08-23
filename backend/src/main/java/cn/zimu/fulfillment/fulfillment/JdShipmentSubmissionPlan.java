package cn.zimu.fulfillment.fulfillment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shipment 级京东出库提交的稳定、只读内部计划。
 *
 * <p>预览投影、库存判定和真实建单都消费同一份 {@link #request()}、
 * {@link #stockDemands()}、{@link #blockers()} 与 {@link #requestHash()}，不得各自重建
 * SKU、数量或地址映射。计划只包含确定性读取与计算结果，不持有操作人、幂等键、审计、
 * 京东网络调用或提交结果持久化职责。
 */
record JdShipmentSubmissionPlan(
        long shipmentId,
        long shipmentVersion,
        long orderId,
        long providerId,
        String providerType,
        String erpDeliveryNo,
        PriorSubmission priorSubmission,
        List<OrderLineState> orderLines,
        Map<String, Object> request,
        String requestHash,
        List<StockDemand> stockDemands,
        List<Validation> validations,
        List<Blocker> blockers,
        String manualCorrectionSource) {

    JdShipmentSubmissionPlan {
        request = immutableMap(request);
        orderLines = List.copyOf(orderLines);
        stockDemands = List.copyOf(stockDemands);
        validations = List.copyOf(validations);
        blockers = List.copyOf(blockers);
    }

    boolean submittable() {
        return blockers.isEmpty();
    }

    record Validation(String path, String status, String source, String message) {
    }

    record Blocker(
            int httpStatus,
            String code,
            String path,
            String source,
            String correctionTarget,
            String message) {
    }

    /** 京东库存查询输入，与出库请求的货品展开和件数换算同源。 */
    record StockDemand(long skuId, String goodsNo, int requiredPieces) {
    }

    record PriorSubmission(
            String syncStatus,
            String requestHash,
            int retryCount,
            String lastErrorCode,
            String clientMode) {

        boolean requiresReconciliation() {
            return ShipmentJdOutboundPreparer.SYNC_STATUS_SUBMITTING.equals(syncStatus)
                    || (ShipmentJdOutboundPreparer.SYNC_STATUS_SYNC_FAILED.equals(syncStatus)
                            && ShipmentJdOutboundPreparer.UNCERTAIN_EXTERNAL_RESULTS.contains(lastErrorCode));
        }
    }

    record OrderLineState(long orderLineId, String processingStage) {
    }

    private static Map<String, Object> immutableMap(Map<String, Object> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, immutableValue(value)));
        return Collections.unmodifiableMap(copy);
    }

    private static Object immutableValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((key, nested) -> copy.put(String.valueOf(key), immutableValue(nested)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list.size());
            list.forEach(item -> copy.add(immutableValue(item)));
            return Collections.unmodifiableList(copy);
        }
        return value;
    }
}
