package cn.zimu.fulfillment.fulfillment;

/** 明确库存决策的稳定应用层结果。 */
public record StockDecisionResult(
        String fulfillmentId,
        StockDecisionCommand.Decision decision,
        String processingStage,
        String procurementTicketId) {}
