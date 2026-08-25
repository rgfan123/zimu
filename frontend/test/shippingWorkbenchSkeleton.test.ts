import assert from 'node:assert/strict';
import test, { after, afterEach, before } from 'node:test';
import { createRouteHarness, jsonResponse, page, reviewCaseFixture, type RouteHarness } from './routeHarness.ts';

/**
 * Issue #108 骨架先行（ADR 0005/0006）：发货台一次立起原型全骨架——七指标、八段链路、
 * 复核分组、告警区；计数走既有列表接口 size=1 拼真数，拼不出的段位就地「暂无汇总」诚实态。
 * 同步动线的锁定契约在 shippingWorkbenchRoute.test.ts，本文件只测骨架新增面。
 */

let harness: RouteHarness;

before(async () => {
  harness = await createRouteHarness('http://localhost/workbench/shipping');
});

after(async () => {
  await harness.close();
});

afterEach(async () => {
  await harness.unmount();
});

/** 按 URL 分流的计数桩：每个口径给不同的可辨识数字。 */
function stubCounts() {
  globalThis.fetch = async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url.includes('/api/v1/review-cases')) {
      if (url.includes('size=1')) return jsonResponse({ ...page([]), total_elements: 3 });
      return jsonResponse({
        ...page([
          reviewCaseFixture('1', { reasonCode: 'SKU_MAPPING_REQUIRED' }),
          reviewCaseFixture('2', { reasonCode: 'SKU_MAPPING_REQUIRED' }),
          reviewCaseFixture('3', { reasonCode: 'CUSTOMER_MATCH_REQUIRED' }),
        ]),
        total_elements: 3,
      });
    }
    if (url.includes('/api/v1/operational-alerts')) {
      return jsonResponse({
        ...page([{ id: '9', alert_type: 'JD_SHIPMENT_OUTBOUND_SUBMIT_FAILED', status: 'OPEN', created_at: '2026-08-23T01:00:00Z' }]),
        total_elements: 1,
      });
    }
    if (url.includes('/api/v1/shipments')) return jsonResponse({ ...page([]), total_elements: 9 });
    if (url.includes('/api/v1/orders')) {
      if (url.includes('processing_stage=NEED_REVIEW')) return jsonResponse({ ...page([]), total_elements: 14 });
      if (url.includes('processing_stage=READY_TO_EXPORT')) return jsonResponse({ ...page([]), total_elements: 39 });
      if (url.includes('processing_stage=WAITING_PROVIDER')) return jsonResponse({ ...page([]), total_elements: 68 });
      if (url.includes('processing_stage=TRACKING_RECEIVED')) return jsonResponse({ ...page([]), total_elements: 4 });
      if (url.includes('processing_stage=RETURN_FILE_READY')) return jsonResponse({ ...page([]), total_elements: 6 });
      return jsonResponse({ ...page([]), total_elements: 213 });
    }
    return jsonResponse(page([]));
  };
}

test('骨架一次全部在位：七指标真数、八段链路、占位段诚实态', async () => {
  window.localStorage.clear();
  stubCounts();
  await harness.mount(['/workbench/shipping']);

  await harness.waitFor(() => assert.match(harness.bodyText(), /39/, '待发货计数（READY_TO_EXPORT total）必须渲染'));
  const body = harness.bodyText();

  // 七指标
  for (const label of ['已入库订单', '仅报告未入库', '待我人工复核', '待发货', '发货中', '已发货已回填', '回填失败']) {
    assert.match(body, new RegExp(label), `指标「${label}」必须在位`);
  }
  assert.match(body, /213/, '今日已入库计数');
  assert.match(body, /68/, '发货中计数');

  // 八段链路
  for (const segment of ['平台拉取', '落导入批次', 'SKU \/ 客户识别', '整批确认', '京东出库提交', '运单回填', '来源回填表', '回传来源平台']) {
    assert.match(body, new RegExp(segment), `链路段「${segment}」必须在位`);
  }
  assert.match(body, /暂无汇总/, '拼不出的段位显示诚实占位而不是留空或假数');
  assert.match(body, /异常需介入/, '五态图例在位');

  // #115 口径：平台拉单本无每日次数与最小间隔，页面不呈现任何配额说法
  //（含「未暴露剩余额度」——那本身也在暗示存在配额制度）。
  assert.doesNotMatch(body, /配额|今日剩|剩余拉取额度|下次可拉取/, '不得出现任何配额说法');

  // 密度优先（ADR 0005）：解释性 lede 与 hero 长段落删除
  assert.doesNotMatch(body, /并逐渠道如实显示结果/, '旧 LEDE 解释句必须删除');
  assert.doesNotMatch(body, /不会偷偷跳过/, 'hero 解释段落必须删除');
});

test('复核区按 reason_code 分组、京东门禁组 0 项保留、跳转与计数同接口同筛选', async () => {
  window.localStorage.clear();
  stubCounts();
  await harness.mount(['/workbench/shipping']);

  await harness.waitFor(() => assert.match(harness.bodyText(), /SKU 映射待确认/));
  const body = harness.bodyText();

  assert.match(body, /客户映射待确认/, '分组标签取自 REASON_LABELS');
  assert.match(body, /这一类保留是因为它出现时必须第一时间看见/, '京东门禁组 0 项保留可见');

  const inboxLink = [...document.querySelectorAll<HTMLAnchorElement>('a')].find((a) =>
    a.getAttribute('href') === '/workbench/reviews?status=OPEN');
  assert.ok(inboxLink, '收件箱入口必须是 status=OPEN 的真实链接');

  const groupLink = [...document.querySelectorAll<HTMLAnchorElement>('a')].find((a) =>
    (a.getAttribute('href') ?? '').includes('reason_code=SKU_MAPPING_REQUIRED'));
  assert.ok(groupLink, '分组跳转必须带 reason_code 预筛（同接口同筛选）');
  assert.match(groupLink.getAttribute('href') ?? '', /status=OPEN/);
});

test('告警区显示打开的运营告警并链接提醒中心', async () => {
  window.localStorage.clear();
  stubCounts();
  await harness.mount(['/workbench/shipping']);

  await harness.waitFor(() => assert.match(harness.bodyText(), /JD_SHIPMENT_OUTBOUND_SUBMIT_FAILED/));
  const alertsLink = [...document.querySelectorAll<HTMLAnchorElement>('a')].find((a) =>
    a.getAttribute('href') === '/workbench/alerts');
  assert.ok(alertsLink, '提醒中心入口必须存在');
});

/**
 * 京东建单阻塞项就地可见（发货台一页闭环）：
 * 事故当天的动线是「发货台看不到原因 → 跳发货单页看到一行长文本 → 跳系统管理」。
 * 本例锁住第一段：结构化阻塞必须在发货台本页按修复位置分组显示，并给出就地入口。
 */
test('预览阻断事项在发货台展开真实阻塞，并提供就地处置入口', async () => {
  window.localStorage.clear();
  const blockers = [
    {
      code: 'JD_SHIPMENT_OUTBOUND_CONFIG_MISSING',
      path: 'sourceNo',
      source: 'fulfillment_providers.config.sourceNo',
      correction_target: 'fulfillment provider configuration',
      message: '履约方配置缺少京东标识 sourceNo，请先补齐后再建单',
    },
    {
      code: 'JD_SHIPMENT_OUTBOUND_CUSTOMER_CODE_MISSING',
      path: 'customerInfo.customerCode',
      source: 'fulfillment_providers.config.customerCode',
      correction_target: 'fulfillment provider configuration',
      message: '履约方配置缺少青龙业主号 customerCode，请先补齐',
    },
    {
      code: 'JD_SHIPMENT_OUTBOUND_CONFIG_MISSING',
      path: 'receiverInfo.townPolicy',
      source: 'fulfillment_providers.config.townRequired',
      correction_target: 'fulfillment provider address policy',
      message: '履约方配置缺少显式乡镇必填策略 townRequired',
    },
  ];
  globalThis.fetch = async (input: RequestInfo | URL) => {
    const url = String(input);
    if (url.includes('/api/v1/review-cases')) {
      if (url.includes('size=1')) return jsonResponse({ ...page([]), total_elements: 1 });
      return jsonResponse({
        ...page([
          reviewCaseFixture('77', {
            reasonCode: 'JD_SHIPMENT_OUTBOUND_PREVIEW_BLOCKED',
            team: 'FULFILLMENT_OPS',
            caseNo: 'RC-JD-PREVIEW-DEMO',
            subjectType: 'SHIPMENT',
            subjectId: '1',
            detail: { message: '京东出库请求预览存在阻断项', blockers },
          }),
        ]),
        total_elements: 1,
      });
    }
    return jsonResponse(page([]));
  };

  await harness.mount(['/workbench/shipping']);
  await harness.waitFor(() => assert.match(harness.bodyText(), /RC-JD-PREVIEW-DEMO/, '阻塞事项必须渲染'));
  const text = harness.bodyText();

  assert.ok(text.includes('RC-JD-PREVIEW-DEMO'), '发货台应显示事项编号，而不是只给一个计数');
  assert.ok(text.includes('履约方配置 2 项'), '阻塞必须按修复位置分组，而不是拼成一行长文本');
  assert.ok(text.includes('履约方地址策略 1 项'), '第二个修复位置也要独立成组');
  assert.ok(text.includes('就地处置'), '必须提供就地入口，而不是把人踢去别的页面');

  // 三条阻塞里有两个不同 code 却同属「履约方配置」——按 code 分组会拆成三组，这里锁住不会
  assert.ok(!text.includes('履约方配置 1 项'), '同一修复位置的不同 code 不得被拆开');
});
