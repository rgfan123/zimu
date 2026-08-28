/**
 * 京东库存/映射类阻断（JD_STOCK_BLOCKED）的纯投影：把复核事项 detail.blockers 解析成
 * 逐条商品身份视图，供发货台「就地处置」抽屉渲染。
 *
 * <p>背景：blockerGrouping.ts 只认 JD_SHIPMENT_OUTBOUND_PREVIEW_BLOCKED 那种
 * {code,path,source,correction_target,message} 五键阻断；JD_STOCK_BLOCKED 的
 * blockers 是完全不同的形状——item 级（goods_no/product_name/sku_code/sku_id/
 * order_line_ids/missing_field），没有 path/source/correction_target，「修复位置」
 * 是「换一个 SKU」或「去补一个具体字段」，不是「改配置表单」（2026-08-27 阻断明细全量
 * 透传，见 ShipmentJdStockCheckService.mappingGateBlocker / stockBlocker）。
 *
 * <p>无 React、无 fetch，便于用 node:test 直接断言（体例照 blockerGrouping.ts）。
 */

export const STOCK_BLOCKED_REASON = 'JD_STOCK_BLOCKED';

export interface StockBlockerItem {
  code: string;
  message: string;
  goodsNo: string | null;
  productName: string | null;
  skuCode: string | null;
  skuId: string | null;
  /** 该阻断涉及的订单行；「换货」按订单行定位，同一 SKU 可能被多条行引用。 */
  orderLineIds: string[];
  /** 拿得到就带的「缺哪个本地字段」；远端事实类问题（如商品已停用）没有这个字段。 */
  missingField: string | null;
  /**
   * 映射门禁的**真正**失败原因码。门禁有 14 种 issue，落到 blocker 上 code 一律被塌缩成
   * `JD_SKU_MAPPING_GATE_BLOCKED`（mappingGateBlocker 为保持既有按 code 分组口径不变），
   * 真原因只在这个字段里。此前前端整个丢掉，于是 12 种毛病在工作台上长成同一句话。
   */
  mappingIssueCode: string | null;
}

export interface StockBlockerCase {
  caseId: string;
  caseNo: string | null;
  /** 仅当 subject_type==='SHIPMENT' 时有值——阻塞项自身不带发货单身份，只能由事项注入。 */
  shipmentId: string | null;
  /** 事项关联的订单（ReviewCaseDto.order_id）；整卡点击就跳这个订单。 */
  orderId: string | null;
  /** 事项关联的订单业务单号（ReviewCaseDto.order_no）。 */
  orderNo: string | null;
  blockers: StockBlockerItem[];
}

function stringOrNull(value: unknown): string | null {
  return typeof value === 'string' && value ? value : null;
}

/** 单条 blocker 的 fail-closed 读取：code/message 缺一不可，其余字段各自可选。 */
function readStockBlocker(raw: unknown): StockBlockerItem | null {
  if (typeof raw !== 'object' || raw === null) return null;
  const record = raw as Record<string, unknown>;
  const code = record.code;
  const message = record.message;
  if (typeof code !== 'string' || !code) return null;
  if (typeof message !== 'string' || !message) return null;
  const orderLineIds = Array.isArray(record.order_line_ids)
    ? record.order_line_ids.filter((item): item is string => typeof item === 'string')
    : [];
  return {
    code,
    message,
    goodsNo: stringOrNull(record.goods_no),
    productName: stringOrNull(record.product_name),
    skuCode: stringOrNull(record.sku_code),
    skuId: stringOrNull(record.sku_id),
    orderLineIds,
    missingField: stringOrNull(record.missing_field),
    mappingIssueCode: stringOrNull(record.mapping_issue_code),
  };
}

/**
 * 从复核事项列表里挑出「京东库存/映射阻断」并抽取其 blockers。
 *
 * <p>只认 `reason_code === STOCK_BLOCKED_REASON`；其余事项与本视图无关。
 * 没有任何合法 blocker 的事项也会被丢弃——显示一个空组比不显示更糟。
 */
export function extractStockBlockerCases(items: unknown[]): StockBlockerCase[] {
  const cases: StockBlockerCase[] = [];
  for (const item of items) {
    if (typeof item !== 'object' || item === null) continue;
    const record = item as Record<string, unknown>;
    if (record.reason_code !== STOCK_BLOCKED_REASON) continue;
    const id = record.id;
    if (typeof id !== 'string' && typeof id !== 'number') continue;

    const detail = record.detail;
    const rawBlockers =
      typeof detail === 'object' && detail !== null
        ? (detail as Record<string, unknown>).blockers
        : undefined;
    if (!Array.isArray(rawBlockers)) continue;

    const blockers: StockBlockerItem[] = [];
    for (const raw of rawBlockers) {
      const parsed = readStockBlocker(raw);
      if (parsed) blockers.push(parsed);
    }
    if (blockers.length === 0) continue;

    cases.push({
      caseId: String(id),
      caseNo: typeof record.case_no === 'string' ? record.case_no : null,
      // subject_type/subject_id 由 OrderMapper.toReviewCase 派生；本类事项没有
      // order_line_id（ShipmentJdStockCheckService.reconcileCase 只落 order_id/shipment_id），
      // 故必为 SHIPMENT，与 blockerGrouping.ts 的推导同源。
      shipmentId:
        record.subject_type === 'SHIPMENT' && typeof record.subject_id === 'string'
          ? record.subject_id
          : null,
      orderId: stringOrNull(record.order_id),
      orderNo: stringOrNull(record.order_no),
      blockers,
    });
  }
  return cases;
}

/** 跨多条事项合并去重：同一 shipment 常在 stock 与 sku-mapping 两个 review case 上各出现一份。 */
export function mergeStockBlockers(cases: StockBlockerCase[]): StockBlockerItem[] {
  const seen = new Set<string>();
  const merged: StockBlockerItem[] = [];
  for (const item of cases) {
    for (const blocker of item.blockers) {
      const key = `${blocker.code}:${blocker.skuId ?? ''}:${blocker.goodsNo ?? ''}:${blocker.message}`;
      if (seen.has(key)) continue;
      seen.add(key);
      merged.push(blocker);
    }
  }
  return merged;
}
