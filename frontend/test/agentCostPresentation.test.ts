import assert from 'node:assert/strict';
import test from 'node:test';
import {
  COST_DEFAULT_GROUP_BY,
  averageOrNull,
  costFiltersFromParams,
  costLocation,
  costSearchParams,
  coverageNote,
  formatDuration,
  formatTokens,
  measuredRuns,
} from '../src/pages/agents/agentPresentation.ts';
import type { TokenUsageSummaryItem } from '../src/api/agentTypes.ts';

function item(overrides: Partial<TokenUsageSummaryItem> = {}): TokenUsageSummaryItem {
  return {
    group_key: 'procurement-price-agent',
    runs: 10,
    failed_runs: 1,
    runs_without_token_usage: 0,
    over_threshold_runs: 0,
    prompt_tokens: 4000,
    completion_tokens: 500,
    total_tokens: 4500,
    max_run_total_tokens: 900,
    model_calls: 25,
    total_latency_ms: 12_000,
    max_run_latency_ms: 3_000,
    ...overrides,
  };
}

// ---------- URL 往返：刷新与分享必须复现同一视图 ----------

test('costFiltersFromParams 缺省时归一为 AGENT + LIVE', () => {
  const filters = costFiltersFromParams(new URLSearchParams());
  assert.equal(filters.groupBy, COST_DEFAULT_GROUP_BY);
  assert.equal(filters.runMode, undefined);
});

test('显式 LIVE 与缺省同义，不写进 URL', () => {
  const filters = costFiltersFromParams(new URLSearchParams('run_mode=LIVE'));
  assert.equal(filters.runMode, undefined);
  assert.equal(costSearchParams(filters).has('run_mode'), false);
});

test('PREVIEW 必须显式落进 URL —— 草稿试跑的视图不能被静默还原成 LIVE', () => {
  const filters = costFiltersFromParams(new URLSearchParams('run_mode=PREVIEW'));
  assert.equal(filters.runMode, 'PREVIEW');
  assert.equal(costSearchParams(filters).get('run_mode'), 'PREVIEW');
});

test('非法 group_by 回落默认值而不是原样透传给后端', () => {
  const filters = costFiltersFromParams(new URLSearchParams('group_by=DROP+TABLE'));
  assert.equal(filters.groupBy, 'AGENT');
});

test('筛选 URL 往返幂等', () => {
  const original = costFiltersFromParams(
    new URLSearchParams(
      'slug=procurement-price-agent&group_by=DAY&run_mode=PREVIEW' +
        '&business_entity_type=PROCUREMENT_TICKET&started_from=2026-08-01T00:00:00%2B08:00' +
        '&started_to=2026-08-25T00:00:00%2B08:00',
    ),
  );
  const roundTripped = costFiltersFromParams(costSearchParams(original));
  assert.deepEqual(roundTripped, original);
});

test('costLocation 无参数时不带问号', () => {
  assert.equal(costLocation({ groupBy: 'AGENT' }), '/agents/cost');
  assert.equal(costLocation({ groupBy: 'DAY' }), '/agents/cost?group_by=DAY');
});

// ---------- 诚实字段：下界不能被读成全量 ----------

test('全部已计量时不报「下界」', () => {
  const note = coverageNote(item());
  assert.equal(note.partial, false);
  assert.match(note.label, /全部已计量/);
});

test('有未计量运行时必须说出求和是下界', () => {
  const note = coverageNote(item({ runs: 10, runs_without_token_usage: 3 }));
  assert.equal(note.partial, true);
  assert.match(note.label, /3 次无计量/);
  assert.match(note.label, /下界/);
});

test('零运行时说「无运行记录」而不是报下界', () => {
  const note = coverageNote(item({ runs: 0, runs_without_token_usage: 0 }));
  assert.equal(note.partial, false);
  assert.equal(note.label, '无运行记录');
});

test('measuredRuns 是求和的实际分母', () => {
  assert.equal(measuredRuns(item({ runs: 10, runs_without_token_usage: 3 })), 7);
  // 脏数据（未计量数大于运行数）不产生负数分母
  assert.equal(measuredRuns(item({ runs: 2, runs_without_token_usage: 5 })), 0);
});

// ---------- 均值：分母为 0 时「没有均值」≠「均值是 0」 ----------

test('分母为 0 返回 null 而不是 0', () => {
  assert.equal(averageOrNull(4500, 0), null);
  assert.equal(formatTokens(averageOrNull(4500, 0)), '—');
});

test('每次运行均耗与每轮均耗是两个口径', () => {
  const summary = item({ total_tokens: 4500, runs: 10, runs_without_token_usage: 0, model_calls: 25 });
  assert.equal(averageOrNull(summary.total_tokens, measuredRuns(summary)), 450);
  assert.equal(averageOrNull(summary.total_tokens, summary.model_calls), 180);
});

test('未计量运行不进均值分母 —— 否则均耗会被稀释', () => {
  const summary = item({ total_tokens: 4500, runs: 10, runs_without_token_usage: 5 });
  assert.equal(averageOrNull(summary.total_tokens, measuredRuns(summary)), 900);
});

// ---------- 格式化 ----------

test('formatTokens 千分位；null 显示破折号而不是 0', () => {
  assert.equal(formatTokens(1234567), '1,234,567');
  assert.equal(formatTokens(0), '0');
  assert.equal(formatTokens(null), '—');
  assert.equal(formatTokens(undefined), '—');
});

test('formatDuration 按量级进位', () => {
  assert.equal(formatDuration(null), '—');
  assert.equal(formatDuration(999), '999 ms');
  assert.equal(formatDuration(1500), '1.5 s');
  assert.equal(formatDuration(90_000), '1 分 30 秒');
  assert.equal(formatDuration(3_930_000), '1 时 5 分');
});
