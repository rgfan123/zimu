/**
 * P3 运行记录（/agents/runs）：跨 Agent 的运行排障表（设计裁定二：独立页做真源）。
 * GET /api/v1/agent-runs —— 默认只返回 LIVE（不传 run_mode 即 LIVE）。
 *
 * - LIVE 与 PREVIEW 视觉隔离：默认只看 LIVE；切 PREVIEW 时 URL 带 `run_mode=PREVIEW`，
 *   页面渲染醒目标识（Alert + 行内模式 Tag），防止草稿试跑污染对线上行为的判断。
 * - 筛选（slug/outcome/run_mode/业务实体/时间范围）与分页（limit/offset）全部进 URL，
 *   刷新与分享可复现同一视图（runsFiltersFromParams / runsLocation 纯函数）。
 */

import { useMemo, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import {
  Alert,
  Button,
  Card,
  Col,
  DatePicker,
  Empty,
  Input,
  Row,
  Segmented,
  Select,
  Skeleton,
  Space,
  Statistic,
  Tag,
  Typography,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { ReloadOutlined, SearchOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import { errorMessage } from '@/api/client';
import { agentRunsApi, type AgentTokenUsageQuery } from '@/api/endpoints';
import type { RunListItem } from '@/api/agentTypes';
import { useAsync } from '@/hooks/useAsync';
import PageShell from '@/components/PageShell';
import FilterBar from '@/components/FilterBar';
import DataTable from '@/components/DataTable';
import {
  OUTCOME_OPTIONS,
  RUNS_DEFAULT_LIMIT,
  formatLatency,
  formatTime,
  formatTokens,
  parseTokenUsage,
  rangeToStartedParams,
  runModePresentation,
  runOutcomePresentation,
  runStatusPresentation,
  runsFiltersFromParams,
  runsSearchParams,
  type RunsFilters,
} from './agentPresentation';

const PREVIEW_BANNER =
  '正在查看 PREVIEW（草稿试跑）记录 —— 仅用于验证草稿行为，不计入对线上运行状态的判断。';

const TOKEN_SUMMARY_GROUP_LIMIT = 500;

function TokenUsageSummaryCard({ query }: { query: AgentTokenUsageQuery }) {
  const summary = useAsync(
    () =>
      agentRunsApi.tokenUsage({
        ...query,
        group_by: 'AGENT',
        limit: TOKEN_SUMMARY_GROUP_LIMIT,
      }),
    [
      query.slug,
      query.outcome,
      query.run_mode,
      query.business_entity_type,
      query.business_entity_id,
      query.started_from,
      query.started_to,
    ],
  );
  const reachedGroupLimit =
    summary.data !== null && summary.data.items.length >= TOKEN_SUMMARY_GROUP_LIMIT;

  return (
    <Card
      size="small"
      title="当前筛选历史 Token 汇总"
      aria-label="当前筛选 Token 汇总"
      role="region"
    >
      {summary.loading ? (
        <Skeleton active paragraph={{ rows: 1 }} />
      ) : summary.error ? (
        <Alert
          showIcon
          type="error"
          message="Token 汇总加载失败"
          description={errorMessage(summary.error)}
          action={(
            <Button size="small" icon={<ReloadOutlined />} onClick={summary.reload}>
              重试
            </Button>
          )}
        />
      ) : reachedGroupLimit ? (
        <Alert
          showIcon
          type="warning"
          message="筛选范围过大，无法确认完整 Token 汇总"
          description="汇总分组已达到接口的 500 组上限；请缩小 Agent 或时间范围后再查看，当前不展示可能少算的数字。"
        />
      ) : !summary.data || summary.data.totals.runs === 0 ? (
        <Empty
          image={Empty.PRESENTED_IMAGE_SIMPLE}
          description="当前筛选范围内暂无 Token 汇总"
        />
      ) : (
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          <Row gutter={[16, 12]} wrap>
            <Col flex="1 1 140px">
              <Statistic
                title="总 Token"
                value={summary.data.totals.total_tokens}
                formatter={() => formatTokens(summary.data?.totals.total_tokens)}
              />
            </Col>
            <Col flex="1 1 140px">
              <Statistic
                title="输入 Token"
                value={summary.data.totals.prompt_tokens}
                formatter={() => formatTokens(summary.data?.totals.prompt_tokens)}
              />
            </Col>
            <Col flex="1 1 140px">
              <Statistic
                title="输出 Token"
                value={summary.data.totals.completion_tokens}
                formatter={() => formatTokens(summary.data?.totals.completion_tokens)}
              />
            </Col>
            <Col flex="1 1 140px">
              <Statistic
                title="模型调用次数"
                value={summary.data.totals.model_calls}
                formatter={() => formatTokens(summary.data?.totals.model_calls)}
                suffix="次"
              />
            </Col>
            <Col flex="1 1 140px">
              <Statistic
                title="运行次数"
                value={summary.data.totals.runs}
                formatter={() => formatTokens(summary.data?.totals.runs)}
                suffix="次"
              />
            </Col>
          </Row>
          {summary.data.totals.runs_without_token_usage > 0 ? (
            <Alert
              showIcon
              type="warning"
              message={`${summary.data.totals.runs_without_token_usage} 次运行未记录 token`}
              description="以上 Token 只汇总已计量运行，不能当作全部运行的完整消耗。"
            />
          ) : null}
        </Space>
      )}
    </Card>
  );
}

export default function AgentRunsPage() {
  const [searchParams, setSearchParams] = useSearchParams();

  const appliedFilters = runsFiltersFromParams(searchParams);
  const [initialFilters] = useState(appliedFilters);
  const [draftFilters, setDraftFilters] = useState<RunsFilters>(initialFilters);
  const [summaryRefreshVersion, setSummaryRefreshVersion] = useState(0);

  const page = useMemo(() => {
    const rawLimit = Number(searchParams.get('limit'));
    const rawOffset = Number(searchParams.get('offset'));
    return {
      limit:
        Number.isSafeInteger(rawLimit) && rawLimit >= 1 && rawLimit <= 500
          ? rawLimit
          : RUNS_DEFAULT_LIMIT,
      offset: Number.isSafeInteger(rawOffset) && rawOffset >= 0 ? rawOffset : 0,
    };
  }, [searchParams]);

  // 列表与汇总只从这一份已应用筛选构造请求，避免同屏口径再次漂移。
  const appliedQuery: AgentTokenUsageQuery = {
    slug: appliedFilters.slug,
    outcome: appliedFilters.outcome,
    run_mode: appliedFilters.runMode,
    business_entity_type: appliedFilters.businessEntityType,
    business_entity_id: appliedFilters.businessEntityId,
    started_from: appliedFilters.startedFrom,
    started_to: appliedFilters.startedTo,
  };

  const runs = useAsync(
    () =>
      agentRunsApi.list({
        ...appliedQuery,
        limit: page.limit,
        offset: page.offset,
      }),
    [
      appliedFilters.slug,
      appliedFilters.outcome,
      appliedFilters.runMode,
      appliedFilters.businessEntityType,
      appliedFilters.businessEntityId,
      appliedFilters.startedFrom,
      appliedFilters.startedTo,
      page.limit,
      page.offset,
    ],
  );

  const navigateTo = (filters: RunsFilters, nextPage: { limit: number; offset: number }) => {
    setDraftFilters(filters);
    setSearchParams(runsSearchParams(filters, nextPage));
  };

  const applyDraft = () => {
    setDraftFilters(draftFilters);
    setSearchParams(runsSearchParams(draftFilters, { limit: page.limit, offset: 0 }));
  };

  const resetFilters = () => {
    setDraftFilters({});
    setSearchParams(new URLSearchParams());
  };

  const isPreview = appliedFilters.runMode === 'PREVIEW';
  const summaryViewKey = `${runsSearchParams(
    appliedFilters,
    { limit: RUNS_DEFAULT_LIMIT, offset: 0 },
  ).toString()}::${summaryRefreshVersion}`;

  const columns: ColumnsType<RunListItem> = [
    {
      title: 'Run ID',
      dataIndex: 'run_id',
      width: 210,
      render: (value: string) => (
        <Link to={`/agents/runs/${value}`} style={{ fontFamily: 'monospace', fontSize: 12 }}>
          {value}
        </Link>
      ),
    },
    {
      title: 'Agent',
      key: 'agent',
      width: 170,
      render: (_, item) => (
        <Space direction="vertical" size={0}>
          <Link to={`/agents/${item.agent_slug}`} style={{ fontFamily: 'monospace', fontSize: 12 }}>
            {item.agent_slug}
          </Link>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            v{item.agent_version}
          </Typography.Text>
        </Space>
      ),
    },
    {
      title: '模式',
      dataIndex: 'run_mode',
      width: 110,
      render: (value: RunListItem['run_mode']) => {
        const presentation = runModePresentation(value);
        return (
          <Tag color={presentation.tone} style={{ fontFamily: 'monospace' }}>
            {presentation.label}
          </Tag>
        );
      },
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 90,
      render: (value: RunListItem['status']) => {
        const presentation = runStatusPresentation(value);
        return <Tag color={presentation.tone}>{presentation.label}</Tag>;
      },
    },
    {
      title: '结果',
      dataIndex: 'outcome',
      width: 90,
      render: (value: RunListItem['outcome']) => {
        const presentation = runOutcomePresentation(value);
        return <Tag color={presentation.tone}>{presentation.label}</Tag>;
      },
    },
    {
      title: '耗时',
      dataIndex: 'latency_ms',
      width: 100,
      render: (value: number | null) => formatLatency(value),
    },
    {
      title: 'Token',
      dataIndex: 'token_usage',
      width: 190,
      render: (value: unknown) => {
        const usage = parseTokenUsage(value);
        if (!usage) return <Typography.Text type="secondary">—</Typography.Text>;
        return (
          <Space direction="vertical" size={0}>
            <Typography.Text strong style={{ fontFamily: 'monospace' }}>
              {formatTokens(usage.totalTokens)}
            </Typography.Text>
            <Typography.Text type="secondary" style={{ fontFamily: 'monospace', fontSize: 12 }}>
              入 {formatTokens(usage.promptTokens)} / 出 {formatTokens(usage.completionTokens)}
            </Typography.Text>
            {usage.modelCalls !== null && usage.modelCalls > 1 ? (
              <Tag bordered={false} style={{ fontSize: 11, marginInlineEnd: 0 }}>
                {formatTokens(usage.modelCalls)} 次调用
              </Tag>
            ) : null}
          </Space>
        );
      },
    },
    {
      title: '关联业务实体',
      key: 'business_entity',
      width: 160,
      render: (_, item) =>
        item.business_entity_type ? (
          <Space direction="vertical" size={0}>
            <Typography.Text style={{ fontSize: 12 }}>{item.business_entity_type}</Typography.Text>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {item.business_entity_id ?? '—'}
            </Typography.Text>
          </Space>
        ) : (
          <Typography.Text type="secondary">—</Typography.Text>
        ),
    },
    {
      title: '意图',
      dataIndex: 'intent',
      width: 160,
      ellipsis: true,
      render: (value: string | null) => value ?? '—',
    },
    {
      title: '开始时间',
      dataIndex: 'started_at',
      width: 160,
      render: (value: string | null) => formatTime(value),
    },
  ];

  return (
    <PageShell
      title="运行记录"
      description="跨 Agent 的运行排障表：默认只看 LIVE 线上运行，PREVIEW 草稿试跑需显式切换。"
    >
      <FilterBar
        actions={
          <Space wrap>
            <Button type="primary" icon={<SearchOutlined />} onClick={applyDraft}>
              查询
            </Button>
            <Button onClick={resetFilters}>重置</Button>
            <Button
              icon={<ReloadOutlined />}
              onClick={() => {
                runs.reload();
                setSummaryRefreshVersion((version) => version + 1);
              }}
            >
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
          onChange={(value) => {
            const next: RunsFilters = {
              ...draftFilters,
              runMode: value === 'PREVIEW' ? ('PREVIEW' as const) : undefined,
            };
            setDraftFilters(next);
            setSearchParams(runsSearchParams(next, { limit: page.limit, offset: 0 }));
          }}
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
          onPressEnter={applyDraft}
        />
        <Select
          aria-label="结果筛选"
          placeholder="结果"
          allowClear
          style={{ width: 120 }}
          value={draftFilters.outcome}
          options={OUTCOME_OPTIONS}
          onChange={(value) =>
            setDraftFilters((current) => ({ ...current, outcome: value ?? undefined }))
          }
        />
        <Input
          aria-label="业务实体类型"
          placeholder="业务实体类型"
          allowClear
          style={{ width: 150 }}
          value={draftFilters.businessEntityType ?? ''}
          onChange={(event) =>
            setDraftFilters((current) => ({
              ...current,
              businessEntityType: event.target.value || undefined,
            }))
          }
        />
        <Input
          aria-label="业务实体 ID"
          placeholder="业务实体 ID"
          allowClear
          style={{ width: 150 }}
          value={draftFilters.businessEntityId ?? ''}
          onChange={(event) =>
            setDraftFilters((current) => ({
              ...current,
              businessEntityId: event.target.value || undefined,
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

      <TokenUsageSummaryCard key={summaryViewKey} query={appliedQuery} />

      <DataTable<RunListItem>
        rowClassName={() => (isPreview ? 'agent-run-preview-row' : '')}
        rowKey="run_id"
        columns={columns}
        dataSource={runs.data?.items ?? []}
        loading={runs.loading}
        error={runs.error}
        onRetry={runs.reload}
        errorTitle="运行记录加载失败"
        emptyText="当前筛选范围内暂无运行记录"
        pagination={{
          current: page.offset / page.limit + 1,
          pageSize: page.limit,
          total: runs.data?.total ?? 0,
          showSizeChanger: true,
          showTotal: (total) => `共 ${total} 条`,
          onChange: (nextPage, nextSize) => {
            navigateTo(appliedFilters, {
              limit: nextSize,
              offset: (nextPage - 1) * nextSize,
            });
          },
        }}
      />
    </PageShell>
  );
}
