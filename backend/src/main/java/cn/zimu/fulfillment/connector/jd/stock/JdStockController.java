package cn.zimu.fulfillment.connector.jd.stock;

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

/** 京东 ISC 库存查询域只读作业面：7 个查询端点，全部为 GET，不做任何写操作。 */
@RestController
@RequestMapping("/api/v1/jd-stock")
public class JdStockController {

    private final JDStockService service;
    private final String clientMode;
    private final String serverUrl;
    private final String appKey;
    private final String appSecret;
    private final String accessToken;
    private final String pin;
    private final String ownerNo;

    public JdStockController(
            JDStockService service,
            @Value("${app.jd.client-mode:MOCK}") String clientMode,
            @Value("${app.jd.server-url:}") String serverUrl,
            @Value("${app.jd.app-key:}") String appKey,
            @Value("${app.jd.app-secret:}") String appSecret,
            @Value("${app.jd.access-token:}") String accessToken,
            @Value("${app.jd.pin:}") String pin,
            @Value("${app.jd.owner-no:}") String ownerNo) {
        this.service = service;
        this.clientMode = clientMode;
        this.serverUrl = serverUrl;
        this.appKey = appKey;
        this.appSecret = appSecret;
        this.accessToken = accessToken;
        this.pin = pin;
        this.ownerNo = ownerNo;
    }

    @GetMapping("/status")
    public JdStockClientStatus status() {
        boolean credentialsConfigured = present(serverUrl) && present(appKey) && present(appSecret) && present(accessToken);
        boolean tenantConfigured = present(pin) && present(ownerNo);
        return new JdStockClientStatus(
                clientMode.toUpperCase(Locale.ROOT),
                credentialsConfigured,
                tenantConfigured,
                "REAL".equalsIgnoreCase(clientMode) && credentialsConfigured && tenantConfigured);
    }

    @GetMapping("/snapshot")
    public JdResult stockSnapshot(
            @RequestParam(name = "goods_no", required = false) List<String> goodsNo,
            @RequestParam(name = "goods_level", required = false) List<String> goodsLevel,
            @RequestParam(name = "isv_sku", required = false) List<String> isvSku,
            @RequestParam(name = "seller_goods_sign", required = false) List<String> sellerGoodsSign,
            @RequestParam(name = "stock_type", required = false) List<String> stockType,
            @RequestParam(name = "above_zero", required = false) Integer aboveZero,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "page_size", required = false) Integer pageSize) {
        Map<String, Object> request = new LinkedHashMap<>();
        putIfPresent(request, "goodsNoList", goodsNo);
        putIfPresent(request, "goodsLevelList", goodsLevel);
        putIfPresent(request, "isvSkuList", isvSku);
        putIfPresent(request, "sellerGoodsSignList", sellerGoodsSign);
        putIfPresent(request, "stockTypeList", integers(stockType));
        putIfPresent(request, "aboveZero", aboveZero);
        putIfPresent(request, "cursor", cursor);
        putIfPresent(request, "pageSize", pageSize);
        return redactPersonalData(service.queryStockSnapshot(request));
    }

    @GetMapping("/summary")
    public JdResult stockSummary(
            @RequestParam(name = "goods_no", required = false) List<String> goodsNo,
            @RequestParam(name = "goods_level", required = false) List<String> goodsLevel,
            @RequestParam(name = "isv_sku", required = false) List<String> isvSku,
            @RequestParam(name = "stock_type", required = false) List<String> stockType,
            @RequestParam(name = "above_zero", required = false) Integer aboveZero) {
        Map<String, Object> request = new LinkedHashMap<>();
        putIfPresent(request, "goodsNoList", goodsNo);
        putIfPresent(request, "goodsLevelList", goodsLevel);
        putIfPresent(request, "isvSkuList", isvSku);
        putIfPresent(request, "stockTypeList", integers(stockType));
        putIfPresent(request, "aboveZero", aboveZero);
        return redactPersonalData(service.queryStockSummary(request));
    }

    @GetMapping("/batch-changes")
    public JdResult batchChanges(
            @RequestParam(name = "warehouse_no", required = false) String warehouseNo,
            @RequestParam(name = "batch_change_no", required = false) List<String> batchChangeNo,
            @RequestParam(name = "start_date", required = false) String startDate,
            @RequestParam(name = "end_date", required = false) String endDate,
            @RequestParam(name = "current_page", required = false) Integer currentPage,
            @RequestParam(name = "page_size", required = false) Integer pageSize) {
        Map<String, Object> request = new LinkedHashMap<>();
        putIfPresent(request, "warehouseNo", warehouseNo);
        putIfPresent(request, "batchChangeNoList", batchChangeNo);
        putIfPresent(request, "startDate", startDate);
        putIfPresent(request, "endDate", endDate);
        putIfPresent(request, "currentPage", currentPage);
        putIfPresent(request, "pageSize", pageSize);
        return redactPersonalData(service.queryBatchChange(request));
    }

    @GetMapping("/level-changes")
    public JdResult levelChanges(
            @RequestParam(name = "order_no", required = false) List<String> orderNo,
            @RequestParam(name = "pre_change_level", required = false) String preChangeLevel,
            @RequestParam(name = "changed_level", required = false) String changedLevel,
            @RequestParam(name = "start_date", required = false) String startDate,
            @RequestParam(name = "end_date", required = false) String endDate,
            @RequestParam(name = "current_page", required = false) Integer currentPage,
            @RequestParam(name = "page_size", required = false) Integer pageSize) {
        Map<String, Object> request = new LinkedHashMap<>();
        putIfPresent(request, "orderNoList", orderNo);
        putIfPresent(request, "preChangeLevel", preChangeLevel);
        putIfPresent(request, "changedLevel", changedLevel);
        putIfPresent(request, "startDate", startDate);
        putIfPresent(request, "endDate", endDate);
        putIfPresent(request, "currentPage", currentPage);
        putIfPresent(request, "pageSize", pageSize);
        return redactPersonalData(service.queryGoodsLevelChange(request));
    }

    @GetMapping("/shelf-life-goods")
    public JdResult shelfLifeGoods(
            @RequestParam(name = "order_type", required = false) String orderType,
            @RequestParam(name = "check_order_no", required = false) String checkOrderNo,
            @RequestParam(name = "start_time", required = false) String startTime,
            @RequestParam(name = "end_time", required = false) String endTime,
            @RequestParam(name = "current_page", required = false) Integer currentPage,
            @RequestParam(name = "page_size", required = false) Integer pageSize) {
        Map<String, Object> request = new LinkedHashMap<>();
        putIfPresent(request, "orderType", orderType);
        putIfPresent(request, "checkOrderNo", checkOrderNo);
        putIfPresent(request, "startTime", startTime);
        putIfPresent(request, "endTime", endTime);
        putIfPresent(request, "currentPage", currentPage);
        putIfPresent(request, "pageSize", pageSize);
        return redactPersonalData(service.queryShelfLifeGoods(request));
    }

    @GetMapping("/shelf-life-inventory")
    public JdResult shelfLifeInventory(
            @RequestParam(name = "warehouse_no", required = false) String warehouseNo,
            @RequestParam(name = "goods_no", required = false) String goodsNo,
            @RequestParam(name = "erp_goods_no", required = false) String erpGoodsNo,
            @RequestParam(name = "goods_level", required = false) String goodsLevel,
            @RequestParam(name = "status", required = false) Integer status,
            @RequestParam(name = "current_page", required = false) Integer currentPage,
            @RequestParam(name = "page_size", required = false) Integer pageSize) {
        Map<String, Object> request = new LinkedHashMap<>();
        putIfPresent(request, "warehouseNo", warehouseNo);
        putIfPresent(request, "goodsNo", goodsNo);
        putIfPresent(request, "erpGoodsNo", erpGoodsNo);
        putIfPresent(request, "goodsLevel", goodsLevel);
        putIfPresent(request, "status", status);
        putIfPresent(request, "currentPage", currentPage);
        putIfPresent(request, "pageSize", pageSize);
        return redactPersonalData(service.queryShelfLifeInventory(request));
    }

    @GetMapping("/shop-stock-flow")
    public JdResult shopStockFlow(
            @RequestParam(name = "shop_no", required = false) String shopNo,
            @RequestParam(name = "warehouse_no", required = false) String warehouseNo,
            @RequestParam(name = "goods_no", required = false) String goodsNo,
            @RequestParam(name = "start_date", required = false) String startDate,
            @RequestParam(name = "end_date", required = false) String endDate,
            @RequestParam(name = "current_page", required = false) Integer currentPage,
            @RequestParam(name = "page_size", required = false) Integer pageSize) {
        Map<String, Object> request = new LinkedHashMap<>();
        putIfPresent(request, "shopNo", shopNo);
        putIfPresent(request, "warehouseNo", warehouseNo);
        putIfPresent(request, "goodsNo", goodsNo);
        putIfPresent(request, "startDate", startDate);
        putIfPresent(request, "endDate", endDate);
        putIfPresent(request, "currentPage", currentPage);
        putIfPresent(request, "pageSize", pageSize);
        return redactPersonalData(service.searchShopStockFlow(request));
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

    private List<Integer> integers(List<String> raw) {
        if (raw == null) {
            return List.of();
        }
        return raw.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> {
                    try {
                        return Integer.valueOf(value.trim());
                    } catch (NumberFormatException exception) {
                        return null;
                    }
                })
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private void putIfPresent(Map<String, Object> target, String key, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof List<?> values && values.isEmpty()) {
            return;
        }
        if (value instanceof String text && text.isBlank()) {
            return;
        }
        target.put(key, value);
    }

    private boolean present(String value) {
        return value != null && !value.isBlank();
    }

    public record JdStockClientStatus(
            String clientMode,
            boolean credentialsConfigured,
            boolean tenantConfigured,
            boolean liveReady) {}
}
