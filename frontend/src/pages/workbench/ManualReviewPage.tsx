/**
 * 人工复核队列页（/workbench/reviews）。
 * Issue #64 拆分后本页只承载「阻断复核」队列：URL 筛选（status/reason/team/import_batch）
 * 是唯一事实源（Issue #96/#95），批次上下文与 fail-closed 校验原样保留；
 * 列表承载结构（FilterBar + DataTable + 分页）复用 QueueTable，处理表单全部收在
 * ReviewCaseDrawer。运营提醒已拆到 /workbench/alerts（AlertsQueuePage）。
 *
 * ReviewQueueCompatRoute：路由层兼容门——旧 #96 分享链接 ?view=alerts 重定向到新提醒
 * 路由（除 view 外参数原样保留），其余情况原样渲染本页。
 */

import { type Key, useEffect, useMemo, useRef, useState } from 'react';
import { Link, Navigate, useNavigate, useSearchParams } from 'react-router-dom';
import { Alert, Button, Card, Empty, Input, Popconfirm, Select, Space, Tag, Typography } from 'antd';
import { CheckSquareOutlined } from '@ant-design/icons';
import type { ColumnsType } from 'antd/es/table';
import dayjs from 'dayjs';
import { ApiError, errorMessage } from '@/api/client';
import { fileOperationsApi, reviewCasesApi } from '@/api/endpoints';
import type { ReviewCase, ReviewCaseStatus } from '@/api/types';
import { useAsync } from '@/hooks/useAsync';
import PageShell from '@/components/PageShell';
import { reviewCaseStatusSemantic } from '@/pages/shared/semanticStatus';
import {
  fileJobUrlForBatch,
  invalidBatchIdMessage,
  parseBatchIdParam,
} from '@/pages/shared/batchUrl';
import {
  REVIEWS_BATCH_PARAM,
  REVIEWS_REASON_PARAM,
  REVIEWS_STATUS_PARAM,
  REVIEWS_TEAM_PARAM,
  alertsQueueUrl,
  alertsRouteFromLegacyView,
} from '@/pages/shared/reviewQueueUrl';
import { readStoredWorkbenchRole } from '@/workbenchRole';
import { reviewTeamForRole } from '@/components/layout/useRailBadges';
import QueueTable from './queueTable';
import LongCode from '@/components/LongCode';
import ReviewCaseDrawer from './ReviewCaseDrawer';
import { useQueuePagination } from './queuePagination';
import { REASON_LABELS, REVIEW_STATUS_LABELS, TEAM_LABELS, TEAM_OPTIONS } from './queuePresentation';
import { reasonLabel } from '@/constants/labels';
import { formatDateTime } from '@/format/dateTime';
import { buildDismissCommand, buildManualResolution, reviewAction } from './manualReviewActions';

type BatchReviewAction = 'RESOLVE_MANUALLY' | 'DISMISS';

interface BatchReviewFailure {
  id: string;
  caseNo: string;
  message: string;
}

interface BatchReviewResult {
  successCount: number;
  failures: BatchReviewFailure[];
}

/** 批量只覆盖无需专用表单的通用闭环；客户、SKU、草稿等事项必须逐条处理。 */
function batchSelectable(item: ReviewCase): boolean {
  if (item.status !== 'OPEN' || reviewAction(item) !== 'NAVIGATE') return false;
  return item.allowed_actions.includes('RESOLVE_MANUALLY') || item.allowed_actions.includes('DISMISS');
}

function batchFailureMessage(error: unknown): string {
  return error instanceof Error && !('status' in error) ? error.message : errorMessage(error);
}

/** 路由层兼容门：旧 view=alerts 链接重定向到新提醒路由，其余原样渲染复核页。 */
export function ReviewQueueCompatRoute() {
  const [searchParams] = useSearchParams();
  const target = alertsRouteFromLegacyView(searchParams);
  if (target) return <Navigate replace to={target} />;
  return <ManualReviewPage />;
}

export default function ManualReviewPage() {
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const batchParam = parseBatchIdParam(searchParams.get(REVIEWS_BATCH_PARAM));
  const batchId = batchParam.kind === 'valid' ? batchParam.id : null;
  const batchFilterInvalid = batchParam.kind === 'invalid' ? batchParam.raw : null;
  /** 非法批次标识时整块隐藏队列 UI（fail-closed：绝不显示无筛选的全局队列）。 */
  const queueVisible = batchFilterInvalid === null;
  // 队列筛选全部以 URL 为唯一事实源（Issue #96）：工作台跳转携带筛选、刷新/分享/回退可恢复。
  const statusParam = searchParams.get(REVIEWS_STATUS_PARAM);
  const status: ReviewCaseStatus = statusParam === 'RESOLVED' || statusParam === 'DISMISSED'
    ? statusParam
    : 'OPEN';
  const reasonCode = searchParams.get(REVIEWS_REASON_PARAM) || undefined;
  const team = searchParams.get(REVIEWS_TEAM_PARAM) || undefined;

  /**
   * Issue #106：进入收件箱时按岗位团队默认预筛。默认值写进 URL（URL 保持唯一事实源、
   * 分享链接如实反映所见），且只在首挂载、URL 未带 responsible_team 时写入——
   * URL 已有该参数时以 URL 为准（spec 故事 27）；「看全部」清除后本次挂载内不再回填。
   * 岗位只是视图（D1）：没有岗位或财务（无团队）时不加任何过滤。
   */
  const roleTeam = reviewTeamForRole(readStoredWorkbenchRole());
  const defaultTeamApplied = useRef(false);
  useEffect(() => {
    if (defaultTeamApplied.current) return;
    defaultTeamApplied.current = true;
    if (!roleTeam || searchParams.has(REVIEWS_TEAM_PARAM)) return;
    const next = new URLSearchParams(searchParams);
    next.set(REVIEWS_TEAM_PARAM, roleTeam);
    setSearchParams(next, { replace: true });
    // eslint-disable-next-line react-hooks/exhaustive-deps -- 仅首挂载应用一次默认预筛
  }, []);
  const roleFilterActive = Boolean(roleTeam) && team === roleTeam;
  /** URL 筛选变化（含浏览器回退/前进）时回到第一页，避免带着旧页码看新筛选。 */
  const urlFilterKey = `${status}|${reasonCode ?? ''}|${team ?? ''}|${batchId ?? ''}|${batchFilterInvalid ?? ''}`;
  const { page, size, setPage, onPageChange } = useQueuePagination(urlFilterKey);
  const [selected, setSelected] = useState<ReviewCase | null>(null);
  const [selectedQueueIndex, setSelectedQueueIndex] = useState<number | null>(null);
  const [selectedRowKeys, setSelectedRowKeys] = useState<Key[]>([]);
  const [batchNote, setBatchNote] = useState('');
  const [batchSubmitting, setBatchSubmitting] = useState(false);
  const [batchResult, setBatchResult] = useState<BatchReviewResult>();
  const [hiddenBatchSuccessIds, setHiddenBatchSuccessIds] = useState<Set<string>>(() => new Set());
  const [queueRevision, setQueueRevision] = useState(0);
  const queueContextKey = `${urlFilterKey}|${page}|${size}`;
  const [selectionContextKey, setSelectionContextKey] = useState(queueContextKey);
  const queueContextRef = useRef(queueContextKey);
  const batchGeneration = useRef(0);
  if (queueContextRef.current !== queueContextKey) {
    queueContextRef.current = queueContextKey;
    batchGeneration.current += 1;
  }

  useEffect(() => {
    setSelectedRowKeys([]);
    setSelectionContextKey(queueContextKey);
    setBatchNote('');
    setBatchResult(undefined);
    setHiddenBatchSuccessIds(new Set());
  }, [queueContextKey]);

  const queue = useAsync(
    () => !queueVisible
      ? Promise.resolve({ items: [], page, size, total_elements: 0, total_pages: 0 })
      : reviewCasesApi.list({
          page, size, status, reason_code: reasonCode, responsible_team: team,
          import_batch_id: batchId ?? undefined,
        }),
    [page, size, status, team, reasonCode, batchId, queueVisible],
  );
  useEffect(() => {
    if (queue.data) setQueueRevision((current) => current + 1);
  }, [queue.data]);
  const batch = useAsync(
    () => (batchId ? fileOperationsApi.getSourceBatch(batchId) : Promise.resolve(null)),
    [batchId],
  );
  const batchMissing = batch.error instanceof ApiError && batch.error.status === 404;
  const rawItems = useMemo(() => queue.data?.items ?? [], [queue.data]);
  const items = useMemo(
    () => rawItems.filter((item) => !hiddenBatchSuccessIds.has(item.id)),
    [hiddenBatchSuccessIds, rawItems],
  );
  const selectedItems = useMemo(() => {
    const selectedIds = new Set(selectedRowKeys.map(String));
    return items.filter((item) => selectedIds.has(item.id));
  }, [items, selectedRowKeys]);
  const nextSelected = useMemo(() => {
    if (!selected) return null;
    const currentIndex = items.findIndex((item) => item.id === selected.id);
    const nextIndex = currentIndex >= 0 ? currentIndex + 1 : selectedQueueIndex;
    return nextIndex === null ? null : items[nextIndex] ?? null;
  }, [items, selected, selectedQueueIndex]);
  const selectedReasonCodes = new Set(selectedItems.map((item) => item.reason_code));
  const sameBatchType = selectedReasonCodes.size <= 1;
  const canBatchResolve = selectedItems.length > 0
    && sameBatchType
    && selectionContextKey === queueContextKey
    && !queue.loading
    && selectedItems.every((item) => item.allowed_actions.includes('RESOLVE_MANUALLY'));
  const canBatchDismiss = selectedItems.length > 0
    && sameBatchType
    && selectionContextKey === queueContextKey
    && !queue.loading
    && selectedItems.every((item) => item.allowed_actions.includes('DISMISS'));
  const hiddenOnCurrentPage = rawItems.filter((item) => hiddenBatchSuccessIds.has(item.id)).length;

  async function runBatch(action: BatchReviewAction) {
    const rows = [...selectedItems];
    const startedInContext = queueContextKey;
    const startedGeneration = ++batchGeneration.current;
    const actionAllowed = action === 'RESOLVE_MANUALLY' ? canBatchResolve : canBatchDismiss;
    if (!actionAllowed || rows.length === 0 || batchSubmitting) return;

    setBatchSubmitting(true);
    setBatchResult(undefined);
    const succeededIds: string[] = [];
    const failures: BatchReviewFailure[] = [];
    for (const item of rows) {
      try {
        // 每次 API 调用都会独立经过 writeHeaders → trustedWriteHeaders → newRequestId；
        // 因而每行既是独立事务，也使用兼容明文 HTTP 的独立幂等键。
        if (action === 'RESOLVE_MANUALLY') {
          await reviewCasesApi.resolve(item.id, buildManualResolution(item, batchNote));
        } else {
          await reviewCasesApi.dismiss(item.id, buildDismissCommand(item, batchNote));
        }
        succeededIds.push(item.id);
      } catch (error) {
        failures.push({ id: item.id, caseNo: item.case_no, message: batchFailureMessage(error) });
      }
    }

    // 用户可能在逐行提交期间切换筛选/页码；写操作照常完成，但旧上下文结果不得污染新队列。
    if (queueContextRef.current !== startedInContext || batchGeneration.current !== startedGeneration) {
      setBatchSubmitting(false);
      queue.reload();
      return;
    }
    setHiddenBatchSuccessIds((current) => new Set([...current, ...succeededIds]));
    setSelectedRowKeys(failures.map((item) => item.id));
    setBatchResult({ successCount: succeededIds.length, failures });
    if (failures.length === 0) setBatchNote('');
    setBatchSubmitting(false);
    queue.reload();
  }

  function selectCase(item: ReviewCase | null) {
    if (!item) {
      setSelected(null);
      setSelectedQueueIndex(null);
      return;
    }
    const queueIndex = items.findIndex((candidate) => candidate.id === item.id);
    if (queueIndex >= 0) setSelectedQueueIndex(queueIndex);
    setSelected(item);
  }

  /**
   * 队列筛选变更：写回 URL（保留 import_batch 等其他参数）并回到第一页。
   * null 表示清空该筛选（删除参数），undefined 表示不修改。
   * status 始终显式写出（含默认 OPEN），与工作台跳转链接的 URL 形态保持一致。
   */
  function updateQueueFilters(updates: {
    status?: ReviewCaseStatus | null;
    reasonCode?: string | null;
    team?: string | null;
  }) {
    const next = new URLSearchParams(searchParams);
    if (updates.status !== undefined) {
      if (updates.status) next.set(REVIEWS_STATUS_PARAM, updates.status);
      else next.delete(REVIEWS_STATUS_PARAM);
    }
    if (updates.reasonCode !== undefined) {
      if (updates.reasonCode) next.set(REVIEWS_REASON_PARAM, updates.reasonCode);
      else next.delete(REVIEWS_REASON_PARAM);
    }
    if (updates.team !== undefined) {
      if (updates.team) next.set(REVIEWS_TEAM_PARAM, updates.team);
      else next.delete(REVIEWS_TEAM_PARAM);
    }
    setPage(0);
    setSearchParams(next);
  }

  const reviewColumns: ColumnsType<ReviewCase> = [
    { title: '复核单号', dataIndex: 'case_no', width: 170, render: (v: string) => <LongCode value={v} width={150} /> },
    { title: '待办事项', dataIndex: 'reason_code', width: 190, render: (value: string) => reasonLabel(value) },
    { title: '责任团队', dataIndex: 'responsible_team', width: 115, render: (value: string) => TEAM_LABELS[value] ?? value },
    {
      title: '关联订单', dataIndex: 'order_no', width: 170,
      render: (value?: string, record?: ReviewCase) => value
        ? <LongCode value={value} to={record?.order_id ? `/orders/${record.order_id}` : undefined} width={150} />
        : (record?.order_id ? <Typography.Link onClick={() => navigate(`/orders/${record.order_id}`)}>#{record.order_id}</Typography.Link> : '—'),
    },
    {
      title: '状态', dataIndex: 'status', width: 85,
      render: (value: ReviewCaseStatus) => <Tag color={reviewCaseStatusSemantic(value)}>{REVIEW_STATUS_LABELS[value] ?? value}</Tag>,
    },
    { title: '进入队列时间', dataIndex: 'created_at', width: 150, render: (value: string) => formatDateTime(value) },
    { title: '操作', key: 'action', width: 90, fixed: 'right', render: (_, item) => <Typography.Link onClick={() => selectCase(item)}>查看处理</Typography.Link> },
  ];

  return (
    <PageShell
      title="人工作业中心"
      description="阻断复核需要明确解决；运营提醒只记录知晓，不推进业务状态。"
      icon={<CheckSquareOutlined />}
      actions={<Link to={alertsQueueUrl()}>运营提醒</Link>}
    >
      {batchFilterInvalid !== null ? (
        <Alert
          type="error"
          showIcon
          message="导入批次标识无效"
          description={invalidBatchIdMessage(batchFilterInvalid, '加载复核队列')}
          action={<Link to="/fulfillment/sales-outbound">返回文件作业</Link>}
        />
      ) : batchId ? (
        <Card size="small">
          {batch.loading ? (
            <Typography.Text type="secondary">正在核对导入批次 {batchId}…</Typography.Text>
          ) : batch.error ? (
            <Alert
              type={batchMissing ? 'warning' : 'error'}
              showIcon
              message={batchMissing ? '导入批次不存在' : '导入批次加载失败'}
              description={batchMissing
                ? `批次 ${batchId} 不存在或已被清理，请核对分享链接。`
                : errorMessage(batch.error)}
              action={<Link to="/fulfillment/sales-outbound">返回文件作业</Link>}
            />
          ) : batch.data ? (
            batch.data.confirmed_at ? (
              <Alert
                type="success"
                showIcon
                message="本批次已确认"
                description={`批次 ${batch.data.batch_no} 已于 ${dayjs(batch.data.confirmed_at).format('YYYY-MM-DD HH:mm')}${batch.data.confirmed_by ? ` 由 ${batch.data.confirmed_by}` : ''} 确认，无需继续处理。`}
                action={<Link to={fileJobUrlForBatch(batchId)}>返回该批次</Link>}
              />
            ) : (
              <Alert
                type="info"
                showIcon
                message={`正在复核导入批次 ${batch.data.batch_no}`}
                description={`共 ${batch.data.row_counts.total} 行，已接收 ${batch.data.row_counts.accepted} 行，待复核 ${batch.data.row_counts.need_review} 行，拒绝 ${batch.data.row_counts.rejected} 行。处理完成后可返回该批次统一确认。`}
                action={<Link to={fileJobUrlForBatch(batchId)}>返回该批次</Link>}
              />
            )
          ) : null}
        </Card>
      ) : null}
      {selectedItems.length > 0 ? (
        <Card size="small" title={`批量处理（已选 ${selectedItems.length} 项）`}>
          <Space direction="vertical" size={12} style={{ width: '100%' }}>
            {!sameBatchType ? (
              <Alert type="warning" showIcon message="批量处理需选择同类事项" />
            ) : !canBatchResolve && !canBatchDismiss ? (
              <Alert type="warning" showIcon message="所选事项没有共同的批量动作" />
            ) : null}
            <Input.TextArea
              aria-label="批量处理备注"
              value={batchNote}
              onChange={(event) => setBatchNote(event.target.value)}
              rows={2}
              maxLength={1000}
              showCount
              placeholder="填写本次批量处理依据（可选）"
            />
            <Space wrap>
              <Button
                type="primary"
                disabled={!canBatchResolve || batchSubmitting}
                loading={batchSubmitting}
                onClick={() => void runBatch('RESOLVE_MANUALLY')}
              >
                批量标记已处理（{selectedItems.length}）
              </Button>
              <Popconfirm
                title="确认批量关闭所选复核事项？"
                description="每项会独立提交并记录审计；失败项保留在队列中。"
                okText="确认关闭"
                cancelText="取消"
                okButtonProps={{ danger: true }}
                disabled={!canBatchDismiss || batchSubmitting}
                onConfirm={() => runBatch('DISMISS')}
              >
                <Button danger disabled={!canBatchDismiss || batchSubmitting}>批量关闭（{selectedItems.length}）</Button>
              </Popconfirm>
              <Typography.Text type="secondary">
                仅支持无需客户、SKU 等专用输入的同类事项。
              </Typography.Text>
            </Space>
          </Space>
        </Card>
      ) : null}
      {batchResult ? (
        <Alert
          type={batchResult.failures.length === 0 ? 'success' : 'warning'}
          showIcon
          message={`批量处理完成：成功 ${batchResult.successCount} 项，失败 ${batchResult.failures.length} 项`}
          description={batchResult.failures.length > 0 ? (
            <Space direction="vertical" size={2}>
              {batchResult.failures.map((failure) => (
                <Typography.Text key={failure.id} type="danger">
                  {failure.caseNo}：{failure.message}
                </Typography.Text>
              ))}
            </Space>
          ) : undefined}
        />
      ) : null}
      {queueVisible ? (
        <QueueTable<ReviewCase>
          rowKey="id"
          columns={reviewColumns}
          items={items}
          rowSelection={{
            selectedRowKeys,
            onChange: (keys) => {
              if (!batchSubmitting) {
                const nextIds = new Set(keys.map(String));
                const failureIds = new Set(batchResult?.failures.map((failure) => failure.id) ?? []);
                const keepsFailureRetry = failureIds.size > 0
                  && nextIds.size === failureIds.size
                  && [...failureIds].every((id) => nextIds.has(id));
                if (!keepsFailureRetry) {
                  setBatchNote('');
                  setBatchResult(undefined);
                }
                setSelectedRowKeys(keys);
                setSelectionContextKey(queueContextKey);
              }
            },
            getCheckboxProps: (item) => ({
              disabled: batchSubmitting || !batchSelectable(item),
              title: batchSelectable(item) ? undefined : '此事项需使用专用表单，不能批量处理',
            }),
          }}
          loading={queue.loading}
          error={queue.error}
          errorTitle="复核队列加载失败"
          emptyText={
            <Empty
              image={Empty.PRESENTED_IMAGE_SIMPLE}
              description={roleFilterActive ? (
                <>
                  你这个团队当前没有待办{' '}
                  <Typography.Link onClick={() => updateQueueFilters({ team: null })}>看全部</Typography.Link>
                </>
              ) : '当前没有复核事项'}
            />
          }
          total={Math.max(0, (queue.data?.total_elements ?? 0) - hiddenOnCurrentPage)}
          page={page}
          pageSize={size}
          onPageChange={(nextPage, nextSize) => {
            if (!batchSubmitting) onPageChange(nextPage, nextSize);
          }}
          onReload={queue.reload}
          filterControls={
            <>
              <Typography.Text type="secondary">状态</Typography.Text>
              <Select<ReviewCaseStatus>
                id="review-status-filter"
                value={status} style={{ width: 130 }}
                disabled={batchSubmitting}
                onChange={(value) => updateQueueFilters({ status: value })}
                options={Object.entries(REVIEW_STATUS_LABELS).map(([value, label]) => ({ value: value as ReviewCaseStatus, label }))}
              />
              <Typography.Text type="secondary">事项类型</Typography.Text>
              <Select
                id="review-reason-filter"
                allowClear
                showSearch
                optionFilterProp="label"
                placeholder="全部事项"
                value={reasonCode} style={{ width: 200 }}
                disabled={batchSubmitting}
                onChange={(value) => updateQueueFilters({ reasonCode: value ?? null })}
                options={Object.entries(REASON_LABELS).map(([value, label]) => ({ value, label }))}
              />
              <Typography.Text type="secondary">责任团队</Typography.Text>
              <Select
                id="review-team-filter"
                allowClear placeholder="全部团队"
                value={team} style={{ width: 160 }}
                disabled={batchSubmitting}
                onChange={(value) => updateQueueFilters({ team: value ?? null })}
                options={TEAM_OPTIONS}
              />
              {roleFilterActive ? (
                <>
                  <Tag>已按岗位预筛：{TEAM_LABELS[roleTeam as string] ?? roleTeam}</Tag>
                  <Typography.Link onClick={() => updateQueueFilters({ team: null })}>看全部</Typography.Link>
                </>
              ) : null}
            </>
          }
        />
      ) : null}

      <ReviewCaseDrawer
        selected={selected}
        nextCase={nextSelected}
        queueRevision={queueRevision}
        onClose={() => selectCase(null)}
        onQueueReload={queue.reload}
        onRefreshCase={selectCase}
      />
    </PageShell>
  );
}
