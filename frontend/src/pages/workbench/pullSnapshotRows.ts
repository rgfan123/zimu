/**
 * 批次快照弹窗的取数口径：正式订单优先，rows API 的 parsed 白名单其次，
 * raw_cells 只保留脱敏证据，不参与 PII 主表展示。
 *
 * <p>候选流水线下，结构化拉取确认前不会创建正式订单，raw row 的 order_id 必须为空。
 * 后端因此会把仍在服务端的 CanonicalOrderInput 按 raw row 投影成七个白名单字段；
 * 本模块把这份投影作为 SNAPSHOT 口径展示。确认后 order_id 回填，订单实体重新取得最高优先级。
 *
 * <p>商品与件数必须同源：两者要么都来自订单行，要么都来自候选/文件解析投影，
 * 绝不允许一个取订单、一个取投影。
 */

const EMPTY_MARK = '—';

/** 事实来自哪一层；SNAPSHOT 包含文件解析投影与确认前的候选安全投影。 */
export type FactSource = 'ORDER' | 'SNAPSHOT' | 'NONE';

/** 弹窗会读到的导入行字段（结构化入参，便于测试直接构造夹具）。 */
export interface SnapshotImportRow {
  /** 系统订单关联；`presentImportRow` 在缺失时给的是 '—' 而不是 null。 */
  orderId: string;
  orderLineId: string;
  receiverName: string;
  receiverPhone: string;
  receiverAddress: string;
  productName: string;
  quantity: string;
  specification: string;
}

/** 订单实体里本模块会读到的字段。 */
export interface SnapshotOrderSource {
  order_no?: string | null;
  order_status?: string | null;
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
    requested_quantity?: number | null;
  }> | null;
}

export interface SnapshotRowFacts {
  receiverName: string | null;
  receiverPhone: string | null;
  receiverAddress: string | null;
  receiverSource: FactSource;
  /** 平台原名（order_lines.product_name_snapshot）优先。 */
  productName: string | null;
  quantity: string | null;
  specification: string | null;
  /** 商品/件数/规格共用一个来源——三者永远同源，不混口径。 */
  productSource: FactSource;
  orderId: string | null;
  orderNo: string | null;
  orderStatus: string | null;
  /**
   * 该行**为什么**待复核：复核事项的 reason_code（如 SKU_MAPPING_REQUIRED），
   * 中文由渲染层套 reasonLabel，缺译回退原码。
   *
   * <p><b>不能用订单的 `attention_reason`</b>：它是视图 `v_order_progress_summary` 里
   * `min(exception_reason) FILTER (WHERE processing_stage='EXCEPTION')` 派生的，
   * 而「商品没有对应 SKU」把行置为 `NEED_REVIEW` 而非 `EXCEPTION`，也不产生 operational_alert——
   * 于是恰恰在这个最常见的待复核场景里 attention_reason 是 NULL。review_cases 才是唯一真源。
   */
  reviewReasonCode: string | null;
  /** 该行是否压根没建单——渲染层据此显示「未建单」而不是破折号。 */
  hasOrder: boolean;
}

/** 复核事项里本模块会读到的字段。 */
export interface SnapshotReviewCase {
  order_id?: string | null;
  order_line_id?: string | null;
  reason_code?: string | null;
}

/** 空串、全空白与 `presentImportRow` 的 '—' 占位一律视为「没有」。 */
function realOrNull(value: string | null | undefined): string | null {
  if (typeof value !== 'string') return null;
  const trimmed = value.trim();
  if (trimmed.length === 0 || trimmed === EMPTY_MARK) return null;
  return trimmed;
}

/** 省市区乡镇 + 详细地址，与订单详情页同口径（空段跳过）。 */
function fullAddress(receiver: SnapshotOrderSource['receiver']): string | null {
  if (!receiver) return null;
  const joined = [receiver.province, receiver.city, receiver.district, receiver.town, receiver.address]
    .map(realOrNull)
    .filter((part): part is string => part !== null)
    .join(' ');
  return joined.length > 0 ? joined : null;
}

/**
 * 一行导入行 + （可能拿到的）订单实体 → 弹窗要显示的事实。
 *
 * @param row          `presentImportRow` 的投影（快照/解析口径）
 * @param order        经 `row.orderId` 拉到的订单实体；没建单或没拉到时传 null
 * @param reviewReason 该行对应复核事项的 reason_code（由 reviewReasonFor 从整批事项里查）
 */
export function presentSnapshotRowFacts(
  row: SnapshotImportRow,
  order: SnapshotOrderSource | null,
  reviewReason: string | null = null,
): SnapshotRowFacts {
  const orderId = realOrNull(row.orderId);

  // —— 收件人：订单实体优先（快照里被脱敏，捞不回来）——
  const orderReceiverName = realOrNull(order?.receiver?.name);
  const orderReceiverPhone = realOrNull(order?.receiver?.phone);
  const orderReceiverAddress = fullAddress(order?.receiver);
  const hasOrderReceiver = Boolean(orderReceiverName || orderReceiverPhone || orderReceiverAddress);

  const snapshotName = realOrNull(row.receiverName);
  const snapshotPhone = realOrNull(row.receiverPhone);
  const snapshotAddress = realOrNull(row.receiverAddress);
  const hasSnapshotReceiver = Boolean(snapshotName || snapshotPhone || snapshotAddress);

  // —— 商品/件数/规格：整组同源，不允许一个取订单、一个取快照 ——
  const line = order?.lines?.find((item) => item.id === realOrNull(row.orderLineId)) ?? null;
  const lineProductName = realOrNull(line?.product_name);
  const lineQuantity = line?.requested_quantity != null ? realOrNull(String(line.requested_quantity)) : null;
  const hasOrderLine = Boolean(lineProductName || lineQuantity);

  const snapshotProductName = realOrNull(row.productName);
  const snapshotQuantity = realOrNull(row.quantity);
  const hasSnapshotProduct = Boolean(snapshotProductName || snapshotQuantity);

  return {
    receiverName: hasOrderReceiver ? orderReceiverName : hasSnapshotReceiver ? snapshotName : null,
    receiverPhone: hasOrderReceiver ? orderReceiverPhone : hasSnapshotReceiver ? snapshotPhone : null,
    receiverAddress: hasOrderReceiver ? orderReceiverAddress : hasSnapshotReceiver ? snapshotAddress : null,
    receiverSource: hasOrderReceiver ? 'ORDER' : hasSnapshotReceiver ? 'SNAPSHOT' : 'NONE',

    productName: hasOrderLine ? lineProductName : hasSnapshotProduct ? snapshotProductName : null,
    quantity: hasOrderLine
      ? (lineQuantity === null ? null : `${lineQuantity}${realOrNull(line?.unit) ?? ''}`)
      : hasSnapshotProduct ? snapshotQuantity : null,
    specification: hasOrderLine ? realOrNull(line?.specification) : realOrNull(row.specification),
    productSource: hasOrderLine ? 'ORDER' : hasSnapshotProduct ? 'SNAPSHOT' : 'NONE',

    orderId,
    orderNo: realOrNull(order?.order_no),
    orderStatus: realOrNull(order?.order_status),
    reviewReasonCode: realOrNull(reviewReason),
    hasOrder: orderId !== null,
  };
}

/**
 * 从整批复核事项里查这一行的原因码。
 *
 * <p>优先按 `order_line_id` 精确匹配（一单多商品时只有出问题的那一行才该被标红）；
 * 匹配不到再退回同订单的事项——总比只显示一个「待复核」强。
 *
 * <p>整批事项一次拉回（`GET /api/v1/review-cases?import_batch_id=…`），不是逐行请求。
 */
export function reviewReasonFor(
  row: SnapshotImportRow,
  cases: SnapshotReviewCase[],
): string | null {
  const orderLineId = realOrNull(row.orderLineId);
  const orderId = realOrNull(row.orderId);
  if (orderLineId) {
    const exact = cases.find((item) => realOrNull(item.order_line_id) === orderLineId);
    if (exact) return realOrNull(exact.reason_code);
  }
  if (orderId) {
    const byOrder = cases.find((item) => realOrNull(item.order_id) === orderId);
    if (byOrder) return realOrNull(byOrder.reason_code);
  }
  return null;
}

/**
 * 一页快照要拉哪些订单：去重后的 order_id 列表。
 *
 * <p>一张订单可能对应多行（一单多商品），去重后请求数远小于行数；
 * 且弹窗一页固定 20 行，天然有上界。
 */
export function snapshotOrderIdsToLoad(rows: SnapshotImportRow[]): string[] {
  const seen = new Set<string>();
  for (const row of rows) {
    const orderId = realOrNull(row.orderId);
    if (orderId) seen.add(orderId);
  }
  return [...seen];
}

/**
 * 缺值时该显示什么——**永远给原因，不给光秃秃的破折号**。
 * 没建单就说「未建单」，建了单但这一项确实空着才说「未提供」。
 */
export function missingFactLabel(facts: SnapshotRowFacts): string {
  return facts.hasOrder ? '未提供' : '未建单';
}
