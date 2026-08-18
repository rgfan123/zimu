package cn.zimu.fulfillment.order.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** 复核事项详情。 */
public record ReviewCaseDto(
        String id,
        String caseNo,
        String caseType,
        String responsibleTeam,
        String reasonCode,
        String status,
        String orderId,
        String orderLineId,
        String subjectType,
        String subjectId,
        Map<String, Object> detail,
        List<Map<String, Object>> suggestions,
        List<String> allowedActions,
        Map<String, Object> resolution,
        String resolvedBy,
        Instant resolvedAt,
        long version,
        Instant createdAt) {
}
