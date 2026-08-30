import assert from 'node:assert/strict';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { after, afterEach, before, beforeEach, test } from 'node:test';
import { JSDOM } from 'jsdom';
import {
  SHELL_BASELINE_REQUEST_URLS,
  isShellBaselineRequest,
  shellBaselineResponse,
  withShellRequests,
  withoutShellRequests,
} from './routeHarness.ts';

/**
 * Agent 中心路由与页面结构断言（真实路由 + 空态 + 错误态 + LIVE/PREVIEW 切换）。
 * 沿用既有 jsdom + Vite ssrLoadModule + MemoryRouter 挂载模式
 * （见 inventoryOverviewRoute.test.ts），不引入新的测试框架或渲染库。
 */

const frontendRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

let dom: JSDOM;
let vite: Awaited<ReturnType<typeof import('vite')['createServer']>>;
let App: typeof import('../src/App.tsx')['default'];
let MemoryRouter: typeof import('react-router-dom')['MemoryRouter'];
let createRoot: typeof import('react-dom/client')['createRoot'];
let createElement: typeof import('react')['createElement'];
let act: typeof import('react')['act'];
let simulate: typeof import('react-dom/test-utils')['Simulate'];
let mountedRoot: ReturnType<typeof import('react-dom/client')['createRoot']> | null = null;

function installDom() {
  dom = new JSDOM('<!doctype html><html><body><div id="root"></div></body></html>', {
    url: 'http://localhost/agents',
  });
  const browserGlobals = [
    'window', 'document', 'navigator', 'HTMLElement', 'HTMLBodyElement', 'HTMLInputElement', 'HTMLTextAreaElement',
    'SVGElement', 'Element', 'Document', 'Node', 'ShadowRoot', 'MutationObserver', 'Event',
    'MouseEvent', 'KeyboardEvent',
  ] as const;
  for (const key of browserGlobals) {
    Object.defineProperty(globalThis, key, {
      configurable: true,
      value: key === 'window' ? dom.window : dom.window[key],
    });
  }

  const nativeGetComputedStyle = dom.window.getComputedStyle.bind(dom.window);
  const safeGetComputedStyle = (element: Element) => nativeGetComputedStyle(element);
  Object.defineProperty(dom.window, 'getComputedStyle', { configurable: true, value: safeGetComputedStyle });
  Object.defineProperty(globalThis, 'getComputedStyle', { configurable: true, value: safeGetComputedStyle });

  class ResizeObserverStub {
    observe() {}
    unobserve() {}
    disconnect() {}
  }
  Object.defineProperty(globalThis, 'ResizeObserver', { configurable: true, value: ResizeObserverStub });
  Object.defineProperty(dom.window, 'ResizeObserver', { configurable: true, value: ResizeObserverStub });
  Object.defineProperty(dom.window, 'matchMedia', {
    configurable: true,
    value: () => ({
      matches: false,
      media: '',
      onchange: null,
      addListener() {},
      removeListener() {},
      addEventListener() {},
      removeEventListener() {},
      dispatchEvent() { return false; },
    }),
  });
  Object.defineProperty(globalThis, 'requestAnimationFrame', {
    configurable: true,
    value: (callback: FrameRequestCallback) => setTimeout(callback, 0),
  });
  Object.defineProperty(globalThis, 'cancelAnimationFrame', {
    configurable: true,
    value: (handle: number) => clearTimeout(handle),
  });
  Object.defineProperty(dom.window, 'scrollTo', { configurable: true, value() {} });
  Object.defineProperty(globalThis, 'IS_REACT_ACT_ENVIRONMENT', { configurable: true, value: true });
  Object.defineProperty(globalThis, 'MessageChannel', { configurable: true, value: undefined });
}

function bodyText(): string {
  return document.body.textContent?.replace(/\s+/g, ' ').trim() ?? '';
}

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  });
}

async function waitFor(assertion: () => void, timeoutMs = 2_000) {
  const deadline = Date.now() + timeoutMs;
  let lastError: unknown;
  while (Date.now() < deadline) {
    try {
      assertion();
      return;
    } catch (error) {
      lastError = error;
    }
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 5));
    });
  }
  throw lastError;
}

async function mountRoute(initialEntries: string[]) {
  const container = document.querySelector<HTMLDivElement>('#root');
  assert.ok(container, 'test root must exist');
  mountedRoot = createRoot(container);
  await act(async () => {
    mountedRoot?.render(createElement(
      MemoryRouter,
      {
        initialEntries,
        future: { v7_startTransition: true, v7_relativeSplatPath: true },
      },
      createElement(App),
    ));
  });
}

function agentListItem(slug: string, overrides: Record<string, unknown> = {}) {
  return {
    slug,
    name: slug,
    state: 'RUNNING',
    enabled: true,
    current_version: 3,
    draft_count: 0,
    seven_day_run_count: 0,
    seven_day_failure_count: 0,
    allow_write: false,
    model_ref: 'ref',
    prompt_version: 'pv3',
    tools: [],
    ...overrides,
  };
}

function agentsResponse(items: unknown[]) {
  return { items };
}

function runListItem(runId: string, overrides: Record<string, unknown> = {}) {
  return {
    run_id: runId,
    agent_slug: 'procurement-price',
    agent_version: '3',
    status: 'SUCCESS',
    outcome: 'SUCCESS',
    run_mode: 'LIVE',
    error_type: null,
    latency_ms: 1200,
    token_usage: { prompt_tokens: 10, completion_tokens: 20 },
    business_entity_type: null,
    business_entity_id: null,
    intent: '比价',
    model_metadata: { provider: 'none', model: 'none', prompt_version: 'none', visibility: 'NOT_CONFIGURED' },
    started_at: '2026-08-13T10:00:00+08:00',
    finished_at: '2026-08-13T10:00:02+08:00',
    ...overrides,
  };
}

function runDetail(overrides: Record<string, unknown> = {}) {
  return {
    run_id: 'run_11111111111111111111111111111111',
    thread_id: 'thread-1',
    agent_slug: 'procurement-price',
    agent_version: '3',
    status: 'SUCCESS',
    outcome: 'SUCCESS',
    run_mode: 'LIVE',
    error_type: null,
    latency_ms: 1200,
    token_usage: { prompt_tokens: 10, completion_tokens: 20 },
    business_entity_type: null,
    business_entity_id: null,
    intent: '比价',
    model_metadata: { provider: 'none', model: 'none', prompt_version: 'none', visibility: 'NOT_CONFIGURED' },
    input_digest: 'a3f1b2c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2',
    started_at: '2026-08-13T10:00:00+08:00',
    finished_at: '2026-08-13T10:00:02+08:00',
    tool_calls: [
      { sequence_no: 2, tool_name: 'query_price', args_summary: '{"sku":"A"}', result_summary: '{"price":10}', latency_ms: 500, status: 'SUCCESS' },
      { sequence_no: 1, tool_name: 'match_sku', args_summary: '{"text":"苹果"}', result_summary: '{"sku":"A"}', latency_ms: 300, status: 'SUCCESS' },
    ],
    eval_result: null,
    ...overrides,
  };
}

function tokenUsageSummary(overrides: Record<string, unknown> = {}) {
  return {
    group_by: 'AGENT',
    run_mode: 'LIVE',
    items: [],
    totals: {
      group_key: '',
      runs: 1,
      failed_runs: 0,
      runs_without_token_usage: 0,
      over_threshold_runs: 0,
      prompt_tokens: 1000,
      completion_tokens: 200,
      total_tokens: 1200,
      max_run_total_tokens: 1200,
      model_calls: 1,
      total_latency_ms: 1200,
      max_run_latency_ms: 1200,
      ...overrides,
    },
  };
}

before(async () => {
  installDom();
  ({ createRoot } = await import('react-dom/client'));
  ({ act, createElement } = await import('react'));
  ({ Simulate: simulate } = await import('react-dom/test-utils'));
  ({ MemoryRouter } = await import('react-router-dom'));

  const { createServer } = await import('vite');
  vite = await createServer({
    root: frontendRoot,
    server: { middlewareMode: true },
    appType: 'custom',
    logLevel: 'silent',
    optimizeDeps: { noDiscovery: true, include: [] },
  });
  App = (await vite.ssrLoadModule('/src/App.tsx')).default;
});

beforeEach(() => {
  document.body.innerHTML = '<div id="root"></div>';
});

afterEach(async () => {
  if (mountedRoot) {
    await act(async () => mountedRoot?.unmount());
    mountedRoot = null;
  }
});

after(async () => {
  await vite.close();
  dom.window.close();
});

// ---------- 路由与菜单装配（Issue #104：分组标题平铺导航，无折叠层级） ----------

test('Agent 中心注册为顶级板块：菜单分组 + 可见叶子 + 隐藏下钻路由', async () => {
  const { appNavigation, navigationContext } = await import('../src/navigation.ts');
  const group = appNavigation.find((node) => node.label === 'Agent 中心');
  assert.ok(group, '顶级分组「Agent 中心」必须存在');
  assert.equal(group.path, '/agents');

  assert.deepEqual(navigationContext('/agents', 'Agent 列表'), { section: 'Agent 中心', page: 'Agent 列表' });
  assert.deepEqual(navigationContext('/agents/runs', '运行记录'), { section: 'Agent 中心', page: '运行记录' });
  assert.deepEqual(navigationContext('/agents/runs/run_11111111111111111111111111111111', '运行详情'), {
    section: 'Agent 中心',
    page: '运行详情',
  });
  assert.deepEqual(navigationContext('/agents/some-slug', 'Agent 详情'), { section: 'Agent 中心', page: 'Agent 详情' });

  const routes = await vite.ssrLoadModule('/src/routes.tsx');
  const flattened = routes.flattenRoutes(routes.routeConfig).map((route: { path: string }) => route.path);
  for (const expected of ['/agents', '/agents/runs', '/agents/:slug', '/agents/runs/:runId', '/agents/:slug/evals']) {
    assert.ok(flattened.includes(expected), `路由 ${expected} 必须注册`);
  }
});

// ---------- P1：空态与错误态 ----------

test('P1 /agents：加载后空列表渲染统一空态，页面请求只发 GET /api/v1/agents', async () => {
  const requestedUrls: string[] = [];
  globalThis.fetch = (input) => {
    const url = String(input);
    requestedUrls.push(url);
    // 外壳基线请求不是本页发的，按生产的保守默认给空开放集（见 routeHarness 的说明）。
    if (isShellBaselineRequest(url)) return Promise.resolve(shellBaselineResponse());
    return Promise.resolve(jsonResponse(agentsResponse([])));
  };
  await mountRoute(['/agents']);

  await waitFor(() => assert.match(bodyText(), /暂无 Agent/));
  // 页面只取 Agent 列表；外壳另有一次基线请求——AppLayout 每次挂载都要读业务模块开放清单
  // 来过滤导航树（票 03），与停在哪一页无关，排在页面挂载期请求之后。
  assert.deepEqual(requestedUrls, withShellRequests('/api/v1/agents'));
});

test('P1 /agents：错误态渲染「Agent 列表加载失败」+ 重试可恢复', async () => {
  let fail = true;
  globalThis.fetch = () => {
    if (fail) {
      return Promise.resolve(jsonResponse({ business_code: 'INTERNAL_ERROR', message: 'boom', http_status: 500 }, 500));
    }
    return Promise.resolve(jsonResponse(agentsResponse([agentListItem('alpha'), agentListItem('beta')])));
  };
  await mountRoute(['/agents']);

  await waitFor(() => assert.match(bodyText(), /Agent 列表加载失败/));
  assert.match(bodyText(), /服务暂时不可用/);
  assert.doesNotMatch(bodyText(), /boom/);

  fail = false;
  const retry = [...document.querySelectorAll<HTMLElement>('button')].find((b) => b.textContent?.includes('重试'));
  assert.ok(retry, '错误态必须提供重试按钮');
  await act(async () => simulate.click(retry));
  await waitFor(() => assert.match(bodyText(), /alpha/));
});

// ---------- P1：state 三值渲染 + 新建按钮禁用 ----------

test('P1 /agents：state 三值直映为三种文案；新建 Agent 通往对话式创建页', async () => {
  globalThis.fetch = () =>
    Promise.resolve(
      jsonResponse(
        agentsResponse([
          agentListItem('running-agent'),
          agentListItem('disabled-agent', { state: 'DISABLED', enabled: false }),
          agentListItem('no-version-agent', { state: 'NO_ACTIVE_VERSION', current_version: null }),
        ]),
      ),
    );
  await mountRoute(['/agents']);

  await waitFor(() => assert.match(bodyText(), /running-agent/));
  assert.match(bodyText(), /运行中/);
  assert.match(bodyText(), /已停用/);
  assert.match(bodyText(), /无生效版本/);

  const createButton = [...document.querySelectorAll<HTMLElement>('button')].find((b) => b.textContent?.includes('新建 Agent'));
  assert.ok(createButton, '主操作「新建 Agent」必须存在');
  // P6（/agents/new 对话式创建 + meta-agent 后端）已落地：按钮必须可点并通往创建页
  assert.equal(createButton.hasAttribute('disabled'), false, '创建页已上线，按钮不得再禁用');
  assert.equal(
    createButton.closest('a')?.getAttribute('href'),
    '/agents/new',
    '新建按钮必须通往对话式创建页',
  );
});

// ---------- P3：LIVE 默认 / PREVIEW 显式切换与视觉隔离 ----------

test('P3 /agents/runs：默认不传 run_mode（只取 LIVE）；切 PREVIEW 后请求带 run_mode=PREVIEW 且有醒目标识', async () => {
  const requestedUrls: string[] = [];
  globalThis.fetch = (input) => {
    const url = String(input);
    requestedUrls.push(url);
    if (url.startsWith('/api/v1/agent-runs/token-usage')) {
      return Promise.resolve(jsonResponse(tokenUsageSummary({
        total_tokens: url.includes('run_mode=PREVIEW') ? 990 : 110,
      })));
    }
    if (url.includes('run_mode=PREVIEW')) {
      return Promise.resolve(
        jsonResponse({
          items: [runListItem('run_22222222222222222222222222222222', { run_mode: 'PREVIEW', outcome: null, status: 'RUNNING' })],
          total: 1,
        }),
      );
    }
    // 外壳基线请求不是本页发的，按生产的保守默认给空开放集（见 routeHarness 的说明）。
    if (isShellBaselineRequest(url)) return Promise.resolve(shellBaselineResponse());
    return Promise.resolve(jsonResponse({ items: [runListItem('run_33333333333333333333333333333333')], total: 1 }));
  };
  await mountRoute(['/agents/runs']);

  // 默认 LIVE：列表与汇总都不带 run_mode 参数
  await waitFor(() => assert.match(bodyText(), /run_33333333333333333333333333333333/));
  // 挂载期共 2 次页面请求（列表 + Token 汇总）外加外壳读业务模块开放清单那一次；
  // 「不传 run_mode」这条约束只针对页面自己发的请求，故下面按页面请求取值。
  await waitFor(() => assert.equal(requestedUrls.length, 2 + SHELL_BASELINE_REQUEST_URLS.length));
  const initialPageRequests = withoutShellRequests(requestedUrls);
  const initialList = initialPageRequests.find((url) => !url.includes('/token-usage'));
  const initialSummary = initialPageRequests.find((url) => url.includes('/token-usage'));
  assert.ok(initialList, '必须请求运行列表');
  assert.ok(initialSummary, '必须请求 Token 汇总');
  assert.doesNotMatch(initialList, /run_mode/, '默认列表请求不得携带 run_mode');
  assert.doesNotMatch(initialSummary, /run_mode/, '默认汇总请求不得携带 run_mode');

  // 切到 PREVIEW：请求带 run_mode=PREVIEW，页面出现醒目标识
  // （rc-segmented 的选项是包着 radio input 的 label，onChange 由 input 的 change 触发）
  const previewOption = [...document.querySelectorAll<HTMLElement>('.ant-segmented-item')].find((el) =>
    el.textContent?.includes('PREVIEW'),
  );
  assert.ok(previewOption, '必须提供 PREVIEW 切换入口');
  const previewRadio = previewOption.querySelector<HTMLInputElement>('input[type="radio"]');
  assert.ok(previewRadio, 'PREVIEW 选项必须包含 radio input');
  await act(async () => simulate.change(previewRadio, { target: { checked: true } }));
  await waitFor(() => assert.match(bodyText(), /run_22222222222222222222222222222222/));
  await waitFor(() => {
    const previewRequests = requestedUrls.filter((url) => url.includes('run_mode=PREVIEW'));
    assert.equal(previewRequests.length, 2);
    assert.ok(previewRequests.some((url) => url.includes('/token-usage')));
    assert.ok(previewRequests.some((url) => !url.includes('/token-usage')));
  });
  assert.match(bodyText(), /正在查看 PREVIEW/);
  assert.match(bodyText(), /草稿试跑/);
});

test('P3 /agents/runs：Token 列结构化，汇总与列表同源携带 outcome 和 business_entity_id', async () => {
  const requestedUrls: string[] = [];
  globalThis.fetch = (input) => {
    const url = String(input);
    requestedUrls.push(url);
    if (url.startsWith('/api/v1/agent-runs/token-usage')) {
      return Promise.resolve(jsonResponse(tokenUsageSummary({
        runs: 4,
        runs_without_token_usage: 2,
        prompt_tokens: 10_000,
        completion_tokens: 2_345,
        total_tokens: 12_345,
        model_calls: 7,
      })));
    }
    // 外壳基线请求不是本页发的，按生产的保守默认给空开放集（见 routeHarness 的说明）。
    if (isShellBaselineRequest(url)) return Promise.resolve(shellBaselineResponse());
    return Promise.resolve(jsonResponse({
      items: [
        runListItem('run_44444444444444444444444444444444', {
          outcome: 'FAILED',
          status: 'FAILED',
          token_usage: {
            model_calls: 2,
            total_tokens: 1966,
            prompt_tokens: 1634,
            completion_tokens: 332,
          },
        }),
        runListItem('run_55555555555555555555555555555555', {
          outcome: 'FAILED',
          status: 'FAILED',
          token_usage: { total_tokens: 99, raw_json_marker: 'SHOULD_NOT_RENDER' },
        }),
      ],
      total: 2,
    }));
  };

  await mountRoute(['/agents/runs?outcome=FAILED&business_entity_id=ORDER-42']);

  await waitFor(() => assert.match(bodyText(), /12,345/));
  assert.match(bodyText(), /1,966/);
  assert.match(bodyText(), /入 1,634 \/ 出 332/);
  assert.match(bodyText(), /2 次调用/);
  assert.match(bodyText(), /2 次运行未记录 token/);
  assert.doesNotMatch(bodyText(), /SHOULD_NOT_RENDER|raw_json_marker|model_calls/);

  const invalidRow = [...document.querySelectorAll('tbody tr')].find((row) =>
    row.textContent?.includes('run_55555555555555555555555555555555'),
  );
  assert.ok(invalidRow, '异常 token_usage 的运行行必须存在');
  assert.match(invalidRow.textContent ?? '', /—/);

  // 总数精确：页面 2 次（列表 + Token 汇总）+ 外壳基线 1 次，不多不少。
  await waitFor(() => assert.equal(requestedUrls.length, 2 + SHELL_BASELINE_REQUEST_URLS.length));
  // 「同源」这条约束只针对页面自己发的请求：外壳读的是业务模块开放清单（部署事实），
  // 本来就不带页面筛选，把它算进来会把断言变成一句错话。
  for (const url of withoutShellRequests(requestedUrls)) {
    assert.match(url, /outcome=FAILED/);
    assert.match(url, /business_entity_id=ORDER-42/);
  }
});

test('P3 /agents/runs：Token 汇总失败可重试，失败期间不伪装成 0', async () => {
  let summaryAttempts = 0;
  globalThis.fetch = (input) => {
    const url = String(input);
    if (url.startsWith('/api/v1/agent-runs/token-usage')) {
      summaryAttempts += 1;
      return Promise.resolve(
        summaryAttempts === 1
          ? jsonResponse({ business_code: 'INTERNAL_ERROR', message: 'boom', http_status: 500 }, 500)
          : jsonResponse(tokenUsageSummary({ total_tokens: 999 })),
      );
    }
    return Promise.resolve(jsonResponse({ items: [], total: 0 }));
  };

  await mountRoute(['/agents/runs']);

  await waitFor(() => assert.match(bodyText(), /Token 汇总加载失败/));
  const summary = document.querySelector<HTMLElement>('[aria-label="当前筛选 Token 汇总"]');
  assert.ok(summary, '必须有独立的 Token 汇总区域');
  assert.doesNotMatch(summary.textContent ?? '', /总 Token\s*0/);
  const retry = [...summary.querySelectorAll<HTMLElement>('button')].find((button) =>
    button.textContent?.includes('重试'),
  );
  assert.ok(retry, '汇总失败必须提供重试');
  await act(async () => simulate.click(retry));
  await waitFor(() => assert.match(summary.textContent ?? '', /999/));
});

test('P3 /agents/runs：Token 汇总加载中显示骨架，不沿用旧数值', async () => {
  let releaseSummary: ((response: Response) => void) | undefined;
  const pendingSummary = new Promise<Response>((resolve) => {
    releaseSummary = resolve;
  });
  globalThis.fetch = (input) => {
    const url = String(input);
    return url.startsWith('/api/v1/agent-runs/token-usage')
      ? pendingSummary
      : Promise.resolve(jsonResponse({ items: [], total: 0 }));
  };

  await mountRoute(['/agents/runs']);

  const summary = document.querySelector<HTMLElement>('[aria-label="当前筛选 Token 汇总"]');
  assert.ok(summary, '加载开始时汇总区域必须已经存在');
  assert.ok(summary.querySelector('.ant-skeleton'), '加载态必须显示骨架');
  assert.doesNotMatch(summary.textContent ?? '', /总 Token\s*0/);

  assert.ok(releaseSummary);
  await act(async () => releaseSummary?.(jsonResponse(tokenUsageSummary())));
  await waitFor(() => assert.match(summary.textContent ?? '', /1,200/));
});

test('P3 /agents/runs：筛选切换同步重置旧汇总，新请求完成前不显示上一口径', async () => {
  let releasePreviewSummary: ((response: Response) => void) | undefined;
  const pendingPreviewSummary = new Promise<Response>((resolve) => {
    releasePreviewSummary = resolve;
  });
  globalThis.fetch = (input) => {
    const url = String(input);
    if (url.startsWith('/api/v1/agent-runs/token-usage')) {
      return url.includes('run_mode=PREVIEW')
        ? pendingPreviewSummary
        : Promise.resolve(jsonResponse(tokenUsageSummary({ total_tokens: 1200 })));
    }
    return Promise.resolve(jsonResponse({ items: [], total: 0 }));
  };

  await mountRoute(['/agents/runs']);
  await waitFor(() => assert.match(bodyText(), /1,200/));
  const previousSummary = document.querySelector<HTMLElement>('[aria-label="当前筛选 Token 汇总"]');
  assert.ok(previousSummary);

  const previewOption = [...document.querySelectorAll<HTMLElement>('.ant-segmented-item')].find((element) =>
    element.textContent?.includes('PREVIEW'),
  );
  const previewRadio = previewOption?.querySelector<HTMLInputElement>('input[type="radio"]');
  assert.ok(previewRadio);
  await act(async () => simulate.change(previewRadio, { target: { checked: true } }));

  const nextSummary = document.querySelector<HTMLElement>('[aria-label="当前筛选 Token 汇总"]');
  assert.ok(nextSummary);
  assert.notEqual(nextSummary, previousSummary, '筛选口径变化必须重挂汇总区域，不能复用旧状态');
  assert.ok(nextSummary.querySelector('.ant-skeleton'));
  assert.doesNotMatch(nextSummary.textContent ?? '', /1,200/);

  assert.ok(releasePreviewSummary);
  await act(async () => releasePreviewSummary?.(jsonResponse(tokenUsageSummary({ total_tokens: 999 }))));
  await waitFor(() => assert.match(nextSummary.textContent ?? '', /999/));
});

test('P3 /agents/runs：分组达到端点上限时拒绝展示可能少算的 totals', async () => {
  globalThis.fetch = (input) => {
    const url = String(input);
    if (!url.startsWith('/api/v1/agent-runs/token-usage')) {
      return Promise.resolve(jsonResponse({ items: [], total: 0 }));
    }
    const summary = tokenUsageSummary({ total_tokens: 1200 });
    return Promise.resolve(jsonResponse({
      ...summary,
      items: Array.from({ length: 500 }, (_, index) => ({
        ...summary.totals,
        group_key: `agent-${index}`,
      })),
    }));
  };

  await mountRoute(['/agents/runs']);

  await waitFor(() => assert.match(bodyText(), /筛选范围过大，无法确认完整 Token 汇总/));
  const summary = document.querySelector<HTMLElement>('[aria-label="当前筛选 Token 汇总"]');
  assert.ok(summary);
  assert.equal(summary.getAttribute('role'), 'region');
  assert.doesNotMatch(summary.textContent ?? '', /总 Token\s*1,200/);
  assert.match(summary.textContent ?? '', /缩小 Agent 或时间范围/);
});

test('P3 /agents/runs：Token 汇总空态不显示 0', async () => {
  globalThis.fetch = (input) => {
    const url = String(input);
    return Promise.resolve(
      url.startsWith('/api/v1/agent-runs/token-usage')
        ? jsonResponse(tokenUsageSummary({
            runs: 0,
            prompt_tokens: 0,
            completion_tokens: 0,
            total_tokens: 0,
            model_calls: 0,
          }))
        : jsonResponse({ items: [], total: 0 }),
    );
  };

  await mountRoute(['/agents/runs']);

  await waitFor(() => assert.match(bodyText(), /当前筛选范围内暂无 Token 汇总/));
  const summary = document.querySelector<HTMLElement>('[aria-label="当前筛选 Token 汇总"]');
  assert.ok(summary);
  assert.doesNotMatch(summary.textContent ?? '', /总 Token\s*0/);
});

// ---------- P4：input_digest 说明文案 + 工具调用序列 + 错误态 ----------

test('P4 /agents/runs/:runId：input_digest 显式展示摘要与隐私说明，工具调用按序渲染', async () => {
  globalThis.fetch = (input) => {
    assert.match(String(input), /^\/api\/v1\/agent-runs\/run_11111111111111111111111111111111$/);
    return Promise.resolve(jsonResponse(runDetail()));
  };
  await mountRoute(['/agents/runs/run_11111111111111111111111111111111']);

  await waitFor(() => assert.match(bodyText(), /运行详情/));
  // 摘要本体 + 隐私说明文案（不得渲染成空白）
  assert.match(bodyText(), /a3f1b2c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2/);
  assert.match(bodyText(), /输入原文不留存，仅存摘要用于比对/);
  // 工具调用序列：乱序返回也按 sequence_no 升序渲染
  assert.match(bodyText(), /#1 match_sku/);
  assert.match(bodyText(), /#2 query_price/);
  assert.doesNotMatch(bodyText(), /该运行无工具调用/);
});

test('P4 /agents/runs/:runId：error_type 放最显眼处（失败 Alert）', async () => {
  globalThis.fetch = () =>
    Promise.resolve(
      jsonResponse(
        runDetail({
          status: 'FAILED',
          outcome: 'FAILED',
          error_type: 'MODEL_CALL_FAILED',
        }),
      ),
    );
  await mountRoute(['/agents/runs/run_11111111111111111111111111111111']);

  await waitFor(() => assert.match(bodyText(), /MODEL_CALL_FAILED/));
  assert.match(bodyText(), /失败原因/);
});

test('P4 /agents/runs/:runId：错误态渲染「运行详情加载失败」', async () => {
  globalThis.fetch = () =>
    Promise.resolve(jsonResponse({ business_code: 'NOT_FOUND', message: 'nope', http_status: 404 }, 404));
  await mountRoute(['/agents/runs/run_11111111111111111111111111111111']);

  await waitFor(() => assert.match(bodyText(), /运行详情加载失败/));
  assert.match(bodyText(), /未找到所需数据/);
});

// ---------- P5：评测用例按 INVARIANT / QUALITY 分组渲染 ----------

test('P5 /agents/:slug/evals：两组用例分表渲染，active 版本只读提示', async () => {
  const requestedUrls: string[] = [];
  globalThis.fetch = (input) => {
    const url = String(input);
    requestedUrls.push(url);
    if (url.endsWith('/versions')) {
      return Promise.resolve(
        jsonResponse([
          { version: 2, status: 'ACTIVE', activated_by: 'alice', activated_at: '2026-08-10T09:00:00+08:00' },
          { version: 1, status: 'RETIRED', activated_by: 'bob', activated_at: '2026-08-01T09:00:00+08:00' },
        ]),
      );
    }
    if (url.includes('/eval-cases')) {
      return Promise.resolve(
        jsonResponse([
          {
            id: 11,
            agent_slug: 'procurement-price',
            agent_version: 2,
            metric_kind: 'INVARIANT',
            input: { sku: 'A' },
            expected: { price: 10 },
            status: 'CONFIRMED',
            created_by: 'alice',
            confirmed_by: 'alice',
            confirmed_at: '2026-08-10T10:00:00+08:00',
          },
          {
            id: 12,
            agent_slug: 'procurement-price',
            agent_version: 2,
            metric_kind: 'QUALITY',
            input: { text: '最近价格' },
            expected: { answer: '包含价格' },
            status: 'PENDING',
            created_by: 'alice',
            confirmed_by: null,
            confirmed_at: null,
          },
        ]),
      );
    }
    return Promise.resolve(jsonResponse({}));
  };
  await mountRoute(['/agents/procurement-price/evals']);

  await waitFor(() => assert.match(bodyText(), /INVARIANT · 确定性门禁/));
  assert.match(bodyText(), /QUALITY · 质量评测/);
  assert.match(bodyText(), /#11/);
  assert.match(bodyText(), /#12/);
  assert.match(bodyText(), /已确认/);
  assert.match(bodyText(), /待确认/);
  assert.match(bodyText(), /active 版本用例冻结只读/);
  assert.match(bodyText(), /新增或修改用例需先创建草稿版本/);
  // 默认选 active 版本 v2 拉取用例
  assert.ok(requestedUrls.some((url) => url.includes('/versions/2/eval-cases')), '默认应请求 active 版本用例集');
});

// ---------- P2：详情页两个 tab + 最近运行摘要 ----------

test('P2 /agents/:slug：当前生效定义 + 守卫豁免空态文案 + 最近 5 次运行与查看全部', async () => {
  globalThis.fetch = (input) => {
    const url = String(input);
    if (url.endsWith('/versions')) {
      return Promise.resolve(jsonResponse([]));
    }
    if (url.includes('/agent-runs?')) {
      return Promise.resolve(
        jsonResponse({
          items: [runListItem('run_44444444444444444444444444444444')],
          total: 1,
        }),
      );
    }
    if (url.endsWith('/agents/procurement-price')) {
      return Promise.resolve(
        jsonResponse({
          slug: 'procurement-price',
          name: '采购比价',
          description: '比价助手',
          system_prompt: '你是比价助手',
          prompt_version: 'pv3',
          model_ref: 'ref-1',
          enabled: true,
          version: 3,
          status: 'ACTIVE',
          activated_by: 'alice',
          activated_at: '2026-08-10T09:00:00+08:00',
          allow_write: false,
          guard_exemptions: [],
          output_schema: { type: 'object' },
          input_format: 'STRUCTURED_JSON',
          tools: [{ name: 'query_price', read_only: true, registered: true }],
        }),
      );
    }
    return Promise.resolve(jsonResponse({}));
  };
  await mountRoute(['/agents/procurement-price']);

  await waitFor(() => assert.match(bodyText(), /采购比价/));
  assert.match(bodyText(), /默认守卫全部生效/);
  assert.match(bodyText(), /最近运行/);
  assert.match(bodyText(), /查看全部/);
  assert.match(bodyText(), /run_44444444444444444444444444444444/);

  // 切到版本链 tab：URL 变化，时间线渲染
  const versionsTab = [...document.querySelectorAll<HTMLElement>('.ant-tabs-tab')].find((el) =>
    el.textContent?.includes('版本链'),
  );
  assert.ok(versionsTab, '详情页必须有版本链 tab');
  await act(async () => simulate.click(versionsTab));
  await waitFor(() => assert.match(document.body.textContent ?? '', /版本状态机无回边/));
});
