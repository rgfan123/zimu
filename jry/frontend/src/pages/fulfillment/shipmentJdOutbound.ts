import type { JdClientMode, JdClientStatus, ShipmentJdOutbound, ShipmentJdOutboundPreview } from '../../api/types.ts';

export interface ShipmentJdOutboundPresentation {
  statusLabel: string;
  statusTone: 'default' | 'processing' | 'success' | 'error' | 'warning';
  modeLabel: string;
  actionLabel: string;
  canSubmit: boolean;
}

export interface ShipmentJdOutboundRuntimeGate {
  ready: boolean;
  mode: JdClientMode | undefined;
  confirmation: string;
}

export interface ShipmentJdOutboundSubmitGateInput {
  selectedShipmentId?: string;
  detailShipmentId?: string;
  previewShipmentId?: string;
  isJdShipment: boolean;
  presentationAllowsSubmit: boolean;
  detailLoading: boolean;
  detailError: boolean;
  previewSubmittable: boolean;
  previewLoading: boolean;
  previewError: boolean;
  runtimeReady: boolean;
  runtimeLoading: boolean;
  runtimeError: boolean;
  submitting: boolean;
}

/** Never let useAsync's retained data authorize a different or currently refreshing Shipment. */
export function canSubmitJdOutbound(input: ShipmentJdOutboundSubmitGateInput): boolean {
  return Boolean(input.selectedShipmentId)
    && input.selectedShipmentId === input.detailShipmentId
    && input.selectedShipmentId === input.previewShipmentId
    && input.isJdShipment
    && input.presentationAllowsSubmit
    && !input.detailLoading
    && !input.detailError
    && input.previewSubmittable
    && !input.previewLoading
    && !input.previewError
    && input.runtimeReady
    && !input.runtimeLoading
    && !input.runtimeError
    && !input.submitting;
}

/** A high-risk external write stays disabled until the current runtime status is known. */
export function jdOutboundRuntimeGate(
  runtime?: Pick<JdClientStatus, 'client_mode' | 'live_ready'> | null,
): ShipmentJdOutboundRuntimeGate {
  if (!runtime) {
    return {
      ready: false,
      mode: undefined,
      confirmation: '运行环境尚未确认，不能提交',
    };
  }
  if (runtime.client_mode === 'REAL') {
    return {
      ready: runtime.live_ready === true,
      mode: 'REAL',
      confirmation: '确认向真实京东提交这张出库单？',
    };
  }
  return {
    ready: true,
    mode: 'MOCK',
    confirmation: '确认在模拟环境提交？',
  };
}

export function jdOutboundPresentation(
  outbound?: ShipmentJdOutbound | null,
): ShipmentJdOutboundPresentation {
  if (!outbound) {
    return {
      statusLabel: '未提交',
      statusTone: 'default',
      modeLabel: '环境待确认',
      actionLabel: '提交京东出库单',
      canSubmit: true,
    };
  }

  const modeLabel = outbound.client_mode === 'REAL'
    ? '真实京东'
    : outbound.client_mode === 'MOCK'
      ? '模拟环境'
      : '历史环境未记录';
  if (outbound.sync_status === 'SUBMITTING') {
    return {
      statusLabel: '提交中',
      statusTone: 'processing',
      modeLabel,
      actionLabel: '正在提交…',
      canSubmit: false,
    };
  }
  if (outbound.sync_status === 'SUBMITTED') {
    return {
      statusLabel: '已提交',
      statusTone: 'success',
      modeLabel,
      actionLabel: '已提交',
      canSubmit: false,
    };
  }
  if (!outbound.retryable) {
    return {
      statusLabel: '需对账',
      statusTone: 'warning',
      modeLabel,
      actionLabel: '需先对账',
      canSubmit: false,
    };
  }
  return {
    statusLabel: '提交失败',
    statusTone: 'error',
    modeLabel,
    actionLabel: '重试提交',
    canSubmit: true,
  };
}

export interface JdOutboundCargoLine {
  orderLine: string;
  goodsNo: string;
  goodsName: string;
  planQuantity: number;
}

export interface JdOutboundConfirmationDetail {
  erpDeliveryNo?: string;
  cargos: JdOutboundCargoLine[];
}

function asCargoLine(entry: unknown): JdOutboundCargoLine | null {
  if (!entry || typeof entry !== 'object') return null;
  const cargo = entry as Record<string, unknown>;
  const goodsNo = typeof cargo.goodsNo === 'string' ? cargo.goodsNo : '';
  const goodsName = typeof cargo.goodsName === 'string' ? cargo.goodsName : '';
  const planQuantity = typeof cargo.planQuantity === 'number'
    ? cargo.planQuantity
    : typeof cargo.planQuantity === 'string'
      ? Number(cargo.planQuantity)
      : Number.NaN;
  if (!goodsNo && !goodsName) return null;
  return {
    orderLine: typeof cargo.orderLine === 'string' ? cargo.orderLine : '',
    goodsNo,
    goodsName,
    planQuantity: Number.isFinite(planQuantity) ? planQuantity : Number.NaN,
  };
}

/** Objects the submit will actually send to JD, parsed from the display-safe preview request. */
export function jdOutboundConfirmationDetail(
  preview?: Pick<ShipmentJdOutboundPreview, 'erp_delivery_no' | 'request'> | null,
  fallbackErpDeliveryNo?: string | null,
): JdOutboundConfirmationDetail {
  const cargos: JdOutboundCargoLine[] = [];
  const raw = preview?.request?.['cargoInfos'];
  if (Array.isArray(raw)) {
    for (const entry of raw) {
      const line = asCargoLine(entry);
      if (line) cargos.push(line);
    }
  }
  return {
    erpDeliveryNo: preview?.erp_delivery_no || fallbackErpDeliveryNo || undefined,
    cargos,
  };
}

/** REAL confirmation keeps its warning and carries the outbound key identifier. */
export function jdOutboundConfirmationTitle(
  mode: JdClientMode | undefined,
  baseConfirmation: string,
  erpDeliveryNo?: string,
): string {
  if (mode === 'REAL' && erpDeliveryNo) {
    return `${baseConfirmation}（商户出库号 ${erpDeliveryNo}）`;
  }
  return baseConfirmation;
}
