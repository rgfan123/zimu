package cn.zimu.fulfillment.agent.dto;

import java.util.List;

/**
 * Agent 列表行（12 票；agent-console 设计 P1）。
 *
 * <p>{@code state} 为 status×enabled 组合的运行状态；{@code currentVersion} 为当前生效
 * 版本号（无 active 版本时为 null）；{@code enabled}/{@code allowWrite}/{@code tools}/
 * {@code modelRef}/{@code promptVersion} 取自代表行（active 版本优先，否则最新版本）；
 * {@code draftCount} 为该 slug 的待确认草稿数；{@code sevenDayRunCount}/
 * {@code sevenDayFailureCount} 为近 7 日 {@code run_mode='LIVE'} 的运行统计
 * （PREVIEW 草稿试跑不污染）。
 */
public record AgentListItem(
        String slug,
        String name,
        AgentListState state,
        boolean enabled,
        Integer currentVersion,
        long draftCount,
        long sevenDayRunCount,
        long sevenDayFailureCount,
        boolean allowWrite,
        String modelRef,
        String promptVersion,
        List<ToolItem> tools) {}
