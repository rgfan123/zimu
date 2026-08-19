package cn.zimu.fulfillment.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * promptfoo eval 结果解析（meta-agent-platform-impl 09）：{@code --output} JSON 的
 * {@code results[]} 数组（每项 vars.input + outputs[0].score），聚合为用例数/通过数与
 * 逐条得分（不落模型原始输出，details 只存得分）。
 */
public record PromptfooEvalResult(int caseCount, int passedCount, List<Double> scores) {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public PromptfooEvalResult {
        scores = scores == null ? List.of() : List.copyOf(scores);
    }

    /** 解析 promptfoo --output JSON；结构非法抛 {@link IllegalStateException}（拒收即暴露漂移）。 */
    public static PromptfooEvalResult parse(String outputJson) {
        try {
            JsonNode root = MAPPER.readTree(outputJson);
            // promptfoo 0.120 输出结构：{results: {results: [测试结果], stats, ...}}（外层 results 是对象）
            JsonNode results = root.path("results").path("results");
            if (!results.isArray()) {
                throw new IllegalStateException("promptfoo 输出缺少 results.results 数组");
            }
            List<Double> scores = new ArrayList<>();
            int passed = 0;
            for (JsonNode item : results) {
                JsonNode grading = item.path("gradingResult");
                double score = grading.path("score").asDouble(
                        grading.path("pass").asBoolean(false) ? 1.0 : 0.0);
                scores.add(score);
                if (score >= 1.0) {
                    passed++;
                }
            }
            return new PromptfooEvalResult(results.size(), passed, scores);
        } catch (IOException ex) {
            throw new IllegalStateException("promptfoo 结果 JSON 无法解析", ex);
        }
    }
}
