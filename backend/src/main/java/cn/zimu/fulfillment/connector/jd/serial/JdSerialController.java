package cn.zimu.fulfillment.connector.jd.serial;

import cn.zimu.fulfillment.connector.jd.JdResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 京东序列号只读查询作业面。四个查询均为 GET，参数经 RequestParam 传入，
 * HTTP 边界统一脱敏 PII 后返回，不渲染自由 JSON。
 */
@RestController
@RequestMapping("/api/v1/jd-serial")
public class JdSerialController {

    private final JdSerialService service;

    public JdSerialController(JdSerialService service) {
        this.service = service;
    }

    /** 序列号查询：按订单/时间范围分页查询京东商城序列号。 */
    @GetMapping("/mall")
    public JdResult mall(
            @RequestParam(name = "order_no", required = false) String orderNo,
            @RequestParam(name = "enterprise_order_no", required = false) String enterpriseOrderNo,
            @RequestParam(name = "owner_no", required = false) String ownerNo,
            @RequestParam(name = "start_date", required = false) String startDate,
            @RequestParam(name = "end_date", required = false) String endDate,
            @RequestParam(name = "page_size", required = false) Integer pageSize,
            @RequestParam(name = "current_page", required = false) Integer currentPage) {
        Map<String, Object> request = new LinkedHashMap<>();
        putIfPresent(request, "orderNo", orderNo);
        putIfPresent(request, "enterpriseOrderNo", enterpriseOrderNo);
        putIfPresent(request, "ownerNo", ownerNo);
        putIfPresent(request, "startDate", startDate);
        putIfPresent(request, "endDate", endDate);
        putIfPresent(request, "pageSize", pageSize);
        putIfPresent(request, "currentPage", currentPage);
        return redactPersonalData(service.queryJdMallSerial(request));
    }

    /** 序列号条件查询：按事业部、仓库、业务类型等条件分页查询序列号。 */
    @GetMapping("/condition")
    public JdResult condition(
            @RequestParam(name = "biz_type", required = false) Integer bizType,
            @RequestParam(name = "query_type", required = false) Integer queryType,
            @RequestParam(name = "owner_no", required = false) String ownerNo,
            @RequestParam(name = "warehouse_no", required = false) String warehouseNo,
            @RequestParam(name = "start_date", required = false) String startDate,
            @RequestParam(name = "end_date", required = false) String endDate,
            @RequestParam(name = "current_page", required = false) Integer currentPage,
            @RequestParam(name = "page_size", required = false) Integer pageSize) {
        Map<String, Object> request = new LinkedHashMap<>();
        putIfPresent(request, "bizType", bizType);
        putIfPresent(request, "queryType", queryType);
        putIfPresent(request, "ownerNo", ownerNo);
        putIfPresent(request, "warehouseNo", warehouseNo);
        putIfPresent(request, "startDate", startDate);
        putIfPresent(request, "endDate", endDate);
        putIfPresent(request, "currentPage", currentPage);
        putIfPresent(request, "pageSize", pageSize);
        return redactPersonalData(service.querySerialByCondition(request));
    }

    /** 序列号流向查询：按商品编码 + 序列号查询出入库流向。 */
    @GetMapping("/flow")
    public JdResult flow(
            @RequestParam(name = "goods_no", required = false) String goodsNo,
            @RequestParam(name = "serial_no", required = false) String serialNo,
            @RequestParam(name = "query_type", required = false) Integer queryType) {
        Map<String, Object> request = new LinkedHashMap<>();
        putIfPresent(request, "goodsNo", goodsNo);
        putIfPresent(request, "serialNo", serialNo);
        putIfPresent(request, "queryType", queryType);
        return redactPersonalData(service.querySerialFlow(request));
    }

    /** 序列号内部查询：按商品编码分页查询在库序列号。 */
    @GetMapping("/inside")
    public JdResult inside(
            @RequestParam(name = "goods_no", required = false) String goodsNo,
            @RequestParam(name = "query_type", required = false) Integer queryType,
            @RequestParam(name = "page_size", required = false) Integer pageSize,
            @RequestParam(name = "current_page", required = false) Integer currentPage) {
        Map<String, Object> request = new LinkedHashMap<>();
        putIfPresent(request, "goodsNo", goodsNo);
        putIfPresent(request, "queryType", queryType);
        putIfPresent(request, "pageSize", pageSize);
        putIfPresent(request, "currentPage", currentPage);
        return redactPersonalData(service.querySerialInside(request));
    }

    private void putIfPresent(Map<String, Object> request, String key, Object value) {
        if (value instanceof String text) {
            if (!text.isBlank()) {
                request.put(key, text);
            }
        } else if (value != null) {
            request.put(key, value);
        }
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
}
