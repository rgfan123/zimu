package cn.zimu.fulfillment.agent.dto;

import cn.zimu.fulfillment.common.error.BusinessException;

/**
 * 消耗汇总的分组维度（129 票）。
 *
 * <p>枚举而非自由字符串是硬性要求：分组表达式直接拼进 SQL 的 SELECT/GROUP BY，
 * 无法参数化，允许自由值等于开放注入面。新增维度必须在此登记。
 */
public enum TokenUsageGroupBy {

    /** 按 Agent 汇总（走 idx_agent_runs_slug_started）。 */
    AGENT("agent_slug"),

    /** 按业务日汇总（Asia/Shanghai，与看板既有口径一致）。 */
    DAY("(started_at AT TIME ZONE 'Asia/Shanghai')::date::text"),

    /** 按业务实体类型汇总；无实体的运行归入空串（不是丢弃）。 */
    BUSINESS_ENTITY_TYPE("COALESCE(business_entity_type, '')");

    private final String sqlExpression;

    TokenUsageGroupBy(String sqlExpression) {
        this.sqlExpression = sqlExpression;
    }

    /** 分组表达式；仅来自本枚举常量，不接受外部拼接。 */
    public String sqlExpression() {
        return sqlExpression;
    }

    public static TokenUsageGroupBy parse(String value) {
        if (value == null || value.isBlank()) {
            return AGENT;
        }
        try {
            return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw BusinessException.badRequest(
                    "VALIDATION_ERROR",
                    "group_by 必须是 AGENT/DAY/BUSINESS_ENTITY_TYPE 之一: " + value);
        }
    }
}
