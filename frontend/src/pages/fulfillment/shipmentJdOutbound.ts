import type { JdClientMode, JdClientStatus, ProcessingStage, ShipmentJdOutbound, ShipmentJdOutboundPreview, ShipmentStatus } from '../../api/types.ts';

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

/**
 * 建单预检 blocker 消息里会出现的状态/阶段枚举 → 中文。
 *
 * 文案与 constants/labels.ts 的 SHIPMENT_STATUS_LABELS / PROCESSING_STAGE_LABELS 同句；
 * 不直接引用是因为 labels.ts 值依赖 `@/` 别名模块，node:test（strip-types 直跑）加载不了。
 * 用 Record<两枚举并集> 锁全量覆盖：枚举增删改名会在编译期炸掉；文案漂移由
 * shipmentJdOutbound.test.ts 的源文本对账用例拦截（导出仅供该用例使用）。
 */
export const JD_BLOCKER_ENUM_LABELS: Record<ShipmentStatus | ProcessingStage, string> = {
  CREATED: '已创建',
  SHIPPED: '已发货',
  FAILED: '失败',
  DELIVERED: '已送达',
  NEED_REVIEW: '待复核',
  READY_TO_EXPORT: '待生成发货表',
  PROCUREMENT_IN_PROGRESS: '采购中',
  WAITING_PROVIDER: '等待履约方',
  TRACKING_RECEIVED: '已取得运单',
  RETURN_FILE_READY: '待生成回填表',
  COMPLETED: '已完成',
  EXCEPTION: '异常',
};

// 两侧的可选 ASCII 空格一并吸收：后端消息为中英混排（"必须是 CREATED 才能"），
// 换成全角引号后原空格会变成「已创建」两侧的多余空隙
const JD_BLOCKER_ENUM_PATTERN = new RegExp(
  ` ?\\b(${Object.keys(JD_BLOCKER_ENUM_LABELS).sort((a, b) => b.length - a.length).join('|')})\\b ?`,
  'g',
);

/**
 * 把 blocker 消息里的裸枚举翻成「中文」。登记之外的标识符（配置键 warehouseNo、
 * JD_WAREHOUSE 这类已被上下文解释的码）原样保留——宁可被人看见去问，不编可能错的话。
 */
export function jdOutboundBlockerText(blocker: { message: string }): string {
  return blocker.message.replace(
    JD_BLOCKER_ENUM_PATTERN,
    (_match, token: string) => `「${JD_BLOCKER_ENUM_LABELS[token as ShipmentStatus | ProcessingStage]}」`,
  );
}

/**
 * 建单卡片的提示区投影。
 *
 * 2026-08-31 生产实证（商户出库号 202608310004）：已提交成功且运单已回传的批次，
 * 重复提交预检的三条 blocker（状态非 CREATED / 行阶段不符 / 禁止重复提交）被渲染成
 * 黄色「当前不可提交」+ 裸枚举，用户误判流程出错、不敢走下一步回传。
 * 已提交（SUBMITTED）是成功终态：给绿色确认并指路下一步；阻断原因只在真的
 * 可操作（未提交 / 可重试失败）且预检不过时出现，且必须先说人话。
 */
export type JdOutboundNotice =
  | { kind: 'SUBMITTED_OK'; message: string; description: string }
  | { kind: 'BLOCKED'; message: string; reasons: string[] }
  | { kind: 'NONE' };

export function jdOutboundNotice(input: {
  outbound: ShipmentJdOutbound | null | undefined;
  preview: { submittable: boolean; blockers: Array<{ message: string }> } | null | undefined;
  /** 与页面回传入口同判据（已发货 + 已有正式运单），决定成功提示指向哪一步 */
  canSyncToSource: boolean;
}): JdOutboundNotice {
  if (input.outbound?.sync_status === 'SUBMITTED') {
    return {
      kind: 'SUBMITTED_OK',
      message: '京东出库单已提交成功，无需重复提交',
      description: input.canSyncToSource
        ? '下一步：在下方「回传给客户平台」把发货与运单结果回传。'
        : '等待京东发货并回传运单；取得运单后即可在下方回传给客户平台。',
    };
  }
  // 提交中 / 需对账没有可执行动作，预检原因只是噪音。需对账时的解释依赖
  // last_error_message 告警：后端 persistSubmitFailure 是 SYNC_FAILED 的唯一写入点且
  // 恒写非空消息（缺省「京东出库单提交失败」）——该不变量只有约定没有类型保证。
  if (!jdOutboundPresentation(input.outbound).canSubmit) return { kind: 'NONE' };
  if (!input.preview || input.preview.submittable) return { kind: 'NONE' };
  // 多行同因（如多条订单行同处错误阶段）会产生逐字相同的 blocker 消息，去重后再展示
  return {
    kind: 'BLOCKED',
    message: '当前不可提交',
    reasons: [...new Set(input.preview.blockers.map(jdOutboundBlockerText))],
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
