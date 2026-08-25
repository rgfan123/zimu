import { useState } from 'react';
import { App, Alert, Button, Descriptions, Drawer, Form, Input, InputNumber, Modal, Select, Space, Tag, Typography } from 'antd';
import { PlusOutlined, ReloadOutlined, RobotOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { agentsApi, businessFollowUpsApi } from '@/api/endpoints';
import { errorMessage } from '@/api/client';
import type { BusinessFollowUp, BusinessFollowUpCreateInput } from '@/api/types';
import DataTable from '@/components/DataTable';
import PageShell from '@/components/PageShell';
import { useAsync } from '@/hooks/useAsync';

const STAGE_LABELS: Record<BusinessFollowUp['stage'], string> = {
  PENDING_ORGANIZATION: '待发起整理',
  ORGANIZING: '整理中',
  DRAFT_READY: '草稿待核对',
};

const PROCESSING_LABELS: Record<BusinessFollowUp['processing_status'], string> = {
  NOT_STARTED: '未发起',
  PENDING: '排队中',
  RUNNING: '处理中',
  SUCCEEDED: '已完成',
  FAILED: '失败',
};

export default function BusinessFollowUpsPage() {
  const { message } = App.useApp();
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [selected, setSelected] = useState<BusinessFollowUp | null>(null);
  const [createOpen, setCreateOpen] = useState(false);
  const [organizeTarget, setOrganizeTarget] = useState<BusinessFollowUp | null>(null);
  const [saving, setSaving] = useState(false);
  const [writeError, setWriteError] = useState<Error | null>(null);
  const list = useAsync(() => businessFollowUpsApi.list({ page, size }), [page, size]);
  const agents = useAsync(() => agentsApi.list(), []);

  const create = async (values: BusinessFollowUpCreateInput) => {
    setSaving(true);
    setWriteError(null);
    try {
      await businessFollowUpsApi.create(values);
      setCreateOpen(false);
      list.reload();
      message.success('客户跟进材料已建档');
    } catch (error) {
      setWriteError(error instanceof Error ? error : new Error(String(error)));
    } finally {
      setSaving(false);
    }
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
    } finally {
      setSaving(false);
    }
  };

  const columns: ColumnsType<BusinessFollowUp> = [
    { title: '跟进编号', dataIndex: 'followup_no', width: 150 },
    { title: '来源提交', dataIndex: 'message_submission_id', width: 110 },
    { title: '证据版本', dataIndex: 'source_revision', width: 90 },
    {
      title: '业务阶段',
      dataIndex: 'stage',
      width: 120,
      render: (value: BusinessFollowUp['stage']) => <Tag color="blue">{STAGE_LABELS[value]}</Tag>,
    },
    {
      title: 'Agent 处理',
      dataIndex: 'processing_status',
      width: 110,
      render: (value: BusinessFollowUp['processing_status']) => (
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
      width: 150,
      render: (_, row) => (
        <Space>
          <Typography.Link onClick={() => setSelected(row)}>详情</Typography.Link>
          {row.stage === 'PENDING_ORGANIZATION' ? (
            <Typography.Link onClick={() => { setWriteError(null); setOrganizeTarget(row); }}>
              发起整理
            </Typography.Link>
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
          <Button type="primary" icon={<PlusOutlined />} onClick={() => { setWriteError(null); setCreateOpen(true); }}>
            新建跟进
          </Button>
        </Space>
      )}
    >
      <DataTable<BusinessFollowUp>
        rowKey="id"
        columns={columns}
        dataSource={list.data?.items ?? []}
        loading={list.loading}
        error={list.error}
        errorTitle="客户跟进加载失败"
        onRetry={list.reload}
        scroll={{ x: 1100 }}
        pagination={{
          current: page + 1,
          pageSize: size,
          total: list.data?.total_elements ?? 0,
          showSizeChanger: true,
          showTotal: (total) => `共 ${total} 条`,
          onChange: (nextPage, nextSize) => { setPage(nextPage - 1); setSize(nextSize); },
        }}
      />

      <Modal title="新建客户跟进" open={createOpen} footer={null} onCancel={() => setCreateOpen(false)} destroyOnClose>
        {writeError ? <Alert type="error" showIcon message={errorMessage(writeError)} style={{ marginBottom: 16 }} /> : null}
        <Form<BusinessFollowUpCreateInput> layout="vertical" onFinish={create}>
          <Form.Item name="message_submission_id" label="来源消息提交 ID" rules={[{ required: true }]}>
            <InputNumber min={1} precision={0} style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="employee_draft" label="员工大体草稿" rules={[{ required: true }, { max: 20000 }]}>
            <Input.TextArea rows={6} maxLength={20000} showCount />
          </Form.Item>
          <Button type="primary" htmlType="submit" loading={saving}>先建档，不运行模型</Button>
        </Form>
      </Modal>

      <Modal title="由 +1 发起整理" open={Boolean(organizeTarget)} footer={null} onCancel={() => setOrganizeTarget(null)} destroyOnClose>
        {writeError ? <Alert type="error" showIcon message={errorMessage(writeError)} style={{ marginBottom: 16 }} /> : null}
        <Form<{ agent: string }> layout="vertical" onFinish={organize}>
          <Form.Item name="agent" label="当前启用的 Agent 版本" rules={[{ required: true }]}>
            <Select
              loading={agents.loading}
              placeholder="请选择，不自动默认"
              options={(agents.data?.items ?? [])
                .filter((agent) => agent.state === 'RUNNING' && agent.current_version !== null)
                .map((agent) => ({
                  value: `${agent.slug}@${agent.current_version}`,
                  label: `${agent.name} · ${agent.slug} · v${agent.current_version}`,
                }))}
            />
          </Form.Item>
          <Button type="primary" htmlType="submit" icon={<RobotOutlined />} loading={saving}>
            确认发起异步整理
          </Button>
        </Form>
      </Modal>

      <Drawer title={selected?.followup_no ?? '客户跟进详情'} open={Boolean(selected)} onClose={() => setSelected(null)} width={680}>
        {selected ? (
          <Space direction="vertical" size={20} style={{ width: '100%' }}>
            <Descriptions
              size="small"
              column={2}
              items={[
                { key: 'stage', label: '业务阶段', children: STAGE_LABELS[selected.stage] },
                { key: 'processing', label: 'Agent 处理', children: PROCESSING_LABELS[selected.processing_status] },
                { key: 'source', label: '来源提交', children: selected.message_submission_id },
                { key: 'revision', label: '证据版本', children: selected.source_revision },
                { key: 'reviewer', label: '指定 +1', children: selected.designated_reviewer ?? '—' },
                { key: 'task', label: '任务结局', children: selected.task_status ?? '未发起' },
              ]}
            />
            <div>
              <Typography.Text strong>员工大体草稿</Typography.Text>
              <Typography.Paragraph style={{ whiteSpace: 'pre-wrap', marginTop: 8 }}>
                {selected.employee_draft}
              </Typography.Paragraph>
            </div>
          </Space>
        ) : null}
      </Drawer>
    </PageShell>
  );
}
