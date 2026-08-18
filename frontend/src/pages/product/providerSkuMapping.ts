/**
 * 履约方 SKU 的显式单位换算。
 *
 * 该值表示“1 个内部 SKU 销售单位 = 多少个京东库存件”；
 * 空值代表尚未确认，不能被默认成 1。
 */
export const JD_PIECES_PER_UNIT_PATTERN = /^(?!0(?:\.0{1,3})?$)(0|[1-9][0-9]*)(\.[0-9]{1,3})?$/;

export function optionalJdPiecesPerUnit(value: unknown): string | undefined {
  if (value === undefined || value === null) return undefined;
  const normalized = String(value).trim();
  if (!normalized) return undefined;
  if (!JD_PIECES_PER_UNIT_PATTERN.test(normalized)) {
    throw new Error('京东件数换算必须是正数，且最多三位小数');
  }
  return normalized;
}

export function jdPiecesPerUnitLabel(value: unknown): string {
  const normalized = optionalJdPiecesPerUnit(value);
  return normalized === undefined ? '—' : `${normalized} 件`;
}

function optionalTrimmedString(value: unknown): string | undefined {
  if (typeof value !== 'string') return undefined;
  const normalized = value.trim();
  return normalized || undefined;
}

export function buildProviderSkuMappingCreateBody(values: Record<string, unknown>) {
  return {
    provider_id: String(values.provider_id),
    sku_id: String(values.sku_id),
    provider_sku_code: String(values.provider_sku_code),
    provider_sku_name: typeof values.provider_sku_name === 'string' && values.provider_sku_name
      ? values.provider_sku_name
      : undefined,
    merchant_sku_code: optionalTrimmedString(values.merchant_sku_code),
    jd_pieces_per_unit: optionalJdPiecesPerUnit(values.jd_pieces_per_unit),
    active: typeof values.active === 'boolean' ? values.active : undefined,
  };
}

export function buildProviderSkuMappingUpdateBody(values: Record<string, unknown>) {
  return {
    expected_version: Number(values.expected_version),
    provider_sku_code: typeof values.provider_sku_code === 'string' ? values.provider_sku_code : undefined,
    provider_sku_name: typeof values.provider_sku_name === 'string' && values.provider_sku_name
      ? values.provider_sku_name
      : undefined,
    merchant_sku_code: optionalTrimmedString(values.merchant_sku_code),
    jd_pieces_per_unit: optionalJdPiecesPerUnit(values.jd_pieces_per_unit),
    active: typeof values.active === 'boolean' ? values.active : undefined,
  };
}
