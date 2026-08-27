/**
 * 统一 API 请求客户端。
 * - 所有请求走相对路径，由 Vite dev proxy / Nginx 转发到后端（见 vite.config.ts）。
 * - 遵循 docs/api-contract.md §3 通用约定：snake_case JSON、X-Request-Id 请求标识、
 *   统一错误模型（business_code / message / http_status / trace_id）。
 * - 各端点只传幂等键和普通业务头；操作人身份由受信网关覆盖，浏览器不得提供 X-Operator。
 */

import type { ApiErrorBody } from './types';

/** 查询参数值：空值/undefined 会被忽略；数组会展开为重复参数（如 sort）。 */
export type QueryValue = string | number | boolean | undefined | null | string[];

export interface RequestOptions {
  method?: 'GET' | 'POST' | 'PATCH' | 'PUT' | 'DELETE';
  params?: Record<string, QueryValue>;
  body?: unknown;
  headers?: Record<string, string>;
  signal?: AbortSignal;
}

export class ApiError extends Error {
  readonly status: number;
  readonly body: ApiErrorBody;

  constructor(status: number, body: ApiErrorBody) {
    // Error.message 也可能被第三方组件直接展示，不能保存后端自由文本。
    super('服务暂时不可用，请稍后重试');
    this.name = 'ApiError';
    this.status = status;
    this.body = body;
  }
}

const FILE_ERROR_CODES = [
  'FILE_',
  'TEMPLATE_',
  'MAPPING_REFERENCE_',
  'TRACKING_',
  'JD_EXPORT_TEMPLATE_',
] as const;

function isFileError(code: string): boolean {
  return FILE_ERROR_CODES.some((prefix) => code.startsWith(prefix));
}

export function errorMessage(err: unknown): string {
  if (err instanceof ApiError) {
    const code = err.body.business_code ?? '';
    if (err.status === 401 || err.status === 403) return '当前操作未获授权，请联系管理员确认权限';
    if (err.status === 404) return '未找到所需数据，请核对查询条件后重试';
    if (err.status >= 500) return '服务暂时不可用，请稍后重试；如持续失败请联系管理员';
    if (err.status === 400) return '提交内容有误，请检查必填项和格式后重试';
    if (err.status === 405) return '当前操作方式不受支持，请刷新页面后重试；如持续失败请联系管理员';
    if (err.status === 409 && code === 'VERSION_CONFLICT') return '数据已被其他操作更新，请刷新后重试';
    if (err.status === 409 && code === 'TRACKING_DUPLICATE') return '该运单已被接收，请刷新后核对现有运单事实';
    if (err.status === 409 && code === 'TASK_NOT_PENDING') return '该发货任务已被处理，请刷新后核对最新状态';
    if (err.status === 409 && code === 'DRAFT_NOT_OPEN') return '该草稿已被处理，请刷新后查看只读终态';
    if (err.status === 409 && code === 'IMPORT_BATCH_EXPORT_INCOMPLETE') {
      return '批次中仍有订单未完成复核或尚未形成履约行，请先前往复核工作台处理';
    }
    if (err.status === 409) return '操作与当前数据状态冲突，请刷新并核对后重试';
    if (err.status === 413) return '文件过大，请选择较小的文件后重试';
    if (err.status === 422 && code === 'JD_TRACKING_TEMPLATE_GATE') {
      return '当前无法接收京东履约回传，请联系管理员补充官方模板后再试';
    }
    if (err.status === 422 && code === 'TASK_REQUIRED') return '请先选择待回传的第三方发货任务';
    if (err.status === 422 && code === 'TASK_INVALID') return '所选任务已不在待回传范围，请刷新后重新核对';
    if (err.status === 422 && code === 'TASK_SHIPMENT_AMBIGUOUS') return '该任务有多个待回传发货批次，请先消除歧义';
    if (err.status === 422 && code === 'CARRIER_REQUIRED') return '请先选择已启用的标准物流公司';
    if (err.status === 422 && code === 'CARRIER_INVALID') return '所选物流公司已失效，请刷新后重新核对';
    if (err.status === 422 && code === 'SHIPMENT_JUDGMENT_INVALID') return '发货判断无法识别，请人工核对原始回传';
    if (err.status === 422 && isFileError(code)) {
      return '文件内容或格式不符合要求，请使用正确模板，核对内容后重新上传';
    }
    if (err.status === 422) return '提交内容不符合业务要求，请核对填写内容后重试';
    if (err.status === 429) return '操作过于频繁，请稍候再试';
    return '操作未完成，请核对当前内容后重试；如持续失败请联系管理员';
  }
  if (err instanceof Error && err.name === 'AbortError') return '操作已取消';
  return '网络连接失败，请检查网络后重试';
}

function buildUrl(path: string, params?: Record<string, QueryValue>): string {
  const url = new URL(path, window.location.origin);
  if (params) {
    for (const [key, value] of Object.entries(params)) {
      if (value === undefined || value === null || value === '') continue;
      if (Array.isArray(value)) {
        for (const item of value) {
          if (item !== undefined && item !== null && item !== '') url.searchParams.append(key, String(item));
        }
      } else {
        url.searchParams.append(key, String(value));
      }
    }
  }
  return url.pathname + url.search;
}

export async function apiRequest<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = 'GET', params, body, headers, signal } = options;

  const requestHeaders: Record<string, string> = {
    Accept: 'application/json',
    'X-Request-Id': crypto.randomUUID(),
    ...headers,
  };
  // FormData 由浏览器生成 multipart 边界，不得设置 JSON Content-Type。
  if (body !== undefined && !(body instanceof FormData)) requestHeaders['Content-Type'] = 'application/json';

  const doFetch = () =>
    fetch(buildUrl(path, params), {
      method,
      headers: requestHeaders,
      body: body === undefined ? undefined : body instanceof FormData ? body : JSON.stringify(body),
      signal,
    });

  // 网络级失败（fetch 抛 TypeError，请求根本没到网关）对幂等 GET 自动重试两次：
  // 生产网关经 Docker Desktop 端口转发，工作台一屏并发二十余请求时偶发个别连接被掐，
  // 服务端日志全 200 而浏览器报「网络连接失败」——重试一次即愈，用户不该替 NAT 抖动买单。
  let res: Response;
  if (method === 'GET') {
    let lastError: unknown;
    res = await (async () => {
      for (const delay of [0, 300, 800]) {
        if (delay > 0) await new Promise((resolve) => setTimeout(resolve, delay));
        try {
          return await doFetch();
        } catch (error) {
          if (signal?.aborted) throw error;
          lastError = error;
        }
      }
      throw lastError;
    })();
  } else {
    res = await doFetch();
  }

  if (!res.ok) {
    let errorBody: ApiErrorBody = { message: '', http_status: res.status };
    try {
      const parsed = (await res.json()) as ApiErrorBody;
      if (parsed && typeof parsed === 'object') errorBody = parsed;
    } catch {
      // 非 JSON 错误体，保留默认信息
    }
    throw new ApiError(res.status, errorBody);
  }

  if (res.status === 204) return undefined as T;
  return (await res.json()) as T;
}
