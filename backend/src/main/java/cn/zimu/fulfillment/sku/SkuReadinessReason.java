package cn.zimu.fulfillment.sku;

import cn.zimu.fulfillment.common.error.BusinessException;

/** SKU 履约就绪阻断原因；声明顺序即公开 API 的稳定排序。 */
public enum SkuReadinessReason {
    PRODUCT_INACTIVE("所属商品已停用", "启用所属商品，或改用其他有效商品"),
    SKU_INACTIVE("SKU 已停用", "启用 SKU，或改用其他有效 SKU"),
    PROVIDER_INACTIVE("履约方已停用", "启用履约方，或把业务改到有效履约方"),
    SPECIFICATION_REQUIRED("SKU 规格缺失或仍为占位值", "维护真实规格，不能填写‘待维护/待确认/未知/-’"),
    UNIT_REQUIRED("SKU 库存计数单位缺失或仍为占位值", "维护真实库存计数单位"),
    PROVIDER_MAPPING_REQUIRED("缺少履约方商品映射", "维护该 SKU 对应履约方的有效商品编码"),
    PROVIDER_MAPPING_INACTIVE("履约方商品映射已停用", "复核后启用正确映射，或新建替代映射"),
    UNIT_CONVERSION_REQUIRED("京东件数换算缺失或无效", "非‘件’单位须维护正数 jd_pieces_per_unit"),
    BARCODE_CONFLICT("条码与其他 active SKU 冲突", "核对实物后为不同商品维护独立条码"),
    REVIEW_REQUIRED("存在尚未裁决的数据质量证据", "按数据质量标记中的证据完成人工复核");

    private final String message;
    private final String action;

    SkuReadinessReason(String message, String action) {
        this.message = message;
        this.action = action;
    }

    public String message() {
        return message;
    }

    public String action() {
        return action;
    }

    public static SkuReadinessReason parse(String value) {
        try {
            return valueOf(value);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw BusinessException.badRequest("INVALID_READINESS_REASON", "未知的 SKU 履约就绪原因: " + value);
        }
    }
}
