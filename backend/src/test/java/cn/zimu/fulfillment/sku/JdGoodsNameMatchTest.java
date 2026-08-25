package cn.zimu.fulfillment.sku;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zimu.fulfillment.sku.JdGoodsNameMatch.Verdict;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 名称比对内核是核对（advisory）与出库门禁（fail-closed）共用的唯一实现，
 * 这里锁死三值裁决语义：MATCHED / MISMATCHED / NO_REFERENCE 必须显式区分，
 * 「没有参照名」不允许被吞成任何一种比对结论。
 */
class JdGoodsNameMatchTest {

    @Test
    void exactMatchAfterWhitespaceNormalization() {
        assertThat(JdGoodsNameMatch.verdict("子牧羊小腿 500g/盒", List.of("子牧羊小腿500g/盒")))
                .isEqualTo(Verdict.MATCHED);
    }

    @Test
    void containmentMatchesInBothDirections() {
        assertThat(JdGoodsNameMatch.verdict("子牧羊小腿 500g/盒", List.of("羊小腿")))
                .isEqualTo(Verdict.MATCHED);
        assertThat(JdGoodsNameMatch.verdict("羊小腿", List.of("子牧羊小腿 500g/盒")))
                .isEqualTo(Verdict.MATCHED);
    }

    @Test
    void anyReferenceHitWins() {
        assertThat(JdGoodsNameMatch.verdict("子牧羊小腿 500g/盒", Arrays.asList("标准箱", "羊小腿")))
                .isEqualTo(Verdict.MATCHED);
    }

    @Test
    void noHitAcrossAllReferencesIsMismatched() {
        assertThat(JdGoodsNameMatch.verdict("子牧羊小腿 500g/盒", List.of("标准箱", "牛腱礼盒")))
                .isEqualTo(Verdict.MISMATCHED);
    }

    @Test
    void nullAndBlankReferencesDoNotCountAsReferences() {
        assertThat(JdGoodsNameMatch.verdict("子牧羊小腿 500g/盒", Arrays.asList(null, "", "   ")))
                .isEqualTo(Verdict.NO_REFERENCE);
    }

    @Test
    void blankReferenceIsSkippedButRealReferenceStillJudges() {
        assertThat(JdGoodsNameMatch.verdict("子牧羊小腿 500g/盒", Arrays.asList("  ", "标准箱")))
                .isEqualTo(Verdict.MISMATCHED);
    }

    /** 固化历史语义：远端名为空白时会命中任意参照名的 contains("")，视为 MATCHED，不在本票改动。 */
    @Test
    void blankRemoteNameKeepsLegacyContainsSemantics() {
        assertThat(JdGoodsNameMatch.verdict("   ", List.of("标准箱")))
                .isEqualTo(Verdict.MATCHED);
    }
}
