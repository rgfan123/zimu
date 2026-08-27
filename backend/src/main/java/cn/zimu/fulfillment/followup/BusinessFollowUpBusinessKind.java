package cn.zimu.fulfillment.followup;

import cn.zimu.fulfillment.common.error.BusinessException;

/** Server-owned write intent; only an explicit SAMPLE/FORMAL value can enable those flows. */
public enum BusinessFollowUpBusinessKind {
    CUSTOMER,
    SAMPLE,
    FORMAL;

    public static BusinessFollowUpBusinessKind parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return CUSTOMER;
        }
        try {
            return valueOf(raw);
        } catch (IllegalArgumentException ex) {
            throw BusinessException.badRequest(
                    "FOLLOWUP_BUSINESS_KIND_INVALID",
                    "business_kind 只允许 CUSTOMER、SAMPLE 或 FORMAL");
        }
    }
}
