package cn.zimu.fulfillment.agent.dto;

import cn.zimu.fulfillment.agent.AgentStatus;
import java.time.OffsetDateTime;

/**
 * 版本链节点（12 票；agent-console 设计 P2「版本链」时间线）。
 *
 * <p>每个版本一个节点：version / status（draft/active/retired，无回边）/ 确认事实
 * （activated_by/activated_at，与 status='active' 同事务，draft 行为 null）。
 */
public record AgentVersionItem(
        int version, AgentStatus status, String activatedBy, OffsetDateTime activatedAt) {}
