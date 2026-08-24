package cn.zimu.fulfillment.agent.dto;

import java.util.List;

/**
 * 运行记录列表响应（12 票）：items 按 started_at 降序（新→旧），total 为过滤后的总数
 * （分页用）。默认只返回 LIVE（run_mode=PREVIEW 需显式请求——草稿试跑不污染对线上
 * 行为的判断）。
 */
public record RunListResponse(List<RunListItem> items, long total) {}
