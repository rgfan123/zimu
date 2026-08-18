package cn.zimu.fulfillment.message;

import java.util.List;

/**
 * CUSTOMER_ORDER 解释结果的订单草稿工厂接缝（票 04/06 实现）。
 *
 * <p>IntentRouter 只按意图调用本接缝，不包含草稿规则；实现类在同一 Worker 事务内创建
 * OrderDraft、草稿行与唯一的 ORDER_OPS 复核事项，并可把提交状态升级为 DRAFTED。
 * 返回创建的草稿 ID 列表（未创建时返回空列表）。
 */
public interface OrderDraftFactory {

    List<Long> createDrafts(MessageSubmission submission, InterpretationResult result);
}
