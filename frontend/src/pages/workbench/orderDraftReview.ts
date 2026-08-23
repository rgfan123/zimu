export interface OrderDraftCustomerCandidate {
  customer_id?: string;
  customer_code?: string;
  customer_name?: string;
  matched_by?: string;
  [key: string]: unknown;
}

export interface OrderDraftSkuCandidate {
  sku_id?: string;
  sku_code?: string;
  product_name?: string;
  specification?: string;
  unit?: string;
  source_sku_ref?: string;
  matched_by?: string;
  [key: string]: unknown;
}

export interface OrderDraftLineDetail {
  id: string;
  line_no: number;
  sku_id?: string | null;
  sku_code?: string | null;
  sku_candidates: OrderDraftSkuCandidate[];
  product_name_raw?: string | null;
  spec_raw?: string | null;
  unit_raw?: string | null;
  quantity?: string | null;
}

export interface OrderDraftDetail {
  id: string;
  draft_no: string;
  source_order_no: string;
  submission_id: string;
  status: 'OPEN' | 'CONFIRMED' | 'REJECTED';
  revision: number;
  customer_id?: string | null;
  customer_code?: string | null;
  customer_name?: string | null;
  customer_candidates: OrderDraftCustomerCandidate[];
  customer_name_raw?: string | null;
  receiver_name?: string | null;
  receiver_phone?: string | null;
  receiver_address?: string | null;
  settlement_method?: string | null;
  settlement_time?: string | null;
  missing_fields: string[];
  lines: OrderDraftLineDetail[];
  review_case_id?: string | null;
  review_case_version?: number | null;
  confirmed_order_id?: string | null;
  confirmed_by?: string | null;
  confirmed_at?: string | null;
  created_at: string;
  updated_at: string;
}

export interface OrderDraftReviewForm {
  customer_id: string;
  receiver: {
    name: string;
    phone: string;
    province: string;
    city: string;
    district: string;
    town: string;
    address: string;
  };
  settlement_method: string;
  settlement_time: string;
  items: Record<number, { sku_id: string; quantity: string }>;
  remark: string;
}

export const ORDER_DRAFT_SETTLEMENT_METHODS = [
  'MONTHLY',
  'IMMEDIATE',
  'CREDIT_TERM',
  'PREPAID',
  'COD',
  'OTHER',
] as const;

export interface ConfirmOrderDraftCommand {
  expected_revision: number;
  expected_case_version: number;
  customer: { customer_id: string };
  receiver: OrderDraftReviewForm['receiver'];
  settlement: { method: string; settlement_time: string };
  items: Array<{ line_no: number; sku_id: string; quantity: string }>;
  remark: string;
}

export interface RejectOrderDraftCommand {
  expected_revision: number;
  expected_case_version: number;
  reason: string;
}

export function orderDraftReviewPermissions(
  reviewCaseStatus: 'OPEN' | 'RESOLVED' | 'DISMISSED',
  draftStatus: OrderDraftDetail['status'],
  allowedActions: readonly string[],
): { canConfirm: boolean; canReject: boolean } {
  const open = reviewCaseStatus === 'OPEN' && draftStatus === 'OPEN';
  return {
    canConfirm: open && allowedActions.includes('CONFIRM_ORDER_DRAFT'),
    canReject: open && allowedActions.includes('REJECT_ORDER_DRAFT'),
  };
}

function text(value: unknown): string {
  return typeof value === 'string' ? value.trim() : '';
}

function uniqueCandidateId(candidates: Array<Record<string, unknown>>, key: string): string {
  return candidates.length === 1 ? text(candidates[0]?.[key]) : '';
}

function settlementMethod(value: unknown): string {
  const normalized = text(value);
  return ORDER_DRAFT_SETTLEMENT_METHODS.some((method) => method === normalized)
    ? normalized
    : '';
}

export function initialOrderDraftReviewForm(draft: OrderDraftDetail): OrderDraftReviewForm {
  return {
    customer_id: draft.customer_id
      ?? uniqueCandidateId(draft.customer_candidates, 'customer_id'),
    receiver: {
      name: text(draft.receiver_name),
      phone: text(draft.receiver_phone),
      province: '',
      city: '',
      district: '',
      town: '',
      address: text(draft.receiver_address),
    },
    settlement_method: settlementMethod(draft.settlement_method),
    settlement_time: text(draft.settlement_time),
    items: Object.fromEntries(draft.lines.map((line) => [
      line.line_no,
      {
        sku_id: line.sku_id ?? uniqueCandidateId(line.sku_candidates, 'sku_id'),
        quantity: text(line.quantity),
      },
    ])),
    remark: '',
  };
}

export function orderDraftMissingFields(
  draft: OrderDraftDetail,
  form: OrderDraftReviewForm,
): string[] {
  const missing: string[] = [];
  if (!text(form.customer_id)) missing.push('customer');
  if (!text(form.receiver.name)) missing.push('receiver_name');
  if (!text(form.receiver.phone)) missing.push('receiver_phone');
  if (!text(form.receiver.address)) missing.push('receiver_address');
  if (!settlementMethod(form.settlement_method)) missing.push('settlement_method');
  if (!text(form.settlement_time)) missing.push('settlement_time');
  if (draft.review_case_version === null || draft.review_case_version === undefined) {
    missing.push('review_case');
  }
  if (draft.lines.length === 0) missing.push('items');
  for (const line of draft.lines) {
    const item = form.items[line.line_no];
    if (!item || !text(item.sku_id)) missing.push(`line_${line.line_no}_sku`);
    if (!item || !isPositiveQuantity(item.quantity)) missing.push(`line_${line.line_no}_quantity`);
  }
  return missing;
}

export function buildOrderDraftConfirmCommand(
  draft: OrderDraftDetail,
  form: OrderDraftReviewForm,
): ConfirmOrderDraftCommand {
  const missing = orderDraftMissingFields(draft, form);
  if (missing.length) {
    throw new Error(`订单草稿仍有未完整字段：${missing.join('、')}`);
  }
  return {
    expected_revision: draft.revision,
    expected_case_version: draft.review_case_version!,
    customer: { customer_id: text(form.customer_id) },
    receiver: {
      name: text(form.receiver.name),
      phone: text(form.receiver.phone),
      province: text(form.receiver.province),
      city: text(form.receiver.city),
      district: text(form.receiver.district),
      town: text(form.receiver.town),
      address: text(form.receiver.address),
    },
    settlement: {
      method: text(form.settlement_method),
      settlement_time: text(form.settlement_time),
    },
    items: draft.lines.map((line) => ({
      line_no: line.line_no,
      sku_id: text(form.items[line.line_no]!.sku_id),
      quantity: text(form.items[line.line_no]!.quantity),
    })),
    remark: text(form.remark),
  };
}

export function buildOrderDraftRejectCommand(
  draft: OrderDraftDetail,
  reason: string,
): RejectOrderDraftCommand {
  const trimmed = text(reason);
  if (!trimmed) throw new Error('请填写拒绝理由');
  if (draft.review_case_version === null || draft.review_case_version === undefined) {
    throw new Error('订单草稿缺少可处理的复核事项');
  }
  return {
    expected_revision: draft.revision,
    expected_case_version: draft.review_case_version,
    reason: trimmed,
  };
}

function isPositiveQuantity(value: string): boolean {
  const normalized = text(value);
  return /^(?!0(?:\.0{1,3})?$)(0|[1-9][0-9]*)(\.[0-9]{1,3})?$/.test(normalized);
}
