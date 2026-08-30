/**
 * SKU 人民币价格的公开 decimal-string 契约。
 *
 * 价格最多 12 位整数、2 位小数；空值表示“未定价”，与 0 元分开。
 */
export const COMMERCIAL_PRICE_PATTERN = /^(0|[1-9][0-9]{0,11})(\.[0-9]{1,2})?$/;

function normalizeCommercialPrice(value: unknown): string {
  if (typeof value !== 'string') {
    throw new Error('价格必须使用 decimal string');
  }
  const normalized = value.trim();
  if (!COMMERCIAL_PRICE_PATTERN.test(normalized)) {
    throw new Error('价格必须为非负数，最多十二位整数和两位小数');
  }
  return normalized;
}

/** 新建载荷：空输入省略字段，后端持久化为未定价。 */
export function optionalCommercialPrice(value: unknown): string | undefined {
  if (value === undefined || value === null) return undefined;
  if (typeof value === 'string' && !value.trim()) return undefined;
  return normalizeCommercialPrice(value);
}

/** 编辑载荷：未出现的字段不修改，显式清空则写 null（未定价）。 */
export function patchCommercialPrice(value: unknown): string | null | undefined {
  if (value === undefined) return undefined;
  if (value === null || (typeof value === 'string' && !value.trim())) return null;
  return normalizeCommercialPrice(value);
}

export function commercialPriceLabel(value: unknown): string {
  if (value === undefined || value === null || value === '') return '未定价';
  try {
    const normalized = normalizeCommercialPrice(value);
    const [integer, decimals = ''] = normalized.split('.');
    return `¥${integer}.${decimals.padEnd(2, '0')}`;
  } catch {
    return '价格数据异常';
  }
}

function optionalTrimmedString(value: unknown): string | undefined {
  if (typeof value !== 'string') return undefined;
  const normalized = value.trim();
  return normalized || undefined;
}

function optionalPositiveInteger(value: unknown): number | undefined {
  if (value === undefined || value === null || value === '') return undefined;
  const parsed = typeof value === 'number' ? value : Number(String(value).trim());
  if (!Number.isInteger(parsed) || parsed <= 0) throw new Error('包装件数必须为正整数');
  return parsed;
}

function patchTrimmedString(value: unknown): string | null | undefined {
  if (value === undefined) return undefined;
  return optionalTrimmedString(value) ?? null;
}

function patchPositiveInteger(value: unknown): number | null | undefined {
  if (value === undefined) return undefined;
  return optionalPositiveInteger(value) ?? null;
}

export function buildSkuCreateBody(values: Record<string, unknown>) {
  return {
    provider_id: String(values.provider_id),
    product_id: String(values.product_id),
    specification: String(values.specification),
    unit: String(values.unit),
    net_content_value: optionalTrimmedString(values.net_content_value),
    net_content_unit: optionalTrimmedString(values.net_content_unit),
    package_count: optionalPositiveInteger(values.package_count),
    package_unit: optionalTrimmedString(values.package_unit),
    barcode: optionalTrimmedString(values.barcode),
    purchase_price: optionalCommercialPrice(values.purchase_price),
    retail_price: optionalCommercialPrice(values.retail_price),
    active: typeof values.active === 'boolean' ? values.active : undefined,
  };
}

/** 商品档案新建：提交新商品资料及首个 SKU，不接受已有 product_id。 */
export function buildProductWithInitialSkuBody(values: Record<string, unknown>) {
  const active = typeof values.active === 'boolean' ? values.active : undefined;
  return {
    product: {
      product_code: String(values.product_code),
      product_name: String(values.product_name),
      brand_name: optionalTrimmedString(values.brand_name),
      category_id: String(values.category_id),
      active,
    },
    sku: {
      provider_id: String(values.provider_id),
      specification: String(values.specification),
      unit: String(values.unit),
      net_content_value: optionalTrimmedString(values.net_content_value),
      net_content_unit: optionalTrimmedString(values.net_content_unit),
      package_count: optionalPositiveInteger(values.package_count),
      package_unit: optionalTrimmedString(values.package_unit),
      barcode: optionalTrimmedString(values.barcode),
      purchase_price: optionalCommercialPrice(values.purchase_price),
      retail_price: optionalCommercialPrice(values.retail_price),
      active,
    },
  };
}

export function buildSkuUpdateBody(values: Record<string, unknown>) {
  return {
    expected_version: Number(values.expected_version),
    specification: typeof values.specification === 'string' ? values.specification : undefined,
    unit: typeof values.unit === 'string' ? values.unit : undefined,
    net_content_value: patchTrimmedString(values.net_content_value),
    net_content_unit: patchTrimmedString(values.net_content_unit),
    package_count: patchPositiveInteger(values.package_count),
    package_unit: patchTrimmedString(values.package_unit),
    barcode: typeof values.barcode === 'string' ? (optionalTrimmedString(values.barcode) ?? null) : undefined,
    purchase_price: patchCommercialPrice(values.purchase_price),
    retail_price: patchCommercialPrice(values.retail_price),
    active: typeof values.active === 'boolean' ? values.active : undefined,
  };
}
