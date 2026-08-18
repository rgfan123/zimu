package cn.zimu.fulfillment.customer;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 客户创建输入。 */
public record CustomerWrite(
        @NotBlank(message = "客户编码不能为空") @Size(max = 64, message = "客户编码超长") String customerCode,
        @NotBlank(message = "客户名称不能为空") @Size(max = 128, message = "客户名称超长") String customerName,
        @Size(max = 64) String departmentCode,
        @Size(max = 128) String contactName,
        @Size(max = 32) String contactPhone,
        Boolean active) {
}
