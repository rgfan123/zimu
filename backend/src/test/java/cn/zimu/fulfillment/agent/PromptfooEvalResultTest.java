package cn.zimu.fulfillment.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * 09 — promptfoo 结果解析（meta-agent-platform-impl 09）：聚合用例数/通过数/逐条得分，
 * 结构非法拒收。纯单元测试。
 */
class PromptfooEvalResultTest {

    @Test
    void parsesResultsWithScores() {
        // promptfoo 0.120 实际输出结构：{results: {results: [...]}}
        String output = """
                {"results":{"results":[
                  {"gradingResult":{"pass":true,"score":1}},
                  {"gradingResult":{"pass":false,"score":0}},
                  {"gradingResult":{"pass":true,"score":1}}
                ]}}
                """;

        PromptfooEvalResult result = PromptfooEvalResult.parse(output);

        assertThat(result.caseCount()).isEqualTo(3);
        assertThat(result.passedCount()).isEqualTo(2);
        assertThat(result.scores()).containsExactly(1.0, 0.0, 1.0);
    }

    @Test
    void emptyResultsYieldsZeroCounts() {
        PromptfooEvalResult result = PromptfooEvalResult.parse("{\"results\":{\"results\":[]}}");

        assertThat(result.caseCount()).isZero();
        assertThat(result.passedCount()).isZero();
        assertThat(result.scores()).isEmpty();
    }

    @Test
    void malformedOutputIsRejected() {
        assertThatThrownBy(() -> PromptfooEvalResult.parse("not json"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> PromptfooEvalResult.parse("{\"results\":\"oops\"}"))
                .isInstanceOf(IllegalStateException.class);
    }
}
