package cn.zimu.fulfillment.order.dto;

import cn.zimu.fulfillment.common.dto.Patterns;
import cn.zimu.fulfillment.order.domain.SettlementMethod;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/**
 * 订单草稿补充资料命令（票 06）：补充/选择收货与结账资料并修订商品数量，草稿保持 OPEN。
 *
 * <p>不携带客户选择与 SKU 确认——客户解析属于确认用例，SKU 主数据以确认后为准；
 * 所有字段可选，命令里出现的字段才被覆盖。
 */
public record OrderDraftSupplementCommand(
        @NotNull(message = "草稿期望版本不能为空") Long expectedRevision,
        @Valid Receiver receiver,
        SettlementMethod settlementMethod,
        Instant settlementTime,
        @Valid @Size(max = 100, message = "商品行超量") List<@Valid LineSupplement> items) {

    public OrderDraftSupplementCommand(
            Long expectedRevision,
            Receiver receiver,
            SettlementMethod settlementMethod,
            List<LineSupplement> items) {
        this(expectedRevision, receiver, settlementMethod, null, items);
    }

    /** 草稿行补充：数量为正数时修订；sku_id 仅限候选内选择，不作为主数据确认。 */
    public record LineSupplement(
            @NotNull(message = "行号不能为空") Integer lineNo,
            @Pattern(regexp = Patterns.POSITIVE_INTEGER_QUANTITY, message = "数量必须为正整数")
                    String quantity,
            @Pattern(regexp = Patterns.IDENTIFIER, message = "SKU 标识符无效") String skuId) {}
}
