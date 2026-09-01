package cn.zimu.fulfillment.sku;

/** SKU 履约就绪告警；告警不改变 ready，声明顺序即公开 API 的稳定排序。 */
public enum SkuReadinessWarningCode {
    PACKAGING_METADATA_INCOMPLETE(
            "SKU 包装资料未完整维护，不影响成单、导出或发货",
            "可选完善净含量、净含量单位、包装件数和包装单位；填写时必须成组一致");

    private final String message;
    private final String action;

    SkuReadinessWarningCode(String message, String action) {
        this.message = message;
        this.action = action;
    }

    public String message() {
        return message;
    }

    public String action() {
        return action;
    }
}
