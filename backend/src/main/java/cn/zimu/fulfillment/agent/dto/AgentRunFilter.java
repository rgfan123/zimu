package cn.zimu.fulfillment.agent.dto;

import cn.zimu.fulfillment.agent.AgentDefinition;
import cn.zimu.fulfillment.agent.AgentOutcome;
import cn.zimu.fulfillment.common.error.BusinessException;
import java.time.OffsetDateTime;

/**
 * 运行记录查询过滤条件（12 票；/api 与 /internal 共用）。
 *
 * <p>校验规则（两个读面一致）：slug 匹配定义 slug 正则；run_mode 仅 LIVE/PREVIEW
 * （null 按 LIVE——默认不返回 PREVIEW 是 run_mode 字段存在的全部理由）；outcome
 * 仅 SUCCESS/NEEDS_INPUT/REJECTED/FAILED（NEEDS_INPUT 与 SUCCESS 在 agent_runs
 * 行级同形——04 决策澄清不再是失败，过滤语义一致）；limit 1..500；offset ≥ 0。
 *
 * @param runId           精确 run_id（可选）
 * @param slug            agent_slug（可选）
 * @param outcome         结果维度（可选；映射见 AgentRunReadService）
 * @param runMode         LIVE/PREVIEW；null 默认 LIVE
 * @param businessEntityType 业务实体类型（可选）
 * @param businessEntityId   业务实体 ID（可选）
 * @param startedFrom     开始时间下界（含，可选）
 * @param startedTo       开始时间上界（含，可选）
 * @param limit           页大小（默认 100，上限 500）
 * @param offset          偏移（默认 0）
 */
public record AgentRunFilter(
        String runId,
        String slug,
        AgentOutcome outcome,
        String runMode,
        String businessEntityType,
        String businessEntityId,
        OffsetDateTime startedFrom,
        OffsetDateTime startedTo,
        int limit,
        int offset) {

    public static final int DEFAULT_LIMIT = 100;
    public static final int MAX_LIMIT = 500;

    /** 校验并构造（参数非法抛 400 VALIDATION_ERROR；/api 与 /internal 共用同一语义）。 */
    public static AgentRunFilter of(
            String runId,
            String slug,
            String outcome,
            String runMode,
            String businessEntityType,
            String businessEntityId,
            String startedFrom,
            String startedTo,
            int limit,
            int offset) {
        if (slug != null && !slug.matches(AgentDefinition.SLUG_PATTERN)) {
            throw BusinessException.badRequest(
                    "VALIDATION_ERROR", "slug 必须匹配 ^[a-z][a-z0-9-]{0,63}$: " + slug);
        }
        if (runId != null && !runId.matches("^run_[0-9a-f]{32}$")) {
            throw BusinessException.badRequest(
                    "VALIDATION_ERROR", "run_id 必须匹配 ^run_[0-9a-f]{32}$: " + runId);
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
        if (offset < 0) {
            throw BusinessException.badRequest("VALIDATION_ERROR", "offset 不能为负");
        }
        return new AgentRunFilter(
                runId, slug, parsedOutcome, runMode, businessEntityType, businessEntityId,
                parseInstant(startedFrom, "started_from"),
                parseInstant(startedTo, "started_to"),
                limit, offset);
    }

    /** ISO-8601 带时区时间解析（OffsetDateTime.parse）；非法抛 400 VALIDATION_ERROR。 */
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

    /** 生效的 run_mode（null 按 LIVE——默认不返回 PREVIEW）。 */
    public String effectiveRunMode() {
        return runMode == null ? "LIVE" : runMode;
    }
}
