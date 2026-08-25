import { useEffect, useState } from 'react';
import { App, Alert, Button, Card, Descriptions, Drawer, Empty, Form, Input, List, Modal, Select, Space, Tag, Typography } from 'antd';
import { FileSearchOutlined, ReloadOutlined, RobotOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import { useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { agentsApi, businessFollowUpsApi, operatorsApi } from '@/api/endpoints';
import { errorMessage } from '@/api/client';
import type {
  BusinessFollowUp,
  BusinessFollowUpCreateInput,
  BusinessFollowUpDecisionInput,
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
  PENDING_APPROVAL: '待 +1 确认',
  CONFIRMED: '已确认',
  PAUSED: '暂不推进',
};

const PROCESSING_LABELS: Record<BusinessFollowUpSummary['processing_status'], string> = {
  NOT_STARTED: '未发起',
  PENDING: '排队中',
  RUNNING: '处理中',
  SUCCEEDED: '已完成',
  FAILED: '失败',
};

const DECISION_BY_QUERY: Record<string, BusinessFollowUpDecisionInput['decision']> = {
  redo: 'REDO',
  supplement: 'NEEDS_INPUT',
  pause: 'PAUSE',
};

const DECISION_LABELS: Record<BusinessFollowUpDecisionInput['decision'], string> = {
  CONFIRM: '确认',
  REDO: '让 Agent 重做',
  NEEDS_INPUT: '需要补充',
  PAUSE: '暂不推进',
};

export default function BusinessFollowUpsPage() {
  const { message } = App.useApp();
  const navigate = useNavigate();
  const location = useLocation();
  const [searchParams, setSearchParams] = useSearchParams();
  const sourceSubmissionId = searchParams.get('submission_id');
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const decisionQuery = searchParams.get('decision');
  const decision = decisionQuery ? DECISION_BY_QUERY[decisionQuery] : undefined;
  const linkedFollowupId = searchParams.get('followup_id');
  const expectedDraftVersion = Number(searchParams.get('expected_draft_version'));
  const decisionCapability = new URLSearchParams(location.hash.replace(/^#/, '')).get('capability');
  const validDecisionLink = Number.isSafeInteger(expectedDraftVersion)
    && expectedDraftVersion > 0
    && Boolean(decisionCapability);
  const [selectedId, setSelectedId] = useState<string | null>(linkedFollowupId);
  const [organizeTarget, setOrganizeTarget] = useState<BusinessFollowUpSummary | null>(null);
  const [saving, setSaving] = useState(false);
  const [writeError, setWriteError] = useState<Error | null>(null);
  const [organizeForm] = Form.useForm<{ agent: string; reviewer: string }>();
  const [decisionForm] = Form.useForm<{ reason: string }>();
  const list = useAsync(() => businessFollowUpsApi.list({ page, size }), [page, size]);
  const detail = useAsync(
    () => (selectedId ? businessFollowUpsApi.detail(selectedId) : Promise.resolve(null)),
    [selectedId],
  );
  const agents = useAsync(() => agentsApi.list(), []);
  const operators = useAsync(
    () => (organizeTarget
      ? operatorsApi.list({ page: 0, size: 200 })
      : Promise.resolve(null)),
    [organizeTarget?.id],
  );
  const agentOptions = (agents.data?.items ?? [])
    .filter((agent) => agent.slug === 'customer-followup-agent'
      && agent.state === 'RUNNING'
      && agent.current_version !== null)
    .map((agent) => ({
      value: `${agent.slug}@${agent.current_version}`,
      label: `${agent.name} · ${agent.slug} · v${agent.current_version}`,
    }));
  const reviewerOptions = (operators.data?.items ?? [])
    .filter((operator) => operator.active && Boolean(operator.wecom_userid))
    .map((operator) => ({
      value: operator.id,
      label: `${operator.display_name} · ${operator.responsible_team} · ${operator.wecom_userid}`,
    }));

  useEffect(() => {
    const selectedAgent = organizeForm.getFieldValue('agent');
    if (selectedAgent && !agentOptions.some((option) => option.value === selectedAgent)) {
      organizeForm.resetFields();
    }
  }, [agentOptions, organizeForm]);

  useEffect(() => {
    if (linkedFollowupId) setSelectedId(linkedFollowupId);
  }, [linkedFollowupId]);

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
    operators.reload();
  };

  const organize = async (values: { agent: string; reviewer: string }) => {
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
        reviewer_operator_id: values.reviewer,
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

  const closeDecision = () => {
    const next = new URLSearchParams(searchParams);
    next.delete('followup_id');
    next.delete('decision');
    next.delete('expected_draft_version');
    next.delete('capability');
    navigate({
      pathname: location.pathname,
      search: next.toString() ? `?${next.toString()}` : '',
      hash: '',
    }, { replace: true });
    decisionForm.resetFields();
  };

  const submitDecision = async (values: { reason: string }) => {
    if (!linkedFollowupId || !decision || !Number.isSafeInteger(expectedDraftVersion)
      || expectedDraftVersion < 1 || !decisionCapability) return;
    setSaving(true);
    setWriteError(null);
    try {
      await businessFollowUpsApi.decide(linkedFollowupId, {
        expected_draft_version: expectedDraftVersion,
        decision,
        reason: values.reason,
        capability: decisionCapability,
      });
      closeDecision();
      detail.reload();
      list.reload();
      message.success(`已受理：${DECISION_LABELS[decision]}`);
    } catch (error) {
      setWriteError(error instanceof Error ? error : new Error(String(error)));
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
        {operators.error ? (
          <Alert
            type="error"
            showIcon
            message="+1 审批人目录加载失败"
            action={<Button size="small" onClick={operators.reload}>重试</Button>}
            style={{ marginBottom: 16 }}
          />
        ) : null}
        {!agents.loading && !agents.error && agentOptions.length === 0 ? (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="当前没有可用的 Agent 版本" />
        ) : null}
        {!operators.loading && !operators.error && reviewerOptions.length === 0 ? (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="没有已绑定企微的 active +1 审批人" />
        ) : null}
        <Form<{ agent: string; reviewer: string }> form={organizeForm} layout="vertical" onFinish={organize}>
          <Form.Item name="agent" label="当前启用的 Agent 版本" rules={[{ required: true }]}>
            <Select
              loading={agents.loading}
              disabled={agents.loading || Boolean(agents.error) || agentOptions.length === 0}
              placeholder="请选择，不自动默认"
              options={agentOptions}
            />
          </Form.Item>
          <Form.Item name="reviewer" label="指定 +1 审批人" rules={[{ required: true }]}>
            <Select
              loading={operators.loading}
              disabled={operators.loading || Boolean(operators.error) || reviewerOptions.length === 0}
              placeholder="请选择已启用且已绑定企微的内部运营人员"
              options={reviewerOptions}
            />
          </Form.Item>
          <Button
            type="primary"
            htmlType="submit"
            icon={<RobotOutlined />}
            loading={saving}
            disabled={agents.loading || Boolean(agents.error) || agentOptions.length === 0
              || operators.loading || Boolean(operators.error) || reviewerOptions.length === 0}
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

      <Modal
        title={decision ? DECISION_LABELS[decision] : '提交客户跟进决定'}
        open={Boolean(linkedFollowupId && decision)}
        footer={null}
        onCancel={closeDecision}
        destroyOnHidden
      >
        {writeError ? <Alert type="error" showIcon message={errorMessage(writeError)} style={{ marginBottom: 16 }} /> : null}
        {!validDecisionLink ? (
          <Alert type="error" showIcon message="决定链接无效或缺少版本授权，请回到最新企微卡片重试" />
        ) : !detail.data?.latest_draft ? (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="正在读取当前草稿版本…" />
        ) : detail.data.latest_draft.version !== expectedDraftVersion ? (
          <Alert
            type="warning"
            showIcon
            message={`卡片草稿 v${expectedDraftVersion} 已被 v${detail.data.latest_draft.version} 取代`}
            description="该链接不能修改新版业务事实，请从最新企微卡片重新进入。"
          />
        ) : (
          <Form<{ reason: string }> form={decisionForm} layout="vertical" onFinish={submitDecision}>
            <Alert
              type="info"
              showIcon
              message={`跟进 ${detail.data.followup_no} · 草稿 v${detail.data.latest_draft.version}`}
              description="提交后只记录人工决定并进入异步处理；不会在当前请求内调用模型或外部写接口。"
              style={{ marginBottom: 16 }}
            />
            <Form.Item
              name="reason"
              label={decision === 'REDO' ? '重做反馈' : decision === 'NEEDS_INPUT' ? '需要补充什么' : '暂停原因'}
              rules={[{ required: true }, { max: 2000 }]}
            >
              <Input.TextArea rows={5} maxLength={2000} showCount />
            </Form.Item>
            <Button type="primary" htmlType="submit" loading={saving}>确认提交</Button>
          </Form>
        )}
      </Modal>
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
      <Card size="small" title="草稿版本轨迹">
        <List
          size="small"
          dataSource={detail.draft_versions ?? []}
          locale={{ emptyText: '尚无草稿版本' }}
          renderItem={(draft) => (
            <List.Item>
              <Space>
                <Tag>v{draft.version}</Tag>
                <Tag color={draft.status === 'CONFIRMED' ? 'green' : 'default'}>{draft.status}</Tag>
                <Typography.Text type="secondary">Agent run {draft.agent_run_id}</Typography.Text>
              </Space>
            </List.Item>
          )}
        />
      </Card>
      <Card size="small" title="+1 决策证据">
        <List
          size="small"
          dataSource={detail.approvals ?? []}
          locale={{ emptyText: '尚无 +1 决策' }}
          renderItem={(approval) => (
            <List.Item>
              <Space direction="vertical" size={2}>
                <Typography.Text strong>
                  v{approval.draft_version} · {approval.decision} · {approval.decided_by}
                </Typography.Text>
                <Typography.Text type="secondary">
                  来源 {approval.source_kind} · 事件 {approval.source_event_message_id ?? '—'} · 请求 {approval.request_id}
                </Typography.Text>
                {approval.reason ? <Typography.Text>原因/反馈：{approval.reason}</Typography.Text> : null}
                <Typography.Text type={approval.application_status === 'FAILED' ? 'danger' : 'secondary'}>
                  异步投影 {approval.application_status}
                  {approval.application_failure_code ? ` · ${approval.application_failure_code}` : ''}
                </Typography.Text>
              </Space>
            </List.Item>
          )}
        />
      </Card>
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
        extra={<Tag color={draft.status === 'READY' ? 'green' : 'orange'}>{draft.status}</Tag>}
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
        {draft.content.risks?.length ? (
          <Alert type="warning" showIcon message="风险" description={draft.content.risks.join('；')} style={{ marginTop: 12 }} />
        ) : null}
        {draft.content.recommended_actions?.length ? (
          <Alert type="info" showIcon message="建议后续动作" description={draft.content.recommended_actions.join('；')} style={{ marginTop: 12 }} />
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
