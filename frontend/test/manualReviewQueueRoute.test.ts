import assert from 'node:assert/strict';
import { after, afterEach, before, test } from 'node:test';
import {
  control,
  createRouteHarness,
  apiErrorResponse,
  jsonResponse,
  page,
  reviewCaseFixture,
  type RouteHarness,
} from './routeHarness.ts';

let harness: RouteHarness;

before(async () => {
  harness = await createRouteHarness('http://localhost/workbench/reviews');
});

afterEach(async () => {
  await harness.unmount();
});

after(async () => {
  await harness.close();
});

function operationalAlert(id: string) {
  return {
    id,
    alert_no: `ALERT-Q-${id}`,
    alert_type: 'PROCUREMENT_REQUIRED',
    severity: 'YELLOW',
    status: 'OPEN',
    order_id: '101',
    message: '库存不足，已创建采购工单',
    detail: {},
    version: 0,
    created_at: '2026-08-20T02:00:00Z',
  };
}

function batchReviewCase(
  id: string,
  reasonCode: string,
  allowedActions: Array<'RESOLVE_MANUALLY' | 'DISMISS'> = ['RESOLVE_MANUALLY', 'DISMISS'],
) {
  return {
    ...reviewCaseFixture(id, { reasonCode, team: 'ORDER_OPS', caseNo: `RC-BATCH-${id}` }),
    allowed_actions: allowedActions,
    version: Number(id),
  };
}

/** 复核队列页 mock：按收到的筛选参数返回对应队列；提醒列表按状态返回（兼容重定向测试）。 */
function reviewsFetch(requests: string[]) {
  return async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    requests.push(`${init?.method ?? 'GET'} ${url}`);
    if (url.startsWith('/api/v1/review-cases?')) {
      const params = new URLSearchParams(url.split('?')[1]);
      const items = params.get('reason_code') === 'SKU_MAPPING_REQUIRED'
        ? [reviewCaseFixture('1', {
            reasonCode: 'SKU_MAPPING_REQUIRED',
            team: params.get('responsible_team') ?? 'SKU_OPS',
          })]
        : [];
      return jsonResponse(page(items, Number(params.get('size') ?? 20)));
    }
    if (url.startsWith('/api/v1/operational-alerts?')) {
      const params = new URLSearchParams(url.split('?')[1]);
      const status = params.get('status') ?? 'OPEN';
      return jsonResponse(page(status === 'RESOLVED' ? [] : [operationalAlert('9')]));
    }
    throw new Error(`unexpected request: ${url}`);
  };
}

test('status/reason_code/responsible_team 从 URL 恢复并实际影响队列请求', async () => {
  const requests: string[] = [];
  globalThis.fetch = reviewsFetch(requests);

  await harness.mount(['/workbench/reviews?status=OPEN&reason_code=SKU_MAPPING_REQUIRED&responsible_team=SKU_OPS']);

  await harness.waitFor(() => assert.match(harness.bodyText(), /RC-FIXTURE-1/));
  assert.ok(requests.includes(
    'GET /api/v1/review-cases?page=0&size=20&status=OPEN&reason_code=SKU_MAPPING_REQUIRED&responsible_team=SKU_OPS',
  ), '队列请求必须带 URL 中的全部筛选');
  assert.match(harness.bodyText(), /SKU 映射待确认/, '事项类型筛选控件显示 URL 中的值');
  assert.match(harness.bodyText(), /商品运营/, '责任团队筛选控件显示 URL 中的值');
});

test('复核主队列支持行选择，运营提醒队列不传选择能力时保持原状', async () => {
  const requests: string[] = [];
  globalThis.fetch = reviewsFetch(requests);

  await harness.mount(['/workbench/reviews?status=OPEN&reason_code=SKU_MAPPING_REQUIRED']);
  await harness.waitFor(() => assert.match(harness.bodyText(), /RC-FIXTURE-1/));
  assert.ok(
    document.querySelectorAll<HTMLInputElement>('.ant-table input[type="checkbox"]').length >= 2,
    '复核队列必须渲染表头全选与行选择框',
  );

  await harness.unmount();
  await harness.mount(['/workbench/alerts?status=OPEN']);
  await harness.waitFor(() => assert.match(harness.bodyText(), /ALERT-Q-9/));
  assert.equal(
    document.querySelectorAll<HTMLInputElement>('.ant-table input[type="checkbox"]').length,
    0,
    '运营提醒未传 rowSelection 时不得新增选择列',
  );
});

test('跨事项类型混选时禁用批量动作并明确说明同类限制', async () => {
  const rows = [
    batchReviewCase('1', 'SYNC_FAILED'),
    batchReviewCase('2', 'WECOM_TRACKING_FILE_REVIEW'),
  ];
  globalThis.fetch = async (input) => {
    const url = String(input);
    if (url.startsWith('/api/v1/review-cases?')) return jsonResponse(page(rows));
    throw new Error(`unexpected request: ${url}`);
  };

  await harness.mount(['/workbench/reviews?status=OPEN']);
  await harness.waitFor(() => assert.match(harness.bodyText(), /RC-BATCH-2/));
  const selectAll = document.querySelector<HTMLInputElement>('.ant-table-thead input[type="checkbox"]');
  assert.ok(selectAll, '复核队列必须提供全选框');
  await harness.dispatchEvent(selectAll, new MouseEvent('click', { bubbles: true }));

  await harness.waitFor(() => assert.match(harness.bodyText(), /批量处理需选择同类事项/));
  assert.notEqual(control('批量标记已处理（2）').getAttribute('disabled'), null);
  assert.notEqual(control('批量关闭（2）').getAttribute('disabled'), null);
});

test('批量逐行独立提交：幂等键独立，成功行移除，失败行可见且可重试', async () => {
  let openRows = [
    batchReviewCase('1', 'SYNC_FAILED', ['RESOLVE_MANUALLY']),
    batchReviewCase('2', 'SYNC_FAILED', ['RESOLVE_MANUALLY']),
  ];
  let secondAttempts = 0;
  const writeRequests: Array<{ url: string; idempotencyKey: string | null; body: unknown }> = [];
  globalThis.fetch = async (input, init) => {
    const url = String(input);
    const method = init?.method ?? 'GET';
    if (url.startsWith('/api/v1/review-cases?') && method === 'GET') return jsonResponse(page(openRows));
    if (url.match(/^\/api\/v1\/review-cases\/[12]\/resolve$/) && method === 'POST') {
      const id = url.split('/')[4];
      writeRequests.push({
        url,
        idempotencyKey: new Headers(init?.headers).get('Idempotency-Key'),
        body: JSON.parse(String(init?.body)),
      });
      if (id === '2' && secondAttempts++ === 0) {
        return apiErrorResponse(409, 'VERSION_CONFLICT', '版本冲突');
      }
      openRows = openRows.filter((row) => row.id !== id);
      return jsonResponse({ ...batchReviewCase(id, 'SYNC_FAILED'), status: 'RESOLVED' });
    }
    throw new Error(`unexpected request: ${method} ${url}`);
  };

  await harness.mount(['/workbench/reviews?status=OPEN']);
  await harness.waitFor(() => assert.match(harness.bodyText(), /RC-BATCH-2/));
  const selectAll = document.querySelector<HTMLInputElement>('.ant-table-thead input[type="checkbox"]');
  assert.ok(selectAll);
  await harness.dispatchEvent(selectAll, new MouseEvent('click', { bubbles: true }));

  const cryptoDescriptor = Object.getOwnPropertyDescriptor(globalThis, 'crypto');
  let randomFill = 0;
  Object.defineProperty(globalThis, 'crypto', {
    configurable: true,
    value: {
      getRandomValues(array: Uint8Array) {
        array.fill(++randomFill);
        return array;
      },
    },
  });
  try {
    await harness.dispatchEvent(control('批量标记已处理（2）'), new MouseEvent('click', { bubbles: true }));
    await harness.waitFor(() => assert.match(harness.bodyText(), /成功 1 项，失败 1 项/));
    assert.doesNotMatch(harness.bodyText(), /RC-BATCH-1/, '成功行必须从当前界面移除');
    assert.match(harness.bodyText(), /RC-BATCH-2/, '失败行必须留在当前界面');
    assert.match(harness.bodyText(), /数据已被其他操作更新/, '失败原因必须留在界面可查');

    await harness.dispatchEvent(control('批量标记已处理（1）'), new MouseEvent('click', { bubbles: true }));
    await harness.waitFor(() => assert.match(harness.bodyText(), /成功 1 项，失败 0 项/));
    assert.doesNotMatch(harness.bodyText(), /RC-BATCH-2/, '重试成功后失败行才移除');
  } finally {
    if (cryptoDescriptor) Object.defineProperty(globalThis, 'crypto', cryptoDescriptor);
    else delete (globalThis as { crypto?: unknown }).crypto;
  }

  assert.deepEqual(writeRequests.map((request) => request.url), [
    '/api/v1/review-cases/1/resolve',
    '/api/v1/review-cases/2/resolve',
    '/api/v1/review-cases/2/resolve',
  ]);
  assert.deepEqual(writeRequests.map((request) => request.body), [
    { expected_version: 1, note: '' },
    { expected_version: 2, note: '' },
    { expected_version: 2, note: '' },
  ]);
  assert.ok(writeRequests.every((request) => request.idempotencyKey), '每行请求必须带幂等键');
  assert.equal(
    new Set(writeRequests.map((request) => request.idempotencyKey)).size,
    3,
    '每行及重试都必须使用独立幂等键',
  );
});

test('单条处理成功后 Drawer 可直接载入当前筛选下的下一条', async () => {
  let rows = [
    batchReviewCase('1', 'SYNC_FAILED', ['RESOLVE_MANUALLY']),
    batchReviewCase('2', 'SYNC_FAILED', ['RESOLVE_MANUALLY']),
  ];
  globalThis.fetch = async (input, init) => {
    const url = String(input);
    const method = init?.method ?? 'GET';
    if (url.startsWith('/api/v1/review-cases?') && method === 'GET') return jsonResponse(page(rows));
    if (url === '/api/v1/review-cases/1/resolve' && method === 'POST') {
      rows = rows.filter((row) => row.id !== '1');
      return jsonResponse({ ...batchReviewCase('1', 'SYNC_FAILED'), status: 'RESOLVED' });
    }
    throw new Error(`unexpected request: ${method} ${url}`);
  };

  await harness.mount(['/workbench/reviews?status=OPEN']);
  await harness.waitFor(() => assert.match(harness.bodyText(), /RC-BATCH-2/));
  await harness.dispatchEvent(control('查看处理'), new MouseEvent('click', { bubbles: true }));
  await harness.waitFor(() => assert.match(harness.bodyText(), /复核事项 RC-BATCH-1/));
  await harness.dispatchEvent(control('标记已处理'), new MouseEvent('click', { bubbles: true }));

  await harness.waitFor(() => assert.match(harness.bodyText(), /当前事项已处理/));
  assert.ok(control('处理下一条'));
  await harness.dispatchEvent(control('处理下一条'), new MouseEvent('click', { bubbles: true }));
  await harness.waitFor(() => assert.match(harness.bodyText(), /复核事项 RC-BATCH-2/));
  assert.match(harness.bodyText(), /标记已处理/, '下一条必须直接载入原有单条处理表单');
});

test('页末事项处理后，刷新出的后续页首项仍可作为下一条连续处理', async () => {
  const allRows = Array.from({ length: 21 }, (_, index) => (
    batchReviewCase(String(index + 1), 'SYNC_FAILED', ['RESOLVE_MANUALLY'])
  ));
  let resolved = false;
  globalThis.fetch = async (input, init) => {
    const url = String(input);
    const method = init?.method ?? 'GET';
    if (url.startsWith('/api/v1/review-cases?') && method === 'GET') {
      const items = resolved ? [...allRows.slice(0, 19), allRows[20]] : allRows.slice(0, 20);
      return jsonResponse({
        items,
        page: 0,
        size: 20,
        total_elements: resolved ? 20 : 21,
        total_pages: resolved ? 1 : 2,
      });
    }
    if (url === '/api/v1/review-cases/20/resolve' && method === 'POST') {
      resolved = true;
      return jsonResponse({ ...allRows[19], status: 'RESOLVED' });
    }
    throw new Error(`unexpected request: ${method} ${url}`);
  };

  await harness.mount(['/workbench/reviews?status=OPEN']);
  await harness.waitFor(() => assert.match(harness.bodyText(), /RC-BATCH-20/));
  const row20 = [...document.querySelectorAll<HTMLTableRowElement>('.ant-table-tbody tr')]
    .find((row) => row.textContent?.includes('RC-BATCH-20'));
  const openRow20 = [...(row20?.querySelectorAll<HTMLElement>('a') ?? [])]
    .find((link) => link.textContent?.includes('查看处理'));
  assert.ok(openRow20, '当前页末行必须可打开');
  await harness.dispatchEvent(openRow20, new MouseEvent('click', { bubbles: true }));
  await harness.waitFor(() => assert.match(harness.bodyText(), /复核事项 RC-BATCH-20/));
  await harness.dispatchEvent(control('标记已处理'), new MouseEvent('click', { bubbles: true }));

  await harness.waitFor(() => assert.ok(control('处理下一条')));
  await harness.dispatchEvent(control('处理下一条'), new MouseEvent('click', { bubbles: true }));
  await harness.waitFor(() => assert.match(harness.bodyText(), /复核事项 RC-BATCH-21/));
});

test('旧事项写请求晚到时不会覆盖新打开事项的 Drawer 状态', async () => {
  const rows = [
    batchReviewCase('1', 'SYNC_FAILED', ['RESOLVE_MANUALLY']),
    batchReviewCase('2', 'SYNC_FAILED', ['RESOLVE_MANUALLY']),
  ];
  let finishResolve: (() => void) | undefined;
  globalThis.fetch = async (input, init) => {
    const url = String(input);
    const method = init?.method ?? 'GET';
    if (url.startsWith('/api/v1/review-cases?') && method === 'GET') return jsonResponse(page(rows));
    if (url === '/api/v1/review-cases/1/resolve' && method === 'POST') {
      return new Promise<Response>((resolve) => {
        finishResolve = () => resolve(jsonResponse({ ...rows[0], status: 'RESOLVED' }));
      });
    }
    throw new Error(`unexpected request: ${method} ${url}`);
  };

  await harness.mount(['/workbench/reviews?status=OPEN']);
  await harness.waitFor(() => assert.match(harness.bodyText(), /RC-BATCH-2/));
  await harness.dispatchEvent(control('查看处理'), new MouseEvent('click', { bubbles: true }));
  await harness.waitFor(() => assert.match(harness.bodyText(), /复核事项 RC-BATCH-1/));
  await harness.dispatchEvent(control('标记已处理'), new MouseEvent('click', { bubbles: true }));
  assert.ok(finishResolve, '第一条写请求必须保持待返回');

  const closeDrawer = document.querySelector<HTMLButtonElement>('.ant-drawer-close');
  assert.ok(closeDrawer);
  await harness.dispatchEvent(closeDrawer, new MouseEvent('click', { bubbles: true }));
  const row2 = [...document.querySelectorAll<HTMLTableRowElement>('.ant-table-tbody tr')]
    .find((row) => row.textContent?.includes('RC-BATCH-2'));
  const openRow2 = [...(row2?.querySelectorAll<HTMLElement>('a') ?? [])]
    .find((link) => link.textContent?.includes('查看处理'));
  assert.ok(openRow2);
  await harness.dispatchEvent(openRow2, new MouseEvent('click', { bubbles: true }));
  await harness.waitFor(() => assert.match(harness.bodyText(), /复核事项 RC-BATCH-2/));

  finishResolve();
  await new Promise((resolve) => setTimeout(resolve, 50));
  assert.match(harness.bodyText(), /复核事项 RC-BATCH-2/);
  assert.doesNotMatch(harness.bodyText(), /当前事项已处理/, '第一条的晚到成功态不得覆盖第二条');
  assert.match(harness.bodyText(), /标记已处理/, '第二条原处理表单必须保持可用');
});

test('reason_code 与 responsible_team 同时出现时组合过滤（工作台行上下文）', async () => {
  const requests: string[] = [];
  globalThis.fetch = reviewsFetch(requests);

  await harness.mount(['/workbench/reviews?status=OPEN&reason_code=SKU_MAPPING_REQUIRED&responsible_team=CUSTOMER_OPS']);

  await harness.waitFor(() => assert.ok(requests.some((r) =>
    r.includes('reason_code=SKU_MAPPING_REQUIRED') && r.includes('responsible_team=CUSTOMER_OPS'))));
  assert.match(harness.bodyText(), /客户运营/, '团队筛选控件显示 URL 中的 CUSTOMER_OPS');
});

test('无筛选参数时队列请求保持原状，不写多余参数（#95 兼容）', async () => {
  const requests: string[] = [];
  globalThis.fetch = reviewsFetch(requests);

  await harness.mount(['/workbench/reviews?import_batch=7']);

  await harness.waitFor(() => assert.ok(
    requests.includes('GET /api/v1/review-cases?page=0&size=20&status=OPEN&import_batch_id=7'),
    '不带 reason/team 时请求与 #95 完全一致',
  ));
});

test('非法 status 值回退到默认 OPEN，不把坏链接当筛选', async () => {
  const requests: string[] = [];
  globalThis.fetch = reviewsFetch(requests);

  await harness.mount(['/workbench/reviews?status=BOGUS']);

  await harness.waitFor(() => assert.ok(
    requests.includes('GET /api/v1/review-cases?page=0&size=20&status=OPEN'),
    '非法 status 必须回退 OPEN',
  ));
});

test('旧 view=alerts 分享链接重定向到 /workbench/alerts，不静默落在复核队列', async () => {
  const requests: string[] = [];
  globalThis.fetch = reviewsFetch(requests);

  await harness.mount(['/workbench/reviews?view=alerts']);

  await harness.waitFor(() => assert.equal(harness.location(), '/workbench/alerts'));
  await harness.waitFor(() => assert.match(harness.bodyText(), /ALERT-Q-9/));
  assert.match(harness.bodyText(), /运营提醒/);
  assert.ok(requests.includes('GET /api/v1/operational-alerts?page=0&size=20&status=OPEN'),
    '重定向后必须实际拉取运营提醒列表');
});

test('旧 view=alerts 链接保留批次/状态等其他参数，只剥离 view', async () => {
  const requests: string[] = [];
  globalThis.fetch = reviewsFetch(requests);

  await harness.mount(['/workbench/reviews?view=alerts&import_batch=7&status=OPEN']);

  await harness.waitFor(() => assert.equal(
    harness.location(),
    '/workbench/alerts?import_batch=7&status=OPEN',
    '除 view 外其余参数必须原样保留',
  ));
  await harness.waitFor(() => assert.ok(
    requests.includes('GET /api/v1/operational-alerts?page=0&size=20&status=OPEN'),
    '保留的 status 参数必须实际影响提醒列表请求',
  ));
});

test('旧 view=alerts&status=RESOLVED 链接把状态语义交给提醒页（只落在提醒队列，不落复核队列）', async () => {
  const requests: string[] = [];
  globalThis.fetch = reviewsFetch(requests);

  await harness.mount(['/workbench/reviews?view=alerts&status=RESOLVED']);

  await harness.waitFor(() => assert.equal(harness.location(), '/workbench/alerts?status=RESOLVED'));
  await harness.waitFor(() => assert.ok(
    requests.includes('GET /api/v1/operational-alerts?page=0&size=20&status=RESOLVED'),
    '提醒页必须按保留的 status 拉取已恢复提醒列表',
  ));
  await harness.waitFor(() => assert.match(harness.bodyText(), /当前没有运营提醒/));
  assert.equal(requests.filter((r) => r.startsWith('GET /api/v1/review-cases')).length, 0,
    '重定向后绝不请求复核队列');
});

test('view=reviews 显式旧参数仍落在复核队列页（不重写 URL）', async () => {
  const requests: string[] = [];
  globalThis.fetch = reviewsFetch(requests);

  await harness.mount(['/workbench/reviews?view=reviews&status=OPEN&reason_code=SKU_MAPPING_REQUIRED']);

  await harness.waitFor(() => assert.match(harness.bodyText(), /RC-FIXTURE-1/));
  assert.equal(
    harness.location(),
    '/workbench/reviews?view=reviews&status=OPEN&reason_code=SKU_MAPPING_REQUIRED',
  );
});

test('复核页提供「运营提醒」上下文链接，点击进入提醒路由', async () => {
  const requests: string[] = [];
  globalThis.fetch = reviewsFetch(requests);

  await harness.mount(['/workbench/reviews']);

  await harness.waitFor(() => assert.match(harness.bodyText(), /阻断复核/));
  const alertsLink = control('运营提醒');
  assert.ok(alertsLink.tagName === 'A' && (alertsLink.getAttribute('href') ?? '') === '/workbench/alerts',
    '上下文切换必须是直达新提醒路由的链接');
  await harness.dispatchEvent(alertsLink, new MouseEvent('click', { bubbles: true }));

  await harness.waitFor(() => assert.equal(harness.location(), '/workbench/alerts'));
  await harness.waitFor(() => assert.match(harness.bodyText(), /ALERT-Q-9/));
});

test('改变责任团队筛选后 URL 同步更新（可分享/刷新恢复）', async () => {
  const requests: string[] = [];
  globalThis.fetch = reviewsFetch(requests);

  await harness.mount(['/workbench/reviews']);

  await harness.waitFor(() => assert.match(harness.bodyText(), /责任团队/));
  const teamSelector = document.querySelector<HTMLElement>('#review-team-filter')?.closest('.ant-select-selector');
  assert.ok(teamSelector, '缺少责任团队筛选');
  await harness.dispatchEvent(teamSelector, new MouseEvent('mousedown', { bubbles: true }));
  await harness.waitFor(() => assert.ok(
    [...document.querySelectorAll<HTMLElement>('.ant-select-item-option')]
      .some((candidate) => candidate.textContent?.includes('订单运营')),
  ));
  const orderOps = [...document.querySelectorAll<HTMLElement>('.ant-select-item-option')]
    .find((candidate) => candidate.textContent?.includes('订单运营'));
  assert.ok(orderOps);
  await harness.dispatchEvent(orderOps, new MouseEvent('click', { bubbles: true }));

  await harness.waitFor(() => assert.match(harness.location(), /responsible_team=ORDER_OPS/));
  await harness.waitFor(() => assert.ok(requests.some((r) => r.includes('responsible_team=ORDER_OPS'))));
});

// ---------- Issue #106：岗位默认团队预筛（URL 优先、看全部、写回 URL） ----------

test('#106 有岗位且 URL 无团队参数时，默认按岗位团队预筛并写回 URL', async () => {
  window.localStorage.setItem('zimu.workbench-role', 'FULFILLMENT_OPS');
  const requests: string[] = [];
  globalThis.fetch = reviewsFetch(requests);

  await harness.mount(['/workbench/reviews?status=OPEN']);

  await harness.waitFor(() => assert.ok(
    requests.some((r) => r.includes('/api/v1/review-cases?') && r.includes('responsible_team=FULFILLMENT_OPS')),
    '默认预筛必须实际影响队列请求',
  ));
  assert.match(harness.location(), /responsible_team=FULFILLMENT_OPS/, '默认团队写进 URL（URL 唯一事实源）');
  assert.match(harness.bodyText(), /已按岗位预筛：履约运营/, '预筛提示必须可见');
  assert.match(harness.bodyText(), /看全部/, '看全部切换必须可见');
  window.localStorage.clear();
});

test('#106 URL 带 responsible_team 时以 URL 为准，忽略岗位默认值', async () => {
  window.localStorage.setItem('zimu.workbench-role', 'FULFILLMENT_OPS');
  const requests: string[] = [];
  globalThis.fetch = reviewsFetch(requests);

  await harness.mount(['/workbench/reviews?status=OPEN&responsible_team=CUSTOMER_OPS']);

  await harness.waitFor(() => assert.ok(
    requests.some((r) => r.includes('responsible_team=CUSTOMER_OPS')),
    '队列请求必须用 URL 中的团队',
  ));
  // 侧栏徽标（size=1）按岗位计数是它自己的契约；这里只断言队列请求不被岗位覆盖。
  assert.ok(
    !requests.some((r) => r.includes('responsible_team=FULFILLMENT_OPS') && !r.includes('size=1')),
    '岗位默认值不得覆盖分享链接（故事 27）',
  );
  assert.doesNotMatch(harness.bodyText(), /已按岗位预筛/, 'URL 显式筛选时不显示岗位预筛提示');
  window.localStorage.clear();
});

test('#106 看全部清除预筛且本次挂载内不回填', async () => {
  window.localStorage.setItem('zimu.workbench-role', 'FULFILLMENT_OPS');
  const requests: string[] = [];
  globalThis.fetch = reviewsFetch(requests);

  await harness.mount(['/workbench/reviews?status=OPEN']);
  await harness.waitFor(() => assert.match(harness.bodyText(), /看全部/));

  await harness.dispatchEvent(control('看全部'), new window.MouseEvent('click', { bubbles: true }));
  await harness.waitFor(() => assert.doesNotMatch(harness.location(), /responsible_team/));
  await harness.waitFor(() => assert.ok(
    requests.some((r) => r.endsWith('/api/v1/review-cases?page=0&size=20&status=OPEN')),
    '清除后队列请求不带团队参数',
  ));
  window.localStorage.clear();
});
