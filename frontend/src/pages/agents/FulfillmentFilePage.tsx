/**
 * 履约单据助手（/agents/fulfillment-file）：Excel 四段闭环的只读解读。
 *
 * 两条呈现纪律：
 * - **「暂不适用」≠「0 待办」**。某段 supported=false 时显式写「该段暂不适用」，
 *   绝不渲染成 0 —— 复核没过就不会有发货单，显示「0 单待发」会让人以为发完了。
 * - **事实与解读分开**。上半屏是 SQL 算出的四段进度，下半屏才是模型的话。
 *   揉成一段，读者就无从判断哪些数字能拿去做决定。
 */

import { useState } from 'react';
import { Alert, Button, Card, Col, Descriptions, Input, Row, Space, Table, Tag, Typography } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { RobotOutlined, SearchOutlined } from '@ant-design/icons';
import { importBatchApi } from '@/api/endpoints';
import type {
  FulfillmentFileRunResult,
  ImportBatchBlocker,
  ImportBatchProgress,
  ImportBatchStage,
} from '@/api/agentTypes';
import { importBatchStageComplete } from './agentPresentation';
import PageShell from '@/components/PageShell';

const READ_ONLY_NOTE =
  '本 Agent 只读：它不发货、不回填、不回传。三件事仍由确定性服务执行，这里只给解读与建议。';

function StageCard({ stage }: { stage: ImportBatchStage }) {
  if (!stage.supported) {
    return (
      <Card size="small" title={stage.name}>
        <Typography.Text type="secondary">该段暂不适用</Typography.Text>
        <br />
        {/* 说清楚为什么不适用，否则会被读成「加载失败」 */}
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          这批还没有进入该段的对象（不是 0 待办）
        </Typography.Text>
      </Card>
    );
  }
  const blocked = stage.blocked;
  return (
    <Card
      size="small"
      title={
        <Space>
          {stage.name}
          {importBatchStageComplete(stage) ? <Tag color="success">已完成</Tag> : null}
        </Space>
      }
    >
      <Typography.Text style={{ fontSize: 22, fontWeight: 600 }}>{stage.done}</Typography.Text>
      <Typography.Text type="secondary"> / {stage.total}</Typography.Text>
      <br />
      {blocked > 0 ? (
        <Tag color="error">{blocked} 项卡住</Tag>
      ) : (
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          无阻塞
        </Typography.Text>
      )}
    </Card>
  );
}

const BLOCKER_COLUMNS: ColumnsType<ImportBatchBlocker> = [
  { title: '段', dataIndex: 'stage', width: 90 },
  {
    title: '稳定码',
    dataIndex: 'code',
    render: (value: string) => (
      <Typography.Text style={{ fontFamily: 'monospace', fontSize: 12 }}>{value}</Typography.Text>
    ),
  },
  { title: '条数', dataIndex: 'count', width: 80 },
  {
    title: '样本业务号',
    dataIndex: 'sample_no',
    width: 200,
    render: (value: string | null) =>
      value ? (
        <Typography.Text copyable style={{ fontFamily: 'monospace', fontSize: 12 }}>
          {value}
        </Typography.Text>
      ) : (
        <Typography.Text type="secondary">—</Typography.Text>
      ),
  },
];

export default function FulfillmentFilePage() {
  const [batchId, setBatchId] = useState('');
  const [progress, setProgress] = useState<ImportBatchProgress | null>(null);
  const [result, setResult] = useState<FulfillmentFileRunResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState<'progress' | 'assess' | null>(null);

  const parsedId = Number(batchId.trim());
  const validId = Number.isSafeInteger(parsedId) && parsedId > 0;
  // 四段固定顺序：完成判定与分段卡片共用同一个数组，段序只写一次
  const stages = progress
    ? [progress.intake, progress.outbound, progress.tracking, progress.source_return]
    : [];

  const run = async (mode: 'progress' | 'assess') => {
    if (!validId) return;
    setLoading(mode);
    setError(null);
    try {
      if (mode === 'progress') {
        setProgress(await importBatchApi.progress(parsedId));
        setResult(null);
      } else {
        const assessed = await importBatchApi.assess(parsedId);
        setResult(assessed);
        setProgress(assessed.progress);
      }
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : '请求失败');
    } finally {
      setLoading(null);
    }
  };

  return (
    <PageShell
      title="履约单据助手"
      description="按导入批次查看「收表 → 发货 → 回填 → 回传」四段进度，并让 Agent 解读卡点。"
    >
      <Alert showIcon type="info" message={READ_ONLY_NOTE} />

      <Space wrap>
        <Input
          aria-label="导入批次 ID"
          placeholder="导入批次 ID"
          style={{ width: 200 }}
          value={batchId}
          onChange={(event) => setBatchId(event.target.value)}
          onPressEnter={() => void run('progress')}
        />
        <Button
          icon={<SearchOutlined />}
          disabled={!validId}
          loading={loading === 'progress'}
          onClick={() => void run('progress')}
        >
          查看进度
        </Button>
        {/* 解读要跑模型、要花钱，和只读的进度分成两个动作 */}
        <Button
          type="primary"
          icon={<RobotOutlined />}
          disabled={!validId}
          loading={loading === 'assess'}
          onClick={() => void run('assess')}
        >
          让 Agent 解读
        </Button>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          查看进度不调模型，也不花 token。
        </Typography.Text>
      </Space>

      {error ? <Alert showIcon type="error" message="请求失败" description={error} /> : null}

      {progress ? (
        <>
          <Descriptions bordered size="small" column={{ xs: 1, sm: 2, lg: 4 }}>
            <Descriptions.Item label="批次号">{progress.batch_no}</Descriptions.Item>
            <Descriptions.Item label="来源渠道">{progress.source_channel || '未标注'}</Descriptions.Item>
            <Descriptions.Item label="批次状态">{progress.status}</Descriptions.Item>
            <Descriptions.Item label="当前卡在">
              {progress.blockers.length === 0 && !progress.intake.supported ? '—' : null}
              {stages.every(importBatchStageComplete) ? (
                <Tag color="success">四段已走完</Tag>
              ) : (
                <Tag color="processing">见下方分段</Tag>
              )}
            </Descriptions.Item>
          </Descriptions>

          <Row gutter={[16, 16]}>
            {stages.map((stage) => (
              <Col xs={24} sm={12} xl={6} key={stage.name}>
                <StageCard stage={stage} />
              </Col>
            ))}
          </Row>

          <Card title="阻塞事实" size="small">
            <Table<ImportBatchBlocker>
              rowKey={(row) => `${row.stage}-${row.code}`}
              size="small"
              pagination={false}
              columns={BLOCKER_COLUMNS}
              dataSource={progress.blockers}
              locale={{ emptyText: '当前无阻塞事实' }}
            />
          </Card>
        </>
      ) : null}

      {result?.error ? (
        <Alert
          showIcon
          type="warning"
          message="Agent 解读未成功，但上方事实照常可用"
          description={
            <Typography.Text style={{ fontFamily: 'monospace' }}>{result.error}</Typography.Text>
          }
        />
      ) : null}

      {result?.assessment ? (
        <Card
          size="small"
          title={
            <Space>
              Agent 解读
              {result.assessment.requires_human ? <Tag color="warning">需要人工</Tag> : null}
              <Typography.Text type="secondary" style={{ fontSize: 12, fontWeight: 400 }}>
                {result.model ?? '—'} · {result.prompt_version ?? '—'}
              </Typography.Text>
            </Space>
          }
        >
          <Space direction="vertical" size={12} style={{ width: '100%' }}>
            <Typography.Paragraph style={{ marginBottom: 0 }}>
              {result.assessment.summary}
            </Typography.Paragraph>

            {result.assessment.stage_notes.length > 0 ? (
              <ul style={{ marginBottom: 0, paddingInlineStart: 20 }}>
                {result.assessment.stage_notes.map((note) => (
                  <li key={note.stage}>
                    <Typography.Text strong>{note.stage}</Typography.Text>：{note.note}
                  </li>
                ))}
              </ul>
            ) : null}

            <div>
              <Typography.Text strong>建议后续动作</Typography.Text>
              <ul style={{ marginBottom: 0, paddingInlineStart: 20 }}>
                {result.assessment.suggested_actions.map((action) => (
                  <li key={action.action}>
                    {action.action}
                    <Typography.Text type="secondary"> —— 依据：{action.reason}</Typography.Text>
                    {action.target_no ? (
                      <Typography.Text copyable style={{ fontFamily: 'monospace', fontSize: 12 }}>
                        {' '}
                        {action.target_no}
                      </Typography.Text>
                    ) : null}
                  </li>
                ))}
              </ul>
            </div>

            {result.assessment.missing_fields.length > 0 ? (
              <Alert
                showIcon
                type="info"
                message="事实不足以判断下一步"
                description={`缺少：${result.assessment.missing_fields.join('、')}`}
              />
            ) : null}
          </Space>
        </Card>
      ) : null}
    </PageShell>
  );
}
