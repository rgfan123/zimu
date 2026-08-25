import assert from 'node:assert/strict';
import { after, afterEach, before, test } from 'node:test';
import { control, createRouteHarness, jsonResponse, type RouteHarness } from './routeHarness.ts';

/**
 * 履约单据助手（/agents/fulfillment-file）契约回归。
 *
 * 这页曾整页白屏：后端 `JacksonConfig` 全局 SNAKE_CASE、`ImportBatchProgress` 无
 * `@JsonProperty` 覆写，线上是 `source_return`，而前端类型与读取点写的是 `sourceReturn`，
 * 于是 `progress.sourceReturn.complete` 抛 TypeError 整树卸载。单词字段（status/intake/
 * outbound/tracking）恰好两边同名，掩盖了另一半问题——所以下面的 fixture **必须逐字**
 * 用后端真实键名，键集由 `ImportBatchProgressJsonContractTest` /
 * `FulfillmentFileRunResultJsonContractTest` 钉死；谁把前端改回 camelCase，这里就会崩。
 */

let harness: RouteHarness;

before(async () => {
  harness = await createRouteHarness('http://localhost/agents/fulfillment-file');
});

afterEach(async () => {
  await harness.unmount();
});

after(async () => {
  await harness.close();
});

/** 段进度：线上恒返回 name/total/done/blocked/supported 五个键，且**没有 complete**。 */
function stage(
  name: string,
  overrides: { total?: number; done?: number; blocked?: number; supported?: boolean } = {},
) {
  return {
    name,
    total: overrides.total ?? 0,
    done: overrides.done ?? 0,
    blocked: overrides.blocked ?? 0,
    supported: overrides.supported ?? false,
  };
}

/** 进行中的批次：收表段卡 2 行，后三段尚未接入。 */
function progressPayload() {
  return {
    batch_id: 7,
    batch_no: 'IB-20260825-001',
    batch_type: 'SOURCE_ORDER',
    source_channel: 'JD',
    status: 'PROCESSED',
    revision_no: 1,
    received_at: '2026-08-25T02:00:00Z',
    processed_at: '2026-08-25T03:00:00Z',
    intake: stage('收表', { total: 10, done: 8, blocked: 2, supported: true }),
    outbound: stage('发货'),
    tracking: stage('回填'),
    source_return: stage('回传'),
    blockers: [{ stage: '收表', code: 'SKU_MAPPING_REQUIRED', count: 2, sample_no: 'SO-1' }],
  };
}

/** 四段全部接入且走完；线上没有 complete 字段，界面必须自己按规则派生。 */
function completedProgressPayload() {
  return {
    ...progressPayload(),
    intake: stage('收表', { total: 10, done: 10, supported: true }),
    outbound: stage('发货', { total: 10, done: 10, supported: true }),
    tracking: stage('回填', { total: 10, done: 10, supported: true }),
    source_return: stage('回传', { total: 10, done: 10, supported: true }),
    blockers: [],
  };
}

function runResultPayload() {
  return {
    progress: progressPayload(),
    assessment: {
      batch_no: 'IB-20260825-001',
      current_stage: '收表',
      summary: '收表段有两行缺 SKU 映射，后三段尚未接入。',
      stage_notes: [{ stage: '收表', note: '2 行待补映射' }],
      suggested_actions: [
        { action: '补齐来源商品 SKU 映射', reason: 'SKU_MAPPING_REQUIRED 共 2 条', target_no: 'SO-1' },
      ],
      requires_human: true,
      missing_fields: ['来源结算价'],
    },
    provider: 'deepseek',
    model: 'deepseek-chat',
    prompt_version: 'v56',
    error: null,
  };
}

/** 输入批次 ID 并触发指定动作；两个按钮在 ID 非法时都是 disabled。 */
async function query(action: '查看进度' | '让 Agent 解读', batchId = '7') {
  await harness.waitFor(() => assert.match(harness.bodyText(), /履约单据助手/));
  const input = document.querySelector<HTMLInputElement>('input[aria-label="导入批次 ID"]');
  assert.ok(input, 'batch id input must be present');
  const setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value')?.set;
  setter?.call(input, batchId);
  await harness.dispatchEvent(input, new Event('input', { bubbles: true }));
  await harness.dispatchEvent(control(action), new MouseEvent('click', { bubbles: true }));
}

test('progress renders from the snake_case wire shape instead of blanking the page', async () => {
  const requests: string[] = [];
  globalThis.fetch = async (input, init) => {
    const url = String(input);
    requests.push(`${init?.method ?? 'GET'} ${url}`);
    if (url === '/api/v1/import-batches/7/progress') return jsonResponse(progressPayload());
    throw new Error(`unexpected request: ${url}`);
  };

  await harness.mount(['/agents/fulfillment-file']);
  await query('查看进度');

  await harness.waitFor(() => assert.match(harness.bodyText(), /IB-20260825-001/));
  assert.ok(requests.includes('GET /api/v1/import-batches/7/progress'));

  const text = harness.bodyText();
  // 页面还在（camelCase 读法会让整树卸载，body 只剩空壳）
  assert.match(text, /履约单据助手/);
  assert.match(text, /JD/, 'source_channel must render, not fall back to 未标注');
  assert.match(text, /8 \/ 10/, 'done/total come straight off the wire');
  assert.match(text, /2 项卡住/);
  // 阻塞表的 sample_no 列
  assert.match(text, /SKU_MAPPING_REQUIRED/);
  assert.match(text, /SO-1/);
  // 未接入的三段如实标注，绝不渲染成 0 待办
  assert.match(text, /该段暂不适用/);
  assert.doesNotMatch(text, /undefined/, 'no field may leak as undefined');
});

test('all four stages complete is derived client-side because the wire carries no complete flag', async () => {
  globalThis.fetch = async (input) => {
    const url = String(input);
    if (url === '/api/v1/import-batches/7/progress') return jsonResponse(completedProgressPayload());
    throw new Error(`unexpected request: ${url}`);
  };

  await harness.mount(['/agents/fulfillment-file']);
  await query('查看进度');

  await harness.waitFor(() => assert.match(harness.bodyText(), /四段已走完/));
  assert.match(harness.bodyText(), /已完成/, 'each finished stage card carries its own tag');
});

test('agent assessment renders from the snake_case wire shape', async () => {
  const requests: string[] = [];
  globalThis.fetch = async (input, init) => {
    const url = String(input);
    requests.push(`${init?.method ?? 'GET'} ${url}`);
    if (url === '/api/v1/import-batches/7/assessment') return jsonResponse(runResultPayload());
    throw new Error(`unexpected request: ${url}`);
  };

  await harness.mount(['/agents/fulfillment-file']);
  await query('让 Agent 解读');

  await harness.waitFor(() => assert.match(harness.bodyText(), /收表段有两行缺 SKU 映射/));
  assert.ok(requests.includes('POST /api/v1/import-batches/7/assessment'));

  const text = harness.bodyText();
  assert.match(text, /需要人工/, 'requires_human drives the tag');
  assert.match(text, /v56/, 'prompt_version renders in the card header');
  assert.match(text, /2 行待补映射/, 'stage_notes render');
  assert.match(text, /补齐来源商品 SKU 映射/, 'suggested_actions render');
  assert.match(text, /SO-1/, 'suggested action target_no renders');
  assert.match(text, /缺少：来源结算价/, 'missing_fields render');
  // 解读成功时也照常给出确定性事实
  assert.match(text, /IB-20260825-001/);
  assert.doesNotMatch(text, /undefined/, 'no field may leak as undefined');
});
