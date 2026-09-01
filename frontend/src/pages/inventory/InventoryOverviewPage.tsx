import { useState, useMemo } from 'react';
import { Alert, Button, Input, Select, Space, Tag, Typography } from 'antd';
import { ReloadOutlined, SearchOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { Link, useSearchParams } from 'react-router-dom';
import DataTable from '@/components/DataTable';
import FilterBar from '@/components/FilterBar';
import PageShell from '@/components/PageShell';
import { errorMessage } from '@/api/client';
import { inventoryApi, providersApi, skusApi } from '@/api/endpoints';
import { useAsync } from '@/hooks/useAsync';
import { AdminEmpty } from '@/pages/shared/AdminVisualComponents';
import { adminFailurePresentation } from '@/pages/shared/adminVisual';
import { PageState } from '@/pages/shared/PageState';
import { ProductIdentity } from '@/pages/shared/ProductIdentity';
import '@/pages/shared/adminSurface.css';
import './inventoryOverview.css';
import {
  inventoryObservationPresentation,
  inventoryOverviewWarnings,
  inventoryQuantityLabel,
  inventoryQuantityUnit,
  inventorySourceLabel,
  inventoryTimeLabel,
  type InventoryOverviewItem,
  type InventoryOverviewResponse,
} from './inventoryOverviewView';

interface InventoryFilters {
  providerId?: string;
  skuId?: string;
  warehouseCode?: string;
}

const EMPTY_FILTERS: InventoryFilters = {};

/** 下拉选项的通用检索：按名称或编码大小写不敏感匹配。 */
function matchOption(input: string, option?: { label?: string }) {
  return (option?.label ?? '').toLowerCase().includes(input.toLowerCase());
}

function boundedIntegerParam(
  params: URLSearchParams,
  key: string,
  fallback: number,
  minimum: number,
  maximum: number,
): number {
  const raw = params.get(key);
  if (!raw || !/^\d+$/.test(raw)) return fallback;
  const value = Number(raw);
  return Number.isSafeInteger(value) && value >= minimum && value <= maximum ? value : fallback;
}

function filterParam(params: URLSearchParams, key: string): string | undefined {
  return params.get(key)?.trim() || undefined;
}

function overviewLocation(page: number, size: number, filters: InventoryFilters): string {
  const params = new URLSearchParams({ page: String(page), size: String(size) });
  if (filters.providerId) params.set('provider_id', filters.providerId);
  if (filters.skuId) params.set('sku_id', filters.skuId);
  if (filters.warehouseCode) params.set('warehouse_code', filters.warehouseCode);
  return `/inventory/overview?${params}`;
}

function detailLocation(
  item: InventoryOverviewItem,
  warehouseCode: string | undefined,
  returnTo: string,
): string {
  const params = new URLSearchParams({
    provider_id: item.provider_id,
    sku_id: item.sku_id,
  });
  const resolvedWarehouse = item.warehouse_code ?? warehouseCode;
  if (resolvedWarehouse) params.set('warehouse_code', resolvedWarehouse);
  params.set('return_to', returnTo);
  return `/inventory/details?${params}`;
}

function inventoryOverview(
  page: number,
  size: number,
  filters: InventoryFilters,
): Promise<InventoryOverviewResponse> {
  return inventoryApi.overview({
    page,
    size,
    provider_id: filters.providerId,
    sku_id: filters.skuId,
    warehouse_code: filters.warehouseCode,
  });
}

function observationTag(item: InventoryOverviewItem) {
  const presentation = inventoryObservationPresentation(item);
  const color = presentation.tone === 'neutral'
    ? undefined
    : presentation.tone === 'info'
      ? 'processing'
      : presentation.tone;
  return <Tag color={color}>{presentation.label}</Tag>;
}

export default function InventoryOverviewPage() {
  const [searchParams] = useSearchParams();
  const [initialState] = useState(() => ({
    page: boundedIntegerParam(searchParams, 'page', 0, 0, Number.MAX_SAFE_INTEGER),
    size: boundedIntegerParam(searchParams, 'size', 20, 1, 200),
    filters: {
      providerId: filterParam(searchParams, 'provider_id'),
      skuId: filterParam(searchParams, 'sku_id'),
      warehouseCode: filterParam(searchParams, 'warehouse_code'),
    },
  }));
  const [page, setPage] = useState(initialState.page);
  const [size, setSize] = useState(initialState.size);
  const [draftFilters, setDraftFilters] = useState<InventoryFilters>(initialState.filters);
  const [filters, setFilters] = useState<InventoryFilters>(initialState.filters);

  const overview = useAsync(
    () => inventoryOverview(page, size, filters),
    [page, size, filters.providerId, filters.skuId, filters.warehouseCode],
  );

  // 筛选下拉选项（UIUX-09 #143）：履约方 / SKU 来自主数据接口，仓库取当前结果集内已观测编码。
  const providers = useAsync(() => providersApi.list(), []);
  const skus = useAsync(() => skusApi.list({ size: 500 }), []);
  const warehouseOptions = useMemo(() => {
    const codes = new Set<string>();
    for (const item of overview.data?.items ?? []) {
      if (item.warehouse_code) codes.add(item.warehouse_code);
    }
    return [...codes].sort();
  }, [overview.data]);

  const response = overview.data;
  const warnings = response ? inventoryOverviewWarnings(response) : [];
  const returnTo = overviewLocation(page, size, filters);
  const columns: ColumnsType<InventoryOverviewItem> = [
    {
      title: '商品 / SKU',
      key: 'sku',
      width: 210,
      render: (_, item) => (
        <Space direction="vertical" size={0}>
          <ProductIdentity name={item.product_name} code={item.sku_code} meta={[item.specification]} />
          <Link to={detailLocation(item, filters.warehouseCode, returnTo)}>查看明细</Link>
        </Space>
      ),
    },
    {
      title: '履约方',
      dataIndex: 'provider_name',
      width: 160,
      render: (value: string, item) => (
        <Space direction="vertical" size={0}>
          <Typography.Text>{value}</Typography.Text>
          <Typography.Text type="secondary" className="inventory-overview__secondary">
            {item.provider_code}
          </Typography.Text>
        </Space>
      ),
    },
    {
      title: '仓库',
      dataIndex: 'warehouse_code',
      width: 140,
      render: (value: string | null) => value
        ?? (filters.warehouseCode
          ? `目标仓 ${filters.warehouseCode} 尚未观测`
          : '尚未观测仓库'),
    },
    {
      title: '总库存',
      dataIndex: 'total_quantity',
      align: 'right',
      width: 118,
      render: (value: number | null, item) => inventoryQuantityLabel(value, inventoryQuantityUnit(item)),
    },
    {
      title: '可用',
      dataIndex: 'available_quantity',
      align: 'right',
      width: 108,
      render: (value: number | null, item) => inventoryQuantityLabel(value, inventoryQuantityUnit(item)),
    },
    {
      title: '不可用差额',
      dataIndex: 'unavailable_quantity',
      align: 'right',
      width: 128,
      render: (value: number | null, item) => inventoryQuantityLabel(value, inventoryQuantityUnit(item)),
    },
    {
      title: '观测状态',
      key: 'observation',
      width: 190,
      render: (_, item) => observationTag(item),
    },
    {
      title: '观测时间',
      dataIndex: 'observed_at',
      width: 168,
      render: (value: string | null) => inventoryTimeLabel(value),
    },
    {
      title: '来源',
      dataIndex: 'source_type',
      width: 136,
      render: (value: string | null) => inventorySourceLabel(value),
    },
  ];

  if (overview.loading) {
    return (
      <div className="admin-page">
        <PageState state="loading" description="正在加载库存观测…" />
      </div>
    );
  }

  if (overview.error) {
    const presentation = adminFailurePresentation(overview.error, '总库存加载失败');
    return (
      <div className="admin-page">
        <PageState state="error" message={presentation.title} description={presentation.description} onRetry={overview.reload} />
      </div>
    );
  }

  return (
    <div className="admin-page">
      <PageShell
        title="总库存"
        description="按 SKU、仓库与履约方查看已落库的最新库存观测；未观测范围始终与零库存分开。"
      >
        <div className="inventory-overview__scope" aria-label="库存观测覆盖范围">
          <div>
            <Typography.Text type="secondary">履约方覆盖</Typography.Text>
            <Typography.Text strong>
              {response?.coverage.observed_provider_count ?? 0} / {response?.coverage.provider_count ?? 0}
            </Typography.Text>
          </div>
          <div>
            <Typography.Text type="secondary">SKU 覆盖</Typography.Text>
            <Typography.Text strong>
              {response?.coverage.observed_sku_count ?? 0} / {response?.coverage.sku_count ?? 0}
            </Typography.Text>
          </div>
          <div>
            <Typography.Text type="secondary">已观测仓库</Typography.Text>
            <Typography.Text strong>{response?.coverage.warehouse_count ?? 0}</Typography.Text>
          </div>
          <div>
            <Typography.Text type="secondary">最近观测</Typography.Text>
            <Typography.Text strong>{inventoryTimeLabel(response?.coverage.latest_observed_at ?? null)}</Typography.Text>
          </div>
        </div>

        {warnings.length ? (
          <Alert
            showIcon
            type="info"
            message="库存观测范围提示"
            description={
              <ul className="inventory-overview__warnings">
                {warnings.map((warning) => <li key={warning}>{warning}</li>)}
              </ul>
            }
          />
        ) : null}

        <FilterBar
          actions={<Button icon={<ReloadOutlined />} onClick={overview.reload}>刷新</Button>}
        >
          {providers.error ? (
            <Alert
              type="warning"
              showIcon
              message="履约方选项加载失败，已降级为编码输入"
              description={errorMessage(providers.error)}
              action={<Button size="small" onClick={providers.reload}>重试</Button>}
            />
          ) : null}
          {providers.error ? (
            <Input
              aria-label="履约方 ID"
              placeholder="履约方 ID"
              value={draftFilters.providerId}
              onChange={(event) => setDraftFilters((current) => ({ ...current, providerId: event.target.value || undefined }))}
              style={{ width: 140 }}
              allowClear
            />
          ) : (
            <Select
              showSearch
              allowClear
              aria-label="履约方"
              placeholder="履约方（名称或编码）"
              style={{ width: 200 }}
              value={draftFilters.providerId}
              onChange={(value) => setDraftFilters((current) => ({ ...current, providerId: value || undefined }))}
              loading={providers.loading}
              filterOption={matchOption}
              options={(providers.data ?? []).map((provider) => ({
                value: provider.id,
                label: `${provider.provider_name}（${provider.provider_code}）`,
              }))}
            />
          )}
          {skus.error ? (
            <Alert
              type="warning"
              showIcon
              message="SKU 选项加载失败，已降级为编码输入"
              description={errorMessage(skus.error)}
              action={<Button size="small" onClick={skus.reload}>重试</Button>}
            />
          ) : null}
          {skus.error ? (
            <Input
              aria-label="SKU ID"
              placeholder="SKU ID"
              value={draftFilters.skuId}
              onChange={(event) => setDraftFilters((current) => ({ ...current, skuId: event.target.value || undefined }))}
              style={{ width: 140 }}
              allowClear
            />
          ) : (
            <Select
              showSearch
              allowClear
              aria-label="SKU"
              placeholder="SKU（商品名或编码）"
              style={{ width: 220 }}
              value={draftFilters.skuId}
              onChange={(value) => setDraftFilters((current) => ({ ...current, skuId: value || undefined }))}
              loading={skus.loading}
              filterOption={matchOption}
              options={(skus.data?.items ?? []).map((sku) => ({
                value: sku.id,
                label: `${sku.name}（${sku.code}）`,
              }))}
            />
          )}
          <Select
            showSearch
            allowClear
            aria-label="仓库编码"
            placeholder="仓库编码（当前结果集）"
            style={{ width: 200 }}
            value={draftFilters.warehouseCode}
            onChange={(value) => setDraftFilters((current) => ({ ...current, warehouseCode: value || undefined }))}
            filterOption={matchOption}
            options={warehouseOptions.map((code) => ({ value: code, label: code }))}
            notFoundContent={warehouseOptions.length ? undefined : '当前结果集内无已观测仓库，清空筛选后查看'}
          />
          <Button type="primary" icon={<SearchOutlined />} onClick={() => { setPage(0); setFilters(draftFilters); }}>
            查询
          </Button>
          <Button onClick={() => { setPage(0); setDraftFilters(EMPTY_FILTERS); setFilters(EMPTY_FILTERS); }}>
            重置
          </Button>
        </FilterBar>

        <div className="admin-surface">
          <DataTable<InventoryOverviewItem>
            rowKey={(item) => `${item.provider_id}:${item.sku_id}:${item.warehouse_code ?? 'NOT_OBSERVED'}`}
            columns={columns}
            dataSource={response?.items ?? []}
            scroll={{ x: 1360 }}
            emptyText={<AdminEmpty description="当前筛选范围内暂无匹配 SKU" />}
            pagination={{
              current: page + 1,
              pageSize: size,
              total: response?.total_elements ?? 0,
              showSizeChanger: true,
              showTotal: (total) => `共 ${total} 条`,
              onChange: (nextPage, nextSize) => {
                setPage(nextPage - 1);
                setSize(nextSize);
              },
            }}
          />
        </div>
      </PageShell>
    </div>
  );
}
