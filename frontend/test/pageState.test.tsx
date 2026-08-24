/**
 * PageState 三态组件测试（issue #36）。
 *
 * 覆盖：loading / error / empty 三态渲染（默认文案与自定义文案）+ 错误态「重试」回调触发。
 * 走 vitest（.tsx），与既有 node:test 逻辑单测（.test.ts）互不干扰。
 */

import { describe, expect, test, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { PAGE_STATE_COPY, PageState } from '@/pages/shared/PageState';

describe('PageState 加载态（loading）', () => {
  test('默认文案「正在加载…」并渲染 Spin', () => {
    render(<PageState state="loading" />);

    expect(screen.getByText(PAGE_STATE_COPY.loading)).toBeInTheDocument();
    expect(document.querySelector('.ant-spin')).toBeInTheDocument();
    // 无障碍：向读屏/测试播报加载中
    expect(screen.getByRole('status')).toBeInTheDocument();
  });

  test('自定义 description 覆盖默认文案', () => {
    render(<PageState state="loading" description="正在加载库存观测…" />);

    expect(screen.getByText('正在加载库存观测…')).toBeInTheDocument();
    expect(screen.queryByText(PAGE_STATE_COPY.loading)).not.toBeInTheDocument();
  });
});

describe('PageState 错误态（error）', () => {
  test('默认文案「加载失败」+「重试」按钮，点击触发 onRetry', async () => {
    const user = userEvent.setup();
    const onRetry = vi.fn();

    render(<PageState state="error" onRetry={onRetry} />);

    expect(screen.getByText(PAGE_STATE_COPY.error)).toBeInTheDocument();
    // 图标 aria-label 会并入按钮可访问名（"reload 重试"），用正则匹配
    expect(screen.getByRole('button', { name: /重试/ })).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /重试/ }));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  test('自定义 message 与 description 均渲染，重试仍可用', async () => {
    const user = userEvent.setup();
    const onRetry = vi.fn();

    render(<PageState state="error" message="库存加载失败" description="后端返回 502" onRetry={onRetry} />);

    expect(screen.getByText('库存加载失败')).toBeInTheDocument();
    expect(screen.getByText('后端返回 502')).toBeInTheDocument();
    expect(screen.queryByText(PAGE_STATE_COPY.error)).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /重试/ }));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });
});

describe('PageState 空态（empty）', () => {
  test('默认文案「暂无数据」', () => {
    render(<PageState state="empty" />);

    expect(screen.getByText(PAGE_STATE_COPY.empty)).toBeInTheDocument();
  });

  test('自定义 description 覆盖默认文案', () => {
    render(<PageState state="empty" description="当前筛选范围内暂无匹配 SKU" />);

    expect(screen.getByText('当前筛选范围内暂无匹配 SKU')).toBeInTheDocument();
    expect(screen.queryByText(PAGE_STATE_COPY.empty)).not.toBeInTheDocument();
  });
});
