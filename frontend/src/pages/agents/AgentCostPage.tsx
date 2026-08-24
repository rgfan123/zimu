/**
 * P7 消耗看板（/agents/cost，129 票）：把逐次运行的 token 与耗时聚合成
 * 「按 Agent / 按业务日 / 按业务实体」的成本视图，用来在账单来之前发现跑飞。
 *
 * 三条刻意的呈现约束：
 * - **不做费用换算**。只出 token 计数；各模型单价会变且属计费口径，界面上编一个
 *   金额出来，一定会有人拿它当账。
 * - **未计量必须说出来**。求和只覆盖有计量的运行，有未计量运行时汇总是下界而非
 *   全量，页面顶部显式告警，不让读者把下界当全量。
 * - **默认只看 LIVE**。PREVIEW 是草稿试跑，混进来「线上花了多少」就不成立了。
 */

import { useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { Alert, Button, Col, DatePicker, Input, Row, Segmented, Space, Tag, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { ReloadOutlined, SearchOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import { agentRunsApi } from '@/api/endpoints';
import type { TokenUsageSummaryItem } from '@/api/agentTypes';
import { useAsync } from '@/hooks/useAsync';
import PageShell from '@/components/PageShell';
import FilterBar from '@/components/FilterBar';
import DataTable from '@/components/DataTable';
import KpiCard from '@/components/KpiCard';
import {
  COST_DEFAULT_GROUP_BY,
  GROUP_BY_OPTIONS,
  averageOrNull,
  costFiltersFromParams,
  costSearchParams,
  coverageNote,
  formatDuration,
  formatTokens,
  measuredRuns,
  rangeToStartedParams,
  type CostFilters,
} from './agentPresentation';

const PREVIEW_BANNER =
  '正在查看 PREVIEW（草稿试跑）消耗 —— 草稿试跑不代表线上成本，切回 LIVE 才是「线上花了多少」。';

const NO_PRICING_NOTE =
  '只出 token 计数，不做费用换算：各模型单价会变、属计费口径，不进业务库。';

/** 分组键为空串的含义随维度而变——空白会被读成「加载失败」，必须写出来。 */
function groupLabel(groupKey: string, groupBy: CostFilters['groupBy']): string {
  if (groupKey) return groupKey;
  return groupBy === 'BUSINESS_ENTITY_TYPE' ? '（无关联业务实体）' : '（未分组）';
}

export default function AgentCostPage() {
  const [searchParams, setSearchParams] = useSearchParams();

  const appliedFilters = costFiltersFromParams(searchParams);
  const [initialFilters] = useState(appliedFilters);
  const [draftFilters, setDraftFilters] = useState<CostFilters>(initialFilters);

  const summary = useAsync(
    () =>
      agentRunsApi.tokenUsage({
        slug: appliedFilters.slug,
        run_mode: appliedFilters.runMode,
        business_entity_type: appliedFilters.businessEntityType,
        started_from: appliedFilters.startedFrom,
        started_to: appliedFilters.startedTo,
        group_by: appliedFilters.groupBy,
      }),
    [
      appliedFilters.slug,
      appliedFilters.runMode,
      appliedFilters.businessEntityType,
      appliedFilters.startedFrom,
      appliedFilters.startedTo,
      appliedFilters.groupBy,
    ],
  );

  const applyFilters = (next: CostFilters) => {
    setDraftFilters(next);
    setSearchParams(costSearchParams(next));
  };

  const resetFilters = () => {
    const next: CostFilters = { groupBy: COST_DEFAULT_GROUP_BY };
    setDraftFilters(next);
    setSearchParams(new URLSearchParams());
  };

  const isPreview = appliedFilters.runMode === 'PREVIEW';
  const totals = summary.data?.totals ?? null;
  const coverage = totals ? coverageNote(totals) : null;

  const columns: ColumnsType<TokenUsageSummaryItem> = [
    {
      title: GROUP_BY_OPTIONS.find((o) => o.value === appliedFilters.groupBy)?.label ?? '分组',
      dataIndex: 'group_key',
      width: 190,
      fixed: 'left',
      render: (value: string) =>
        appliedFilters.groupBy === 'AGENT' && value ? (
          <Link to={`/agents/${value}`} style={{ fontFamily: 'monospace', fontSize: 12 }}>
            {value}
          </Link>
        ) : (
          <Typography.Text style={{ fontFamily: 'monospace', fontSize: 12 }}>
            {groupLabel(value, appliedFilters.groupBy)}
          </Typography.Text>
        ),
    },
    {
      title: '运行数',
      key: 'runs',
      width: 130,
      render: (_, item) => (
        <Space direction="vertical" size={0}>
          <Typography.Text>{item.runs}</Typography.Text>
          {item.failed_runs > 0 ? (
            <Typography.Text type="danger" style={{ fontSize: 12 }}>
              失败 {item.failed_runs}
            </Typography.Text>
          ) : null}
        </Space>
      ),
    },
    {
      title: '已计量',
      key: 'measured',
      width: 130,
      render: (_, item) => {
        const measured = measuredRuns(item);
        // 未计量 > 0 时逐行标出来：某个 Agent 大面积没计量，汇总就不能拿来比大小
        return item.runs_without_token_usage > 0 ? (
          <Space direction="vertical" size={0}>
            <Typography.Text>{measured}</Typography.Text>
            <Tag color="warning" style={{ fontSize: 11, marginInlineEnd: 0 }}>
              {item.runs_without_token_usage} 次无计量
            </Tag>
          </Space>
        ) : (
          <Typography.Text type="secondary">{measured}</Typography.Text>
        );
      },
    },
    {
      title: '总 Token',
      dataIndex: 'total_tokens',
      width: 120,
      sorter: (a, b) => a.total_tokens - b.total_tokens,
      render: (value: number) => (
        <Typography.Text strong style={{ fontFamily: 'monospace' }}>
          {formatTokens(value)}
        </Typography.Text>
      ),
    },
    {
      title: '输入 / 输出',
      key: 'io_tokens',
      width: 160,
      render: (_, item) => (
        <Typography.Text style={{ fontFamily: 'monospace', fontSize: 12 }}>
          {formatTokens(item.prompt_tokens)} / {formatTokens(item.completion_tokens)}
        </Typography.Text>
      ),
    },
    {
      title: '每次运行均耗',
      key: 'avg_per_run',
      width: 130,
      render: (_, item) => formatTokens(averageOrNull(item.total_tokens, measuredRuns(item))),
    },
    {
      title: '每轮均耗',
      key: 'avg_per_call',
      width: 120,
      render: (_, item) => formatTokens(averageOrNull(item.total_tokens, item.model_calls)),
    },
    {
      title: '模型轮数',
      dataIndex: 'model_calls',
      width: 110,
      render: (value: number) => (
        <Typography.Text type="secondary" style={{ fontFamily: 'monospace' }}>
          {formatTokens(value)}
        </Typography.Text>
      ),
    },
    {
      title: '单次峰值',
      dataIndex: 'max_run_total_tokens',
      width: 120,
      sorter: (a, b) => (a.max_run_total_tokens ?? 0) - (b.max_run_total_tokens ?? 0),
      render: (value: number | null) => formatTokens(value),
    },
    {
      title: '总耗时',
      dataIndex: 'total_latency_ms',
      width: 130,
      sorter: (a, b) => a.total_latency_ms - b.total_latency_ms,
      render: (value: number) => formatDuration(value),
    },
    {
      title: '单次最慢',
      dataIndex: 'max_run_latency_ms',
      width: 120,
      render: (value: number | null) => formatDuration(value),
    },
    {
      title: '超阈值',
      dataIndex: 'over_threshold_runs',
      width: 100,
      render: (value: number) =>
        value > 0 ? (
          <Tag color="error">{value} 次</Tag>
        ) : (
          <Typography.Text type="secondary">0</Typography.Text>
        ),
    },
  ];

  return (
    <PageShell
      title="消耗看板"
      description="按 Agent / 业务日 / 业务实体汇总 token 与耗时。默认只看 LIVE 线上运行。"
    >
      <FilterBar
        actions={
          <Space wrap>
            <Button type="primary" icon={<SearchOutlined />} onClick={() => applyFilters(draftFilters)}>
              查询
            </Button>
            <Button onClick={resetFilters}>重置</Button>
            <Button icon={<ReloadOutlined />} onClick={summary.reload}>
              刷新
            </Button>
          </Space>
        }
      >
        <Segmented
          aria-label="运行模式"
          value={isPreview ? 'PREVIEW' : 'LIVE'}
          options={[
            { value: 'LIVE', label: 'LIVE · 线上' },
            { value: 'PREVIEW', label: 'PREVIEW · 草稿试跑' },
          ]}
          onChange={(value) =>
            applyFilters({
              ...draftFilters,
              runMode: value === 'PREVIEW' ? ('PREVIEW' as const) : undefined,
            })
          }
        />
        <Segmented
          aria-label="分组维度"
          value={draftFilters.groupBy}
          options={GROUP_BY_OPTIONS}
          onChange={(value) =>
            applyFilters({ ...draftFilters, groupBy: value as CostFilters['groupBy'] })
          }
        />
        <Input
          aria-label="Agent slug"
          placeholder="Agent slug"
          allowClear
          style={{ width: 170 }}
          value={draftFilters.slug ?? ''}
          onChange={(event) =>
            setDraftFilters((current) => ({ ...current, slug: event.target.value || undefined }))
          }
          onPressEnter={() => applyFilters(draftFilters)}
        />
        <Input
          aria-label="业务实体类型"
          placeholder="业务实体类型"
          allowClear
          style={{ width: 160 }}
          value={draftFilters.businessEntityType ?? ''}
          onChange={(event) =>
            setDraftFilters((current) => ({
              ...current,
              businessEntityType: event.target.value || undefined,
            }))
          }
        />
        <DatePicker.RangePicker
          showTime
          aria-label="开始时间范围"
          style={{ width: 360 }}
          value={
            draftFilters.startedFrom && draftFilters.startedTo
              ? [dayjs(draftFilters.startedFrom), dayjs(draftFilters.startedTo)]
              : null
          }
          onChange={(values) => {
            const range = values as [dayjs.Dayjs | null, dayjs.Dayjs | null] | null;
            const started = rangeToStartedParams(range);
            setDraftFilters((current) => ({
              ...current,
              startedFrom: started.startedFrom,
              startedTo: started.startedTo,
            }));
          }}
        />
      </FilterBar>

      {isPreview ? <Alert showIcon type="warning" message={PREVIEW_BANNER} /> : null}

      {coverage?.partial ? (
        <Alert
          showIcon
          type="warning"
          message="本页求和是下界，不是全量"
          description={coverage.label}
        />
      ) : null}

      <Row gutter={[16, 16]}>
        <Col xs={24} sm={12} xl={6}>
          <KpiCard
            title="运行数"
            value={totals?.runs ?? null}
            unit="次"
            loading={summary.loading}
            tooltip={coverage?.label}
          />
        </Col>
        <Col xs={24} sm={12} xl={6}>
          <KpiCard
            title="总 Token"
            value={totals ? formatTokens(totals.total_tokens) : null}
            loading={summary.loading}
            tooltip={NO_PRICING_NOTE}
          />
        </Col>
        <Col xs={24} sm={12} xl={6}>
          <KpiCard
            title="总耗时"
            value={totals ? formatDuration(totals.total_latency_ms) : null}
            loading={summary.loading}
            tooltip="含失败运行——失败也占用了时间与算力。"
          />
        </Col>
        <Col xs={24} sm={12} xl={6}>
          <KpiCard
            title="单次超阈值"
            value={totals?.over_threshold_runs ?? null}
            unit="次"
            loading={summary.loading}
            tooltip="阈值由 app.agent.observability.token-warn-threshold 配置，默认关闭（恒为 0）。"
          />
        </Col>
      </Row>

      <Typography.Paragraph type="secondary" style={{ fontSize: 12, marginBottom: 0 }}>
        {NO_PRICING_NOTE}
      </Typography.Paragraph>

      <DataTable<TokenUsageSummaryItem>
        rowKey="group_key"
        columns={columns}
        dataSource={summary.data?.items ?? []}
        loading={summary.loading}
        error={summary.error}
        onRetry={summary.reload}
        errorTitle="消耗汇总加载失败"
        emptyText="当前筛选范围内暂无运行记录"
        scroll={{ x: 1600 }}
        pagination={false}
      />
    </PageShell>
  );
}
