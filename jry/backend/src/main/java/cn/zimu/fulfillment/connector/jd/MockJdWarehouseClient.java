package cn.zimu.fulfillment.connector.jd;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 默认可重复的本地客户端。Mock 和真实 LOP 客户端共用同一应用边界，避免 Demo
 * 环境意外调用付费或不可逆的仓配接口。
 */
@Service
@ConditionalOnProperty(name = "app.jd.client-mode", havingValue = "MOCK", matchIfMissing = true)
public class MockJdWarehouseClient implements JDWarehouseService {

    @Override
    public JdResult queryOwners(Map<String, Object> request) {
        return success("queryOwners", request, Map.of(
                "owners", java.util.List.of(Map.of(
                        "owner_no", "MOCK-OWNER-001",
                        "owner_name", "默认事业部"))));
    }

    @Override
    public JdResult queryWarehouses(Map<String, Object> request) {
        return success("queryWarehouses", request, Map.of(
                "warehouses", java.util.List.of(Map.of(
                        "warehouse_no", "MOCK-WH-001",
                        "warehouse_name", "默认仓"))));
    }

    @Override
    public JdResult queryProducts(Map<String, Object> request) {
        return success("queryProducts", request, Map.of(
                "products", java.util.List.of(Map.of(
                        "goods_no", value(request, "goods_no", "MOCK-SKU-001"),
                        "goods_name", "Mock 商品"))));
    }

    @Override
    public JdResult queryStock(Map<String, Object> request) {
        String warehouse = String.valueOf(value(request, "warehouseNo", "MOCK-WH-001"));
        String goods = String.valueOf(value(request, "goodsNo", "MOCK-SKU-001"));
        return new JdResult(true, "MOCK_SUCCESS", "mock client completed", "mock-queryStock", Map.of(
                "resultList", java.util.Arrays.stream(goods.split(","))
                        .map(String::trim)
                        .filter(value -> !value.isEmpty())
                        .map(goodsNo -> Map.<String, Object>of(
                                "goodsNo", goodsNo,
                                "warehouseNo", warehouse,
                                "goodsLevel", "100",
                                "stockStatus", "1",
                                "stockType", "1",
                                "stockNum", 100,
                                "usableNum", 100))
                        .toList(),
                // 兼容旧管理查询页面的摘要字段；业务门禁只消费上面的严格 resultList。
                "warehouseNo", warehouse,
                "goodsNo", goods,
                "availableQuantity", 100));
    }

    @Override
    public JdResult createOutboundOrder(Map<String, Object> request) {
        return success("createOutboundOrder", request, Map.of(
                "warehouse_order_no", "MOCK-SO-001",
                "status", "CREATED"));
    }

    @Override
    public JdResult queryOutboundOrder(Map<String, Object> request) {
        Object erpDeliveryNo = value(request, "erpDeliveryNo", "MOCK-ERP-DELIVERY-001");
        Object warehouseNo = value(request, "warehouseNo", "MOCK-WH-001");
        // 默认 Mock 表示已建单但未出库，不凭空伪造实发量或运单。
        // Ticket 06 的 full/partial/conflict/failure 由同一 JDWarehouseService 可控测试 seam 提供。
        return success("queryOutboundOrder", request, Map.of(
                "warehouse_order_no", value(request, "warehouse_order_no", "MOCK-SO-001"),
                "erpDeliveryNo", erpDeliveryNo,
                "warehouseNo", warehouseNo,
                "deliveryNo", "MOCK-DELIVERY-001",
                "status", "10010",
                "isSplit", "0",
                "deliveryItemList", java.util.List.of()));
    }

    @Override
    public JdResult cancelOutboundOrder(Map<String, Object> request) {
        return success("cancelOutboundOrder", request, Map.of(
                "warehouse_order_no", value(request, "warehouse_order_no", "MOCK-SO-001"),
                "status", "CANCELLED"));
    }

    @Override
    public JdResult queryTracking(Map<String, Object> request) {
        return success("queryTracking", request, Map.of(
                "warehouse_order_no", value(request, "warehouse_order_no", "MOCK-SO-001"),
                "tracking_no", "MOCK-TRACK-001",
                "status", "SHIPPED"));
    }

    private JdResult success(String operation, Map<String, Object> request, Object data) {
        Map<String, Object> stableData = new LinkedHashMap<>();
        stableData.put("operation", operation);
        stableData.put("request", request == null ? Map.of() : Map.copyOf(request));
        stableData.put("response", data);
        return new JdResult(true, "MOCK_SUCCESS", "mock client completed", "mock-" + operation, stableData);
    }

    private Object value(Map<String, Object> request, String key, Object fallback) {
        if (request == null) return fallback;
        Object value = request.get(key);
        if (value == null) {
            value = request.get(key.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(java.util.Locale.ROOT));
        }
        return value == null ? fallback : value;
    }
}
