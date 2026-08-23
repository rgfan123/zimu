import assert from 'node:assert/strict';
import test from 'node:test';
import { presentSuggestion } from '../src/pages/workbench/procurementSuggestion.ts';
import type { ProcurementPriceRunResult } from '../src/api/types.ts';

/**
 * ADR 0010 / spec #120：建议卡投影的三条硬约束——
 * 被剔除候选降级展示不消失、requires_human 恒显式、Agent 失败是结果不是异常。
 */

const TICKET = { id: '9001', ticket_no: 'PT-2026-0823-01' };

function runResult(overrides: Partial<ProcurementPriceRunResult> = {}): ProcurementPriceRunResult {
  return {
    provider: 'deepseek',
    model: 'deepseek-chat',
    prompt_version: 'v3',
    recommendation: {
      target_sku: 'SKU-JD-000073',
      requested_quantity: '640',
      inventory: { available: '180', shortage: '460' },
      candidates: [
        { provider_code: 'JD_CLOUD', price: '52.40', price_basis: 'provider_sku', note: null },
        { provider_code: 'THIRD_PARTY', price: '49.90', price_basis: 'sku_commercial_price', note: null },
      ],
      excluded_candidates: [
        {
          provider_code: 'OUTLIER_CO',
          price: '9.90',
          price_basis: 'provider_sku',
          exclusion_reason: 'price_outlier',
          exclusion_reason_detail: '低于同规格均价 80%',
        },
      ],
      recommendation: { provider_code: 'THIRD_PARTY', reason: '同规格最低可比价' },
      missing_fields: [],
      confidence: 0.82,
      requires_human: true,
    },
    error: null,
    ...overrides,
  };
}

test('被剔除候选降级展示且带理由，绝不静默消失（AI 不藏牌）', () => {
  const view = presentSuggestion(TICKET, runResult());

  assert.equal(view.quotes.length, 3, '可比 2 + 被剔除 1 全部在列');
  const excluded = view.quotes.find((quote) => quote.provider === 'OUTLIER_CO');
  assert.ok(excluded, '被剔除候选必须仍然出现');
  assert.match(excluded.excludedReason ?? '', /价格离群/, '剔除理由标签可见');
  assert.match(excluded.excludedReason ?? '', /低于同规格均价 80%/, '可读说明可见');
  assert.match(view.why, /剔除 1 个不可比候选/);
});

test('推荐候选被标记，建议文案强调比价价≠订单价', () => {
  const view = presentSuggestion(TICKET, runResult());

  const recommended = view.quotes.filter((quote) => quote.recommended);
  assert.equal(recommended.length, 1);
  assert.equal(recommended[0].provider, 'THIRD_PARTY');
  assert.match(view.suggestion, /THIRD_PARTY/);
  assert.match(view.suggestion, /比价价不等于订单价/, 'ADR 0003/0010 红线必须写在卡上');
  assert.equal(view.requiresHuman, true);
  assert.equal(view.confidencePercent, 82);
  assert.deepEqual(view.provenance, ['deepseek-chat', 'v3', 'deepseek'], 'Agent 留痕可见');
});

test('无可比候选时不硬推，如实说明', () => {
  const result = runResult();
  result.recommendation!.candidates = [];
  result.recommendation!.recommendation = null;
  result.recommendation!.missing_fields = ['provider_sku_price'];

  const view = presentSuggestion(TICKET, result);
  assert.match(view.suggestion, /无可比候选，系统不硬推/);
  assert.match(view.why, /缺失字段：provider_sku_price/);
});

test('Agent 失败是结果不是异常：稳定错误码单独呈现', () => {
  const view = presentSuggestion(TICKET, runResult({ recommendation: null, error: 'MODEL_NOT_CONFIGURED' }));

  assert.equal(view.errorCode, 'MODEL_NOT_CONFIGURED');
  assert.equal(view.quotes.length, 0);
  assert.equal(view.requiresHuman, true, 'fail-closed：拿不到结论时仍标记需人工');
});

test('requires_human 缺省按 fail-closed 视为需要人工', () => {
  const result = runResult();
  delete (result.recommendation as Record<string, unknown>).requires_human;
  assert.equal(presentSuggestion(TICKET, result).requiresHuman, true);
});
