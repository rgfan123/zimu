/**
 * 组件测试的 jsdom 环境补齐：antd 依赖若干浏览器 API，jsdom 未实现需打桩。
 * 逐文件重复这段样板正是既有 route 测试难写的原因，这里集中一次。
 */

import '@testing-library/jest-dom/vitest';
import { afterEach } from 'vitest';
import { cleanup } from '@testing-library/react';

class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}

if (!globalThis.ResizeObserver) {
  globalThis.ResizeObserver = ResizeObserverStub as unknown as typeof ResizeObserver;
}

// antd 的响应式栅格与 Table 依赖 matchMedia；jsdom 不提供实现。
if (!window.matchMedia) {
  Object.defineProperty(window, 'matchMedia', {
    configurable: true,
    value: (query: string) => ({
      matches: false,
      media: query,
      onchange: null,
      addListener() {},
      removeListener() {},
      addEventListener() {},
      removeEventListener() {},
      dispatchEvent() {
        return false;
      },
    }),
  });
}

// rc-table / rc-drawer 会传 pseudoElt；jsdom 对该参数只打印未实现错误。
const nativeGetComputedStyle = window.getComputedStyle.bind(window);
Object.defineProperty(window, 'getComputedStyle', {
  configurable: true,
  value: (element: Element) => nativeGetComputedStyle(element),
});

afterEach(() => {
  cleanup();
});
