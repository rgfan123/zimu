/**
 * 商品与主数据 · 原料库存（票 06 落骨架，票 09 接真数据；spec `unified-business-frontend` D4、D6）。
 *
 * 数据来自后端 `GET /api/v1/raw-material-inventory/stock`（票 08 网关反代 yuanliaokc 实时结存）。
 * 模块开放即有取数路径；模块未开放时保持票 06 的 NOT_CONFIGURED 呈现，不发任何取数请求。
 *
 * **铁律：取数失败时一个结存数字都不显示**——不显示 0、不显示空表，只显示对应失败措辞。
 * 一张写着「暂无数据」的空表或一个 0 会被运营读成「原料没了」并据此下采购决定，那是把故障
 * 读成了事实。反过来，读取成功但 items 为空是合法业务状态（在库物料为 0），此时说
 * 「当前无在库物料」——「读到了没有」与「读不到」两种措辞必须可区分。
 *
 * kg 三列是 decimal-string（重量，可带小数，V99 整数纪律不适用）：原样展示字符串 + 单位，
 * 不做 parseFloat 再格式化——浮点会把 103.5 改写成 103.49999… 的精度谎言。
 *
 * 状态判据取外壳读到的那份后端模块开放清单（`useBusinessModuleStatus`），不在页面里另立标准
 * ——菜单里没有这个入口、页面上说未接通，必须是同一个事实的两种呈现。
 */

import { useState, type ReactNode } from 'react';
import { Alert, Button, Input, Tag, Typography } from 'antd';
import { LockOutlined, ReloadOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import DataTable from '@/components/DataTable';
import FilterBar from '@/components/FilterBar';
import PageShell from '@/components/PageShell';
import { rawMaterialInventoryApi } from '@/api/endpoints';
import type { RawMaterialStockItem, RawMaterialStockResponse } from '@/api/types';
import { useAsync } from '@/hooks/useAsync';
import { useBusinessModuleStatus } from '@/components/layout/useBusinessModules';
import { PageState } from '@/pages/shared/PageState';
import '@/pages/shared/adminSurface.css';
import {
  RAW_MATERIAL_CHECKING_HINT,
  RAW_MATERIAL_LOADING_HINT,
  RAW_MATERIAL_SCOPE_NOTE,
  rawMaterialEmptyStockText,
  rawMaterialInventoryNotice,
  rawMaterialInventoryState,
  rawMaterialReadFailureNotice,
  rawMaterialReadFailureReason,
  rawMaterialStockStatusPresentation,
  type RawMaterialNotice,
} from './rawMaterialInventoryView';

/** decimal-string 原样出屏 + 单位；不得 parseFloat（见文件头「精度谎言」）。 */
function kgCell(value: string) {
  return `${value} kg`;
}

/** tone → antd Tag 语义色；与 InventoryOverviewPage 的 observationTag 同一套映射。 */
function statusTag(status: string) {
  const presentation = rawMaterialStockStatusPresentation(status);
  const color = presentation.tone === 'neutral'
    ? undefined
    : presentation.tone === 'info'
      ? 'processing'
      : presentation.tone;
  return <Tag color={color}>{presentation.label}</Tag>;
}

const columns: ColumnsType<RawMaterialStockItem> = [
  { title: '物料编码', dataIndex: 'material_code', width: 110 },
  { title: '物料名称', dataIndex: 'material_name', width: 180 },
  { title: '类目', dataIndex: 'category', width: 96, render: (value: string | null) => value ?? '—' },
  { title: '规格', dataIndex: 'spec', width: 96, render: (value: string | null) => value ?? '—' },
  {
    title: '件数',
    dataIndex: 'piece_count',
    align: 'right',
    width: 84,
    // null = 上游没有件数口径，如实显示「—」；0 件是事实，不与「未提供」混写。
    render: (value: number | null) => (value === null ? '—' : String(value)),
  },
  { title: '结存 kg', dataIndex: 'current_kg', align: 'right', width: 112, render: kgCell },
  { title: '可用 kg', dataIndex: 'available_kg', align: 'right', width: 112, render: kgCell },
  { title: '冻结 kg', dataIndex: 'frozen_kg', align: 'right', width: 112, render: kgCell },
  { title: '批次数', dataIndex: 'batch_count', align: 'right', width: 84 },
  { title: '最早到期', dataIndex: 'earliest_expiry', width: 112, render: (value: string | null) => value ?? '—' },
  { title: '状态', dataIndex: 'status', width: 96, render: (value: string) => statusTag(value) },
];

function noticeAlert(notice: RawMaterialNotice, onRetry?: () => void) {
  return (
    <Alert
      type={notice.tone}
      showIcon
      message={notice.title}
      description={notice.description}
      action={onRetry ? (
        <Button size="small" icon={<ReloadOutlined />} onClick={onRetry}>重试</Button>
      ) : undefined}
    />
  );
}

export default function RawMaterialInventoryPage() {
  const module = useBusinessModuleStatus('raw-material-inventory');
  const moduleState = rawMaterialInventoryState(module);
  const [keywordDraft, setKeywordDraft] = useState('');
  const [keyword, setKeyword] = useState<string | undefined>(undefined);

  // 模块未开放时不发取数请求：入口没在清单里，取数必然只是把 NOT_CONFIGURED 再拿一遍。
  const readReady = moduleState.kind === 'ready';
  const stock = useAsync<RawMaterialStockResponse | null>(
    () => (readReady ? rawMaterialInventoryApi.stock({ keyword }) : Promise.resolve(null)),
    [readReady, keyword],
  );

  const moduleNotice = rawMaterialInventoryNotice(moduleState);
  const items = stock.data?.items ?? [];

  let content: ReactNode;
  if (moduleNotice) {
    // 模块未开放：保持票 06 的呈现——只有失败措辞，没有任何结存数字。
    content = noticeAlert(moduleNotice);
  } else if (!readReady) {
    content = <PageState state="loading" description={RAW_MATERIAL_CHECKING_HINT} />;
  } else if (stock.error) {
    // 铁律的落点：失败时整个数据区只剩失败措辞（连搜索框都不留，避免「再搜一次就有」的暗示）。
    content = noticeAlert(rawMaterialReadFailureNotice(rawMaterialReadFailureReason(stock.error)), stock.reload);
  } else {
    content = (
      <>
        <FilterBar
          actions={<Button icon={<ReloadOutlined />} onClick={stock.reload}>刷新</Button>}
        >
          <Input.Search
            aria-label="物料搜索"
            placeholder="物料编码 / 名称模糊搜索"
            allowClear
            value={keywordDraft}
            onChange={(event) => setKeywordDraft(event.target.value)}
            onSearch={(value) => setKeyword(value.trim() || undefined)}
            style={{ width: 280 }}
          />
        </FilterBar>
        {stock.loading ? (
          <PageState state="loading" description={RAW_MATERIAL_LOADING_HINT} />
        ) : items.length === 0 ? (
          // 读取成功 + 空清单 = 「读到了没有」：措辞与失败态可区分，允许说「无在库物料」。
          <PageState state="empty" description={rawMaterialEmptyStockText(keyword)} />
        ) : (
          <div className="admin-surface">
            <DataTable<RawMaterialStockItem>
              rowKey={(item) => item.material_id}
              columns={columns}
              dataSource={items}
              scroll={{ x: 1200 }}
              pagination={{
                defaultPageSize: 20,
                showSizeChanger: true,
                showTotal: (total) => `共 ${total} 条`,
              }}
            />
          </div>
        )}
      </>
    );
  }

  return (
    <div className="admin-page">
      <PageShell
        title="原料库存"
        description="原料、批次与结存的只读视图，事实来自原料库存系统（yuanliaokc）。"
        actions={<Tag bordered={false} icon={<LockOutlined />}>只读视图</Tag>}
      >
        {content}
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          {RAW_MATERIAL_SCOPE_NOTE}
        </Typography.Text>
      </PageShell>
    </div>
  );
}
