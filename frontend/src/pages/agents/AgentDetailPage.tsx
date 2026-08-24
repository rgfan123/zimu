/**
 * P2 Agent 详情（/agents/:slug；评测 tab 即 P5，独立路由 /agents/:slug/evals）。
 *
 * - 三个 tab：当前生效（默认）/ 版本链 / 评测。tab 与视图进 URL：
 *   `/agents/:slug?tab=versions` 直达版本链，`/agents/:slug/evals` 直达评测，
 *   刷新与分享可复现（设计裁定：详情走真实路由，不用本地状态抽屉）。
 * - 写动作本期一律不做（T11 未合并）：版本链不出现任何回滚/确认按钮。
 * - 页内只放「最近 5 次运行」摘要 + 「查看全部」跳 /agents/runs?slug=<slug>
 *   （设计裁定二：运行记录独立页做真源，本页不重复实现运行表格）。
 */

import { Link, useLocation, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import {
  Alert,
  Button,
  Card,
  Collapse,
  Descriptions,
  Empty,
  Skeleton,
  Space,
  Tabs,
  Tag,
  Timeline,
  Typography,
} from 'antd';
import { ArrowLeftOutlined, ReloadOutlined } from '@ant-design/icons';
import { errorMessage } from '@/api/client';
import { agentsApi, agentRunsApi } from '@/api/endpoints';
import type { AgentDetail, AgentVersionItem } from '@/api/agentTypes';
import { useAsync } from '@/hooks/useAsync';
import PageShell from '@/components/PageShell';
import EvalsTab from './EvalsTab';
import {
  agentStatusPresentation,
  formatJson,
  formatTime,
  runOutcomePresentation,
  versionStatusPresentation,
} from './agentPresentation';

function toolTag(tool: { name: string; read_only: boolean | null; registered: boolean }) {
  if (!tool.registered) return <Tag key={tool.name}>未注册</Tag>;
  return tool.read_only === false ? (
    <Tag key={tool.name} color="volcano">
      写
    </Tag>
  ) : (
    <Tag key={tool.name} color="blue">
      读
    </Tag>
  );
}

function CurrentFacts({ detail }: { detail: AgentDetail }) {
  const status = agentStatusPresentation(detail.status);
  return (
    <Space direction="vertical" size={12} style={{ width: '100%' }}>
      <Card size="small" title="定义事实">
        <Descriptions column={2} size="small" bordered>
          <Descriptions.Item label="名称">{detail.name}</Descriptions.Item>
          <Descriptions.Item label="Slug">
            <Typography.Text style={{ fontFamily: 'monospace', fontSize: 12 }}>{detail.slug}</Typography.Text>
          </Descriptions.Item>
          <Descriptions.Item label="描述" span={2}>
            {detail.description ?? '—'}
          </Descriptions.Item>
          <Descriptions.Item label="版本">
            <Space size={6}>
              <Typography.Text style={{ fontFamily: 'monospace' }}>v{detail.version}</Typography.Text>
              <Tag color={status.tone}>{status.label}</Tag>
            </Space>
          </Descriptions.Item>
          <Descriptions.Item label="启停">
            {detail.enabled ? (
              <Tag color="green">已启用</Tag>
            ) : (
              <Space size={4}>
                <Tag>已停用</Tag>
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  停用不改变版本状态，重新启用回到原版本
                </Typography.Text>
              </Space>
            )}
          </Descriptions.Item>
          <Descriptions.Item label="写权限">
            {detail.allow_write ? (
              <Tag color="volcano">可写</Tag>
            ) : (
              <Typography.Text type="secondary">只读</Typography.Text>
            )}
          </Descriptions.Item>
          <Descriptions.Item label="提示词版本">
            <Typography.Text style={{ fontFamily: 'monospace', fontSize: 12 }}>
              {detail.prompt_version ?? '—'}
            </Typography.Text>
          </Descriptions.Item>
          <Descriptions.Item label="模型引用">
            <Typography.Text style={{ fontFamily: 'monospace', fontSize: 12 }}>
              {detail.model_ref ?? '—'}
            </Typography.Text>
          </Descriptions.Item>
          <Descriptions.Item label="输入格式">
            {detail.input_format === 'STRUCTURED_JSON' ? '结构化 JSON' : '自然语言'}
          </Descriptions.Item>
          <Descriptions.Item label="确认人">{detail.activated_by ?? '—'}</Descriptions.Item>
          <Descriptions.Item label="确认时间">{formatTime(detail.activated_at)}</Descriptions.Item>
        </Descriptions>
      </Card>

      <Card size="small" title="系统提示词">
        <Collapse
          ghost
          items={[
            {
              key: 'system-prompt',
              label: '查看完整提示词',
              children: (
                <pre style={{ margin: 0, fontFamily: 'monospace', fontSize: 12, whiteSpace: 'pre-wrap' }}>
                  {detail.system_prompt}
                </pre>
              ),
            },
          ]}
        />
      </Card>

      <Card size="small" title="输出 Schema">
        {detail.output_schema === null || detail.output_schema === undefined ? (
          <Typography.Text type="secondary">—</Typography.Text>
        ) : (
          <Collapse
            ghost
            items={[
              {
                key: 'output-schema',
                label: '查看 JSON Schema',
                children: (
                  <pre style={{ margin: 0, fontFamily: 'monospace', fontSize: 12, whiteSpace: 'pre-wrap' }}>
                    {formatJson(detail.output_schema)}
                  </pre>
                ),
              },
            ]}
          />
        )}
      </Card>

      <Card size="small" title="守卫豁免">
        {detail.guard_exemptions.length ? (
          <Space wrap size={4}>
            {detail.guard_exemptions.map((exemption) => (
              <Tag key={exemption} style={{ fontFamily: 'monospace' }}>
                {exemption}
              </Tag>
            ))}
          </Space>
        ) : (
          <Typography.Text type="secondary">默认守卫全部生效</Typography.Text>
        )}
      </Card>

      <Card size="small" title="工具白名单">
        {detail.tools.length ? (
          <Space wrap size={8}>
            {detail.tools.map((tool) => (
              <Space key={tool.name} size={4}>
                <Typography.Text style={{ fontFamily: 'monospace', fontSize: 12 }}>{tool.name}</Typography.Text>
                {toolTag(tool)}
              </Space>
            ))}
          </Space>
        ) : (
          <Typography.Text type="secondary">无工具</Typography.Text>
        )}
      </Card>
    </Space>
  );
}

function VersionChain({ versions }: { versions: AgentVersionItem[] }) {
  const sorted = [...versions].sort((a, b) => b.version - a.version);
  return (
    <Card size="small" title="版本链">
      <Timeline
        items={sorted.map((version) => {
          const presentation = versionStatusPresentation(version.status);
          return {
            key: version.version,
            color: presentation.tone === 'success' ? 'green' : presentation.tone === 'warning' ? 'orange' : 'gray',
            children: (
              <Space direction="vertical" size={2}>
                <Space size={8}>
                  <Typography.Text strong style={{ fontFamily: 'monospace' }}>
                    v{version.version}
                  </Typography.Text>
                  <Tag color={presentation.tone}>{presentation.label}</Tag>
                </Space>
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  {version.status === 'DRAFT'
                    ? '草稿，尚未确认'
                    : `由 ${version.activated_by ?? '—'} 于 ${formatTime(version.activated_at)} 确认`}
                </Typography.Text>
              </Space>
            ),
          };
        })}
      />
      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
        版本状态机无回边：回滚 = 以旧版本为蓝本创建新草稿再确认（写动作即将开放）。
      </Typography.Text>
    </Card>
  );
}

function RecentRuns({ slug }: { slug: string }) {
  const recent = useAsync(() => agentRunsApi.list({ slug, limit: 5 }), [slug]);
  return (
    <Card
      size="small"
      title="最近运行"
      extra={<Link to={`/agents/runs?slug=${slug}`}>查看全部</Link>}
    >
      {recent.loading ? (
        <Skeleton active paragraph={{ rows: 3 }} />
      ) : recent.error ? (
        <Typography.Text type="danger">最近运行加载失败</Typography.Text>
      ) : (() => {
        const runs = recent.data?.items ?? [];
        return runs.length ? (
        <Space direction="vertical" size={6} style={{ width: '100%' }}>
          {runs.map((run) => {
            const outcome = runOutcomePresentation(run.outcome);
            return (
              <Space key={run.run_id} size={8} style={{ width: '100%', justifyContent: 'space-between' }}>
                <Space size={8}>
                  <Tag color={outcome.tone}>{outcome.label}</Tag>
                  <Link to={`/agents/runs/${run.run_id}`} style={{ fontFamily: 'monospace', fontSize: 12 }}>
                    {run.run_id}
                  </Link>
                </Space>
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  {formatTime(run.started_at)}
                </Typography.Text>
              </Space>
            );
          })}
        </Space>
        ) : (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无运行记录" />
        );
      })()}
    </Card>
  );
}

export default function AgentDetailPage() {
  const { slug = '' } = useParams();
  const navigate = useNavigate();
  const { pathname } = useLocation();
  const [searchParams] = useSearchParams();

  const isEvalsRoute = pathname.endsWith('/evals');
  const activeTab = isEvalsRoute ? 'evals' : searchParams.get('tab') === 'versions' ? 'versions' : 'current';

  const detail = useAsync(() => agentsApi.detail(slug), [slug]);
  const versionsQuery = useAsync(() => agentsApi.versions(slug), [slug]);

  const loading = detail.loading || versionsQuery.loading;
  const error = detail.error ?? versionsQuery.error;

  const onTabChange = (key: string) => {
    if (key === 'evals') navigate(`/agents/${slug}/evals`);
    else if (key === 'versions') navigate(`/agents/${slug}?tab=versions`);
    else navigate(`/agents/${slug}`);
  };

  return (
    <PageShell
      title={detail.data ? `${detail.data.name} · ${slug}` : `Agent 详情 · ${slug}`}
      description="当前生效定义、版本链与冻结评测用例；运行记录请前往独立页。"
      actions={
        <Link to="/agents">
          <Button icon={<ArrowLeftOutlined />}>返回 Agent 列表</Button>
        </Link>
      }
    >
      {loading ? (
        <Card size="small">
          <Skeleton active paragraph={{ rows: 8 }} />
        </Card>
      ) : error ? (
        <Alert
          type="error"
          showIcon
          message="Agent 详情加载失败"
          description={errorMessage(error)}
          action={
            <Button size="small" icon={<ReloadOutlined />} onClick={() => { detail.reload(); versionsQuery.reload(); }}>
              重试
            </Button>
          }
        />
      ) : detail.data ? (
        <Tabs
          activeKey={activeTab}
          onChange={onTabChange}
          items={[
            {
              key: 'current',
              label: '当前生效',
              children: (
                <Space direction="vertical" size={12} style={{ width: '100%' }}>
                  <CurrentFacts detail={detail.data} />
                  <RecentRuns slug={slug} />
                </Space>
              ),
            },
            {
              key: 'versions',
              label: '版本链',
              children: <VersionChain versions={versionsQuery.data ?? []} />,
            },
            {
              key: 'evals',
              label: '评测',
              children: <EvalsTab slug={slug} versions={versionsQuery.data ?? []} />,
            },
          ]}
        />
      ) : null}
    </PageShell>
  );
}
