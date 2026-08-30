package cn.zimu.fulfillment.order;

/** 仅在服务端 OrderDraft 确认事务内传递的内部 SKU capability；不属于任何 HTTP DTO。 */
record ConfirmedDraftSku(int lineNo, String sourceLineRef, long skuId, String skuCode) {}
