import { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Drawer,
  Empty,
  Input,
  Popconfirm,
  Segmented,
  Select,
  Space,
  Table,
  Tag,
  Typography,
  message,
} from 'antd';
import { CheckSquareOutlined, ReloadOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import dayjs from 'dayjs';
import { errorMessage } from '@/api/client';
import {
  customersApi,
  operationalAlertsApi,
  reviewCasesApi,
  shipmentsApi,
  skusApi,
} from '@/api/endpoints';
import type {
  OperationalAlert,
  OperationalAlertStatus,
  ReviewCase,
  ReviewCaseStatus,
} from '@/api/types';
import { useAsync } from '@/hooks/useAsync';
import {
  ATTENTION_COLORS,
  operationalAlertStatusSemantic,
  reviewCaseStatusSemantic,
  severitySemantic,
} from '@/pages/shared/semanticStatus';
import { saasVisualTokens } from '@/theme/saasTheme';
import {
  buildCustomerResolution,
  buildDismissCommand,
  buildManualResolution,
  buildSkuResolution,
  buildSourceFollowupCompletion,
  reviewAction,
} from './manualReviewActions';
import {
  jdSkuMappingReviewEvidence,
  rerunJdSkuMappingReview,
  jdSkuMappingRerunResultMessage,
  jdSkuMappingReviewPermissions,
} from './jdSkuMappingReview';
import OrderDraftReviewPanel from './OrderDraftReviewPanel';
import TrackingDraftReviewPanel from './TrackingDraftReviewPanel';
import {
  emptyMasterDataOptionState,
  hasMoreMasterDataOptions,
  loadMasterDataOptionPage,
  type MasterDataOptionLoader,
  type MasterDataOptionState,
} from './orderDraftMasterData';
import {
  isSkuMappingReasonCode,
  safeReviewDetailRows,
  skuMappingDetailRows,
  skuMappingEvidenceCell,
  skuMappingEvidenceItems,
} from '@/presentation/publicReady';

const STATUS_LABELS: Record<ReviewCaseStatus, string> = {
  OPEN: '待处理',
  RESOLVED: '已解决',
  DISMISSED: '已关闭',
};

const ALERT_STATUS_LABELS: Record<OperationalAlertStatus, string> = {
  OPEN: '待确认',
  ACKNOWLEDGED: '已知晓',
  RESOLVED: '已恢复',
};

const REASON_LABELS: Record<string, string> = {
  CUSTOMER_MATCH_REQUIRED: '客户映射待确认',
  SKU_MAPPING_REQUIRED: 'SKU 映射待确认',
  SKU_MAPPING_CONFLICT: 'SKU 映射冲突',
  SOURCE_SKU_MAPPING_REQUIRED: '来源 SKU 待确认',
  PROVIDER_SKU_MAPPING_REQUIRED: '履约方 SKU 待确认',
  MAPPING_MULTIPLIER: '数量换算待确认',
  QUANTITY_SCALE: '数量精度待确认',
  CARRIER_MAPPING: '承运商映射待确认',
  MULTI_SHIPMENT_SOURCE_FOLLOWUP: '多批发货来源回传待跟进',
  IMPORT_DATA: '导入数据待修正',
  REVISION_AFTER_EXPORT: '导出后改单待确认',
  SYNC_FAILED: '来源回传失败',
  FULFILLMENT_EXCEPTION: '履约异常',
  WECOM_ORDER_DRAFT: '企业微信订单草稿待确认',
  WECOM_TRACKING_DRAFT: '企业微信运单草稿待确认',
  JD_SKU_MAPPING_BLOCKED: '京东 SKU 映射门禁阻断',
  JD_STOCK_BLOCKED: '京东库存不足阻断',
  MULTIPLE_TRACKINGS_FOR_OUTBOUND: '京东多运单待确认',
  JD_TRACKING_CARRIER_MAPPING_REQUIRED: '京东承运商映射待确认',
  JD_TRACKING_TERMINAL_EXCEPTION: '京东运单终态异常待复核',
  WECOM_NEED_REVIEW: '企微消息待人工识别',
  WECOM_ORDER_CHANGE: '企微改单待确认',
  WECOM_ORDER_CANCEL: '企微取消待确认',
};

const TEAM_OPTIONS = [
  { value: 'CUSTOMER_OPS', label: '客户运营' },
  { value: 'SKU_OPS', label: '商品运营' },
  { value: 'ORDER_OPS', label: '订单运营' },
  { value: 'FULFILLMENT_OPS', label: '履约运营' },
];
const TEAM_LABELS = Object.fromEntries(TEAM_OPTIONS.map((item) => [item.value, item.label]));

type PrimaryAction =
  | 'CUSTOMER'
  | 'SKU'
  | 'SOURCE_FOLLOWUP'
  | 'JD_TRACKING_CONFLICT'
  | 'MANUAL_RESOLVE';

const PRIMARY_ACTION_META: Record<PrimaryAction, {
  label: string;
  alertType: 'info' | 'warning';
  alertMessage: string;
  alertDescription: string;
  notePlaceholder: string;
}> = {
  CUSTOMER: {
    label: '确认并解决',
    alertType: 'info',
    alertMessage: '只选择已存在、已确认且启用的主数据',
    alertDescription: '此处不会按名称相似度创建客户；如客户尚不存在，请先前往客户档案维护。',
    notePlaceholder: '填写人工核对依据（可选）',
  },
  SKU: {
    label: '确认并解决',
    alertType: 'info',
    alertMessage: '只选择已存在、已确认且启用的主数据',
    alertDescription: '此处不会按名称相似度创建 SKU；如 SKU 尚不存在，请先前往 SKU 主数据维护。',
    notePlaceholder: '填写人工核对依据（可选）',
  },
  SOURCE_FOLLOWUP: {
    label: '确认已完成后续回传',
    alertType: 'warning',
    alertMessage: '系统会重新校验履约终态与所有真实运单',
    alertDescription: '任一 Fulfillment 未终局或 Shipment 缺少 Tracking 时，提交会被明确拒绝。',
    notePlaceholder: '填写来源平台逐票处理依据（可选）',
  },
  JD_TRACKING_CONFLICT: {
    label: '确认已处理',
    alertType: 'info',
    alertMessage: '确认京东运单冲突已按人工核对处理',
    alertDescription: '确认后关闭该复核事项并记录审计，不会修改运单事实。',
    notePlaceholder: '填写人工核对依据（可选）',
  },
  MANUAL_RESOLVE: {
    label: '标记已处理',
    alertType: 'info',
    alertMessage: '该事项没有专用解决表单',
    alertDescription: '主数据或线下问题处理完毕后在此显式闭环；系统不会猜测或自动改写业务事实。',
    notePlaceholder: '填写处理依据（可选）',
  },
};

function resolutionTarget(item: ReviewCase): string | undefined {
  if (reviewAction(item) === 'SKU' || reviewAction(item) === 'JD_SKU_MAPPING') return '/product/sku-mappings';
  if (item.order_id) return `/orders/${item.order_id}`;
  return undefined;
}

export default function ManualReviewPage() {
  const navigate = useNavigate();
  const [messageApi, messageContext] = message.useMessage();
  const [view, setView] = useState<'REVIEWS' | 'ALERTS'>('REVIEWS');
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [status, setStatus] = useState<ReviewCaseStatus>('OPEN');
  const [team, setTeam] = useState<string>();
  const [selected, setSelected] = useState<ReviewCase | null>(null);
  const [alertPage, setAlertPage] = useState(0);
  const [alertSize, setAlertSize] = useState(20);
  const [alertStatus, setAlertStatus] = useState<OperationalAlertStatus>('OPEN');
  const [selectedAlert, setSelectedAlert] = useState<OperationalAlert | null>(null);
  const [masterId, setMasterId] = useState<string>();
  const [masterOptions, setMasterOptions] = useState<MasterDataOptionState>(() => emptyMasterDataOptionState());
  const [masterOptionsLoading, setMasterOptionsLoading] = useState(false);
  const [masterQuery, setMasterQuery] = useState('');
  const masterRequest = useRef(0);
  const [multiplier, setMultiplier] = useState('1.000');
  const [note, setNote] = useState('');
  const [submitError, setSubmitError] = useState<string>();
  const [submitting, setSubmitting] = useState(false);
  const [alertNote, setAlertNote] = useState('');
  const [alertSubmitError, setAlertSubmitError] = useState<string>();
  const [alertSubmitting, setAlertSubmitting] = useState(false);

  const queue = useAsync(
    () => reviewCasesApi.list({ page, size, status, responsible_team: team }),
    [page, size, status, team],
  );
  const alerts = useAsync(
    () => operationalAlertsApi.list({ page: alertPage, size: alertSize, status: alertStatus }),
    [alertPage, alertSize, alertStatus],
  );
  const items = useMemo(() => queue.data?.items ?? [], [queue.data]);
  const alertItems = useMemo(() => alerts.data?.items ?? [], [alerts.data]);

  const masterLoaders: Record<'CUSTOMER' | 'SKU', MasterDataOptionLoader> = {
    CUSTOMER: (query) => customersApi.list(query),
    SKU: ({ page, size }) => skusApi.list({ page, size }),
  };

  useEffect(() => {
    setMasterId(undefined);
    setMultiplier('1.000');
    setNote('');
    setSubmitError(undefined);
    setMasterQuery('');
    setMasterOptions(emptyMasterDataOptionState());
  }, [selected?.id]);

  useEffect(() => {
    if (!selected || selected.status !== 'OPEN') return;
    const action = reviewAction(selected);
    if (action !== 'CUSTOMER' && action !== 'SKU') return;
    let active = true;
    const request = ++masterRequest.current;
    setMasterOptionsLoading(true);
    const timer = window.setTimeout(() => {
      loadMasterDataOptionPage(masterLoaders[action], emptyMasterDataOptionState(), {
        query: masterQuery,
        reset: true,
      })
        .then((next) => {
          if (active && request === masterRequest.current) setMasterOptions(next);
        })
        .catch((error) => {
          if (active && request === masterRequest.current) setSubmitError(errorMessage(error));
        })
        .finally(() => {
          if (active && request === masterRequest.current) setMasterOptionsLoading(false);
        });
    }, 250);
    return () => {
      active = false;
      window.clearTimeout(timer);
    };
  }, [selected, masterQuery]);

  async function submitReview() {
    if (!selected) return;
    setSubmitting(true);
    setSubmitError(undefined);
    try {
      const action = reviewAction(selected);
      if (action === 'CUSTOMER') {
        if (!masterId) throw new Error('请选择已确认的客户主数据');
        await reviewCasesApi.resolveCustomer(selected.id, buildCustomerResolution(selected, masterId, note));
      } else if (action === 'SKU') {
        if (!masterId) throw new Error('请选择已确认的 SKU 主数据');
        if (!Number.isFinite(Number(multiplier)) || Number(multiplier) <= 0) throw new Error('数量换算必须大于 0');
        await reviewCasesApi.resolveSku(selected.id, buildSkuResolution(selected, masterId, multiplier, note));
      } else if (action === 'SOURCE_FOLLOWUP') {
        await reviewCasesApi.completeSourceFollowup(selected.id, buildSourceFollowupCompletion(selected, note));
      } else {
        throw new Error('该事项需要前往关联页面处理');
      }
      messageApi.success('复核事项已处理并记录审计');
      setSelected(null);
      queue.reload();
    } catch (error) {
      setSubmitError(error instanceof Error && !('status' in error) ? error.message : errorMessage(error));
    } finally {
      setSubmitting(false);
    }
  }

  async function loadMoreMaster() {
    if (!selected || masterOptionsLoading || !hasMoreMasterDataOptions(masterOptions)) return;
    const action = reviewAction(selected);
    if (action !== 'CUSTOMER' && action !== 'SKU') return;
    const request = ++masterRequest.current;
    setMasterOptionsLoading(true);
    try {
      const next = await loadMasterDataOptionPage(masterLoaders[action], masterOptions, {
        query: masterOptions.query,
      });
      if (request === masterRequest.current) setMasterOptions(next);
    } catch (error) {
      if (request === masterRequest.current) setSubmitError(errorMessage(error));
    } finally {
      if (request === masterRequest.current) setMasterOptionsLoading(false);
    }
  }

  async function resolveManually() {
    if (!selected) return;
    setSubmitting(true);
    setSubmitError(undefined);
    try {
      await reviewCasesApi.resolve(selected.id, buildManualResolution(selected, note));
      messageApi.success('复核事项已标记已解决并记录审计');
      setSelected(null);
      queue.reload();
    } catch (error) {
      setSubmitError(errorMessage(error));
    } finally {
      setSubmitting(false);
    }
  }

  async function dismissCase() {
    if (!selected) return;
    setSubmitting(true);
    setSubmitError(undefined);
    try {
      await reviewCasesApi.dismiss(selected.id, buildDismissCommand(selected, note));
      messageApi.success('复核事项已关闭并记录审计');
      setSelected(null);
      queue.reload();
    } catch (error) {
      setSubmitError(errorMessage(error));
    } finally {
      setSubmitting(false);
    }
  }

  async function confirmJdTrackingConflict() {
    if (!selected) return;
    setSubmitting(true);
    setSubmitError(undefined);
    try {
      await reviewCasesApi.resolveJdTrackingConflict(selected.id, buildManualResolution(selected, note));
      messageApi.success('京东运单冲突已确认处理并记录审计');
      setSelected(null);
      queue.reload();
    } catch (error) {
      setSubmitError(errorMessage(error));
    } finally {
      setSubmitting(false);
    }
  }

  async function rerunJdStockCheck() {
    if (!selected) return;
    const shipmentId = selected.subject_type === 'SHIPMENT'
      ? selected.subject_id
      : typeof selected.detail?.shipment_id === 'string' ? selected.detail.shipment_id : undefined;
    if (!shipmentId) {
      setSubmitError('该事项缺少关联 Shipment，无法重跑库存核对');
      return;
    }
    setSubmitting(true);
    setSubmitError(undefined);
    try {
      const result = await shipmentsApi.checkJdStock(shipmentId);
      if (result.stock_status === 'PASSED') {
        messageApi.success('库存核对通过，阻断事项已自动解除');
        setSelected(null);
      } else {
        messageApi.warning('库存仍不足，阻断保持不变');
        const refreshed = await reviewCasesApi.detail(selected.id);
        setSelected(refreshed);
      }
      queue.reload();
    } catch (error) {
      setSubmitError(errorMessage(error));
    } finally {
      setSubmitting(false);
    }
  }

  async function rerunJdSkuMapping() {
    if (!selected || reviewAction(selected) !== 'JD_SKU_MAPPING') return;
    setSubmitting(true);
    setSubmitError(undefined);
    try {
      const outcome = await rerunJdSkuMappingReview(selected, {
        check: shipmentsApi.checkJdSkuMapping,
        loadReviewCase: reviewCasesApi.detail,
      });
      const { result } = outcome;
      const feedback = jdSkuMappingRerunResultMessage(result);
      if (result.gate_status === 'PASSED') {
        messageApi.success(feedback);
        setSelected(null);
      } else {
        messageApi.warning(feedback);
        setSelected(outcome.refreshedCase);
      }
      queue.reload();
    } catch (error) {
      setSubmitError(errorMessage(error));
    } finally {
      setSubmitting(false);
    }
  }

  async function acknowledgeAlert() {
    if (!selectedAlert || !alertNote.trim()) {
      setAlertSubmitError('请填写确认备注');
      return;
    }
    setAlertSubmitting(true);
    setAlertSubmitError(undefined);
    try {
      await operationalAlertsApi.acknowledge(selectedAlert.id, {
        expected_version: selectedAlert.version,
        note: alertNote.trim(),
      });
      messageApi.success('运营提醒已确认；业务状态未被推进');
      setSelectedAlert(null);
      setAlertNote('');
      alerts.reload();
    } catch (error) {
      setAlertSubmitError(errorMessage(error));
    } finally {
      setAlertSubmitting(false);
    }
  }

  function completeOrderDraft(action: 'CONFIRMED' | 'REJECTED', orderId?: string | null) {
    messageApi.success(action === 'CONFIRMED' ? '订单草稿已确认并生成正式订单' : '订单草稿已拒绝');
    setSelected(null);
    queue.reload();
    if (action === 'CONFIRMED' && orderId) navigate(`/orders/${orderId}`);
  }

  function completeTrackingDraft() {
    messageApi.success('运单草稿已确认并记录正式运单');
    setSelected(null);
    queue.reload();
  }

  const reviewColumns: ColumnsType<ReviewCase> = [
    { title: '复核单号', dataIndex: 'case_no', width: 145 },
    { title: '待办事项', dataIndex: 'reason_code', width: 190, render: (value: string) => REASON_LABELS[value] ?? value },
    { title: '责任团队', dataIndex: 'responsible_team', width: 115, render: (value: string) => TEAM_LABELS[value] ?? value },
    {
      title: '关联订单', dataIndex: 'order_id', width: 90,
      render: (value?: string) => value ? <Typography.Link onClick={() => navigate(`/orders/${value}`)}>#{value}</Typography.Link> : '—',
    },
    {
      title: '状态', dataIndex: 'status', width: 85,
      render: (value: ReviewCaseStatus) => <Tag color={reviewCaseStatusSemantic(value)}>{STATUS_LABELS[value]}</Tag>,
    },
    { title: '进入队列时间', dataIndex: 'created_at', width: 150, render: (value: string) => dayjs(value).format('YYYY-MM-DD HH:mm') },
    { title: '操作', key: 'action', width: 90, fixed: 'right', render: (_, item) => <Typography.Link onClick={() => setSelected(item)}>查看处理</Typography.Link> },
  ];

  const alertColumns: ColumnsType<OperationalAlert> = [
    { title: '提醒单号', dataIndex: 'alert_no', width: 150 },
    { title: '提醒类型', dataIndex: 'alert_type', width: 160 },
    { title: '内容', dataIndex: 'message' },
    {
      title: '等级', dataIndex: 'severity', width: 80,
      render: (value: OperationalAlert['severity']) => <Tag color={severitySemantic(value)}>{value === 'RED' ? '红色' : '黄色'}</Tag>,
    },
    {
      title: '状态', dataIndex: 'status', width: 90,
      render: (value: OperationalAlertStatus) => <Tag color={operationalAlertStatusSemantic(value)}>{ALERT_STATUS_LABELS[value]}</Tag>,
    },
    { title: '创建时间', dataIndex: 'created_at', width: 150, render: (value: string) => dayjs(value).format('YYYY-MM-DD HH:mm') },
    { title: '操作', key: 'action', width: 90, fixed: 'right', render: (_, item) => <Typography.Link onClick={() => { setSelectedAlert(item); setAlertNote(''); setAlertSubmitError(undefined); }}>查看确认</Typography.Link> },
  ];

  const selectedAction = selected ? reviewAction(selected) : 'NAVIGATE';
  const requiresMaster = selectedAction === 'CUSTOMER' || selectedAction === 'SKU';
  const allowedActions = new Set(selected?.allowed_actions ?? []);
  const canResolveJdTracking = selected?.status === 'OPEN' && allowedActions.has('RESOLVE_JD_TRACKING_CONFLICT');
  const canResolveManually = selected?.status === 'OPEN' && allowedActions.has('RESOLVE_MANUALLY');
  const canRerunStock = selected?.status === 'OPEN' && allowedActions.has('RERUN_JD_STOCK_CHECK');
  const canReinterpret = selected?.status === 'OPEN' && allowedActions.has('REINTERPRET');
  const canDismiss = selected?.status === 'OPEN' && allowedActions.has('DISMISS');
  const primaryAction: PrimaryAction | undefined = selectedAction === 'CUSTOMER'
    || selectedAction === 'SKU'
    || selectedAction === 'SOURCE_FOLLOWUP'
    ? selectedAction
    : canResolveJdTracking
      ? 'JD_TRACKING_CONFLICT'
      : canResolveManually
        ? 'MANUAL_RESOLVE'
        : undefined;
  const jdSkuPermissions = selected && selectedAction === 'JD_SKU_MAPPING'
    ? jdSkuMappingReviewPermissions(selected.status, selected.allowed_actions)
    : { canOpenMapping: false, canRerun: false };
  const jdSkuEvidence = selected && selectedAction === 'JD_SKU_MAPPING'
    ? jdSkuMappingReviewEvidence(selected.detail)
    : [];

  return (
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      {messageContext}
      <Card size="small" styles={{ body: { padding: '16px 18px' } }}>
        <Space align="start" style={{ width: '100%', justifyContent: 'space-between' }}>
          <Space align="start" size={12}>
            <CheckSquareOutlined style={{ color: saasVisualTokens.brand.primary, fontSize: 20, marginTop: 3 }} />
            <div>
              <Typography.Title level={5} style={{ margin: 0 }}>人工作业中心</Typography.Title>
              <Typography.Text type="secondary">阻断复核需要明确解决；运营提醒只记录知晓，不推进业务状态。</Typography.Text>
            </div>
          </Space>
          <Segmented
            value={view}
            onChange={(value) => setView(value as 'REVIEWS' | 'ALERTS')}
            options={[{ value: 'REVIEWS', label: '阻断复核' }, { value: 'ALERTS', label: '运营提醒' }]}
          />
        </Space>
      </Card>

      {view === 'REVIEWS' ? (
        <>
          {queue.error ? <Alert type="error" showIcon message="复核队列加载失败" description={errorMessage(queue.error)} /> : null}
          <Card size="small">
            <Space wrap>
              <Typography.Text type="secondary">状态</Typography.Text>
              <Select<ReviewCaseStatus>
                value={status} style={{ width: 130 }}
                onChange={(value) => { setStatus(value); setPage(0); }}
                options={Object.entries(STATUS_LABELS).map(([value, label]) => ({ value: value as ReviewCaseStatus, label }))}
              />
              <Typography.Text type="secondary">责任团队</Typography.Text>
              <Select allowClear placeholder="全部团队" value={team} style={{ width: 160 }} onChange={(value) => { setTeam(value); setPage(0); }} options={TEAM_OPTIONS} />
              <Button icon={<ReloadOutlined />} onClick={queue.reload}>刷新</Button>
              <Typography.Text strong style={{ color: ATTENTION_COLORS.waiting }}>{queue.data?.total_elements ?? 0} 项</Typography.Text>
            </Space>
          </Card>
          <Card size="small" styles={{ body: { padding: '4px 8px' } }}>
            <Table<ReviewCase>
              rowKey="id" loading={queue.loading} columns={reviewColumns} dataSource={items} scroll={{ x: 900 }}
              locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="当前没有复核事项" /> }}
              pagination={{
                current: page + 1, pageSize: size, total: queue.data?.total_elements ?? 0, showSizeChanger: true,
                showTotal: (total) => `共 ${total} 项`, onChange: (nextPage, nextSize) => { setPage(nextPage - 1); setSize(nextSize); },
              }}
            />
          </Card>
        </>
      ) : (
        <>
          {alerts.error ? <Alert type="error" showIcon message="运营提醒加载失败" description={errorMessage(alerts.error)} /> : null}
          <Card size="small">
            <Space wrap>
              <Typography.Text type="secondary">状态</Typography.Text>
              <Select<OperationalAlertStatus>
                value={alertStatus} style={{ width: 130 }}
                onChange={(value) => { setAlertStatus(value); setAlertPage(0); }}
                options={Object.entries(ALERT_STATUS_LABELS).map(([value, label]) => ({ value: value as OperationalAlertStatus, label }))}
              />
              <Button icon={<ReloadOutlined />} onClick={alerts.reload}>刷新</Button>
              <Typography.Text strong style={{ color: ATTENTION_COLORS.waiting }}>{alerts.data?.total_elements ?? 0} 项</Typography.Text>
            </Space>
          </Card>
          <Card size="small" styles={{ body: { padding: '4px 8px' } }}>
            <Table<OperationalAlert>
              rowKey="id" loading={alerts.loading} columns={alertColumns} dataSource={alertItems} scroll={{ x: 900 }}
              locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="当前没有运营提醒" /> }}
              pagination={{
                current: alertPage + 1, pageSize: alertSize, total: alerts.data?.total_elements ?? 0, showSizeChanger: true,
                showTotal: (total) => `共 ${total} 项`, onChange: (nextPage, nextSize) => { setAlertPage(nextPage - 1); setAlertSize(nextSize); },
              }}
            />
          </Card>
        </>
      )}

      <Drawer
        title={selected ? `复核事项 ${selected.case_no}` : '复核事项'}
        open={Boolean(selected)}
        onClose={() => setSelected(null)}
        width={selectedAction === 'ORDER_DRAFT' || selectedAction === 'TRACKING_DRAFT' ? 920 : 620}
      >
        {selected && selectedAction === 'ORDER_DRAFT' ? (
          <OrderDraftReviewPanel
            reviewCase={selected}
            onCompleted={(action, result) => completeOrderDraft(action, result.confirmed_order_id)}
          />
        ) : selected && selectedAction === 'TRACKING_DRAFT' ? (
          <TrackingDraftReviewPanel
            reviewCase={selected}
            onCompleted={completeTrackingDraft}
          />
        ) : selected ? (
          <Space direction="vertical" size={18} style={{ width: '100%' }}>
            <Descriptions size="small" column={2} items={[
              { key: 'reason', label: '事项', children: REASON_LABELS[selected.reason_code] ?? selected.reason_code },
              { key: 'team', label: '责任团队', children: TEAM_LABELS[selected.responsible_team] ?? selected.responsible_team },
              { key: 'subject', label: '关联对象', children: `${selected.subject_type} #${selected.subject_id}` },
              { key: 'status', label: '状态', children: STATUS_LABELS[selected.status] },
              { key: 'version', label: '当前版本', children: selected.version },
              { key: 'resolved', label: '解决人', children: selected.resolved_by ?? '—' },
            ]} />
            <div>
              {isSkuMappingReasonCode(selected.reason_code) ? (
                <>
                  <Typography.Text strong>来源商品信息</Typography.Text>
                  <Descriptions
                    bordered
                    size="small"
                    column={1}
                    style={{ marginTop: 8 }}
                    items={skuMappingDetailRows(selected.detail).map((row, index) => ({
                      key: `${row.label}-${index}`,
                      label: row.label,
                      children: row.value,
                    }))}
                  />
                  <Typography.Text strong style={{ display: 'block', marginTop: 12 }}>
                    待映射商品明细
                  </Typography.Text>
                  <Table
                    size="small"
                    rowKey="evidenceKey"
                    pagination={false}
                    style={{ marginTop: 8 }}
                    dataSource={skuMappingEvidenceItems(selected.detail).map((item, index) => ({
                      ...item,
                      evidenceKey: index,
                    }))}
                    columns={[
                      { title: '商品名称', dataIndex: 'productName', render: (value: string | null) => skuMappingEvidenceCell(value) },
                      { title: '规格', dataIndex: 'specification', render: (value: string | null) => skuMappingEvidenceCell(value) },
                      { title: '单位', dataIndex: 'unit', render: (value: string | null) => skuMappingEvidenceCell(value) },
                      { title: '数量', dataIndex: 'quantity', render: (value: string | null) => skuMappingEvidenceCell(value) },
                      { title: '来源商品编号', dataIndex: 'sourceSkuRef', render: (value: string | null) => skuMappingEvidenceCell(value) },
                    ]}
                    locale={{ emptyText: '来源未提供商品明细' }}
                  />
                </>
              ) : safeReviewDetailRows(selected.detail).length ? (
                <>
                  <Typography.Text strong>复核依据</Typography.Text>
                  <Descriptions
                    bordered
                    size="small"
                    column={1}
                    style={{ marginTop: 8 }}
                    items={safeReviewDetailRows(selected.detail).map((row, index) => ({
                      key: `${row.label}-${index}`,
                      label: row.label,
                      children: row.value,
                    }))}
                  />
                </>
              ) : (
                <>
                  <Typography.Text strong>复核依据</Typography.Text>
                  <Typography.Paragraph type="secondary" style={{ marginTop: 8 }}>
                    该事项没有可公开展示的补充字段，请按事项类型和关联订单处理。
                  </Typography.Paragraph>
                </>
              )}
            </div>
            {selectedAction === 'JD_SKU_MAPPING' && selected.status === 'OPEN' ? (
              <>
                <Alert
                  type="warning"
                  showIcon
                  message="建单已阻断，不会猜测或自动改写 SKU 映射"
                  description="先在现有 SKU 映射页修正 goods 标识、启用状态或销售单位折算，再手动重跑当前 Shipment 的只读核对。"
                />
                <div>
                  <Typography.Text strong>受影响发货明细</Typography.Text>
                  {jdSkuEvidence.length ? (
                    <Table
                      size="small"
                      rowKey={(item) => item.evidenceKey}
                      pagination={false}
                      style={{ marginTop: 8 }}
                      dataSource={jdSkuEvidence}
                      columns={[
                        { title: '订单行', dataIndex: 'lineLabel', width: 140 },
                        { title: 'SKU', dataIndex: 'skuLabel', width: 160 },
                        { title: '阻断问题', dataIndex: 'issues', render: (issues: string[]) => issues.join('、') },
                      ]}
                    />
                  ) : (
                    <Typography.Paragraph type="secondary" style={{ marginTop: 8 }}>
                      当前事项未包含可公开的发货明细，请重新核对以刷新阻断证据。
                    </Typography.Paragraph>
                  )}
                </div>
                {submitError ? <Alert type="error" showIcon message="重新核对未完成" description={submitError} /> : null}
                <Space>
                  <Button
                    type="primary"
                    disabled={!jdSkuPermissions.canOpenMapping || submitting}
                    onClick={() => navigate('/product/sku-mappings?tab=provider')}
                  >
                    前往 SKU 映射维护
                  </Button>
                  <Button
                    loading={submitting}
                    disabled={!jdSkuPermissions.canRerun}
                    onClick={rerunJdSkuMapping}
                  >
                    修正后重新核对
                  </Button>
                </Space>
              </>
            ) : null}
            {selected.status === 'OPEN' ? (
              <>
                {primaryAction ? (
                  <>
                    <Alert
                      type={PRIMARY_ACTION_META[primaryAction].alertType}
                      showIcon
                      message={PRIMARY_ACTION_META[primaryAction].alertMessage}
                      description={PRIMARY_ACTION_META[primaryAction].alertDescription}
                    />
                    {requiresMaster ? (
                      <>
                        <Select
                          showSearch
                          filterOption={false}
                          loading={masterOptionsLoading}
                          value={masterId}
                          onChange={setMasterId}
                          onSearch={setMasterQuery}
                          style={{ width: '100%' }}
                          placeholder={selectedAction === 'CUSTOMER' ? '选择已确认客户' : '选择已确认 SKU'}
                          options={masterOptions.items.map((item) => ({ value: item.id, label: `${item.name}（${item.code}）` }))}
                        />
                        <Space wrap size={8}>
                          <Typography.Text type="secondary">
                            输入名称或编码可搜索全部主数据；已加载 {masterOptions.items.length} / {masterOptions.totalElements} 条。
                          </Typography.Text>
                          {hasMoreMasterDataOptions(masterOptions) ? (
                            <Button size="small" loading={masterOptionsLoading} onClick={loadMoreMaster}>加载更多</Button>
                          ) : null}
                        </Space>
                      </>
                    ) : null}
                    {selectedAction === 'SKU' ? (
                      <Input addonBefore="数量换算" value={multiplier} onChange={(event) => setMultiplier(event.target.value)} placeholder="例如 2.000" />
                    ) : null}
                    <Input.TextArea
                      value={note}
                      onChange={(event) => setNote(event.target.value)}
                      rows={4}
                      maxLength={1000}
                      showCount
                      placeholder={PRIMARY_ACTION_META[primaryAction].notePlaceholder}
                    />
                    {submitError ? <Alert type="error" showIcon message="提交未完成" description={submitError} /> : null}
                    <Space>
                      <Button
                        type="primary"
                        loading={submitting}
                        onClick={
                          primaryAction === 'JD_TRACKING_CONFLICT'
                            ? confirmJdTrackingConflict
                            : primaryAction === 'MANUAL_RESOLVE'
                              ? resolveManually
                              : submitReview
                        }
                      >
                        {PRIMARY_ACTION_META[primaryAction].label}
                      </Button>
                      {selectedAction === 'SKU' ? <Button onClick={() => navigate('/product/sku-mappings')}>前往 SKU 映射</Button> : null}
                    </Space>
                  </>
                ) : null}
                {canRerunStock ? (
                  <>
                    <Alert
                      type="warning"
                      showIcon
                      message="京东库存不足阻断建单"
                      description="可先重跑库存核对：通过后阻断自动解除；仍不足时请线下补货后再试。"
                    />
                    <Button loading={submitting} onClick={rerunJdStockCheck}>重跑库存核对</Button>
                  </>
                ) : null}
                {canReinterpret ? (
                  <>
                    <Alert
                      type="warning"
                      showIcon
                      message="消息链路事项需先在消息页处理"
                      description="前往消息页对原提交重新识别或人工处理；处理完毕后可在此标记已解决。"
                    />
                    <Button onClick={() => navigate('/workbench/channel-messages')}>前往消息页重新识别</Button>
                  </>
                ) : null}
                {canDismiss ? (
                  <>
                    {!primaryAction && !canRerunStock && !canReinterpret ? (
                      <>
                        <Alert
                          type="info"
                          showIcon
                          message="该事项没有可执行的处理表单"
                          description="如确认不再需要处理，可直接关闭并记录审计。"
                        />
                        <Input.TextArea
                          value={note}
                          onChange={(event) => setNote(event.target.value)}
                          rows={3}
                          maxLength={1000}
                          showCount
                          placeholder="填写关闭原因（可选）"
                        />
                        {submitError ? <Alert type="error" showIcon message="关闭未完成" description={submitError} /> : null}
                      </>
                    ) : null}
                    <Popconfirm
                      title="确认关闭该复核事项？"
                      description="关闭后不再进入待办队列，动作会记录审计。"
                      okText="确认关闭"
                      cancelText="取消"
                      okButtonProps={{ danger: true }}
                      onConfirm={dismissCase}
                      disabled={submitting}
                    >
                      <Button danger disabled={submitting}>关闭事项</Button>
                    </Popconfirm>
                  </>
                ) : null}
                {!primaryAction && !canRerunStock && !canReinterpret && !canDismiss ? (
                  resolutionTarget(selected) ? (
                    <Button type="primary" onClick={() => navigate(resolutionTarget(selected)!)}>前往关联页面处理</Button>
                  ) : (
                    <Typography.Text type="secondary">该事项暂无可用处理动作，请联系管理员。</Typography.Text>
                  )
                ) : null}
              </>
            ) : selected.resolution ? (
              <Descriptions
                bordered
                size="small"
                column={1}
                items={safeReviewDetailRows(selected.resolution).map((row, index) => ({
                  key: `${row.label}-${index}`,
                  label: row.label,
                  children: row.value,
                }))}
              />
            ) : resolutionTarget(selected) ? (
              <Button type="primary" onClick={() => navigate(resolutionTarget(selected)!)}>前往关联页面处理</Button>
            ) : null}
          </Space>
        ) : null}
      </Drawer>

      <Drawer title={selectedAlert ? `运营提醒 ${selectedAlert.alert_no}` : '运营提醒'} open={Boolean(selectedAlert)} onClose={() => setSelectedAlert(null)} width={560}>
        {selectedAlert ? (
          <Space direction="vertical" size={18} style={{ width: '100%' }}>
            <Descriptions size="small" column={1} items={[
              { key: 'message', label: '提醒内容', children: selectedAlert.message },
              { key: 'severity', label: '等级', children: selectedAlert.severity === 'RED' ? '红色' : '黄色' },
              { key: 'order', label: '关联订单', children: selectedAlert.order_id ? `#${selectedAlert.order_id}` : '—' },
              { key: 'status', label: '状态', children: ALERT_STATUS_LABELS[selectedAlert.status] },
              { key: 'version', label: '当前版本', children: selectedAlert.version },
            ]} />
            <Alert type="info" showIcon message="确认提醒不会推进订单或履约状态" description="此动作只记录处理人、时间、备注和审计日志。" />
            {selectedAlert.status === 'OPEN' ? (
              <>
                <Input.TextArea value={alertNote} onChange={(event) => setAlertNote(event.target.value)} rows={4} maxLength={1000} showCount placeholder="填写已知晓后的跟进安排" />
                {alertSubmitError ? <Alert type="error" showIcon message="确认未完成" description={alertSubmitError} /> : null}
                <Button type="primary" loading={alertSubmitting} onClick={acknowledgeAlert}>确认已知晓</Button>
              </>
            ) : (
              <Typography.Text type="secondary">{selectedAlert.acknowledged_by ? `${selectedAlert.acknowledged_by} 已于 ${dayjs(selectedAlert.acknowledged_at).format('YYYY-MM-DD HH:mm')} 确认` : '该提醒已关闭'}</Typography.Text>
            )}
          </Space>
        ) : null}
      </Drawer>
    </Space>
  );
}
