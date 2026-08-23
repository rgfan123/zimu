/**
 * 侧栏徽标（Issue #104 · ADR 0004）：只显示真实计数，取不到就不显示（ADR 0001 禁伪造）。
 * 未选择岗位时不发任何请求；财务无 responsible_team（D4），不给它挂复核徽标。
 */

import { useEffect, useState } from 'react';

const ROLE_TEAM: Record<string, string> = {
  FULFILLMENT_OPS: 'FULFILLMENT_OPS',
  SKU_OPS: 'SKU_OPS',
  CUSTOMER_OPS: 'CUSTOMER_OPS',
  ORDER_OPS: 'ORDER_OPS',
};

/** 当前岗位团队的 OPEN 复核事项数；size=1 只取 total_elements，不复制跨页拉全量反模式。 */
export function useReviewsBadge(role: string | null): number | null {
  const [count, setCount] = useState<number | null>(null);

  useEffect(() => {
    setCount(null);
    const team = role ? ROLE_TEAM[role] : undefined;
    if (!team) return;

    const params = new URLSearchParams({ status: 'OPEN', responsible_team: team, page: '0', size: '1' });
    const controller = new AbortController();
    fetch(`/api/v1/review-cases?${params.toString()}`, {
      headers: { Accept: 'application/json' },
      signal: controller.signal,
    })
      .then((response) => (response.ok ? response.json() : null))
      .then((body: { total_elements?: unknown } | null) => {
        if (typeof body?.total_elements === 'number') setCount(body.total_elements);
      })
      .catch(() => {
        // 徽标是辅助信息：加载失败保持不显示，不打扰主流程。
      });
    return () => controller.abort();
  }, [role]);

  return count;
}
