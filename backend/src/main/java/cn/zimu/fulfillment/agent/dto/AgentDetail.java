package cn.zimu.fulfillment.agent.dto;

import cn.zimu.fulfillment.agent.AgentInputFormat;
import cn.zimu.fulfillment.agent.AgentStatus;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Agent 详情（12 票；agent-console 设计 P2「当前生效」tab 的全部定义事实）。
 *
 * <p>返回代表行（active 版本优先，否则最新版本）的定义全量快照投影：
 * 系统提示词、提示词版本、模型引用、工具白名单（读/写属性）、输出 schema、
 * 守卫豁免（空数组 = 默认守卫全部生效）、确认人与确认时间。密钥/凭据不属于
 * 定义事实，绝不出现在本投影中（模型三元组不在此响应中暴露）。
 */
public record AgentDetail(
        String slug,
        String name,
        String description,
        String systemPrompt,
        String promptVersion,
        String modelRef,
        boolean enabled,
        int version,
        AgentStatus status,
        String activatedBy,
        OffsetDateTime activatedAt,
        boolean allowWrite,
        List<String> guardExemptions,
        JsonNode outputSchema,
        AgentInputFormat inputFormat,
        List<ToolItem> tools) {}
