package cn.zimu.fulfillment.connector.jd.stock;

import cn.zimu.fulfillment.connector.jd.JdResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 默认可重复的本地库存查询客户端。Mock 和真实 LOP 客户端共用同一应用边界，
 * 避免 Demo 环境意外调用付费或触发风控的库存接口。
 *
 * <p>输出键与 REAL 客户端一致使用 camelCase，保证业务解析器对两种模式共用同一断言。
 */
@Service
@ConditionalOnProperty(name = "app.jd.client-mode", havingValue = "MOCK", matchIfMissing = true)
public class MockJdStockClient implements JDStockService {

    @Override
    public JdResult queryStockSnapshot(Map<String, Object> request) {
        return success("queryStockSnapshot", request, Map.of(
                "total", 1,
                "snapshotStatus", 1,
                "cursor", "mock-cursor-001",
                "warehouseStockSnapshotList", List.of(Map.of(
                        "warehouseNo", "MOCK-WH-001",
                        "goodsNo", value(request, "goodsNo", "MOCK-SKU-001"),
                        "goodsLevel", "1",
                        "stockType", "1",
                        "availableQuantity", 100,
                        "onWayQuantity", 20,
                        "occupiedQuantity", 5))));
    }

    @Override
    public JdResult queryStockSummary(Map<String, Object> request) {
        return success("queryStockSummary", request, Map.of(
                "warehouseStockList", List.of(Map.of(
                        "warehouseNo", "MOCK-WH-001",
                        "goodsNo", value(request, "goodsNo", "MOCK-SKU-001"),
                        "goodsName", "Mock 商品",
                        "availableQuantity", 100,
                        "totalQuantity", 125))));
    }

    @Override
    public JdResult queryBatchChange(Map<String, Object> request) {
        return success("queryBatchChange", request, Map.of(
                "totalNum", 1,
                "resultList", List.of(Map.of(
                        "batchChangeNo", "MOCK-BC-001",
                        "warehouseNo", value(request, "warehouseNo", "MOCK-WH-001"),
                        "goodsNo", value(request, "goodsNo", "MOCK-SKU-001"),
                        "changeNum", -2,
                        "changedLot", "LOT-2026",
                        "changeTime", "2026-08-13 10:00:00"))));
    }

    @Override
    public JdResult queryGoodsLevelChange(Map<String, Object> request) {
        return success("queryGoodsLevelChange", request, Map.of(
                "totalNum", 1,
                "resultList", List.of(Map.of(
                        "orderNo", "MOCK-LC-001",
                        "warehouseNo", value(request, "warehouseNo", "MOCK-WH-001"),
                        "goodsNo", value(request, "goodsNo", "MOCK-SKU-001"),
                        "preChangeLevel", "1",
                        "changedLevel", "2",
                        "changeTime", "2026-08-13 10:00:00"))));
    }

    @Override
    public JdResult queryShelfLifeGoods(Map<String, Object> request) {
        return success("queryShelfLifeGoods", request, Map.of(
                "totalNum", 1,
                "resultList", List.of(Map.of(
                        "checkOrderNo", value(request, "checkOrderNo", "MOCK-CK-001"),
                        "warehouseNo", "MOCK-WH-001",
                        "createTime", "2026-08-13 10:00:00",
                        "ownerNo", "MOCK-OWNER-001"))));
    }

    @Override
    public JdResult queryShelfLifeInventory(Map<String, Object> request) {
        return success("queryShelfLifeInventory", request, Map.of(
                "totalNum", 1,
                "resultList", List.of(Map.of(
                        "warehouseNo", value(request, "warehouseNo", "MOCK-WH-001"),
                        "goodsNo", value(request, "goodsNo", "MOCK-SKU-001"),
                        "goodsName", "Mock 商品",
                        "lotNo", "LOT-2026",
                        "productDate", "2026-01-01",
                        "expireDate", "2026-12-31",
                        "availableQuantity", 80,
                        "status", 1))));
    }

    @Override
    public JdResult searchShopStockFlow(Map<String, Object> request) {
        return success("searchShopStockFlow", request, Map.of(
                "totalNum", 1,
                "resultList", List.of(Map.of(
                        "shopNo", value(request, "shopNo", "MOCK-SHOP-001"),
                        "warehouseNo", value(request, "warehouseNo", "MOCK-WH-001"),
                        "goodsNo", value(request, "goodsNo", "MOCK-SKU-001"),
                        "bizNo", "MOCK-BIZ-001",
                        "bizType", "OUTBOUND",
                        "stockNum", 100,
                        "stockChangeNum", -2,
                        "createTime", "2026-08-13 10:00:00"))));
    }

    private JdResult success(String operation, Map<String, Object> request, Object data) {
        Map<String, Object> stableData = new LinkedHashMap<>();
        stableData.put("operation", operation);
        stableData.put("request", request == null ? Map.of() : Map.copyOf(request));
        stableData.put("response", data);
        return new JdResult(true, "MOCK_SUCCESS", "mock client completed", "mock-" + operation, stableData);
    }

    /** 取用户参数值用于稳定回显：兼容控制器传入的 camelCase 键、列表键与 snake_case 键。 */
    private Object value(Map<String, Object> request, String camelKey, Object fallback) {
        if (request == null) {
            return fallback;
        }
        Object raw = request.get(camelKey);
        if (raw == null) {
            raw = request.get(camelKey + "List");
        }
        if (raw == null) {
            raw = request.get(snakeKey(camelKey));
        }
        if (raw instanceof List<?> values && !values.isEmpty()) {
            return values.getFirst();
        }
        return raw == null ? fallback : raw;
    }

    private String snakeKey(String camelKey) {
        return camelKey.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }
}
