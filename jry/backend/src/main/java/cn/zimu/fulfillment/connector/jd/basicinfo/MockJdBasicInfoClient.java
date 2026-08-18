package cn.zimu.fulfillment.connector.jd.basicinfo;

import cn.zimu.fulfillment.connector.jd.JdResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 默认可重复的本地基础信息查询客户端。Mock 和真实 LOP 客户端共用同一应用边界，
 * 返回稳定假数据，不触网；数据不含联系人、电话、地址等个人信息。
 *
 * <p>输出键与 REAL 客户端一致使用 camelCase，保证业务解析器对两种模式共用同一断言。
 */
@Service
@ConditionalOnProperty(name = "app.jd.client-mode", havingValue = "MOCK", matchIfMissing = true)
public class MockJdBasicInfoClient implements JDBasicInfoService {

    @Override
    public JdResult queryCustomers(Map<String, Object> request) {
        return success("queryCustomers", request, Map.of(
                "customers", List.of(Map.of(
                        "ownerNo", value(request, "owner_no", "MOCK-OWNER-001"),
                        "customerNo", value(request, "customer_no", "MOCK-CUST-001"),
                        "customerName", value(request, "customer_name", "默认客户")))));
    }

    @Override
    public JdResult querySellers(Map<String, Object> request) {
        return success("querySellers", request, Map.of(
                "seller", Map.of(
                        "ownerNo", "MOCK-OWNER-001",
                        "shopNos", "MOCK-SHOP-001",
                        "warehouseNos", "MOCK-WH-001")));
    }

    @Override
    public JdResult queryShops(Map<String, Object> request) {
        return success("queryShops", request, Map.of(
                "shops", List.of(Map.of(
                        "ownerNo", value(request, "owner_no", "MOCK-OWNER-001"),
                        "shopNo", value(request, "shop_no", "MOCK-SHOP-001"),
                        "shopName", "默认店铺",
                        "erpShopNo", value(request, "erp_shop_no", "ERP-SHOP-001"),
                        "status", "ACTIVE"))));
    }

    @Override
    public JdResult queryShopGoods(Map<String, Object> request) {
        return success("queryShopGoods", request, Map.of(
                "shopGoods", Map.of(
                        "totalNum", 1,
                        "pageSize", value(request, "page_size", 20),
                        "currentPage", value(request, "current_page", 1),
                        "resultList", List.of(Map.of(
                                "shopNo", value(request, "shop_no", "MOCK-SHOP-001"),
                                "goodsNo", value(request, "goods_no", "MOCK-SKU-001"),
                                "shopGoodsNo", "MOCK-SHOPGOODS-001",
                                "shopGoodsName", "Mock 店铺商品")))));
    }

    @Override
    public JdResult querySuppliers(Map<String, Object> request) {
        return success("querySuppliers", request, Map.of(
                "suppliers", List.of(Map.of(
                        "ownerNo", value(request, "owner_no", "MOCK-OWNER-001"),
                        "supplierNo", value(request, "supplier_nos", "MOCK-SUP-001"),
                        "supplierName", "默认供应商",
                        "status", "ACTIVE"))));
    }

    @Override
    public JdResult queryGoodsCategories(Map<String, Object> request) {
        return success("queryGoodsCategories", request, Map.of(
                "categories", Map.of(
                        "firstCategoryList", List.of(Map.of(
                                "firstCategoryCode", value(request, "first_category_code", "1"),
                                "firstCategoryName", "Mock 一级类目")))));
    }

    @Override
    public JdResult queryWarehouseCoverages(Map<String, Object> request) {
        return success("queryWarehouseCoverages", request, Map.of(
                "warehouseCoverages", List.of(Map.of(
                        "warehouseNo", "MOCK-WH-001"))));
    }

    @Override
    public JdResult queryGoodsInfo(Map<String, Object> request) {
        Object goodsNoValue = value(request, "goods_no", null);
        String goodsNo = goodsNoValue == null ? null : goodsNoValue.toString();
        if (goodsNo == null || goodsNo.isBlank() || "MOCK-MISSING-001".equals(goodsNo)) {
            return goodsResult(List.of());
        }
        boolean enabled = !"MOCK-DISABLED-001".equals(goodsNo);
        // enableFlag 必须落在京东真实值域：官方定义「1：未启用，2：启用」
        // （快照 docs/research/jdl-api-367/json/1610-queryGoodsInfo.json）。此前 Mock 用
        // enabled ? 1 : 0——0 是京东永不返回的编造值，且语义与官方相反，会把错误判据固化进测试。
        return goodsResult(List.of(Map.of(
                "ownerNo", value(request, "owner_no", "MOCK-OWNER-001"),
                "basicInfo", Map.of(
                        "goodsNo", goodsNo,
                        "erpGoodsNo", "ERP-" + goodsNo,
                        "goodsName", "子牧羊小腿 500g/盒",
                        "enableFlag", enabled ? 2 : 1))));
    }

    /**
     * queryGoodsInfo 的 data 与 REAL 客户端一致直接是商品列表（不套 operation/request/response 壳），
     * 保证 SKU 映射核对解析器对两种模式共用同一断言。
     */
    private JdResult goodsResult(Object data) {
        return new JdResult(true, "MOCK_SUCCESS", "mock client completed", "mock-queryGoodsInfo", data);
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
        // Controller 以驼峰键传参（如 supplierNos），此处同时兼容驼峰与下划线键，让 Mock 真正回显输入。
        return request.getOrDefault(key, request.getOrDefault(toCamelCase(key), fallback));
    }

    private String toCamelCase(String key) {
        StringBuilder camel = new StringBuilder(key.length());
        boolean upperNext = false;
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c == '_') {
                upperNext = true;
            } else if (upperNext) {
                camel.append(Character.toUpperCase(c));
                upperNext = false;
            } else {
                camel.append(c);
            }
        }
        return camel.toString();
    }
}
