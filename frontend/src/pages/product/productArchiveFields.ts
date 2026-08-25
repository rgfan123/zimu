/**
 * 商品档案新字段（毛利/标签/原料/上市周期/发货时效/主图）的载荷构建与展示格式化。
 * 契约与后端 openapi ProductWrite/ProductPatch 对齐：价格/日期为字符串，显式清空写 null。
 */

import {
  commercialPriceLabel,
  optionalCommercialPrice,
  patchCommercialPrice,
} from './skuCommercialPrice.ts';

/** 发货时效：正整数小时数。 */
export const LEAD_TIME_HOURS_PATTERN = /^[1-9][0-9]*$/;

/** ISO 日期（YYYY-MM-DD）。 */
export const ISO_DATE_PATTERN = /^[0-9]{4}-[0-9]{2}-[0-9]{2}$/;

function optionalTrimmedString(value: unknown): string | undefined {
  if (typeof value !== 'string') return undefined;
  const normalized = value.trim();
  return normalized || undefined;
}

function patchTrimmedString(value: unknown): string | null | undefined {
  if (value === undefined) return undefined;
  if (value === null) return null;
  if (typeof value !== 'string') return null;
  return value.trim() || null;
}

function patchNullable(value: unknown): string | null | undefined {
  if (value === undefined) return undefined;
  return typeof value === 'string' && value.trim() ? value.trim() : null;
}

/** 标签归一化：去首尾空白、去重；空列表返回 undefined（未填写）。 */
export function normalizeTags(value: unknown): string[] | undefined {
  if (!Array.isArray(value)) return undefined;
  const seen = new Set<string>();
  const result: string[] = [];
  for (const item of value) {
    if (typeof item !== 'string') continue;
    const trimmed = item.trim();
    if (trimmed && !seen.has(trimmed)) {
      seen.add(trimmed);
      result.push(trimmed);
    }
  }
  return result.length ? result : undefined;
}

/** 编辑载荷：未填写省略，空列表显式清空（null）。 */
export function patchTags(value: unknown): string[] | null | undefined {
  if (value === undefined) return undefined;
  return normalizeTags(value) ?? null;
}

/** 上市周期表单值：{ from?: string; to?: string }，只填开始也可。 */
export interface ListingPeriodValue {
  from?: string;
  to?: string;
}

function listingPeriod(value: unknown): ListingPeriodValue | undefined {
  if (!value || typeof value !== 'object') return undefined;
  const { from, to } = value as ListingPeriodValue;
  const result: ListingPeriodValue = {};
  if (from) result.from = from;
  if (to) result.to = to;
  return Object.keys(result).length ? result : undefined;
}

function patchListingPeriod(value: unknown): { listed_from?: string | null; listed_until?: string | null } {
  if (value === undefined || value === null) return {};
  const period = listingPeriod(value);
  if (!period) {
    // 显式空对象：清空起止
    return { listed_from: null, listed_until: null };
  }
  return {
    ...(period.from ? { listed_from: period.from } : { listed_from: null }),
    ...(period.to ? { listed_until: period.to } : { listed_until: null }),
  };
}

export function buildProductCreateBody(values: Record<string, unknown>) {
  const period = patchListingPeriod(values.listing_period);
  const tags = normalizeTags(values.tags);
  return {
    product_code: String(values.product_code),
    product_name: String(values.product_name),
    category_id: String(values.category_id),
    ...(optionalTrimmedString(values.ingredients) ? { ingredients: optionalTrimmedString(values.ingredients) } : {}),
    ...(tags ? { tags } : {}),
    ...(period.listed_from ? { listed_from: period.listed_from } : {}),
    ...(period.listed_until ? { listed_until: period.listed_until } : {}),
    ...(values.lead_time_hours !== undefined && values.lead_time_hours !== '' && values.lead_time_hours !== null
      ? { lead_time_hours: Number(values.lead_time_hours) }
      : {}),
    purchase_price: optionalCommercialPrice(values.purchase_price),
    retail_price: optionalCommercialPrice(values.retail_price),
    other_cost: optionalCommercialPrice(values.other_cost),
    ...(optionalTrimmedString(values.main_image_ref)
      ? { main_image_ref: optionalTrimmedString(values.main_image_ref) }
      : {}),
    active: typeof values.active === 'boolean' ? values.active : undefined,
  };
}

export function buildProductUpdateBody(values: Record<string, unknown>) {
  const period = patchListingPeriod(values.listing_period);
  return {
    expected_version: Number(values.expected_version),
    product_name: typeof values.product_name === 'string' ? values.product_name : undefined,
    category_id: typeof values.category_id === 'string' ? values.category_id : undefined,
    ingredients: patchTrimmedString(values.ingredients),
    tags: patchTags(values.tags),
    ...period,
    lead_time_hours: patchLeadTimeHours(values.lead_time_hours),
    purchase_price: patchCommercialPrice(values.purchase_price),
    retail_price: patchCommercialPrice(values.retail_price),
    other_cost: patchCommercialPrice(values.other_cost),
    main_image_ref: patchNullable(values.main_image_ref),
    active: typeof values.active === 'boolean' ? values.active : undefined,
  };
}

/**
 * 商品档案字段更新载荷（在 SKU 档案编辑弹窗中维护商品层字段用）：
 * 只含商品层字段，商品价格来自表单的 product_* 命名；不含 product_name/category_id/active，
 * 避免误改商品身份与启停。无任何字段变化时返回 null（调用方跳过商品 PATCH）。
 */
export function buildProductFieldsUpdateBody(values: Record<string, unknown>) {
  const period = patchListingPeriod(values.listing_period);
  const body = {
    expected_version: Number(values.product_version),
    ingredients: patchTrimmedString(values.ingredients),
    tags: patchTags(values.tags),
    ...period,
    lead_time_hours: patchLeadTimeHours(values.lead_time_hours),
    purchase_price: patchCommercialPrice(values.product_purchase_price),
    retail_price: patchCommercialPrice(values.product_retail_price),
    other_cost: patchCommercialPrice(values.product_other_cost),
    main_image_ref: patchNullable(values.main_image_ref),
  };
  // 只有实际有值的字段才算变更，避免只带 expected_version 触发后端 PATCH_EMPTY
  const hasChanges = Object.entries(body)
    .some(([key, value]) => key !== 'expected_version' && value !== undefined);
  return hasChanges ? body : null;
}

function patchLeadTimeHours(value: unknown): number | null | undefined {
  if (value === undefined) return undefined;
  if (typeof value === 'number') return value >= 1 ? value : null;
  if (typeof value === 'string' && value.trim()) return Number(value);
  return null;
}

/** 毛利展示：未定价 / ¥金额。 */
export function marginLabel(value: unknown): string {
  return commercialPriceLabel(value);
}

/**
 * 新建 SKU 弹窗「新建商品」模式的商品创建输入：
 * 表单里商品层价格用 product_* 命名（避免与 SKU 价格冲突），这里映射回商品三价；
 * 不携带 SKU 弹窗的 active，避免商品启停被 SKU 的开关误带。
 */
export function buildProductCreateValues(values: Record<string, unknown>) {
  return {
    product_code: values.product_code,
    product_name: values.product_name,
    category_id: values.category_id,
    ingredients: values.ingredients,
    tags: values.tags,
    listing_period: values.listing_period,
    lead_time_hours: values.lead_time_hours,
    purchase_price: values.product_purchase_price,
    retail_price: values.product_retail_price,
    other_cost: values.product_other_cost,
    main_image_ref: values.main_image_ref,
  };
}

/** 发货时效展示：小时数 → 「X小时内发货」。 */
export function leadTimeLabel(value: unknown): string {
  if (value === undefined || value === null || value === '') return '—';
  return `${String(value)}小时内发货`;
}

/** 上市周期展示：起始 ~ 结束；只填起始显示「X 起」。 */
export function listingPeriodLabel(from: unknown, until: unknown): string {
  const fromText = from == null || from === '' ? undefined : String(from);
  const untilText = until == null || until === '' ? undefined : String(until);
  if (!fromText && !untilText) return '—';
  if (fromText && untilText) return `${fromText} ~ ${untilText}`;
  if (fromText) return `${fromText} 起`;
  return `至 ${untilText}`;
}
