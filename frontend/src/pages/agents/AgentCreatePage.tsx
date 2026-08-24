/**
 * P6 对话式创建 Agent（/agents/new，agent-console 06）。
 *
 * 平台红线的界面表达：**本页不存在任何启用路径**。产出永远是草稿，
 * 启用必须由人到 Agent 详情页单独做——把「创建并启用」做成一个按钮，
 * 就等于让没人复核过的 Agent 直接上线。
 *
 * 三种结局各有独立呈现，不折叠成成功/失败两态：NEEDS_INPUT 是正常的一步，
 * 显示成失败会让人以为 Agent 坏了，而系统只是在等一个它不该猜的答案。
 */

import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Alert, Button, Card, Col, Input, List, Row, Space, Tag, Typography } from 'antd';
import { SendOutlined } from '@ant-design/icons';
import { metaAgentApi } from '@/api/endpoints';
import type { MetaAgentOutcome } from '@/api/agentTypes';
import PageShell from '@/components/PageShell';

const RED_LINE =
  'Meta-Agent 只能产出草稿。启用永远是人工在 Agent 详情页单独完成的动作——本页没有、也不会有「创建并启用」。';

interface Turn {
  message: string;
  outcome: MetaAgentOutcome | null;
  error: string | null;
}

function outcomeTag(outcome: MetaAgentOutcome) {
  switch (outcome.outcome) {
    case 'SUCCESS':
      return <Tag color="success">草稿已创建</Tag>;
    case 'NEEDS_INPUT':
      return <Tag color="processing">需要补充信息</Tag>;
    case 'REJECTED':
      return <Tag color="warning">已拒绝</Tag>;
    default:
      return <Tag color="error">运行失败</Tag>;
  }
}

export default function AgentCreatePage() {
  const [draft, setDraft] = useState('');
  const [turns, setTurns] = useState<Turn[]>([]);
  const [sending, setSending] = useState(false);

  const latest = turns.length > 0 ? turns[turns.length - 1] : null;
  const latestRaw = latest?.outcome?.raw ?? null;

  const send = async () => {
    const message = draft.trim();
    if (!message || sending) return;
    setSending(true);
    try {
      const outcome = await metaAgentApi.converse(message);
      setTurns((current) => [...current, { message, outcome, error: null }]);
      setDraft('');
    } catch (error) {
      setTurns((current) => [
        ...current,
        { message, outcome: null, error: error instanceof Error ? error.message : '请求失败' },
      ]);
    } finally {
      setSending(false);
    }
  };

  return (
    <PageShell
      title="对话式创建 Agent"
      description="用自然语言描述职责，Meta-Agent 选工具、写提示词，产出待确认的草稿。"
    >
      <Alert showIcon type="info" message={RED_LINE} />

      <Row gutter={[16, 16]}>
        <Col xs={24} lg={13}>
          <Card title="对话" size="small">
            <List
              locale={{ emptyText: '描述一下你想要的 Agent：它负责什么、需要读哪些数据。' }}
              dataSource={turns}
              renderItem={(turn) => (
                <List.Item style={{ display: 'block' }}>
                  <Typography.Paragraph style={{ marginBottom: 8 }}>
                    <Typography.Text type="secondary">你：</Typography.Text> {turn.message}
                  </Typography.Paragraph>
                  {turn.error ? (
                    <Alert showIcon type="error" message="请求失败" description={turn.error} />
                  ) : null}
                  {turn.outcome ? <OutcomeBlock outcome={turn.outcome} /> : null}
                </List.Item>
              )}
            />
            <Space.Compact style={{ width: '100%', marginTop: 12 }}>
              <Input.TextArea
                aria-label="Agent 需求描述"
                rows={3}
                value={draft}
                placeholder="例如：做一个每天汇总缺货订单的只读 Agent，能查订单和库存"
                onChange={(event) => setDraft(event.target.value)}
                onPressEnter={(event) => {
                  if (!event.shiftKey) {
                    event.preventDefault();
                    void send();
                  }
                }}
              />
              <Button
                type="primary"
                icon={<SendOutlined />}
                loading={sending}
                disabled={!draft.trim()}
                onClick={() => void send()}
                style={{ height: 'auto' }}
              >
                发送
              </Button>
            </Space.Compact>
          </Card>
        </Col>

        <Col xs={24} lg={11}>
          <Card title="草稿预览" size="small">
            {latestRaw ? (
              <Typography.Paragraph>
                <pre
                  style={{
                    margin: 0,
                    maxHeight: 420,
                    overflow: 'auto',
                    fontSize: 12,
                    fontFamily: 'monospace',
                  }}
                >
                  {JSON.stringify(latestRaw, null, 2)}
                </pre>
              </Typography.Paragraph>
            ) : (
              <Typography.Text type="secondary">尚无草稿。左侧发起对话后，这里显示模型产出的定义。</Typography.Text>
            )}
          </Card>
        </Col>
      </Row>
    </PageShell>
  );
}

function OutcomeBlock({ outcome }: { outcome: MetaAgentOutcome }) {
  return (
    <Space direction="vertical" size={8} style={{ width: '100%' }}>
      <Space wrap>
        {outcomeTag(outcome)}
        {outcome.run_id ? (
          <Link to={`/agents/runs/${outcome.run_id}`} style={{ fontFamily: 'monospace', fontSize: 12 }}>
            {outcome.run_id}
          </Link>
        ) : null}
      </Space>

      {outcome.outcome === 'SUCCESS' && outcome.agent_slug ? (
        <Alert
          showIcon
          type="success"
          message={`草稿 ${outcome.agent_slug} v${outcome.draft_version ?? '?'} 已创建，尚未启用`}
          description={
            <Space direction="vertical" size={4}>
              <Typography.Text>
                下一步：去详情页复核定义与建议评测用例，确认后再单独启用。
              </Typography.Text>
              {/* 草稿上的 enabled 只是草稿里的一个值，说清楚它不等于已启用 */}
              {outcome.draft_enabled ? (
                <Typography.Text type="warning">
                  草稿里 enabled 写的是 true，但该版本状态仍是 draft —— 没有人确认过，它不在运行。
                </Typography.Text>
              ) : null}
              <Link to={`/agents/${outcome.agent_slug}`}>去 {outcome.agent_slug} 详情页</Link>
            </Space>
          }
        />
      ) : null}

      {outcome.questions.length > 0 ? (
        <Alert
          showIcon
          type="info"
          message="需要你补充这些信息"
          description={
            <ul style={{ marginBottom: 0, paddingInlineStart: 20 }}>
              {outcome.questions.map((question) => (
                <li key={question}>{question}</li>
              ))}
            </ul>
          }
        />
      ) : null}

      {outcome.outcome === 'REJECTED' && outcome.rejection_reason ? (
        <Alert showIcon type="warning" message="未产出草稿" description={outcome.rejection_reason} />
      ) : null}

      {outcome.outcome === 'FAILED' ? (
        <Alert
          showIcon
          type="error"
          message="Meta-Agent 运行失败"
          description={
            <Typography.Text style={{ fontFamily: 'monospace' }}>{outcome.error}</Typography.Text>
          }
        />
      ) : null}
    </Space>
  );
}
