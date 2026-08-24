package cn.zimu.fulfillment.fulfillment.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 运单草稿白名单投影：候选、校验问题与开放事项摘要，不直接倾倒模型原始输出。 */
public record ProviderTrackingDraftDetailDto(
        String id,
        String draftNo,
        String submissionId,
        int lineNo,
        String rawReceiverName,
        String maskedReceiverName,
        String trackingNo,
        String carrierCode,
        List<Map<String, Object>> carrierCandidates,
        List<Map<String, Object>> manualCarrierOptions,
        String taskId,
        List<Map<String, Object>> taskCandidates,
        String source,
        String confirmationScope,
        String shipmentJudgment,
        boolean defaultFullShipment,
        String actualQuantity,
        List<String> validationIssues,
        String status,
        long revision,
        String confirmedBy,
        Instant confirmedAt,
        String reviewCaseId,
        Long reviewCaseVersion,
        Instant createdAt) {}
