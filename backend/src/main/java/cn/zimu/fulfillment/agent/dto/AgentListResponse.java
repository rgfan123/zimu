package cn.zimu.fulfillment.agent.dto;

import java.util.List;

/**
 * Agent 列表响应（12 票）：一行一个 slug，一次拿全 P1 需要的全部聚合
 * （当前生效版本、待确认草稿数、近 7 日 LIVE 运行次数与失败数），防前端 N+1。
 */
public record AgentListResponse(List<AgentListItem> items) {}
