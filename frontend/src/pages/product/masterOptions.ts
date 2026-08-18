/**
 * 主数据各页面共用：下拉选项（品类 / 商品 / SKU / 履约方）。
 * 从 openapi MasterData 端点取前 200 条用于表单 Select（表单主数据量级小，够用）。
 */

import { useAsync } from '@/hooks/useAsync';
import { categoriesApi, productsApi, providersApi, skusApi } from '@/api/endpoints';

export function useCategoryOptions() {
  const { data } = useAsync(() => categoriesApi.list({ page: 0, size: 200 }), []);
  return (data?.items ?? []).map((r) => ({ value: r.id, label: `${r.name}（${r.code}）` }));
}

export function useProductOptions() {
  const { data } = useAsync(() => productsApi.list({ page: 0, size: 200 }), []);
  return (data?.items ?? []).map((r) => ({ value: r.id, label: `${r.name}（${r.code}）` }));
}

export function useSkuOptions() {
  const { data } = useAsync(() => skusApi.list({ page: 0, size: 200 }), []);
  return (data?.items ?? []).map((r) => ({ value: r.id, label: `${r.name}（${r.code}）` }));
}

export function useProviderOptions() {
  const { data } = useAsync(() => providersApi.list(), []);
  return (data ?? []).map((r) => ({ value: r.id, label: `${r.provider_name}（${r.provider_code}）` }));
}
