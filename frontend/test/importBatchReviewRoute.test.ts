import assert from 'node:assert/strict';
import dayjs from 'dayjs';
import { after, afterEach, before, test } from 'node:test';
import {
  apiErrorResponse,
  control,
  createRouteHarness,
  jsonResponse,
  page,
  type RouteHarness,
} from './routeHarness.ts';

let harness: RouteHarness;

before(async () => {
  harness = await createRouteHarness('http://localhost/fulfillment/sales-outbound');
});

afterEach(async () => {
  await harness.unmount();
});

after(async () => {
  await harness.close();
});

function sourceBatch(
  id: string,
  overrides: { confirmedAt?: string | null; needReview?: number; accepted?: number; rejected?: number } = {},
) {
  const accepted = overrides.accepted ?? 1;
  const needReview = overrides.needReview ?? 2;
  const rejected = overrides.rejected ?? 0;
  return {
    id,
    batch_no: `IMP-B${id}`,
    batch_type: 'SOURCE_ORDER',
    import_mode: 'NEW',
    revision_no: 1,
    source_channel: 'CAISHIXIAN',
    source_channel_display_name: '彩食鲜',
    template_family: 'CSX_ORDER',
    template_version: '1',
    template_fingerprint: 'fixture',
    original_file_name: 'orders.xlsx',
    content_sha256: 'c'.repeat(64),
    status: 'COMPLETED',
    confirmed_at: overrides.confirmedAt ?? null,
    confirmed_by: overrides.confirmedAt ? 'ops-reviewer' : null,
    settlement_missing: false,
    row_counts: { total: accepted + needReview + rejected, accepted, need_review: needReview, rejected },
    generated_fulfillment_export_ids: [],
    received_at: '2026-08-14T06:00:00Z',
  };
}

function intakeJob(status: 'RECEIVED' | 'SUCCEEDED' = 'RECEIVED') {
  return {
    id: '17',
    job_no: 'SOI-17',
    source_channel: 'CAISHIXIAN',
    import_mode: 'NEW',
    parent_import_batch_id: null,
    original_file_name: 'caishixian.xlsx',
    file_format: 'XLSX',
    content_sha256: 'a'.repeat(64),
    status,
    error_code: null,
    import_batch_id: status === 'SUCCEEDED' ? '7' : null,
    lock_version: status === 'SUCCEEDED' ? 1 : 0,
    created_at: '2026-08-27T00:00:00Z',
    updated_at: '2026-08-27T00:00:01Z',
  };
}

async function chooseSourceChannel(label = '彩食鲜') {
  const selector = [...document.querySelectorAll<HTMLElement>('.ant-select-selector')]
    .find((element) => element.querySelector('.ant-select-selection-placeholder')?.textContent === '选择来源渠道');
  assert.ok(selector, 'missing source channel selector');
  await harness.dispatchEvent(selector, new MouseEvent('mousedown', { bubbles: true }));
  await harness.waitFor(() => assert.ok(document.querySelector('.ant-select-item-option')));
  const option = [...document.querySelectorAll<HTMLElement>('.ant-select-item-option')]
    .find((element) => element.textContent?.includes(label));
  assert.ok(option, `missing source channel option ${label}`);
  await harness.dispatchEvent(option, new MouseEvent('click', { bubbles: true }));
}

function reviewRow(id: string, status: 'ACCEPTED' | 'NEED_REVIEW') {
  return {
    id,
    sheet_name: '待发货明细',
    sheet_index: 0,
    row_index: 2,
    raw_cells: { '商品编号': '2047705', '商品名称': '子牧牛腱子500g*2' },
    source_order_ref: `CSX-${id}`,
    status,
    error_code: null,
    error_detail: {},
    order_id: status === 'ACCEPTED' ? '101' : null,
    order_line_id: status === 'ACCEPTED' ? '201' : null,
  };
}

function reviewCase(id: string) {
  return {
    id,
    case_no: `RC-B7-${id}`,
    case_type: 'ORDER',
    responsible_team: 'SKU_OPS',
    reason_code: 'SKU_MAPPING_REQUIRED',
    status: 'OPEN',
    order_id: '101',
    order_line_id: '201',
    subject_type: 'ORDER_LINE',
    subject_id: '201',
    detail: {},
    suggestions: [],
    allowed_actions: ['RESOLVE_SKU'],
    version: 0,
    created_at: '2026-08-14T06:00:00Z',
  };
}

/** 无专用表单的事项：复核页抽屉走通用「标记已处理」，是闭环测试里最轻的解决路径。 */
function manualCase(id: string) {
  return {
    id,
    case_no: `RC-MANUAL-${id}`,
    case_type: 'ORDER',
    responsible_team: 'ORDER_OPS',
    reason_code: 'FULFILLMENT_EXCEPTION',
    status: 'OPEN',
    order_id: '101',
    order_line_id: '201',
    subject_type: 'ORDER_LINE',
    subject_id: '201',
    detail: {},
    suggestions: [],
    allowed_actions: ['RESOLVE_MANUALLY'],
    version: 0,
    created_at: '2026-08-14T06:00:00Z',
  };
}

/** 文件作业页 mock：批次 7 的详情、明细行、确认与页面基础数据。 */
function fileJobFetch(requests: string[], confirmedBatch?: ReturnType<typeof sourceBatch>) {
  return async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    requests.push(`${init?.method ?? 'GET'} ${url}`);
    if (url.startsWith('/api/v1/fulfillment-providers')) return jsonResponse([]);
    if (url.startsWith('/api/v1/fulfillment-exports')) {
      return jsonResponse({ items: [], page: 0, size: 10, total_elements: 0, total_pages: 0 });
    }
    if (url === '/api/v1/import-batches/7') return jsonResponse(confirmedBatch ?? sourceBatch('7'));
    if (url === '/api/v1/import-batches/7/rows?page=0&size=200&status=ACCEPTED') {
      return jsonResponse(page([reviewRow('71', 'ACCEPTED')]));
    }
    if (url === '/api/v1/import-batches/7/rows?page=0&size=200&status=NEED_REVIEW') {
      return jsonResponse(page([reviewRow('72', 'NEED_REVIEW'), reviewRow('73', 'NEED_REVIEW')]));
    }
    if (url === '/api/v1/import-batches/7/confirm' && init?.method === 'POST') {
      return jsonResponse(sourceBatch('7', { confirmedAt: '2026-08-14T07:00:00Z' }));
    }
    throw new Error(`unexpected request: ${url}`);
  };
}

test('file job page restores the batch from the URL and the review link carries the batch context', async () => {
  const requests: string[] = [];
  globalThis.fetch = fileJobFetch(requests);

  await harness.mount(['/fulfillment/sales-outbound?import_batch=7']);

  await harness.waitFor(() => assert.match(harness.bodyText(), /IMP-B7/));
  assert.match(harness.bodyText(), /确认明细/);
  assert.match(harness.bodyText(), /待复核 2 行/);
  assert.match(harness.location(), /import_batch=7/);
  assert.ok(requests.includes('GET /api/v1/import-batches/7'), 'batch must be restored from the URL');
  const reviewLink = control('前往人工复核');
  assert.match(reviewLink.getAttribute('href') ?? '', /\/workbench\/reviews\?import_batch=7/);
});

test('file job page keeps the batch id in the URL after upload and refresh rehydrates it', async () => {
  const requests: string[] = [];
  let jobReads = 0;
  globalThis.fetch = async (input, init) => {
    const url = String(input);
    requests.push(`${init?.method ?? 'GET'} ${url}`);
    if (url.startsWith('/api/v1/fulfillment-providers')) return jsonResponse([]);
    if (url.startsWith('/api/v1/fulfillment-exports')) {
      return jsonResponse({ items: [], page: 0, size: 10, total_elements: 0, total_pages: 0 });
    }
    if (url === '/api/v1/source-order-intake-jobs' && init?.method === 'POST') {
      return jsonResponse(intakeJob(), 202);
    }
    if (url === '/api/v1/source-order-intake-jobs/17') {
      jobReads += 1;
      if (jobReads === 1) throw new Error('临时网络故障');
      return jsonResponse(jobReads < 4 ? { ...intakeJob(), status: 'PROCESSING' } : intakeJob('SUCCEEDED'));
    }
    if (url === '/api/v1/import-batches/7') {
      return jsonResponse(sourceBatch('7'));
    }
    if (url === '/api/v1/import-batches/7/rows?page=0&size=200&status=ACCEPTED') {
      return jsonResponse(page([reviewRow('71', 'ACCEPTED')]));
    }
    if (url === '/api/v1/import-batches/7/rows?page=0&size=200&status=NEED_REVIEW') {
      return jsonResponse(page([reviewRow('72', 'NEED_REVIEW')]));
    }
    throw new Error(`unexpected request: ${url}`);
  };

  await harness.mount(['/fulfillment/sales-outbound']);
  // 全量套件 16 路并行时本文件常最后启动，vite SSR 冷启动可能挤占默认 3s——放宽到 10s（仅时限，断言不变）。
  await harness.waitFor(() => assert.match(harness.bodyText(), /来源订单导入/), 10_000);

  const fileInput = document.querySelector<HTMLInputElement>('input[type="file"][accept=".xlsx,.xls,.csv"]');
  assert.ok(fileInput, 'missing source import file input');
  const file = new File(['fixture'], 'caishixian.xlsx', {
    type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  });
  Object.defineProperty(fileInput, 'files', { configurable: true, value: [file] });
  await harness.dispatchEvent(fileInput, new Event('change', { bubbles: true }));
  await harness.waitFor(() => assert.match(harness.bodyText(), /caishixian\.xlsx/), 10_000);
  await chooseSourceChannel();
  await control('开始导入').click();

  await harness.waitFor(() => assert.match(harness.bodyText(), /附件任务 SOI-17 · 已接收/));
  assert.match(harness.location(), /intake_job=17/, '处理中的任务标识必须可刷新恢复');
  assert.doesNotMatch(harness.bodyText(), /· RECEIVED/);
  await harness.waitFor(() => assert.match(harness.location(), /import_batch=7/), 10_000);
  assert.match(harness.bodyText(), /IMP-B7/);
  assert.equal(jobReads, 4, '短暂请求失败后仍必须持续轮询到终态');
});

test('duplicate upload that already succeeded restores its existing batch immediately', async () => {
  const requests: string[] = [];
  let batchReads = 0;
  globalThis.fetch = async (input, init) => {
    const url = String(input);
    requests.push(`${init?.method ?? 'GET'} ${url}`);
    if (url.startsWith('/api/v1/fulfillment-providers')) return jsonResponse([]);
    if (url.startsWith('/api/v1/fulfillment-exports')) {
      return jsonResponse({ items: [], page: 0, size: 10, total_elements: 0, total_pages: 0 });
    }
    if (url === '/api/v1/source-order-intake-jobs' && init?.method === 'POST') {
      return jsonResponse(intakeJob('SUCCEEDED'), 202);
    }
    if (url === '/api/v1/import-batches/7') {
      batchReads += 1;
      if (batchReads === 1) throw new Error('临时网络故障');
      return jsonResponse(sourceBatch('7'));
    }
    if (url === '/api/v1/import-batches/7/rows?page=0&size=200&status=ACCEPTED') {
      return jsonResponse(page([reviewRow('71', 'ACCEPTED')]));
    }
    if (url === '/api/v1/import-batches/7/rows?page=0&size=200&status=NEED_REVIEW') {
      return jsonResponse(page([reviewRow('72', 'NEED_REVIEW')]));
    }
    throw new Error(`unexpected request: ${url}`);
  };

  await harness.mount(['/fulfillment/sales-outbound']);
  await harness.waitFor(() => assert.match(harness.bodyText(), /来源订单导入/), 10_000);
  const fileInput = document.querySelector<HTMLInputElement>('input[type="file"][accept=".xlsx,.xls,.csv"]');
  assert.ok(fileInput, 'missing source import file input');
  Object.defineProperty(fileInput, 'files', {
    configurable: true,
    value: [new File(['fixture'], 'caishixian.xlsx', {
      type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    })],
  });
  await harness.dispatchEvent(fileInput, new Event('change', { bubbles: true }));
  await chooseSourceChannel();
  await control('开始导入').click();

  await harness.waitFor(() => assert.match(harness.location(), /import_batch=7/), 10_000);
  assert.match(harness.bodyText(), /IMP-B7/);
  assert.equal(
    requests.filter((request) => request === 'GET /api/v1/source-order-intake-jobs/17').length,
    0,
    '已成功的幂等任务不应重新轮询',
  );
  assert.equal(batchReads, 2, '已完成批次的短暂读取失败必须自动恢复');
});

test('refreshing a succeeded intake job URL restores the completed batch', async () => {
  const requests: string[] = [];
  globalThis.fetch = async (input, init) => {
    const url = String(input);
    requests.push(`${init?.method ?? 'GET'} ${url}`);
    if (url.startsWith('/api/v1/fulfillment-providers')) return jsonResponse([]);
    if (url.startsWith('/api/v1/fulfillment-exports')) {
      return jsonResponse({ items: [], page: 0, size: 10, total_elements: 0, total_pages: 0 });
    }
    if (url === '/api/v1/source-order-intake-jobs/17') return jsonResponse(intakeJob('SUCCEEDED'));
    if (url === '/api/v1/import-batches/7') return jsonResponse(sourceBatch('7'));
    if (url === '/api/v1/import-batches/7/rows?page=0&size=200&status=ACCEPTED') {
      return jsonResponse(page([reviewRow('71', 'ACCEPTED')]));
    }
    if (url === '/api/v1/import-batches/7/rows?page=0&size=200&status=NEED_REVIEW') {
      return jsonResponse(page([reviewRow('72', 'NEED_REVIEW')]));
    }
    throw new Error(`unexpected request: ${url}`);
  };

  await harness.mount(['/fulfillment/sales-outbound?intake_job=17']);
  await harness.waitFor(() => assert.match(harness.location(), /import_batch=7/), 10_000);
  assert.match(harness.bodyText(), /IMP-B7/);
  assert.ok(requests.includes('GET /api/v1/source-order-intake-jobs/17'));
});

test('a missing intake job stops automatic retries', async () => {
  let jobReads = 0;
  globalThis.fetch = async (input) => {
    const url = String(input);
    if (url.startsWith('/api/v1/fulfillment-providers')) return jsonResponse([]);
    if (url.startsWith('/api/v1/fulfillment-exports')) {
      return jsonResponse({ items: [], page: 0, size: 10, total_elements: 0, total_pages: 0 });
    }
    if (url === '/api/v1/source-order-intake-jobs/404') {
      jobReads += 1;
      return jsonResponse({ business_code: 'SOURCE_ORDER_INTAKE_JOB_NOT_FOUND', message: '不存在' }, 404);
    }
    throw new Error(`unexpected request: ${url}`);
  };

  await harness.mount(['/fulfillment/sales-outbound?intake_job=404']);
  await harness.waitFor(() => assert.match(harness.bodyText(), /未找到所需数据/), 10_000);
  await new Promise((resolve) => setTimeout(resolve, 2200));
  assert.equal(jobReads, 1, '确定性 404 不应继续轮询');
});

test('a malformed intake job link fails closed with a visible error', async () => {
  const requests: string[] = [];
  globalThis.fetch = async (input) => {
    const url = String(input);
    requests.push(url);
    if (url.startsWith('/api/v1/fulfillment-providers')) return jsonResponse([]);
    if (url.startsWith('/api/v1/fulfillment-exports')) {
      return jsonResponse({ items: [], page: 0, size: 10, total_elements: 0, total_pages: 0 });
    }
    throw new Error(`unexpected request: ${url}`);
  };

  await harness.mount(['/fulfillment/sales-outbound?intake_job=abc']);
  await harness.waitFor(() => assert.match(harness.bodyText(), /不是有效的批次编号.*附件任务/), 10_000);
  assert.equal(requests.some((url) => url.includes('/source-order-intake-jobs/')), false);
});

test('review page lands on the batch-filtered queue with batch context and an explicit way back', async () => {
  const requests: string[] = [];
  globalThis.fetch = async (input, init) => {
    const url = String(input);
    requests.push(`${init?.method ?? 'GET'} ${url}`);
    if (url === '/api/v1/review-cases?page=0&size=20&status=OPEN&import_batch_id=7') {
      return jsonResponse(page([reviewCase('1'), reviewCase('2')]));
    }
    if (url === '/api/v1/operational-alerts?page=0&size=20&status=OPEN') return jsonResponse(page([]));
    if (url === '/api/v1/import-batches/7') return jsonResponse(sourceBatch('7'));
    throw new Error(`unexpected request: ${url}`);
  };

  await harness.mount(['/workbench/reviews?import_batch=7']);

  await harness.waitFor(() => assert.match(harness.bodyText(), /RC-B7-1/));
  assert.match(harness.bodyText(), /RC-B7-2/);
  assert.match(harness.bodyText(), /IMP-B7/);
  assert.match(harness.bodyText(), /待复核 2 行/);
  assert.ok(requests.includes('GET /api/v1/review-cases?page=0&size=20&status=OPEN&import_batch_id=7'),
    'queue must be filtered by import_batch_id');
  const back = control('返回该批次');
  assert.match(back.getAttribute('href') ?? '', /\/fulfillment\/sales-outbound\?import_batch=7/);
});

test('review page shows a readable confirmed state instead of a blank queue', async () => {
  globalThis.fetch = async (input, init) => {
    const url = String(input);
    if (url === '/api/v1/review-cases?page=0&size=20&status=OPEN&import_batch_id=7') {
      return jsonResponse(page([]));
    }
    if (url === '/api/v1/operational-alerts?page=0&size=20&status=OPEN') return jsonResponse(page([]));
    if (url === '/api/v1/import-batches/7') {
      return jsonResponse(sourceBatch('7', { confirmedAt: '2026-08-14T07:00:00Z' }));
    }
    throw new Error(`unexpected request: ${url}`);
  };

  await harness.mount(['/workbench/reviews?import_batch=7']);

  await harness.waitFor(() => assert.match(harness.bodyText(), /本批次已确认/));
  assert.match(harness.bodyText(), /无需继续处理/);
  assert.match(harness.bodyText(), new RegExp(dayjs('2026-08-14T07:00:00Z').format('YYYY-MM-DD HH:mm')));
  assert.match(control('返回该批次').getAttribute('href') ?? '', /\/fulfillment\/sales-outbound\?import_batch=7/);
});

test('review page shows a readable missing-batch state instead of a blank page', async () => {
  globalThis.fetch = async (input, init) => {
    const url = String(input);
    if (url === '/api/v1/review-cases?page=0&size=20&status=OPEN&import_batch_id=7') {
      return jsonResponse(page([]));
    }
    if (url === '/api/v1/operational-alerts?page=0&size=20&status=OPEN') return jsonResponse(page([]));
    if (url === '/api/v1/import-batches/7') {
      return apiErrorResponse(404, 'NOT_FOUND', '导入批次不存在: 7');
    }
    throw new Error(`unexpected request: ${url}`);
  };

  await harness.mount(['/workbench/reviews?import_batch=7']);

  await harness.waitFor(() => assert.match(harness.bodyText(), /导入批次不存在/));
  assert.match(harness.bodyText(), /不存在或已被清理/);
  assert.ok(control('返回文件作业'));
});

test('review page fails closed on a malformed batch id and never degrades to the global queue', async () => {
  const queueRequests: string[] = [];
  globalThis.fetch = async (input, init) => {
    const url = String(input);
    if (url.includes('/api/v1/review-cases')) {
      queueRequests.push(url);
      throw new Error('queue must not be fetched for a malformed batch id');
    }
    throw new Error(`unexpected request: ${url}`);
  };

  await harness.mount(['/workbench/reviews?import_batch=abc']);

  await harness.waitFor(() => assert.match(harness.bodyText(), /导入批次标识无效/));
  assert.match(harness.bodyText(), /abc/);
  assert.equal(queueRequests.length, 0, 'malformed batch id must not trigger any queue request');
  assert.ok(control('返回文件作业'));
});

test('operator completes upload → review → back → confirm without touching the left menu', async () => {
  const requests: string[] = [];
  let reviewed = false;
  globalThis.fetch = async (input, init) => {
    const url = String(input);
    const method = init?.method ?? 'GET';
    requests.push(`${method} ${url}`);
    if (url.startsWith('/api/v1/fulfillment-providers')) return jsonResponse([]);
    if (url.startsWith('/api/v1/fulfillment-exports')) {
      return jsonResponse({ items: [], page: 0, size: 10, total_elements: 0, total_pages: 0 });
    }
    if (url === '/api/v1/source-order-intake-jobs' && method === 'POST') {
      return jsonResponse(intakeJob(), 202);
    }
    if (url === '/api/v1/source-order-intake-jobs/17') {
      return jsonResponse(intakeJob('SUCCEEDED'));
    }
    if (url === '/api/v1/import-batches/7') {
      return jsonResponse(reviewed ? sourceBatch('7', { needReview: 0 }) : sourceBatch('7'));
    }
    if (url === '/api/v1/import-batches/7/rows?page=0&size=200&status=ACCEPTED') {
      return jsonResponse(page([reviewRow('71', 'ACCEPTED')]));
    }
    if (url === '/api/v1/import-batches/7/rows?page=0&size=200&status=NEED_REVIEW') {
      return jsonResponse(reviewed ? page([]) : page([reviewRow('72', 'NEED_REVIEW'), reviewRow('73', 'NEED_REVIEW')]));
    }
    if (url === '/api/v1/review-cases?page=0&size=20&status=OPEN&import_batch_id=7') {
      return jsonResponse(reviewed ? page([]) : page([manualCase('1')]));
    }
    if (url === '/api/v1/operational-alerts?page=0&size=20&status=OPEN') return jsonResponse(page([]));
    if (url === '/api/v1/review-cases/1/resolve' && method === 'POST') {
      reviewed = true;
      return jsonResponse({ ...manualCase('1'), status: 'RESOLVED', resolved_by: 'ops-reviewer' });
    }
    if (url === '/api/v1/import-batches/7/confirm' && method === 'POST') {
      return jsonResponse(sourceBatch('7', { confirmedAt: '2026-08-14T07:00:00Z' }));
    }
    throw new Error(`unexpected request: ${url}`);
  };

  // 上传：选择文件并开始导入
  await harness.mount(['/fulfillment/sales-outbound']);
  await harness.waitFor(() => assert.match(harness.bodyText(), /来源订单导入/));
  const fileInput = document.querySelector<HTMLInputElement>('input[type="file"][accept=".xlsx,.xls,.csv"]');
  assert.ok(fileInput, 'missing source import file input');
  const file = new File(['fixture'], 'caishixian.xlsx', {
    type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  });
  Object.defineProperty(fileInput, 'files', { configurable: true, value: [file] });
  await harness.dispatchEvent(fileInput, new Event('change', { bubbles: true }));
  await harness.waitFor(() => assert.match(harness.bodyText(), /caishixian\.xlsx/));
  await chooseSourceChannel();
  await control('开始导入').click();

  await harness.waitFor(() => assert.match(harness.location(), /import_batch=7/));
  await harness.waitFor(() => assert.match(harness.bodyText(), /IMP-B7/));
  assert.match(harness.bodyText(), /待复核 2 行/);

  // 复核：带批次上下文跳转并解决待复核项
  await control('前往人工复核').click();
  await harness.waitFor(() => assert.equal(harness.location(), '/workbench/reviews?import_batch=7'));
  await harness.waitFor(() => assert.match(harness.bodyText(), /正在复核导入批次 IMP-B7/));
  await harness.waitFor(() => assert.match(harness.bodyText(), /RC-MANUAL-1/));
  await control('查看处理').click();
  await harness.waitFor(() => assert.match(harness.bodyText(), /标记已处理/));
  await control('标记已处理').click();
  await harness.waitFor(() => assert.match(harness.bodyText(), /复核事项已标记已解决并记录审计/));
  await harness.waitFor(() => assert.match(harness.bodyText(), /当前没有复核事项/));

  // 返回该批次：无需左侧菜单，批次状态已在 URL 中恢复
  await harness.dispatchEvent(
    control('返回该批次'),
    new MouseEvent('click', { bubbles: true, cancelable: true, button: 0 }),
  );
  await harness.waitFor(() => assert.equal(harness.location(), '/fulfillment/sales-outbound?import_batch=7'));
  await harness.waitFor(() => assert.match(harness.bodyText(), /确认本批次（已接收 1 行）/));

  // 确认本批次
  await control('确认本批次（已接收 1 行）').click();
  await harness.waitFor(() =>
    assert.match(harness.bodyText(), /确认后已接收的 1 行将写入系统订单并生成履约文件/));
  const popconfirmOk = [...document.querySelectorAll<HTMLElement>('.ant-popconfirm-buttons button')]
    .find((candidate) => candidate.textContent?.includes('确认本批次'));
  assert.ok(popconfirmOk, 'missing popconfirm ok button');
  await harness.dispatchEvent(popconfirmOk, new MouseEvent('click', { bubbles: true }));

  await harness.waitFor(() => assert.match(harness.bodyText(), /批次已确认，生成履约文件/));
  assert.ok(requests.includes('POST /api/v1/import-batches/7/confirm'));
  assert.match(harness.location(), /import_batch=7/, 'batch id must stay in the URL after confirmation');
});
