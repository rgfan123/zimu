import { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Alert,
  Button,
  Card,
  DatePicker,
  Descriptions,
  Divider,
  Empty,
  Image,
  Input,
  Popconfirm,
  Select,
  Space,
  Tag,
  Typography,
} from 'antd';
import dayjs from 'dayjs';
import { errorMessage } from '@/api/client';
import { channelMessagesApi, customersApi, messageMediaContentUrl, messageSubmissionsApi, skusApi } from '@/api/endpoints';
import type {
  ChannelMessageDetail,
  MasterDataRecord,
  MessageSubmissionDetail,
  ReviewCase,
} from '@/api/types';
import {
  buildOrderDraftConfirmCommand,
  buildOrderDraftRejectCommand,
  initialOrderDraftReviewForm,
  ORDER_DRAFT_SETTLEMENT_METHODS,
  orderDraftMissingFields,
  orderDraftReviewPermissions,
  type OrderDraftCustomerCandidate,
  type OrderDraftDetail,
  type OrderDraftReviewForm,
  type OrderDraftSkuCandidate,
} from './orderDraftReview';
import { orderDraftReviewApi } from './orderDraftReviewApi';
import {
  emptyMasterDataOptionState,
  hasMoreMasterDataOptions,
  loadMasterDataOptionPage,
  type MasterDataOptionLoader,
} from './orderDraftMasterData';

const SETTLEMENT_LABELS: Record<(typeof ORDER_DRAFT_SETTLEMENT_METHODS)[number], string> = {
  MONTHLY: '月结',
  IMMEDIATE: '即时结算',
  CREDIT_TERM: '账期',
  PREPAID: '预付',
  COD: '货到付款',
  OTHER: '其他',
};

const MISSING_FIELD_LABELS: Record<string, string> = {
  customer: '客户',
  receiver_name: '收货人',
  receiver_phone: '收货电话',
  receiver_address: '收货地址',
  settlement_method: '结算方式',
  settlement_time: '结算时间',
  review_case: '复核版本',
  items: '订单行',
};

type CompletedAction = 'CONFIRMED' | 'REJECTED';

interface OrderDraftReviewPanelProps {
  reviewCase: ReviewCase;
  onCompleted: (action: CompletedAction, result: OrderDraftDetail) => void;
}

interface LoadedEvidence {
  submission: MessageSubmissionDetail;
  message: ChannelMessageDetail;
}

const loadCustomerOptions: MasterDataOptionLoader = (query) => customersApi.list(query);
const loadSkuOptions: MasterDataOptionLoader = ({ page, size }) => skusApi.list({ page, size });

export default function OrderDraftReviewPanel({ reviewCase, onCompleted }: OrderDraftReviewPanelProps) {
  const navigate = useNavigate();
  const [draft, setDraft] = useState<OrderDraftDetail>();
  const [evidence, setEvidence] = useState<LoadedEvidence>();
  const [form, setForm] = useState<OrderDraftReviewForm>();
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string>();
  const [submitError, setSubmitError] = useState<string>();
  const [submitting, setSubmitting] = useState<CompletedAction>();
  const [rejectReason, setRejectReason] = useState('');
  const [customerQuery, setCustomerQuery] = useState('');
  const [customerOptions, setCustomerOptions] = useState(emptyMasterDataOptionState);
  const [customerOptionsLoading, setCustomerOptionsLoading] = useState(false);
  const [customerOptionsError, setCustomerOptionsError] = useState<string>();
  const [skuOptions, setSkuOptions] = useState(emptyMasterDataOptionState);
  const [skuOptionsLoading, setSkuOptionsLoading] = useState(false);
  const [skuOptionsError, setSkuOptionsError] = useState<string>();
  const customerRequest = useRef(0);
  const skuRequest = useRef(0);

  useEffect(() => {
    let active = true;
    setLoading(true);
    setLoadError(undefined);
    setSubmitError(undefined);
    setDraft(undefined);
    setEvidence(undefined);
    setForm(undefined);
    setRejectReason('');
    setCustomerQuery('');
    setCustomerOptions(emptyMasterDataOptionState());
    setCustomerOptionsError(undefined);
    setSkuOptions(emptyMasterDataOptionState());
    setSkuOptionsError(undefined);

    async function load() {
      try {
        const currentDraft = await orderDraftReviewApi.detail(reviewCase.subject_id);
        if (
          currentDraft.status === 'OPEN'
          && reviewCase.status === 'OPEN'
          && currentDraft.review_case_id !== reviewCase.id
        ) {
          throw new Error('订单草稿已由其他复核事项接管，请刷新队列');
        }
        const submission = await messageSubmissionsApi.detail(currentDraft.submission_id);
        const sourceMessage = await channelMessagesApi.detail(submission.source_message_id);
        if (!active) return;
        setDraft(currentDraft);
        setEvidence({
          submission,
          message: sourceMessage,
        });
        setForm(initialOrderDraftReviewForm(currentDraft));
      } catch (error) {
        if (active) setLoadError(displayError(error));
      } finally {
        if (active) setLoading(false);
      }
    }

    void load();
    return () => {
      active = false;
    };
  }, [reviewCase.id, reviewCase.status, reviewCase.subject_id]);

  useEffect(() => {
    if (!draft) return;
    let active = true;
    const request = ++customerRequest.current;
    setCustomerOptions(emptyMasterDataOptionState(customerQuery));
    setCustomerOptionsLoading(true);
    const timer = window.setTimeout(() => {
      setCustomerOptionsError(undefined);
      void loadMasterDataOptionPage(
        loadCustomerOptions,
        emptyMasterDataOptionState(),
        { query: customerQuery, reset: true },
      )
        .then((next) => {
          if (active && request === customerRequest.current) setCustomerOptions(next);
        })
        .catch((error) => {
          if (active && request === customerRequest.current) setCustomerOptionsError(displayError(error));
        })
        .finally(() => {
          if (active && request === customerRequest.current) setCustomerOptionsLoading(false);
        });
    }, 250);
    return () => {
      active = false;
      window.clearTimeout(timer);
    };
  }, [customerQuery, draft?.id]);

  useEffect(() => {
    if (!draft) return;
    let active = true;
    const request = ++skuRequest.current;
    setSkuOptionsLoading(true);
    setSkuOptionsError(undefined);
    void loadMasterDataOptionPage(loadSkuOptions, emptyMasterDataOptionState(), { reset: true })
      .then((next) => {
        if (active && request === skuRequest.current) setSkuOptions(next);
      })
      .catch((error) => {
        if (active && request === skuRequest.current) setSkuOptionsError(displayError(error));
      })
      .finally(() => {
        if (active && request === skuRequest.current) setSkuOptionsLoading(false);
      });
    return () => {
      active = false;
    };
  }, [draft?.id]);

  const missingFields = useMemo(
    () => (draft && form ? orderDraftMissingFields(draft, form) : []),
    [draft, form],
  );
  const { canConfirm, canReject } = orderDraftReviewPermissions(
    reviewCase.status,
    draft?.status ?? 'REJECTED',
    reviewCase.allowed_actions,
  );
  const canWrite = canConfirm || canReject;
  const customerSelectOptions = useMemo(
    () => masterDataSelectOptions(customerOptions.items),
    [customerOptions.items],
  );
  const skuSelectOptions = useMemo(
    () => masterDataSelectOptions(skuOptions.items),
    [skuOptions.items],
  );

  function updateReceiver(field: keyof OrderDraftReviewForm['receiver'], value: string) {
    setForm((current) => current ? {
      ...current,
      receiver: { ...current.receiver, [field]: value },
    } : current);
  }

  function updateLine(lineNo: number, field: 'sku_id' | 'quantity', value: string) {
    setForm((current) => current ? {
      ...current,
      items: {
        ...current.items,
        [lineNo]: { ...current.items[lineNo], [field]: value },
      },
    } : current);
  }

  async function loadMoreCustomers() {
    if (customerOptionsLoading || !hasMoreMasterDataOptions(customerOptions)) return;
    const request = ++customerRequest.current;
    setCustomerOptionsLoading(true);
    setCustomerOptionsError(undefined);
    try {
      const next = await loadMasterDataOptionPage(loadCustomerOptions, customerOptions, {
        query: customerOptions.query,
      });
      if (request === customerRequest.current) setCustomerOptions(next);
    } catch (error) {
      if (request === customerRequest.current) setCustomerOptionsError(displayError(error));
    } finally {
      if (request === customerRequest.current) setCustomerOptionsLoading(false);
    }
  }

  async function loadMoreSkus() {
    if (skuOptionsLoading || !hasMoreMasterDataOptions(skuOptions)) return;
    const request = ++skuRequest.current;
    setSkuOptionsLoading(true);
    setSkuOptionsError(undefined);
    try {
      const next = await loadMasterDataOptionPage(loadSkuOptions, skuOptions);
      if (request === skuRequest.current) setSkuOptions(next);
    } catch (error) {
      if (request === skuRequest.current) setSkuOptionsError(displayError(error));
    } finally {
      if (request === skuRequest.current) setSkuOptionsLoading(false);
    }
  }

  async function confirmDraft() {
    if (!draft || !form) return;
    setSubmitting('CONFIRMED');
    setSubmitError(undefined);
    try {
      const result = await orderDraftReviewApi.confirm(
        draft.id,
        buildOrderDraftConfirmCommand(draft, form),
      );
      onCompleted('CONFIRMED', result);
    } catch (error) {
      setSubmitError(displayError(error));
    } finally {
      setSubmitting(undefined);
    }
  }

  async function rejectDraft() {
    if (!draft) return;
    setSubmitting('REJECTED');
    setSubmitError(undefined);
    try {
      const result = await orderDraftReviewApi.reject(
        draft.id,
        buildOrderDraftRejectCommand(draft, rejectReason),
      );
      onCompleted('REJECTED', result);
    } catch (error) {
      setSubmitError(displayError(error));
    } finally {
      setSubmitting(undefined);
    }
  }

  if (loading) {
    return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="正在加载订单草稿与原始消息…" />;
  }
  if (loadError) {
    return <Alert type="error" showIcon message="订单草稿加载失败" description={loadError} />;
  }
  if (!draft || !form || !evidence) return null;

  return (
    <Space direction="vertical" size={18} style={{ width: '100%' }}>
      <Alert
        type={draft.status === 'OPEN' ? 'info' : draft.status === 'CONFIRMED' ? 'success' : 'warning'}
        showIcon
        message={draftStatusLabel(draft.status)}
        description={draft.status === 'OPEN'
          ? '候选项只是系统根据确定性映射找到的建议；客户、SKU、数量和收结信息均以本次人工确认为准。'
          : draft.status === 'CONFIRMED'
            ? '该草稿已生成正式订单，不可再次修改。'
            : '该草稿已明确拒绝，未生成正式订单。'}
        action={draft.confirmed_order_id
          ? <Button size="small" onClick={() => navigate(`/orders/${draft.confirmed_order_id}`)}>查看正式订单</Button>
          : undefined}
      />

      {reviewCase.status === 'OPEN' && draft.status === 'OPEN' && !canWrite ? (
        <Alert
          type="warning"
          showIcon
          message="当前复核事项不可处理订单草稿"
          description="请刷新队列并核对事项权限；若仍无法处理，请联系管理员。"
        />
      ) : null}

      <Descriptions size="small" column={3} items={[
        { key: 'draft', label: '草稿编号', children: draft.draft_no },
        { key: 'source', label: '来源单号', children: draft.source_order_no },
        { key: 'submission', label: '消息提交', children: evidence.submission.submission_no },
        { key: 'revision', label: '草稿版本', children: draft.revision },
        { key: 'case-version', label: '复核版本', children: draft.review_case_version ?? '已结束' },
        { key: 'updated', label: '更新时间', children: dayjs(draft.updated_at).format('YYYY-MM-DD HH:mm') },
      ]} />

      <Card size="small" title="原始企微消息证据">
        <Typography.Paragraph style={{ whiteSpace: 'pre-wrap', marginBottom: evidence.message.quote_content ? 12 : 0 }}>
          {evidence.message.content}
        </Typography.Paragraph>
        {evidence.message.quote_content ? (
          <>
            <Divider style={{ margin: '12px 0' }} />
            <Typography.Text type="secondary">引用内容</Typography.Text>
            <Typography.Paragraph type="secondary" style={{ whiteSpace: 'pre-wrap', margin: '6px 0 0' }}>
              {evidence.message.quote_content}
            </Typography.Paragraph>
          </>
        ) : null}
        {evidence.message.media_refs && evidence.message.media_refs.length > 0 ? (
          <>
            <Divider style={{ margin: '12px 0' }} />
            <Typography.Text type="secondary">消息图片证据（点击查看原图）</Typography.Text>
            <Image.PreviewGroup>
              <Space wrap size={12} style={{ marginTop: 8 }}>
                {evidence.message.media_refs.map((media) => (
                  <Image
                    key={media.id}
                    width={120}
                    height={120}
                    style={{ objectFit: 'cover', borderRadius: 6 }}
                    src={messageMediaContentUrl(media.id)}
                    alt={`消息图片证据 ${media.id}`}
                  />
                ))}
              </Space>
            </Image.PreviewGroup>
          </>
        ) : null}
      </Card>

      <Card size="small" title="客户确认">
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          <Descriptions size="small" column={1} items={[
            { key: 'raw', label: '模型识别原值', children: draft.customer_name_raw || '—' },
            {
              key: 'candidates',
              label: '确定性候选',
              children: draft.customer_candidates.length
                ? <CandidateTags candidates={draft.customer_candidates} label={customerCandidateLabel} />
                : '未命中候选',
            },
          ]} />
          <Select<string>
            showSearch
            allowClear
            disabled={!canConfirm}
            loading={customerOptionsLoading}
            value={form.customer_id || undefined}
            onChange={(value) => setForm((current) => current ? { ...current, customer_id: value } : current)}
            onSearch={setCustomerQuery}
            placeholder="选择已确认客户"
            filterOption={false}
            style={{ width: '100%' }}
            options={customerSelectOptions}
          />
          <Space wrap size={8}>
            <Typography.Text type="secondary">
              输入客户名称或编码可搜索全部主数据；已加载 {customerOptions.items.length} / {customerOptions.totalElements} 条。
            </Typography.Text>
            {hasMoreMasterDataOptions(customerOptions) ? (
              <Button size="small" disabled={!canConfirm} loading={customerOptionsLoading} onClick={() => void loadMoreCustomers()}>加载更多客户</Button>
            ) : null}
          </Space>
          {customerOptionsError ? <Typography.Text type="danger">{customerOptionsError}</Typography.Text> : null}
          <Typography.Text type="secondary">本票只选择已有客户；新客户创建与渠道绑定由后续能力处理。</Typography.Text>
        </Space>
      </Card>

      <Card size="small" title="收货与结算确认">
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          <Typography.Text type="secondary">模型原始地址：{draft.receiver_address || '—'}</Typography.Text>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
            <Input disabled={!canConfirm} addonBefore="收货人" value={form.receiver.name} onChange={(event) => updateReceiver('name', event.target.value)} />
            <Input disabled={!canConfirm} addonBefore="电话" value={form.receiver.phone} onChange={(event) => updateReceiver('phone', event.target.value)} />
            <Input disabled={!canConfirm} addonBefore="省" value={form.receiver.province} onChange={(event) => updateReceiver('province', event.target.value)} />
            <Input disabled={!canConfirm} addonBefore="市" value={form.receiver.city} onChange={(event) => updateReceiver('city', event.target.value)} />
            <Input disabled={!canConfirm} addonBefore="区/县" value={form.receiver.district} onChange={(event) => updateReceiver('district', event.target.value)} />
            <Input disabled={!canConfirm} addonBefore="街道/乡镇" value={form.receiver.town} onChange={(event) => updateReceiver('town', event.target.value)} />
          </div>
          <Input.TextArea disabled={!canConfirm} rows={2} value={form.receiver.address} onChange={(event) => updateReceiver('address', event.target.value)} placeholder="详细地址" />
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}>
            <Select<string>
              disabled={!canConfirm}
              value={form.settlement_method || undefined}
              onChange={(value) => setForm((current) => current ? { ...current, settlement_method: value } : current)}
              placeholder="选择结算方式"
              options={ORDER_DRAFT_SETTLEMENT_METHODS.map((value) => ({ value, label: SETTLEMENT_LABELS[value] }))}
            />
            <DatePicker
              showTime
              disabled={!canConfirm}
              value={form.settlement_time ? dayjs(form.settlement_time) : null}
              onChange={(value) => setForm((current) => current ? {
                ...current,
                settlement_time: value ? value.toISOString() : '',
              } : current)}
              placeholder="选择结算时间"
              style={{ width: '100%' }}
            />
          </div>
        </Space>
      </Card>

      <Space direction="vertical" size={12} style={{ width: '100%' }}>
        <Typography.Text strong>订单行确认</Typography.Text>
        <Space wrap size={8}>
          <Typography.Text type="secondary">
            SKU 端点当前仅支持分页；可在已加载选项内搜索，已加载 {skuOptions.items.length} / {skuOptions.totalElements} 条。
          </Typography.Text>
          {hasMoreMasterDataOptions(skuOptions) ? (
            <Button size="small" disabled={!canConfirm} loading={skuOptionsLoading} onClick={() => void loadMoreSkus()}>加载更多 SKU</Button>
          ) : null}
        </Space>
        {skuOptionsError ? <Typography.Text type="danger">{skuOptionsError}</Typography.Text> : null}
        {draft.lines.map((line) => (
          <Card key={line.id} size="small" title={`第 ${line.line_no} 行`}>
            <Space direction="vertical" size={12} style={{ width: '100%' }}>
              <Descriptions size="small" column={2} items={[
                { key: 'product', label: '商品原值', children: line.product_name_raw || '—' },
                { key: 'spec', label: '规格/单位原值', children: [line.spec_raw, line.unit_raw].filter(Boolean).join(' · ') || '—' },
                {
                  key: 'candidates',
                  label: 'SKU 候选',
                  span: 2,
                  children: line.sku_candidates.length
                    ? <CandidateTags candidates={line.sku_candidates} label={skuCandidateLabel} />
                    : '未命中候选',
                },
              ]} />
              <div style={{ display: 'grid', gridTemplateColumns: 'minmax(0, 2fr) minmax(160px, 1fr)', gap: 12 }}>
                <Select<string>
                  showSearch
                  disabled={!canConfirm}
                  loading={skuOptionsLoading}
                  value={form.items[line.line_no]?.sku_id || undefined}
                  onChange={(value) => updateLine(line.line_no, 'sku_id', value)}
                  placeholder="选择已确认 SKU"
                  optionFilterProp="label"
                  options={skuSelectOptions}
                />
                <Input
                  disabled={!canConfirm}
                  addonBefore="数量"
                  value={form.items[line.line_no]?.quantity ?? ''}
                  onChange={(event) => updateLine(line.line_no, 'quantity', event.target.value)}
                  placeholder="最多三位小数"
                />
              </div>
              <Typography.Text type="secondary">履约方由所选 SKU 的主数据关系在服务端派生，不接受模型或人工直接指定。</Typography.Text>
            </Space>
          </Card>
        ))}
      </Space>

      {canWrite ? (
        <>
          <Input.TextArea
            rows={3}
            maxLength={2000}
            showCount
            disabled={!canConfirm || Boolean(submitting)}
            value={form.remark}
            onChange={(event) => setForm((current) => current ? { ...current, remark: event.target.value } : current)}
            placeholder="填写人工核对依据（可选）"
          />
          {canConfirm && missingFields.length ? (
            <Alert
              type="warning"
              showIcon
              message="还不能生成正式订单"
              description={`请补齐：${missingFields.map(missingFieldLabel).join('、')}`}
            />
          ) : null}
          {submitError ? <Alert type="error" showIcon message="提交未完成" description={submitError} /> : null}
          <Space wrap>
            <Button
              type="primary"
              disabled={!canConfirm || missingFields.length > 0 || Boolean(submitting)}
              loading={submitting === 'CONFIRMED'}
              onClick={confirmDraft}
            >
              确认并生成正式订单
            </Button>
            <Input
              status={canReject && !rejectReason.trim() ? 'warning' : undefined}
              disabled={!canReject || Boolean(submitting)}
              value={rejectReason}
              onChange={(event) => setRejectReason(event.target.value)}
              placeholder="填写拒绝理由"
              style={{ width: 280 }}
              maxLength={2000}
            />
            <Popconfirm
              title="确认拒绝这份订单草稿？"
              description="拒绝后不会生成正式订单。"
              okText="确认拒绝"
              cancelText="取消"
              okButtonProps={{ danger: true }}
              onConfirm={rejectDraft}
              disabled={!canReject || !rejectReason.trim() || Boolean(submitting)}
            >
              <Button danger disabled={!canReject || !rejectReason.trim() || Boolean(submitting)} loading={submitting === 'REJECTED'}>
                拒绝草稿
              </Button>
            </Popconfirm>
          </Space>
        </>
      ) : null}
    </Space>
  );
}

function CandidateTags<T extends Record<string, unknown>>({
  candidates,
  label,
}: {
  candidates: T[];
  label: (candidate: T) => string;
}) {
  return (
    <Space wrap>
      {candidates.map((candidate, index) => <Tag key={`${label(candidate)}-${index}`}>{label(candidate)}</Tag>)}
    </Space>
  );
}

function masterDataSelectOptions(records: MasterDataRecord[]) {
  return records.map((item) => ({
    value: item.id,
    label: `${item.name}（${item.code}）`,
  }));
}

function customerCandidateLabel(candidate: OrderDraftCustomerCandidate): string {
  return [candidate.customer_code, candidate.customer_name, candidate.matched_by]
    .filter((value): value is string => typeof value === 'string' && Boolean(value.trim()))
    .join(' · ') || '未命名客户候选';
}

function skuCandidateLabel(candidate: OrderDraftSkuCandidate): string {
  return [candidate.product_name, candidate.sku_code, candidate.specification, candidate.unit, candidate.source_sku_ref]
    .filter((value): value is string => typeof value === 'string' && Boolean(value.trim()))
    .join(' · ') || '未命名 SKU 候选';
}

function missingFieldLabel(field: string): string {
  if (MISSING_FIELD_LABELS[field]) return MISSING_FIELD_LABELS[field];
  const lineMatch = /^line_(\d+)_(sku|quantity)$/.exec(field);
  if (!lineMatch) return field;
  return `第 ${lineMatch[1]} 行${lineMatch[2] === 'sku' ? ' SKU' : '数量'}`;
}

function draftStatusLabel(status: OrderDraftDetail['status']): string {
  if (status === 'CONFIRMED') return '订单草稿已确认';
  if (status === 'REJECTED') return '订单草稿已拒绝';
  return '待人工确认的订单草稿';
}

function displayError(error: unknown): string {
  if (error instanceof Error && !('status' in error)) return error.message;
  return errorMessage(error);
}
