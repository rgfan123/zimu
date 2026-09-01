package cn.zimu.fulfillment.fulfillment;

/** 履约应用层调用文件 Adapter 的最窄端口。 */
public interface ContinuationExportGenerator {

    ContinuationExport generateContinuation(
            long fulfillmentId, int instructedQuantity, String remark, String operator);

    record ContinuationExport(
            long fulfillmentExportId,
            long shipmentId,
            int shipmentSequence,
            String outboundOrderNo,
            String exportBatchNo) {
    }
}
