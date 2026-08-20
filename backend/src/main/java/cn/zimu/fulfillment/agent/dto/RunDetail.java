package cn.zimu.fulfillment.agent.dto;

import cn.zimu.fulfillment.agent.AgentOutcome;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 运行记录详情（12 票；agent-console 设计 P4；13 票 202 任务轮询复用面）。
 *
 * <p>列表行字段之外补充：thread_id、输入 SHA-256 摘要（{@code input_digest}——
 * 08 票隐私设计：输入原文不留存，只有摘要可比对，界面如实说明而非显示空白）、
 * 工具调用序列（{@code toolCalls}，按序号升序）与关联评测结果摘要
 * （{@code evalResult}，仅 QUALITY PREVIEW 评测行存在）。
 */
public record RunDetail(
        String runId,
        String threadId,
        String agentSlug,
        String agentVersion,
        String status,
        AgentOutcome outcome,
        String runMode,
        String errorType,
        Long latencyMs,
        JsonNode tokenUsage,
        String businessEntityType,
        String businessEntityId,
        String intent,
        ModelMetadataItem modelMetadata,
        String inputDigest,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt,
        List<RunToolCallItem> toolCalls,
        RunEvalResultItem evalResult) {}
