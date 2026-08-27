import { useEffect, useState } from 'react';
import { App, Alert, Button, Card, Descriptions, Drawer, Empty, Form, Input, InputNumber, List, Modal, Select, Space, Tag, Typography } from 'antd';
import { DeleteOutlined, FileSearchOutlined, PlusOutlined, ReloadOutlined, RobotOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import type { Rule } from 'antd/es/form';
import { useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { agentsApi, businessFollowUpsApi, operatorsApi } from '@/api/endpoints';
import { errorMessage } from '@/api/client';
import type {
  BusinessFollowUp,
  BusinessFollowUpBusinessKind,
  BusinessFollowUpCommercialTerms,
  BusinessFollowUpCreateInput,
  BusinessFollowUpDecisionInput,
  BusinessFollowUpFormalExecutionPlan,
  BusinessFollowUpSampleExecutionPlan,
  BusinessFollowUpSummary,
} from '@/api/types';
import DataTable from '@/components/DataTable';
import PageShell from '@/components/PageShell';
import { BUSINESS_FOLLOWUP_KIND_LABELS } from '@/constants/labels';
import { useAsync } from '@/hooks/useAsync';
import {
  buildBusinessFollowUpCreateInput,
  executionPlanSizeError,
  formalItemCountError,
  isValidExecutionDecimal,
  isValidExecutionInteger,
  isValidIsoDate,
  MAX_DECIMAL_QUANTITY,
  MAX_FORMAL_ITEMS,
  MAX_INTEGER_QUANTITY,
  type BusinessFollowUpCreateFormValues,
} from './businessFollowUpExecutionPlan';

const BUSINESS_KIND_OPTIONS = (Object.entries(BUSINESS_FOLLOWUP_KIND_LABELS) as Array<
  [BusinessFollowUpBusinessKind, string]
>).map(([value, label]) => ({ value, label }));

const COMMERCIAL_TERM_FIELDS: ReadonlyArray<{
  key: keyof BusinessFollowUpCommercialTerms;
  label: string;
}> = [
  { key: 'payment_terms', label: '付款条款' },
  { key: 'reconciliation_date', label: '对账日期说明' },
  { key: 'payment_date', label: '付款日期说明' },
  { key: 'credit_days', label: '账期天数说明' },
  { key: 'invoice_requirement', label: '开票要求' },
  { key: 'moq', label: '最小起订量说明' },
  { key: 'quoted_price', label: '报价说明' },
  { key: 'target_price', label: '目标价说明' },
  { key: 'remark', label: '商务条款备注' },
];

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

const requiredTextRules = (label: string, max: number): Rule[] => [
  { required: true, whitespace: true, message: `请输入${label}` },
  { max, message: `${label}不能超过 ${max} 个字符` },
];

const optionalTextRules = (label: string, max: number): Rule[] => [
  { max, message: `${label}不能超过 ${max} 个字符` },
  {
    validator: (_, value: unknown) => {
      if (value === undefined || value === null || value === '') return Promise.resolve();
      return typeof value === 'string' && value.trim().length > 0
        ? Promise.resolve()
        : Promise.reject(new Error(`${label}不能只填写空格`));
    },
  },
];

const isoDateRules = (label: string, required: boolean): Rule[] => [
  ...(required ? [{ required: true, message: `请选择${label}` } satisfies Rule] : []),
  {
    validator: (_, value: unknown) => {
      if (!required && (value === undefined || value === null || value === '')) return Promise.resolve();
      if (!isValidIsoDate(value)) {
        return Promise.reject(new Error(`${label}必须是有效的 ISO 日期（YYYY-MM-DD）`));
      }
      return Promise.resolve();
    },
  },
];

const positiveDecimalRules = (label: string): Rule[] => [
  { required: true, message: `请输入${label}` },
  {
    validator: (_, value: unknown) => {
      if (typeof value !== 'number' || !Number.isFinite(value) || value <= 0) {
        return Promise.reject(new Error(`${label}必须是正数`));
      }
      if (value > MAX_DECIMAL_QUANTITY) {
        return Promise.reject(new Error(`${label}不能超过 ${MAX_DECIMAL_QUANTITY}`));
      }
      return isValidExecutionDecimal(value)
        ? Promise.resolve()
        : Promise.reject(new Error(`${label}最多保留 3 位小数`));
    },
  },
];

const positiveIntegerRules = (label: string): Rule[] => [
  { required: true, message: `请输入${label}` },
  {
    validator: (_, value: unknown) => isValidExecutionInteger(value)
      ? Promise.resolve()
      : Promise.reject(new Error(`${label}必须是 1..${MAX_INTEGER_QUANTITY} 的整数`)),
  },
];

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
  const [businessKind, setBusinessKind] = useState<BusinessFollowUpBusinessKind>('CUSTOMER');
  const [planSizeError, setPlanSizeError] = useState<string | null>(null);
  const [createForm] = Form.useForm<BusinessFollowUpCreateFormValues>();
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

  const closeCreate = () => {
    setSearchParams({});
    setBusinessKind('CUSTOMER');
    setPlanSizeError(null);
    setWriteError(null);
    createForm.resetFields();
  };

  const create = async (values: BusinessFollowUpCreateFormValues) => {
    const sizeError = executionPlanSizeError(values);
    if (sizeError) {
      setPlanSizeError(sizeError);
      return;
    }
    const input: BusinessFollowUpCreateInput = buildBusinessFollowUpCreateInput(values);
    setSaving(true);
    setWriteError(null);
    try {
      await businessFollowUpsApi.create(input);
      closeCreate();
      list.reload();
      message.success('客户跟进材料已建档');
    } catch (error) {
      setWriteError(error instanceof Error ? error : new Error(String(error)));
    } finally {
      setSaving(false);
    }
  };

  const changeBusinessKind = (kind: BusinessFollowUpBusinessKind) => {
    setBusinessKind(kind);
    setPlanSizeError(null);
    createForm.setFieldValue(
      'execution_plan',
      kind === 'FORMAL' ? { items: [{}] } : undefined,
    );
  };

  const checkPlanSize = (_: unknown, values: BusinessFollowUpCreateFormValues) => {
    setPlanSizeError(executionPlanSizeError(values));
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
        <Form<BusinessFollowUpCreateFormValues>
          form={createForm}
          key={sourceSubmissionId}
          layout="vertical"
          initialValues={{
            message_submission_id: sourceSubmissionId ?? '',
            business_kind: 'CUSTOMER',
          }}
          onValuesChange={checkPlanSize}
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
          <Form.Item
            name="business_kind"
            label="业务类型"
            extra="由业务员明确选择；普通跟进不会生成可执行计划。"
            rules={[{ required: true, message: '请选择业务类型' }]}
          >
            <Select
              options={BUSINESS_KIND_OPTIONS}
              onChange={changeBusinessKind}
            />
          </Form.Item>
          {businessKind === 'SAMPLE' ? <SampleExecutionPlanFields /> : null}
          {businessKind === 'FORMAL' ? <FormalExecutionPlanFields /> : null}
          {planSizeError ? (
            <Alert type="error" showIcon message={planSizeError} style={{ marginBottom: 16 }} />
          ) : null}
          <Button type="primary" htmlType="submit" loading={saving} disabled={Boolean(planSizeError)}>
            先建档，不运行模型
          </Button>
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

function SampleExecutionPlanFields() {
  return (
    <Card size="small" title="样品执行计划" style={{ marginBottom: 16 }}>
      <Form.Item
        name={['execution_plan', 'sample_name']}
        label="样品名称"
        rules={requiredTextRules('样品名称', 200)}
      >
        <Input maxLength={200} showCount />
      </Form.Item>
      <Form.Item
        name={['execution_plan', 'product_name']}
        label="商品名称"
        rules={requiredTextRules('商品名称', 200)}
      >
        <Input maxLength={200} showCount />
      </Form.Item>
      <Space align="start" wrap style={{ width: '100%' }}>
        <Form.Item
          name={['execution_plan', 'quantity_per_unit']}
          label="每单位数量"
          rules={positiveDecimalRules('每单位数量')}
        >
          <InputNumber min={0.001} max={MAX_DECIMAL_QUANTITY} step={0.001} style={{ width: 180 }} />
        </Form.Item>
        <Form.Item
          name={['execution_plan', 'quantity_unit']}
          label="数量单位"
          rules={requiredTextRules('数量单位', 30)}
        >
          <Input maxLength={30} placeholder="例如 kg、箱" style={{ width: 150 }} />
        </Form.Item>
        <Form.Item
          name={['execution_plan', 'unit_count']}
          label="单位份数"
          rules={positiveIntegerRules('单位份数')}
        >
          <InputNumber min={1} max={MAX_INTEGER_QUANTITY} precision={0} style={{ width: 150 }} />
        </Form.Item>
      </Space>
      <Space align="start" wrap style={{ width: '100%' }}>
        <Form.Item
          name={['execution_plan', 'requested_date']}
          label="需求日期"
          rules={isoDateRules('需求日期', true)}
        >
          <Input type="date" style={{ width: 180 }} />
        </Form.Item>
        <Form.Item
          name={['execution_plan', 'expected_delivery_date']}
          label="期望送达日期"
          rules={isoDateRules('期望送达日期', false)}
        >
          <Input type="date" style={{ width: 180 }} />
        </Form.Item>
        <Form.Item
          name={['execution_plan', 'testing_date']}
          label="测试日期"
          rules={isoDateRules('测试日期', false)}
        >
          <Input type="date" style={{ width: 180 }} />
        </Form.Item>
      </Space>
      <Form.Item
        name={['execution_plan', 'specification']}
        label="规格说明"
        rules={optionalTextRules('规格说明', 4000)}
      >
        <Input.TextArea rows={2} maxLength={4000} showCount />
      </Form.Item>
      <Form.Item
        name={['execution_plan', 'requirements']}
        label="样品要求"
        rules={optionalTextRules('样品要求', 4000)}
      >
        <Input.TextArea rows={2} maxLength={4000} showCount />
      </Form.Item>
      <Form.Item
        name={['execution_plan', 'remark']}
        label="样品备注"
        rules={optionalTextRules('样品备注', 4000)}
      >
        <Input.TextArea rows={2} maxLength={4000} showCount />
      </Form.Item>
      <Form.Item
        name={['execution_plan', 'business_note']}
        label="业务说明"
        rules={optionalTextRules('业务说明', 4000)}
      >
        <Input.TextArea rows={2} maxLength={4000} showCount />
      </Form.Item>
      <CommercialTermsFields />
    </Card>
  );
}

function FormalExecutionPlanFields() {
  return (
    <Card size="small" title="正式订单执行计划" style={{ marginBottom: 16 }}>
      <Form.Item
        name={['execution_plan', 'name']}
        label="订单名称"
        rules={requiredTextRules('订单名称', 200)}
      >
        <Input maxLength={200} showCount />
      </Form.Item>
      <Form.Item
        name={['execution_plan', 'delivery_date']}
        label="交付日期"
        rules={isoDateRules('交付日期', true)}
      >
        <Input type="date" style={{ width: 180 }} />
      </Form.Item>
      <Form.Item
        name={['execution_plan', 'delivery_address']}
        label="交付地址"
        rules={requiredTextRules('交付地址', 500)}
      >
        <Input.TextArea rows={3} maxLength={500} showCount />
      </Form.Item>
      <Space align="start" wrap style={{ width: '100%' }}>
        <Form.Item
          name={['execution_plan', 'settlement_period']}
          label="结算周期"
          rules={optionalTextRules('结算周期', 100)}
        >
          <Input maxLength={100} style={{ width: 220 }} />
        </Form.Item>
        <Form.Item
          name={['execution_plan', 'settlement_method']}
          label="结算方式"
          rules={optionalTextRules('结算方式', 100)}
        >
          <Input maxLength={100} style={{ width: 220 }} />
        </Form.Item>
      </Space>
      <Form.Item
        name={['execution_plan', 'business_note']}
        label="业务说明"
        rules={optionalTextRules('业务说明', 4000)}
      >
        <Input.TextArea rows={2} maxLength={4000} showCount />
      </Form.Item>
      <Form.List
        name={['execution_plan', 'items']}
        rules={[{
          validator: async (_, items: unknown) => {
            const error = formalItemCountError(items);
            if (error) throw new Error(error);
          },
        }]}
      >
        {(fields, { add, remove }, { errors }) => (
          <Space direction="vertical" size={12} style={{ width: '100%', marginBottom: 16 }}>
            <Typography.Text strong>商品明细（至少 1 行，最多 {MAX_FORMAL_ITEMS} 行）</Typography.Text>
            {fields.map((field, index) => (
              <Card
                key={field.key}
                size="small"
                title={`商品明细 ${index + 1}`}
                extra={(
                  <Button
                    type="text"
                    danger
                    icon={<DeleteOutlined />}
                    aria-label={`删除商品明细 ${index + 1}`}
                    disabled={fields.length <= 1}
                    onClick={() => remove(field.name)}
                  />
                )}
              >
                <Form.Item
                  name={[field.name, 'product_name']}
                  label="商品名称"
                  rules={requiredTextRules('商品名称', 200)}
                >
                  <Input maxLength={200} showCount />
                </Form.Item>
                <Space align="start" wrap style={{ width: '100%' }}>
                  <Form.Item
                    name={[field.name, 'quantity_per_unit']}
                    label="每单位数量"
                    rules={positiveDecimalRules('每单位数量')}
                  >
                    <InputNumber min={0.001} max={MAX_DECIMAL_QUANTITY} step={0.001} style={{ width: 170 }} />
                  </Form.Item>
                  <Form.Item
                    name={[field.name, 'quantity_unit']}
                    label="数量单位"
                    rules={requiredTextRules('数量单位', 30)}
                  >
                    <Input maxLength={30} style={{ width: 140 }} />
                  </Form.Item>
                  <Form.Item
                    name={[field.name, 'unit_count']}
                    label="单位份数"
                    rules={positiveIntegerRules('单位份数')}
                  >
                    <InputNumber min={1} max={MAX_INTEGER_QUANTITY} precision={0} style={{ width: 140 }} />
                  </Form.Item>
                </Space>
              </Card>
            ))}
            <Button
              type="dashed"
              icon={<PlusOutlined />}
              disabled={fields.length >= MAX_FORMAL_ITEMS}
              onClick={() => add({})}
            >
              添加商品明细
            </Button>
            <Form.ErrorList errors={errors} />
          </Space>
        )}
      </Form.List>
      <CommercialTermsFields />
    </Card>
  );
}

function CommercialTermsFields() {
  return (
    <Card size="small" type="inner" title="商务条款（选填）">
      {COMMERCIAL_TERM_FIELDS.map(({ key, label }) => (
        <Form.Item
          key={key}
          name={['execution_plan', 'commercial_terms', key]}
          label={label}
          rules={optionalTextRules(label, 4000)}
        >
          <Input.TextArea rows={2} maxLength={4000} showCount />
        </Form.Item>
      ))}
    </Card>
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
          {
            key: 'businessKind',
            label: '业务类型',
            children: BUSINESS_FOLLOWUP_KIND_LABELS[detail.business_kind] ?? detail.business_kind ?? '普通跟进',
          },
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
      <ExecutionPlanDetail detail={detail} />
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
      <Card size="small" title="后续 Assignment">
        <List
          size="small"
          dataSource={detail.assignments ?? []}
          locale={{ emptyText: '尚无已投影的后续任务' }}
          renderItem={(assignment) => (
            <List.Item>
              <Space direction="vertical" size={2} style={{ width: '100%' }}>
                <Space wrap>
                  <Typography.Text strong>{assignment.task_type}</Typography.Text>
                  <Tag color={assignmentStatusColor(assignment.status)}>{assignment.status}</Tag>
                  <Tag>{assignment.priority}</Tag>
                </Space>
                <Typography.Text>{assignment.logical_target}</Typography.Text>
                <Typography.Text type="secondary">
                  Approval {assignment.approval_id} · 草稿 v{assignment.draft_version}
                  {' · '}Agent run {assignment.agent_run_id}
                  {' · '}确认人 {assignment.confirmed_by ?? '—'}
                </Typography.Text>
                <Typography.Text type="secondary">
                  {assignment.assignee_type} · {assignment.assignee_ref} · 优先级 {assignment.priority}
                  {' · '}截止 {assignment.due_at ?? '未设定'}
                </Typography.Text>
                <Typography.Text type={assignment.status === 'FAILED'
                  || assignment.status === 'RECONCILIATION_REQUIRED' ? 'danger' : 'secondary'}>
                  请求 {assignment.request_id ?? '—'}
                  {' · '}Payload {assignment.payload_hash ?? '—'}
                  {' · '}外部结果 {assignment.external_entity_type && assignment.external_entity_id
                    ? `${assignment.external_entity_type}:${assignment.external_entity_id}` : '—'}
                  {' · '}{assignment.result_code ?? '尚无结果码'}
                </Typography.Text>
                <Typography.Text type="secondary">
                  执行任务 {assignment.execution_task_key ?? '—'} · Assignment {assignment.id}
                </Typography.Text>
              </Space>
            </List.Item>
          )}
        />
      </Card>
    </Space>
  );
}

function ExecutionPlanDetail({ detail }: { detail: BusinessFollowUp }) {
  if (detail.business_kind === 'CUSTOMER' || !detail.business_kind) {
    return (
      <Card size="small" title="结构化执行计划">
        <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="普通跟进不生成结构化执行计划" />
      </Card>
    );
  }
  const plan = detail.execution_plan;
  if (!plan) {
    return (
      <Alert
        type="warning"
        showIcon
        message={`${BUSINESS_FOLLOWUP_KIND_LABELS[detail.business_kind]}缺少结构化执行计划`}
      />
    );
  }
  return 'order_type' in plan
    ? <FormalExecutionPlanDetail plan={plan} />
    : <SampleExecutionPlanDetail plan={plan} />;
}

function SampleExecutionPlanDetail({ plan }: { plan: BusinessFollowUpSampleExecutionPlan }) {
  const optionalItems = [
    { key: 'expectedDelivery', label: '期望送达日期', value: plan.expected_delivery_date },
    { key: 'testing', label: '测试日期', value: plan.testing_date },
    { key: 'specification', label: '规格说明', value: plan.specification },
    { key: 'requirements', label: '样品要求', value: plan.requirements },
    { key: 'remark', label: '样品备注', value: plan.remark },
    { key: 'businessNote', label: '业务说明', value: plan.business_note },
  ].filter((item) => item.value);
  return (
    <Card size="small" title="样品执行计划（只读）">
      <Descriptions
        size="small"
        column={1}
        items={[
          { key: 'sampleName', label: '样品名称', children: plan.sample_name },
          { key: 'productName', label: '商品名称', children: plan.product_name },
          {
            key: 'quantity',
            label: '数量',
            children: `${plan.quantity_per_unit} ${plan.quantity_unit} × ${plan.unit_count} 份`,
          },
          { key: 'requestedDate', label: '需求日期', children: plan.requested_date },
          ...optionalItems.map((item) => ({
            key: item.key,
            label: item.label,
            children: item.value,
          })),
        ]}
      />
      <CommercialTermsDetail terms={plan.commercial_terms} />
    </Card>
  );
}

function FormalExecutionPlanDetail({ plan }: { plan: BusinessFollowUpFormalExecutionPlan }) {
  return (
    <Card size="small" title="正式订单执行计划（只读）">
      <Descriptions
        size="small"
        column={1}
        items={[
          { key: 'name', label: '订单名称', children: plan.name },
          { key: 'deliveryDate', label: '交付日期', children: plan.delivery_date },
          { key: 'deliveryAddress', label: '交付地址', children: plan.delivery_address },
          ...(plan.settlement_period
            ? [{ key: 'settlementPeriod', label: '结算周期', children: plan.settlement_period }]
            : []),
          ...(plan.settlement_method
            ? [{ key: 'settlementMethod', label: '结算方式', children: plan.settlement_method }]
            : []),
          ...(plan.business_note
            ? [{ key: 'businessNote', label: '业务说明', children: plan.business_note }]
            : []),
        ]}
      />
      <List
        header={<Typography.Text strong>商品明细（{plan.items.length} 行）</Typography.Text>}
        size="small"
        dataSource={plan.items}
        renderItem={(item, index) => (
          <List.Item>
            <Space direction="vertical" size={2}>
              <Typography.Text strong>商品明细 {index + 1} · {item.product_name}</Typography.Text>
              <Typography.Text type="secondary">
                {item.quantity_per_unit} {item.quantity_unit} × {item.unit_count} 份
              </Typography.Text>
            </Space>
          </List.Item>
        )}
      />
      <CommercialTermsDetail terms={plan.commercial_terms} />
    </Card>
  );
}

function CommercialTermsDetail({ terms }: { terms?: BusinessFollowUpCommercialTerms }) {
  if (!terms) return null;
  const items = COMMERCIAL_TERM_FIELDS
    .map(({ key, label }) => ({ key, label, value: terms[key] }))
    .filter((item) => item.value)
    .map((item) => ({ key: item.key, label: item.label, children: item.value }));
  if (!items.length) return null;
  return (
    <Card size="small" type="inner" title="商务条款" style={{ marginTop: 12 }}>
      <Descriptions size="small" column={1} items={items} />
    </Card>
  );
}

function assignmentStatusColor(status: NonNullable<BusinessFollowUp['assignments']>[number]['status']) {
  if (status === 'SUCCEEDED') return 'green';
  if (status === 'FAILED' || status === 'RECONCILIATION_REQUIRED') return 'red';
  if (status === 'WAITING_HUMAN') return 'orange';
  return 'blue';
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
