package cn.zimu.fulfillment.customer;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 客户更新输入（至少修改一个业务字段）。 */
public record CustomerPatch(
        @NotNull(message = "期望版本不能为空") Long expectedVersion,
        @Size(max = 128, message = "客户名称超长") String customerName,
        @Size(max = 64) String departmentCode,
        @Size(max = 128) String contactName,
        @Size(max = 32) String contactPhone,
        Boolean active,
        @Size(max = 64, message = "京东客户编码超长") String jdCustomerCode) {
}
