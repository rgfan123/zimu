/**
 * P5 评测（/agents/:slug/evals，Agent 详情内 tab）：某定义版本的冻结用例集。
 * GET /api/v1/agents/{slug}/versions/{version}/eval-cases。
 *
 * - 按版本选择（默认 active 版本，无 active 则最新版本），INVARIANT（确定性门禁）与
 *   QUALITY（质量评测）分两组表格，混在一起会误读基线。
 * - 本期只读（写动作等 T11）：不提供新增/修改入口；active 版本上显式说明
 *   「新增用例需先创建草稿版本」。
 */

import { useMemo, useState } from 'react';
import { Alert, Card, Segmented, Space, Tag, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { agentsApi } from '@/api/endpoints';
import type { AgentEvalCaseItem, AgentVersionItem } from '@/api/agentTypes';
import { useAsync } from '@/hooks/useAsync';
import DataTable from '@/components/DataTable';
import {
  EVAL_STATUS_PRESENTATION,
  evalCaseGroups,
  formatJson,
  formatTime,
} from './agentPresentation';

const columns: ColumnsType<AgentEvalCaseItem> = [
  {
    title: '用例 ID',
    dataIndex: 'id',
    width: 110,
    render: (value: number) => (
      <Typography.Text style={{ fontFamily: 'monospace', fontSize: 12 }}>#{value}</Typography.Text>
    ),
  },
  {
    title: '输入',
    dataIndex: 'input',
    width: 280,
    render: (value: unknown) => (
      <pre style={{ margin: 0, fontFamily: 'monospace', fontSize: 12, maxHeight: 160, overflow: 'auto' }}>
        {formatJson(value)}
      </pre>
    ),
  },
  {
    title: '期望',
    dataIndex: 'expected',
    width: 280,
    render: (value: unknown) => (
      <pre style={{ margin: 0, fontFamily: 'monospace', fontSize: 12, maxHeight: 160, overflow: 'auto' }}>
        {formatJson(value)}
      </pre>
    ),
  },
  {
    title: '状态',
    dataIndex: 'status',
    width: 100,
    render: (value: AgentEvalCaseItem['status']) => {
      const presentation = EVAL_STATUS_PRESENTATION[value];
      return <Tag color={presentation.tone}>{presentation.label}</Tag>;
    },
  },
  {
    title: '创建 / 确认',
    key: 'confirmation',
    width: 200,
    render: (_, item) => (
      <Space direction="vertical" size={0}>
        <Typography.Text style={{ fontSize: 12 }}>
          {item.confirmed_by
            ? `确认：${item.confirmed_by} @ ${formatTime(item.confirmed_at)}`
            : `创建：${item.created_by ?? '—'}`}
        </Typography.Text>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          {item.confirmed_at ? formatTime(item.confirmed_at) : '待确认'}
        </Typography.Text>
      </Space>
    ),
  },
];

function EvalGroupTable({
  title,
  tone,
  cases,
  loading,
  error,
  onRetry,
  emptyText,
}: {
  title: string;
  tone: 'purple' | 'blue';
  cases: AgentEvalCaseItem[];
  loading: boolean;
  error: unknown;
  onRetry: () => void;
  emptyText: string;
}) {
  return (
    <Card
      size="small"
      title={
        <Space size={6}>
          <Tag color={tone}>{title}</Tag>
        </Space>
      }
    >
      <DataTable<AgentEvalCaseItem>
        rowKey="id"
        columns={columns}
        dataSource={cases}
        loading={loading}
        error={error}
        onRetry={onRetry}
        errorTitle="评测用例加载失败"
        emptyText={emptyText}
        pagination={false}
        scroll={{ x: 900 }}
      />
    </Card>
  );
}

export default function EvalsTab({ slug, versions }: { slug: string; versions: AgentVersionItem[] }) {
  const sortedVersions = useMemo(() => [...versions].sort((a, b) => b.version - a.version), [versions]);
  const [selectedVersion, setSelectedVersion] = useState<number>(() => {
    const active = sortedVersions.find((version) => version.status === 'ACTIVE');
    return (active ?? sortedVersions[0])?.version ?? 0;
  });

  const evalCases = useAsync(
    () => (selectedVersion > 0 ? agentsApi.evalCases(slug, selectedVersion) : Promise.resolve([])),
    [slug, selectedVersion],
  );
  const groups = evalCaseGroups(evalCases.data ?? []);

  if (!sortedVersions.length) {
    return <Alert showIcon type="info" message="该 Agent 暂无任何版本，无冻结评测用例。" />;
  }

  const activeSelected = sortedVersions.some(
    (version) => version.version === selectedVersion && version.status === 'ACTIVE',
  );

  return (
    <Space direction="vertical" size={12} style={{ width: '100%' }}>
      <Card size="small">
        <Space wrap size={12} align="center">
          <Typography.Text type="secondary">评测版本</Typography.Text>
          <Segmented
            aria-label="评测版本"
            value={selectedVersion}
            options={sortedVersions.map((version) => ({
              value: version.version,
              label: `v${version.version} · ${version.status === 'ACTIVE' ? '生效中' : version.status === 'DRAFT' ? '草稿' : '已下线'}`,
            }))}
            onChange={(value) => setSelectedVersion(Number(value))}
          />
        </Space>
      </Card>

      {activeSelected ? (
        <Alert
          showIcon
          type="info"
          message="active 版本用例冻结只读：新增或修改用例需先创建草稿版本（写动作即将开放）。"
        />
      ) : null}

      <EvalGroupTable
        title="INVARIANT · 确定性门禁"
        tone="blue"
        cases={groups.invariant}
        loading={evalCases.loading}
        error={evalCases.error}
        onRetry={evalCases.reload}
        emptyText="该版本暂无 INVARIANT 用例"
      />
      <EvalGroupTable
        title="QUALITY · 质量评测"
        tone="purple"
        cases={groups.quality}
        loading={evalCases.loading}
        error={evalCases.error}
        onRetry={evalCases.reload}
        emptyText="该版本暂无 QUALITY 用例"
      />
    </Space>
  );
}
