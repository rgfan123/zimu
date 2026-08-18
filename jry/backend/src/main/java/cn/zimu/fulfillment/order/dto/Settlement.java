package cn.zimu.fulfillment.order.dto;

import cn.zimu.fulfillment.order.domain.SettlementMethod;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/** 结账方式与结账时间。 */
public record Settlement(
        @NotNull(message = "结账方式不能为空") SettlementMethod method,
        @NotNull(message = "结账时间不能为空") Instant settlementTime) {
}
