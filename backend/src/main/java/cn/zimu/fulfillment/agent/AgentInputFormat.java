package cn.zimu.fulfillment.agent;

/**
 * Agent 定义的输入约定（T05 评审修复，04 决策 2）：输入解析形态在定义中表达——
 * {@link #STRUCTURED_JSON}（结构化 JSON，如采购比价）走输入解析校验；
 * {@link #NATURAL_LANGUAGE}（自然语言，如数据查询）直接透传。DB 存大写字符串
 * （agent_definitions.input_format），经 {@link #fromDb} 互转。
 */
public enum AgentInputFormat {

    STRUCTURED_JSON,
    NATURAL_LANGUAGE;

    public static AgentInputFormat fromDb(String value) {
        return switch (value) {
            case "STRUCTURED_JSON" -> STRUCTURED_JSON;
            case "NATURAL_LANGUAGE" -> NATURAL_LANGUAGE;
            default -> throw new IllegalArgumentException("未知 input_format: " + value);
        };
    }
}
