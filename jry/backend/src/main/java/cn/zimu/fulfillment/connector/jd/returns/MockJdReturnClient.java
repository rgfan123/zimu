package cn.zimu.fulfillment.connector.jd.returns;

import cn.zimu.fulfillment.connector.jd.JdResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 默认可重复的本地客户端。Mock 和真实 LOP 客户端共用同一应用边界，避免 Demo
 * 环境意外调用付费或不可逆的仓配接口。mock 含假 PII（寄件 / 收件人），由
 * 控制器在 HTTP 边界脱敏，用于演示与测试脱敏链路。
 *
 * <p>输出键与 REAL 客户端一致使用 camelCase，保证业务解析器对两种模式共用同一断言。
 */
@Service
@ConditionalOnProperty(name = "app.jd.client-mode", havingValue = "MOCK", matchIfMissing = true)
public class MockJdReturnClient implements JDReturnService {

    @Override
    public JdResult queryRtwOrderList(Map<String, Object> request) {
        return success("queryRtwOrderList", request, List.of(rtwOrder(request)));
    }

    @Override
    public JdResult queryRtwOrderDetail(Map<String, Object> request) {
        Map<String, Object> order = rtwOrder(request);
        order.put("updateTime", "2026-08-13 10:05:00");
        order.put("billingMode", "月结");
        return success("queryRtwOrderDetail", request, order);
    }

    @Override
    public JdResult queryReturnToSupplier(Map<String, Object> request) {
        Map<String, Object> rts = new LinkedHashMap<>();
        rts.put("returnToSupplierNo", "MOCK-RTS-001");
        rts.put("erpReturnToSupplierNo", value(request, "erpReturnToSupplierNo", "MOCK-ERP-RTS-001"));
        rts.put("ownerNo", "MOCK-OWNER-001");
        rts.put("warehouseNo", "MOCK-WH-001");
        rts.put("deliveryMode", "自提");
        rts.put("supplierNo", "MOCK-SUP-001");
        rts.put("status", "RECEIVED");
        rts.put("operatorTime", "2026-08-13 10:00:00");
        rts.put("operatorUser", "mock-operator");
        rts.put("remark", "mock 退供单");
        rts.put("receiverInfo", Map.of(
                "name", "Mock 收件人",
                "phone", "13800000000",
                "detailAddress", "北京市测试地址 1 号"));
        rts.put("returnToSupplierDetailList", List.of(detailLine()));
        return success("queryReturnToSupplier", request, rts);
    }

    private Map<String, Object> rtwOrder(Map<String, Object> request) {
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("returnToWarehouseNo", "MOCK-RTW-001");
        order.put("erpReturnToWarehouseNo", value(request, "erpReturnToWarehouseNo", "MOCK-ERP-RTW-001"));
        order.put("deliveryNo", "MOCK-DLV-001");
        order.put("ownerNo", "MOCK-OWNER-001");
        order.put("warehouseNo", "MOCK-WH-001");
        order.put("source", "ERP");
        order.put("returnReason", "质检不合格");
        order.put("status", "RECEIVED");
        order.put("createTime", "2026-08-13 10:00:00");
        order.put("senderInfo", Map.of(
                "name", "Mock 寄件人",
                "mobile", "13800000000",
                "phone", "010-00000000"));
        order.put("returnToWarehouseDetailsList", List.of(detailLine()));
        return order;
    }

    private Map<String, Object> detailLine() {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("goodsNo", "MOCK-GOODS-001");
        line.put("goodsName", "Mock 商品");
        line.put("planQuantity", 10);
        line.put("realQuantity", 10);
        return line;
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
        if (request.containsKey(key)) {
            return request.get(key);
        }
        return request.getOrDefault(camelCase(key), fallback);
    }

    private static String camelCase(String snake) {
        StringBuilder builder = new StringBuilder();
        boolean upper = false;
        for (int i = 0; i < snake.length(); i++) {
            char c = snake.charAt(i);
            if (c == '_') {
                upper = true;
            } else if (upper) {
                builder.append(Character.toUpperCase(c));
                upper = false;
            } else {
                builder.append(c);
            }
        }
        return builder.toString();
    }
}
