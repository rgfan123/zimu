package cn.zimu.fulfillment.order.dto;

import cn.zimu.fulfillment.order.domain.SettlementMethod;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/** 结账方式与结账时间。 */
public record Settlement(
        @NotNull(message = "结账方式不能为空") SettlementMethod method,
        @NotNull(message = "结账时间不能为空") Instant settlementTime) {

    public Settlement {
        if (method == SettlementMethod.UNSPECIFIED && settlementTime != null) {
            throw new IllegalArgumentException("UNSPECIFIED settlement must not carry a time");
        }
    }

    /** 仅来源适配器在来源明确不提供结账事实时使用；外部请求仍受字段上的 NotNull 门禁。 */
    public static Settlement unspecifiedSourceFact() {
        return new Settlement(SettlementMethod.UNSPECIFIED, null);
    }
}
