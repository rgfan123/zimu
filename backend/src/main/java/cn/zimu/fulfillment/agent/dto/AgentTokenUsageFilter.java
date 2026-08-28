package cn.zimu.fulfillment.agent.dto;

import cn.zimu.fulfillment.agent.AgentDefinition;
import cn.zimu.fulfillment.agent.AgentOutcome;
import cn.zimu.fulfillment.common.error.BusinessException;
import java.time.OffsetDateTime;

/**
 * 消耗汇总查询条件（129 票）。校验语义与 {@link AgentRunFilter} 保持一致
 * （同一批参数在两个端点上不应有两套规则）。
 *
 * @param slug               限定单个 Agent（可选）
 * @param outcome            结果维度（可选；映射与运行记录列表一致）
 * @param runMode            LIVE/PREVIEW；null 默认 LIVE
 * @param businessEntityType 限定业务实体类型（可选）
 * @param businessEntityId   限定业务实体 ID（可选）
 * @param startedFrom        开始时间下界（含，可选）
 * @param startedTo          开始时间上界（含，可选）
 * @param groupBy            分组维度；null 默认 AGENT
 * @param limit              返回分组数上限（默认 100，上限 500）
 */
public record AgentTokenUsageFilter(
        String slug,
        AgentOutcome outcome,
        String runMode,
        String businessEntityType,
        String businessEntityId,
        OffsetDateTime startedFrom,
        OffsetDateTime startedTo,
        TokenUsageGroupBy groupBy,
        int limit) {

    public static final int DEFAULT_LIMIT = 100;
    public static final int MAX_LIMIT = 500;

    /** 校验并构造（参数非法抛 400 VALIDATION_ERROR）。 */
    public static AgentTokenUsageFilter of(
            String slug,
            String outcome,
            String runMode,
            String businessEntityType,
            String businessEntityId,
            String startedFrom,
            String startedTo,
            String groupBy,
            int limit) {
        if (slug != null && !slug.matches(AgentDefinition.SLUG_PATTERN)) {
            throw BusinessException.badRequest(
                    "VALIDATION_ERROR", "slug 必须匹配 ^[a-z][a-z0-9-]{0,63}$: " + slug);
        }
        if (runMode != null && !"LIVE".equals(runMode) && !"PREVIEW".equals(runMode)) {
            throw BusinessException.badRequest("VALIDATION_ERROR", "run_mode 必须是 LIVE 或 PREVIEW: " + runMode);
        }
        AgentOutcome parsedOutcome = null;
        if (outcome != null) {
            try {
                parsedOutcome = AgentOutcome.valueOf(outcome);
            } catch (IllegalArgumentException ex) {
                throw BusinessException.badRequest(
                        "VALIDATION_ERROR",
                        "outcome 必须是 SUCCESS/NEEDS_INPUT/REJECTED/FAILED 之一: " + outcome);
            }
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw BusinessException.badRequest("VALIDATION_ERROR", "limit 必须在 1.." + MAX_LIMIT);
        }
        return new AgentTokenUsageFilter(
                slug,
                parsedOutcome,
                runMode,
                businessEntityType,
                businessEntityId,
                parseInstant(startedFrom, "started_from"),
                parseInstant(startedTo, "started_to"),
                TokenUsageGroupBy.parse(groupBy),
                limit);
    }

    private static OffsetDateTime parseInstant(String value, String param) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value);
        } catch (java.time.format.DateTimeParseException ex) {
            throw BusinessException.badRequest(
                    "VALIDATION_ERROR",
                    param + " 必须是 ISO-8601 带时区时间（如 2026-08-13T10:00:00+08:00）: " + value);
        }
    }

    /** 生效的 run_mode（null 按 LIVE——PREVIEW 不进成本视图）。 */
    public String effectiveRunMode() {
        return runMode == null ? "LIVE" : runMode;
    }
}
