import type { ShipmentJdSkuMappingGateResult } from '@/api/types';

export interface JdSkuMappingReviewEvidence {
  evidenceKey: string;
  shipmentItemId: string;
  orderLineId: string;
  lineLabel: string;
  skuLabel: string;
  issues: string[];
}

const ISSUE_LABELS: Record<string, string> = {
  INTERNAL_SKU_MISSING: '未关联内部 SKU',
  INTERNAL_SKU_INACTIVE: '内部 SKU 已停用',
  MAPPING_MISSING: '缺少京东履约方 SKU 映射',
  MAPPING_INACTIVE: '京东履约方 SKU 映射已停用',
  GOODS_NO_MISSING: '京东商品标识为空',
  UNIT_CONVERSION_MISSING: '缺少显式京东件数换算',
  UNIT_CONVERSION_INVALID: '京东件数换算无效',
  NON_INTEGRAL_QUANTITY: '换算后不是精确正整数件',
  JD_GOODS_QUERY_FAILED: '京东商品只读核对失败',
  JD_GOODS_NOT_FOUND: '京东未查到对应商品',
  GOODS_NO_CONFLICT: '京东商品标识与映射冲突',
  ERP_GOODS_NO_CONFLICT: '商家 SKU 标识与京东事实冲突',
  GOODS_STATUS_MISSING: '京东商品缺少可用状态',
  GOODS_DISABLED: '京东商品当前不可用',
};

function scalar(value: unknown): string {
  return typeof value === 'string' || typeof value === 'number' ? String(value) : '';
}

export function jdSkuMappingReviewEvidence(detail: Record<string, unknown>): JdSkuMappingReviewEvidence[] {
  const affected = Array.isArray(detail.affected_shipment_items) ? detail.affected_shipment_items : [];
  return affected.flatMap((raw) => {
    if (!raw || typeof raw !== 'object') return [];
    const item = raw as Record<string, unknown>;
    const shipmentItemId = scalar(item.shipment_item_id);
    const orderLineId = scalar(item.order_line_id);
    if (!shipmentItemId || !orderLineId) return [];
    const lineNo = scalar(item.line_no);
    const componentNo = scalar(item.component_no);
    const issues = Array.isArray(item.issues)
      ? item.issues.map((issue) => {
        const code = issue && typeof issue === 'object'
          ? scalar((issue as Record<string, unknown>).code)
          : '';
        return ISSUE_LABELS[code] ?? '需进一步核对';
      })
      : [];
    return [{
      evidenceKey: [shipmentItemId, orderLineId, componentNo || '0', scalar(item.sku_id) || '0'].join(':'),
      shipmentItemId,
      orderLineId,
      lineLabel: lineNo
        ? `第 ${lineNo} 行${componentNo ? ` · 组件 ${componentNo}` : ''}`
        : '未标注行号',
      skuLabel: scalar(item.sku_code) || (scalar(item.sku_id) ? `SKU #${scalar(item.sku_id)}` : '未关联 SKU'),
      issues: issues.length ? issues : ['需进一步核对'],
    }];
  });
}

export async function rerunJdSkuMappingReview<TReviewCase>(
  selected: { id: string; subject_id: string },
  dependencies: {
    check: (shipmentId: string) => Promise<ShipmentJdSkuMappingGateResult>;
    loadReviewCase: (reviewCaseId: string) => Promise<TReviewCase>;
  },
): Promise<{
  result: ShipmentJdSkuMappingGateResult;
  refreshedCase: TReviewCase | null;
}> {
  const result = await dependencies.check(selected.subject_id);
  return {
    result,
    refreshedCase: result.gate_status === 'BLOCKED'
      ? await dependencies.loadReviewCase(selected.id)
      : null,
  };
}

export function jdSkuMappingReviewPermissions(
  status: 'OPEN' | 'RESOLVED' | 'DISMISSED',
  allowedActions: readonly string[],
): { canOpenMapping: boolean; canRerun: boolean } {
  return {
    canOpenMapping: status === 'OPEN' && allowedActions.includes('OPEN_SKU_MAPPING'),
    canRerun: status === 'OPEN' && allowedActions.includes('RERUN_JD_SKU_MAPPING_CHECK'),
  };
}

export function jdSkuMappingRerunResultMessage(result: ShipmentJdSkuMappingGateResult): string {
  return result.gate_status === 'PASSED'
    ? '映射门禁已通过，阻断事项已解决'
    : `仍有 ${result.blocking_issue_count} 个阻断问题，请继续修正 SKU 映射`;
}
