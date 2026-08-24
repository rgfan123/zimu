package cn.zimu.fulfillment.agent.meta;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * 一次 meta-agent 对话的结局（agent-console 06）。
 *
 * <p>三种结局各有独立呈现，不折叠成「成功/失败」两态：NEEDS_INPUT 是正常的一步，
 * 把它显示成失败会让人以为 Agent 坏了，而实际上系统只是在等一个它不该猜的答案。
 *
 * @param outcome         SUCCESS / NEEDS_INPUT / REJECTED / FAILED
 * @param runId           关联 agent_runs 与审计，双向可追溯
 * @param agentSlug       草稿 slug（SUCCESS 时非空）
 * @param draftVersion    草稿版本（SUCCESS 时非空）
 * @param draftEnabled    草稿的 enabled 字段。**始终只是草稿上的一个值，不代表已启用**
 * @param questions       澄清问题（NEEDS_INPUT 时非空）
 * @param rejectionReason 拒绝理由，必须可操作——告诉用户下一步该做什么
 * @param error           稳定失败码（FAILED 时非空）
 * @param raw             模型原始输出，供界面右侧实时预览草稿
 */
public record MetaAgentOutcome(
        String outcome,
        String runId,
        String agentSlug,
        Integer draftVersion,
        Boolean draftEnabled,
        List<String> questions,
        String rejectionReason,
        String error,
        JsonNode raw) {

    public static final String SUCCESS = "SUCCESS";
    public static final String NEEDS_INPUT = "NEEDS_INPUT";
    public static final String REJECTED = "REJECTED";
    public static final String FAILED = "FAILED";
}
