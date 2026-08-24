/**
 * 订单详情 return_to 的 fail-closed 解析：只接受从 /workbench/recon 生成的站内查询路径，
 * 拒绝协议、反斜杠、双斜杠与任意其它 pathname，避免 open redirect。
 */

const RECON_PATH = '/workbench/recon';
const RECON_QUERY_TYPES = ['OUTBOUND_ORDER_NO', 'JD_DELIVERY_NO', 'ORDER_NO'] as const;

function isReconQueryType(value: string | null): value is (typeof RECON_QUERY_TYPES)[number] {
  return value === 'OUTBOUND_ORDER_NO' || value === 'JD_DELIVERY_NO' || value === 'ORDER_NO';
}

export function safeOrderReturnLocation(raw: string | null | undefined): string | null {
  if (typeof raw !== 'string' || raw.length === 0) return null;
  if (!raw.startsWith('/')) return null;
  if (raw.includes('\\') || raw.includes('//')) return null;

  try {
    const parsed = new URL(raw, 'http://order.local');
    if (parsed.origin !== 'http://order.local') return null;
    if (parsed.username || parsed.password) return null;
    if (parsed.pathname !== RECON_PATH) return null;
    const queryType = parsed.searchParams.get('query_type');
    const queryValue = parsed.searchParams.get('query_value');
    if (!isReconQueryType(queryType)) return null;
    if (typeof queryValue !== 'string' || queryValue.trim() === '') return null;
    const search = new URLSearchParams();
    search.set('query_type', queryType);
    search.set('query_value', queryValue.trim());
    return `${RECON_PATH}?${search.toString()}`;
  } catch {
    return null;
  }
}
