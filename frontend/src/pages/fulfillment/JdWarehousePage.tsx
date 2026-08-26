/** 京东工具 · 连接与出库查询：只读检查授权、仓库权限与出库事实。 */

import { useEffect, useState } from 'react';
import { App as AntApp, Alert, Button, Card, DatePicker, Descriptions, Input, Pagination, Space, Tag, Typography } from 'antd';
import { CloudServerOutlined, DownloadOutlined, SearchOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import DataTable from '@/components/DataTable';
import FilterBar from '@/components/FilterBar';
import PageShell from '@/components/PageShell';
import { ApiError, apiRequest, errorMessage } from '@/api/client';
import { jdWarehouseApi } from '@/api/endpoints';
import type { ApiErrorBody, JdQueryResult } from '@/api/types';
import { useAsync } from '@/hooks/useAsync';
import { PageState } from '@/pages/shared/PageState';
import { jdConnectionSemantic } from '@/pages/shared/semanticStatus';
import dayjs from 'dayjs';
import { jdQueryPresentation } from '@/presentation/publicReady';

type QueryKind = 'owners' | 'warehouses' | 'outbound';

interface QueryState {
  kind: QueryKind;
  mode: 'MOCK' | 'REAL';
  result: JdQueryResult;
}

interface OrderNoRow {
  orderNo: string;
  erpOrderNo: string;
}

interface OrderListFilter {
  startDate: string;
  endDate: string;
  status: string;
  currentPage: number;
  pageSize: number;
}

interface OrderNosPage {
  rows: OrderNoRow[];
  total: number;
}

/** 兼容真实 LOP（resultList/orderNo）与 Mock（result_list/order_no 包裹在 response 下）两种数据形状。 */
function orderNosPageFrom(result: JdQueryResult): OrderNosPage {
  const data = result.data as Record<string, unknown> | null | undefined;
  const page = (typeof data === 'object' && data !== null ? data : {}) as Record<string, unknown>;
  const payload =
    typeof page.response === 'object' && page.response !== null
      ? (page.response as Record<string, unknown>)
      : page;
  const rows: OrderNoRow[] = [];
  const list = payload.resultList ?? payload.result_list;
  if (Array.isArray(list)) {
    for (const item of list) {
      if (typeof item !== 'object' || item === null) continue;
      const row = item as Record<string, unknown>;
      const orderNo =
        typeof row.orderNo === 'string' ? row.orderNo : typeof row.order_no === 'string' ? row.order_no : '';
      const erpOrderNo =
        typeof row.erpOrderNo === 'string'
          ? row.erpOrderNo
          : typeof row.erp_order_no === 'string'
            ? row.erp_order_no
            : '';
      if (orderNo || erpOrderNo) rows.push({ orderNo, erpOrderNo });
    }
  }
  const totalValue = payload.totalNum ?? payload.total_num;
  const total = typeof totalValue === 'number' ? totalValue : rows.length;
  return { rows, total };
}

/** 按当前筛选条件导出出库单号列表（.xlsx），与文件下载通用实现保持一致。 */
async function downloadOrderNosExport(filter: OrderListFilter): Promise<void> {
  const params = new URLSearchParams();
  if (filter.startDate) params.set('start_date', filter.startDate);
  if (filter.endDate) params.set('end_date', filter.endDate);
  if (filter.status) params.set('status', filter.status);
  const res = await fetch(`/api/v1/jd-order/outbound-order-nos/export?${params.toString()}`, {
    headers: { Accept: 'application/octet-stream', 'X-Request-Id': crypto.randomUUID() },
  });
  if (!res.ok) {
    let body: ApiErrorBody = { message: '', http_status: res.status };
    try {
      const parsed = (await res.json()) as ApiErrorBody;
      if (parsed && typeof parsed === 'object') body = parsed;
    } catch {
      // 非 JSON 错误体，保留默认信息
    }
    throw new ApiError(res.status, body);
  }
  const blob = await res.blob();
  const disposition = res.headers.get('Content-Disposition') ?? '';
  const encodedName = disposition.match(/filename\*=UTF-8''([^;]+)/i)?.[1];
  const plainName = disposition.match(/filename="?([^";]+)"?/i)?.[1];
  let filename = 'jd-outbound-order-nos.xlsx';
  try {
    filename = encodedName ? decodeURIComponent(encodedName) : plainName ?? filename;
  } catch {
    filename = plainName ?? filename;
  }
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  anchor.click();
  URL.revokeObjectURL(url);
}

export default function JdWarehousePage() {
  const { message: messageApi } = AntApp.useApp();
  const sdkStatus = useAsync(() => jdWarehouseApi.status(), []);
  const [erpDeliveryNo, setErpDeliveryNo] = useState('');
  const [sdkResult, setSdkResult] = useState<QueryState | null>(null);
  const [sdkLoading, setSdkLoading] = useState(false);
  const [listFilter, setListFilter] = useState<OrderListFilter>({
    startDate: '',
    endDate: '',
    status: '',
    currentPage: 1,
    pageSize: 10,
  });
  const [orderNosPage, setOrderNosPage] = useState<OrderNosPage | null>(null);
  const [orderListLoading, setOrderListLoading] = useState(false);
  const [exporting, setExporting] = useState(false);

  const runSdkQuery = async (kind: QueryKind) => {
    if (!sdkStatus.data || (kind === 'outbound' && !erpDeliveryNo.trim())) return;
    const mode = sdkStatus.data.client_mode;
    setSdkLoading(true);
    try {
      const result = kind === 'owners'
        ? await jdWarehouseApi.owners()
        : kind === 'warehouses'
          ? await jdWarehouseApi.warehouses()
          : await jdWarehouseApi.outboundOrder(erpDeliveryNo.trim());
      setSdkResult({ kind, mode, result });
      const presentation = jdQueryPresentation(mode, kind, result);
      if (result.success) messageApi.success(presentation.title);
      else messageApi.warning(presentation.title);
    } catch (err) {
      messageApi.error(errorMessage(err));
    } finally {
      setSdkLoading(false);
    }
  };

  const loadOrderNos = async (filter: OrderListFilter) => {
    setOrderListLoading(true);
    try {
      const result = await apiRequest<JdQueryResult>('/api/v1/jd-order/outbound-order-nos', {
        params: {
          start_date: filter.startDate || undefined,
          end_date: filter.endDate || undefined,
          status: filter.status || undefined,
          current_page: filter.currentPage,
          page_size: filter.pageSize,
        },
      });
      setOrderNosPage(orderNosPageFrom(result));
      if (!result.success) messageApi.warning('出库单列表查询未完成，请检查查询条件后重试');
    } catch (err) {
      messageApi.error(errorMessage(err));
    } finally {
      setOrderListLoading(false);
    }
  };

  useEffect(() => {
    void loadOrderNos({ startDate: '', endDate: '', status: '', currentPage: 1, pageSize: 10 });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const applyOrderListFilter = () => {
    const next = { ...listFilter, currentPage: 1 };
    setListFilter(next);
    void loadOrderNos(next);
  };

  const changeOrderNosPage = (page: number, pageSize: number) => {
    const next = { ...listFilter, currentPage: page, pageSize };
    setListFilter(next);
    void loadOrderNos(next);
  };

  const exportOrderNos = async () => {
    setExporting(true);
    try {
      await downloadOrderNosExport(listFilter);
      messageApi.success('导出文件已生成，请查看下载内容');
    } catch (err) {
      messageApi.error(errorMessage(err));
    } finally {
      setExporting(false);
    }
  };

  const openOutboundDetail = async (erpOrderNo: string) => {
    if (!sdkStatus.data) return;
    setErpDeliveryNo(erpOrderNo);
    const mode = sdkStatus.data.client_mode;
    setSdkLoading(true);
    try {
      const result = await jdWarehouseApi.outboundOrder(erpOrderNo);
      setSdkResult({ kind: 'outbound', mode, result });
      const presentation = jdQueryPresentation(mode, 'outbound', result);
      if (result.success) messageApi.success(presentation.title);
      else messageApi.warning(presentation.title);
    } catch (err) {
      messageApi.error(errorMessage(err));
    } finally {
      setSdkLoading(false);
    }
  };

  const resultPresentation = sdkResult
    ? jdQueryPresentation(sdkResult.mode, sdkResult.kind, sdkResult.result)
    : null;

  const orderNosColumns: ColumnsType<OrderNoRow> = [
    {
      title: '京东单号',
      dataIndex: 'orderNo',
      render: (v: string) => <span style={{ fontVariantNumeric: 'tabular-nums' }}>{v || '—'}</span>,
    },
    {
      title: 'ERP单号（点击查看发货详情）',
      dataIndex: 'erpOrderNo',
      render: (v: string) =>
        v ? (
          <Typography.Link
            onClick={() => void openOutboundDetail(v)}
            style={{ fontVariantNumeric: 'tabular-nums' }}
          >
            {v}
          </Typography.Link>
        ) : (
          '—'
        ),
    },
  ];

  return (
    <PageShell
      icon={<CloudServerOutlined />}
      title="京东仓配连接检查"
      description="只读检查账号权限并查询发货事实；不会在此页面创建或取消出库单。"
      actions={
        <Tag color={jdConnectionSemantic(Boolean(sdkStatus.data?.live_ready), sdkStatus.data?.client_mode)}>
          {sdkStatus.loading
            ? '正在确认连接状态'
            : sdkStatus.data?.live_ready
              ? '真实连接已就绪'
              : sdkStatus.data?.client_mode === 'REAL'
                ? '真实连接未就绪'
                : sdkStatus.data?.client_mode === 'MOCK'
                  ? '模拟模式（不代表真实权限）'
                  : '连接状态未知'}
        </Tag>
      }
    >
      <Card size="small">
        <Space direction="vertical" size={14} style={{ width: '100%' }}>
          {sdkStatus.error ? (
            <PageState
              state="error"
              message="京东仓配连接状态加载失败"
              description={errorMessage(sdkStatus.error)}
              onRetry={sdkStatus.reload}
            />
          ) : null}
          {sdkStatus.data?.client_mode === 'REAL' && !sdkStatus.data.live_ready ? (
            <Alert
              type="warning"
              showIcon
              message="真实连接尚未就绪"
              description="京东仓配授权或租户信息尚未完整，请联系管理员完成配置后再试。"
            />
          ) : null}
          <Space.Compact style={{ width: '100%', maxWidth: 720 }}>
            <Button disabled={!sdkStatus.data} loading={sdkLoading} onClick={() => runSdkQuery('owners')}>
              {sdkStatus.data?.client_mode === 'REAL' ? '查询授权事业部' : '查看模拟事业部'}
            </Button>
            <Button disabled={!sdkStatus.data} loading={sdkLoading} onClick={() => runSdkQuery('warehouses')}>
              {sdkStatus.data?.client_mode === 'REAL' ? '检查真实仓库权限' : '查看模拟仓库'}
            </Button>
            <Input
              value={erpDeliveryNo}
              onChange={(event) => setErpDeliveryNo(event.target.value)}
              onPressEnter={() => runSdkQuery('outbound')}
              placeholder="输入系统出库单号，例如 ZM202608120001"
            />
            <Button type="primary" icon={<SearchOutlined />} loading={sdkLoading} disabled={!sdkStatus.data || !erpDeliveryNo.trim()} onClick={() => runSdkQuery('outbound')}>
              {sdkStatus.data?.client_mode === 'MOCK' ? '查看模拟发货信息' : '查询发货信息'}
            </Button>
          </Space.Compact>
          {sdkResult && resultPresentation ? (
            <Alert
              type={resultPresentation.tone}
              showIcon
              message={resultPresentation.title}
              description={
                <Space direction="vertical" size={6} style={{ width: '100%' }}>
                  <Typography.Text>{resultPresentation.description}</Typography.Text>
                  {resultPresentation.rows.length ? (
                    <Descriptions
                      size="small"
                      column={{ xs: 1, sm: 2 }}
                      items={resultPresentation.rows.map((row, index) => ({
                        key: `${row.label}-${index}`,
                        label: row.label,
                        children: row.value,
                      }))}
                    />
                  ) : sdkResult.result.success ? (
                    <Typography.Text type="secondary">本次结果没有可公开展示的业务字段。</Typography.Text>
                  ) : null}
                </Space>
              }
            />
          ) : null}
        </Space>
      </Card>

      <FilterBar
        actions={<Button type="primary" icon={<SearchOutlined />} loading={orderListLoading} onClick={applyOrderListFilter}>查询</Button>}
      >
        <DatePicker.RangePicker
          value={
            listFilter.startDate && listFilter.endDate
              ? [dayjs(listFilter.startDate), dayjs(listFilter.endDate)]
              : null
          }
          onChange={(dates) =>
            setListFilter((prev) => ({
              ...prev,
              startDate: dates?.[0] ? dates[0].format('YYYY-MM-DD') : '',
              endDate: dates?.[1] ? dates[1].format('YYYY-MM-DD') : '',
            }))
          }
          placeholder={['开始日期', '结束日期']}
        />
        <Input
          value={listFilter.status}
          onChange={(event) => setListFilter((prev) => ({ ...prev, status: event.target.value }))}
          onPressEnter={applyOrderListFilter}
          placeholder="出库单状态（可选，如 10）"
          style={{ width: 220 }}
          allowClear
        />
      </FilterBar>

      <Card
        size="small"
        title="出库单列表"
        extra={
          <Button
            size="small"
            icon={<DownloadOutlined />}
            loading={exporting}
            onClick={() => void exportOrderNos()}
          >
            导出当前列表
          </Button>
        }
      >
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          <DataTable<OrderNoRow>
            rowKey={(row) => row.orderNo || row.erpOrderNo}
            columns={orderNosColumns}
            dataSource={orderNosPage?.rows ?? []}
            loading={orderListLoading}
            size="middle"
            pagination={false}
            emptyText="暂无出库单数据"
          />
          <Space style={{ width: '100%', justifyContent: 'flex-end' }}>
            <Pagination
              current={listFilter.currentPage}
              pageSize={listFilter.pageSize}
              total={orderNosPage?.total ?? 0}
              showSizeChanger
              showTotal={(total) => `共 ${total} 条`}
              onChange={changeOrderNosPage}
              disabled={!orderNosPage}
            />
          </Space>
        </Space>
      </Card>
    </PageShell>
  );
}
