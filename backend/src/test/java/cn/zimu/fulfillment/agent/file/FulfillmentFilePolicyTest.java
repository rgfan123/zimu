package cn.zimu.fulfillment.agent.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.zimu.fulfillment.batch.ImportBatchProgress;
import cn.zimu.fulfillment.common.error.BusinessException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 履约单据 Agent 的确定性收口：模型输出**被事实覆盖**而不是被信任。
 *
 * <p>每条断言都对应一种「模型说得头头是道但会误导人」的情形——自然语言里这些错
 * 毫无破绽，只能靠确定性策略挡住。
 */
class FulfillmentFilePolicyTest {

    // ---------- 输入：非法输入不进入模型 ----------

    @Test
    void malformedInputIsRejectedBeforeSpendingATokenOnIt() {
        for (String bad : new String[] {
            null, "", "   ", "not json", "[]", "{}", "{\"import_batch_id\":null}",
            "{\"import_batch_id\":\"abc\"}", "{\"import_batch_id\":\"0\"}", "{\"import_batch_id\":\"-3\"}"
        }) {
            assertThatThrownBy(() -> FulfillmentFileInput.parse(bad))
                    .as("input=%s", bad)
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("");
        }
    }

    @Test
    void numericAndTextualIdsAreBothAccepted() {
        assertThat(FulfillmentFileInput.parse("{\"import_batch_id\":\"42\"}").importBatchId()).isEqualTo(42);
        assertThat(FulfillmentFileInput.parse("{\"import_batch_id\":42}").importBatchId()).isEqualTo(42);
    }

    // ---------- 收口：事实覆盖模型转述 ----------

    @Test
    void businessNumberFromTheModelIsOverwrittenByTheFact() {
        ImportBatchProgress progress = progress(0, 0, 0, 0);
        FulfillmentFileAssessment raw = new FulfillmentFileAssessment(
                "BATCH-模型抄错了", "回传", "摘要", List.of(),
                List.of(new FulfillmentFileAssessment.SuggestedAction("看一眼", "无", null)),
                false, List.of());

        FulfillmentFileAssessment enforced = FulfillmentFilePolicy.enforce(raw, progress);

        // 抄错的批次号会让运营去后台搜一个不存在的单子，而这错在自然语言里看不出来
        assertThat(enforced.batchNo()).isEqualTo("BATCH-REAL");
        assertThat(enforced.currentStage()).isEqualTo(progress.currentStage());
    }

    @Test
    void blockedChainForcesRequiresHumanEvenIfTheModelSaysOtherwise() {
        // 有 3 项阻塞，模型却说不需要人
        ImportBatchProgress progress = progress(10, 7, 3, 0);
        FulfillmentFileAssessment raw = new FulfillmentFileAssessment(
                "BATCH-REAL", null, "一切正常", List.of(),
                List.of(new FulfillmentFileAssessment.SuggestedAction("无需处理", "看起来能自动继续", null)),
                false, List.of());

        assertThat(FulfillmentFilePolicy.enforce(raw, progress).requiresHuman()).isTrue();
    }

    @Test
    void emptySuggestionsOnAnUnfinishedChainGetADeterministicFallback() {
        ImportBatchProgress progress = progress(10, 7, 3, 0);
        FulfillmentFileAssessment raw = new FulfillmentFileAssessment(
                "BATCH-REAL", null, "摘要", List.of(), List.of(), true, List.of());

        FulfillmentFileAssessment enforced = FulfillmentFilePolicy.enforce(raw, progress);
        // 「没卡住也没建议」的空白比一条保守建议更糟
        assertThat(enforced.suggestedActions()).hasSize(1);
        assertThat(enforced.suggestedActions().getFirst().targetNo()).isEqualTo("BATCH-REAL");
    }

    @Test
    void blankSummaryFallsBackToPlainFacts() {
        ImportBatchProgress progress = progress(10, 7, 3, 0);
        FulfillmentFileAssessment raw = new FulfillmentFileAssessment(
                "BATCH-REAL", null, "   ", List.of(), List.of(), true, List.of());

        String summary = FulfillmentFilePolicy.enforce(raw, progress).summary();
        assertThat(summary).contains("BATCH-REAL").contains("3 项待人工处理");
    }

    @Test
    void nullModelOutputStillProducesUsableFacts() {
        ImportBatchProgress progress = progress(10, 7, 3, 0);
        FulfillmentFileAssessment enforced = FulfillmentFilePolicy.enforce(null, progress);
        assertThat(enforced.batchNo()).isEqualTo("BATCH-REAL");
        assertThat(enforced.requiresHuman()).isTrue();
        assertThat(enforced.summary()).isNotBlank();
    }

    // ---------- 段语义：未接入 ≠ 0 ----------

    @Test
    void unsupportedStageIsNeverCountedAsComplete() {
        ImportBatchProgress.Stage stage = ImportBatchProgress.Stage.unsupported("回传");
        // 「该段不适用」被当成「已完成」，整条链路就会假装走完了
        assertThat(stage.complete()).isFalse();
        assertThat(stage.supported()).isFalse();
    }

    @Test
    void zeroTotalStageIsNotCompleteEither() {
        // total=0 时说「已完成」等于宣称一件根本没发生的事做完了
        assertThat(new ImportBatchProgress.Stage("发货", 0, 0, 0, true).complete()).isFalse();
    }

    @Test
    void currentStageIsTheEarliestUnfinishedOne() {
        ImportBatchProgress progress = progress(10, 10, 0, 0);
        // 收表已完成，发货段未接入 → 卡在发货
        assertThat(progress.currentStage()).isEqualTo("发货");
    }

    // ------------------------------------------------------------------

    private static ImportBatchProgress progress(int total, int done, int blocked, int returned) {
        return new ImportBatchProgress(
                1,
                "BATCH-REAL",
                "SOURCE_ORDER",
                "CAISHIXIAN",
                "COMPLETED",
                1,
                null,
                null,
                new ImportBatchProgress.Stage("收表", total, done, blocked, true),
                ImportBatchProgress.Stage.unsupported("发货"),
                ImportBatchProgress.Stage.unsupported("回填"),
                new ImportBatchProgress.Stage("回传", returned, returned, 0, returned > 0),
                List.of());
    }
}
