/**
 * Issue #64：复核事项抽屉（从 ManualReviewPage 拆出）。
 * 承载单一复核事项的全部处理表单与动作：主数据选择（客户/SKU 分页搜索）、
 * 数量换算、来源回传确认、京东 SKU 映射核对、库存重跑、消息链路重识别、
 * 通用标记已解决 / 关闭，以及订单/运单草稿面板。
 * 动作状态（备注、主数据选项、提交中）全部收在本组件，页面只持有「选中事项」。
 */

import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Alert,
  Button,
  Descriptions,
  Drawer,
  Input,
  Popconfirm,
  Select,
  Space,
  Table,
  Typography,
  message,
} from 'antd';
import type { ReviewCase } from '@/api/types';
import { customersApi, reviewCasesApi, shipmentsApi, skusApi } from '@/api/endpoints';
import { errorMessage } from '@/api/client';
import {
  factGroupRows,
  isSkuMappingReasonCode,
  reviewBlockerRows,
  reviewFactGroups,
  safeReviewDetailRows,
  skuMappingEvidenceCell,
  skuMappingEvidenceItems,
  type FactGroup,
} from '@/presentation/publicReady';
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
import { REVIEW_STATUS_LABELS, TEAM_LABELS } from './queuePresentation';
import { reasonLabel } from '@/constants/labels';

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

const MASTER_LOADERS: Record<'CUSTOMER' | 'SKU', MasterDataOptionLoader> = {
  CUSTOMER: (query) => customersApi.list(query),
  SKU: ({ page, size }) => skusApi.list({ page, size }),
};

function resolutionTarget(item: ReviewCase): string | undefined {
  if (reviewAction(item) === 'SKU' || reviewAction(item) === 'JD_SKU_MAPPING') return '/product/sku-mappings';
  if (item.order_id) return `/orders/${item.order_id}`;
  return undefined;
}

/**
 * Issue #72：按家族事实组渲染「做决定所需事实」。所有家族共用同一呈现结构——
 * 字段定义（白名单）/ 事实组 / 「来源未提供」占位，不逐家族复制 JSX；
 * 字段缺失显示占位而不是整行消失，未知 detail 键 fail-closed。
 */
function FactGroupSection({ detail, groups }: { detail: Record<string, unknown>; groups: FactGroup[] }) {
  return (
    <>
      {groups.map((group) => (
        <div key={group.title}>
          <Typography.Text strong>{group.title}</Typography.Text>
          <Descriptions
            bordered
            size="small"
            column={1}
            style={{ marginTop: 8 }}
            items={factGroupRows(detail, group).map((row, index) => ({
              key: `${group.title}-${row.label}-${index}`,
              label: row.label,
              children: row.value,
            }))}
          />
        </div>
      ))}
    </>
  );
}

export interface ReviewCaseDrawerProps {
  selected: ReviewCase | null;
  /** 当前筛选与排序下，紧随当前事项之后的一条；不存在时不显示连续作业入口。 */
  nextCase: ReviewCase | null;
  /** 队列成功载入新快照时递增；连续作业必须等成功写后的新快照到达。 */
  queueRevision: number;
  onClose: () => void;
  /** 列表侧刷新（成功动作后重新拉取队列；不负责关闭抽屉）。 */
  onQueueReload: () => void;
  /** 重跑核对后刷新当前事项（保留抽屉打开）。 */
  onRefreshCase: (item: ReviewCase | null) => void;
}

interface CompletedReview {
  message: string;
  queueRevision: number;
}

interface CaseWriteRequest {
  caseId: string;
  token: number;
}

export default function ReviewCaseDrawer({ selected, nextCase, queueRevision, onClose, onQueueReload, onRefreshCase }: ReviewCaseDrawerProps) {
  const navigate = useNavigate();
  const [messageApi, messageContext] = message.useMessage();
  const [masterId, setMasterId] = useState<string>();
  const [masterOptions, setMasterOptions] = useState<MasterDataOptionState>(() => emptyMasterDataOptionState());
  const [masterOptionsLoading, setMasterOptionsLoading] = useState(false);
  const [masterQuery, setMasterQuery] = useState('');
  const masterRequest = useRef(0);
  const [multiplier, setMultiplier] = useState('1.000');
  const [note, setNote] = useState('');
  const [submitError, setSubmitError] = useState<string>();
  const [submitting, setSubmitting] = useState(false);
  const [completed, setCompleted] = useState<CompletedReview>();
  const activeCaseId = useRef<string | null>(selected?.id ?? null);
  const writeRequest = useRef(0);
  activeCaseId.current = selected?.id ?? null;

  useEffect(() => {
    setMasterId(undefined);
    setMultiplier('1.000');
    setNote('');
    setSubmitError(undefined);
    setMasterQuery('');
    setMasterOptions(emptyMasterDataOptionState());
    setCompleted(undefined);
    setSubmitting(false);
    writeRequest.current += 1;
  }, [selected?.id]);

  function beginWrite(caseId: string): CaseWriteRequest {
    return { caseId, token: ++writeRequest.current };
  }

  function isActiveWrite(request: CaseWriteRequest): boolean {
    return activeCaseId.current === request.caseId && writeRequest.current === request.token;
  }

  function completeCurrent(messageText: string, caseId: string) {
    // 成功写无论 Drawer 是否已切换都要刷新队列；只把成功态写进仍匹配的当前事项。
    onQueueReload();
    if (activeCaseId.current !== caseId) return;
    messageApi.success(messageText);
    setCompleted({ message: messageText, queueRevision });
  }

  function openNextCase() {
    if (!nextCase) return;
    onRefreshCase(nextCase);
  }

  useEffect(() => {
    if (!selected || selected.status !== 'OPEN') return;
    const action = reviewAction(selected);
    if (action !== 'CUSTOMER' && action !== 'SKU') return;
    let active = true;
    const request = ++masterRequest.current;
    setMasterOptionsLoading(true);
    const timer = window.setTimeout(() => {
      loadMasterDataOptionPage(MASTER_LOADERS[action], emptyMasterDataOptionState(), {
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
    const request = beginWrite(selected.id);
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
      completeCurrent('复核事项已处理并记录审计', request.caseId);
    } catch (error) {
      if (isActiveWrite(request)) {
        setSubmitError(error instanceof Error && !('status' in error) ? error.message : errorMessage(error));
      }
    } finally {
      if (isActiveWrite(request)) setSubmitting(false);
    }
  }

  async function loadMoreMaster() {
    if (!selected || masterOptionsLoading || !hasMoreMasterDataOptions(masterOptions)) return;
    const action = reviewAction(selected);
    if (action !== 'CUSTOMER' && action !== 'SKU') return;
    const request = ++masterRequest.current;
    setMasterOptionsLoading(true);
    try {
      const next = await loadMasterDataOptionPage(MASTER_LOADERS[action], masterOptions, {
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
    const request = beginWrite(selected.id);
    setSubmitting(true);
    setSubmitError(undefined);
    try {
      await reviewCasesApi.resolve(selected.id, buildManualResolution(selected, note));
      completeCurrent('复核事项已标记已解决并记录审计', request.caseId);
    } catch (error) {
      if (isActiveWrite(request)) setSubmitError(errorMessage(error));
    } finally {
      if (isActiveWrite(request)) setSubmitting(false);
    }
  }

  async function dismissCase() {
    if (!selected) return;
    const request = beginWrite(selected.id);
    setSubmitting(true);
    setSubmitError(undefined);
    try {
      await reviewCasesApi.dismiss(selected.id, buildDismissCommand(selected, note));
      completeCurrent('复核事项已关闭并记录审计', request.caseId);
    } catch (error) {
      if (isActiveWrite(request)) setSubmitError(errorMessage(error));
    } finally {
      if (isActiveWrite(request)) setSubmitting(false);
    }
  }

  async function confirmJdTrackingConflict() {
    if (!selected) return;
    const request = beginWrite(selected.id);
    setSubmitting(true);
    setSubmitError(undefined);
    try {
      await reviewCasesApi.resolveJdTrackingConflict(selected.id, buildManualResolution(selected, note));
      completeCurrent('京东运单冲突已确认处理并记录审计', request.caseId);
    } catch (error) {
      if (isActiveWrite(request)) setSubmitError(errorMessage(error));
    } finally {
      if (isActiveWrite(request)) setSubmitting(false);
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
    const request = beginWrite(selected.id);
    setSubmitting(true);
    setSubmitError(undefined);
    try {
      const result = await shipmentsApi.checkJdStock(shipmentId);
      if (!isActiveWrite(request)) {
        onQueueReload();
        return;
      }
      if (result.stock_status === 'PASSED') {
        completeCurrent('库存核对通过，阻断事项已自动解除', request.caseId);
      } else {
        messageApi.warning('库存仍不足，阻断保持不变');
        const refreshed = await reviewCasesApi.detail(selected.id);
        if (!isActiveWrite(request)) return;
        onRefreshCase(refreshed);
        onQueueReload();
      }
    } catch (error) {
      if (isActiveWrite(request)) setSubmitError(errorMessage(error));
    } finally {
      if (isActiveWrite(request)) setSubmitting(false);
    }
  }

  async function rerunJdSkuMapping() {
    if (!selected || reviewAction(selected) !== 'JD_SKU_MAPPING') return;
    const request = beginWrite(selected.id);
    setSubmitting(true);
    setSubmitError(undefined);
    try {
      const outcome = await rerunJdSkuMappingReview(selected, {
        check: shipmentsApi.checkJdSkuMapping,
        loadReviewCase: reviewCasesApi.detail,
      });
      if (!isActiveWrite(request)) {
        onQueueReload();
        return;
      }
      const { result } = outcome;
      const feedback = jdSkuMappingRerunResultMessage(result);
      if (result.gate_status === 'PASSED') {
        completeCurrent(feedback, request.caseId);
      } else {
        messageApi.warning(feedback);
        onRefreshCase(outcome.refreshedCase);
        onQueueReload();
      }
    } catch (error) {
      if (isActiveWrite(request)) setSubmitError(errorMessage(error));
    } finally {
      if (isActiveWrite(request)) setSubmitting(false);
    }
  }

  function completeOrderDraft(action: 'CONFIRMED' | 'REJECTED', orderId?: string | null) {
    if (!selected) return;
    const messageText = action === 'CONFIRMED' ? '订单草稿已确认并生成正式订单' : '订单草稿已拒绝';
    if (action === 'CONFIRMED' && orderId) {
      onQueueReload();
      if (activeCaseId.current !== selected.id) return;
      messageApi.success(messageText);
      onClose();
      navigate(`/orders/${orderId}`);
      return;
    }
    completeCurrent(messageText, selected.id);
  }

  function completeTrackingDraft() {
    if (!selected) return;
    completeCurrent('运单草稿已确认并记录正式运单', selected.id);
  }

  const selectedAction = selected ? reviewAction(selected) : 'NAVIGATE';
  const queueRefreshedAfterCompletion = completed ? queueRevision > completed.queueRevision : false;
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
    <Drawer
      title={selected ? `复核事项 ${selected.case_no}` : '复核事项'}
      open={Boolean(selected)}
      onClose={onClose}
      width={selectedAction === 'ORDER_DRAFT' || selectedAction === 'TRACKING_DRAFT' ? 920 : 620}
    >
      {messageContext}
      {selected && completed ? (
        <Space direction="vertical" size={16} style={{ width: '100%' }}>
          <Alert
            type="success"
            showIcon
            message="当前事项已处理"
            description={!queueRefreshedAfterCompletion
              ? `${completed.message}。正在刷新当前筛选队列…`
              : nextCase
              ? `${completed.message}。下一条：${nextCase.case_no}（${reasonLabel(nextCase.reason_code)}）`
              : `${completed.message}。当前筛选下暂时没有下一条事项。`}
          />
          <Space>
            {queueRefreshedAfterCompletion && nextCase ? <Button type="primary" onClick={openNextCase}>处理下一条</Button> : null}
            <Button onClick={onClose}>返回队列</Button>
          </Space>
        </Space>
      ) : selected && selectedAction === 'ORDER_DRAFT' ? (
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
            { key: 'reason', label: '事项', children: reasonLabel(selected.reason_code) },
            { key: 'team', label: '责任团队', children: TEAM_LABELS[selected.responsible_team] ?? selected.responsible_team },
            { key: 'subject', label: '关联对象', children: selected.subject_no ?? selected.subject_id },
            { key: 'status', label: '状态', children: REVIEW_STATUS_LABELS[selected.status] ?? selected.status },
            { key: 'version', label: '当前版本', children: selected.version },
            { key: 'resolved', label: '解决人', children: selected.resolved_by ?? '—' },
          ]} />
          <div>
            {isSkuMappingReasonCode(selected.reason_code) ? (
              <>
                <FactGroupSection detail={selected.detail} groups={reviewFactGroups(selected.reason_code)} />
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
            ) : reviewFactGroups(selected.reason_code).length ? (
              <FactGroupSection detail={selected.detail} groups={reviewFactGroups(selected.reason_code)} />
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
            {reviewBlockerRows(selected.detail).length ? (
              <div style={{ marginTop: 12 }}>
                <Typography.Text strong>阻断明细</Typography.Text>
                <Table
                  size="small"
                  rowKey="code"
                  pagination={false}
                  style={{ marginTop: 8 }}
                  dataSource={reviewBlockerRows(selected.detail)}
                  columns={[
                    { title: '商品', dataIndex: 'productLabel', render: (value: string | null) => value ?? '—' },
                    { title: '说明', dataIndex: 'message', render: (value: string | null) => value ?? '—' },
                    { title: '阻断码', dataIndex: 'code', width: 220 },
                    { title: '修正目标', dataIndex: 'correctionTarget', render: (value: string | null) => value ?? '—' },
                  ]}
                />
              </div>
            ) : null}
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
              {/* 推进型动作永远排在「标记已处理/关闭」之前：能让流水线继续走的按钮
                  被埋在兜底动作下面，读起来就是「没有推进按钮」（2026-08-26 用户实测反馈 #2——
                  重跑按钮其实一直在，只是折叠在视口外、还排在次动作后面）。 */}
              {canRerunStock ? (
                <>
                  <Alert
                    type="warning"
                    showIcon
                    message="京东库存不足阻断建单"
                    description="重跑库存核对：通过后阻断自动解除，建单继续；仍不足时请线下补货后再试。"
                  />
                  <Button type="primary" loading={submitting} onClick={rerunJdStockCheck}>重跑库存核对</Button>
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
                  <Button type="primary" onClick={() => navigate('/workbench/channel-messages')}>前往消息页重新识别</Button>
                </>
              ) : null}
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
                      type={canRerunStock || canReinterpret ? 'default' : 'primary'}
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
            selected.reason_code === 'SOURCE_SYNC_BLOCKED' ? (
              <FactGroupSection
                detail={selected.resolution}
                groups={reviewFactGroups(selected.reason_code)}
              />
            ) : safeReviewDetailRows(selected.resolution).length ? (
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
            ) : (
              <Typography.Paragraph type="secondary">
                该事项的解决记录没有可公开展示的补充字段。
              </Typography.Paragraph>
            )
          ) : resolutionTarget(selected) ? (
            <Button type="primary" onClick={() => navigate(resolutionTarget(selected)!)}>前往关联页面处理</Button>
          ) : null}
        </Space>
      ) : null}
    </Drawer>
  );
}
