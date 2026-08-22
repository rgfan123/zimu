/**
 * 今日发货工作台（Issue #107）路由契约：发货员从一次订单同步开始今天的工作，并如实呈现各渠道结果。
 * 覆盖：初始 / 同步中禁用 / 成功多渠道 / 部分失败 / 全零「没有新订单」/ 聚福宝仅报告未入库 /
 * 顶层错误可读且可重试 / 有 batch_id 整卡跳文件作业页 / 手动导入跳文件作业页 / 无假配额 /
 * 销售出库生产入口。隐藏但可路由的导航断言在 businessObjectNavigation.test.ts。
 */

import assert from 'node:assert/strict';
import { after, afterEach, before, test } from 'node:test';
import {
  apiErrorResponse,
  control,
  createRouteHarness,
  jsonResponse,
  type RouteHarness,
} from './routeHarness.ts';
import {
  QUOTA_UNAVAILABLE_TEXT,
  presentShippingChannel,
  summarizeShippingResult,
} from '../src/pages/workbench/shippingPresentation.ts';

let harness: RouteHarness;

before(async () => {
  harness = await createRouteHarness('http://localhost/workbench/shipping');
});

afterEach(async () => {
  await harness.unmount();
});

after(async () => {
  await harness.close();
});

const REFRESH_URL = '/api/v1/platform-orders/refresh';

/** 渠道刷新结果夹具：默认彩食鲜 OK 且已生成导入批次。 */
function channel(overrides: Record<string, unknown> = {}) {
  return {
    channel: 'CAISHIXIAN',
    status: 'OK',
    batch_no: 'IMP-CSX-001',
    batch_id: '7',
    row_counts: { total: 30, accepted: 28, need_review: 2, rejected: 0 },
    ...overrides,
  };
}

function refreshResult(channels: unknown[], dateBegin = '2026-08-21', dateEnd = '2026-08-21') {
  return { channels, date_begin: dateBegin, date_end: dateEnd };
}

function refreshOnlyFetch(result: unknown) {
  return async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url === REFRESH_URL) return jsonResponse(result);
    throw new Error(`unexpected request: ${url}`);
  };
}

function anchorWithHref(href: string): HTMLAnchorElement {
  const link = [...document.querySelectorAll<HTMLAnchorElement>('a')]
    .find((a) => a.getAttribute('href') === href);
  assert.ok(link, `missing anchor with href ${href}`);
  return link;
}

test('shipping workbench renders the prototype header, lede, hero and no fabricated quota before sync', async () => {
  globalThis.fetch = async (input) => {
    throw new Error(`unexpected request: ${String(input)}`);
  };

  await harness.mount(['/workbench/shipping']);

  await harness.waitFor(() => assert.match(harness.bodyText(), /今日发货工作台/));
  assert.match(harness.bodyText(), /落为导入批次/, 'lede 必须还原原型口径');
  assert.match(harness.bodyText(), /开始今日订单同步/);
  assert.match(harness.bodyText(), /手动导入 Excel/);
  assert.match(harness.bodyText(), /尚未同步/, '初始态必须给出可读提示');
  assert.match(harness.bodyText(), /当前接口未暴露剩余拉取额度/, '契约边界必须如实说明剩余额度不可见');
  assert.doesNotMatch(harness.bodyText(), /今日剩/, '严禁伪造剩余次数');
});

test('sync disables the trigger and shows an independent loading block while in flight', async () => {
  let resolveRefresh: (r: Response) => void = () => {};
  const gate = new Promise<Response>((resolve) => {
    resolveRefresh = resolve;
  });
  globalThis.fetch = async (input) => {
    if (String(input) === REFRESH_URL) return gate;
    throw new Error(`unexpected request: ${String(input)}`);
  };

  await harness.mount(['/workbench/shipping']);
  await harness.waitFor(() => assert.ok(control('开始今日订单同步')));
  await harness.dispatchEvent(control('开始今日订单同步'), new MouseEvent('click', { bubbles: true }));

  const loadingButton = [...document.querySelectorAll<HTMLButtonElement>('button')]
    .find((b) => b.textContent?.includes('开始今日订单同步'));
  assert.ok(loadingButton, '同步按钮必须在同步中保持挂载');
  await harness.waitFor(() => assert.ok(loadingButton.classList.contains('ant-btn-loading'), '同步中必须禁用触发按钮'));
  await harness.waitFor(() => assert.match(harness.bodyText(), /正在同步三平台订单/, '同步中必须有独立的加载块'));

  resolveRefresh(jsonResponse(refreshResult([channel()])));
  await harness.waitFor(() => assert.match(harness.bodyText(), /彩食鲜/));
});

test('successful sync summarizes batch count and total rows and renders one card per channel', async () => {
  globalThis.fetch = refreshOnlyFetch(refreshResult([
    channel(),
    channel({
      channel: 'JUFUBAO',
      status: 'OK',
      batch_no: undefined,
      batch_id: undefined,
      row_counts: undefined,
      order_count: 41,
      message: '缺收货人字段，仅报告未入库',
    }),
    channel({
      channel: 'FEIXIANG',
      batch_no: 'IMP-FX-002',
      batch_id: '8',
      row_counts: { total: 12, accepted: 12, need_review: 0, rejected: 0 },
    }),
  ]));

  await harness.mount(['/workbench/shipping']);
  await harness.waitFor(() => assert.ok(control('开始今日订单同步')));
  await harness.dispatchEvent(control('开始今日订单同步'), new MouseEvent('click', { bubbles: true }));

  await harness.waitFor(() => assert.match(harness.bodyText(), /彩食鲜/));
  await harness.waitFor(() => assert.match(harness.bodyText(), /飞象/));
  await harness.waitFor(() => assert.match(harness.bodyText(), /聚福宝/));
  await harness.waitFor(() => assert.match(harness.bodyText(), /生成 2 个导入批次 · 共 42 行 · 仅报告未入库 41 单/));
  await harness.waitFor(() => assert.match(harness.bodyText(), /批次 IMP-CSX-001/));
  await harness.waitFor(() => assert.match(harness.bodyText(), /批次 IMP-FX-002/));
  await harness.waitFor(() => assert.match(harness.bodyText(), /成功/));
});

test('partial failure keeps a readable summary and marks FAILED/SKIPPED channels distinctly', async () => {
  globalThis.fetch = refreshOnlyFetch(refreshResult([
    channel(),
    channel({
      channel: 'FEIXIANG',
      status: 'FAILED',
      batch_no: undefined,
      batch_id: undefined,
      row_counts: undefined,
      message: '脚本超时',
    }),
    channel({
      channel: 'ZHONGHUI',
      status: 'SKIPPED',
      batch_no: undefined,
      batch_id: undefined,
      row_counts: undefined,
      message: '今日无数据',
    }),
  ]));

  await harness.mount(['/workbench/shipping']);
  await harness.dispatchEvent(control('开始今日订单同步'), new MouseEvent('click', { bubbles: true }));

  await harness.waitFor(() => assert.match(harness.bodyText(), /生成 1 个导入批次 · 共 30 行/));
  await harness.waitFor(() => assert.match(harness.bodyText(), /1 个渠道失败/));
  await harness.waitFor(() => assert.match(harness.bodyText(), /失败/));
  await harness.waitFor(() => assert.match(harness.bodyText(), /已跳过/));
  await harness.waitFor(() => assert.match(harness.bodyText(), /脚本超时/));
});

test('all-zero sync surfaces a clear "no new orders" result', async () => {
  globalThis.fetch = refreshOnlyFetch(refreshResult([
    channel({ status: 'SKIPPED', batch_no: undefined, batch_id: undefined, row_counts: undefined, message: '今日无数据' }),
    channel({
      channel: 'JUFUBAO',
      status: 'SKIPPED',
      batch_no: undefined,
      batch_id: undefined,
      row_counts: undefined,
      message: '今日无数据',
    }),
  ]));

  await harness.mount(['/workbench/shipping']);
  await harness.dispatchEvent(control('开始今日订单同步'), new MouseEvent('click', { bubbles: true }));

  await harness.waitFor(() => assert.match(harness.bodyText(), /没有新订单/));
  await harness.waitFor(() => assert.doesNotMatch(harness.bodyText(), /生成 \d+ 个导入批次/));
});

test('all-FAILED sync reports an incomplete sync and never claims three platforms completed', async () => {
  globalThis.fetch = refreshOnlyFetch(refreshResult([
    channel({ status: 'FAILED', batch_no: undefined, batch_id: undefined, row_counts: undefined, message: '脚本超时' }),
    channel({
      channel: 'JUFUBAO',
      status: 'FAILED',
      batch_no: undefined,
      batch_id: undefined,
      row_counts: undefined,
      message: '拉取失败',
    }),
    channel({
      channel: 'FEIXIANG',
      status: 'FAILED',
      batch_no: undefined,
      batch_id: undefined,
      row_counts: undefined,
      message: '脚本超时',
    }),
  ]));

  await harness.mount(['/workbench/shipping']);
  await harness.dispatchEvent(control('开始今日订单同步'), new MouseEvent('click', { bubbles: true }));

  await harness.waitFor(() => assert.match(harness.bodyText(), /同步未完成/));
  await harness.waitFor(() => assert.match(harness.bodyText(), /3 个渠道失败，请重试/));
  await harness.waitFor(() => assert.doesNotMatch(harness.bodyText(), /没有新订单/));
  await harness.waitFor(() => assert.doesNotMatch(harness.bodyText(), /三平台已同步完成/));
  await harness.waitFor(() => assert.doesNotMatch(harness.bodyText(), /生成 \d+ 个导入批次/));
});

test('zero-data partial failure reports the failed channels instead of "no new orders"', async () => {
  globalThis.fetch = refreshOnlyFetch(refreshResult([
    channel({ status: 'SKIPPED', batch_no: undefined, batch_id: undefined, row_counts: undefined, message: '今日无数据' }),
    channel({
      channel: 'JUFUBAO',
      status: 'FAILED',
      batch_no: undefined,
      batch_id: undefined,
      row_counts: undefined,
      message: '拉取失败',
    }),
  ]));

  await harness.mount(['/workbench/shipping']);
  await harness.dispatchEvent(control('开始今日订单同步'), new MouseEvent('click', { bubbles: true }));

  await harness.waitFor(() => assert.match(harness.bodyText(), /同步未完成/));
  await harness.waitFor(() => assert.match(harness.bodyText(), /1 个渠道失败，请重试/));
  await harness.waitFor(() => assert.doesNotMatch(harness.bodyText(), /没有新订单/));
  await harness.waitFor(() => assert.doesNotMatch(harness.bodyText(), /三平台已同步完成/));
});

test('JUFUBAO report-only is a first-class status without a fabricated destination', async () => {
  globalThis.fetch = refreshOnlyFetch(refreshResult([
    channel({
      channel: 'JUFUBAO',
      status: 'OK',
      batch_no: undefined,
      batch_id: undefined,
      row_counts: undefined,
      order_count: 41,
      message: '缺收货人字段，仅报告未入库',
    }),
  ]));

  await harness.mount(['/workbench/shipping']);
  await harness.dispatchEvent(control('开始今日订单同步'), new MouseEvent('click', { bubbles: true }));

  await harness.waitFor(() => assert.match(harness.bodyText(), /仅报告未入库/));
  await harness.waitFor(() => assert.match(harness.bodyText(), /拉取 41 单/));
  const cardLinks = [...document.querySelectorAll<HTMLAnchorElement>('a')]
    .filter((a) => (a.getAttribute('href') ?? '').startsWith('/fulfillment/sales-outbound?import_batch='));
  assert.equal(cardLinks.length, 0, '仅报告未入库的渠道卡不得伪造导入批次落点');
});

test('top-level sync failure is readable and retryable', async () => {
  let calls = 0;
  globalThis.fetch = async (input) => {
    const url = String(input);
    if (url === REFRESH_URL) {
      calls += 1;
      if (calls === 1) return apiErrorResponse(500, 'INTERNAL', 'refresh exploded');
      return jsonResponse(refreshResult([channel()]));
    }
    throw new Error(`unexpected request: ${url}`);
  };

  await harness.mount(['/workbench/shipping']);
  await harness.dispatchEvent(control('开始今日订单同步'), new MouseEvent('click', { bubbles: true }));

  await harness.waitFor(() => assert.match(harness.bodyText(), /订单同步失败/));
  await harness.waitFor(() => assert.match(harness.bodyText(), /服务暂时不可用，请稍后重试/, '顶层失败必须可读'));
  await harness.waitFor(() => assert.ok(control('重试'), '顶层失败必须暴露重试动作'));
  await harness.dispatchEvent(control('重试'), new MouseEvent('click', { bubbles: true }));
  await harness.waitFor(() => assert.match(harness.bodyText(), /彩食鲜/));
  assert.equal(calls, 2, '重试必须重新发起同步请求');
});

test('a channel card with a batch id navigates as a whole card to the file job page', async () => {
  globalThis.fetch = refreshOnlyFetch(refreshResult([channel()]));

  await harness.mount(['/workbench/shipping']);
  await harness.dispatchEvent(control('开始今日订单同步'), new MouseEvent('click', { bubbles: true }));
  await harness.waitFor(() => assert.match(harness.bodyText(), /彩食鲜/));

  let cardLink: HTMLAnchorElement | undefined;
  await harness.waitFor(() => {
    cardLink = anchorWithHref('/fulfillment/sales-outbound?import_batch=7');
  });
  assert.ok(cardLink, '整卡必须渲染为指向文件作业页的链接');
  await harness.dispatchEvent(cardLink, new MouseEvent('click', { bubbles: true }));
  await harness.waitFor(() => assert.equal(harness.location(), '/fulfillment/sales-outbound?import_batch=7'));
});

test('manual import is a real link to the file job page', async () => {
  globalThis.fetch = async (input) => {
    throw new Error(`unexpected request: ${String(input)}`);
  };

  await harness.mount(['/workbench/shipping']);
  await harness.waitFor(() => assert.match(harness.bodyText(), /手动导入 Excel/));

  const manualLink = anchorWithHref('/fulfillment/sales-outbound');
  assert.match(manualLink.textContent ?? '', /手动导入 Excel/);
  assert.equal(manualLink.tagName, 'A', '手动导入必须是单一 <a> 锚点');
  assert.equal(manualLink.querySelectorAll('button, a').length, 0, '手动导入锚点内不得嵌套 button/a');
  await harness.dispatchEvent(manualLink, new MouseEvent('click', { bubbles: true }));
  await harness.waitFor(() => assert.equal(harness.location(), '/fulfillment/sales-outbound'));
});

test('sales outbound page exposes the production entry without a second refresh', async () => {
  const requests: string[] = [];
  globalThis.fetch = async (input, init) => {
    const url = String(input);
    requests.push(`${init?.method ?? 'GET'} ${url}`);
    if (url.startsWith('/api/v1/fulfillment-providers')) return jsonResponse([]);
    if (url.startsWith('/api/v1/fulfillment-exports')) {
      return jsonResponse({ items: [], page: 0, size: 10, total_elements: 0, total_pages: 0 });
    }
    throw new Error(`unexpected request: ${url}`);
  };

  await harness.mount(['/fulfillment/sales-outbound']);
  await harness.waitFor(() => assert.match(harness.bodyText(), /销售出库/));

  assert.doesNotMatch(harness.bodyText(), /刷新三平台订单/, '销售出库不得再提供第二套刷新入口');

  const entry = [...document.querySelectorAll<HTMLAnchorElement>('a')]
    .find((a) => (a.getAttribute('href') === '/workbench/shipping') && (a.textContent ?? '').includes('今日发货工作台'));
  assert.ok(entry, '销售出库页必须提供指向今日发货工作台的生产入口');
  await harness.dispatchEvent(entry, new MouseEvent('click', { bubbles: true }));
  await harness.waitFor(() => assert.match(harness.location(), /\/workbench\/shipping/));
  await harness.waitFor(() => assert.match(harness.bodyText(), /开始今日订单同步/));
  assert.equal(
    requests.some((r) => r.includes('/api/v1/platform-orders/refresh')),
    false,
    '销售出库不得发起三平台刷新请求',
  );
});

test('shipping presentation summarizes batch count, total rows and reported orders', () => {
  const summary = summarizeShippingResult({
    channels: [
      channel(),
      channel({ channel: 'JUFUBAO', batch_no: undefined, batch_id: undefined, row_counts: undefined, order_count: 41 }),
      channel({
        channel: 'FEIXIANG',
        batch_no: 'IMP-FX-002',
        batch_id: '8',
        row_counts: { total: 12, accepted: 12, need_review: 0, rejected: 0 },
      }),
    ],
  });
  assert.deepEqual(summary, { batchCount: 2, totalRows: 42, reportedOrders: 41, failedCount: 0, hasNewOrders: true });
  assert.deepEqual(summarizeShippingResult({ channels: [] }), {
    batchCount: 0,
    totalRows: 0,
    reportedOrders: 0,
    failedCount: 0,
    hasNewOrders: false,
  });
  assert.deepEqual(summarizeShippingResult({
    channels: [
      channel(),
      channel({ channel: 'FEIXIANG', status: 'FAILED', batch_no: undefined, batch_id: undefined, row_counts: undefined }),
      channel({ channel: 'ZHONGHUI', status: 'SKIPPED', batch_no: undefined, batch_id: undefined, row_counts: undefined }),
    ],
  }), { batchCount: 1, totalRows: 30, reportedOrders: 0, failedCount: 1, hasNewOrders: true });
});

test('shipping presentation gives a batch channel a destination and a report-only channel none', () => {
  const imported = presentShippingChannel(channel());
  assert.equal(imported.destination, '/fulfillment/sales-outbound?import_batch=7');
  assert.equal(imported.reportOnly, false);
  assert.equal(imported.batchNo, 'IMP-CSX-001');

  const reportOnly = presentShippingChannel(channel({
    channel: 'JUFUBAO',
    batch_no: undefined,
    batch_id: undefined,
    row_counts: undefined,
    order_count: 41,
  }));
  assert.equal(reportOnly.reportOnly, true);
  assert.equal(reportOnly.destination, null);
  assert.equal(reportOnly.orderCount, 41);
  assert.equal(QUOTA_UNAVAILABLE_TEXT, '当前接口未暴露剩余拉取额度');
});
