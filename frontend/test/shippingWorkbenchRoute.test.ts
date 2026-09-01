/**
 * 今日发货工作台（Issue #107）路由契约：发货员从一次订单同步开始今天的工作，并如实呈现各渠道结果。
 * 覆盖：初始 / 同步中禁用 / 成功多渠道 / 部分失败 / 全零「没有新订单」/ 聚福宝仅报告未入库 /
 * 真实 OK Connector 即使生成 batch 也返回 order_count，reportedOrders 不得误计已入库渠道 /
 * 顶层错误可读且可重试 / 有 batch_id 整卡跳文件作业页 / 手动导入跳文件作业页 / 无虚构频控 /
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
  REFRESH_URL,
  channel,
  rawChannel,
  rawRefreshResult,
  refreshResult,
} from './shippingTestFixtures.ts';

let harness: RouteHarness;

before(async () => {
  harness = await createRouteHarness('http://localhost/workbench/shipping');
});

afterEach(async () => {
  await harness.unmount();
  window.sessionStorage.clear();
});

after(async () => {
  await harness.close();
});

function refreshOnlyFetch(result: unknown) {
  return async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url === REFRESH_URL) return jsonResponse(result);
    throw new Error(`unexpected request: ${url}`);
  };
}

function emptyOk(name: 'CAISHIXIAN' | 'JUFUBAO' | 'FEIXIANG', extra: Parameters<typeof channel>[0] = {}) {
  return channel({
    channel: name, status: 'OK', batch_no: undefined, batch_id: undefined, row_counts: undefined, ...extra,
  });
}

function skipped(name: 'CAISHIXIAN' | 'JUFUBAO' | 'FEIXIANG') {
  return channel({
    channel: name, status: 'SKIPPED', batch_no: undefined, batch_id: undefined, row_counts: undefined,
    business_code: 'SKIPPED', message: '今日无数据',
  });
}

function anchorWithHref(href: string): HTMLAnchorElement {
  const link = [...document.querySelectorAll<HTMLAnchorElement>('a')]
    .find((a) => a.getAttribute('href') === href);
  assert.ok(link, `missing anchor with href ${href}`);
  return link;
}

function workbenchBlock(selector: string, label: string): HTMLElement {
  const block = [...document.querySelectorAll<HTMLElement>(selector)]
    .find((element) => element.textContent?.includes(label));
  assert.ok(block, `missing workbench block ${label}`);
  return block;
}

async function leaveForFileOperationsAndReturn(): Promise<void> {
  const manualLink = [...document.querySelectorAll<HTMLAnchorElement>('main a')]
    .find((link) => link.textContent?.includes('手动导入 Excel'));
  assert.ok(manualLink);
  await harness.dispatchEvent(manualLink, new MouseEvent('click', { bubbles: true }));
  await harness.waitFor(() => assert.equal(harness.location(), '/fulfillment/sales-outbound'));
  const returnLink = [...document.querySelectorAll<HTMLAnchorElement>('a')]
    .find((link) => link.getAttribute('href') === '/workbench/shipping' && link.textContent?.includes('今日发货工作台'));
  assert.ok(returnLink);
  await harness.dispatchEvent(returnLink, new MouseEvent('click', { bubbles: true }));
  await harness.waitFor(() => assert.equal(harness.location(), '/workbench/shipping'));
}

test('shipping workbench does not request or render a quota because platform pulls are unlimited', async () => {
  const requests: string[] = [];
  // 骨架挂载会发计数请求（ADR 0006 size=1 拼真数）；这里放行计数、只断言「不存在配额请求」。
  globalThis.fetch = async (input) => {
    requests.push(String(input));
    return jsonResponse({ items: [], page: 0, size: 1, total_elements: 0, total_pages: 0 });
  };

  await harness.mount(['/workbench/shipping']);

  await harness.waitFor(() => assert.match(harness.bodyText(), /今日发货工作台/));
  // ADR 0005 密度优先：解释性 lede 与 hero 长段落已删除，第一屏是数据本身（骨架断言见 skeleton 测试）。
  assert.doesNotMatch(harness.bodyText(), /并逐渠道如实显示结果/, '旧 LEDE 解释句不得回归');
  assert.match(harness.bodyText(), /开始今日订单同步/);
  assert.match(harness.bodyText(), /手动导入 Excel/);
  // 左上角手工建单入口（用户裁定）：真实 <a>（Button href）钉在页头标题同侧左区，
  // 不进右侧 zs-ph-actions（ADR 0005：标题左、同步动作右）。
  const manualCreateEntry = [...document.querySelectorAll<HTMLAnchorElement>('.zs-ph a')]
    .find((link) => link.getAttribute('href') === '/orders/manual-create');
  assert.ok(manualCreateEntry, '工作台页头必须有手工建单入口');
  assert.match(manualCreateEntry.textContent ?? '', /手工建单/);
  assert.equal(manualCreateEntry.closest('.zs-ph-actions'), null, '手工建单入口在标题旁左区，不得挤进右侧动作区');
  // ADR 0005：闲置态不再占版面（骨架即首屏），同步结果区仅在动作后出现。
  assert.doesNotMatch(harness.bodyText(), /尚未同步/, '闲置提示文案不得回归');
  // #115 口径：平台拉单本无每日次数与最小间隔，因此既不请求配额、也不呈现任何配额说法
  //（包括「未暴露剩余额度」——那本身就在暗示存在配额制度）。
  assert.equal(
    requests.some((url) => /quota|last_pull|rate.?limit/i.test(url)),
    false,
    '页面挂载不得读取不存在的频控配额',
  );
  assert.doesNotMatch(harness.bodyText(), /配额|今天最多还能拉|下次可拉取|今日剩|剩余拉取额度/);
});

test('sync disables the trigger and shows an independent loading block while in flight', async () => {
  let refreshCalls = 0;
  let resolveRefresh: (r: Response) => void = () => {};
  const gate = new Promise<Response>((resolve) => {
    resolveRefresh = resolve;
  });
  globalThis.fetch = async (input) => {
    if (String(input) === REFRESH_URL) {
      refreshCalls += 1;
      return gate;
    }
    throw new Error(`unexpected request: ${String(input)}`);
  };

  await harness.mount(['/workbench/shipping']);
  await harness.waitFor(() => assert.ok(control('开始今日订单同步')));
  await harness.dispatchEvent(control('开始今日订单同步'), new MouseEvent('click', { bubbles: true }));

  const loadingButton = [...document.querySelectorAll<HTMLButtonElement>('button')]
    .find((b) => b.textContent?.includes('开始今日订单同步'));
  assert.ok(loadingButton, '同步按钮必须在同步中保持挂载');
  await harness.waitFor(() => assert.ok(loadingButton.classList.contains('ant-btn-loading'), '同步中必须禁用触发按钮'));
  await harness.dispatchEvent(loadingButton, new MouseEvent('click', { bubbles: true }));
  assert.equal(refreshCalls, 1, '同一次在途同步不得被双击重复发起');
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
      business_code: 'OK',
      message: '缺收货人字段，仅报告未入库 /tmp/jufubao.py',
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
  assert.match(workbenchBlock('.zs-st', '仅报告未入库').textContent ?? '', /41/);
  assert.match(workbenchBlock('.zs-pstep', '1 平台拉取').textContent ?? '', /3.*3 成功/);
  assert.match(workbenchBlock('.zs-pstep', '2 落导入批次').textContent ?? '', /2.*42 行/);
  assert.doesNotMatch(harness.bodyText(), /jufubao\.py/);
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
      business_code: 'SCRIPT_FAILED',
      message: '脚本超时 /tmp/feixiang_fetch_orders.py CSX_PASSWORD',
    }),
    channel({
      channel: 'ZHONGHUI',
      status: 'SKIPPED',
      batch_no: undefined,
      batch_id: undefined,
      row_counts: undefined,
      business_code: 'SKIPPED',
      message: '今日无数据',
    }),
  ]));

  await harness.mount(['/workbench/shipping']);
  await harness.dispatchEvent(control('开始今日订单同步'), new MouseEvent('click', { bubbles: true }));

  await harness.waitFor(() => assert.match(harness.bodyText(), /生成 1 个导入批次 · 共 30 行/));
  await harness.waitFor(() => assert.match(harness.bodyText(), /1 个渠道失败/));
  await harness.waitFor(() => assert.match(harness.bodyText(), /失败/));
  await harness.waitFor(() => assert.match(harness.bodyText(), /响应异常/));
  await harness.waitFor(() => assert.match(harness.bodyText(), /未知渠道/));
  await harness.waitFor(() => assert.match(harness.bodyText(), /该渠道拉取失败，请稍后重试/));
  await harness.waitFor(() => assert.match(harness.bodyText(), /渠道响应格式异常，请联系管理员/));
  await harness.waitFor(() => assert.match(harness.bodyText(), /同步结果格式异常/));
  assert.doesNotMatch(harness.bodyText(), /已跳过/);
  assert.doesNotMatch(harness.bodyText(), /该渠道已跳过本次拉取/);
  assert.doesNotMatch(harness.bodyText(), /没有新订单|三平台已同步完成/);
  assert.doesNotMatch(harness.bodyText(), /脚本超时|feixiang_fetch_orders\.py|CSX_PASSWORD|今日无数据/);
});

test('all-zero sync surfaces a clear "no new orders" result', async () => {
  globalThis.fetch = refreshOnlyFetch(refreshResult([
    emptyOk('CAISHIXIAN'),
    emptyOk('JUFUBAO', { order_count: 0 }),
    emptyOk('FEIXIANG'),
  ]));

  await harness.mount(['/workbench/shipping']);
  await harness.dispatchEvent(control('开始今日订单同步'), new MouseEvent('click', { bubbles: true }));

  await harness.waitFor(() => {
    const text = harness.bodyText();
    assert.match(text, /没有新订单/);
    assert.match(text, /三平台已同步完成/);
    assert.doesNotMatch(text, /生成 \d+ 个导入批次|同步未完成/);
  });
});

test('all-FAILED sync reports an incomplete sync and never claims three platforms completed', async () => {
  const failedChannels = [
    channel({
      status: 'FAILED',
      batch_no: undefined,
      batch_id: undefined,
      row_counts: undefined,
      business_code: 'SCRIPT_FAILED',
      message: '脚本超时 /tmp/caishixian_fetch_orders.py',
    }),
    channel({
      channel: 'JUFUBAO',
      status: 'FAILED',
      batch_no: undefined,
      batch_id: undefined,
      row_counts: undefined,
      business_code: 'INTERNAL_ERROR',
      message: '拉取失败 credentials/csx-credentials.txt',
    }),
    channel({
      channel: 'FEIXIANG',
      status: 'SKIPPED',
      batch_no: undefined,
      batch_id: undefined,
      row_counts: undefined,
      business_code: 'SKIPPED',
      message: '今日无数据 FEIXIANG_PASSWORD',
    }),
  ];
  globalThis.fetch = async (input) => {
    const url = String(input);
    if (url === REFRESH_URL) {
      return jsonResponse({
        message: '所有渠道刷新均未成功（SKIPPED 或 FAILED），请查看各渠道 message 后重试',
        http_status: 502,
        business_code: 'PLATFORM_REFRESH_ALL_FAILED',
        trace_id: 'test-trace',
        details: { channels: failedChannels },
      }, 502);
    }
    throw new Error(`unexpected request: ${url}`);
  };

  await harness.mount(['/workbench/shipping']);
  await harness.dispatchEvent(control('开始今日订单同步'), new MouseEvent('click', { bubbles: true }));

  await harness.waitFor(() => assert.match(harness.bodyText(), /订单同步失败/));
  await harness.waitFor(() => assert.ok(control('重试'), '合法 502 必须保留顶层失败重试'));
  await harness.waitFor(() => assert.match(harness.bodyText(), /彩食鲜/));
  await harness.waitFor(() => assert.match(harness.bodyText(), /聚福宝/));
  await harness.waitFor(() => assert.match(harness.bodyText(), /飞象/));
  await harness.waitFor(() => assert.match(harness.bodyText(), /该渠道拉取失败，请稍后重试/));
  await harness.waitFor(() => assert.match(harness.bodyText(), /该渠道刷新出现内部错误，请稍后重试/));
  await harness.waitFor(() => assert.match(harness.bodyText(), /该渠道已跳过本次拉取/));
  await harness.waitFor(() => assert.match(harness.bodyText(), /失败/));
  await harness.waitFor(() => assert.match(harness.bodyText(), /已跳过/));
  await harness.waitFor(() => assert.doesNotMatch(harness.bodyText(), /没有新订单/));
  await harness.waitFor(() => assert.doesNotMatch(harness.bodyText(), /三平台已同步完成/));
  await harness.waitFor(() => assert.doesNotMatch(harness.bodyText(), /生成 \d+ 个导入批次/));
  assert.doesNotMatch(
    harness.bodyText(),
    /脚本超时|caishixian_fetch_orders\.py|csx-credentials\.txt|FEIXIANG_PASSWORD|今日无数据/,
  );
});

test('malformed PLATFORM_REFRESH_ALL_FAILED details stay on the generic error and do not crash', async () => {
  globalThis.fetch = async (input) => {
    const url = String(input);
    if (url === REFRESH_URL) {
      return jsonResponse({
        message: '所有渠道刷新均未成功（SKIPPED 或 FAILED），请查看各渠道 message 后重试',
        http_status: 502,
        business_code: 'PLATFORM_REFRESH_ALL_FAILED',
        trace_id: 'test-trace',
        details: { channels: 'not-an-array' },
      }, 502);
    }
    throw new Error(`unexpected request: ${url}`);
  };

  await harness.mount(['/workbench/shipping']);
  await harness.dispatchEvent(control('开始今日订单同步'), new MouseEvent('click', { bubbles: true }));

  await harness.waitFor(() => assert.match(harness.bodyText(), /订单同步失败/));
  await harness.waitFor(() => assert.match(harness.bodyText(), /服务暂时不可用，请稍后重试/));
  await harness.waitFor(() => assert.ok(control('重试')));
  const statusTags = [...document.querySelectorAll('.ant-tag')].map((el) => el.textContent ?? '');
  assert.equal(
    statusTags.filter((text) => text === '失败' || text === '已跳过' || text === '成功').length,
    0,
    '畸形 details 不得渲染渠道状态卡',
  );
  assert.doesNotMatch(harness.bodyText(), /脚本超时|拉取失败|已跳过/);
});

test('one OK plus two SKIPPED with zero data reports incomplete sync and never claims three platforms completed', async () => {
  globalThis.fetch = refreshOnlyFetch(refreshResult([
    emptyOk('CAISHIXIAN'), skipped('JUFUBAO'), skipped('FEIXIANG'),
  ]));
  await harness.mount(['/workbench/shipping']);
  await harness.dispatchEvent(control('开始今日订单同步'), new MouseEvent('click', { bubbles: true }));
  await harness.waitFor(() => {
    const text = harness.bodyText();
    assert.match(text, /同步未完成/);
    assert.match(text, /2 个渠道已跳过/);
    assert.doesNotMatch(text, /没有新订单|三平台已同步完成|生成 \d+ 个导入批次/);
  });
});

test('legal batch plus SKIPPED keeps the batch summary but never claims three platforms completed', async () => {
  globalThis.fetch = refreshOnlyFetch(refreshResult([
    channel(), skipped('JUFUBAO'), skipped('FEIXIANG'),
  ]));
  await harness.mount(['/workbench/shipping']);
  await harness.dispatchEvent(control('开始今日订单同步'), new MouseEvent('click', { bubbles: true }));
  await harness.waitFor(() => {
    const text = harness.bodyText();
    assert.match(text, /生成 1 个导入批次 · 共 30 行/);
    assert.match(text, /2 个渠道已跳过/);
    assert.match(text, /同步未完成/);
    assert.doesNotMatch(text, /没有新订单|三平台已同步完成/);
  });
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

test('CAISHIXIAN order_count with a batch plus JUFUBAO report-only only counts the 41 unimported orders', async () => {
  globalThis.fetch = refreshOnlyFetch(refreshResult([
    channel({ order_count: 3 }),
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

  await harness.waitFor(() => assert.match(harness.bodyText(), /生成 1 个导入批次 · 共 30 行 · 仅报告未入库 41 单/));
  await harness.waitFor(() => assert.match(harness.bodyText(), /拉取 41 单/));
  assert.doesNotMatch(harness.bodyText(), /仅报告未入库 44 单/);
  assert.doesNotMatch(harness.bodyText(), /拉取 3 单/);
});

test('JUFUBAO report-only opens an in-place explanation with an executable file-import next step', async () => {
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

  const channelCard = [...document.querySelectorAll<HTMLElement>('[role="button"]')]
    .find((element) => element.textContent?.includes('聚福宝'));
  assert.ok(channelCard, '聚福宝渠道卡必须可原地打开说明');
  await harness.dispatchEvent(channelCard, new MouseEvent('click', { bubbles: true }));
  await harness.waitFor(() => assert.match(harness.bodyText(), /聚福宝 · 拉取快照/));
  assert.equal(harness.location(), '/workbench/shipping');
  assert.match(harness.bodyText(), /JSON 直连拉到 41 单/);
  assert.match(harness.bodyText(), /来源缺少收货人字段，本次未生成导入批次/);
  const uploadLink = [...document.querySelectorAll<HTMLAnchorElement>('a')]
    .find((link) => link.textContent?.includes('去文件作业页上传 Excel 补录'));
  assert.ok(uploadLink);
  assert.equal(uploadLink.getAttribute('href'), '/fulfillment/sales-outbound');
});

test('JUFUBAO without a reported count keeps the number unknown and still offers the Excel next step', async () => {
  globalThis.fetch = refreshOnlyFetch(refreshResult([
    channel({
      channel: 'JUFUBAO',
      status: 'OK',
      batch_no: undefined,
      batch_id: undefined,
      row_counts: undefined,
      order_count: undefined,
      business_code: 'OK',
    }),
  ]));

  await harness.mount(['/workbench/shipping']);
  await harness.dispatchEvent(control('开始今日订单同步'), new MouseEvent('click', { bubbles: true }));
  await harness.waitFor(() => assert.match(harness.bodyText(), /聚福宝/));

  const reportedMetric = workbenchBlock('.zs-st', '仅报告未入库');
  assert.equal(reportedMetric.querySelector('.zs-v')?.textContent, '—');
  assert.doesNotMatch(harness.bodyText(), /没有新订单|拉取 0 单/);
  const channelCard = [...document.querySelectorAll<HTMLElement>('[role="button"]')]
    .find((element) => element.textContent?.includes('聚福宝'));
  assert.ok(channelCard);
  await harness.dispatchEvent(channelCard, new MouseEvent('click', { bubbles: true }));
  await harness.waitFor(() => assert.match(harness.bodyText(), /JSON 直连拉取数量暂不可用/));
  const uploadLink = [...document.querySelectorAll<HTMLAnchorElement>('a')]
    .find((link) => link.textContent?.includes('去文件作业页上传 Excel 补录'));
  assert.ok(uploadLink);
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

test('a channel card opens its paged batch snapshot in place through the safe row projection', async () => {
  const requests: string[] = [];
  globalThis.fetch = async (input) => {
    const url = String(input);
    requests.push(url);
    if (url === REFRESH_URL) return jsonResponse(refreshResult([channel()]));
    if (url === '/api/v1/import-batches/7/rows?page=0&size=20') {
      return jsonResponse({
        items: [{
          id: 'row-1',
          sheet_name: '订单',
          sheet_index: 0,
          row_index: 2,
          source_order_ref: 'CSX-1001',
          raw_cells: {
            商品编号: 'SKU-CSX-001',
            身份证号码: '410000000000000000',
            内部备注: '禁止展示的原始字段',
          },
          parsed: {
            receiver_name: '张三',
            receiver_phone: '13800000000',
            receiver_address: '河南省开封市测试路 1 号',
            product_name: '来源苹果',
            specification: '5kg/箱',
            quantity: '2',
          },
          status: 'NEED_REVIEW',
          error_code: 'SKU_MAPPING_REQUIRED',
          order_id: null,
          order_line_id: null,
          jd_cargos: [],
        }, {
          id: 'row-2',
          sheet_name: '订单',
          sheet_index: 0,
          row_index: 3,
          source_order_ref: 'CSX-1002',
          raw_cells: {
            商品名称: '来源梨',
            数量: 3,
          },
          parsed: {
            receiver_name: '李四',
            receiver_phone: '13900000000',
            receiver_address: '河南省开封市测试路 2 号',
          },
          status: 'REJECTED',
          error_code: 'IMPORT_VALIDATION',
          order_id: null,
          order_line_id: null,
          jd_cargos: [],
        }],
        page: 0,
        size: 20,
        total_elements: 31,
        total_pages: 2,
      });
    }
    if (url === '/api/v1/import-batches/7/rows?page=1&size=20') {
      return jsonResponse({ items: [], page: 1, size: 20, total_elements: 31, total_pages: 2 });
    }
    throw new Error(`unexpected request: ${url}`);
  };

  await harness.mount(['/workbench/shipping']);
  await harness.dispatchEvent(control('开始今日订单同步'), new MouseEvent('click', { bubbles: true }));
  await harness.waitFor(() => assert.match(harness.bodyText(), /彩食鲜/));

  const channelCard = [...document.querySelectorAll<HTMLElement>('[role="button"]')]
    .find((element) => element.textContent?.includes('彩食鲜'));
  assert.ok(channelCard, '渠道卡必须是可点击、可键盘触发的原地操作');
  await harness.dispatchEvent(channelCard, new MouseEvent('click', { bubbles: true }));

  await harness.waitFor(() => assert.match(harness.bodyText(), /彩食鲜 · 批次快照/));
  assert.equal(harness.location(), '/workbench/shipping', '打开快照不得离开工作台');
  assert.match(harness.bodyText(), /批次 IMP-CSX-001/);
  assert.match(harness.bodyText(), /共 30 行 · 已接收 28 · 待复核 2 · 拒绝 0/);
  assert.match(harness.bodyText(), /拉取窗口 2026-08-21/);
  assert.match(harness.bodyText(), /张三/);
  assert.match(harness.bodyText(), /13800000000/);
  assert.match(harness.bodyText(), /河南省开封市测试路 1 号/);
  assert.deepEqual(
    [...document.querySelectorAll<HTMLTableCellElement>('.ant-table-thead th')]
      .map((cell) => cell.textContent?.trim()),
    // 首列是「平台原始返回」证据折叠区的展开钮（无表头文案）——快照留档但不再是主数据源。
    ['', '序号', '渠道单号', '收件人', '电话', '收货地址', '商品', '件数', '状态', '处理结果'],
  );
  assert.match(harness.bodyText(), /CSX-1001/);
  assert.match(harness.bodyText(), /来源苹果/);
  assert.match(harness.bodyText(), /CSX-1002/);
  assert.match(harness.bodyText(), /来源梨/);
  const detailRows = [...document.querySelectorAll<HTMLTableRowElement>('.ant-table-tbody tr.ant-table-row')];
  assert.deepEqual(
    detailRows.map((row) => {
      const cells = [...row.querySelectorAll<HTMLTableCellElement>('td')];
      return [cells[6]?.textContent?.trim(), cells[7]?.textContent?.trim()];
    }),
    [['来源苹果', '2'], ['来源梨', '3']],
    '商品与件数必须同时使用来源行口径（这两行未建单，整组退回快照口径）',
  );
  assert.match(harness.bodyText(), /待复核/);
  assert.match(harness.bodyText(), /已拒绝/);
  assert.doesNotMatch(harness.bodyText(), /发货明细|SKU-CSX-001/);
  assert.doesNotMatch(harness.bodyText(), /身份证号码|410000000000000000|内部备注|禁止展示的原始字段|raw_cells/);
  assert.ok(requests.includes('/api/v1/import-batches/7/rows?page=0&size=20'));
  assert.equal(
    requests.some((url) => /\/rows\?page=0&size=(?!20(?:&|$))/.test(url)),
    false,
    '快照首屏不得一次拉全量',
  );
  assert.ok(anchorWithHref('/fulfillment/sales-outbound?import_batch=7').textContent?.includes('去文件作业页确认整批'));
  assert.doesNotMatch(harness.bodyText(), /确认整批发货/);

  const nextPage = [...document.querySelectorAll<HTMLElement>('li.ant-pagination-next')][0];
  assert.ok(nextPage, '多页批次必须显示分页控件');
  await harness.dispatchEvent(nextPage, new MouseEvent('click', { bubbles: true }));
  await harness.waitFor(() => assert.ok(requests.includes('/api/v1/import-batches/7/rows?page=1&size=20')));
});

/**
 * 候选流水线回归：结构化平台拉取确认前不会创建正式订单，rows API 必须直接返回
 * 服务端候选白名单投影；弹窗不得依赖一个尚不存在的 order_id。
 */
test('structured pull snapshot reads staged candidate preview before order materialization', async () => {
  let orderDetailCalls = 0;
  globalThis.fetch = async (input) => {
    const url = String(input);
    if (url === REFRESH_URL) {
      return jsonResponse(refreshResult([channel({
        channel: 'JUFUBAO', batch_no: 'IMP-JFB-001', batch_id: '9',
        row_counts: { total: 1, accepted: 0, need_review: 1, rejected: 0 },
      })]));
    }
    if (url === '/api/v1/import-batches/9/rows?page=0&size=20') {
      return jsonResponse({
        items: [{
          id: 'row-1',
          sheet_name: 'STRUCTURED',
          sheet_index: 0,
          row_index: 1,
          source_order_ref: 'm951890039794349980',
          raw_cells: {
            source_ref: 'm951890039794349980',
            source_line_ref: 'm951890039794349980-1',
            item_index: 0,
            snapshot: {
              product_name: '乔府大院金饭碗五常大米5kg',
              product_num: 1,
              receiver_source: 'sub-order-info',
              receiver_missing: false,
              supplier_name: '京诚乾***',
            },
          },
          parsed: {
            receiver_name: '丁小满',
            receiver_phone: '13800001111',
            receiver_address: '上海市 上海市 浦东新区 张江镇 科苑路 88 号',
            product_name: '乔府大院金饭碗五常大米5kg',
            quantity: '1',
            specification: '5kg/袋',
            source_sku_ref: '2047704',
          },
          status: 'NEED_REVIEW',
          error_code: 'SKU_MATCH',
          order_id: null,
          order_line_id: null,
          jd_cargos: [],
        }],
        page: 0, size: 20, total_elements: 1, total_pages: 1,
      });
    }
    if (url.startsWith('/api/v1/orders/')) {
      orderDetailCalls += 1;
      throw new Error('确认前不得请求尚不存在的正式订单');
    }
    if (url.startsWith('/api/v1/review-cases?')) {
      return jsonResponse({ items: [], page: 0, size: 200, total_elements: 0, total_pages: 0 });
    }
    throw new Error(`unexpected request: ${url}`);
  };

  await harness.mount(['/workbench/shipping']);
  await harness.dispatchEvent(control('开始今日订单同步'), new MouseEvent('click', { bubbles: true }));
  await harness.waitFor(() => assert.match(harness.bodyText(), /聚福宝/));
  const channelCard = [...document.querySelectorAll<HTMLElement>('[role="button"]')]
    .find((element) => element.textContent?.includes('聚福宝'));
  assert.ok(channelCard);
  await harness.dispatchEvent(channelCard, new MouseEvent('click', { bubbles: true }));
  await harness.waitFor(() => assert.match(harness.bodyText(), /聚福宝 · 批次快照/));

  await harness.waitFor(() => assert.match(harness.bodyText(), /丁小满/));
  assert.match(harness.bodyText(), /13800001111/);
  assert.match(harness.bodyText(), /上海市 上海市 浦东新区 张江镇 科苑路 88 号/);
  assert.match(harness.bodyText(), /乔府大院金饭碗五常大米5kg/);
  assert.match(harness.bodyText(), /来源商品尚未建立 SKU 映射/);
  assert.equal(orderDetailCalls, 0, '确认前没有 order_id，不应调用订单详情接口');

  const cells = [...document.querySelectorAll<HTMLTableRowElement>('.ant-table-tbody tr.ant-table-row')]
    .flatMap((row) => [...row.querySelectorAll<HTMLTableCellElement>('td')])
    .map((cell) => cell.textContent?.trim());
  assert.equal(cells.filter((text) => text === '—').length, 0, '候选投影完整时主表不得出现破折号占位');
  assert.doesNotMatch(harness.bodyText(), /京诚乾\*\*\*/, '脱敏 raw snapshot 值不上主表');
});

test('a failed snapshot row load has a retry and an empty page has an explicit empty state', async () => {
  let rowCalls = 0;
  let resolveFirstRows: (response: Response) => void = () => {};
  const firstRows = new Promise<Response>((resolve) => {
    resolveFirstRows = resolve;
  });
  globalThis.fetch = async (input) => {
    const url = String(input);
    if (url === REFRESH_URL) return jsonResponse(refreshResult([channel()]));
    if (url === '/api/v1/import-batches/7/rows?page=0&size=20') {
      rowCalls += 1;
      if (rowCalls === 1) return firstRows;
      return jsonResponse({ items: [], page: 0, size: 20, total_elements: 0, total_pages: 0 });
    }
    throw new Error(`unexpected request: ${url}`);
  };

  await harness.mount(['/workbench/shipping']);
  await harness.dispatchEvent(control('开始今日订单同步'), new MouseEvent('click', { bubbles: true }));
  await harness.waitFor(() => assert.match(harness.bodyText(), /彩食鲜/));
  const channelCard = [...document.querySelectorAll<HTMLElement>('[role="button"]')]
    .find((element) => element.textContent?.includes('彩食鲜'));
  assert.ok(channelCard);
  await harness.dispatchEvent(channelCard, new MouseEvent('click', { bubbles: true }));

  await harness.waitFor(() => assert.match(harness.bodyText(), /正在加载批次明细/));
  resolveFirstRows(apiErrorResponse(500, 'INTERNAL', 'raw backend detail'));
  await harness.waitFor(() => assert.match(harness.bodyText(), /批次明细加载失败/));
  assert.ok(control('重试加载'));
  await harness.dispatchEvent(control('重试加载'), new MouseEvent('click', { bubbles: true }));
  await harness.waitFor(() => assert.match(harness.bodyText(), /该批次暂无明细行/));
  assert.equal(rowCalls, 2);
});

test('the latest successful sync snapshot survives leaving and returning in the same tab', async () => {
  let refreshCalls = 0;
  globalThis.fetch = async (input) => {
    const url = String(input);
    if (url === REFRESH_URL) {
      refreshCalls += 1;
      return jsonResponse(refreshResult([channel()]));
    }
    if (url.startsWith('/api/v1/fulfillment-providers')) return jsonResponse([]);
    if (url.startsWith('/api/v1/fulfillment-exports')) {
      return jsonResponse({ items: [], page: 0, size: 10, total_elements: 0, total_pages: 0 });
    }
    throw new Error(`unexpected request: ${url}`);
  };

  await harness.mount(['/workbench/shipping']);
  await harness.dispatchEvent(control('开始今日订单同步'), new MouseEvent('click', { bubbles: true }));
  await harness.waitFor(() => assert.match(harness.bodyText(), /批次 IMP-CSX-001/));

  await leaveForFileOperationsAndReturn();
  await harness.waitFor(() => assert.match(harness.bodyText(), /批次 IMP-CSX-001/));
  assert.equal(refreshCalls, 1, '跨路由恢复只读 sessionStorage，不得偷偷二次同步');
  assert.match(workbenchBlock('.zs-pstep', '1 平台拉取').textContent ?? '', /1.*1 成功/);
});

test('unknown sync metrics stay explicit instead of being fabricated as zero', async () => {
  globalThis.fetch = refreshOnlyFetch(refreshResult([
    channel({ row_counts: undefined }),
    channel({
      channel: 'JUFUBAO',
      status: 'FAILED',
      batch_no: undefined,
      batch_id: undefined,
      row_counts: undefined,
      order_count: undefined,
      business_code: 'INTERNAL_ERROR',
    }),
  ]));

  await harness.mount(['/workbench/shipping']);
  await harness.dispatchEvent(control('开始今日订单同步'), new MouseEvent('click', { bubbles: true }));
  await harness.waitFor(() => assert.match(harness.bodyText(), /批次 IMP-CSX-001/));

  const reportedMetric = workbenchBlock('.zs-st', '仅报告未入库');
  assert.equal(reportedMetric.querySelector('.zs-v')?.textContent, '—');
  assert.match(reportedMetric.textContent ?? '', /暂无汇总/);
  const importedSegment = workbenchBlock('.zs-pstep', '2 落导入批次');
  assert.equal(importedSegment.querySelector('.zs-v')?.textContent, '1');
  assert.match(importedSegment.textContent ?? '', /暂无汇总/);
  assert.doesNotMatch(importedSegment.textContent ?? '', /0 行/);
  assert.match(harness.bodyText(), /生成 1 个导入批次 · 行数暂无汇总/);
  assert.doesNotMatch(harness.bodyText(), /生成 1 个导入批次 · 共 0 行/);
});

test('a failed refresh invalidates the older successful session snapshot', async () => {
  let refreshCalls = 0;
  globalThis.fetch = async (input) => {
    const url = String(input);
    if (url === REFRESH_URL) {
      refreshCalls += 1;
      return refreshCalls === 1
        ? jsonResponse(refreshResult([channel()]))
        : apiErrorResponse(500, 'INTERNAL', 'refresh exploded');
    }
    if (url.startsWith('/api/v1/fulfillment-providers')) return jsonResponse([]);
    if (url.startsWith('/api/v1/fulfillment-exports')) {
      return jsonResponse({ items: [], page: 0, size: 10, total_elements: 0, total_pages: 0 });
    }
    throw new Error(`unexpected request: ${url}`);
  };

  await harness.mount(['/workbench/shipping']);
  await harness.dispatchEvent(control('开始今日订单同步'), new MouseEvent('click', { bubbles: true }));
  await harness.waitFor(() => assert.match(harness.bodyText(), /批次 IMP-CSX-001/));
  await harness.dispatchEvent(control('开始今日订单同步'), new MouseEvent('click', { bubbles: true }));
  await harness.waitFor(() => assert.match(harness.bodyText(), /订单同步失败/));

  await leaveForFileOperationsAndReturn();
  await harness.waitFor(() => assert.doesNotMatch(harness.bodyText(), /批次 IMP-CSX-001|订单同步失败/));
  assert.equal(refreshCalls, 2);
});

test('manual import is a real link to the file job page', async () => {
  globalThis.fetch = async (input) => {
    throw new Error(`unexpected request: ${String(input)}`);
  };

  await harness.mount(['/workbench/shipping']);
  await harness.waitFor(() => assert.match(harness.bodyText(), /手动导入 Excel/));

  // Issue #104 外壳后侧栏也有指向文件作业的导航链接：限定在主内容区找页面按钮。
  const manualLink = [...document.querySelectorAll<HTMLAnchorElement>('main a')]
    .find((a) => a.getAttribute('href') === '/fulfillment/sales-outbound');
  assert.ok(manualLink, 'missing manual import anchor in main content');
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
  await harness.waitFor(() => assert.match(harness.bodyText(), /文件作业/));

  assert.doesNotMatch(harness.bodyText(), /刷新三平台订单/, '文件作业页不得再提供第二套刷新入口');

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

function channelStatusTags(): string[] {
  return [...document.querySelectorAll('.ant-tag')]
    .map((el) => el.textContent ?? '')
    .filter((text) => text === '失败' || text === '已跳过' || text === '成功' || text === '响应异常');
}

test('malicious 502 details do not render cards, links or crash', async () => {
  globalThis.fetch = async (input) => {
    const url = String(input);
    if (url === REFRESH_URL) {
      return jsonResponse({
        message: '所有渠道刷新均未成功（SKIPPED 或 FAILED），请查看各渠道 message 后重试',
        http_status: 502,
        business_code: 'PLATFORM_REFRESH_ALL_FAILED',
        trace_id: 'test-trace',
        details: {
          channels: [
            {
              channel: 'CAISHIXIAN',
              status: 'FAILED',
              business_code: 'SCRIPT_FAILED',
              batch_id: '7&return_to=https://evil.invalid',
              message: { text: '<img src=x onerror=alert(1)>' },
              row_counts: '30',
            },
            {
              channel: 'JUFUBAO',
              status: 'OK',
              business_code: 'OK',
              message: 'should-not-appear',
            },
            {
              channel: 'ZHONGHUI',
              status: 'SKIPPED',
              business_code: 'SKIPPED',
              message: 'extra-channel',
            },
          ],
        },
      }, 502);
    }
    throw new Error(`unexpected request: ${url}`);
  };

  await harness.mount(['/workbench/shipping']);
  await harness.dispatchEvent(control('开始今日订单同步'), new MouseEvent('click', { bubbles: true }));

  await harness.waitFor(() => assert.match(harness.bodyText(), /订单同步失败/));
  await harness.waitFor(() => assert.match(harness.bodyText(), /服务暂时不可用，请稍后重试/));
  await harness.waitFor(() => assert.ok(control('重试')));
  assert.equal(channelStatusTags().length, 0, '恶意 502 details 不得渲染渠道状态卡');
  const hrefs = [...document.querySelectorAll('a')].map((a) => a.getAttribute('href') ?? '');
  assert.equal(hrefs.some((href) => href.includes('evil.invalid') || href.includes('return_to')), false);
  assert.doesNotMatch(harness.bodyText(), /should-not-appear|extra-channel|evil\.invalid/);
});

test('success response with a poisoned batch_id does not create a file-job link or crash', async () => {
  globalThis.fetch = refreshOnlyFetch(refreshResult([
    channel({
      batch_id: '7&return_to=https://evil.invalid',
      batch_no: 'IMP-CSX-001',
      row_counts: { total: 30, accepted: 28, need_review: 2, rejected: 0 },
    }),
  ]));

  await harness.mount(['/workbench/shipping']);
  await harness.dispatchEvent(control('开始今日订单同步'), new MouseEvent('click', { bubbles: true }));
  await harness.waitFor(() => assert.match(harness.bodyText(), /彩食鲜/));
  await harness.waitFor(() => assert.match(harness.bodyText(), /批次 IMP-CSX-001/));
  const hrefs = [...document.querySelectorAll('a')].map((a) => a.getAttribute('href') ?? '');
  assert.equal(hrefs.some((href) => href.includes('import_batch=')), false, '非法 batch_id 不得生成文件作业链接');
  assert.equal(hrefs.some((href) => href.includes('evil.invalid')), false);
});

test('channel cards render public copy and never render backend free text', async () => {
  const raw = 'leak /opt/zimu/scripts/caishixian_fetch_orders.py data-local/csx-credentials.txt CSX_PASSWORD <img src=x onerror=alert(1)>';
  globalThis.fetch = refreshOnlyFetch(refreshResult([
    channel({
      status: 'FAILED',
      batch_no: undefined,
      batch_id: undefined,
      row_counts: undefined,
      business_code: 'SCRIPT_FAILED',
      message: raw,
    }),
    channel({
      channel: 'JUFUBAO',
      status: 'SKIPPED',
      batch_no: undefined,
      batch_id: undefined,
      row_counts: undefined,
      business_code: 'UNKNOWN_SKIP_CODE',
      message: raw,
    }),
    channel({
      channel: 'FEIXIANG',
      status: 'OK',
      batch_no: 'IMP-FX-002',
      batch_id: '8',
      row_counts: { total: 12, accepted: 12, need_review: 0, rejected: 0 },
      business_code: 'OK',
      message: raw,
    }),
  ]));

  await harness.mount(['/workbench/shipping']);
  await harness.dispatchEvent(control('开始今日订单同步'), new MouseEvent('click', { bubbles: true }));
  await harness.waitFor(() => assert.match(harness.bodyText(), /彩食鲜/));
  await harness.waitFor(() => assert.match(harness.bodyText(), /聚福宝/));
  await harness.waitFor(() => assert.match(harness.bodyText(), /飞象/));
  await harness.waitFor(() => assert.match(harness.bodyText(), /失败/));
  await harness.waitFor(() => assert.match(harness.bodyText(), /已跳过/));
  await harness.waitFor(() => assert.match(harness.bodyText(), /成功/));
  await harness.waitFor(() => assert.match(harness.bodyText(), /该渠道拉取失败，请稍后重试/));
  await harness.waitFor(() => assert.match(harness.bodyText(), /该渠道已跳过本次拉取/));
  assert.doesNotMatch(
    harness.bodyText(),
    /caishixian_fetch_orders\.py|csx-credentials\.txt|CSX_PASSWORD|<img src=x onerror=alert\(1\)>/,
  );
});

test('invalid contracts drop batch/count fields and cannot pollute shipping summary totals', async () => {
  const unknownChannel = rawChannel({
    channel: '__proto__',
    status: 'FAILED',
    batch_no: 'IMP-PROTO-007',
    batch_id: '7',
    row_counts: { total: 30, accepted: 28, need_review: 2, rejected: 0 },
    order_count: 99,
    message: 'raw leak __proto__',
  });
  const unknownStatus = rawChannel({
    channel: 'CAISHIXIAN',
    status: 'toString',
    batch_no: 'IMP-CSX-008',
    batch_id: '8',
    row_counts: { total: 12, accepted: 12, need_review: 0, rejected: 0 },
    order_count: 5,
    business_code: 'SCRIPT_FAILED',
    message: 'raw leak toString',
  });
  const legalFeixiang = channel({
    channel: 'FEIXIANG',
    batch_no: 'IMP-FX-009',
    batch_id: '9',
    row_counts: { total: 4, accepted: 4, need_review: 0, rejected: 0 },
  });

  globalThis.fetch = refreshOnlyFetch(rawRefreshResult([unknownChannel, unknownStatus, legalFeixiang]));
  await harness.mount(['/workbench/shipping']);
  await harness.dispatchEvent(control('开始今日订单同步'), new MouseEvent('click', { bubbles: true }));
  await harness.waitFor(() => assert.match(harness.bodyText(), /飞象/));
  await harness.waitFor(() => assert.match(harness.bodyText(), /生成 1 个导入批次 · 共 4 行/));
  await harness.waitFor(() => assert.match(harness.bodyText(), /批次 IMP-FX-009/));
  await harness.waitFor(() => assert.match(harness.bodyText(), /同步结果格式异常/));
  await harness.waitFor(() => assert.match(harness.bodyText(), /渠道响应格式异常，请联系管理员/));
  await harness.waitFor(() => assert.match(harness.bodyText(), /响应异常/));
  assert.doesNotMatch(harness.bodyText(), /没有新订单|三平台已同步完成/);
  assert.doesNotMatch(harness.bodyText(), /批次 IMP-PROTO-007|批次 IMP-CSX-008/);
  assert.doesNotMatch(harness.bodyText(), /共 30 行|共 12 行|拉取 99 单|拉取 5 单/);
  assert.doesNotMatch(harness.bodyText(), /3 个导入批次|共 46 行|个渠道失败/);
  let hrefs = [...document.querySelectorAll('a')].map((a) => a.getAttribute('href') ?? '');
  assert.equal(
    hrefs.some((href) => href.includes('import_batch=7') || href.includes('import_batch=8')),
    false,
    '非法契约不得生成批次落点',
  );
  const legalCard = [...document.querySelectorAll<HTMLElement>('[role="button"]')]
    .find((element) => element.textContent?.includes('批次 IMP-FX-009'));
  assert.ok(legalCard, '合法飞象批次仍须可打开快照');
  await harness.dispatchEvent(legalCard, new MouseEvent('click', { bubbles: true }));
  await harness.waitFor(() => assert.match(harness.bodyText(), /飞象 · 批次快照/));
  hrefs = [...document.querySelectorAll('a')].map((a) => a.getAttribute('href') ?? '');
  assert.ok(hrefs.includes('/fulfillment/sales-outbound?import_batch=9'), '合法飞象快照应保留文件作业落点');
});

test('invalid-only contracts surface a page-level format error and never claim three platforms completed', async () => {
  const unknownSkipped = rawChannel({
    channel: 'ZHONGHUI',
    status: 'SKIPPED',
    batch_no: 'IMP-ZH-001',
    batch_id: '7',
    row_counts: { total: 8, accepted: 8, need_review: 0, rejected: 0 },
    order_count: 3,
    business_code: 'SKIPPED',
    message: '今日无数据 /tmp/zhonghui.py',
  });
  const badStatus = rawChannel({
    channel: 'CAISHIXIAN',
    status: 'WEIRD',
    batch_no: undefined,
    batch_id: undefined,
    row_counts: undefined,
    business_code: 'SKIPPED',
    message: '今日无数据',
  });

  globalThis.fetch = refreshOnlyFetch(rawRefreshResult([unknownSkipped, badStatus]));
  await harness.mount(['/workbench/shipping']);
  await harness.dispatchEvent(control('开始今日订单同步'), new MouseEvent('click', { bubbles: true }));

  await harness.waitFor(() => assert.match(harness.bodyText(), /同步结果格式异常/));
  await harness.waitFor(() => assert.match(harness.bodyText(), /渠道响应格式异常，请联系管理员/));
  await harness.waitFor(() => assert.match(harness.bodyText(), /响应异常/));
  await harness.waitFor(() => assert.match(harness.bodyText(), /未知渠道/));
  assert.doesNotMatch(harness.bodyText(), /没有新订单/);
  assert.doesNotMatch(harness.bodyText(), /三平台已同步完成/);
  assert.doesNotMatch(harness.bodyText(), /生成 \d+ 个导入批次/);
  assert.doesNotMatch(harness.bodyText(), /已跳过|该渠道已跳过本次拉取/);
  assert.doesNotMatch(harness.bodyText(), /批次 IMP-ZH-001|今日无数据|zhonghui\.py/);
  const hrefs = [...document.querySelectorAll('a')].map((a) => a.getAttribute('href') ?? '');
  assert.equal(hrefs.some((href) => href.includes('import_batch=')), false, '纯非法契约不得生成批次落点');
});

test('valid plus invalid contracts keep legal batches while contractErrorCount blocks the all-clear copy', async () => {
  const legalCaishixian = channel();
  const unknownSkipped = rawChannel({
    channel: 'WANQI',
    status: 'SKIPPED',
    batch_no: 'IMP-WQ-002',
    batch_id: '8',
    row_counts: { total: 9, accepted: 9, need_review: 0, rejected: 0 },
    order_count: 4,
    business_code: 'SKIPPED',
    message: '今日无数据 WANQI_PASSWORD',
  });

  globalThis.fetch = refreshOnlyFetch(rawRefreshResult([legalCaishixian, unknownSkipped]));
  await harness.mount(['/workbench/shipping']);
  await harness.dispatchEvent(control('开始今日订单同步'), new MouseEvent('click', { bubbles: true }));

  await harness.waitFor(() => assert.match(harness.bodyText(), /彩食鲜/));
  await harness.waitFor(() => assert.match(harness.bodyText(), /生成 1 个导入批次 · 共 30 行/));
  await harness.waitFor(() => assert.match(harness.bodyText(), /批次 IMP-CSX-001/));
  await harness.waitFor(() => assert.match(harness.bodyText(), /同步结果格式异常/));
  await harness.waitFor(() => assert.match(harness.bodyText(), /渠道响应格式异常，请联系管理员/));
  await harness.waitFor(() => assert.match(harness.bodyText(), /响应异常/));
  await harness.waitFor(() => assert.match(harness.bodyText(), /未知渠道/));
  assert.doesNotMatch(harness.bodyText(), /没有新订单|三平台已同步完成/);
  assert.doesNotMatch(harness.bodyText(), /批次 IMP-WQ-002|WANQI_PASSWORD|今日无数据/);
  const legalCard = [...document.querySelectorAll<HTMLElement>('[role="button"]')]
    .find((element) => element.textContent?.includes('批次 IMP-CSX-001'));
  assert.ok(legalCard, '合法彩食鲜批次仍须可打开快照');
  await harness.dispatchEvent(legalCard, new MouseEvent('click', { bubbles: true }));
  await harness.waitFor(() => assert.match(harness.bodyText(), /彩食鲜 · 批次快照/));
  const hrefs = [...document.querySelectorAll('a')].map((a) => a.getAttribute('href') ?? '');
  assert.ok(hrefs.includes('/fulfillment/sales-outbound?import_batch=7'), '合法彩食鲜快照应保留文件作业落点');
  assert.equal(hrefs.some((href) => href.includes('import_batch=8')), false, '非法契约批次不得生成落点');
});

test('success 200 with prototype channel/status keys stays crash-free without fake links or raw messages', async () => {
  const raw = 'leak /opt/zimu/scripts/caishixian_fetch_orders.py CSX_PASSWORD';
  globalThis.fetch = refreshOnlyFetch(rawRefreshResult([
    // 恶意运行时输入：channel/status 为原型键，走 unknown seam（refreshResult 本就接受 unknown[]）
    rawChannel({
      channel: '__proto__',
      status: 'OK',
      batch_no: 'IMP-PROTO-001',
      batch_id: '7',
      row_counts: { total: 30, accepted: 28, need_review: 2, rejected: 0 },
      message: raw,
    }),
    rawChannel({
      channel: 'constructor',
      status: 'FAILED',
      batch_no: undefined,
      batch_id: undefined,
      row_counts: undefined,
      business_code: 'toString',
      message: raw,
    }),
    rawChannel({
      channel: 'CAISHIXIAN',
      status: 'toString',
      batch_no: 'IMP-CSX-001',
      batch_id: '8',
      row_counts: { total: 12, accepted: 12, need_review: 0, rejected: 0 },
      business_code: '__proto__',
      message: raw,
    }),
    channel({
      channel: 'JUFUBAO',
      status: 'OK',
      batch_no: undefined,
      batch_id: undefined,
      row_counts: undefined,
      order_count: 41,
      message: '缺收货人字段，仅报告未入库 /tmp/jufubao.py',
    }),
    channel({
      channel: 'FEIXIANG',
      batch_no: 'IMP-FX-002',
      batch_id: '9',
      row_counts: { total: 12, accepted: 12, need_review: 0, rejected: 0 },
    }),
  ]));

  await harness.mount(['/workbench/shipping']);
  await harness.dispatchEvent(control('开始今日订单同步'), new MouseEvent('click', { bubbles: true }));

  await harness.waitFor(() => assert.match(harness.bodyText(), /未知渠道/));
  await harness.waitFor(() => assert.match(harness.bodyText(), /响应异常/));
  await harness.waitFor(() => assert.match(harness.bodyText(), /彩食鲜/));
  await harness.waitFor(() => assert.match(harness.bodyText(), /聚福宝/));
  await harness.waitFor(() => assert.match(harness.bodyText(), /飞象/));
  await harness.waitFor(() => assert.match(harness.bodyText(), /渠道响应格式异常，请联系管理员/));
  await harness.waitFor(() => assert.match(harness.bodyText(), /同步结果格式异常/));
  await harness.waitFor(() => assert.match(harness.bodyText(), /仅报告未入库/));
  await harness.waitFor(() => assert.match(harness.bodyText(), /批次 IMP-FX-002/));
  assert.doesNotMatch(harness.bodyText(), /该渠道刷新失败，请稍后重试/);
  assert.doesNotMatch(harness.bodyText(), /没有新订单|三平台已同步完成/);
  let hrefs = [...document.querySelectorAll('a')].map((a) => a.getAttribute('href') ?? '');
  assert.equal(
    hrefs.some((href) => href.includes('import_batch=7') || href.includes('import_batch=8')),
    false,
    '原型键渠道不得生成批次落点',
  );
  const legalCard = [...document.querySelectorAll<HTMLElement>('[role="button"]')]
    .find((element) => element.textContent?.includes('批次 IMP-FX-002'));
  assert.ok(legalCard, '合法飞象批次仍须可打开快照');
  await harness.dispatchEvent(legalCard, new MouseEvent('click', { bubbles: true }));
  await harness.waitFor(() => assert.match(harness.bodyText(), /飞象 · 批次快照/));
  hrefs = [...document.querySelectorAll('a')].map((a) => a.getAttribute('href') ?? '');
  assert.ok(hrefs.includes('/fulfillment/sales-outbound?import_batch=9'), '合法飞象快照应保留文件作业落点');
  assert.doesNotMatch(harness.bodyText(), /caishixian_fetch_orders\.py|CSX_PASSWORD|jufubao\.py|raw leak/);
  assert.doesNotMatch(harness.bodyText(), /\[object |native code|function Object/);
  assert.ok(document.body, '原型键不得让 DOM 崩溃');
  assert.match(harness.bodyText(), /今日发货工作台/);
});
