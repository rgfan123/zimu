/**
 * 工作台「待确认发货」入口的路由契约。
 *
 * 为什么要有这块：此前批次只能按 id 打开——上传完当场拿到，或手工拼
 * `?import_batch=` 链接。昨天没确认完的批次在界面上根本找不到，确认发货实际
 * 只能靠企业微信卡片触发（用户原话：「不然我每天操作什么」）。
 *
 * 覆盖：清单可见且带就绪/阻断计数 / 有阻断行仍可确认（部分确认）/ 阻断原因可展开
 * 查看 / 没有可发货行时禁用 / 确认后提示跳过了几行并说明可补做 / 无待办时整块不出现。
 */

import assert from 'node:assert/strict';
import { after, afterEach, before, test } from 'node:test';
import { control, createRouteHarness, jsonResponse, type RouteHarness } from './routeHarness.ts';

let harness: RouteHarness;

const PENDING_URL = '/api/v1/import-batches/pending-confirmation';

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

function pendingBatch(overrides: Record<string, unknown> = {}) {
  const readyRows = (overrides.ready_rows as number) ?? 4;
  const blockedRows = (overrides.blocked_rows as number) ?? 1;
  const pendingRows = (overrides.pending_rows as number) ?? readyRows;
  return {
    id: '7',
    batch_no: 'IMP-CSX-7',
    original_file_name: 'caishixian-deliver-2026-08-28.xlsx',
    status: 'COMPLETED_WITH_REVIEW',
    source_channel: 'CAISHIXIAN',
    source_channel_display_name: '彩食鲜',
    received_at: '2026-08-28T01:00:00Z',
    confirmed_at: null,
    confirmed_by: null,
    total_rows: readyRows + blockedRows,
    ready_rows: readyRows,
    blocked_rows: blockedRows,
    benign_skipped_rows: 0,
    pending_rows: pendingRows,
    confirmable: pendingRows > 0,
    partial: pendingRows > 0 && blockedRows > 0,
    ...overrides,
  };
}

/** 批次详情：阻断原因只在展开时取，清单接口只带计数。 */
function batchDetail() {
  return {
    id: '7',
    batch_no: 'IMP-CSX-7',
    batch_type: 'SOURCE_ORDER',
    import_mode: 'NEW',
    revision_no: 1,
    source_channel: 'CAISHIXIAN',
    source_channel_display_name: '彩食鲜',
    template_family: 'CSX_ORDER',
    template_version: '1',
    template_fingerprint: 'fixture',
    original_file_name: 'caishixian-deliver-2026-08-28.xlsx',
    content_sha256: 'c'.repeat(64),
    status: 'COMPLETED_WITH_REVIEW',
    confirmed_at: null,
    confirmed_by: null,
    settlement_missing: false,
    row_counts: { total: 5, accepted: 4, need_review: 1, rejected: 0 },
    confirm_readiness: {
      ready_rows: 4,
      pending_rows: 4,
      blocked_rows: 1,
      benign_skipped_rows: 0,
      confirmable: true,
      partial: true,
      blockers: [
        {
          row_id: '75',
          source_order_ref: 'CSX-ORDER-005',
          status: 'NEED_REVIEW',
          error_code: 'SKU_MATCH',
          reason: '缺少 SKU 映射',
        },
      ],
    },
    generated_fulfillment_export_ids: [],
    received_at: '2026-08-28T01:00:00Z',
  };
}

/** 工作台其余区块的请求一律给空数据，只留待确认区做断言。 */
function workbenchFetch(
  requests: string[],
  pending: unknown,
  onConfirm?: () => unknown,
) {
  return async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    requests.push(`${init?.method ?? 'GET'} ${url}`);
    if (url.startsWith(PENDING_URL)) return jsonResponse(pending);
    if (url === '/api/v1/import-batches/7') return jsonResponse(batchDetail());
    if (url === '/api/v1/import-batches/7/confirm' && init?.method === 'POST') {
      return jsonResponse(onConfirm ? onConfirm() : {});
    }
    if (url.startsWith('/api/v1/review-cases')) {
      return jsonResponse({ items: [], page: 0, size: 50, total_elements: 0, total_pages: 0 });
    }
    if (url.startsWith('/api/v1/shipments') || url.startsWith('/api/v1/fulfillment-providers')) {
      return jsonResponse({ items: [], page: 0, size: 20, total_elements: 0, total_pages: 0 });
    }
    if (url.startsWith('/api/v1/operational-alerts')) {
      return jsonResponse({ items: [], page: 0, size: 20, total_elements: 0, total_pages: 0 });
    }
    return jsonResponse({ items: [], page: 0, size: 20, total_elements: 0, total_pages: 0 });
  };
}

test('待确认批次出现在工作台，带就绪与待处理行数', async () => {
  const requests: string[] = [];
  globalThis.fetch = workbenchFetch(requests, { items: [pendingBatch()] });

  await harness.mount(['/workbench/shipping']);

  await harness.waitFor(() => assert.match(harness.bodyText(), /待确认发货/));
  assert.match(harness.bodyText(), /IMP-CSX-7/);
  assert.match(harness.bodyText(), /已就绪 4 行/);
  assert.match(harness.bodyText(), /1 行待处理/);
  assert.ok(requests.some((request) => request.includes(PENDING_URL)), '必须拉待确认清单');
});

test('有阻断行时确认按钮仍可用——阻断行被跳过而不是挡住整批', async () => {
  const requests: string[] = [];
  globalThis.fetch = workbenchFetch(requests, { items: [pendingBatch({ blocked_rows: 1 })] });

  await harness.mount(['/workbench/shipping']);

  await harness.waitFor(() => assert.match(harness.bodyText(), /确认发货（4 行）/));
  const button = control('确认发货（4 行）');
  assert.equal(button.hasAttribute('disabled'), false, '有就绪行就该能确认');
});

test('没有可发货行时禁用，并说明被什么挡住', async () => {
  const requests: string[] = [];
  globalThis.fetch = workbenchFetch(requests, {
    items: [pendingBatch({ ready_rows: 0, pending_rows: 0, blocked_rows: 3 })],
  });

  await harness.mount(['/workbench/shipping']);

  await harness.waitFor(() => assert.match(harness.bodyText(), /确认发货（0 行）/));
  const button = control('确认发货（0 行）');
  assert.equal(button.hasAttribute('disabled'), true, '没有就绪行不该能确认');
});

test('阻断原因可展开查看，不是只给一个数字', async () => {
  const requests: string[] = [];
  globalThis.fetch = workbenchFetch(requests, { items: [pendingBatch()] });

  await harness.mount(['/workbench/shipping']);
  await harness.waitFor(() => assert.match(harness.bodyText(), /看待处理原因/));

  await control('看待处理原因').click();

  await harness.waitFor(() => assert.match(harness.bodyText(), /缺少 SKU 映射/));
  assert.match(harness.bodyText(), /CSX-ORDER-005/);
  assert.ok(requests.includes('GET /api/v1/import-batches/7'), '展开时才取批次详情');
});

test('确认后明确告知跳过了哪几行以及可以补做', async () => {
  const requests: string[] = [];
  globalThis.fetch = workbenchFetch(requests, { items: [pendingBatch()] }, () => ({
    ...batchDetail(),
    confirmed_at: '2026-08-28T02:00:00Z',
    confirmed_by: 'ops',
    skipped_rows: [
      {
        row_id: '75',
        source_order_ref: 'CSX-ORDER-005',
        status: 'NEED_REVIEW',
        error_code: 'SKU_MATCH',
        reason: '缺少 SKU 映射',
      },
    ],
  }));

  await harness.mount(['/workbench/shipping']);
  await harness.waitFor(() => assert.match(harness.bodyText(), /确认发货（4 行）/));

  await control('确认发货（4 行）').click();
  await harness.waitFor(() =>
    assert.match(harness.bodyText(), /1 行因待处理被跳过，仍留在本批次/));

  const okButton = [...document.querySelectorAll<HTMLElement>('.ant-popconfirm-buttons button')]
    .find((candidate) => candidate.textContent?.includes('确认发货'));
  assert.ok(okButton, '缺少 popconfirm 确认按钮');
  await harness.dispatchEvent(okButton, new MouseEvent('click', { bubbles: true }));

  await harness.waitFor(() => {
    assert.ok(requests.includes('POST /api/v1/import-batches/7/confirm'), '必须发出确认请求');
    assert.match(harness.bodyText(), /处理完后可再次确认补做/);
  });
});

test('没有待确认批次时整块不出现，不占第一屏', async () => {
  const requests: string[] = [];
  globalThis.fetch = workbenchFetch(requests, { items: [] });

  await harness.mount(['/workbench/shipping']);

  await harness.waitFor(() => assert.match(harness.bodyText(), /今日发货工作台/));
  assert.doesNotMatch(harness.bodyText(), /待确认发货/);
});
