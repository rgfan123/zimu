package cn.zimu.fulfillment.agent;

import java.util.List;

/**
 * 一条 QUALITY 评测用例（07 决策派生：expected → {@code answer_contains} 片段数组，
 * 答案输出须包含全部片段）。由 {@link AgentEvalCaseRepository} 从 DB 冻结集解析。
 */
public record QualityEvalCase(long id, String input, List<String> answerContains) {

    public QualityEvalCase {
        answerContains = answerContains == null ? List.of() : List.copyOf(answerContains);
    }
}
