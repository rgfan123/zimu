/**
 * 路由级测试共享 harness（Issue #95）：JSDOM + Vite SSR + MemoryRouter 装配只写一次，
 * 新增路由测试不再复制整套环境。用法：
 *
 *   const harness = await createRouteHarness('http://localhost/workbench/reviews');
 *   globalThis.fetch = async (input, init) => jsonResponse(...);
 *   await harness.mount(['/workbench/reviews?import_batch=7']);
 *   await harness.waitFor(() => assert.match(harness.bodyText(), /.../));
 *   await harness.unmount(); ... await harness.close();
 */

import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { JSDOM } from 'jsdom';

const frontendRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

const BROWSER_GLOBALS = [
  'window', 'document', 'navigator', 'HTMLElement', 'HTMLBodyElement', 'HTMLInputElement', 'HTMLTextAreaElement',
  'SVGElement', 'Element', 'Document', 'Node', 'ShadowRoot', 'MutationObserver', 'Event',
  'MouseEvent', 'KeyboardEvent', 'File', 'Blob', 'FormData',
] as const;

function installDom(initialUrl: string): JSDOM {
  const dom = new JSDOM('<!doctype html><html><body><div id="root"></div></body></html>', { url: initialUrl });
  for (const key of BROWSER_GLOBALS) {
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
      matches: false, media: '', onchange: null,
      addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {},
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
  return dom;
}

export function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  });
}

export function apiErrorResponse(status: number, businessCode: string, message: string) {
  return jsonResponse({ message, http_status: status, business_code: businessCode, trace_id: 'test-trace' }, status);
}

export function page(items: unknown[], size = 20) {
  return {
    items,
    page: 0,
    size,
    total_elements: items.length,
    total_pages: items.length ? 1 : 0,
  };
}

/** 在 button/a 中按包含文本查找控件；找不到即抛错，避免空引用后继续产生误导性断言。 */
export function control(text: string): HTMLElement {
  const element = [...document.querySelectorAll<HTMLElement>('button, a')]
    .find((candidate) => candidate.textContent?.includes(text));
  if (!element) throw new Error(`missing control: ${text}`);
  return element;
}

export interface RouteHarness {
  /** 页面可见文本（压缩空白），用于行为断言。 */
  bodyText(): string;
  /** 当前路由 location.pathname + search（LocationProbe 输出），用于 URL 断言。 */
  location(): string;
  /** 挂载 App；initialEntries 为 MemoryRouter 初始历史。 */
  mount(initialEntries: string[]): Promise<void>;
  /** 在 React act 中派发 DOM 事件（如 file input change），保证状态更新被 flush。 */
  dispatchEvent(target: Element, event: Event): Promise<void>;
  /** 卸载并清空 antd 全局消息，供 afterEach 使用。 */
  unmount(): Promise<void>;
  /** 轮询等待断言通过（React act 包裹），超时抛最后错误。 */
  waitFor(assertion: () => void, timeoutMs?: number): Promise<void>;
  /** 关闭 vite 与 JSDOM，供 after 使用。 */
  close(): Promise<void>;
}

export async function createRouteHarness(initialUrl: string): Promise<RouteHarness> {
  const dom = installDom(initialUrl);
  const { createRoot } = await import('react-dom/client');
  const { act, createElement, Fragment } = await import('react');
  const { MemoryRouter, useLocation } = await import('react-router-dom');
  const { createServer } = await import('vite');
  const vite = await createServer({
    root: frontendRoot,
    server: { middlewareMode: true },
    appType: 'custom',
    logLevel: 'silent',
    optimizeDeps: { noDiscovery: true, include: [] },
  });
  const App = (await vite.ssrLoadModule('/src/App.tsx')).default;

  function LocationProbe() {
    const location = useLocation();
    return createElement('output', { 'data-testid': 'route-location', hidden: true }, location.pathname + location.search);
  }

  let mountedRoot: ReturnType<typeof createRoot> | null = null;

  return {
    bodyText: () => document.body.textContent?.replace(/\s+/g, ' ').trim() ?? '',
    location: () => document.querySelector('[data-testid="route-location"]')?.textContent ?? '',
    async mount(initialEntries: string[]) {
      document.body.innerHTML = '<div id="root"></div>';
      const container = document.querySelector<HTMLDivElement>('#root');
      if (!container) throw new Error('test root must exist');
      mountedRoot = createRoot(container);
      await act(async () => {
        mountedRoot?.render(createElement(
          MemoryRouter,
          { initialEntries, future: { v7_startTransition: true, v7_relativeSplatPath: true } },
          createElement(Fragment, null, createElement(App), createElement(LocationProbe)),
        ));
      });
    },
    async dispatchEvent(target: Element, event: Event) {
      await act(async () => {
        target.dispatchEvent(event);
      });
    },
    async unmount() {
      if (mountedRoot) {
        await act(async () => mountedRoot?.unmount());
        mountedRoot = null;
      }
      const { message, notification } = await import('antd');
      await act(async () => {
        message.destroy();
        notification.destroy();
        await new Promise((resolve) => setTimeout(resolve, 0));
      });
    },
    async close() {
      await vite.close();
      dom.window.close();
    },
    async waitFor(assertion: () => void, timeoutMs = 3_000) {
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
    },
  };
}
