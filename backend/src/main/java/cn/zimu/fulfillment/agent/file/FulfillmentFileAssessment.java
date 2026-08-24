package cn.zimu.fulfillment.agent.file;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * 履约单据 Agent 的输出（与 V56 定义的 output_schema 同构）。
 *
 * <p>注意这里全部是**解读与建议**，没有一个字段是业务事实的真源。四段数字的真源是
 * {@code ImportBatchProgressService} 的 SQL 投影；这份输出即使全错，也不会改变任何
 * 业务状态——这正是「不让 LLM 决定确定性业务结果」的落点。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FulfillmentFileAssessment(
        @JsonProperty("batch_no") String batchNo,
        @JsonProperty("current_stage") String currentStage,
        String summary,
        @JsonProperty("stage_notes") List<StageNote> stageNotes,
        @JsonProperty("suggested_actions") List<SuggestedAction> suggestedActions,
        @JsonProperty("requires_human") Boolean requiresHuman,
        @JsonProperty("missing_fields") List<String> missingFields) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StageNote(String stage, String note) {}

    /**
     * 一条建议。
     *
     * @param action   建议做什么（不得是「我已经…」——Agent 不执行）
     * @param reason   依据哪条事实
     * @param targetNo 可去后台搜的业务号；没有则 null
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SuggestedAction(
            String action, String reason, @JsonProperty("target_no") String targetNo) {}
}
