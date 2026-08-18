package cn.zimu.fulfillment.connector.jd;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 京东仓只读作业面。创建、取消出库仍只允许由受审计的履约用例调用，避免前端误操作。
 */
@RestController
@RequestMapping("/api/v1/jd-warehouse")
public class JdWarehouseController {

    private final JDWarehouseService service;
    private final String clientMode;
    private final String serverUrl;
    private final String appKey;
    private final String appSecret;
    private final String accessToken;
    private final String pin;
    private final String ownerNo;

    public JdWarehouseController(
            JDWarehouseService service,
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
    public JdClientStatus status() {
        boolean credentialsConfigured = present(serverUrl) && present(appKey) && present(appSecret) && present(accessToken);
        boolean tenantConfigured = present(pin) && present(ownerNo);
        return new JdClientStatus(
                clientMode.toUpperCase(Locale.ROOT),
                credentialsConfigured,
                tenantConfigured,
                "REAL".equalsIgnoreCase(clientMode) && credentialsConfigured && tenantConfigured);
    }

    @GetMapping("/warehouses")
    public JdResult warehouses(@RequestParam(name = "warehouse_no", required = false) String warehouseNo) {
        Map<String, Object> request = new LinkedHashMap<>();
        if (warehouseNo != null && !warehouseNo.isBlank()) {
            request.put("warehouseNo", warehouseNo);
        }
        return service.queryWarehouses(request);
    }

    @GetMapping("/owners")
    public JdResult owners() {
        return redactPersonalData(service.queryOwners(Map.of()));
    }

    @GetMapping("/outbound-orders/{erp_delivery_no}")
    public JdResult outboundOrder(@PathVariable("erp_delivery_no") String erpDeliveryNo) {
        return redactPersonalData(service.queryOutboundOrder(Map.of(
                "erpDeliveryNo", erpDeliveryNo,
                "deliveryItemFlag", 1,
                "deliveryPackageFlag", 1,
                "deliveryStatusFlag", 1)));
    }

    @GetMapping("/tracking")
    public JdResult tracking(
            @RequestParam(name = "waybill_no", required = false) String waybillNo,
            @RequestParam(name = "warehouse_order_no", required = false) String warehouseOrderNo) {
        Map<String, Object> request = new LinkedHashMap<>();
        if (waybillNo != null && !waybillNo.isBlank()) {
            request.put("waybillNo", waybillNo);
        }
        if (warehouseOrderNo != null && !warehouseOrderNo.isBlank()) {
            request.put("warehouseOrderNo", warehouseOrderNo);
        }
        return service.queryTracking(request);
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

    private boolean present(String value) {
        return value != null && !value.isBlank();
    }

    public record JdClientStatus(
            String clientMode,
            boolean credentialsConfigured,
            boolean tenantConfigured,
            boolean liveReady) {}
}
