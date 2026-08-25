package cn.zimu.fulfillment.connector.jd.basicinfo;

import cn.zimu.fulfillment.connector.jd.JdResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 京东 ISC 基础信息查询域（只读）：客户/商家/店铺/店铺商品/供应商/商品类目/仓库覆盖范围/商品信息。
 * 客户、供应商、店铺结果天然含联系人、电话、地址等个人信息，HTTP 边界一律脱敏后再返回。
 */
@RestController
@RequestMapping("/api/v1/jd-basicinfo")
public class JdBasicInfoController {

    private final JDBasicInfoService service;
    private final String clientMode;
    private final String serverUrl;
    private final String appKey;
    private final String appSecret;
    private final String accessToken;

    public JdBasicInfoController(
            JDBasicInfoService service,
            @Value("${app.jd.client-mode:MOCK}") String clientMode,
            @Value("${app.jd.server-url:}") String serverUrl,
            @Value("${app.jd.app-key:}") String appKey,
            @Value("${app.jd.app-secret:}") String appSecret,
            @Value("${app.jd.access-token:}") String accessToken) {
        this.service = service;
        this.clientMode = clientMode;
        this.serverUrl = serverUrl;
        this.appKey = appKey;
        this.appSecret = appSecret;
        this.accessToken = accessToken;
    }

    /** 连接模式与授权就绪状态（与 jd-warehouse/status 同构，便于前端区分 MOCK/REAL）。 */
    @GetMapping("/status")
    public JdClientStatus status() {
        boolean credentialsConfigured = present(serverUrl) && present(appKey) && present(appSecret) && present(accessToken);
        return new JdClientStatus(
                clientMode.toUpperCase(Locale.ROOT),
                credentialsConfigured,
                "REAL".equalsIgnoreCase(clientMode) && credentialsConfigured);
    }

    @GetMapping("/customers")
    public JdResult customers(
            @RequestParam(name = "owner_no", required = false) String ownerNo,
            @RequestParam(name = "customer_no", required = false) String customerNo,
            @RequestParam(name = "customer_name", required = false) String customerName,
            @RequestParam(name = "page_size", required = false) Integer pageSize,
            @RequestParam(name = "current_page", required = false) Integer currentPage) {
        Map<String, Object> request = new LinkedHashMap<>();
        putIfPresent(request, "ownerNo", ownerNo);
        putIfPresent(request, "customerNo", customerNo);
        putIfPresent(request, "customerName", customerName);
        putIfPresent(request, "pageSize", pageSize);
        putIfPresent(request, "currentPage", currentPage);
        return redactPersonalData(service.queryCustomers(request));
    }

    @GetMapping("/sellers")
    public JdResult sellers() {
        return redactPersonalData(service.querySellers(Map.of()));
    }

    @GetMapping("/shops")
    public JdResult shops(
            @RequestParam(name = "owner_no", required = false) String ownerNo,
            @RequestParam(name = "shop_no", required = false) String shopNo,
            @RequestParam(name = "erp_shop_no", required = false) String erpShopNo) {
        Map<String, Object> request = new LinkedHashMap<>();
        putIfPresent(request, "ownerNo", ownerNo);
        putIfPresent(request, "shopNo", shopNo);
        putIfPresent(request, "erpShopNo", erpShopNo);
        return redactPersonalData(service.queryShops(request));
    }

    @GetMapping("/shop-goods")
    public JdResult shopGoods(
            @RequestParam(name = "owner_no", required = false) String ownerNo,
            @RequestParam(name = "shop_no", required = false) String shopNo,
            @RequestParam(name = "goods_no", required = false) String goodsNo,
            @RequestParam(name = "erp_goods_no", required = false) String erpGoodsNo,
            @RequestParam(name = "sales_platform_goods_no", required = false) String salesPlatformGoodsNo,
            @RequestParam(name = "shop_goods_no_min", required = false) String shopGoodsNoMin,
            @RequestParam(name = "page_size", required = false) Integer pageSize,
            @RequestParam(name = "current_page", required = false) Integer currentPage) {
        Map<String, Object> request = new LinkedHashMap<>();
        putIfPresent(request, "ownerNo", ownerNo);
        putIfPresent(request, "shopNo", shopNo);
        putIfPresent(request, "goodsNo", goodsNo);
        putIfPresent(request, "erpGoodsNo", erpGoodsNo);
        putIfPresent(request, "salesPlatformGoodsNo", salesPlatformGoodsNo);
        putIfPresent(request, "shopGoodsNoMin", shopGoodsNoMin);
        putIfPresent(request, "pageSize", pageSize);
        putIfPresent(request, "currentPage", currentPage);
        return redactPersonalData(service.queryShopGoods(request));
    }

    @GetMapping("/suppliers")
    public JdResult suppliers(
            @RequestParam(name = "owner_no", required = false) String ownerNo,
            @RequestParam(name = "supplier_nos", required = false) String supplierNos,
            @RequestParam(name = "isv_supplier_nos", required = false) String isvSupplierNos) {
        Map<String, Object> request = new LinkedHashMap<>();
        putIfPresent(request, "ownerNo", ownerNo);
        putIfPresent(request, "supplierNos", supplierNos);
        putIfPresent(request, "isvSupplierNos", isvSupplierNos);
        return redactPersonalData(service.querySuppliers(request));
    }

    @GetMapping("/goods-categories")
    public JdResult goodsCategories(
            @RequestParam(name = "first_category_code", required = false) Integer firstCategoryCode,
            @RequestParam(name = "second_category_code", required = false) Integer secondCategoryCode,
            @RequestParam(name = "third_category_code", required = false) Integer thirdCategoryCode) {
        Map<String, Object> request = new LinkedHashMap<>();
        putIfPresent(request, "firstCategoryCode", firstCategoryCode);
        putIfPresent(request, "secondCategoryCode", secondCategoryCode);
        putIfPresent(request, "thirdCategoryCode", thirdCategoryCode);
        return redactPersonalData(service.queryGoodsCategories(request));
    }

    @GetMapping("/warehouse-coverages")
    public JdResult warehouseCoverages(
            @RequestParam(name = "owner_no", required = false) String ownerNo,
            @RequestParam(name = "province", required = false) String province,
            @RequestParam(name = "city", required = false) String city,
            @RequestParam(name = "county", required = false) String county,
            @RequestParam(name = "town", required = false) String town,
            @RequestParam(name = "detail_address", required = false) String detailAddress) {
        Map<String, Object> request = new LinkedHashMap<>();
        putIfPresent(request, "ownerNo", ownerNo);
        putIfPresent(request, "province", province);
        putIfPresent(request, "city", city);
        putIfPresent(request, "county", county);
        putIfPresent(request, "town", town);
        putIfPresent(request, "detailAddress", detailAddress);
        return redactPersonalData(service.queryWarehouseCoverages(request));
    }

    /** 商品信息查询（按京东商品编码 goodsNo / 商家 ERP 商品编码 erpGoodsNo 查询）。 */
    @GetMapping("/goods-info")
    public JdResult goodsInfo(
            @RequestParam(name = "goods_no", required = false) String goodsNo,
            @RequestParam(name = "erp_goods_no", required = false) String erpGoodsNo,
            @RequestParam(name = "bar_code", required = false) String barCode,
            @RequestParam(name = "page_size", required = false) Integer pageSize,
            @RequestParam(name = "current_page", required = false) Integer currentPage) {
        Map<String, Object> request = new LinkedHashMap<>();
        putIfPresent(request, "goodsNo", goodsNo);
        putIfPresent(request, "erpGoodsNo", erpGoodsNo);
        putIfPresent(request, "barCode", barCode);
        putIfPresent(request, "pageSize", pageSize);
        putIfPresent(request, "currentPage", currentPage);
        return redactPersonalData(service.queryGoodsInfo(request));
    }

    private JdResult redactPersonalData(JdResult result) {
        return new JdResult(
                result.success(),
                result.businessCode(),
                result.message(),
                result.requestId(),
                sanitize(result.data()));
    }

    private Object sanitize(Object value) {
        if (value instanceof Map<?, ?> values) {
            Map<String, Object> safe = new LinkedHashMap<>();
            values.forEach((key, item) -> {
                String field = String.valueOf(key);
                if (!personalField(field)) {
                    safe.put(field, sanitize(item));
                }
            });
            return safe;
        }
        if (value instanceof List<?> values) {
            return values.stream().map(this::sanitize).toList();
        }
        return value;
    }

    /**
     * HTTP 边界 PII 规则（6 个 JD controller 统一口径）：联系人/客户容器键（receiverinfo/
     * senderinfo/consignee/customerinfo 等）整块剔除；phone/mobile/telephone/email/fax/address
     * 按精确键或后缀匹配剔除，键先归一化为小写（覆盖 SDK camelCase 如 transporterPhone/backEmail），
     * 与 SecretRedactor.isPersonalDataKey 对齐。
     */
    private boolean personalField(String field) {
        String normalized = field.toLowerCase(Locale.ROOT);
        return normalized.contains("customerinfo")
                || normalized.contains("receiverinfo")
                || normalized.contains("senderinfo")
                || normalized.contains("consignee")
                || normalized.contains("contactinfo")
                || normalized.contains("recipientinfo")
                || normalized.equals("phone")
                || normalized.equals("mobile")
                || normalized.equals("telephone")
                || normalized.equals("email")
                || normalized.equals("fax")
                || normalized.equals("address")
                || normalized.endsWith("phone")
                || normalized.endsWith("mobile")
                || normalized.endsWith("telephone")
                || normalized.endsWith("email")
                || normalized.endsWith("fax")
                || normalized.endsWith("address")
                // 个人角色姓名键（transporterName/shipperName/operateName/linkmanName 等）：
                // 以 name 结尾且含个人角色词才剔除；业务实体名（ownerName/shopName/goodsName 等）不受影响
                || (normalized.endsWith("name")
                        && (normalized.contains("transporter")
                                || normalized.contains("shipper")
                                || normalized.contains("operator")
                                || normalized.contains("operate")
                                || normalized.contains("linkman")
                                || normalized.contains("contact")
                                || normalized.contains("receiver")
                                || normalized.contains("sender")));
    }

    private void putIfPresent(Map<String, Object> request, String key, Object value) {
        if (value != null && !value.toString().isBlank()) {
            request.put(key, value);
        }
    }

    private boolean present(String value) {
        return value != null && !value.isBlank();
    }

    public record JdClientStatus(
            String clientMode,
            boolean credentialsConfigured,
            boolean liveReady) {}
}
