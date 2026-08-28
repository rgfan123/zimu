import { App as AntApp } from 'antd';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { beforeEach, describe, expect, test, vi } from 'vitest';
import SkusPage from '@/pages/product/SkusPage';

const { archiveList, archiveSheet, skuExport, skuList } = vi.hoisted(() => ({
  archiveList: vi.fn(),
  archiveSheet: vi.fn(),
  skuExport: vi.fn(),
  skuList: vi.fn(),
}));

vi.mock('@/pages/product/masterOptions', () => ({
  useCategoryOptions: () => [],
  useProviderOptions: () => [],
}));

vi.mock('@/api/endpoints', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/endpoints')>();
  return {
    ...actual,
    skusApi: { ...actual.skusApi, list: skuList, exportFile: skuExport },
    productsApi: { ...actual.productsApi, archiveSheet },
    productArchiveSheetsApi: { list: archiveList },
  };
});

const EXPECTED_ARCHIVE_FIELDS = [
  ['A', '产品名称'], ['B', '产品状态'], ['C', '规格（g）'], ['D', '国条'],
  ['E', '品牌'], ['F', '肉类'], ['G', '原料'], ['H', '供应渠道'],
  ['I', '包装形式'], ['J', '加工要求'], ['K', '净含量/g'], ['L', '加工规格/g'],
  ['M', '原料成本kg/元'], ['N', '核算成本 /份'], ['O', '原料利润'], ['P', '成本+原料利润/kg'],
  ['Q', '人工费'], ['R', '人工 占比'], ['S', '修割损耗率'], ['T', '损耗成本/KG'],
  ['U', '损耗后 成本/KG'], ['V', '加工后 成本/KG'], ['W', '加工后 成本/份'], ['X', '盒/袋'],
  ['Y', '贴纸/腰封'], ['Z', '膜'], ['AA', '签'], ['AB', '泡沫箱/纸箱+冰袋'],
  ['AC', '耗材/KG'], ['AD', '耗材/份'], ['AE', '耗材 占比'], ['AF', '含耗材 成本/份'],
  ['AG', '物流（原料进货）/kg'], ['AH', '物流（成品送货）/kg'], ['AI', '线下供货成本/份'], ['AJ', '售价'],
  ['AK', '（AK 列无表头）'], ['AL', '账期比例'], ['AM', '账期费用/份'], ['AN', '扣点'],
  ['AO', '扣点费用/份'], ['AP', '总成本/KG'], ['AQ', '扣完成本/份'], ['AR', '供货价'],
  ['AS', '毛利率'], ['AT', '促销价格'], ['AU', '大促'],
] as const;

const DEFAULT_ARCHIVE_FIELD_INDEXES = [1, 2, 4, 10, 12, 13, 31, 34, 35, 43, 44] as const;

function sku(id: string, productId: string, name: string, emg: string) {
  return {
    id,
    code: `SKU-${id}`,
    name,
    active: true,
    version: 0,
    attributes: {
      product_id: productId,
      provider_id: '1',
      specification: '500g',
      unit: '袋',
      jd_emg_no: emg,
      purchase_price: null,
      retail_price: null,
    },
  };
}

function archiveRow(id: string, matchedProductId: string | null) {
  return {
    id,
    source_file_name: 'A产品成本核算26.3.29.xlsx',
    source_file_sha256: `sha-${id}`,
    sheet_name: '成品',
    row_no: Number(id),
    product_name: `成本行-${id}`,
    matched_product_id: matchedProductId,
    fields: EXPECTED_ARCHIVE_FIELDS.map(([column, name], index) => ({
      column,
      name,
      value: `值-${String(index + 1).padStart(2, '0')}`,
    })),
    extra_cells: [],
  };
}

function tableRowContaining(text: string): HTMLTableRowElement {
  const cell = screen.getByText(text);
  const row = cell.closest('tr');
  if (!(row instanceof HTMLTableRowElement)) throw new Error(`找不到 ${text} 所在表格行`);
  return row;
}

describe('商品档案页成本表列', () => {
  beforeEach(() => {
    archiveList.mockReset();
    archiveSheet.mockReset();
    skuExport.mockReset();
    skuList.mockReset();
    skuExport.mockResolvedValue(undefined);
    archiveList.mockResolvedValue({
      items: [archiveRow('1', '101'), archiveRow('2', null)],
      page: 0,
      size: 200,
      total_elements: 2,
      total_pages: 1,
    });
    skuList.mockResolvedValue({
      items: [
        sku('1', '101', '已挂接 SKU', 'EMG-MATCHED'),
        sku('2', '202', '未挂接 SKU', 'EMG-UNMATCHED'),
      ],
      page: 0,
      size: 10,
      total_elements: 2,
      total_pages: 1,
    });
    archiveSheet.mockResolvedValue([archiveRow('1', '101')]);
  });

  test('一次拉取全量档案，默认按原表列序展示 11 个关键列并诚实呈现挂接率', async () => {
    render(
      <AntApp>
        <MemoryRouter>
          <SkusPage />
        </MemoryRouter>
      </AntApp>,
    );

    expect(await screen.findByText(/已挂接 1 \/ 成本表共 2 行/)).toBeInTheDocument();
    await waitFor(() => expect(archiveList).toHaveBeenCalledTimes(1));
    expect(archiveList).toHaveBeenCalledWith({ page: 0, size: 200 });

    for (const group of ['基础信息', '成本构成', '供货与售价']) {
      expect(screen.getByRole('columnheader', { name: group })).toBeInTheDocument();
    }
    for (const index of DEFAULT_ARCHIVE_FIELD_INDEXES) {
      const [, name] = EXPECTED_ARCHIVE_FIELDS[index];
      expect(screen.getByRole('columnheader', { name })).toBeInTheDocument();
    }
    for (const index of [0, 3, 36, 46]) {
      const [, name] = EXPECTED_ARCHIVE_FIELDS[index];
      expect(screen.queryByRole('columnheader', { name })).not.toBeInTheDocument();
    }
    for (const removed of ['主图', '品类', '规格', '单位', '履约方', '毛利', '标签', '上市周期', '发货时效', '进货价', '零售价', '条码']) {
      expect(screen.queryByRole('columnheader', { name: removed })).not.toBeInTheDocument();
    }

    const matchedCells = within(tableRowContaining('已挂接 SKU'))
      .getAllByRole('cell')
      .map((cell) => cell.textContent?.trim() ?? '');
    const firstArchiveCell = matchedCells.indexOf('值-02');
    expect(matchedCells.slice(firstArchiveCell, firstArchiveCell + 11)).toEqual(
      DEFAULT_ARCHIVE_FIELD_INDEXES.map((index) => `值-${String(index + 1).padStart(2, '0')}`),
    );

    const unmatchedCells = within(tableRowContaining('未挂接 SKU'))
      .getAllByRole('cell')
      .map((cell) => cell.textContent?.trim() ?? '');
    const emgCell = unmatchedCells.indexOf('EMG-UNMATCHED');
    expect(unmatchedCells.slice(emgCell + 1, emgCell + 12)).toEqual(Array(11).fill('—'));
    expect(unmatchedCells).not.toContain('0');
  });

  test('列设置按任意勾选顺序操作后，新增列仍按 A..AU 原表位置插入', async () => {
    const user = userEvent.setup();
    render(
      <AntApp>
        <MemoryRouter>
          <SkusPage />
        </MemoryRouter>
      </AntApp>,
    );

    expect(await screen.findByText('已挂接 SKU')).toBeInTheDocument();
    await waitFor(() => expect(skuList).toHaveBeenCalledTimes(1));
    const defaultTableWidth = Number.parseInt(screen.getByRole('table').style.width, 10);
    expect(defaultTableWidth).toBeLessThan(3000);
    await user.click(screen.getByRole('button', { name: /列设置/ }));
    await user.click(screen.getByRole('checkbox', { name: 'AU 大促' }));
    await user.click(screen.getByRole('checkbox', { name: 'AK （AK 列无表头）' }));
    await user.click(screen.getByRole('checkbox', { name: 'A 产品名称' }));

    for (const index of [0, 36, 46]) {
      const [, name] = EXPECTED_ARCHIVE_FIELDS[index];
      expect(screen.getByRole('columnheader', { name })).toBeInTheDocument();
    }

    const matchedCells = within(tableRowContaining('已挂接 SKU'))
      .getAllByRole('cell')
      .map((cell) => cell.textContent?.trim() ?? '');
    const firstArchiveCell = matchedCells.indexOf('值-01');
    const expectedIndexes = [0, ...DEFAULT_ARCHIVE_FIELD_INDEXES.slice(0, 9), 36, ...DEFAULT_ARCHIVE_FIELD_INDEXES.slice(9), 46];
    expect(matchedCells.slice(firstArchiveCell, firstArchiveCell + expectedIndexes.length)).toEqual(
      expectedIndexes.map((index) => `值-${String(index + 1).padStart(2, '0')}`),
    );
    expect(Number.parseInt(screen.getByRole('table').style.width, 10)).toBeGreaterThan(defaultTableWidth);
    expect(skuList).toHaveBeenCalledTimes(1);
  });

  test('成本档案抽屉仍展示单行完整 47 列', async () => {
    const user = userEvent.setup();
    render(
      <AntApp>
        <MemoryRouter>
          <SkusPage />
        </MemoryRouter>
      </AntApp>,
    );

    await screen.findByText('已挂接 SKU');
    const matchedRow = tableRowContaining('已挂接 SKU');
    await user.click(within(matchedRow).getByRole('button', { name: '查看' }));

    expect(await screen.findByText(/共 47 列，按原表列序展示/)).toBeInTheDocument();
    expect(archiveSheet).toHaveBeenCalledWith('101');
    expect(screen.getByRole('cell', { name: '（AK 列无表头）' })).toBeInTheDocument();
  });

  test('导出表格调用共享下载 API，导出中展示 loading，失败后提示且可以重试', async () => {
    const user = userEvent.setup();
    let finishExport: (() => void) | undefined;
    skuExport.mockImplementationOnce(() => new Promise<void>((resolve) => {
      finishExport = resolve;
    }));
    render(
      <AntApp>
        <MemoryRouter>
          <SkusPage />
        </MemoryRouter>
      </AntApp>,
    );

    await screen.findByText('已挂接 SKU');
    const exportButton = screen.getByRole('button', { name: '导出表格' });
    await user.click(exportButton);
    expect(skuExport).toHaveBeenCalledTimes(1);
    expect(exportButton).toHaveClass('ant-btn-loading');

    finishExport?.();
    await waitFor(() => expect(exportButton).not.toHaveClass('ant-btn-loading'));

    skuExport.mockRejectedValueOnce(new Error('download failed'));
    await user.click(exportButton);
    expect(await screen.findByText('商品档案导出失败，请重试')).toBeInTheDocument();
    expect(exportButton).not.toBeDisabled();

    skuExport.mockResolvedValueOnce(undefined);
    await user.click(exportButton);
    await waitFor(() => expect(skuExport).toHaveBeenCalledTimes(3));
  });
});
