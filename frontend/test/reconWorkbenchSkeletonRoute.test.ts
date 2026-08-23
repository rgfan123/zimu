import assert from 'node:assert/strict';
import test, { after, afterEach, before } from 'node:test';
import { createRouteHarness, jsonResponse, page, type RouteHarness } from './routeHarness.ts';
import { addDecimal, aggregateChannelMetrics } from '../src/pages/workbench/reconSkeleton.ts';

/**
 * 对账台月度骨架（v-fin，ADR 0005/D15）：分平台数量对账真数、金额 ¥ —— 设计决定、
 * 双形态（聚合行 / 按天行）兼容、十进制字符串求和不走浮点。点查契约在 reconWorkbenchRoute.test.ts。
 */

let harness: RouteHarness;

before(async () => {
  harness = await createRouteHarness('http://localhost/workbench/recon');
});

after(async () => {
  await harness.close();
});

afterEach(async () => {
  await harness.unmount();
});

test('addDecimal：十进制字符串精确相加，无浮点误差', () => {
  assert.equal(addDecimal('1842.5', '903'), '2745.5');
  assert.equal(addDecimal('0.1', '0.2'), '0.3');
  assert.equal(addDecimal('99999999999999999999', '1'), '100000000000000000000');
  assert.equal(addDecimal('1.50', '2.50'), '4');
});

test('aggregateChannelMetrics：按天行聚合、畸形行丢弃、可选字段宁缺毋编', () => {
  const { rows, totals } = aggregateChannelMetrics([
    { source_channel: 'CAISHIXIAN', metric_date: '2026-08-01', order_count: 10, canonical_quantity: '10.5', shipped_quantity: '10', actual_shipped_quantity: '120', exception_order_count: 1 },
    { source_channel: 'CAISHIXIAN', metric_date: '2026-08-02', order_count: 5, canonical_quantity: '4.5', shipped_quantity: '4', actual_shipped_quantity: '48', exception_order_count: 0 },
    { source_channel: 'JUFUBAO', order_count: 7, canonical_quantity: '7', shipped_quantity: '6' },
    { source_channel: 'BROKEN', order_count: 'NaN', canonical_quantity: '1', shipped_quantity: '1' },
  ]);
  assert.equal(rows.length, 2, '畸形行必须整行丢弃');
  const csx = rows.find((row) => row.channel === 'CAISHIXIAN');
  assert.equal(csx?.orderCount, 15);
  assert.equal(csx?.canonicalQuantity, '15');
  assert.equal(csx?.actualShippedQuantity, '168');
  assert.equal(csx?.exceptionCount, 1);
  const jfb = rows.find((row) => row.channel === 'JUFUBAO');
  assert.equal(jfb?.actualShippedQuantity, null, '可选字段缺失置 null，不编数');
  assert.equal(totals.orderCount, 22);
  assert.equal(totals.canonicalQuantity, '22');
  assert.equal(totals.actualShippedQuantity, null, '任一渠道缺失则合计如实置 null');
});

test('对账台骨架：账期指标真数、分平台表、金额 ¥ —— 与诚实告警', async () => {
  window.localStorage.clear();
  globalThis.fetch = async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url.includes('/api/v1/analytics/channels')) {
      return jsonResponse([
        { source_channel: 'CAISHIXIAN', order_count: 1842, canonical_quantity: '1842.5', shipped_quantity: '1836.5', actual_shipped_quantity: '21864', exception_order_count: 4 },
        { source_channel: 'JUFUBAO', order_count: 903, canonical_quantity: '903', shipped_quantity: '861' },
      ]);
    }
    return jsonResponse(page([]));
  };

  await harness.mount(['/workbench/recon']);

  await harness.waitFor(() => assert.match(harness.bodyText(), /2745/, '平台下单合计（1842+903）必须渲染'));
  const body = harness.bodyText();

  assert.match(body, /金额列现在是空的，这不是加载失败/, 'D15 诚实告警必须在位');
  assert.match(body, /金额对账未纳入本期/, '既有口径横幅保留（#111 契约）');
  assert.match(body, /¥ ——/, '金额列显示设计决定占位');
  assert.match(body, /彩食鲜/, '渠道人话名');
  assert.match(body, /2745\.5/, '来源份数十进制求和精确');
  assert.match(body, /21864/, '实际件数');
  assert.match(body, /差异归集尚未接入/, '差异汇总诚实占位');
  assert.match(body, /输入单号开始查询/, '单笔点查区保留（零复制复用）');
});
