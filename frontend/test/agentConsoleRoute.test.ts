import assert from 'node:assert/strict';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { after, afterEach, before, beforeEach, test } from 'node:test';
import { JSDOM } from 'jsdom';

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

// ---------- 路由与菜单装配（~ 后缀机制：分组节点与叶子共用 /agents） ----------

test('Agent 中心注册为顶级板块：菜单分组 + 可见叶子 + 隐藏下钻路由', async () => {
  const { appNavigation, navigationContext, navigationOpenKeys } = await import('../src/navigation.ts');
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
  // 分组节点与叶子共用 /agents：openKeys 用 ~ 后缀区分（既有机制，不另建）
  assert.deepEqual(navigationOpenKeys(appNavigation, '/agents/runs'), ['/agents~']);

  const routes = await vite.ssrLoadModule('/src/routes.tsx');
  const flattened = routes.flattenRoutes(routes.routeConfig).map((route: { path: string }) => route.path);
  for (const expected of ['/agents', '/agents/runs', '/agents/:slug', '/agents/runs/:runId', '/agents/:slug/evals']) {
    assert.ok(flattened.includes(expected), `路由 ${expected} 必须注册`);
  }
});

// ---------- P1：空态与错误态 ----------

test('P1 /agents：加载后空列表渲染统一空态，请求只发 GET /api/v1/agents', async () => {
  const requestedUrls: string[] = [];
  globalThis.fetch = (input) => {
    requestedUrls.push(String(input));
    return Promise.resolve(jsonResponse(agentsResponse([])));
  };
  await mountRoute(['/agents']);

  await waitFor(() => assert.match(bodyText(), /暂无 Agent/));
  assert.deepEqual(requestedUrls, ['/api/v1/agents']);
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

test('P1 /agents：state 三值直映为三种文案；新建 Agent 禁用并注明即将开放', async () => {
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
  assert.equal(createButton.hasAttribute('disabled'), true, 'P6 后置：新建按钮必须禁用，不跳 404');
  assert.match(bodyText(), /即将开放/);
});

// ---------- P3：LIVE 默认 / PREVIEW 显式切换与视觉隔离 ----------

test('P3 /agents/runs：默认不传 run_mode（只取 LIVE）；切 PREVIEW 后请求带 run_mode=PREVIEW 且有醒目标识', async () => {
  const requestedUrls: string[] = [];
  globalThis.fetch = (input) => {
    const url = String(input);
    requestedUrls.push(url);
    if (url.includes('run_mode=PREVIEW')) {
      return Promise.resolve(
        jsonResponse({
          items: [runListItem('run_22222222222222222222222222222222', { run_mode: 'PREVIEW', outcome: null, status: 'RUNNING' })],
          total: 1,
        }),
      );
    }
    return Promise.resolve(jsonResponse({ items: [runListItem('run_33333333333333333333333333333333')], total: 1 }));
  };
  await mountRoute(['/agents/runs']);

  // 默认 LIVE：请求不带 run_mode 参数
  await waitFor(() => assert.match(bodyText(), /run_33333333333333333333333333333333/));
  assert.ok(requestedUrls[0].startsWith('/api/v1/agent-runs'), `请求应命中 agent-runs：${requestedUrls[0]}`);
  assert.doesNotMatch(requestedUrls[0], /run_mode/, '默认请求不得携带 run_mode');

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
  assert.ok(requestedUrls.at(-1)?.includes('run_mode=PREVIEW'), `切换后请求必须带 run_mode=PREVIEW：${requestedUrls.at(-1)}`);
  assert.match(bodyText(), /正在查看 PREVIEW/);
  assert.match(bodyText(), /草稿试跑/);
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
