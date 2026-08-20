package cn.zimu.fulfillment.agent.dto;

import cn.zimu.fulfillment.agent.AgentOutcome;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;

/**
 * 运行记录列表行（12 票；agent-console 设计 P3）。
 *
 * <p>{@code status} 为 DB 生命周期状态（RUNNING/SUCCESS/FAILED）；{@code outcome}
 * 为 04 决策的结果维度（SUCCESS/REJECTED/FAILED；运行中为 null）——由
 * (status, error_type) 派生：FAILED+PII_GUARDED=REJECTED（守卫拒绝转人工），
 * 其余 FAILED=FAILED（NEEDS_INPUT 与 SUCCESS 行级同形，见 {@code AgentRunReadService}）。
 * {@code modelMetadata} 为服务端 allowlist 投影（区分未公开/未配置）；
 * {@code tokenUsage} 为 token 用量对象（数字，无敏感内容）。
 */
public record RunListItem(
        String runId,
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
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt) {}
