import assert from 'node:assert/strict';
import { after, afterEach, before, test } from 'node:test';
import {
  createRouteHarness,
  isShellBaselineRequest,
  jsonResponse,
  shellBaselineResponse,
  withShellRequests,
  type RouteHarness,
} from './routeHarness.ts';

/**
 * MCP 开放面只读核对视图（票 05）的路由级行为。
 *
 * 验的是管理员真正要的四件事：按模块分组看见**已注册**的工具名与用途摘要、
 * 「已知但未开放」与「已开放」分得清楚、什么都没开放时如实呈现空态而不是报错、
 * 以及这页确实改不了开放面（纯只读）。
 *
 * 菜单可见性与板块归属的门禁在 businessObjectNavigation.test.ts（导航树是那边的事）。
 */

let harness: RouteHarness;

before(async () => {
  harness = await createRouteHarness('http://localhost/system/mcp-exposure');
});

afterEach(async () => {
  await harness.unmount();
});

after(async () => {
  await harness.close();
});

const productionLikeExposure = {
  open_modules: [
    {
      module: 'masterdata',
      tools: [
        { name: 'search_provider_skus', description: '按关键词检索履约方 SKU', read_only: true },
        { name: 'list_products', description: '分页读取商品主数据', read_only: true },
      ],
    },
    {
      module: 'inventory',
      tools: [{ name: 'list_inventory', description: '查询库存总览', read_only: true }],
    },
  ],
  unopened_modules: ['messages', 'followup', 'write'],
};

function exposureFetch(payload: unknown, requests: string[] = []) {
  return async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    requests.push(`${init?.method ?? 'GET'} ${url}`);
    if (isShellBaselineRequest(url)) return shellBaselineResponse();
    if (url === '/api/v1/mcp-exposure') return jsonResponse(payload);
    throw new Error(`unexpected request: ${url}`);
  };
}

test('MCP 开放面按模块分组列出已注册工具的名称与用途摘要', async () => {
  const requests: string[] = [];
  globalThis.fetch = exposureFetch(productionLikeExposure, requests);

  await harness.mount(['/system/mcp-exposure']);

  await harness.waitFor(() => assert.match(harness.bodyText(), /search_provider_skus/));
  const text = harness.bodyText();
  assert.match(text, /MCP 开放面/);
  // 模块名 + 该模块下的工具名 + 工具自己声明的用途摘要，三者都要看得见
  assert.match(text, /masterdata/);
  assert.match(text, /inventory/);
  assert.match(text, /按关键词检索履约方 SKU/);
  assert.match(text, /分页读取商品主数据/);
  assert.match(text, /list_inventory/);
  assert.match(text, /查询库存总览/);
  assert.match(text, /已开放 2 个模块、共 3 个工具/);
  assert.match(text, /全部为只读工具/);
  // 只读视图只发一次 GET（外壳基线请求见 routeHarness 说明），没有任何写调用
  assert.deepEqual(requests, withShellRequests('/api/v1/mcp-exposure').map((url) => `GET ${url}`));
});

test('已开放与已知但未开放分成两类：未开放只给模块名，不列它的工具', async () => {
  globalThis.fetch = exposureFetch(productionLikeExposure);

  await harness.mount(['/system/mcp-exposure']);

  await harness.waitFor(() => assert.match(harness.bodyText(), /search_provider_skus/));
  const text = harness.bodyText();
  assert.match(text, /已开放模块/);
  assert.match(text, /已知但未开放的模块/);
  for (const module of productionLikeExposure.unopened_modules) {
    assert.ok(text.includes(module), `未开放模块 ${module} 必须如实列出`);
  }
  // 未开放模块的工具根本没进注册表，界面不得凭空给出它们的明细
  assert.doesNotMatch(text, /get_order_draft|submit_jd_outbound/);
});

test('写工具如实标注：开放面里有写工具时必须一眼看得见', async () => {
  globalThis.fetch = exposureFetch({
    open_modules: [
      {
        module: 'write',
        tools: [{ name: 'submit_jd_outbound', description: '提交京东出库单', read_only: false }],
      },
    ],
    unopened_modules: [],
  });

  await harness.mount(['/system/mcp-exposure']);

  await harness.waitFor(() => assert.match(harness.bodyText(), /submit_jd_outbound/));
  const text = harness.bodyText();
  assert.match(text, /其中 1 个是写工具/);
  assert.match(text, /全部已知模块都已开放/);
});

test('未开放任何模块时如实呈现空态，不报错', async () => {
  globalThis.fetch = exposureFetch({
    open_modules: [],
    unopened_modules: ['masterdata', 'inventory', 'orders-read', 'messages', 'followup', 'write'],
  });

  await harness.mount(['/system/mcp-exposure']);

  await harness.waitFor(() => assert.match(harness.bodyText(), /当前没有开放任何 MCP 模块/));
  const text = harness.bodyText();
  assert.doesNotMatch(text, /加载失败/);
  assert.match(text, /MCP_MODULES/, '空态要说清这是空值的既定语义，管理员才知道下一步做什么');
  // 空态下「已知但未开放」照常呈现——否则管理员看不到自己本可以开放哪些模块
  assert.match(text, /masterdata/);
  assert.match(text, /orders-read/);
});

test('纯只读：页面不提供任何改开放面的控件', async () => {
  globalThis.fetch = exposureFetch(productionLikeExposure);

  await harness.mount(['/system/mcp-exposure']);

  await harness.waitFor(() => assert.match(harness.bodyText(), /search_provider_skus/));
  // 只看页面正文（外壳的搜索框/岗位切换器不属于本页）
  const page = document.querySelector('.admin-page');
  assert.ok(page, '页面正文容器必须存在');
  const controls = [...page.querySelectorAll<HTMLElement>('button, input, textarea, select, [role="switch"]')];
  assert.deepEqual(
    controls.map((control) => control.textContent?.trim() || control.tagName),
    [],
    '开放面由部署期 MCP_MODULES 决定、启动期一次性生效，界面上不得出现任何可改它的控件',
  );
});

test('读取失败时给出可重试的错误态，恢复后照常呈现', async () => {
  let fail = true;
  globalThis.fetch = async (input: RequestInfo | URL) => {
    const url = String(input);
    if (isShellBaselineRequest(url)) return shellBaselineResponse();
    if (fail) {
      return jsonResponse({ business_code: 'INTERNAL_ERROR', message: 'boom', http_status: 500 }, 500);
    }
    return jsonResponse(productionLikeExposure);
  };

  await harness.mount(['/system/mcp-exposure']);

  await harness.waitFor(() => assert.match(harness.bodyText(), /MCP 开放面加载失败/));
  const retry = [...document.querySelectorAll<HTMLElement>('button')]
    .find((button) => button.textContent?.includes('重试'));
  assert.ok(retry, '错误态必须提供重试按钮');

  fail = false;
  await harness.dispatchEvent(retry, new MouseEvent('click', { bubbles: true }));
  await harness.waitFor(() => assert.match(harness.bodyText(), /search_provider_skus/));
});

test('Agent 列表承载发现路径：上下文入口 href 指向原路径且点击可达', async () => {
  globalThis.fetch = async (input: RequestInfo | URL) => {
    const url = String(input);
    if (isShellBaselineRequest(url)) return shellBaselineResponse();
    if (url === '/api/v1/agents') return jsonResponse({ items: [] });
    if (url === '/api/v1/mcp-exposure') return jsonResponse(productionLikeExposure);
    throw new Error(`unexpected request: ${url}`);
  };

  await harness.mount(['/agents']);
  await harness.waitFor(() => assert.match(harness.bodyText(), /Agent 列表/));

  const entry = [...document.querySelectorAll<HTMLAnchorElement>('a')]
    .find((link) => link.textContent?.includes('MCP 开放面'));
  assert.ok(entry, 'Agent 列表必须提供指向 MCP 开放面的上下文入口（菜单里已隐藏）');
  assert.equal(entry.getAttribute('href'), '/system/mcp-exposure', '上下文入口 href 必须指向原路径');

  await harness.dispatchEvent(entry, new MouseEvent('click', { bubbles: true }));
  await harness.waitFor(() => assert.match(harness.location(), /\/system\/mcp-exposure/));
  await harness.waitFor(() => assert.match(harness.bodyText(), /search_provider_skus/));
});
