package cn.zimu.fulfillment.file;

import java.time.Instant;
import java.util.Map;

record ParsedSourceRow(
        String sheetName,
        int sheetIndex,
        int rowIndex,
        Map<String, String> rawCells,
        String sourceOrderRef,
        String sourceLineRef,
        String sourceCustomerRef,
        String customerName,
        String receiverName,
        String receiverPhone,
        String receiverAddress,
        String receiverProvince,
        String receiverCity,
        String receiverDistrict,
        String sourceSkuRef,
        String productName,
        String specification,
        String unit,
        String quantity,
        Instant orderedAt,
        String settlementMethod,
        String remark,
        String errorCode,
        String errorMessage) {

    boolean valid() {
        return errorCode == null;
    }
}
