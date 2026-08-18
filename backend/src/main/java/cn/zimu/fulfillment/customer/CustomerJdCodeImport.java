package cn.zimu.fulfillment.customer;

import java.util.List;

/** 京东客户编码批量导入输入（jd-real-sdk-switch 02）；行级校验在 Service 层完成。 */
public record CustomerJdCodeImport(List<CustomerJdCodeImportRow> rows) {

    public record CustomerJdCodeImportRow(String customerCode, String jdCustomerCode) {}
}
