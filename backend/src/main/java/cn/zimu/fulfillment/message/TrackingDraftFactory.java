package cn.zimu.fulfillment.message;

import java.util.List;

/**
 * SUPPLIER_TRACKING 解释结果的运单草稿工厂接缝（票 08/10 实现）。
 *
 * <p>IntentRouter 只按意图调用本接缝，不包含匹配规则；实现类在同一 Worker 事务内逐行创建
 * ProviderTrackingDraft 与对应的唯一 ORDER_OPS 复核事项，并可把提交状态升级为 DRAFTED。
 * 返回创建的草稿 ID 列表（未创建时返回空列表）。
 */
public interface TrackingDraftFactory {

    List<Long> createDrafts(MessageSubmission submission, InterpretationResult result);
}
