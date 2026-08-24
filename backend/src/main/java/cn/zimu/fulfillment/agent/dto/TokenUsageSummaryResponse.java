package cn.zimu.fulfillment.agent.dto;

import java.util.List;

/**
 * 消耗汇总响应（129 票）。
 *
 * @param groupBy 生效的分组维度（回显，供前端确认 URL 参数已生效）
 * @param runMode 生效的运行模式；默认 LIVE——PREVIEW 是草稿试跑，混进成本视图会
 *                让「线上花了多少」这个问题失去意义
 * @param items   各分组明细，按 total_tokens 降序（最贵的在最上面）
 * @param totals  全量合计行；groupKey 为空串
 */
public record TokenUsageSummaryResponse(
        String groupBy, String runMode, List<TokenUsageSummaryItem> items, TokenUsageSummaryItem totals) {}
