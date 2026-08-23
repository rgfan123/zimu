/**
 * 采购建议卡的纯投影（Issue #110 · ADR 0010）：
 * 把比价 Agent 的运行结果（POST /api/v1/procurement-price-agent/compare，只读）
 * 投影成原型 v-buy 建议卡的视图模型。
 *
 * 三条硬约束在此层落地：
 * 1. 被剔除候选降级展示、绝不静默消失（理由标签 + 可读说明），AI 不藏牌；
 * 2. requires_human 恒为显式标记——建议只是依据，不构成任何业务事实；
 * 3. Agent 的失败是「结果」不是异常（result.error 走稳定错误码路径），与输入非法区分。
 */

import type {
  ProcurementPriceExclusionReason,
  ProcurementPriceRunResult,
} from '@/api/types';

export const EXCLUSION_LABELS: Record<ProcurementPriceExclusionReason, string> = {
  price_outlier: '价格离群',
  price_missing: '价格缺失',
  mapping_stale: '映射失效',
};

const PRICE_BASIS_LABELS: Record<string, string> = {
  sku_commercial_price: 'SKU 主数据进货价',
  provider_sku: '履约方映射价格',
};

export interface SuggestionQuote {
  provider: string;
  price: string;
  basis: string;
  /** 被推荐的候选：视觉上高亮。 */
  recommended: boolean;
  /** 被剔除：降级展示（删除线 + 理由）。 */
  excludedReason: string | null;
  note: string | null;
}

export interface SuggestionCardView {
  ticketId: string;
  ticketNo: string;
  targetSku: string;
  quantity: string | null;
  inventory: { available: string; shortage: string } | null;
  quotes: SuggestionQuote[];
  /** 「为什么提出来」——只陈述事实，不编理由。 */
  why: string;
  /** 「建议」——没有可推荐候选时如实说明，不硬推。 */
  suggestion: string;
  requiresHuman: boolean;
  confidencePercent: number | null;
  /** Agent 留痕：模型 / 提示词版本（agent console 同源）。 */
  provenance: string[];
  /** Agent 结果内错误码（fail-closed）：非空时卡片只显示这条。 */
  errorCode: string | null;
}

function text(value?: string | null): string | null {
  return typeof value === 'string' && value.trim() !== '' ? value.trim() : null;
}

function quantityText(value?: string | null): string {
  return text(value) ?? '—';
}

/** 运行结果 → 建议卡视图模型；畸形候选丢弃但不影响其余展示。 */
export function presentSuggestion(
  ticket: { id: string; ticket_no: string },
  result: ProcurementPriceRunResult,
): SuggestionCardView {
  const recommendation = result.recommendation ?? null;
  const recommendedProvider = recommendation?.recommendation?.provider_code ?? null;

  const quotes: SuggestionQuote[] = [];
  for (const candidate of recommendation?.candidates ?? []) {
    const provider = text(candidate.provider_code);
    if (!provider) continue;
    quotes.push({
      provider,
      price: quantityText(candidate.price),
      basis: candidate.price_basis ? PRICE_BASIS_LABELS[candidate.price_basis] ?? candidate.price_basis : '—',
      recommended: provider === recommendedProvider,
      excludedReason: null,
      note: text(candidate.note),
    });
  }
  for (const candidate of recommendation?.excluded_candidates ?? []) {
    const provider = text(candidate.provider_code);
    if (!provider) continue;
    const label = EXCLUSION_LABELS[candidate.exclusion_reason] ?? candidate.exclusion_reason;
    const detail = text(candidate.exclusion_reason_detail);
    quotes.push({
      provider,
      price: quantityText(candidate.price),
      basis: candidate.price_basis ? PRICE_BASIS_LABELS[candidate.price_basis] ?? candidate.price_basis : '—',
      recommended: false,
      excludedReason: detail ? `${label} · ${detail}` : label,
      note: null,
    });
  }

  const comparable = quotes.filter((quote) => quote.excludedReason === null).length;
  const excluded = quotes.length - comparable;
  const missing = recommendation?.missing_fields ?? [];
  const shortage = text(recommendation?.inventory?.shortage);

  const whyParts: string[] = [];
  if (shortage && shortage !== '0') whyParts.push(`该 SKU 存在缺口 ${shortage}`);
  whyParts.push(`可比候选 ${comparable} 个`);
  if (excluded > 0) whyParts.push(`剔除 ${excluded} 个不可比候选（理由见下）`);
  if (missing.length > 0) whyParts.push(`缺失字段：${missing.join('、')}`);
  const why = `${whyParts.join('，')}。价格与库存均来自系统既有数据，不是模型估的。`;

  const suggestion = recommendation?.recommendation
    ? `${recommendation.recommendation.provider_code}：${recommendation.recommendation.reason}。电话确认后在工单上手填实际成交价——比价价不等于订单价。`
    : comparable > 0
      ? '有可比候选但未产生推荐：请人工比对下方报价后决定。'
      : '无可比候选，系统不硬推：请人工核对价格来源或补齐映射后重试。';

  const provenance = [result.model, result.prompt_version, result.provider]
    .map((value) => text(value))
    .filter((value): value is string => value !== null);

  return {
    ticketId: ticket.id,
    ticketNo: ticket.ticket_no,
    targetSku: text(recommendation?.target_sku) ?? '—',
    quantity: text(recommendation?.requested_quantity),
    inventory: recommendation?.inventory
      ? {
          available: quantityText(recommendation.inventory.available),
          shortage: quantityText(recommendation.inventory.shortage),
        }
      : null,
    quotes,
    why,
    suggestion,
    // 后端 ProcurementPricePolicy.enforce 强制 true；缺字段时按 fail-closed 视为需要人工。
    requiresHuman: recommendation?.requires_human !== false,
    confidencePercent: typeof recommendation?.confidence === 'number'
      ? Math.round(recommendation.confidence * 100)
      : null,
    provenance,
    errorCode: text(result.error),
  };
}
