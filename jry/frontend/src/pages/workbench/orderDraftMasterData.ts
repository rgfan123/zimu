import type { MasterDataPage, MasterDataRecord } from '../../api/types';

export const ORDER_DRAFT_MASTER_DATA_PAGE_SIZE = 50;

export interface MasterDataOptionQuery {
  page: number;
  size: number;
  query?: string;
}

export type MasterDataOptionLoader = (
  query: MasterDataOptionQuery,
) => Promise<MasterDataPage>;

export interface MasterDataOptionState {
  items: MasterDataRecord[];
  page: number;
  totalElements: number;
  totalPages: number;
  query: string;
}

export function emptyMasterDataOptionState(query = ''): MasterDataOptionState {
  return {
    items: [],
    page: -1,
    totalElements: 0,
    totalPages: 1,
    query: query.trim(),
  };
}

/**
 * 通过已有主数据列表契约加载一页选项。Customer 可传 query 走服务端
 * 搜索；SKU 不支持搜索参数，因此只传分页并逐页累加。
 */
export async function loadMasterDataOptionPage(
  loader: MasterDataOptionLoader,
  current: MasterDataOptionState,
  options: { query?: string; reset?: boolean } = {},
): Promise<MasterDataOptionState> {
  const query = options.query?.trim() ?? current.query;
  const reset = Boolean(options.reset) || query !== current.query;
  const page = reset ? 0 : current.page + 1;
  if (!reset && current.page >= 0 && page >= current.totalPages) return current;

  const request: MasterDataOptionQuery = {
    page,
    size: ORDER_DRAFT_MASTER_DATA_PAGE_SIZE,
  };
  if (query) request.query = query;
  const result = await loader(request);

  const records = new Map<string, MasterDataRecord>();
  if (!reset) {
    for (const item of current.items) records.set(item.id, item);
  }
  for (const item of result.items) {
    if (item.active) records.set(item.id, item);
  }
  return {
    items: [...records.values()],
    page: result.page,
    totalElements: result.total_elements,
    totalPages: result.total_pages,
    query,
  };
}

export function hasMoreMasterDataOptions(state: MasterDataOptionState): boolean {
  return state.page + 1 < state.totalPages;
}
