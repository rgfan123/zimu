package cn.zimu.fulfillment.connector.jd.serial;

import cn.zimu.fulfillment.connector.jd.JdResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 默认可重复的本地序列号查询客户端。Mock 和真实 LOP 客户端共用同一应用边界，
 * 避免 Demo 环境意外调用只读接口之外的成本或依赖真实授权。
 */
@Service
@ConditionalOnProperty(name = "app.jd.client-mode", havingValue = "MOCK", matchIfMissing = true)
public class MockJdSerialClient implements JdSerialService {

    @Override
    public JdResult queryJdMallSerial(Map<String, Object> request) {
        return success("queryJdMallSerial", request, Map.of(
                "totalNum", 2,
                "resultList", List.of(
                        Map.of(
                                "sku", "MOCK-SKU-001",
                                "sn", "MOCK-SN-0001",
                                "ownerNo", "MOCK-OWNER-001",
                                "ownerName", "默认事业部",
                                "orderNo", value(request, "orderNo", "MOCK-ORDER-001"),
                                "operateTime", "2026-08-13 10:00:00",
                                "state", "IN_STOCK",
                                "packageNumber", "MOCK-PKG-001",
                                "enterpriseOrderNo", "MOCK-ENT-001"),
                        Map.of(
                                "sku", "MOCK-SKU-002",
                                "sn", "MOCK-SN-0002",
                                "ownerNo", "MOCK-OWNER-001",
                                "ownerName", "默认事业部",
                                "orderNo", value(request, "orderNo", "MOCK-ORDER-001"),
                                "operateTime", "2026-08-13 11:00:00",
                                "state", "SHIPPED",
                                "packageNumber", "MOCK-PKG-002",
                                "enterpriseOrderNo", "MOCK-ENT-001"))));
    }

    @Override
    public JdResult querySerialByCondition(Map<String, Object> request) {
        return success("querySerialByCondition", request, Map.of(
                "totalNum", 1,
                "resultList", List.of(Map.of(
                        "orderNo", value(request, "orderNo", "MOCK-ORDER-001"),
                        "goodsNo", value(request, "goodsNo", "MOCK-SKU-001"),
                        "serial", "MOCK-SN-0001",
                        "bizType", 10,
                        "bizTypeName", "出库",
                        "warehouseNo", "MOCK-WH-001",
                        "warehouseName", "默认仓",
                        "createTime", "2026-08-12 09:30:00"))));
    }

    @Override
    public JdResult querySerialFlow(Map<String, Object> request) {
        return success("querySerialFlow", request, Map.ofEntries(
                Map.entry("goodsNo", value(request, "goodsNo", "MOCK-SKU-001")),
                Map.entry("serial", value(request, "serialNo", "MOCK-SN-0001")),
                Map.entry("outOrderNo", "MOCK-SO-001"),
                Map.entry("outWarehouseNo", "MOCK-WH-001"),
                Map.entry("outWarehouseName", "默认仓"),
                Map.entry("outOrderType", "SO"),
                Map.entry("outTime", "2026-08-13 10:00:00"),
                Map.entry("intoOrderNo", "MOCK-IN-001"),
                Map.entry("intoWarehouseNo", "MOCK-WH-001"),
                Map.entry("intoWarehouseName", "默认仓"),
                Map.entry("inOrderType", "ASN"),
                Map.entry("intoTime", "2026-08-10 14:00:00"),
                Map.entry("status", "OUT")));
    }

    @Override
    public JdResult querySerialInside(Map<String, Object> request) {
        return success("querySerialInside", request, Map.of(
                "totalNum", 2,
                "currentPage", value(request, "currentPage", 1),
                "pageSize", value(request, "pageSize", 20),
                "serialNos", List.of("MOCK-SN-0001", "MOCK-SN-0002")));
    }

    private JdResult success(String operation, Map<String, Object> request, Object data) {
        Map<String, Object> stableData = new LinkedHashMap<>();
        stableData.put("operation", operation);
        stableData.put("request", request == null ? Map.of() : Map.copyOf(request));
        stableData.put("response", data);
        return new JdResult(true, "MOCK_SUCCESS", "mock client completed", "mock-" + operation, stableData);
    }

    private Object value(Map<String, Object> request, String key, Object fallback) {
        return request == null ? fallback : request.getOrDefault(key, fallback);
    }
}
