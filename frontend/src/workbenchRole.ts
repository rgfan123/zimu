/**
 * Issue #104：岗位视图（spec #103 D1–D4）。
 * 本票只落地「决定默认落地的工作台」；复核收件箱的默认 responsible_team 预筛由 Issue #106 消费本模块实现。
 * 它不是身份也不是权限：不发请求、不进 URL、不进请求头；存 localStorage（D3），
 * Phase 2 有真身份后该键作为一次性迁移来源读取后即弃。
 */

import { TEAM_LABELS } from '@/pages/workbench/queuePresentation';

export const WORKBENCH_ROLE_STORAGE_KEY = 'zimu.workbench-role';

export interface WorkbenchRoleOption {
  value: string;
  label: string;
  /** 选中该岗位后跳转的默认工作台。 */
  landing: string;
}

/**
 * 四个 responsible_team（D2：权威取值沿用 queuePresentation 的 TEAM_OPTIONS，不新造词汇）
 * +「财务」落地别名（D4：现有团队里没有财务，不新增团队值，只决定默认落地页）。
 */
export const WORKBENCH_ROLE_OPTIONS: readonly WorkbenchRoleOption[] = [
  { value: 'FULFILLMENT_OPS', label: TEAM_LABELS.FULFILLMENT_OPS ?? 'FULFILLMENT_OPS', landing: '/workbench/shipping' },
  // Issue #110 交付 /workbench/procurement 后，采购岗改指采购工作台
  { value: 'SKU_OPS', label: TEAM_LABELS.SKU_OPS ?? 'SKU_OPS', landing: '/procurement/tickets' },
  { value: 'CUSTOMER_OPS', label: TEAM_LABELS.CUSTOMER_OPS ?? 'CUSTOMER_OPS', landing: '/workbench/reviews' },
  { value: 'ORDER_OPS', label: TEAM_LABELS.ORDER_OPS ?? 'ORDER_OPS', landing: '/workbench/reviews' },
  { value: 'FINANCE', label: '财务', landing: '/workbench/recon' },
];

export function readStoredWorkbenchRole(): string | null {
  try {
    return window.localStorage.getItem(WORKBENCH_ROLE_STORAGE_KEY);
  } catch {
    return null;
  }
}

export function storeWorkbenchRole(value: string): void {
  try {
    window.localStorage.setItem(WORKBENCH_ROLE_STORAGE_KEY, value);
  } catch {
    // D3：localStorage 里没有任何业务事实，存不进去只损失一次重选。
  }
}

/** 未知团队值原样显示（D2：responsible_team 是 VARCHAR 非枚举，不丢弃、不崩溃）。 */
export function workbenchRoleLabel(value: string | null): string | null {
  if (!value) return null;
  return WORKBENCH_ROLE_OPTIONS.find((option) => option.value === value)?.label ?? value;
}

export function workbenchRoleLanding(value: string): string | null {
  return WORKBENCH_ROLE_OPTIONS.find((option) => option.value === value)?.landing ?? null;
}
