package cn.zimu.fulfillment.order.dto;

import cn.zimu.fulfillment.common.dto.Patterns;
import cn.zimu.fulfillment.common.dto.PositiveCountQuantityDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 订单草稿确认命令：草稿期望版本 + 复核事项期望版本 + 客户选择（已有/新建）+ 收货/结账 + 逐行 SKU 与数量。
 *
 * <p>票 05 支持在同一确认流程选择已有 Customer 或创建新客户（编码由系统幂等生成）；
 * 命令仍不接受渠道身份或履约方参数，这些事实只能由消息入口提供。
 */
public record ConfirmOrderDraftCommand(
        @NotNull(message = "草稿期望版本不能为空") Long expectedRevision,
        @NotNull(message = "复核事项期望版本不能为空") Long expectedCaseVersion,
        @Valid @NotNull(message = "客户选择不能为空") CustomerChoice customer,
        @Valid @NotNull(message = "收货信息不能为空") Receiver receiver,
        @Valid @NotNull(message = "结账信息不能为空") Settlement settlement,
        @NotEmpty(message = "订单行不能为空") List<@Valid ConfirmItem> items,
        @Size(max = 2000, message = "备注超长") String remark) {

    /**
     * 票 05 客户选择：二选一——填写 customer_id 选择已有 Customer，或填写人工确认的
     * new_customer_name 创建新客户；两者同时或都为空由应用用例明确拒绝。
     */
    public record CustomerChoice(
            @Pattern(regexp = Patterns.IDENTIFIER, message = "客户标识符无效") String customerId,
            @Size(max = 128, message = "新客户名称超长") String newCustomerName) {}

    /** 草稿行的人工确认：SKU 主数据 + 数量（数量以人工确认为准）。 */
    public record ConfirmItem(
            @NotNull(message = "行号不能为空") Integer lineNo,
            @NotBlank(message = "SKU 不能为空") @Pattern(regexp = Patterns.IDENTIFIER, message = "SKU 标识符无效")
                    String skuId,
            @NotNull(message = "数量不能为空") @Positive(message = "数量必须为正整数")
                    @JsonDeserialize(using = PositiveCountQuantityDeserializer.class)
                    Integer quantity) {}
}
