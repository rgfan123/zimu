import { useEffect, useState } from 'react';
import { App, Alert, Button, Card, Descriptions, Drawer, Empty, Form, Input, List, Modal, Select, Space, Tag, Typography } from 'antd';
import { FileSearchOutlined, ReloadOutlined, RobotOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { agentsApi, businessFollowUpsApi } from '@/api/endpoints';
import { errorMessage } from '@/api/client';
import type {
  BusinessFollowUp,
  BusinessFollowUpCreateInput,
  BusinessFollowUpSummary,
} from '@/api/types';
import DataTable from '@/components/DataTable';
import PageShell from '@/components/PageShell';
import { useAsync } from '@/hooks/useAsync';

const STAGE_LABELS: Record<BusinessFollowUpSummary['stage'], string> = {
  PENDING_ORGANIZATION: '待发起整理',
  ORGANIZING: '整理中',
  DRAFT_READY: '草稿待核对',
  NEEDS_INPUT: '待补充/复核',
};

const PROCESSING_LABELS: Record<BusinessFollowUpSummary['processing_status'], string> = {
  NOT_STARTED: '未发起',
  PENDING: '排队中',
  RUNNING: '处理中',
  SUCCEEDED: '已完成',
  FAILED: '失败',
};

export default function BusinessFollowUpsPage() {
  const { message } = App.useApp();
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const sourceSubmissionId = searchParams.get('submission_id');
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [organizeTarget, setOrganizeTarget] = useState<BusinessFollowUpSummary | null>(null);
  const [saving, setSaving] = useState(false);
  const [writeError, setWriteError] = useState<Error | null>(null);
  const [organizeForm] = Form.useForm<{ agent: string }>();
  const list = useAsync(() => businessFollowUpsApi.list({ page, size }), [page, size]);
  const detail = useAsync(
    () => (selectedId ? businessFollowUpsApi.detail(selectedId) : Promise.resolve(null)),
    [selectedId],
  );
  const agents = useAsync(() => agentsApi.list(), []);
  const agentOptions = (agents.data?.items ?? [])
    .filter((agent) => agent.slug === 'customer-followup-agent'
      && agent.state === 'RUNNING'
      && agent.current_version !== null)
    .map((agent) => ({
      value: `${agent.slug}@${agent.current_version}`,
      label: `${agent.name} · ${agent.slug} · v${agent.current_version}`,
    }));

  useEffect(() => {
    const selectedAgent = organizeForm.getFieldValue('agent');
    if (selectedAgent && !agentOptions.some((option) => option.value === selectedAgent)) {
      organizeForm.resetFields();
    }
  }, [agentOptions, organizeForm]);

  const closeCreate = () => setSearchParams({});

  const create = async (values: BusinessFollowUpCreateInput) => {
    setSaving(true);
    setWriteError(null);
    try {
      await businessFollowUpsApi.create(values);
      closeCreate();
      list.reload();
      message.success('客户跟进材料已建档');
    } catch (error) {
      setWriteError(error instanceof Error ? error : new Error(String(error)));
    } finally {
      setSaving(false);
    }
  };

  const openOrganize = (row: BusinessFollowUpSummary) => {
    setWriteError(null);
    setOrganizeTarget(row);
    organizeForm.resetFields();
    agents.reload();
  };

  const organize = async (values: { agent: string }) => {
    if (!organizeTarget) return;
    if (!agentOptions.some((option) => option.value === values.agent)) {
      organizeForm.resetFields();
      agents.reload();
      setWriteError(new Error('Agent 版本已变化，请重新选择'));
      return;
    }
    const [agentSlug, versionText] = values.agent.split('@');
    setSaving(true);
    setWriteError(null);
    try {
      await businessFollowUpsApi.organize(organizeTarget.id, {
        agent_slug: agentSlug,
        agent_version: Number(versionText),
      });
      setOrganizeTarget(null);
      list.reload();
      message.success('已提交 Agent 整理任务');
    } catch (error) {
      setWriteError(error instanceof Error ? error : new Error(String(error)));
      organizeForm.resetFields();
      agents.reload();
    } finally {
      setSaving(false);
    }
  };

  const columns: ColumnsType<BusinessFollowUpSummary> = [
    { title: '跟进编号', dataIndex: 'followup_no', width: 150 },
    { title: '来源提交', dataIndex: 'message_submission_id', width: 140 },
    { title: '证据版本', dataIndex: 'source_revision', width: 90 },
    {
      title: '业务阶段',
      dataIndex: 'stage',
      width: 120,
      render: (value: BusinessFollowUpSummary['stage']) => (
        <Tag color="blue">{STAGE_LABELS[value]}</Tag>
      ),
    },
    {
      title: 'Agent 处理',
      dataIndex: 'processing_status',
      width: 110,
      render: (value: BusinessFollowUpSummary['processing_status']) => (
        <Tag color={value === 'FAILED' ? 'red' : value === 'SUCCEEDED' ? 'green' : 'default'}>
          {PROCESSING_LABELS[value]}
        </Tag>
      ),
    },
    { title: '指定 +1', dataIndex: 'designated_reviewer', width: 120, render: (value) => value || '—' },
    {
      title: 'Agent 版本',
      key: 'agent',
      width: 170,
      render: (_, row) => row.agent_slug ? `${row.agent_slug} @ v${row.agent_version}` : '—',
    },
    {
      title: '操作',
      key: 'actions',
      width: 170,
      render: (_, row) => (
        <Space>
          <Button type="link" size="small" onClick={() => setSelectedId(row.id)}>详情</Button>
          {row.stage === 'PENDING_ORGANIZATION' ? (
            <Button type="link" size="small" onClick={() => openOrganize(row)}>发起整理</Button>
          ) : null}
        </Space>
      ),
    },
  ];

  return (
    <PageShell
      title="客户跟进"
      description="原始沟通证据先建档；由指定 +1 选择当前启用的 Agent 版本后再异步整理。"
      actions={(
        <Space>
          <Button icon={<ReloadOutlined />} onClick={list.reload}>刷新</Button>
          <Button
            type="primary"
            icon={<FileSearchOutlined />}
            onClick={() => navigate('/workbench/channel-messages')}
          >
            从消息证据建档
          </Button>
        </Space>
      )}
    >
      <DataTable<BusinessFollowUpSummary>
        rowKey="id"
        columns={columns}
        dataSource={list.data?.items ?? []}
        loading={list.loading}
        error={list.error}
        errorTitle="客户跟进加载失败"
        onRetry={list.reload}
        scroll={{ x: 1120 }}
        pagination={{
          current: page + 1,
          pageSize: size,
          total: list.data?.total_elements ?? 0,
          showSizeChanger: true,
          showTotal: (total) => `共 ${total} 条`,
          onChange: (nextPage, nextSize) => { setPage(nextPage - 1); setSize(nextSize); },
        }}
      />

      <Modal
        title="从消息证据新建客户跟进"
        open={Boolean(sourceSubmissionId)}
        footer={null}
        onCancel={closeCreate}
        destroyOnHidden
      >
        {writeError ? <Alert type="error" showIcon message={errorMessage(writeError)} style={{ marginBottom: 16 }} /> : null}
        <Form<BusinessFollowUpCreateInput>
          key={sourceSubmissionId}
          layout="vertical"
          initialValues={{ message_submission_id: sourceSubmissionId ?? '' }}
          onFinish={create}
        >
          <Form.Item name="message_submission_id" label="已选消息证据" rules={[{ required: true }]}>
            <Input readOnly />
          </Form.Item>
          <Form.Item
            name="employee_draft"
            label="员工大体草稿"
            extra="自动核对需包含唯一 Kehuzx 客户编号（格式 KH-YYMMDD-NNN）；自由文本不会发送给模型。"
            rules={[{ required: true }, { max: 20000 }]}
          >
            <Input.TextArea
              rows={6}
              maxLength={20000}
              showCount
              placeholder="例如：客户 KH-260826-001 希望确认样品和后续订单"
            />
          </Form.Item>
          <Button type="primary" htmlType="submit" loading={saving}>先建档，不运行模型</Button>
        </Form>
      </Modal>

      <Modal
        title="由 +1 发起整理"
        open={Boolean(organizeTarget)}
        footer={null}
        onCancel={() => setOrganizeTarget(null)}
        destroyOnHidden
        forceRender
      >
        {writeError ? <Alert type="error" showIcon message={errorMessage(writeError)} style={{ marginBottom: 16 }} /> : null}
        {agents.error ? (
          <Alert
            type="error"
            showIcon
            message="Agent 目录加载失败"
            action={<Button size="small" onClick={agents.reload}>重试</Button>}
            style={{ marginBottom: 16 }}
          />
        ) : null}
        {!agents.loading && !agents.error && agentOptions.length === 0 ? (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="当前没有可用的 Agent 版本" />
        ) : null}
        <Form<{ agent: string }> form={organizeForm} layout="vertical" onFinish={organize}>
          <Form.Item name="agent" label="当前启用的 Agent 版本" rules={[{ required: true }]}>
            <Select
              loading={agents.loading}
              disabled={agents.loading || Boolean(agents.error) || agentOptions.length === 0}
              placeholder="请选择，不自动默认"
              options={agentOptions}
            />
          </Form.Item>
          <Button
            type="primary"
            htmlType="submit"
            icon={<RobotOutlined />}
            loading={saving}
            disabled={agents.loading || Boolean(agents.error) || agentOptions.length === 0}
          >
            确认发起异步整理
          </Button>
        </Form>
      </Modal>

      <Drawer
        title={detail.data?.followup_no ?? '客户跟进详情'}
        open={Boolean(selectedId)}
        onClose={() => setSelectedId(null)}
        width={680}
      >
        {detail.loading ? (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="详情加载中…" />
        ) : detail.error ? (
          <Alert
            type="error"
            showIcon
            message="客户跟进详情加载失败"
            description={errorMessage(detail.error)}
            action={<Button size="small" onClick={detail.reload}>重试</Button>}
          />
        ) : detail.data ? <FollowUpDetail detail={detail.data} /> : null}
      </Drawer>
    </PageShell>
  );
}

function FollowUpDetail({ detail }: { detail: BusinessFollowUp }) {
  return (
    <Space direction="vertical" size={20} style={{ width: '100%' }}>
      <Descriptions
        size="small"
        column={2}
        items={[
          { key: 'stage', label: '业务阶段', children: STAGE_LABELS[detail.stage] },
          { key: 'processing', label: 'Agent 处理', children: PROCESSING_LABELS[detail.processing_status] },
          { key: 'source', label: '来源提交', children: detail.message_submission_id },
          { key: 'revision', label: '证据版本', children: detail.source_revision },
          { key: 'reviewer', label: '指定 +1', children: detail.designated_reviewer ?? '—' },
          { key: 'task', label: '任务结局', children: detail.task_status ?? '未发起' },
        ]}
      />
      {detail.task_failure_code ? (
        <Alert type="warning" showIcon message={`稳定错误码：${detail.task_failure_code}`} />
      ) : null}
      <div>
        <Typography.Text strong>员工大体草稿</Typography.Text>
        <Typography.Paragraph style={{ whiteSpace: 'pre-wrap', marginTop: 8 }}>
          {detail.employee_draft}
        </Typography.Paragraph>
      </div>
      {detail.latest_draft ? <DraftEvidence draft={detail.latest_draft} /> : (
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="尚未生成整理草稿" />
      )}
    </Space>
  );
}

function DraftEvidence({ draft }: { draft: NonNullable<BusinessFollowUp['latest_draft']> }) {
  const calls = draft.kehuzx_source_summary.calls ?? [];
  const failures = draft.kehuzx_source_summary.failures ?? [];
  const facts = draft.content.facts ?? [];
  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Card
        size="small"
        title={draft.content.title || `整理草稿 v${draft.version}`}
        extra={<Tag color={draft.status === 'DRAFT' ? 'green' : 'orange'}>{draft.status}</Tag>}
      >
        <Typography.Paragraph>{draft.content.summary || '暂无摘要'}</Typography.Paragraph>
        {draft.content.agent_suggestion ? (
          <Alert
            type="info"
            showIcon
            message="Agent 建议（未核实，不作为 Kehuzx 事实）"
            description={draft.content.agent_suggestion}
            style={{ marginBottom: 12 }}
          />
        ) : null}
        <List
          size="small"
          dataSource={facts}
          locale={{ emptyText: '暂无可核对事实' }}
          renderItem={(fact) => (
            <List.Item>
              <Tag color={fact.source === 'ZIMU' ? 'blue' : 'purple'}>{fact.source}</Tag>
              <Typography.Text strong>{fact.label}：</Typography.Text>
              <Typography.Text>{fact.value}</Typography.Text>
            </List.Item>
          )}
        />
        {draft.content.missing_fields?.length ? (
          <Alert
            type="warning"
            showIcon
            message="仍需人工补充"
            description={draft.content.missing_fields.join('、')}
            style={{ marginTop: 12 }}
          />
        ) : null}
      </Card>

      <Card size="small" title="来源对账">
        {failures.length ? (
          <Alert
            type="warning"
            showIcon
            message="Kehuzx 读取未完成，草稿不可确认"
            description={`稳定错误码：${failures.join('、')}`}
            style={{ marginBottom: 12 }}
          />
        ) : null}
        <Descriptions
          size="small"
          column={1}
          items={[
            {
              key: 'zimu',
              label: <Tag color="blue">ZIMU</Tag>,
              children: `跟进 ${draft.zimu_source_summary.followup_no} · 提交 ${draft.zimu_source_summary.message_submission_id} · 证据版本 ${draft.zimu_source_summary.source_revision}`,
            },
            {
              key: 'kehuzx',
              label: <Tag color="purple">KEHUZX</Tag>,
              children: `客户候选 ${draft.kehuzx_source_summary.candidate_count} 个 · 远端读取 ${calls.length} 次`,
            },
          ]}
        />
        <List
          size="small"
          dataSource={calls}
          locale={{ emptyText: '没有成功的 Kehuzx 远端读取；需人工复核' }}
          renderItem={(call) => (
            <List.Item>
              <Space direction="vertical" size={2}>
                <Typography.Text code>{call.tool}</Typography.Text>
                <Typography.Text type="secondary">
                  契约 {call.contract_version} · 提交 {call.upstream_commit || '未配置'} · 查询 {call.queried_at}
                </Typography.Text>
                <Typography.Text type="secondary">响应摘要 {call.response_digest}</Typography.Text>
              </Space>
            </List.Item>
          )}
        />
        {draft.upstream_refs.length ? (
          <Typography.Paragraph type="secondary" style={{ marginTop: 12, marginBottom: 0 }}>
            上游引用：{draft.upstream_refs.map((ref) => `${ref.entity_type}:${ref.id}`).join('；')}
          </Typography.Paragraph>
        ) : null}
      </Card>
      <Typography.Text type="secondary">Agent run：{draft.agent_run_id}</Typography.Text>
    </Space>
  );
}
