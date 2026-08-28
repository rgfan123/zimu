/**
 * 就地处置面板的**订单二级信息**投影：`GET /api/v1/orders/{id}` → 「这是谁的哪一单」。
 *
 * <p><b>为什么处置面板必须先摆事实</b>：面板里的两个动作（换货、重新核对）都在改真实订单，
 * 而改之前操作员手上只有一条阻断文案。没有订单来源、收货人和商品清单，换货就是盲改——
 * 换错行、换错单都不会有任何提示。所以「先看清，再动手」，事实区排在动作区前面。
 *
 * <p><b>商品名一律用平台原名</b>：`order_lines.product_name_snapshot`（如聚福宝的
 * 「【京东配送】子牧牛肉惠选礼包1400g」），不是我方 `products.product_name`。
 * 复核事项 `detail.blockers[].product_name` 带的恰恰是后者，两者可以完全不同，
 * 拿内部名跟客户对单会对不上（同 reviewCardPresentation 的口径）。
 *
 * <p><b>PII 边界</b>：收货人姓名/电话/地址只在这个内部工作台页面出现。它们**不进**
 * `review_cases.detail`（那张 JSONB 被多方消费，含企微投影），也**不得**流向企微卡片
 * 或任何对外接口——所以这里是前端按需拉取的临时投影，不是落库字段。
 *
 * <p>纯函数、无 React、无 fetch，可被 node:test 直接加载：只用相对路径 + `.ts` 后缀，
 * 不走 `@/` 别名（`constants/labels.ts` 有值导入走别名，因此这里**不引**它——
 * 渠道/状态的中文标签由渲染层套用，缺译回退原码）。
 */

import { formatDateTime } from '../../format/dateTime.ts';
import type { StockBlockerItem } from './stockBlockerCases.ts';

/** 面板商品清单的一行。数量保持后端原样字符串，不做本地数值格式化（避免精度漂移）。 */
export interface OrderContextLine {
  id: string;
  /** 平台原名（product_name_snapshot）。 */
  productName: string;
  specification: string | null;
  unit: string | null;
  quantity: string;
  skuCode: string | null;
  /** 该行是否正是本次阻断锁定的行——面板据此标出「就是它」，省去人工比对。 */
  blocked: boolean;
}

export interface OrderContextView {
  orderNo: string | null;
  /** 来源渠道原码（CAISHIXIAN/JUFUBAO/…）；中文标签由渲染层套 CHANNEL_LABELS，缺译回退原码。 */
  sourceChannel: string | null;
  /** 来源平台上的单号（source_ref）——跟渠道对账时对的就是这个号。 */
  sourceRef: string | null;
  /** 订单状态原码；中文标签由渲染层套 ORDER_STATUS_LABELS，缺译回退原码。 */
  orderStatus: string | null;
  /** 已格式化的下单时间（Asia/Shanghai）。 */
  orderedAt: string;
  /**
   * `orderedAt` 是否退回了「我方入库时间」（source_ordered_at 缺失时的回退）。
   * 渲染层据此改标签，不把入库时间冒充成平台下单时间。
   */
  orderedAtIsFallback: boolean;
  receiverName: string | null;
  receiverPhone: string | null;
  receiverAddress: string | null;
  lines: OrderContextLine[];
}

/** 订单详情里本模块会读到的字段（结构化入参，不依赖 api/types，便于测试直接构造夹具）。 */
export interface OrderContextSource {
  order_no?: string | null;
  source_channel?: string | null;
  source_ref?: string | null;
  order_status?: string | null;
  source_ordered_at?: string | null;
  created_at?: string | null;
  receiver?: {
    name?: string | null;
    phone?: string | null;
    province?: string | null;
    city?: string | null;
    district?: string | null;
    town?: string | null;
    address?: string | null;
  } | null;
  lines?: Array<{
    id: string;
    product_name?: string | null;
    specification?: string | null;
    unit?: string | null;
    requested_quantity?: string | null;
    sku_code?: string | null;
  }> | null;
}

/** 空字符串与全空白一律视为「没有」，避免面板上出现空标签行。 */
function trimmedOrNull(value: string | null | undefined): string | null {
  if (typeof value !== 'string') return null;
  const trimmed = value.trim();
  return trimmed.length > 0 ? trimmed : null;
}

/** 省市区乡镇 + 详细地址，与订单详情页同口径（空段跳过）。 */
export function fullAddress(receiver: OrderContextSource['receiver']): string | null {
  if (!receiver) return null;
  const joined = [receiver.province, receiver.city, receiver.district, receiver.town, receiver.address]
    .map(trimmedOrNull)
    .filter((part): part is string => part !== null)
    .join(' ');
  return joined.length > 0 ? joined : null;
}

/** 本批阻断锁定的订单行 id 集合——面板用它在商品清单里标出出问题的那几行。 */
export function blockedOrderLineIds(blockers: StockBlockerItem[]): Set<string> {
  const ids = new Set<string>();
  for (const blocker of blockers) {
    for (const lineId of blocker.orderLineIds) ids.add(lineId);
  }
  return ids;
}

/**
 * 订单详情 + 本批阻断 → 面板事实区。
 *
 * <p>商品名缺失时**不**回退到内部 SKU 名（这里根本拿不到），而是显示编码或诚实占位——
 * 宁可显示「（商品名缺失）」也不显示一个会让人对错单的名字。
 */
export function presentOrderContext(
  detail: OrderContextSource,
  blockers: StockBlockerItem[] = [],
): OrderContextView {
  const blocked = blockedOrderLineIds(blockers);
  const sourceOrderedAt = trimmedOrNull(detail.source_ordered_at);
  const createdAt = trimmedOrNull(detail.created_at);
  // 平台下单时刻优先；缺失才退回我方入库时刻，并把「这是回退值」如实标出来。
  const orderedAtIsFallback = sourceOrderedAt === null;
  const orderedAtRaw = sourceOrderedAt ?? createdAt;

  return {
    orderNo: trimmedOrNull(detail.order_no),
    sourceChannel: trimmedOrNull(detail.source_channel),
    sourceRef: trimmedOrNull(detail.source_ref),
    orderStatus: trimmedOrNull(detail.order_status),
    orderedAt: formatDateTime(orderedAtRaw),
    orderedAtIsFallback,
    receiverName: trimmedOrNull(detail.receiver?.name),
    receiverPhone: trimmedOrNull(detail.receiver?.phone),
    receiverAddress: fullAddress(detail.receiver),
    lines: (detail.lines ?? []).map((line) => ({
      id: line.id,
      productName: trimmedOrNull(line.product_name) ?? '（商品名缺失）',
      specification: trimmedOrNull(line.specification),
      unit: trimmedOrNull(line.unit),
      quantity: trimmedOrNull(line.requested_quantity) ?? '—',
      skuCode: trimmedOrNull(line.sku_code),
      blocked: blocked.has(line.id),
    })),
  };
}
