package cn.zimu.fulfillment.order;

import cn.zimu.fulfillment.common.dto.Patterns;
import cn.zimu.fulfillment.common.dto.PositiveCountQuantityDeserializer;
import com.fasterxml.jackson.annotation.JsonProperty;
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
 * 手工建单输入（V100 MANUAL 渠道）：运营柜台直录，不经导入批次。
 *
 * <p>客户必须绑定既有档案（customer_code 精确命中 BUSINESS/ACTIVE）——手工单不是
 * 客户建档入口，新客户先在主数据建档再来建单；商品按系统 SKU 直选，天然全映射，
 * 建成即 SKU_MAPPED，可立即走 fulfillment-routing 生成发货单。
 */
public record ManualOrderCreateWrite(
        @JsonProperty("customer_code")
                @NotBlank(message = "客户编码不能为空")
                @Size(max = 64, message = "客户编码超长")
                String customerCode,
        @NotNull(message = "收货信息不能为空") @Valid ManualReceiver receiver,
        @NotEmpty(message = "商品行不能为空") List<@Valid ManualOrderItem> items,
        @Size(max = 2000, message = "备注超长") String remark) {

    /** 收货三要素快照；省市区可留空，手工单地址整段录入。 */
    public record ManualReceiver(
            @NotBlank(message = "收货人姓名不能为空") @Size(max = 128, message = "收货人姓名超长") String name,
            @NotBlank(message = "收货电话不能为空") @Size(max = 64, message = "收货电话超长") String phone,
            @NotBlank(message = "收货地址不能为空") @Size(max = 1000, message = "收货地址超长") String address) {}

    /** 一行 = 一个系统 SKU × 正整数数量（V99 整数纪律）。 */
    public record ManualOrderItem(
            @JsonProperty("sku_id")
                    @NotBlank(message = "SKU 不能为空")
                    @Pattern(regexp = Patterns.IDENTIFIER, message = "SKU 标识符无效")
                    String skuId,
            @NotNull(message = "数量不能为空") @Positive(message = "数量必须为正整数")
                    @JsonDeserialize(using = PositiveCountQuantityDeserializer.class)
                    Integer quantity) {}
}
