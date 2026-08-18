package cn.zimu.fulfillment.agent;

import java.util.List;
import java.util.Map;

/**
 * 数据查询 Agent（06 票）的结构化输出 schema。
 *
 * <p>记录组件名即 JSON 键（answer / sources / confidence / requires_human /
 * clarification_needed），由 AiServices 以 JSON Schema 约束模型输出并反序列化为本记录；
 * 模型输出不满足该结构时运行失败（{@code AGENT_OUTPUT_INVALID}），保证输出可 schema 校验。
 *
 * <p>{@code sources} 记录实际调用过的只读工具、关键参数与返回行数，供复核与评测核对；
 * {@code requires_human=true} 表示已转人工（PII / 信息不全 / 低置信度）；
 * {@code clarification_needed} 非空表示歧义输入进入澄清路径（不猜参数）。
 */
public record DataQueryAgentOutput(
        String answer,
        List<Source> sources,
        double confidence,
        boolean requires_human,
        List<String> clarification_needed) {

    public DataQueryAgentOutput {
        answer = answer == null ? "" : answer.strip();
        sources = sources == null ? List.of() : List.copyOf(sources);
        clarification_needed = clarification_needed == null
                ? List.of()
                : List.copyOf(clarification_needed);
    }

    /** 一次工具调用的来源记录：工具名、关键参数与返回行数。 */
    public record Source(String tool, Map<String, Object> key_args, int row_count) {

        public Source {
            tool = tool == null ? "" : tool.strip();
            key_args = key_args == null ? Map.of() : Map.copyOf(key_args);
        }
    }
}
