const LEGACY_JD_GOODS_NUMBER = /^京东商品编号\s+EMG\d+$/;

/** 商品档案只展示真实规格；历史上误写入规格字段的京东商品编号统一提示待维护。 */
export function displaySkuSpecification(value: unknown): string {
  const text = String(value ?? '').trim();
  if (!text || LEGACY_JD_GOODS_NUMBER.test(text)) return '待维护';
  return text;
}
