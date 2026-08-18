/**
 * 订单分页列表 hook：统一承载筛选条件 / 分页状态 / 请求。
 * 四个订单列表页（全部/待处理/异常/追踪）共用。
 */

import { useCallback, useEffect, useState } from 'react';
import { ordersApi, type OrderListQuery } from '@/api/endpoints';
import type { OrderPage } from '@/api/types';

export interface PagedOrdersState {
  data: OrderPage | null;
  loading: boolean;
  error: Error | null;
  page: number;
  size: number;
  setPage: (p: number) => void;
  setSize: (s: number) => void;
  /** 更新筛选条件（自动回到第一页） */
  applyFilters: (patch: Partial<OrderListQuery>) => void;
  reload: () => void;
}

const DEFAULT_SORT = ['created_at,desc'];

export function usePagedOrders(initialFilters: Partial<OrderListQuery> = {}): PagedOrdersState {
  const [filters, setFilters] = useState<Partial<OrderListQuery>>(initialFilters);
  const [page, setPageState] = useState(0);
  const [size, setSizeState] = useState(20);
  const [data, setData] = useState<OrderPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<Error | null>(null);
  const [tick, setTick] = useState(0);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    ordersApi
      .list({ ...filters, page, size, sort: DEFAULT_SORT })
      .then((res) => {
        if (!cancelled) {
          setData(res);
          setLoading(false);
        }
      })
      .catch((e: Error) => {
        if (!cancelled) {
          setError(e);
          setLoading(false);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [filters, page, size, tick]);

  const applyFilters = useCallback((patch: Partial<OrderListQuery>) => {
    setFilters((prev) => ({ ...prev, ...patch }));
    setPageState(0);
  }, []);

  const setPage = useCallback((p: number) => setPageState(p), []);
  const setSize = useCallback((s: number) => {
    setSizeState(s);
    setPageState(0);
  }, []);
  const reload = useCallback(() => setTick((t) => t + 1), []);

  return { data, loading, error, page, size, setPage, setSize, applyFilters, reload };
}
