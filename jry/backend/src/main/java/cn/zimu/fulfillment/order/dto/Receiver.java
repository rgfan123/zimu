package cn.zimu.fulfillment.order.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 收货信息（与客户档案分离的快照）。 */
public record Receiver(
        @NotBlank(message = "收货人姓名不能为空") @Size(max = 128, message = "收货人姓名超长") String name,
        @NotBlank(message = "收货电话不能为空") @Size(max = 64, message = "收货电话超长") String phone,
        @Size(max = 64) String province,
        @Size(max = 64) String city,
        @Size(max = 64) String district,
        @Size(max = 64) String town,
        @NotBlank(message = "收货地址不能为空") @Size(max = 1000, message = "收货地址超长") String address) {
}
