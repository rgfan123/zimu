/**
 * `GET /api/v1/orders/{id}` → 复核卡片 / 就地处置面板需要的最小事实集。
 *
 * <p><b>为什么必须走订单详情，而不是从复核事项 detail 里读</b>：
 * <ul>
 *   <li>平台原名只在 `order_lines.product_name_snapshot`。复核事项 `detail.blockers[].product_name`
 *       是我方 `products.product_name`（ShipmentJdStockCheckService.loadSkuLabels 的 JOIN 结果），
 *       两者可以完全不同（ShipmentJdSkuMappingGateApiTest 就拿「完全不同的系统展示名」锁过这一点）。</li>
 *   <li>收件人**根本不在**复核事项里，而且 `raw_import_rows.raw_cells` 里的姓名/电话
 *       已被 SourceImportService.sanitizeSnapshot 截成 3 字前缀，捞不回来——只能 JOIN `app.orders`。</li>
 * </ul>
 *
 * <p><b>为什么不让后端把收件人写进 review_cases.detail</b>：那会把 PII 固化进一张被
 * 多方消费（含企微投影）的 JSONB 里。工作台是内部页面，展示可以；落库扩散不行。
 * 于是收件人只在前端按需拉取、只在这一屏出现。
 */

import type { OrderDetail } from '@/api/types';
import type { OrderFacts } from './reviewCardPresentation.ts';

export type { OrderFacts };

/** 省市区乡镇 + 详细地址，与订单详情页同口径（空段跳过）。 */
export function fullAddress(receiver: OrderDetail['receiver']): string {
  return [receiver.province, receiver.city, receiver.district, receiver.town, receiver.address]
    .filter(Boolean)
    .join(' ');
}

/** 订单详情 → 卡片事实。只取会上屏的字段，不把整个 OrderDetail 拖进展示层。 */
export function toOrderFacts(detail: OrderDetail): OrderFacts {
  const productNameByLineId: Record<string, string> = {};
  for (const line of detail.lines) {
    // line.product_name 就是 product_name_snapshot 的 DTO 投影（OrderMapper.toOrderLine）。
    if (line.product_name) productNameByLineId[line.id] = line.product_name;
  }
  return {
    orderNo: detail.order_no ?? null,
    receiverName: detail.receiver?.name ?? null,
    receiverPhone: detail.receiver?.phone ?? null,
    receiverAddress: detail.receiver ? fullAddress(detail.receiver) || null : null,
    productNameByLineId,
  };
}
