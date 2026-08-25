/**
 * 京东实时库存判定（JD_STOCK）复核事项的可读证据：阻断原因与逐商品库存观测。
 * 观测数据来自 ShipmentJdStockCheckService 落库的 review_cases.detail.observations，
 * 商品名/SKU 编码由服务端按内部主数据补齐；前端只做白名单展示，不透出其他字段。
 */

export interface JdStockReviewEvidence {
  evidenceKey: string;
  productLabel: string;
  goodsLabel: string;
  demandLabel: string;
  observationLabel: string;
}

const BLOCKER_LABELS: Record<string, string> = {
  JD_STOCK_INSUFFICIENT: '京东可用库存不足',
  JD_STOCK_QUERY_FAILED: '京东库存查询失败',
  JD_STOCK_RESPONSE_INVALID: '京东库存响应无法解析',
  JD_STOCK_TARGET_WAREHOUSE_NOT_OBSERVED: '目标仓库无库存观测',
  JD_STOCK_RESPONSE_AMBIGUOUS: '京东库存响应歧义',
  JD_STOCK_DEMAND_EMPTY: '无库存查询需求',
  JD_SKU_MAPPING_GATE_BLOCKED: '京东 SKU 映射门禁未通过',
};

const OBSERVATION_STATUS_LABELS: Record<string, string> = {
  OBSERVED: '已观测',
  NOT_OBSERVED: '未观测',
};

function scalar(value: unknown): string {
  return typeof value === 'string' || typeof value === 'number' ? String(value) : '';
}

/** 阻断原因中文列表；未知码原样展示，不猜测含义。 */
export function jdStockBlockers(detail: Record<string, unknown>): string[] {
  const blockers = Array.isArray(detail.blockers) ? detail.blockers : [];
  const labels: string[] = [];
  for (const raw of blockers) {
    if (!raw || typeof raw !== 'object') continue;
    const code = scalar((raw as Record<string, unknown>).code);
    if (code) labels.push(BLOCKER_LABELS[code] ?? code);
  }
  return labels;
}

/** 逐商品库存观测行：商品名/SKU 编码、京东商品编码、需求件数、总库存/可用库存与仓库。 */
export function jdStockReviewEvidence(detail: Record<string, unknown>): JdStockReviewEvidence[] {
  const observations = Array.isArray(detail.observations) ? detail.observations : [];
  return observations.flatMap((raw) => {
    if (!raw || typeof raw !== 'object') return [];
    const row = raw as Record<string, unknown>;
    const skuId = scalar(row.sku_id);
    if (!skuId) return [];
    const productName = scalar(row.product_name);
    const skuCode = scalar(row.sku_code);
    const goodsNo = scalar(row.goods_no);
    const warehouse = scalar(row.warehouse_code);
    const unit = scalar(row.quantity_unit) || '件';
    const observed = row.observation_status === 'OBSERVED';
    return [{
      evidenceKey: `sku:${skuId}`,
      productLabel: [productName, skuCode ? `SKU ${skuCode}` : ''].filter(Boolean).join(' · ')
        || `SKU #${skuId}`,
      goodsLabel: goodsNo || '—',
      demandLabel: `${scalar(row.required_quantity)} ${unit}`,
      observationLabel: observed
        ? `总库存 ${scalar(row.stock_quantity)} / 可用 ${scalar(row.usable_quantity)}`
          + (warehouse ? `（仓 ${warehouse}）` : '')
        : `${OBSERVATION_STATUS_LABELS[scalar(row.observation_status)] ?? scalar(row.observation_status)}`
          + (warehouse ? `（仓 ${warehouse}）` : ''),
    }];
  });
}
