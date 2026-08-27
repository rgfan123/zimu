/**
 * P1 Agent 列表（/agents）：一行一个 slug。
 * GET /api/v1/agents —— 一次拿全聚合（当前生效版本/待确认草稿数/近 7 日 LIVE 统计/工具白名单）。
 *
 * - state 由服务端投影，前端只渲染不推导（statePresentation 三值直映）。
 * - 筛选（state / 有待确认草稿 / 写权限）进 URL，刷新与分享可复现；列表端点无查询参数，
 *   筛选在客户端做（filterAgentItems）。
 * - 「新建 Agent」为 P6 对话式创建入口，本期后置：按钮禁用并注明「即将开放」，不跳 404。
 */

import { useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { Alert, Badge, Button, Select, Space, Tag, Tooltip, Typography, theme } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { ReloadOutlined, SearchOutlined } from '@ant-design/icons';
import { agentsApi } from '@/api/endpoints';
import type { AgentListItem } from '@/api/agentTypes';
import { useAsync } from '@/hooks/useAsync';
import PageShell from '@/components/PageShell';
import FilterBar from '@/components/FilterBar';
import DataTable from '@/components/DataTable';
import {
  agentListFiltersFromParams,
  agentListSearchParams,
  filterAgentItems,
  sevenDayPresentation,
  attentionSummary,
  statePresentation,
  toolsSummary,
} from './agentPresentation';

const STATE_OPTIONS: Array<{ value: string; label: string }> = [
  { value: 'RUNNING', label: '运行中' },
  { value: 'DISABLED', label: '已停用' },
  { value: 'NO_ACTIVE_VERSION', label: '无生效版本' },
];

/**
 * 运行状态用点 + 文字，不用 Tag 色块：列表里多数行都是「运行中」，
 * 色块会把正常态渲染成噪音，反而让真正需要注意的「无生效版本」淹没。
 */
function StateDot({ tone, label }: { tone: 'success' | 'default' | 'warning'; label: string }) {
  const { token } = theme.useToken();
  const color =
    tone === 'success' ? token.colorSuccess : tone === 'warning' ? token.colorWarning : token.colorTextTertiary;
  return (
    <Space size={6} align="center">
      <span
        aria-hidden="true"
        style={{ width: 7, height: 7, borderRadius: '50%', background: color, display: 'inline-block' }}
      />
      <span style={{ color }}>{label}</span>
    </Space>
  );
}

export default function AgentsListPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const [initialFilters] = useState(() => agentListFiltersFromParams(searchParams));
  const [draftFilters, setDraftFilters] = useState(initialFilters);

  const { token } = theme.useToken();
  const list = useAsync(() => agentsApi.list(), []);

  const attention = attentionSummary(list.data?.items ?? []);
  const appliedFilters = agentListFiltersFromParams(searchParams);
  const items = filterAgentItems(list.data?.items ?? [], appliedFilters);

  const applyFilters = (next: typeof draftFilters) => {
    setDraftFilters(next);
    setSearchParams(agentListSearchParams(next));
  };

  const resetFilters = () => {
    setDraftFilters({});
    setSearchParams(new URLSearchParams());
  };

  const columns: ColumnsType<AgentListItem> = [
    {
      title: 'Agent',
      key: 'agent',
      width: 220,
      render: (_, item) => (
        <Space direction="vertical" size={0}>
          <Space size={6}>
            <Link to={`/agents/${item.slug}`}>{item.name}</Link>
            {item.allow_write ? (
              <Tooltip title="仅 meta-agent 持有写工具权限；界面不提供修改入口">
                <Tag color="volcano" style={{ marginInlineEnd: 0, fontSize: 11, lineHeight: '16px' }}>
                  可写
                </Tag>
              </Tooltip>
            ) : null}
          </Space>
          <Typography.Text type="secondary" style={{ fontFamily: 'monospace', fontSize: 12 }}>
            {item.slug}
          </Typography.Text>
        </Space>
      ),
    },
    {
      title: '运行状态',
      dataIndex: 'state',
      width: 130,
      render: (value: AgentListItem['state']) => {
        const presentation = statePresentation(value);
        return <StateDot tone={presentation.tone} label={presentation.label} />;
      },
    },
    {
      title: '当前版本',
      dataIndex: 'current_version',
      width: 110,
      render: (value: number | null, item) =>
        value === null ? (
          <Typography.Text type="secondary">—</Typography.Text>
        ) : (
          <Link to={`/agents/${item.slug}?tab=versions`} style={{ fontFamily: 'monospace' }}>
            v{value}
          </Link>
        ),
    },
    {
      title: '待确认草稿',
      dataIndex: 'draft_count',
      width: 130,
      render: (value: number, item) =>
        value > 0 ? (
          <Link to={`/agents/${item.slug}?tab=versions`}>
            <Badge count={value} style={{ backgroundColor: token.colorPrimary }} />
          </Link>
        ) : (
          <Typography.Text type="secondary">—</Typography.Text>
        ),
    },
    {
      title: '工具',
      key: 'tools',
      width: 120,
      render: (_, item) => {
        const summary = toolsSummary(item.tools);
        if (!summary.label) {
          return <Typography.Text type="secondary">无工具</Typography.Text>;
        }
        return (
          <Tooltip
            title={
              <Space direction="vertical" size={2}>
                {item.tools.map((tool) => (
                  <Space key={tool.name} size={4}>
                    <Typography.Text style={{ fontFamily: 'monospace', color: 'rgba(255,255,255,0.9)' }}>
                      {tool.name}
                    </Typography.Text>
                    {!tool.registered ? (
                      <Tag color="default">未注册</Tag>
                    ) : tool.read_only === false ? (
                      <Tag color="volcano">写</Tag>
                    ) : (
                      <Tag color="blue">读</Tag>
                    )}
                  </Space>
                ))}
              </Space>
            }
          >
            <span style={{ cursor: 'help' }}>
              {summary.writeCount > 0 ? (
                <>
                  {item.tools.length} · 含{' '}
                  <Typography.Text style={{ color: token.colorError, fontWeight: 600 }}>
                    {summary.writeCount} 写
                  </Typography.Text>
                </>
              ) : (
                summary.label
              )}
            </span>
          </Tooltip>
        );
      },
    },
    {
      title: '近 7 日',
      key: 'seven_day',
      width: 150,
      render: (_, item) => {
        const presentation = sevenDayPresentation(item.seven_day_run_count, item.seven_day_failure_count);
        if (!presentation.total) return <Typography.Text type="secondary">无运行</Typography.Text>;
        return (
          <Space size={6}>
            <Typography.Text>{presentation.total}</Typography.Text>
            {presentation.failure ? (
              <Typography.Text type="danger">{presentation.failure}</Typography.Text>
            ) : null}
          </Space>
        );
      },
    },
  ];

  return (
    <PageShell
      title="Agent 列表"
      description="平台上的 Agent 与运行状态一览：谁在跑、跑得怎么样、哪些草稿待确认。"
      actions={
        <Link to="/agents/new">
          <Button type="primary">新建 Agent</Button>
        </Link>
      }
    >
      {attention.hasAnything ? (
        <Space size={12} style={{ width: '100%' }} styles={{ item: { flex: 1, minWidth: 0 } }}>
          {attention.draftTotal > 0 ? (
            <Alert
              type="info"
              showIcon={false}
              style={{ borderInlineStartWidth: 3, borderInlineStartColor: token.colorPrimary }}
              message={
                <Space size={8} align="baseline">
                  <span style={{ fontSize: 19, fontWeight: 600, color: token.colorPrimary }}>
                    {attention.draftTotal}
                  </span>
                  <span>个草稿待确认 · {attention.draftAgents.join('、')}</span>
                </Space>
              }
            />
          ) : null}
          {attention.failureTotal > 0 ? (
            <Alert
              type="error"
              showIcon={false}
              style={{ borderInlineStartWidth: 3, borderInlineStartColor: token.colorError }}
              message={
                <Space size={8} align="baseline">
                  <span style={{ fontSize: 19, fontWeight: 600, color: token.colorError }}>
                    {attention.failureTotal}
                  </span>
                  <span>次运行失败（近 7 日）</span>
                </Space>
              }
            />
          ) : null}
        </Space>
      ) : null}
      <FilterBar
        actions={
          <Space wrap>
            <Button type="primary" icon={<SearchOutlined />} onClick={() => applyFilters(draftFilters)}>
              查询
            </Button>
            <Button onClick={resetFilters}>重置</Button>
            <Button icon={<ReloadOutlined />} onClick={list.reload}>
              刷新
            </Button>
          </Space>
        }
      >
        <Select
          aria-label="运行状态筛选"
          placeholder="运行状态"
          allowClear
          style={{ width: 150 }}
          value={draftFilters.state}
          options={STATE_OPTIONS}
          onChange={(value) => setDraftFilters((current) => ({ ...current, state: value ?? undefined }))}
        />
        <Select
          aria-label="待确认草稿筛选"
          placeholder="待确认草稿"
          allowClear
          style={{ width: 150 }}
          value={draftFilters.hasDraft === undefined ? undefined : draftFilters.hasDraft ? 'yes' : 'no'}
          options={[
            { value: 'yes', label: '有待确认草稿' },
            { value: 'no', label: '无待确认草稿' },
          ]}
          onChange={(value) =>
            setDraftFilters((current) => ({
              ...current,
              hasDraft: value === 'yes' ? true : value === 'no' ? false : undefined,
            }))
          }
        />
        <Select
          aria-label="写权限筛选"
          placeholder="写权限"
          allowClear
          style={{ width: 150 }}
          value={draftFilters.allowWrite === undefined ? undefined : draftFilters.allowWrite ? 'yes' : 'no'}
          options={[
            { value: 'yes', label: '仅可写' },
            { value: 'no', label: '仅只读' },
          ]}
          onChange={(value) =>
            setDraftFilters((current) => ({
              ...current,
              allowWrite: value === 'yes' ? true : value === 'no' ? false : undefined,
            }))
          }
        />
      </FilterBar>

      <DataTable<AgentListItem>
        rowKey="slug"
        columns={columns}
        dataSource={items}
        loading={list.loading}
        error={list.error}
        onRetry={list.reload}
        errorTitle="Agent 列表加载失败"
        emptyText="暂无 Agent"
        pagination={false}
      />
    </PageShell>
  );
}
