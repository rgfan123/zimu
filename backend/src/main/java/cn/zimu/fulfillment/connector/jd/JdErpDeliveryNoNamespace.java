package cn.zimu.fulfillment.connector.jd;

import java.util.Map;
import java.util.regex.Pattern;

/** Shared adapter-level guard for Zimu-owned JD sales-outbound references. */
public final class JdErpDeliveryNoNamespace {

    public static final String BUSINESS_CODE = "JD_ERP_DELIVERY_NO_NAMESPACE_REQUIRED";
    public static final String MESSAGE = "京东外部单号不在 ZIMU-SO 独占命名空间，禁止创建出库单";

    private static final Pattern OWNED = Pattern.compile(
            "^ZIMU-SO-[0-9]{8}-[0-9]{12}-[0-9A-F]{8}$");

    private JdErpDeliveryNoNamespace() {
    }

    public static boolean owns(String value) {
        return value != null && OWNED.matcher(value).matches();
    }

    public static boolean owns(Map<String, Object> request) {
        Object value = request == null ? null : request.get("erpDeliveryNo");
        return owns(value == null ? null : value.toString());
    }
}
