package cn.zimu.fulfillment.demo;

import cn.zimu.fulfillment.common.domain.SourceChannel;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** AI 提取并经人工确认的演示订单草稿；只能进入 DEMO 数据域。 */
public record DemoExtractedOrderInput(
        @AssertTrue(message = "仅接受已人工确认的演示订单草稿") boolean confirmed,
        @NotNull(message = "来源渠道不能为空") SourceChannel source,
        @JsonProperty("source_ref")
                @NotBlank(message = "来源单号不能为空")
                @Size(max = 128, message = "来源单号超长")
                String sourceRef,
        @NotNull(message = "客户信息不能为空") @Valid Customer customer,
        @NotNull(message = "收货信息不能为空") @Valid Receiver receiver,
        @JsonProperty("required_delivery_time")
                @NotNull(message = "期望送达时间不能为空")
                OffsetDateTime requiredDeliveryTime,
        @NotEmpty(message = "订单行不能为空")
                @Size(max = 100, message = "订单行不能超过 100 条")
                List<@Valid Item> items,
        @NotNull(message = "结账信息不能为空") @Valid Settlement settlement,
        @Size(max = 2000, message = "备注超长") String remark) {

    public record Customer(
            @JsonProperty("customer_name")
                    @NotBlank(message = "客户名称不能为空")
                    @Size(max = 255, message = "客户名称超长")
                    String customerName,
            @JsonProperty("customer_code") @Size(max = 64, message = "客户编码超长") String customerCode) {}

    public record Receiver(
            @JsonProperty("receiver_name")
                    @NotBlank(message = "收货人不能为空")
                    @Size(max = 128, message = "收货人超长")
                    String receiverName,
            @JsonProperty("receiver_phone")
                    @NotBlank(message = "联系电话不能为空")
                    @Size(max = 64, message = "联系电话超长")
                    String receiverPhone,
            @NotBlank(message = "收货地址不能为空")
                    @Size(max = 1000, message = "收货地址超长")
                    String address) {}

    public record Item(
            @JsonProperty("product_name")
                    @NotBlank(message = "商品名称不能为空")
                    @Size(max = 255, message = "商品名称超长")
                    String productName,
            @JsonProperty("sku_code") @Size(max = 64, message = "SKU 编码超长") String skuCode,
            @NotBlank(message = "规格不能为空")
                    @Size(max = 255, message = "规格超长")
                    String specification,
            @NotNull(message = "数量不能为空")
                    @DecimalMin(value = "0", inclusive = false, message = "数量必须大于 0")
                    @Digits(integer = 15, fraction = 3, message = "数量最多三位小数")
                    BigDecimal quantity,
            @NotBlank(message = "单位不能为空") @Size(max = 32, message = "单位超长") String unit) {}

    public record Settlement(
            @JsonProperty("settlement_method")
                    @NotBlank(message = "结账方式不能为空")
                    @Size(max = 64, message = "结账方式超长")
                    String settlementMethod,
            @JsonProperty("settlement_time")
                    @NotNull(message = "结账时间不能为空")
                    OffsetDateTime settlementTime) {}
}
