/**
 * 回归测试：MasterDataCrud 的筛选区必须始终挂载。
 *
 * 事故复盘：加载/错误态曾经整棵树替换为 stateContent，导致每次筛选变化都 unmount 工具栏，
 * 非受控输入随之丢失用户已输入内容，用户卡在「看不见也退不出」的筛选态。
 * 这里用组件级渲染锁住行为：加载态与错误态都只允许替换表格区域。
 */

import { useState } from 'react';
import { describe, expect, test, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { App as AntApp } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import MasterDataCrud, { type MasterDataCrudProps } from '@/pages/shared/MasterDataCrud';
import type { MasterDataPage, MasterDataRecord } from '@/api/types';

const columns: ColumnsType<MasterDataRecord> = [
  { title: '名称', dataIndex: 'name', key: 'name' },
];

function pageOf(items: MasterDataRecord[]): MasterDataPage {
  return {
    items,
    page: 0,
    size: 10,
    total_elements: items.length,
    total_pages: items.length ? 1 : 0,
  };
}

const beefRecord: MasterDataRecord = {
  id: '1',
  code: 'SKU-BEEF-001',
  name: '子牧牛腱',
  active: true,
  version: 1,
};

/** 可由测试决定何时 resolve/reject 的受控 promise，用于把组件停在加载态观察。 */
function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

/**
 * 测试替身：模拟真实调用方（如 SkusPage）——筛选区里放一个非受控输入，
 * 外加一个会改变 extraQuery 的按钮。非受控输入是最敏感的探针：
 * 一旦筛选区被 unmount，DOM 里的已输入文本必然丢失。
 */
function CrudHarness({ fetchPage }: { fetchPage: MasterDataCrudProps['fetchPage'] }) {
  const [providerId, setProviderId] = useState<string | undefined>();
  return (
    <AntApp>
      <MasterDataCrud
        filters={
          <>
            <input data-testid="keyword-filter" placeholder="关键词" />
            <button type="button" onClick={() => setProviderId('provider-2')}>
              切换履约方
            </button>
          </>
        }
        extraQuery={{ provider_id: providerId }}
        fetchPage={fetchPage}
        columns={columns}
      />
    </AntApp>
  );
}

type MasterDataCrudProps = Parameters<typeof MasterDataCrud>[0];

describe('MasterDataCrud 筛选区常驻', () => {
  test('翻到后页后切换筛选条件，首个新请求必须回到第一页', async () => {
    const user = userEvent.setup();
    const fetchPage = vi.fn<MasterDataCrudProps['fetchPage']>(async ({ page, size }) => ({
      items: [beefRecord],
      page,
      size,
      total_elements: 25,
      total_pages: 3,
    }));

    render(<CrudHarness fetchPage={fetchPage} />);

    await screen.findByText('子牧牛腱');
    const secondPage = document.querySelector<HTMLElement>('.ant-pagination-item-2');
    expect(secondPage).not.toBeNull();
    await user.click(secondPage!);
    await waitFor(() => expect(fetchPage).toHaveBeenLastCalledWith({ page: 1, size: 10 }));

    await user.click(screen.getByRole('button', { name: '切换履约方' }));
    await waitFor(() => expect(fetchPage).toHaveBeenLastCalledWith({
      provider_id: 'provider-2',
      page: 0,
      size: 10,
    }));
  });

  test('筛选变化进入加载态时，筛选区不被 unmount 且保留用户已输入内容', async () => {
    const user = userEvent.setup();
    const firstLoad = deferred<MasterDataPage>();
    const secondLoad = deferred<MasterDataPage>();
    const fetchPage = vi
      .fn<MasterDataCrudProps['fetchPage']>()
      .mockReturnValueOnce(firstLoad.promise)
      .mockReturnValueOnce(secondLoad.promise);

    render(<CrudHarness fetchPage={fetchPage} />);

    await waitFor(() => expect(fetchPage).toHaveBeenCalledTimes(1));
    firstLoad.resolve(pageOf([beefRecord]));
    await screen.findByText('子牧牛腱');

    // 用户在筛选区输入了关键词（非受控，只存在于 DOM）
    const keywordInput = screen.getByTestId('keyword-filter');
    await user.type(keywordInput, '牛腱');
    expect(keywordInput).toHaveValue('牛腱');

    // 触发筛选变化 → queryKey 变化 → 组件进入加载态（第二个 promise 未 resolve）
    await user.click(screen.getByRole('button', { name: '切换履约方' }));
    await waitFor(() => expect(fetchPage).toHaveBeenCalledTimes(2));

    // 加载态确实生效：表格区域被替换
    expect(screen.queryByText('子牧牛腱')).not.toBeInTheDocument();

    // 核心断言：筛选区仍在 DOM 中，且是同一个 DOM 节点（未经历 unmount/remount）
    expect(screen.getByTestId('keyword-filter')).toBeInTheDocument();
    expect(screen.getByTestId('keyword-filter')).toBe(keywordInput);
    expect(keywordInput).toHaveValue('牛腱');

    secondLoad.resolve(pageOf([beefRecord]));
    await screen.findByText('子牧牛腱');
  });

  test('fetchPage 失败进入错误态时，筛选区仍可见以便用户改条件重试', async () => {
    const user = userEvent.setup();
    const firstLoad = deferred<MasterDataPage>();
    const failedLoad = deferred<MasterDataPage>();
    const fetchPage = vi
      .fn<MasterDataCrudProps['fetchPage']>()
      .mockReturnValueOnce(firstLoad.promise)
      .mockReturnValueOnce(failedLoad.promise);

    render(<CrudHarness fetchPage={fetchPage} />);

    await waitFor(() => expect(fetchPage).toHaveBeenCalledTimes(1));
    firstLoad.resolve(pageOf([beefRecord]));
    await screen.findByText('子牧牛腱');

    const keywordInput = screen.getByTestId('keyword-filter');
    await user.type(keywordInput, '牛腱');

    await user.click(screen.getByRole('button', { name: '切换履约方' }));
    await waitFor(() => expect(fetchPage).toHaveBeenCalledTimes(2));
    failedLoad.reject(new Error('后端不可用'));

    // 错误态渲染出来
    await screen.findByText('数据加载失败');

    // 错误态下筛选区同样常驻，用户可以改条件重试
    expect(screen.getByTestId('keyword-filter')).toBeInTheDocument();
    expect(screen.getByTestId('keyword-filter')).toBe(keywordInput);
    expect(keywordInput).toHaveValue('牛腱');
    expect(screen.getByRole('button', { name: '切换履约方' })).toBeInTheDocument();
  });
});
