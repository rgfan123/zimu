package cn.zimu.fulfillment.fulfillment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shipment 级京东出库请求的稳定、只读应用快照。
 *
 * <p>预览 HTTP、库存判定和真实建单都必须消费这一份 {@link #request()} 与
 * {@link #requestHash()}，不得另建 SKU/数量/地址映射逻辑。快照本身不执行京东写操作，
 * 也不产生审计记录；审计由具体用例在消费快照时记录。
 */
public record ShipmentJdOutboundPreviewSnapshot(
        long shipmentId,
        long shipmentVersion,
        long orderId,
        long providerId,
        String erpDeliveryNo,
        Map<String, Object> request,
        String requestHash,
        List<StockDemand> stockDemands,
        List<Validation> validations,
        List<Blocker> blockers,
        String manualCorrectionSource) {

    public ShipmentJdOutboundPreviewSnapshot {
        request = immutableMap(request);
        stockDemands = List.copyOf(stockDemands);
        validations = List.copyOf(validations);
        blockers = List.copyOf(blockers);
    }

    public boolean submittable() {
        return blockers.isEmpty();
    }

    public record Validation(String path, String status, String source, String message) {
    }

    public record Blocker(
            int httpStatus,
            String code,
            String path,
            String source,
            String correctionTarget,
            String message) {
    }

    /** Stock query input derived from the same cargo mapping as the outbound request. */
    public record StockDemand(long skuId, String goodsNo, int requiredPieces) {
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
