package cn.zimu.fulfillment.connector.jd;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 出库单接口的审计投影：querySoOrder / addSoOrder 的请求与响应可含收件人、pin 账号与
 * 自由文本备注，审计里只保留定长业务引用与计数，绝不落原始载荷。
 *
 * <p>收编传输内核（票 03）前这段策略内联在 {@code JdWarehouseClient}，是七个客户端中
 * 唯一做载荷收敛的一个；抽成独立单元后语义逐位不变，并由
 * {@code JdWarehouseClientRequestMappingTest} 的白名单断言守门。
 */
final class JdWarehouseAuditProjection implements JdAuditProjection {

    static final JdWarehouseAuditProjection INSTANCE = new JdWarehouseAuditProjection();

    private static final String QUERY_OUTBOUND_ORDER = "queryOutboundOrder";

    private JdWarehouseAuditProjection() {}

    @Override
    public Object request(String operation, Map<String, Object> command) {
        Map<String, Object> values = command == null ? Map.of() : command;
        Map<String, Object> summary = new LinkedHashMap<>();
        if (!QUERY_OUTBOUND_ORDER.equals(operation)) {
            summary.put("owner_no", boundedText(values.get("ownerNo"), 64));
            summary.put("warehouse_no", boundedText(values.get("warehouseNo"), 128));
            summary.put("erp_delivery_no", boundedText(values.get("erpDeliveryNo"), 64));
            summary.put("delivery_no", boundedText(values.get("deliveryNo"), 64));
            summary.put("item_count", firstCollectionSize(
                    values.get("cargoInfos"), values.get("deliveryItemList"), values.get("goodsList")));
            summary.put("field_count", Math.min(values.size(), 256));
            return summary;
        }
        summary.put("erp_delivery_no", boundedText(values.get("erpDeliveryNo"), 64));
        summary.put("delivery_no", boundedText(values.get("deliveryNo"), 64));
        summary.put("sales_platform_delivery_no", boundedText(values.get("salesPlatformDeliveryNo"), 64));
        summary.put("delivery_item_flag", exactFlag(values.get("deliveryItemFlag")));
        summary.put("delivery_status_flag", exactFlag(values.get("deliveryStatusFlag")));
        return summary;
    }

    /** querySoOrder 响应可含收件人、账号与自由文本；审计只保留定长引用和计数。 */
    @Override
    public Object response(String operation, JdResult result) {
        if (!QUERY_OUTBOUND_ORDER.equals(operation)) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("success", result.success());
            summary.put("business_code", boundedText(result.businessCode(), 64));
            summary.put("request_id", boundedText(result.requestId(), 128));
            summary.put("data_item_count", collectionSize(result.data()));
            summary.put("data_field_count", result.data() instanceof Map<?, ?> values
                    ? Math.min(values.size(), 256)
                    : 0);
            return summary;
        }
        Map<?, ?> data = result.data() instanceof Map<?, ?> values ? values : Map.of();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("success", result.success());
        summary.put("business_code", boundedText(result.businessCode(), 64));
        summary.put("request_id", boundedText(result.requestId(), 128));
        summary.put("erp_delivery_no", boundedText(data.get("erpDeliveryNo"), 64));
        summary.put("delivery_no", boundedText(data.get("deliveryNo"), 64));
        summary.put("warehouse_no", boundedText(data.get("warehouseNo"), 128));
        summary.put("status", boundedText(data.get("status"), 32));
        summary.put("delivery_item_count", collectionSize(data.get("deliveryItemList")));
        summary.put("delivery_status_count", collectionSize(data.get("deliveryStatusList")));
        summary.put("split_delivery_count", splitCount(data.get("splitDeliveryNos")));
        return summary;
    }

    @Override
    public String requestId(String value) {
        return boundedText(value, 128);
    }

    @Override
    public String businessCode(String value) {
        return boundedText(value, 64);
    }

    private Integer exactFlag(Object value) {
        if (value instanceof Number number) {
            int flag = number.intValue();
            return flag == 0 || flag == 1 ? flag : null;
        }
        return null;
    }

    private int collectionSize(Object value) {
        return value instanceof Collection<?> collection ? collection.size() : 0;
    }

    private int firstCollectionSize(Object... values) {
        for (Object value : values) {
            if (value instanceof Collection<?> collection) {
                return Math.min(collection.size(), 10_000);
            }
        }
        return 0;
    }

    private int splitCount(Object value) {
        String splitNos = boundedText(value, 4096);
        if (splitNos == null) {
            return 0;
        }
        int count = 0;
        for (String token : splitNos.split(",", -1)) {
            if (!token.isBlank()) {
                count++;
            }
        }
        return count;
    }

    private String boundedText(Object value, int maxLength) {
        if (!(value instanceof String raw)) {
            return null;
        }
        String result = raw.trim();
        if (result.isEmpty()
                || result.length() > maxLength
                || result.codePoints().anyMatch(Character::isISOControl)) {
            return null;
        }
        return result;
    }
}
