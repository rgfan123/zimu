import type {
  BusinessFollowUpBusinessKind,
  BusinessFollowUpCommercialTerms,
  BusinessFollowUpCreateInput,
  BusinessFollowUpFormalExecutionItem,
} from '@/api/types';

export const MAX_EXECUTION_PLAN_BYTES = 64 * 1024;
export const MAX_FORMAL_ITEMS = 500;
export const MAX_DECIMAL_QUANTITY = 99_999_999_999.999;
export const MAX_INTEGER_QUANTITY = 2_147_483_647;

export interface BusinessFollowUpFormalItemFormValue {
  product_name?: string;
  quantity_per_unit?: number;
  quantity_unit?: string;
  unit_count?: number;
}

export interface BusinessFollowUpExecutionPlanFormValue {
  sample_name?: string;
  product_name?: string;
  quantity_per_unit?: number;
  quantity_unit?: string;
  unit_count?: number;
  requested_date?: string;
  expected_delivery_date?: string;
  testing_date?: string;
  specification?: string;
  requirements?: string;
  remark?: string;
  business_note?: string;
  commercial_terms?: BusinessFollowUpCommercialTerms;
  name?: string;
  delivery_date?: string;
  delivery_address?: string;
  settlement_period?: string;
  settlement_method?: string;
  items?: BusinessFollowUpFormalItemFormValue[];
}

export interface BusinessFollowUpCreateFormValues {
  message_submission_id: string;
  employee_draft: string;
  business_kind: BusinessFollowUpBusinessKind;
  execution_plan?: BusinessFollowUpExecutionPlanFormValue;
}

export function isValidIsoDate(value: unknown): value is string {
  if (typeof value !== 'string') return false;
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  if (!match) return false;
  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  const parsed = new Date(Date.UTC(year, month - 1, day));
  return parsed.getUTCFullYear() === year
    && parsed.getUTCMonth() === month - 1
    && parsed.getUTCDate() === day;
}

function decimalScale(value: number): number {
  const [coefficient, exponentText] = value.toString().toLowerCase().split('e');
  const fractionDigits = coefficient.split('.')[1]?.length ?? 0;
  const exponent = exponentText ? Number(exponentText) : 0;
  return Math.max(0, fractionDigits - exponent);
}

export function isValidExecutionDecimal(value: unknown): value is number {
  return typeof value === 'number'
    && Number.isFinite(value)
    && value > 0
    && value <= MAX_DECIMAL_QUANTITY
    && decimalScale(value) <= 3;
}

export function isValidExecutionInteger(value: unknown): value is number {
  return typeof value === 'number'
    && Number.isInteger(value)
    && value > 0
    && value <= MAX_INTEGER_QUANTITY;
}

export function formalItemCountError(items: unknown): string | null {
  return Array.isArray(items) && items.length >= 1 && items.length <= MAX_FORMAL_ITEMS
    ? null
    : `正式订单商品明细必须为 1..${MAX_FORMAL_ITEMS} 行`;
}

const COMMERCIAL_TERM_KEYS: ReadonlyArray<keyof BusinessFollowUpCommercialTerms> = [
  'payment_terms',
  'reconciliation_date',
  'payment_date',
  'credit_days',
  'invoice_requirement',
  'moq',
  'quoted_price',
  'target_price',
  'remark',
];

function requiredText(value: string | undefined): string {
  if (!value?.trim()) throw new Error('结构化执行计划缺少必填文本');
  return value.trim();
}

function requiredNumber(value: number | undefined): number {
  if (typeof value !== 'number' || !Number.isFinite(value)) {
    throw new Error('结构化执行计划缺少必填数量');
  }
  return value;
}

function optionalText(value: string | undefined): string | undefined {
  const normalized = value?.trim();
  return normalized ? normalized : undefined;
}

function compactCommercialTerms(
  source: BusinessFollowUpCommercialTerms | undefined,
): BusinessFollowUpCommercialTerms | undefined {
  if (!source) return undefined;
  const result: BusinessFollowUpCommercialTerms = {};
  for (const key of COMMERCIAL_TERM_KEYS) {
    const value = optionalText(source[key]);
    if (value) result[key] = value;
  }
  return Object.keys(result).length ? result : undefined;
}

function compactFormalItem(item: BusinessFollowUpFormalItemFormValue): BusinessFollowUpFormalExecutionItem {
  return {
    product_name: requiredText(item.product_name),
    quantity_per_unit: requiredNumber(item.quantity_per_unit),
    quantity_unit: requiredText(item.quantity_unit),
    unit_count: requiredNumber(item.unit_count),
  };
}

export function buildBusinessFollowUpCreateInput(
  values: BusinessFollowUpCreateFormValues,
): BusinessFollowUpCreateInput {
  const base = {
    message_submission_id: values.message_submission_id,
    employee_draft: values.employee_draft.trim(),
  };
  if (values.business_kind === 'CUSTOMER') {
    return { ...base, business_kind: 'CUSTOMER' };
  }

  const plan = values.execution_plan ?? {};
  const commercialTerms = compactCommercialTerms(plan.commercial_terms);
  if (values.business_kind === 'SAMPLE') {
    return {
      ...base,
      business_kind: 'SAMPLE',
      execution_plan: {
        sample_name: requiredText(plan.sample_name),
        product_name: requiredText(plan.product_name),
        quantity_per_unit: requiredNumber(plan.quantity_per_unit),
        quantity_unit: requiredText(plan.quantity_unit),
        unit_count: requiredNumber(plan.unit_count),
        requested_date: requiredText(plan.requested_date),
        ...(optionalText(plan.expected_delivery_date) ? { expected_delivery_date: optionalText(plan.expected_delivery_date) } : {}),
        ...(optionalText(plan.testing_date) ? { testing_date: optionalText(plan.testing_date) } : {}),
        ...(optionalText(plan.specification) ? { specification: optionalText(plan.specification) } : {}),
        ...(optionalText(plan.requirements) ? { requirements: optionalText(plan.requirements) } : {}),
        ...(optionalText(plan.remark) ? { remark: optionalText(plan.remark) } : {}),
        ...(optionalText(plan.business_note) ? { business_note: optionalText(plan.business_note) } : {}),
        ...(commercialTerms ? { commercial_terms: commercialTerms } : {}),
      },
    };
  }

  return {
    ...base,
    business_kind: 'FORMAL',
    execution_plan: {
      order_type: 'formal',
      name: requiredText(plan.name),
      delivery_date: requiredText(plan.delivery_date),
      delivery_address: requiredText(plan.delivery_address),
      ...(optionalText(plan.settlement_period) ? { settlement_period: optionalText(plan.settlement_period) } : {}),
      ...(optionalText(plan.settlement_method) ? { settlement_method: optionalText(plan.settlement_method) } : {}),
      ...(optionalText(plan.business_note) ? { business_note: optionalText(plan.business_note) } : {}),
      ...(commercialTerms ? { commercial_terms: commercialTerms } : {}),
      items: (plan.items ?? []).map(compactFormalItem),
    },
  };
}

function compactForJson(value: unknown): unknown {
  if (typeof value === 'string') return optionalText(value);
  if (Array.isArray(value)) return value.map(compactForJson);
  if (value && typeof value === 'object') {
    const compacted: Record<string, unknown> = {};
    for (const [key, fieldValue] of Object.entries(value)) {
      const normalized = compactForJson(fieldValue);
      if (normalized !== undefined) compacted[key] = normalized;
    }
    return compacted;
  }
  return value;
}

export function executionPlanByteLength(values: BusinessFollowUpCreateFormValues): number {
  if (values.business_kind === 'CUSTOMER') return 0;
  const compacted = compactForJson(values.execution_plan ?? {}) as Record<string, unknown>;
  if (compacted.commercial_terms
      && typeof compacted.commercial_terms === 'object'
      && !Array.isArray(compacted.commercial_terms)
      && Object.keys(compacted.commercial_terms).length === 0) {
    delete compacted.commercial_terms;
  }
  const projected = values.business_kind === 'FORMAL'
    ? { ...compacted, order_type: 'formal' }
    : compacted;
  return new TextEncoder().encode(JSON.stringify(projected)).byteLength;
}

export function executionPlanSizeError(values: BusinessFollowUpCreateFormValues): string | null {
  if (values.business_kind === 'CUSTOMER') return null;
  return executionPlanByteLength(values) > MAX_EXECUTION_PLAN_BYTES
    ? '执行计划不能超过 64 KiB，请精简说明或商品明细'
    : null;
}
