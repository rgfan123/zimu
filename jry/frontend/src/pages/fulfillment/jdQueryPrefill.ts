export type JdStockQueryKind =
  | 'snapshot'
  | 'summary'
  | 'batchChanges'
  | 'levelChanges'
  | 'shelfLifeGoods'
  | 'shelfLifeInventory'
  | 'shopStockFlow';

export type JdSerialQueryKind = 'mall' | 'condition' | 'flow' | 'inside';

export interface JdQueryPrefill<TKind extends string> {
  kind: TKind;
  values: Record<string, string>;
}

const STOCK_FIELDS: Readonly<Record<JdStockQueryKind, readonly string[]>> = {
  snapshot: ['goods_no', 'goods_level', 'isv_sku', 'seller_goods_sign', 'stock_type', 'above_zero', 'cursor', 'page_size'],
  summary: ['goods_no', 'goods_level', 'isv_sku', 'stock_type', 'above_zero'],
  batchChanges: ['warehouse_no', 'batch_change_no', 'start_date', 'end_date', 'current_page', 'page_size'],
  levelChanges: ['order_no', 'pre_change_level', 'changed_level', 'start_date', 'end_date', 'current_page', 'page_size'],
  shelfLifeGoods: ['order_type', 'check_order_no', 'start_time', 'end_time', 'current_page', 'page_size'],
  shelfLifeInventory: ['warehouse_no', 'goods_no', 'erp_goods_no', 'goods_level', 'status', 'current_page', 'page_size'],
  shopStockFlow: ['shop_no', 'warehouse_no', 'goods_no', 'start_date', 'end_date', 'current_page', 'page_size'],
};

const SERIAL_FIELDS: Readonly<Record<JdSerialQueryKind, readonly string[]>> = {
  mall: ['order_no', 'enterprise_order_no', 'owner_no', 'start_date', 'end_date', 'page_size', 'current_page'],
  condition: ['biz_type', 'query_type', 'owner_no', 'warehouse_no', 'start_date', 'end_date', 'current_page', 'page_size'],
  flow: ['goods_no', 'serial_no', 'query_type'],
  inside: ['goods_no', 'query_type', 'page_size', 'current_page'],
};

function prefill<TKind extends string>(
  params: URLSearchParams,
  fields: Readonly<Record<TKind, readonly string[]>>,
  fallback: TKind,
): JdQueryPrefill<TKind> {
  const requested = params.get('kind');
  const kind = requested && Object.prototype.hasOwnProperty.call(fields, requested)
    ? requested as TKind
    : fallback;
  const values: Record<string, string> = {};
  for (const field of fields[kind]) {
    const value = params.get(field)?.trim();
    if (value) values[field] = value;
  }
  return { kind, values };
}

export function jdStockQueryPrefill(params: URLSearchParams): JdQueryPrefill<JdStockQueryKind> {
  return prefill(params, STOCK_FIELDS, 'snapshot');
}

export function jdSerialQueryPrefill(params: URLSearchParams): JdQueryPrefill<JdSerialQueryKind> {
  return prefill(params, SERIAL_FIELDS, 'mall');
}
