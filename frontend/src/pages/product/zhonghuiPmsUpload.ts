/**
 * 中汇 PMS 批量上传（pms_openapi.md）的纯逻辑：覆盖字段构建与结果摘要。
 * 与 UI 无关，便于 node --test 直接断言。
 *
 * 覆盖字段按 api-contract §3.1 序列化：标识符（brand_id / certification_type /
 * certification_id / third_id / limit_area_temp_id / origincountry）与 BigDecimal 数量
 * （goods_tax / goods_price / supply_price / goods_num）统一以十进制字符串传输；
 * 文本字段（logistics_carrier / producing_area / sale_unit）去首尾空白。
 */

export interface PmsOverrideFormValues {
  brand_id?: number | string | null;
  certification_type?: number | string | null;
  certification_id?: number | string | null;
  third_id?: number | string | null;
  limit_area_temp_id?: number | string | null;
  goods_tax?: number | string | null;
  /** 物流公司 id；多选时以逗号连接（PMS logisticsCarrier 格式 "1,20"）。 */
  logistics_carrier?: string | string[] | null;
  producing_area?: string | null;
  goods_num?: number | string | null;
  sale_unit?: string | null;
  origincountry?: number | string | null;
  goods_price?: number | string | null;
  supply_price?: number | string | null;
}

const OVERRIDE_KEYS = [
  'brand_id',
  'certification_type',
  'certification_id',
  'third_id',
  'limit_area_temp_id',
  'goods_tax',
  'logistics_carrier',
  'producing_area',
  'goods_num',
  'sale_unit',
  'origincountry',
  'goods_price',
  'supply_price',
] as const;

/**
 * 把覆盖表单值映射为批量上传请求的 overrides（snake_case，服务端字段）。
 * 只包含用户实际填写（非空）的值；数值/标识符统一转十进制字符串，文本去首尾空白。
 */
export function buildPmsBatchUploadOverrides(values: PmsOverrideFormValues): Record<string, unknown> {
  const overrides: Record<string, unknown> = {};
  for (const key of OVERRIDE_KEYS) {
    const value = values[key];
    if (value === undefined || value === null) continue;
    // 物流公司多选数组 → 逗号连接（PMS logisticsCarrier 格式 "1,20"）
    const text = Array.isArray(value) ? value.join(',') : String(value).trim();
    if (text === '') continue;
    overrides[key] = text;
  }
  return overrides;
}

/** 批量上传结果的展示摘要文案。 */
export function pmsUploadSummary(total: number, succeeded: number, failed: number): string {
  if (total === 0) return '没有可上传的商品';
  return `共 ${total} 个：成功 ${succeeded}，失败 ${failed}`;
}
