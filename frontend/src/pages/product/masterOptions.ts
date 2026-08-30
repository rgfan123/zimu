/**
 * 主数据各页面共用：下拉选项（品类 / 商品 / SKU / 履约方 / 礼包）。
 * 从 openapi MasterData 端点取前 200 条用于表单 Select（表单主数据量级小，够用）。
 */

import { useAsync } from '@/hooks/useAsync';
import { categoriesApi, productBundlesApi, productsApi, providersApi, skusApi } from '@/api/endpoints';
import { activeBundleOptions, bundleLabelById } from './bundleMappings';

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

/**
 * 礼包下拉选项 + 礼包 id → 名称查表（来源礼包映射页签用，一次取数两处用）。
 * 只有 ACTIVE 礼包能被绑定，但查表覆盖全部礼包——历史映射可能指向已停用礼包，列表要照实显示。
 */
export function useBundleDirectory() {
  const { data } = useAsync(() => productBundlesApi.list({ page: 0, size: 200 }), []);
  const items = data?.items ?? [];
  return { options: activeBundleOptions(items), labelById: bundleLabelById(items) };
}

export function useProviderOptions() {
  const { data } = useAsync(() => providersApi.list(), []);
  return (data ?? []).map((r) => ({ value: r.id, label: `${r.provider_name}（${r.provider_code}）` }));
}
