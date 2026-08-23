package cn.zimu.fulfillment.order.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 订单草稿详情白名单视图：模型原值、候选、缺失项、人工确认结果与开放复核事项引用。
 *
 * <p>不直接倾倒未知 JSON 字段；`confirmed_order_id` 仅在草稿确认后从复核事项决议读取，
 * `suspected_duplicate_of` 仅在开放复核事项提示疑似重复时出现。
 */
public record OrderDraftDetailDto(
        String id,
        String draftNo,
        String sourceOrderNo,
        String submissionId,
        String status,
        long revision,
        String customerId,
        String customerCode,
        String customerName,
        List<Map<String, Object>> customerCandidates,
        String customerNameRaw,
        String receiverName,
        String receiverPhone,
        String receiverAddress,
        String settlementMethod,
        Instant settlementTime,
        List<String> missingFields,
        List<OrderDraftLineDto> lines,
        String reviewCaseId,
        Long reviewCaseVersion,
        String suspectedDuplicateOf,
        String confirmedOrderId,
        String confirmedBy,
        Instant confirmedAt,
        Instant createdAt,
        Instant updatedAt) {}
