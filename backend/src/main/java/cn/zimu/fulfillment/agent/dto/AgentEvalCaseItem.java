package cn.zimu.fulfillment.agent.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;

/**
 * 评测用例投影（12 票；agent-console 设计 P5）。
 *
 * <p>绑定 (agent_slug, agent_version) 冻结集：case id（即 DB 主键）、metric_kind
 * （INVARIANT/QUALITY，前端按确定性门禁 vs 质量评测分组）、input/expected（JSONB
 * 原文，管理控制台按设计展示输入与期望）、状态（PENDING/CONFIRMED）与确认事实。
 */
public record AgentEvalCaseItem(
        long id,
        String agentSlug,
        int agentVersion,
        String metricKind,
        JsonNode input,
        JsonNode expected,
        String status,
        String createdBy,
        String confirmedBy,
        OffsetDateTime confirmedAt) {}
