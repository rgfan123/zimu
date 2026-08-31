import { ApiError, errorMessage, type RequestOptions } from './client.ts';
import type { ManualOrderCreateInput, ManualOrderItemInput } from './types.ts';

/**
 * 手工建单（V100 MANUAL 渠道）的请求装配与幂等键。
 *
 * <p>两步走：① POST /api/v1/orders/manual 建单（201 → OrderDetail）；
 * ② POST /api/v1/orders/{id}/fulfillment-routing 生成发货单（201 → shipment_ids）。
 * 本模块只做纯函数装配，供页面与 node:test 直跑共用（相对 .ts 导入，不经 @/ 别名）。
 */

/** V99 整数纪律：数量是正整数字符串，禁止前导零/小数/空白。 */
export const MANUAL_QUANTITY_PATTERN = /^[1-9][0-9]*$/;

export function isValidManualQuantity(value: string): boolean {
  return MANUAL_QUANTITY_PATTERN.test(value);
}

/** FNV-1a 32-bit hash，输出 8 位小写 hex（与 zhonghuiPmsIdempotency 同实现）。 */
function fnv1aHex(text: string): string {
  let hash = 0x811c9dc5;
  for (let i = 0; i < text.length; i++) {
    hash ^= text.charCodeAt(i);
    hash = Math.imul(hash, 0x01000193) >>> 0;
  }
  return hash.toString(16).padStart(8, '0');
}

/**
 * 手工建单幂等键 = 草稿指纹（nonce）+ 内容哈希。
 *
 * <p>三种情形各得其所：
 * <ul>
 *   <li>同一草稿重复点击 → 同键同载荷，服务端重放首次结果，不会建两单；</li>
 *   <li>失败后改内容再提交 → 内容哈希变 → 新键（同键不同载荷会被服务端判
 *       IDEMPOTENCY_CONFLICT 拒掉，这里主动换键）；</li>
 *   <li>明天再录一张一模一样的单 → 新草稿 nonce → 新键（纯内容哈希会把真实的
 *       第二张订单误判为重放，这正是不能照抄中汇批量上传纯内容键的原因）。</li>
 * </ul>
 */
export function manualOrderIdempotencyKey(draftNonce: string, body: ManualOrderCreateInput): string {
  // customer_code 可选：undefined 会被 JSON.stringify 稳定地丢掉——「不带客户」
  // 自成一种内容形态，与任何显式客户编码天然不同键。
  const canonical = JSON.stringify({
    customer_code: body.customer_code,
    receiver: body.receiver,
    items: body.items,
    remark: body.remark ?? '',
  });
  return `manual-order-${draftNonce}-${fnv1aHex(canonical)}`;
}

/**
 * 路由幂等键钉住「订单 × 期望版本」：同版本重试是重放（网络断在响应路上时不会重复路由），
 * 刷新版本后重试是新请求（载荷变了，必须换键避免 IDEMPOTENCY_CONFLICT）。
 */
export function manualOrderRoutingIdempotencyKey(orderId: string, expectedOrderVersion: number): string {
  return `manual-order-routing-${orderId}-v${expectedOrderVersion}`;
}

/** 页面表单形状（antd 嵌套 name 路径直出），字段可缺——builder 是最后一道断言。 */
export interface ManualOrderFormValues {
  customer_code?: string;
  receiver?: { name?: string; phone?: string; address?: string };
  items?: Array<{ sku_id?: string; quantity?: string } | undefined>;
  remark?: string;
}

/**
 * 表单值 → 契约载荷：trim 一切字符串，空备注整个不发（发 "" 没有业务含义）。
 * 客户可选：不选就整个不带 customer_code 字段（服务端自动归属「手工平台客户」
 * MANUAL-PLATFORM），发 "" 会被契约当成显式绑定拒掉。缺收货三要素/行内空 SKU/
 * 非法数量在这里直接抛错——表单校验先挡，这里是提交前最后一道纯函数断言，
 * 抛出的消息可直接上屏。
 */
export function manualOrderCreateBody(values: ManualOrderFormValues): ManualOrderCreateInput {
  const customerCode = values.customer_code?.trim() ?? '';
  const name = values.receiver?.name?.trim() ?? '';
  const phone = values.receiver?.phone?.trim() ?? '';
  const address = values.receiver?.address?.trim() ?? '';
  if (!name || !phone || !address) throw new Error('收货人姓名、电话、地址均为必填');
  const rows = (values.items ?? []).filter((item): item is { sku_id?: string; quantity?: string } => Boolean(item));
  const items: ManualOrderItemInput[] = rows.map((item, index) => {
    const skuId = item.sku_id?.trim() ?? '';
    const quantity = item.quantity?.trim() ?? '';
    if (!skuId) throw new Error(`第 ${index + 1} 行商品未选择`);
    if (!isValidManualQuantity(quantity)) throw new Error(`第 ${index + 1} 行数量必须为正整数`);
    return { sku_id: skuId, quantity };
  });
  if (items.length === 0) throw new Error('至少需要一行商品');
  const remark = values.remark?.trim();
  return {
    ...(customerCode ? { customer_code: customerCode } : {}),
    receiver: { name, phone, address },
    items,
    ...(remark ? { remark } : {}),
  };
}

export function manualOrderCreateRequest(
  body: ManualOrderCreateInput,
  headers: Record<string, string>,
): { path: string; options: RequestOptions } {
  return {
    path: '/api/v1/orders/manual',
    options: { method: 'POST', body, headers },
  };
}

export function manualOrderRoutingRequest(
  orderId: string,
  expectedOrderVersion: number,
  headers: Record<string, string>,
): { path: string; options: RequestOptions } {
  return {
    path: `/api/v1/orders/${orderId}/fulfillment-routing`,
    options: { method: 'POST', body: { expected_order_version: expectedOrderVersion }, headers },
  };
}

/**
 * 手工建单/路由失败的用户可读文案：优先展示后端 message（MANUAL_ORDER_* 与
 * ORDER_ROUTING_* / VERSION_CONFLICT 的 message 都是运营能直接行动的具体原因，
 * 如「客户不存在或已停用: C001」），后端没给 message 才落回通用分类文案。
 */
export function manualOrderErrorText(err: unknown): string {
  if (err instanceof ApiError) {
    const backendMessage = err.body.message?.trim();
    if (backendMessage) return backendMessage;
  }
  return errorMessage(err);
}
