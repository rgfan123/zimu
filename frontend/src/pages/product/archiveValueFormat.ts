/**
 * 成本表数值的展示层格式化。
 *
 * 背景：源 Excel 的成本列是公式结果，入库按快照口径**原样留存**完整浮点
 * （如 71.0098888888889）——这是对的，审计要能对回原表。丑的是把它原样
 * 吐到界面上。本模块只管"给人看"这一层：库值、导出值都不经过这里。
 *
 * 规则（按列语义，不按数值猜）：
 * - 比率列（占比/损耗率/账期比例/扣点/毛利率/AK 无表头列）：0.0671654 → 「6.72%」
 * - 其余数值：保留 2 位小数（71.0098888888889 → 「71.01」）；整数原样
 * - 非数值文本（品牌/包装形式/彩袋…）：原样透传
 * - 被圆整过的值返回 full=原值，供 tooltip 展示完整精度
 */

/** 语义为 0–1 比率的列（依据表头：R人工占比 S修割损耗率 AE耗材占比 AL账期比例 AN扣点 AS毛利率；AK 无表头但实测为比率）。 */
const RATIO_COLUMNS: ReadonlySet<string> = new Set(['R', 'S', 'AE', 'AK', 'AL', 'AN', 'AS']);

const PLAIN_NUMBER = /^-?\d+(\.\d+)?$/;

export interface ArchiveValueView {
  text: string;
  /** 展示值被圆整时携带完整原值（供 tooltip）；未圆整则为 undefined。 */
  full?: string;
}

/** 去掉小数尾部多余的 0（'6.70' → '6.7'，'2.00' → '2'）。 */
function trimZeros(fixed: string): string {
  return fixed.replace(/\.?0+$/, '');
}

export function formatArchiveValue(column: string, raw: string): ArchiveValueView {
  const trimmed = raw.trim();
  if (!PLAIN_NUMBER.test(trimmed)) return { text: raw };

  const n = Number(trimmed);
  if (!Number.isFinite(n)) return { text: raw };

  if (RATIO_COLUMNS.has(column)) {
    const pct = trimZeros((n * 100).toFixed(2)) + '%';
    return pct === trimZeros(trimmed) + '%' ? { text: pct } : { text: pct, full: raw };
  }

  if (!trimmed.includes('.')) return { text: raw };

  const fixed = n.toFixed(2);
  // 圆整后与原值等价（如 '49.49'）就不挂 tooltip，避免无意义的悬停提示
  return Number(fixed) === n && trimZeros(fixed) === trimZeros(trimmed)
    ? { text: trimZeros(fixed) === trimmed ? raw : fixed }
    : { text: fixed, full: raw };
}
