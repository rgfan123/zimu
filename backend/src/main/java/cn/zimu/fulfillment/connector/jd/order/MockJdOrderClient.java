package cn.zimu.fulfillment.connector.jd.order;

import cn.zimu.fulfillment.connector.jd.JdResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 默认可重复的本地客户端。Mock 和真实 LOP 客户端共用同一应用边界，避免 Demo
 * 环境意外调用付费或不可逆的仓配接口。
 *
 * <p>输出键与 REAL 客户端一致使用 camelCase，保证业务解析器对两种模式共用同一断言。
 */
@Service
@ConditionalOnProperty(name = "app.jd.client-mode", havingValue = "MOCK", matchIfMissing = true)
public class MockJdOrderClient implements JdOrderService {

    @Override
    public JdResult queryOrderNosByPage(Map<String, Object> request) {
        return success("queryOrderNosByPage", request, Map.of(
                "totalNum", 2,
                "resultList", List.of(
                        Map.of(
                                "orderNo", "MOCK-SO-1001",
                                "erpOrderNo", value(request, "erpOrderNo", "ZM202608120001")),
                        Map.of(
                                "orderNo", "MOCK-SO-1002",
                                "erpOrderNo", "ZM202608120002"))));
    }

    @Override
    public JdResult queryAdjustment(Map<String, Object> request) {
        return success("queryAdjustment", request, Map.of(
                "adjustmentNo", "MOCK-ADJ-001",
                "erpAdjustmentNo", value(request, "erpAdjustmentNo", "ZMADJ202608130001"),
                "status", "CONFIRMED",
                "bizType", 1));
    }

    @Override
    public JdResult queryDestroy(Map<String, Object> request) {
        return success("queryDestroy", request, Map.of(
                "destroyNo", "MOCK-UL-001",
                "erpDestroyNo", value(request, "erpDestroyNo", "ZMUL202608130001"),
                "status", "DESTROYED"));
    }

    @Override
    public JdResult queryException(Map<String, Object> request) {
        return success("queryException", request, Map.of(
                "totalNum", 1,
                "resultList", List.of(Map.of(
                        "orderNo", "MOCK-EXC-001",
                        "exceptionCode", "LOST",
                        "status", "OPEN"))));
    }

    @Override
    public JdResult queryPurchase(Map<String, Object> request) {
        return success("queryPurchase", request, Map.of(
                "purchaseNo", "MOCK-PO-001",
                "erpPurchaseNo", value(request, "erpPurchaseNo", "ZMPO202608130001"),
                "status", "RECEIVED"));
    }

    @Override
    public JdResult queryProcessed(Map<String, Object> request) {
        return success("queryProcessed", request, Map.of(
                "processedNo", "MOCK-PR-001",
                "erpProcessedNo", value(request, "erpProcessedNo", "ZMPR202608130001"),
                "status", "FINISHED"));
    }

    @Override
    public JdResult queryOperateRelation(Map<String, Object> request) {
        return success("queryOperateRelation", request, Map.of(
                "eclpNo", "MOCK-ECLP-001",
                "erpOrderNo", value(request, "erpOrderNo", "ZM202608120001"),
                "orderType", "SO"));
    }

    @Override
    public JdResult queryDeliveryTime(Map<String, Object> request) {
        return success("queryDeliveryTime", request, Map.of(
                "waybillNo", value(request, "waybillNo", "MOCK-WAYBILL-001"),
                "promiseTime", "2026-08-14 18:00:00"));
    }

    @Override
    public JdResult queryCityTrack(Map<String, Object> request) {
        return success("queryCityTrack", request, Map.of(
                "deliveryNo", value(request, "deliveryNo", "MOCK-DELIVERY-001"),
                "cityTrack", List.of(Map.of(
                        "city", "北京",
                        "status", "IN_TRANSIT"))));
    }

    private JdResult success(String operation, Map<String, Object> request, Object data) {
        Map<String, Object> stableData = new LinkedHashMap<>();
        stableData.put("operation", operation);
        stableData.put("request", request == null ? Map.of() : Map.copyOf(request));
        stableData.put("response", data);
        return new JdResult(true, "MOCK_SUCCESS", "mock client completed", "mock-" + operation, stableData);
    }

    private Object value(Map<String, Object> request, String key, Object fallback) {
        if (request == null) {
            return fallback;
        }
        Object raw = request.get(key);
        if (raw == null) {
            raw = request.get(snakeKey(key));
        }
        return raw == null ? fallback : raw;
    }

    private String snakeKey(String camelKey) {
        return camelKey.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(java.util.Locale.ROOT);
    }
}
