import type { OrderShipment } from '@/api/types';

export type JdFulfillmentState = 'NOT_CREATED' | 'SYNCING' | 'FAILED' | 'REVIEWED' | 'RETURNED';

export interface JdFulfillmentPresentation {
  state: JdFulfillmentState;
  stateLabel: '未建单' | '同步中' | '失败' | '人工终结' | '已回传';
  tone: 'default' | 'processing' | 'error' | 'warning' | 'success';
}

export interface OrderShipmentPublicFields {
  providerName: string;
  erpDeliveryNo: string;
  jdDeliveryNo: string;
  syncState: JdFulfillmentPresentation['stateLabel'];
  failurePhase: string;
  tracking: string;
  updatedAt: string;
}

const FAILURE_PHASE_LABELS: Readonly<Record<string, string>> = {
  VALIDATION: '建单校验',
  SUBMIT: '提交建单',
  TRACKING_QUERY: '运单查询',
  TRACKING_ACCEPT: '运单回填',
};

function newestTimestamp(...values: Array<string | null | undefined>): string {
  return values
    .filter((value): value is string => Boolean(value))
    .reduce((latest, value) => (Date.parse(value) > Date.parse(latest) ? value : latest));
}

/**
 * 订单视图的 Shipment 级京东履约状态。
 * “已回传”只由已接受的 Tracking 事实证明；SUBMITTED 仍表示等待运单回填。
 */
export function jdFulfillmentPresentation(shipment: OrderShipment): JdFulfillmentPresentation {
  if (!shipment.jd_outbound) {
    return { state: 'NOT_CREATED', stateLabel: '未建单', tone: 'default' };
  }
  if (shipment.jd_outbound.tracking_query_status === 'TERMINAL_REVIEWED') {
    return { state: 'REVIEWED', stateLabel: '人工终结', tone: 'warning' };
  }
  if (
    shipment.jd_outbound.sync_status === 'SYNC_FAILED'
    || shipment.jd_outbound.tracking_query_status === 'QUERY_FAILED'
    || shipment.jd_outbound.tracking_query_status === 'CONFLICT'
  ) {
    return { state: 'FAILED', stateLabel: '失败', tone: 'error' };
  }
  if (shipment.tracking) {
    return { state: 'RETURNED', stateLabel: '已回传', tone: 'success' };
  }
  return { state: 'SYNCING', stateLabel: '同步中', tone: 'processing' };
}

/**
 * 组装订单详情可见的最小履约白名单。不向表格传递收件人、凭据或供应商原始响应。
 */
export function orderShipmentPublicFields(
  shipment: OrderShipment,
  providerName: string,
): OrderShipmentPublicFields {
  const jd = shipment.jd_outbound;
  return {
    providerName,
    erpDeliveryNo: jd?.erp_delivery_no ?? shipment.outbound_order_no ?? '—',
    jdDeliveryNo: jd?.jd_delivery_no ?? '—',
    syncState: jdFulfillmentPresentation(shipment).stateLabel,
    failurePhase: jd?.tracking_query_status === 'TERMINAL_REVIEWED'
      ? '运单异常终态'
      : jd?.tracking_query_status === 'QUERY_FAILED'
      ? FAILURE_PHASE_LABELS.TRACKING_QUERY
      : jd?.tracking_query_status === 'CONFLICT'
        ? FAILURE_PHASE_LABELS.TRACKING_ACCEPT
        : jd?.failure_phase
          ? FAILURE_PHASE_LABELS[jd.failure_phase] ?? jd.failure_phase
          : '—',
    tracking: shipment.tracking
      ? `${shipment.tracking.logistics_company_name} · ${shipment.tracking.tracking_number}`
      : '—',
    updatedAt: newestTimestamp(shipment.updated_at, shipment.tracking?.received_at, jd?.updated_at),
  };
}
