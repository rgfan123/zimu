import type {
  InventoryDetailCapability,
  InventoryDetailContext,
  InventoryDetailObservation,
} from '@/api/types';

export interface InventoryToolLink {
  code: string;
  label: string;
  href: string;
}

const STOCK_TOOL_KINDS: Readonly<Record<string, string>> = {
  JD_BATCH_CHANGES: 'batchChanges',
  JD_LEVEL_CHANGES: 'levelChanges',
  JD_SHELF_LIFE_GOODS: 'shelfLifeGoods',
  JD_SHELF_LIFE_INVENTORY: 'shelfLifeInventory',
  JD_SHOP_STOCK_FLOW: 'shopStockFlow',
};

const SERIAL_TOOL_KINDS: Readonly<Record<string, string>> = {
  JD_SERIAL_CONDITION: 'condition',
  JD_SERIAL_INSIDE: 'inside',
};

function toolHref(code: string, context: InventoryDetailContext): string | null {
  const stockKind = STOCK_TOOL_KINDS[code];
  const serialKind = SERIAL_TOOL_KINDS[code];
  if (!stockKind && !serialKind) return null;

  const params = new URLSearchParams({ kind: stockKind ?? serialKind });
  if (context.provider_sku_code) params.set('goods_no', context.provider_sku_code);
  if (context.warehouse_code) params.set('warehouse_no', context.warehouse_code);
  return `${stockKind ? '/fulfillment/jd-stock' : '/fulfillment/jd-serial'}?${params}`;
}

export function inventoryCapabilityTools(
  capability: InventoryDetailCapability,
  context: InventoryDetailContext,
): InventoryToolLink[] {
  if (capability.integration_status !== 'INTEGRATED') return [];
  return capability.tools.flatMap((tool) => {
    const href = toolHref(tool.code, context);
    return href ? [{ ...tool, href }] : [];
  });
}

export function inventoryDetailModeLabel(observation: InventoryDetailObservation): string {
  return observation.data_mode === 'CACHED_SNAPSHOT' ? '缓存快照' : '尚无观测';
}

export function inventoryCapabilityModeLabel(capability: InventoryDetailCapability): string {
  if (capability.integration_status === 'NOT_INTEGRATED') return '未接入';
  if (capability.integration_status === 'CONTEXT_MISSING') return '上下文未完整';
  if (capability.runtime_mode === 'MOCK') return '模拟接口（不代表真实权限）';
  if (capability.runtime_mode === 'REAL') return '真实模式（就绪与权限进入工具确认）';
  return '运行模式未确认';
}

export function safeInventoryReturnLocation(value: string | null): string {
  if (!value) return '/inventory/overview';
  try {
    const parsed = new URL(value, 'http://inventory.local');
    if (parsed.origin === 'http://inventory.local' && parsed.pathname === '/inventory/overview') {
      return `${parsed.pathname}${parsed.search}`;
    }
  } catch {
    // Invalid or external locations fall back to the trusted overview route.
  }
  return '/inventory/overview';
}
