export interface TrackingDraftCarrierCandidate {
  code: string;
  name: string;
  source: 'STATED' | 'PREFIX';
}

export interface TrackingDraftCarrierOption {
  code: string;
  name: string;
}

export interface TrackingDraftTaskCandidate {
  task_id: string;
  fulfillment_no: string;
  order_id: string;
  order_no: string;
  order_line_id: string;
  shipment_id: string;
  receiver_name: string;
  requested_quantity: string;
  shipped_quantity: string;
  instructed_quantity: string;
}

export interface TrackingDraftDetail {
  id: string;
  draft_no: string;
  submission_id: string;
  line_no: number;
  raw_receiver_name: string | null;
  masked_receiver_name: string | null;
  tracking_no: string | null;
  carrier_code: string | null;
  carrier_candidates: TrackingDraftCarrierCandidate[];
  manual_carrier_options: TrackingDraftCarrierOption[];
  task_id: string | null;
  task_candidates: TrackingDraftTaskCandidate[];
  shipment_judgment: 'FULL' | 'PARTIAL' | 'SHORTAGE' | 'EXCEPTION';
  default_full_shipment: boolean;
  actual_quantity: string | null;
  validation_issues: string[];
  status: 'OPEN' | 'CONFIRMED' | 'REJECTED';
  revision: number;
  confirmed_by: string | null;
  confirmed_at: string | null;
  review_case_id: string | null;
  review_case_version: number | null;
  created_at: string;
}

export interface TrackingDraftReviewForm {
  task_id: string;
  task_no: string;
  carrier_code: string;
  remark: string;
}

export interface ConfirmTrackingDraftCommand {
  expected_draft_revision: number;
  expected_case_version: number;
  task_id: string | null;
  task_no: string | null;
  carrier_code: string;
  actual_quantity: null;
  remark: string;
}

const ISSUE_LABELS: Record<string, string> = {
  TRACKING_NO_MISSING: '原始消息缺少运单号',
  TASK_NOT_FOUND: '系统中不存在该发货任务号',
  TASK_NOT_APPLICABLE: '该任务不在当前待回传的第三方发货范围',
  TASK_SHIPMENT_MULTI_MATCH: '发货任务存在多个待回传发货批次',
  TASK_NAME_MISSING: '消息缺少可用于复核的收货人',
  TASK_NAME_INSUFFICIENT: '收货人姓名已完全脱敏，无法形成安全候选',
  TASK_NAME_NO_MATCH: '收货人未匹配到待回传发货任务',
  TASK_NAME_MULTI_MATCH: '收货人匹配到多个待回传发货任务',
  CARRIER_PREFIX_UNMATCHED: '运单前缀未匹配到已启用的物流公司',
  CARRIER_MULTI_HIT: '运单前缀匹配到多个物流公司',
  CARRIER_CONFLICT: '消息明示物流公司与运单前缀候选冲突',
  CARRIER_STATED_UNRESOLVED: '消息明示的物流公司未通过标准主数据校验',
  SHIPMENT_JUDGMENT_INVALID: '消息中的发货判断无法识别',
  REQUIRES_ACTUAL_QUANTITY: '非整项发货需要人工录入实发数量',
};

const CARRIER_ISSUES = new Set([
  'CARRIER_PREFIX_UNMATCHED',
  'CARRIER_MULTI_HIT',
  'CARRIER_CONFLICT',
  'CARRIER_STATED_UNRESOLVED',
]);

const MANUALLY_RESOLVABLE_TASK_ISSUES = new Set([
  'TASK_NOT_FOUND',
  'TASK_NOT_APPLICABLE',
  'TASK_NAME_MISSING',
  'TASK_NAME_INSUFFICIENT',
  'TASK_NAME_NO_MATCH',
  'TASK_NAME_MULTI_MATCH',
]);

function text(value: unknown): string {
  return typeof value === 'string' ? value.trim() : '';
}

export function initialTrackingDraftReviewForm(
  draft: TrackingDraftDetail,
): TrackingDraftReviewForm {
  return {
    task_id: text(draft.task_id)
      || (draft.task_candidates.length === 1 ? text(draft.task_candidates[0]?.task_id) : ''),
    task_no: '',
    carrier_code: text(draft.carrier_code)
      || (draft.carrier_candidates.length === 1 ? text(draft.carrier_candidates[0]?.code) : ''),
    remark: '',
  };
}

export function trackingDraftBlockingIssues(
  draft: TrackingDraftDetail,
  form: TrackingDraftReviewForm,
): string[] {
  const issues: string[] = [];
  const taskId = text(form.task_id);
  const taskNo = text(form.task_no);
  const carrierCode = text(form.carrier_code);
  const taskIsKnown = draft.task_candidates.some(
    (candidate) => text(candidate.task_id) === taskId,
  );
  const carrierIsKnown = draft.manual_carrier_options.some(
    (candidate) => text(candidate.code) === carrierCode,
  );
  const taskReferenceIsValid = taskNo
    ? !taskId
    : Boolean(taskId) && taskIsKnown;
  if (!taskReferenceIsValid) issues.push('发货任务未唯一确定');
  if (!carrierCode || !carrierIsKnown) issues.push('物流公司未唯一确定');
  if (!text(draft.tracking_no)) issues.push('运单号缺失');
  if (!draft.default_full_shipment || draft.shipment_judgment !== 'FULL') {
    issues.push('当前流程仅支持整项发货确认');
  }
  for (const issue of draft.validation_issues) {
    if (MANUALLY_RESOLVABLE_TASK_ISSUES.has(issue) && taskReferenceIsValid) continue;
    if (CARRIER_ISSUES.has(issue) && carrierIsKnown) continue;
    const label = trackingDraftIssueLabel(issue);
    if (!issues.includes(label)) issues.push(label);
  }
  return issues;
}

export function buildTrackingDraftConfirmCommand(
  draft: TrackingDraftDetail,
  reviewCaseVersion: number | null | undefined,
  form: TrackingDraftReviewForm,
): ConfirmTrackingDraftCommand {
  if (draft.status !== 'OPEN') {
    throw new Error('运单草稿不是待确认状态');
  }
  if (reviewCaseVersion == null || !Number.isInteger(reviewCaseVersion)) {
    throw new Error('运单草稿缺少可处理的复核版本');
  }
  const issues = trackingDraftBlockingIssues(draft, form);
  if (issues.length) {
    throw new Error(`还不能确认运单草稿：${issues.join('、')}`);
  }
  return {
    expected_draft_revision: draft.revision,
    expected_case_version: reviewCaseVersion as number,
    task_id: text(form.task_id) || null,
    task_no: text(form.task_no) || null,
    carrier_code: text(form.carrier_code),
    actual_quantity: null,
    remark: text(form.remark),
  };
}

export function trackingDraftIssueLabel(issue: string): string {
  return ISSUE_LABELS[issue] ?? '存在暂不支持的校验问题，请联系管理员处理';
}
