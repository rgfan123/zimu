/**
 * 「待我人工复核」卡片行的纯投影：把一条 JD_STOCK_BLOCKED 复核事项渲染成**人话**。
 *
 * <p><b>改造前长什么样</b>（2026-08-28 用户实测反馈）：
 * <pre>
 *   RC-JD-STOCK-B9C7F7B5529146B89A0E7631E82CB6D5   1 项   [就地处置]
 *   牛肉饼(1.2kg)
 * </pre>
 * 主行是一串给机器看的事项编码，副行是我方内部 SKU 名——用户原话「在没有点进去之前，
 * 这个预览该显示的应该是什么缺货，而不是这么晦涩难懂的一串文字」。
 *
 * <p><b>改造后</b>：主行 =「平台商品名 + 具体原因」，副行 = 收货人信息，RC 编码退到
 * title 属性里（它对人没有信息量，但排查时还得能捞出来）。
 *
 * <p>两个「原名」陷阱，都在这里绕开：
 * <ol>
 *   <li>商品名必须是**来源平台**给的名字（`order_lines.product_name_snapshot`，如聚福宝的
 *       「【京东配送】子牧牛肉惠选礼包1400g」），不是我方 `products.product_name`。
 *       blocker 里带的 `product_name` 恰恰是后者（见 ShipmentJdStockCheckService.loadSkuLabels），
 *       所以要用 order_line_id 去订单详情里换平台原名，换不到才退回内部名。</li>
 *   <li>原因不能写死「缺货」。同一个 `JD_SKU_MAPPING_GATE_BLOCKED` 底下塌缩了 14 种失败，
 *       只有 `JD_STOCK_INSUFFICIENT` 才是真缺货——文案一律由 blockerReasonLabel 按码决定。</li>
 * </ol>
 *
 * <p>无 React、无 fetch，可被 node:test 直接加载（相对路径 + .ts 后缀，不走 @/ 别名）。
 */

import { blockerReasonLabel } from '../../constants/reasonLabels.ts';
import type { StockBlockerCase, StockBlockerItem } from './stockBlockerCases.ts';

/**
 * 卡片需要的订单侧事实，由调用方从 `GET /api/v1/orders/{id}` 投影而来。
 *
 * <p>只收这几项而不是整个 OrderDetail：纯模块不该依赖 api/types，且收件人属于 PII——
 * 明确列出来，谁在读一眼可见（内部工作台可展示，但**不得**流向企微卡片或对外接口）。
 */
export interface OrderFacts {
  orderNo: string | null;
  receiverName: string | null;
  receiverPhone: string | null;
  receiverAddress: string | null;
  /** order_line_id → 平台原名（order_lines.product_name_snapshot 的前端投影）。 */
  productNameByLineId: Record<string, string>;
}

export interface ReviewCardView {
  caseId: string;
  /** RC-… 事项编码：不再上屏，只进 title 属性，供排查时对单。 */
  caseNo: string | null;
  orderId: string | null;
  shipmentId: string | null;
  /** 整卡点击目标；没有关联订单时为 null（不造假链接）。 */
  orderHref: string | null;
  /** 主行：「平台商品名 + 具体原因」。 */
  title: string;
  /** 副行：收货人信息；拿不到时诚实说明，不编造。 */
  subtitle: string;
  blockerCount: number;
}

/** 平台原名优先：按 blocker 锁定的订单行去订单详情里换名，换不到再退回内部名/京东编码。 */
export function platformProductName(
  blocker: StockBlockerItem,
  facts: OrderFacts | null,
): string | null {
  if (facts) {
    for (const lineId of blocker.orderLineIds) {
      const name = facts.productNameByLineId[lineId];
      if (name) return name;
    }
  }
  return blocker.productName ?? blocker.goodsNo ?? null;
}

/** 收货人一行：姓名 · 电话 · 地址，缺项自动跳过。 */
function receiverLine(facts: OrderFacts | null): string | null {
  if (!facts) return null;
  const parts = [facts.receiverName, facts.receiverPhone, facts.receiverAddress].filter(
    (part): part is string => typeof part === 'string' && part.length > 0,
  );
  return parts.length > 0 ? parts.join(' · ') : null;
}

/**
 * 一条复核事项 → 一行卡片。
 *
 * <p>一条事项可能挂多个阻断（同一发货批次里几个商品同时出问题）。标题取第一个作为代表，
 * 再缀「等 N 项」——把 N 个商品名拼成一行长文本正是改造前那种没人读的东西。
 */
export function presentStockBlockerCard(
  item: StockBlockerCase,
  facts: OrderFacts | null,
): ReviewCardView {
  const primary = item.blockers[0] ?? null;
  const productName = primary ? platformProductName(primary, facts) : null;
  const reason = primary ? blockerReasonLabel(primary.code, primary.mappingIssueCode) : '待人工复核';
  // 商品名缺失时不硬拼一个空格开头的标题，直接只显示原因（诚实态优于半截文案）。
  const head = productName ? `${productName} ${reason}` : reason;
  const more = item.blockers.length > 1 ? ` 等 ${item.blockers.length} 项` : '';

  const receiver = receiverLine(facts);
  const orderNo = facts?.orderNo ?? item.orderNo;

  return {
    caseId: item.caseId,
    caseNo: item.caseNo,
    orderId: item.orderId,
    shipmentId: item.shipmentId,
    orderHref: item.orderId ? `/orders/${item.orderId}` : null,
    title: `${head}${more}`,
    subtitle: receiver
      ? `收货人 ${receiver}`
      : orderNo
        ? `订单 ${orderNo}（收货人信息待加载）`
        : '收货人信息暂不可用',
    blockerCount: item.blockers.length,
  };
}

/** 一屏卡片要拉哪些订单：去重后的 order_id 列表，供调用方批量取详情。 */
export function orderIdsToLoad(cases: StockBlockerCase[]): string[] {
  const seen = new Set<string>();
  for (const item of cases) {
    if (item.orderId) seen.add(item.orderId);
  }
  return [...seen];
}
