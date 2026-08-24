/**
 * P4 运行详情（/agents/runs/:runId）：一次运行的完整事实。
 * GET /api/v1/agent-runs/{run_id} —— 元信息 + 工具调用序列 + 关联评测结果摘要。
 *
 * - error_type（稳定枚举）失败时放最显眼处（Alert）。
 * - input_digest 是 SHA-256 摘要、无原文（08 隐私设计）：显式展示摘要并说明
 *   「输入原文不留存，仅存摘要用于比对」，不得渲染成空白。
 * - 模型元数据三态（EXPOSED/NOT_PUBLIC/NOT_CONFIGURED）各自独立文案，不折叠。
 * - 工具调用按 sequence_no 升序画 Timeline（sortToolCalls 纯函数）。
 */

import { Link, useParams } from 'react-router-dom';
import {
  Alert,
  Button,
  Card,
  Collapse,
  Descriptions,
  Empty,
  Skeleton,
  Space,
  Tag,
  Timeline,
  theme,
  Typography,
} from 'antd';
import { ArrowLeftOutlined, ReloadOutlined } from '@ant-design/icons';
import { errorMessage } from '@/api/client';
import { agentRunsApi } from '@/api/endpoints';
import type { RunDetail } from '@/api/agentTypes';
import { useAsync } from '@/hooks/useAsync';
import PageShell from '@/components/PageShell';
import {
  INPUT_DIGEST_EXPLANATION,
  digestLabel,
  formatCompactJson,
  formatLatency,
  formatTime,
  modelVisibilityPresentation,
  runModePresentation,
  runOutcomePresentation,
  runStatusPresentation,
  latencyBarPercents,
  sortToolCalls,
} from './agentPresentation';

function modelMetadataView(run: RunDetail) {
  const metadata = run.model_metadata;
  if (!metadata) return '—';
  const presentation = modelVisibilityPresentation(metadata.visibility);
  if (metadata.visibility === 'EXPOSED') {
    return (
      <Space direction="vertical" size={0}>
        <Space size={6}>
          <Tag color={presentation.tone}>{presentation.label}</Tag>
          <Typography.Text style={{ fontFamily: 'monospace', fontSize: 12 }}>
            {metadata.provider} / {metadata.model}
          </Typography.Text>
        </Space>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          prompt version {metadata.prompt_version}
        </Typography.Text>
      </Space>
    );
  }
  return (
    <Space direction="vertical" size={0}>
      <Tag color={presentation.tone}>{presentation.label}</Tag>
      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
        {presentation.note}
      </Typography.Text>
    </Space>
  );
}

export default function RunDetailPage() {
  // hook 必须在任何提前 return 之前无条件调用
  const { token } = theme.useToken();
  const { runId = '' } = useParams();
  const detail = useAsync(() => agentRunsApi.detail(runId), [runId]);

  if (detail.loading) {
    return (
      <PageShell title={`运行详情 · ${runId}`} actions={backButton()}>
        <Card size="small">
          <Skeleton active paragraph={{ rows: 8 }} />
        </Card>
      </PageShell>
    );
  }

  if (detail.error) {
    return (
      <PageShell title={`运行详情 · ${runId}`} actions={backButton()}>
        <Alert
          type="error"
          showIcon
          message="运行详情加载失败"
          description={errorMessage(detail.error)}
          action={
            <Button size="small" icon={<ReloadOutlined />} onClick={detail.reload}>
              重试
            </Button>
          }
        />
      </PageShell>
    );
  }

  const run = detail.data;
  if (!run) return null;

  const failure = run.error_type;
  const outcomePresentation = runOutcomePresentation(run.outcome);
  const statusPresentation = runStatusPresentation(run.status);
  const modePresentation = runModePresentation(run.run_mode);
  const toolCalls = sortToolCalls(run.tool_calls ?? []);
  const latencyBars = latencyBarPercents(toolCalls);

  return (
    <PageShell
      title={`运行详情 · ${run.run_id}`}
      description={`Agent ${run.agent_slug} v${run.agent_version} · ${modePresentation.label} · ${statusPresentation.label}`}
      actions={backButton()}
    >
      {failure ? (
        <Alert
          showIcon
          type="error"
          message="失败原因"
          description={
            <Space direction="vertical" size={2}>
              <Typography.Text strong style={{ fontFamily: 'monospace' }}>
                {failure}
              </Typography.Text>
              <Typography.Text type="secondary">
                {outcomePresentation.label === '被拒绝'
                  ? '守卫/权限/参数拒绝（如 PII 拦截），非系统失败。'
                  : '运行未完成，详见下方工具调用序列与失败枚举。'}
              </Typography.Text>
            </Space>
          }
        />
      ) : null}

      <Card size="small" title="运行信息">
        <Descriptions column={2} size="small" bordered>
          <Descriptions.Item label="Run ID">
            <Typography.Text style={{ fontFamily: 'monospace', fontSize: 12 }}>
              {run.run_id}
            </Typography.Text>
          </Descriptions.Item>
          <Descriptions.Item label="Thread ID">
            <Typography.Text style={{ fontFamily: 'monospace', fontSize: 12 }}>
              {run.thread_id ?? '—'}
            </Typography.Text>
          </Descriptions.Item>
          <Descriptions.Item label="Agent">
            <Link to={`/agents/${run.agent_slug}`} style={{ fontFamily: 'monospace' }}>
              {run.agent_slug}
            </Link>
          </Descriptions.Item>
          <Descriptions.Item label="定义版本">v{run.agent_version}</Descriptions.Item>
          <Descriptions.Item label="模式">
            <Tag color={modePresentation.tone} style={{ fontFamily: 'monospace' }}>
              {modePresentation.label}
            </Tag>
            <Typography.Text type="secondary" style={{ fontSize: 12, marginLeft: 6 }}>
              {modePresentation.note}
            </Typography.Text>
          </Descriptions.Item>
          <Descriptions.Item label="结果">
            <Tag color={outcomePresentation.tone}>{outcomePresentation.label}</Tag>
          </Descriptions.Item>
          <Descriptions.Item label="状态">
            <Tag color={statusPresentation.tone}>{statusPresentation.label}</Tag>
          </Descriptions.Item>
          <Descriptions.Item label="耗时">{formatLatency(run.latency_ms)}</Descriptions.Item>
          <Descriptions.Item label="Token">
            <Typography.Text style={{ fontFamily: 'monospace', fontSize: 12 }}>
              {formatCompactJson(run.token_usage)}
            </Typography.Text>
          </Descriptions.Item>
          <Descriptions.Item label="模型">{modelMetadataView(run)}</Descriptions.Item>
          <Descriptions.Item label="业务实体">
            {run.business_entity_type
              ? `${run.business_entity_type} · ${run.business_entity_id ?? '—'}`
              : '—'}
          </Descriptions.Item>
          <Descriptions.Item label="意图">{run.intent ?? '—'}</Descriptions.Item>
          <Descriptions.Item label="开始时间">{formatTime(run.started_at)}</Descriptions.Item>
          <Descriptions.Item label="结束时间">{formatTime(run.finished_at)}</Descriptions.Item>
        </Descriptions>
      </Card>

      <Card size="small" title="输入摘要">
        <Space direction="vertical" size={4}>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {INPUT_DIGEST_EXPLANATION}
          </Typography.Text>
          <Typography.Text style={{ fontFamily: 'monospace', fontSize: 12 }}>
            {digestLabel(run.input_digest)}
          </Typography.Text>
        </Space>
      </Card>

      <Card size="small" title="工具调用序列">
        {toolCalls.length ? (
          <Timeline
            items={toolCalls.map((call) => ({
              key: call.sequence_no,
              color: call.status === 'SUCCESS' ? 'green' : 'red',
              children: (
                <Space direction="vertical" size={4} style={{ width: '100%' }}>
                  <Space wrap size={8}>
                    <Typography.Text strong style={{ fontFamily: 'monospace' }}>
                      #{call.sequence_no} {call.tool_name}
                    </Typography.Text>
                    <Tag color={call.status === 'SUCCESS' ? 'success' : 'error'}>
                      {call.status === 'SUCCESS' ? '成功' : '失败'}
                    </Tag>
                    <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                      {formatLatency(call.latency_ms)}
                    </Typography.Text>
                  </Space>
                  {latencyBars[call.sequence_no] > 0 ? (
                    <div
                      aria-hidden="true"
                      style={{
                        width: `${latencyBars[call.sequence_no]}%`,
                        height: 3,
                        borderRadius: 2,
                        opacity: 0.55,
                        background: call.status === 'SUCCESS' ? token.colorPrimary : token.colorError,
                      }}
                    />
                  ) : null}
                  <Collapse
                    ghost
                    size="small"
                    items={[
                      {
                        key: `args-${call.sequence_no}`,
                        label: '参数摘要（已脱敏）',
                        children: (
                          <pre style={{ margin: 0, fontFamily: 'monospace', fontSize: 12, whiteSpace: 'pre-wrap' }}>
                            {call.args_summary ?? '—'}
                          </pre>
                        ),
                      },
                      {
                        key: `result-${call.sequence_no}`,
                        label: '结果摘要（已脱敏）',
                        children: (
                          <pre style={{ margin: 0, fontFamily: 'monospace', fontSize: 12, whiteSpace: 'pre-wrap' }}>
                            {call.result_summary ?? '—'}
                          </pre>
                        ),
                      },
                    ]}
                  />
                </Space>
              ),
            }))}
          />
        ) : (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="该运行无工具调用" />
        )}
      </Card>

      {run.eval_result ? (
        <Card size="small" title="关联评测结果">
          <Space direction="vertical" size={4}>
            <Space size={8}>
              <Tag color={evalStatusTone(run.eval_result.status)}>{run.eval_result.status}</Tag>
              <Typography.Text>
                {run.eval_result.passed_count} / {run.eval_result.case_count} 用例通过
              </Typography.Text>
            </Space>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              {formatTime(run.eval_result.started_at)} → {formatTime(run.eval_result.finished_at)}
            </Typography.Text>
          </Space>
        </Card>
      ) : null}

    </PageShell>
  );
}

function evalStatusTone(status: 'RUNNING' | 'SUCCEEDED' | 'FAILED'): string {
  return status === 'SUCCEEDED' ? 'success' : status === 'FAILED' ? 'error' : 'processing';
}

function backButton() {
  return (
    <Link to="/agents/runs">
      <Button icon={<ArrowLeftOutlined />}>返回运行记录</Button>
    </Link>
  );
}
