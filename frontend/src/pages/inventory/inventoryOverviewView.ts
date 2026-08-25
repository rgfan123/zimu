import type {
  InventoryOverviewItem,
  InventoryOverviewResponse,
} from '@/api/types';
import { formatDateTime } from '../../format/dateTime.ts';

export type { InventoryOverviewItem, InventoryOverviewResponse } from '@/api/types';

export interface InventoryObservationPresentation {
  label: string;
  tone: 'neutral' | 'info' | 'success' | 'warning' | 'error';
}

export function inventoryTimeLabel(value: string | null): string {
  return formatDateTime(value);
}

function normalizedDecimal(value: string): string {
  if (!/^\d+(?:\.\d+)?$/.test(value)) return value;
  const [integer, fraction = ''] = value.split('.');
  const significantFraction = fraction.replace(/0+$/, '');
  return significantFraction ? `${integer}.${significantFraction}` : integer;
}

export function inventoryQuantityLabel(value: string | null, unit: string): string {
  return value === null ? '—' : `${normalizedDecimal(value)} ${unit}`;
}

export function inventorySourceLabel(sourceType: string | null): string {
  if (sourceType === null) return '—';
  if (sourceType === 'JD_ISC_QUERY_STOCK') return '京东实时库存';
  if (sourceType === 'NORMALIZED_PROVIDER_SNAPSHOT') return '标准库存快照';
  if (sourceType === 'UNKNOWN') return '历史来源待确认';
  return '未识别来源';
}

export function inventoryQuantityUnit(
  item: Pick<InventoryOverviewItem, 'quantity_unit' | 'unit'>,
): string {
  if (item.quantity_unit === 'JD_PIECE') return '件（京东）';
  if (item.quantity_unit === 'INTERNAL_UNIT' || item.quantity_unit === null) return item.unit;
  return '单位待确认';
}

export function inventoryObservationPresentation(
  value: Pick<InventoryOverviewItem, 'observation_status' | 'freshness_status'>,
): InventoryObservationPresentation {
  if (value.observation_status === 'NOT_OBSERVED') {
    return { label: '尚未观测', tone: 'warning' };
  }
  if (value.freshness_status === 'STALE') {
    return { label: '数据已过期', tone: 'error' };
  }
  if (value.freshness_status === 'CURRENT') {
    return { label: '时效正常', tone: 'success' };
  }
  return { label: '已观测', tone: 'info' };
}

export function inventoryOverviewWarnings(response: InventoryOverviewResponse): string[] {
  const warnings: string[] = [];
  const { coverage } = response;
  if (coverage.partial) {
    warnings.push(
      `当前观测覆盖 ${coverage.observed_provider_count}/${coverage.provider_count} 个履约方、`
        + `${coverage.observed_sku_count}/${coverage.sku_count} 个 SKU，未观测范围不计为零库存。`,
    );
  }
  if (coverage.stale_count > 0) {
    const oldest = inventoryTimeLabel(coverage.oldest_observed_at);
    warnings.push(
      `当前筛选范围有 ${coverage.stale_count} 条库存观测超过时效策略 `
        + `${coverage.freshness_policy}`
        + `${oldest === '—' ? '' : `；最早观测 ${oldest}`}，请重新查询后再用于履约判断。`,
    );
  }
  return warnings;
}
