/**
 * 部门协同 · 采购比价（01 票：不可比候选降级展示）。
 * 调用采购比价 Agent（POST /api/v1/procurement-price-agent/compare，只读）：
 * 输入 procurement_ticket_id 或 sku_id（可带数量），结果分两组展示——
 * 可比候选（参与推荐）与被剔除候选（降级展示、绝不静默消失，理由标签 + 可读说明可见），
 * 让采购员既能快速看到有效对比，也能判断 agent 是否把对的选项扔了。
 */

import { useState } from 'react';
import {
  Alert,
  Button,
  Descriptions,
  Empty,
  Input,
  Space,
  Tag,
  Typography,
} from 'antd';
import { SearchOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { procurementPriceAgentApi } from '@/api/endpoints';
import type {
  ProcurementPriceCandidate,
  ProcurementPriceExcludedCandidate,
  ProcurementPriceExclusionReason,
  ProcurementPriceRunResult,
} from '@/api/types';
import DataTable from '@/components/DataTable';
import FilterBar from '@/components/FilterBar';
import PageShell from '@/components/PageShell';
import { AdminEmpty, AdminFailureAlert, AdminLoading } from '@/pages/shared/AdminVisualComponents';
import { adminPageState } from '@/pages/shared/adminVisual';
import '@/pages/shared/adminSurface.css';

/** 剔除理由标签的可读文案与颜色（01 票三规则并集）。 */
const EXCLUSION_PRESENTATION: Record<ProcurementPriceExclusionReason, { label: string; color: string }> = {
  price_outlier: { label: '价格离群', color: 'orange' },
  price_missing: { label: '价格缺失', color: 'red' },
  mapping_stale: { label: '映射失效', color: 'purple' },
};

const PRICE_BASIS_LABEL: Record<string, string> = {
  sku_commercial_price: 'SKU 主数据进货价',
  provider_sku: '履约方映射价格',
};

function priceBasisLabel(basis?: string | null): string {
  return basis ? (PRICE_BASIS_LABEL[basis] ?? basis) : '—';
}

function formatDecimal(v?: string | number | null): string {
  if (v === undefined || v === null || v === '') return '—';
  const n = typeof v === 'number' ? v : parseFloat(v);
  return Number.isFinite(n) ? n.toLocaleString('zh-CN') : String(v);
}

export default function ProcurementPriceComparePage() {
  const [skuId, setSkuId] = useState('');
  const [ticketId, setTicketId] = useState('');
  const [quantity, setQuantity] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [result, setResult] = useState<ProcurementPriceRunResult | null>(null);

  const canCompare = skuId.trim() !== '' || ticketId.trim() !== '';

  const compare = async () => {
    setLoading(true);
    setError(null);
    setResult(null);
    try {
      setResult(await procurementPriceAgentApi.compare({
        sku_id: skuId.trim() || undefined,
        procurement_ticket_id: ticketId.trim() || undefined,
        quantity: quantity.trim() || undefined,
      }));
    } catch (cause) {
      setError(cause);
    } finally {
      setLoading(false);
    }
  };

  const state = adminPageState(loading, error, Boolean(result));
  const recommendation = result?.recommendation ?? null;

  const comparableColumns: ColumnsType<ProcurementPriceCandidate> = [
    { title: '履约方', dataIndex: 'provider_code', width: 140, render: (v: string) => <span style={{ fontVariantNumeric: 'tabular-nums' }}>{v}</span> },
    { title: '价格（元）', dataIndex: 'price', width: 120, align: 'right', render: formatDecimal },
    { title: '价格依据', dataIndex: 'price_basis', width: 160, render: priceBasisLabel },
    { title: '说明', dataIndex: 'note', render: (v?: string | null) => v || '—' },
  ];

  const excludedColumns: ColumnsType<ProcurementPriceExcludedCandidate> = [
    { title: '履约方', dataIndex: 'provider_code', width: 140, render: (v: string) => <span style={{ fontVariantNumeric: 'tabular-nums' }}>{v}</span> },
    { title: '价格（元）', dataIndex: 'price', width: 120, align: 'right', render: formatDecimal },
    { title: '价格依据', dataIndex: 'price_basis', width: 160, render: priceBasisLabel },
    {
      title: '剔除理由',
      key: 'exclusion_reason',
      width: 130,
      render: (_, candidate) => {
        const presentation = EXCLUSION_PRESENTATION[candidate.exclusion_reason];
        return <Tag color={presentation?.color ?? 'default'}>{presentation?.label ?? candidate.exclusion_reason}</Tag>;
      },
    },
    { title: '理由说明', dataIndex: 'exclusion_reason_detail', render: (v?: string | null) => v || '—' },
  ];

  return (
    <PageShell
      title="采购比价"
      description="按采购工单或 SKU 发起比价：被剔除的不可比候选（价格离群 / 价格缺失 / 映射失效）降级展示并说明理由，绝不静默消失。"
    >
      <FilterBar
        actions={
          <Button
            type="primary"
            icon={<SearchOutlined />}
            loading={loading}
            disabled={!canCompare}
            onClick={compare}
          >
            开始比价
          </Button>
        }
      >
        <Space size={8}>
          <Typography.Text type="secondary" style={{ fontSize: 13 }}>SKU 编码</Typography.Text>
          <Input
            aria-label="SKU 编码"
            placeholder="如 SKU-1001"
            style={{ width: 180 }}
            value={skuId}
            onChange={(event) => setSkuId(event.target.value)}
            onPressEnter={compare}
          />
        </Space>
        <Space size={8}>
          <Typography.Text type="secondary" style={{ fontSize: 13 }}>采购工单</Typography.Text>
          <Input
            aria-label="采购工单 ID"
            placeholder="如 9001"
            style={{ width: 140 }}
            value={ticketId}
            onChange={(event) => setTicketId(event.target.value)}
            onPressEnter={compare}
          />
        </Space>
        <Space size={8}>
          <Typography.Text type="secondary" style={{ fontSize: 13 }}>数量</Typography.Text>
          <Input
            aria-label="数量（可选）"
            placeholder="可选"
            style={{ width: 110 }}
            value={quantity}
            onChange={(event) => setQuantity(event.target.value)}
            onPressEnter={compare}
          />
        </Space>
      </FilterBar>

      {state === 'loading' ? <AdminLoading description="正在运行采购比价…" /> : null}

      {error ? (
        <AdminFailureAlert error={error} title="比价运行失败" onRetry={compare} />
      ) : null}

      {result?.error ? (
        <Alert
          type="error"
          showIcon
          style={{ marginBottom: 16 }}
          message="比价未能完成"
          description={`运行失败（${result.error}）：未配置模型或服务不可用时按 fail-closed 处理，请稍后重试或联系管理员。`}
        />
      ) : null}

      {recommendation ? (
        <Space direction="vertical" size={16} style={{ width: '100%' }}>
          {recommendation.requires_human ? (
            <Alert
              type="warning"
              showIcon
              message="需要人工介入"
              description={
                <span>
                  无可比候选或信息不全（{recommendation.missing_fields.join('、') || '缺失字段未标注'}），
                  系统不硬推候选，请人工核对下方事实摘要。
                </span>
              }
            />
          ) : null}

          <div className="admin-surface">
            <Descriptions
              size="small"
              column={{ xs: 1, md: 3 }}
              items={[
                { key: 'sku', label: '目标 SKU', children: recommendation.target_sku || '—' },
                { key: 'qty', label: '数量', children: formatDecimal(recommendation.requested_quantity) },
                {
                  key: 'inventory',
                  label: '库存',
                  children: recommendation.inventory
                    ? `可用 ${formatDecimal(recommendation.inventory.available)} / 缺口 ${formatDecimal(recommendation.inventory.shortage)}`
                    : '—',
                },
                { key: 'conf', label: '置信度', children: `${Math.round(recommendation.confidence * 100)}%` },
                {
                  key: 'rec',
                  label: '推荐',
                  children: recommendation.recommendation
                    ? `${recommendation.recommendation.provider_code} · ${recommendation.recommendation.reason}`
                    : '—',
                },
                { key: 'pv', label: '提示词版本', children: result?.prompt_version || '—' },
              ]}
            />
          </div>

          <div className="admin-surface">
            <Typography.Text className="admin-detail-section__heading" strong>
              可比候选（{recommendation.candidates.length}）
            </Typography.Text>
            <DataTable<ProcurementPriceCandidate>
              rowKey={(candidate) => candidate.provider_code}
              size="small"
              style={{ marginTop: 8 }}
              pagination={false}
              columns={comparableColumns}
              dataSource={recommendation.candidates}
              emptyText={<AdminEmpty description="无可比候选" />}
            />
          </div>

          <div className="admin-surface">
            <Typography.Text className="admin-detail-section__heading" strong>
              被剔除候选（{recommendation.excluded_candidates.length} · 降级展示，不是删除）
            </Typography.Text>
            <DataTable<ProcurementPriceExcludedCandidate>
              rowKey={(candidate) => candidate.provider_code}
              size="small"
              style={{ marginTop: 8 }}
              pagination={false}
              columns={excludedColumns}
              dataSource={recommendation.excluded_candidates}
              emptyText={<AdminEmpty description="无被剔除候选" />}
            />
          </div>
        </Space>
      ) : null}

      {!loading && !error && !result ? (
        <div className="admin-surface" style={{ padding: 40 }}>
          <Empty description="输入 SKU 编码或采购工单 ID 后开始比价" />
        </div>
      ) : null}
    </PageShell>
  );
}
