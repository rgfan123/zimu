package cn.zimu.fulfillment.sku;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 京东件数换算候选解析（jd-real-sdk-switch 03）。 */
class JdPiecesCandidateParserTest {

    @Test
    @DisplayName("识别真实来源名里的乘数，含中文括号、无单位与大写单位形态")
    void parsesRealWorldMultipliers() {
        assertThat(JdPiecesCandidateParser.candidateOrNull("子牧牛肉块500g*2")).isEqualTo(2);
        assertThat(JdPiecesCandidateParser.candidateOrNull("子牧原切眼肉牛排150g*4")).isEqualTo(4);
        assertThat(JdPiecesCandidateParser.candidateOrNull("子牧M8-9和牛上脑烤肉片150*2")).isEqualTo(2);
        assertThat(JdPiecesCandidateParser.candidateOrNull("子牧澳洲谷饲上脑牛肉片1KG*1")).isEqualTo(1);
        assertThat(JdPiecesCandidateParser.candidateOrNull("子牧新西兰羔羊肉卷200g*4（每包2片）")).isEqualTo(4);
        assertThat(JdPiecesCandidateParser.candidateOrNull("子牧羊蝎子500g×2")).isEqualTo(2);
    }

    @Test
    @DisplayName("没有乘数时不猜测，返回 null 而非默认 1 件")
    void neverGuessesWhenNoMultiplierPresent() {
        assertThat(JdPiecesCandidateParser.candidateOrNull("500g")).isNull();
        assertThat(JdPiecesCandidateParser.candidateOrNull("1.2kg")).isNull();
        assertThat(JdPiecesCandidateParser.candidateOrNull("小龙坎火锅拼盘（肉类6拼）1.2KG")).isNull();
        assertThat(JdPiecesCandidateParser.candidateOrNull((String) null)).isNull();
        assertThat(JdPiecesCandidateParser.candidateOrNull("", "   ")).isNull();
    }

    @Test
    @DisplayName("按参数顺序取第一个可解析来源，前序为空或无乘数时才看后续")
    void honoursSourcePriorityOrder() {
        assertThat(JdPiecesCandidateParser.candidateOrNull("500g*2", "名称*8", "规格*9")).isEqualTo(2);
        assertThat(JdPiecesCandidateParser.candidateOrNull(null, null, "500g*2")).isEqualTo(2);
        assertThat(JdPiecesCandidateParser.candidateOrNull("", "  ", "400g*2")).isEqualTo(2);
    }
}
