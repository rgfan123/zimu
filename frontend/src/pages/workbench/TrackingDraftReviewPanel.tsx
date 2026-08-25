import { useEffect, useMemo, useState } from 'react';
import {
  Alert,
  Button,
  Descriptions,
  Divider,
  Empty,
  Input,
  Modal,
  Select,
  Space,
  Table,
  Tag,
  Typography,
} from 'antd';
import { ReloadOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import { errorMessage } from '@/api/client';
import type { ReviewCase } from '@/api/types';
import {
  buildTrackingDraftConfirmCommand,
  buildTrackingDraftRejectCommand,
  initialTrackingDraftReviewForm,
  trackingDraftBlockingIssues,
  trackingDraftIssueLabel,
  type TrackingDraftCarrierOption,
  type TrackingDraftDetail,
  type TrackingDraftReviewForm,
  type TrackingDraftTaskCandidate,
} from './trackingDraftReview';
import {
  trackingDraftReviewApi,
  type TrackingDraftBatchLine,
  type TrackingDraftBatchConfirmResult,
} from './trackingDraftReviewApi';

interface TrackingDraftReviewPanelProps {
  reviewCase: ReviewCase;
  onCompleted: (result: TrackingDraftDetail) => void;
}

export default function TrackingDraftReviewPanel({
  reviewCase,
  onCompleted,
}: TrackingDraftReviewPanelProps) {
  const [draft, setDraft] = useState<TrackingDraftDetail>();
  const [form, setForm] = useState<TrackingDraftReviewForm>();
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string>();
  const [submitError, setSubmitError] = useState<string>();
  const [submitting, setSubmitting] = useState(false);
  const [reloadSequence, setReloadSequence] = useState(0);
  const [siblingDrafts, setSiblingDrafts] = useState<TrackingDraftDetail[]>([]);
  const [selectedDraftIds, setSelectedDraftIds] = useState<string[]>([]);
  const [batchSubmitting, setBatchSubmitting] = useState(false);
  const [batchResult, setBatchResult] = useState<TrackingDraftBatchConfirmResult>();
  const [batchLoadError, setBatchLoadError] = useState<string>();
  const [rejectOpen, setRejectOpen] = useState(false);
  const [rejectReason, setRejectReason] = useState('');
  const [rejectSubmitting, setRejectSubmitting] = useState(false);
  const [rejectError, setRejectError] = useState<string>();

  useEffect(() => {
    let active = true;
    setLoading(true);
    setLoadError(undefined);
    setSubmitError(undefined);

    trackingDraftReviewApi.detail(reviewCase.subject_id)
      .then((current) => {
        if (!active) return;
        if (
          current.status === 'OPEN'
          && reviewCase.status === 'OPEN'
          && current.review_case_id !== reviewCase.id
        ) {
          throw new Error('运单草稿已由其他复核事项接管，请刷新队列');
        }
        setDraft(current);
        setForm(initialTrackingDraftReviewForm(current));
      })
      .catch((error) => {
        if (active) setLoadError(displayError(error));
      })
      .finally(() => {
        if (active) setLoading(false);
      });

    return () => {
      active = false;
    };
  }, [reloadSequence, reviewCase.id, reviewCase.status, reviewCase.subject_id]);

  // 同一条消息提交的待确认运单草稿（批量确认区）：只有已通过校验的行可被选中
  useEffect(() => {
    let active = true;
    setBatchLoadError(undefined);
    if (!draft || draft.status !== 'OPEN') {
      setSiblingDrafts([]);
      setSelectedDraftIds([]);
      return;
    }
    trackingDraftReviewApi.listBySubmission(draft.submission_id)
      .then((data) => {
        if (!active) return;
        const rows = data.items;
        setSiblingDrafts(rows);
        setSelectedDraftIds(rows
          .filter((row) => row.status === 'OPEN' && batchConfirmable(row))
          .map((row) => row.id));
      })
      .catch(() => {
        if (active) setBatchLoadError('同批运单草稿加载失败，请刷新重试');
      });
    return () => {
      active = false;
    };
  }, [draft, reloadSequence]);

  const canBatch = reviewCase.status === 'OPEN'
    && siblingDrafts.length > 0
    && selectedDraftIds.length > 0
    && !batchSubmitting;

  async function confirmBatch() {
    if (!draft || selectedDraftIds.length === 0) return;
    setBatchSubmitting(true);
    setBatchResult(undefined);
    try {
      const lines: TrackingDraftBatchLine[] = selectedDraftIds
        .map((id) => siblingDrafts.find((row) => row.id === id))
        .filter((row): row is TrackingDraftDetail => Boolean(row))
        .map((row) => {
          const command = buildTrackingDraftConfirmCommand(
            row,
            row.review_case_version,
            initialTrackingDraftReviewForm(row),
          );
          return {
            draft_id: row.id,
            idempotency_key: crypto.randomUUID(),
            expected_draft_revision: command.expected_draft_revision,
            expected_case_version: command.expected_case_version,
            task_id: command.task_id,
            carrier_code: command.carrier_code,
            actual_quantity: command.actual_quantity,
            remark: command.remark,
          };
        });
      const result = await trackingDraftReviewApi.batchConfirm(lines);
      setBatchResult(result);
      const succeededIds = new Set(
        result.results.filter((item) => item.success).map((item) => item.draft_id),
      );
      // 成功行从批量区移除（已解决事项），失败行保留 OPEN 与可执行错误，供重试或单独处理
      setSiblingDrafts((current) => current.filter((row) => !succeededIds.has(row.id)));
      setSelectedDraftIds((current) => current.filter((id) => !succeededIds.has(id)));
      if (result.failure_count === 0) {
        onCompleted(draft);
      }
    } catch (error) {
      setBatchResult({
        results: [],
        success_count: 0,
        failure_count: 1,
      });
      setSubmitError(displayError(error));
    } finally {
      setBatchSubmitting(false);
    }
  }

  const blockingIssues = useMemo(
    () => (draft && form ? trackingDraftBlockingIssues(draft, form) : []),
    [draft, form],
  );
  const canWrite = reviewCase.status === 'OPEN'
    && reviewCase.allowed_actions.includes('CONFIRM_TRACKING_DRAFT')
    && draft?.status === 'OPEN'
    && draft.review_case_id === reviewCase.id
    && draft.review_case_version != null;

  const canReject = reviewCase.status === 'OPEN'
    && reviewCase.allowed_actions.includes('REJECT_TRACKING_DRAFT')
    && draft?.status === 'OPEN'
    && draft.review_case_id === reviewCase.id
    && draft.review_case_version != null;

  async function rejectDraft() {
    if (!draft || !canReject) return;
    setRejectSubmitting(true);
    setRejectError(undefined);
    try {
      const result = await trackingDraftReviewApi.reject(
        draft.id,
        buildTrackingDraftRejectCommand(draft, draft.review_case_version, rejectReason),
      );
      setRejectOpen(false);
      setRejectReason('');
      onCompleted(result);
    } catch (error) {
      setRejectError(displayError(error));
    } finally {
      setRejectSubmitting(false);
    }
  }

  async function confirmDraft() {
    if (!draft || !form) return;
    setSubmitting(true);
    setSubmitError(undefined);
    try {
      const result = await trackingDraftReviewApi.confirm(
        draft.id,
        buildTrackingDraftConfirmCommand(draft, draft.review_case_version, form),
      );
      onCompleted(result);
    } catch (error) {
      setSubmitError(displayError(error));
    } finally {
      setSubmitting(false);
    }
  }

  if (loading) {
    return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="正在加载运单草稿…" />;
  }
  if (loadError) {
    return (
      <Alert
        type="error"
        showIcon
        message="运单草稿加载失败"
        description={loadError}
        action={<Button size="small" icon={<ReloadOutlined />} onClick={() => setReloadSequence((value) => value + 1)}>重新加载</Button>}
      />
    );
  }
  if (!draft || !form) return null;

  const selectedTask = draft.task_candidates.find((candidate) => candidate.task_id === form.task_id);
  const selectedCarrier = draft.carrier_candidates.find((candidate) => candidate.code === form.carrier_code);
  const selectedManualCarrier = draft.manual_carrier_options.find((candidate) => candidate.code === form.carrier_code);

  return (
    <Space direction="vertical" size={20} style={{ width: '100%' }}>
      <Alert
        type={draft.status === 'OPEN' ? 'info' : draft.status === 'CONFIRMED' ? 'success' : 'warning'}
        showIcon
        message={draftStatusLabel(draft.status)}
        description={draft.status === 'OPEN'
          ? '只有系统确定性解析的唯一任务与标准物流公司可被确认；确认后不会从企微消息推断实际发货时间。'
          : draft.status === 'CONFIRMED'
            ? '该草稿已确认并形成正式运单事实，当前为只读状态。'
            : '该草稿已结束，没有形成新的运单事实。'}
      />

      <Descriptions size="small" column={3} items={[
        { key: 'draft', label: '草稿编号', children: draft.draft_no },
        { key: 'line', label: '消息行', children: `第 ${draft.line_no} 行` },
        { key: 'created', label: '接收时间', children: dayjs(draft.created_at).format('YYYY-MM-DD HH:mm') },
        { key: 'status', label: '状态', children: <Tag>{draftStatusLabel(draft.status)}</Tag> },
        { key: 'draft-version', label: '草稿版本', children: draft.revision },
        { key: 'case-version', label: '复核版本', children: draft.review_case_version ?? '—' },
      ]} />

      <section>
        <Typography.Title level={5} style={{ margin: 0 }}>原始回传内容</Typography.Title>
        <Typography.Text type="secondary">仅展示这条回传中需要核对的信息。</Typography.Text>
        <Descriptions
          bordered
          size="small"
          column={2}
          style={{ marginTop: 12 }}
          items={[
            { key: 'receiver', label: '收货人原值', children: draft.raw_receiver_name || '—' },
            { key: 'masked', label: '匹配姓名', children: draft.masked_receiver_name || '—' },
            { key: 'tracking', label: '运单号', span: 2, children: <Typography.Text copyable>{draft.tracking_no || '—'}</Typography.Text> },
          ]}
        />
      </section>

      <Divider style={{ margin: 0 }} />

      <section>
        <Typography.Title level={5} style={{ margin: 0 }}>发货任务</Typography.Title>
        <Typography.Text type="secondary">候选只展示确定性匹配结果；零命中时可输入完整系统任务号，服务端会重新校验待回传范围。</Typography.Text>
        <Select<string>
          value={form.task_id || undefined}
          disabled={!canWrite || draft.task_candidates.length === 0}
          onChange={(value) => setForm((current) => current ? { ...current, task_id: value, task_no: '' } : current)}
          placeholder="未唯一确定发货任务"
          style={{ width: '100%', marginTop: 12 }}
          options={draft.task_candidates.map((candidate) => ({
            value: candidate.task_id,
            label: taskCandidateLabel(candidate),
          }))}
        />
        <Input
          value={form.task_no}
          disabled={!canWrite}
          onChange={(event) => setForm((current) => current
            ? { ...current, task_id: '', task_no: event.target.value }
            : current)}
          placeholder="输入完整系统任务号"
          style={{ marginTop: 8 }}
        />
        {selectedTask ? (
          <Descriptions
            size="small"
            column={2}
            style={{ marginTop: 12 }}
            items={[
              { key: 'task', label: '系统任务号', children: selectedTask.fulfillment_no || '—' },
              { key: 'order', label: '订单号', children: selectedTask.order_no || '—' },
              { key: 'receiver', label: '收货人快照', children: selectedTask.receiver_name || '—' },
              { key: 'quantity', label: '本批指令数量', children: selectedTask.instructed_quantity || '—' },
              { key: 'shipment', label: '待回传发货批次', children: selectedTask.shipment_id || '—' },
            ]}
          />
        ) : null}
      </section>

      <section>
        <Typography.Title level={5} style={{ margin: 0 }}>物流公司</Typography.Title>
        <Typography.Text type="secondary">匹配依据与人工可选的启用物流主数据分开展示。</Typography.Text>
        <Select<string>
          value={form.carrier_code || undefined}
          disabled={!canWrite || draft.manual_carrier_options.length === 0}
          onChange={(value) => setForm((current) => current ? { ...current, carrier_code: value } : current)}
          placeholder="未确定物流公司"
          style={{ width: '100%', marginTop: 12 }}
          options={draft.manual_carrier_options.map((candidate) => ({
            value: candidate.code,
            label: carrierOptionLabel(candidate),
          }))}
        />
        {selectedCarrier ? (
          <Typography.Paragraph type="secondary" style={{ margin: '8px 0 0' }}>
            匹配依据：{selectedCarrier.source === 'STATED' ? '消息明示物流公司' : '运单前缀主数据'}
          </Typography.Paragraph>
        ) : selectedManualCarrier ? (
          <Typography.Paragraph type="secondary" style={{ margin: '8px 0 0' }}>
            匹配依据：人工选择的启用物流公司
          </Typography.Paragraph>
        ) : null}
      </section>

      <Alert
        type={draft.default_full_shipment ? 'success' : 'warning'}
        showIcon
        message={draft.default_full_shipment ? '默认按整项发货' : '不能按整项发货确认'}
        description={draft.default_full_shipment
          ? `本行未明示部分发货或异常，确认时将使用该发货批次的全部指令数量${selectedTask?.instructed_quantity ? ` ${selectedTask.instructed_quantity}` : ''}。`
          : '本草稿包含部分发货、缺货、异常或无法识别的判断，请保留在复核队列并由履约运营核对。'}
      />

      {reviewCase.status === 'OPEN' && draft.status === 'OPEN' && !canWrite ? (
        <Alert
          type="warning"
          showIcon
          message="当前复核事项不可确认运单"
          description="请刷新队列并核对事项状态；若仍无法处理，请联系管理员。"
        />
      ) : null}

      {draft.validation_issues.length ? (
        <Alert
          type="warning"
          showIcon
          message="校验问题"
          description={(
            <Space direction="vertical" size={2}>
              {draft.validation_issues.map((issue, index) => (
                <Typography.Text key={`${issue}-${index}`}>{trackingDraftIssueLabel(issue)}</Typography.Text>
              ))}
            </Space>
          )}
        />
      ) : null}

      {siblingDrafts.length > 0 ? (
        <section>
          <Typography.Title level={5} style={{ margin: 0 }}>批量确认同批回传</Typography.Title>
          <Typography.Text type="secondary">
            同一消息提交的待确认运单草稿；只有已通过校验的行可勾选。逐行独立事务，失败行保持待确认且不影响成功行。
          </Typography.Text>
          {batchLoadError ? (
            <Alert
              style={{ marginTop: 12 }}
              type="error"
              showIcon
              message={batchLoadError}
              action={<Button size="small" icon={<ReloadOutlined />} onClick={() => setReloadSequence((value) => value + 1)}>重新加载</Button>}
            />
          ) : (
            <Table<TrackingDraftDetail>
              rowKey="id"
              size="small"
              style={{ marginTop: 12 }}
              dataSource={siblingDrafts}
              pagination={false}
              rowSelection={{
                selectedRowKeys: selectedDraftIds,
                onChange: (keys) => setSelectedDraftIds(keys.map(String)),
                getCheckboxProps: (row) => ({
                  disabled: row.status !== 'OPEN' || !batchConfirmable(row),
                }),
              }}
              columns={[
                { title: '行号', dataIndex: 'line_no', width: 64 },
                { title: '草稿编号', dataIndex: 'draft_no', width: 150 },
                {
                  title: '匹配姓名',
                  dataIndex: 'masked_receiver_name',
                  width: 130,
                  render: (_: unknown, row) => row.masked_receiver_name || row.raw_receiver_name || '—',
                },
                {
                  title: '发货任务',
                  width: 180,
                  render: (_: unknown, row) => row.task_candidates[0]
                    ? taskCandidateLabel(row.task_candidates[0])
                    : '未匹配任务',
                },
                {
                  title: '指令数量',
                  width: 110,
                  render: (_: unknown, row) => row.task_candidates[0]?.instructed_quantity ?? '—',
                },
                {
                  title: '物流公司',
                  width: 130,
                  render: (_: unknown, row) => row.carrier_candidates[0]?.name
                    ?? row.manual_carrier_options.find((option) => option.code === row.carrier_code)?.name
                    ?? row.carrier_code
                    ?? '—',
                },
                { title: '运单号', dataIndex: 'tracking_no', render: (value: string | null) => value || '—' },
              ]}
            />
          )}
          {batchResult ? (
            <Alert
              style={{ marginTop: 12 }}
              type={batchResult.failure_count === 0 ? 'success' : 'warning'}
              showIcon
              message={batchResultMessage(batchResult)}
              description={(
                <Space direction="vertical" size={2}>
                  {batchResult.results.filter((item) => !item.success).map((item) => (
                    <Typography.Text key={item.draft_id} type="danger">
                      草稿 {item.draft_id}：{item.message ?? item.business_code ?? '确认失败'}
                    </Typography.Text>
                  ))}
                </Space>
              )}
            />
          ) : null}
          {submitError && !batchResult ? (
            <Alert
              style={{ marginTop: 12 }}
              type="error"
              showIcon
              message="批量确认未完成"
              description={submitError}
            />
          ) : null}
          <Space wrap style={{ marginTop: 12 }}>
            <Button
              type="primary"
              disabled={!canBatch}
              loading={batchSubmitting}
              onClick={confirmBatch}
            >
              批量确认已勾选运单（{selectedDraftIds.length}）
            </Button>
            <Typography.Text type="secondary">
              每行使用独立事务与幂等键；成功后发货批次进入已发货，实际发货时间保持为空。
            </Typography.Text>
          </Space>
        </section>
      ) : null}

      <Divider style={{ margin: 0 }} />

      {canWrite ? (
        <>
          <Input.TextArea
            rows={3}
            maxLength={2000}
            showCount
            value={form.remark}
            onChange={(event) => setForm((current) => current ? { ...current, remark: event.target.value } : current)}
            placeholder="填写人工核对依据（可选）"
          />
          {blockingIssues.length ? (
            <Alert
              type="warning"
              showIcon
              message="还不能确认运单草稿"
              description={blockingIssues.join('；')}
            />
          ) : null}
          {submitError ? (
            <Alert
              type="error"
              showIcon
              message="确认未完成"
              description={submitError}
              action={<Button size="small" icon={<ReloadOutlined />} onClick={() => setReloadSequence((value) => value + 1)}>刷新草稿</Button>}
            />
          ) : null}
          <Space wrap>
            <Button
              type="primary"
              disabled={blockingIssues.length > 0 || submitting}
              loading={submitting}
              onClick={confirmDraft}
            >
              确认并记录运单
            </Button>
            {canReject ? (
              <Button danger onClick={() => setRejectOpen(true)}>拒绝该运单草稿</Button>
            ) : null}
            <Typography.Text type="secondary">确认后发货批次进入已发货，实际发货时间保持为空；无法处理的草稿可拒绝关闭。</Typography.Text>
          </Space>
        </>
      ) : null}

      <Modal
        title="拒绝运单草稿"
        open={rejectOpen}
        onCancel={() => {
          setRejectOpen(false);
          setRejectReason('');
          setRejectError(undefined);
        }}
        onOk={rejectDraft}
        okText="确认拒绝"
        okButtonProps={{ disabled: !rejectReason.trim() || rejectSubmitting }}
        confirmLoading={rejectSubmitting}
        cancelButtonProps={{ disabled: rejectSubmitting }}
      >
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          <Typography.Text type="secondary">
            拒绝后该草稿结束（事项关闭，不再参与确认），不会创建任何运单事实。请填写拒绝理由作为处理依据。
          </Typography.Text>
          <Input.TextArea
            rows={4}
            maxLength={2000}
            showCount
            value={rejectReason}
            onChange={(event) => setRejectReason(event.target.value)}
            placeholder="拒绝理由（必填）"
          />
          {rejectError ? (
            <Alert type="error" showIcon message="拒绝未完成" description={rejectError} />
          ) : null}
        </Space>
      </Modal>
    </Space>
  );
}

function taskCandidateLabel(candidate: TrackingDraftTaskCandidate): string {
  return [candidate.fulfillment_no, candidate.order_no, candidate.receiver_name]
    .filter((value): value is string => typeof value === 'string' && Boolean(value.trim()))
    .join(' · ') || '未命名发货任务';
}

function carrierOptionLabel(option: TrackingDraftCarrierOption): string {
  return [option.code, option.name]
    .filter((value): value is string => typeof value === 'string' && Boolean(value.trim()))
    .join(' · ') || '未命名物流公司';
}

function draftStatusLabel(status: TrackingDraftDetail['status']): string {
  if (status === 'CONFIRMED') return '运单草稿已确认';
  if (status === 'REJECTED') return '运单草稿已结束';
  return '待人工确认的运单草稿';
}

/** 批量可确认：已通过校验、能形成默认整项发货且表单无阻塞问题。 */
function batchConfirmable(draft: TrackingDraftDetail): boolean {
  if (draft.status !== 'OPEN' || draft.validation_issues.length > 0) return false;
  if (draft.review_case_id == null || draft.review_case_version == null) return false;
  return trackingDraftBlockingIssues(draft, initialTrackingDraftReviewForm(draft)).length === 0;
}

function batchResultMessage(result: TrackingDraftBatchConfirmResult): string {
  const replayed = result.results.filter((item) => item.success && item.replayed).length;
  const replaySuffix = replayed > 0 ? `（含已重放 ${replayed} 行）` : '';
  return `批量确认完成：成功 ${result.success_count} 行${replaySuffix}，失败 ${result.failure_count} 行`;
}

function displayError(error: unknown): string {
  if (error instanceof Error && !('status' in error)) return error.message;
  return errorMessage(error);
}
