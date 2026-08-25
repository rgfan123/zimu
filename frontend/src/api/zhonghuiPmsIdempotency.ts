/**
 * 中汇 PMS 批量上传的稳定幂等键（api-contract §3.2）。
 *
 * 同一批 SKU + 相同覆盖字段 → 同一 Idempotency-Key：服务端幂等注册表重放首次结果，
 * 不会重复调用 PMS 创建商品；改动选择/覆盖字段 → 新 key（视为新请求）。
 * key 只含 ASCII 可见字符（writeHeaders 约束），长度固定。
 */

export interface ZhonghuiPmsBatchUploadBody {
  sku_ids: string[];
  overrides?: Record<string, unknown>;
}

/** FNV-1a 32-bit hash，输出 8 位小写 hex。 */
function fnv1aHex(text: string): string {
  let hash = 0x811c9dc5;
  for (let i = 0; i < text.length; i++) {
    hash ^= text.charCodeAt(i);
    hash = Math.imul(hash, 0x01000193) >>> 0;
  }
  return hash.toString(16).padStart(8, '0');
}

/** 稳定幂等键：zhonghui-pms-batch-<fnv1a(sku_ids+overrides)>-<sku 数>。 */
export function zhonghuiPmsBatchIdempotencyKey(body: ZhonghuiPmsBatchUploadBody): string {
  const canonical = JSON.stringify({ sku_ids: body.sku_ids, overrides: body.overrides ?? {} });
  return `zhonghui-pms-batch-${fnv1aHex(canonical)}-${body.sku_ids.length}`;
}
