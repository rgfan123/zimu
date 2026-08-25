import { useState } from 'react';
import { App, Alert, Button, Descriptions, Drawer, Empty, Form, Input, Modal, Select, Space, Tag, Typography } from 'antd';
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
  const list = useAsync(() => businessFollowUpsApi.list({ page, size }), [page, size]);
  const detail = useAsync(
    () => (selectedId ? businessFollowUpsApi.detail(selectedId) : Promise.resolve(null)),
    [selectedId],
  );
  const agents = useAsync(() => agentsApi.list(), []);
  const agentOptions = (agents.data?.items ?? [])
    .filter((agent) => agent.state === 'RUNNING' && agent.current_version !== null)
    .map((agent) => ({
      value: `${agent.slug}@${agent.current_version}`,
      label: `${agent.name} · ${agent.slug} · v${agent.current_version}`,
    }));

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
    agents.reload();
  };

  const organize = async (values: { agent: string }) => {
    if (!organizeTarget) return;
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
          <Form.Item name="employee_draft" label="员工大体草稿" rules={[{ required: true }, { max: 20000 }]}>
            <Input.TextArea rows={6} maxLength={20000} showCount />
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
        <Form<{ agent: string }> layout="vertical" onFinish={organize}>
          <Form.Item name="agent" label="当前启用的 Agent 版本" rules={[{ required: true }]}>
            <Select
              loading={agents.loading}
              disabled={Boolean(agents.error) || agentOptions.length === 0}
              placeholder="请选择，不自动默认"
              options={agentOptions}
            />
          </Form.Item>
          <Button
            type="primary"
            htmlType="submit"
            icon={<RobotOutlined />}
            loading={saving}
            disabled={Boolean(agents.error) || agentOptions.length === 0}
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
    </Space>
  );
}
