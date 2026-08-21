import assert from 'node:assert/strict';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { after, afterEach, before, beforeEach, test } from 'node:test';
import { JSDOM } from 'jsdom';

const frontendRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

let dom: JSDOM;
let vite: Awaited<ReturnType<typeof import('vite')['createServer']>>;
let AdminApp: typeof import('../src/App.tsx')['default'];
let MemoryRouter: typeof import('react-router-dom')['MemoryRouter'];
let ConfigProvider: typeof import('antd')['ConfigProvider'];
let AntApp: typeof import('antd')['App'];
let createRoot: typeof import('react-dom/client')['createRoot'];
let createElement: typeof import('react')['createElement'];
let act: typeof import('react')['act'];
let mountedRoot: ReturnType<typeof import('react-dom/client')['createRoot']> | null = null;

function installDom() {
  dom = new JSDOM('<!doctype html><html><body><div id="root"></div></body></html>', {
    url: 'http://localhost/system/fulfillment-providers',
  });
  const browserGlobals = [
    'window',
    'document',
    'navigator',
    'HTMLElement',
    'HTMLInputElement',
    'HTMLTextAreaElement',
    'SVGElement',
    'Element',
    'Document',
    'Node',
    'ShadowRoot',
    'MutationObserver',
    'Event',
    'MouseEvent',
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
  Object.defineProperty(globalThis, 'IS_REACT_ACT_ENVIRONMENT', { configurable: true, value: true });
  Object.defineProperty(globalThis, 'MessageChannel', { configurable: true, value: undefined });
}

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  });
}

function bodyText(): string {
  return document.body.textContent?.replace(/\s+/g, ' ').trim() ?? '';
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

function findControl(text: string): HTMLElement {
  const control = [...document.querySelectorAll<HTMLElement>('button, a, .ant-switch, [role="switch"]')]
    .find((candidate) => candidate.textContent?.includes(text));
  assert.ok(control, `missing control ${text}`);
  return control;
}

function inputByPlaceholder(placeholder: string): HTMLInputElement {
  const input = [...document.querySelectorAll<HTMLInputElement>('.ant-modal input')]
    .find((candidate) => candidate.placeholder === placeholder);
  assert.ok(input, `missing input with placeholder ${placeholder}`);
  return input;
}

function providerRow(code: string): HTMLTableRowElement {
  const row = [...document.querySelectorAll<HTMLTableRowElement>('.ant-table-tbody .ant-table-row')]
    .find((candidate) => candidate.textContent?.includes(code));
  assert.ok(row, `missing provider row ${code}`);
  return row;
}

const jdConfig = (presentKeys: string[]): Record<string, { present: boolean; value?: unknown }> => {
  const config: Record<string, { present: boolean; value?: unknown }> = {};
  for (const key of ['sourceNo', 'warehouseNo', 'pin', 'erpShopNo', 'salesPlatformSource', 'ownerNo', 'shopNo', 'carrierNo', 'townRequired']) {
    if (presentKeys.includes(key)) {
      config[key] = key === 'pin' ? { present: true } : { present: true, value: `VALUE-${key}` };
    } else {
      config[key] = { present: false };
    }
  }
  return config;
};

before(async () => {
  installDom();
  ({ createRoot } = await import('react-dom/client'));
  ({ act, createElement } = await import('react'));
  ({ MemoryRouter } = await import('react-router-dom'));
  ({ App: AntApp, ConfigProvider } = await import('antd'));

  const { createServer } = await import('vite');
  vite = await createServer({
    root: frontendRoot,
    server: { middlewareMode: true },
    appType: 'custom',
    logLevel: 'silent',
    optimizeDeps: { noDiscovery: true, include: [] },
  });
  AdminApp = (await vite.ssrLoadModule('/src/App.tsx')).default;
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

test('JD provider shows missing identifier count and the edit form submits config through the public API', async () => {
  let configured = false;
  const patchBodies: { url: string; init: RequestInit }[] = [];
  globalThis.fetch = async (input, init) => {
    const url = String(input);
    if (url === '/api/v1/fulfillment-providers') {
      return jsonResponse([
        {
          id: '11',
          provider_code: 'JD',
          provider_name: '京东云仓',
          provider_type: 'JD_WAREHOUSE',
          tracking_sla_minutes: 60,
          active: true,
          version: 2,
          wecom_group_chat_id: null,
          jd_config: configured
            ? jdConfig(['sourceNo', 'warehouseNo', 'pin', 'erpShopNo', 'salesPlatformSource', 'ownerNo', 'shopNo', 'carrierNo', 'townRequired'])
            : jdConfig(['sourceNo', 'warehouseNo', 'pin']),
        },
        {
          id: '12',
          provider_code: 'TP',
          provider_name: '第三方履约',
          provider_type: 'THIRD_PARTY',
          tracking_sla_minutes: 120,
          active: false,
          version: 1,
          wecom_group_chat_id: null,
          jd_config: {},
        },
      ]);
    }
    if (url === '/api/v1/fulfillment-providers/11') {
      configured = true;
      patchBodies.push({ url, init });
      return jsonResponse({
        id: '11',
        provider_code: 'JD',
        provider_name: '京东云仓',
        provider_type: 'JD_WAREHOUSE',
        tracking_sla_minutes: 60,
        active: true,
        version: 3,
        wecom_group_chat_id: null,
        jd_config: jdConfig(['sourceNo', 'warehouseNo', 'pin', 'erpShopNo', 'salesPlatformSource', 'ownerNo', 'shopNo', 'carrierNo', 'townRequired']),
      });
    }
    return jsonResponse({ message: `unexpected request ${url}` }, 500);
  };

  const container = document.querySelector<HTMLDivElement>('#root');
  assert.ok(container);
  mountedRoot = createRoot(container);
  await act(async () => {
    mountedRoot?.render(createElement(
      ConfigProvider,
      null,
      createElement(
        AntApp,
        null,
        createElement(
          MemoryRouter,
          {
            initialEntries: ['/system/fulfillment-providers'],
            future: { v7_startTransition: true, v7_relativeSplatPath: true },
          },
          createElement(AdminApp),
        ),
      ),
    ));
  });

  await waitFor(() => assert.match(bodyText(), /京东云仓/));
  const jdRow = providerRow('JD');
  assert.match(jdRow.textContent ?? '', /缺 6 项/);
  const tpRow = providerRow('TP');
  assert.match(tpRow.textContent ?? '', /—/);

  // 打开编辑弹窗：字符串标识回填、pin 只显示存在性
  await act(async () => findControl('编辑').click());
  await waitFor(() => assert.match(bodyText(), /编辑履约方 JD/));
  assert.match(bodyText(), /来源编码 sourceNo/);
  assert.match(bodyText(), /乡镇必填 townRequired/);
  assert.equal(inputByPlaceholder('请输入 来源编码 sourceNo').value, 'VALUE-sourceNo');
  assert.equal(
    inputByPlaceholder('已配置（不显示明文）').placeholder,
    '已配置（不显示明文）',
  );

  // 修改 pin 与 townRequired 后提交；先补齐缺失的 5 项字符串标识
  for (const [key, label] of Object.entries({
    erpShopNo: 'ERP 店铺编码 erpShopNo',
    salesPlatformSource: '销售平台来源 salesPlatformSource',
    ownerNo: '货主编码 ownerNo',
    shopNo: '店铺编码 shopNo',
    carrierNo: '承运商编码 carrierNo',
  })) {
    const input = inputByPlaceholder(`请输入 ${label}`);
    await act(async () => {
      const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set;
      setter?.call(input, `VALUE-${key}`);
      input.dispatchEvent(new Event('input', { bubbles: true }));
      input.dispatchEvent(new Event('change', { bubbles: true }));
    });
  }
  const pinInput = inputByPlaceholder('已配置（不显示明文）');
  await act(async () => {
    const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set;
    setter?.call(pinInput, 'JD-PIN-NEW-001');
    pinInput.dispatchEvent(new Event('input', { bubbles: true }));
    pinInput.dispatchEvent(new Event('change', { bubbles: true }));
  });
  const switches = document.querySelectorAll<HTMLElement>('.ant-modal .ant-switch');
  assert.equal(switches.length, 2, 'modal must expose active and townRequired switches');
  await act(async () => switches[1].click());
  await act(async () => {
    const ok = document.querySelector<HTMLElement>('.ant-modal-footer .ant-btn-primary');
    assert.ok(ok, 'modal ok button missing');
    ok.click();
  });

  await waitFor(() => assert.match(bodyText(), /履约方配置已保存/));
  assert.equal(patchBodies.length, 1);
  const body = JSON.parse(String(patchBodies[0].init.body));
  assert.deepEqual(body, {
    expected_version: 2,
    provider_name: '京东云仓',
    tracking_sla_minutes: 60,
    active: true,
    config: {
      sourceNo: 'VALUE-sourceNo',
      warehouseNo: 'VALUE-warehouseNo',
      erpShopNo: 'VALUE-erpShopNo',
      salesPlatformSource: 'VALUE-salesPlatformSource',
      ownerNo: 'VALUE-ownerNo',
      shopNo: 'VALUE-shopNo',
      carrierNo: 'VALUE-carrierNo',
      pin: 'JD-PIN-NEW-001',
      townRequired: true,
      outboundMode: 'FILE',
      wecomGroupChatId: null,
      // Issue #84：提醒间隔随 config 合并提交；留空 = null（默认等于运单回传时限）
      wecomReminderIntervalMinutes: null,
    },
  });
  const headers = patchBodies[0].init.headers as Record<string, string>;
  assert.match(headers['Idempotency-Key'] ?? '', /^[0-9a-f-]{36}$/);
  assert.equal(headers['X-Operator'], undefined, 'browser never supplies operator identity');

  // 保存后列表刷新为全部就绪
  await waitFor(() => assert.match(providerRow('JD').textContent ?? '', /全部就绪/));
});
