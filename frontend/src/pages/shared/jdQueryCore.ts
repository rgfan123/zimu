/**
 * JdQueryPage 配置驱动骨架的纯逻辑核心（issue #40「京东只读查询页骨架收敛」）。
 *
 * 与组件 JSX（JdQueryPage.tsx）分离，原因有二：
 * 1. 参数构建 / 白名单收集 / 权限判定是可单测的纯函数，node:test 只能加载纯 TS
 *    （--experimental-strip-types 不处理 .tsx），因此核心逻辑放在 .ts 模块；
 * 2. 页面配置（JdQueryField / JdQueryOption / JdQueryPageConfig）与组件实现解耦。
 *
 * 注意：本模块不得引入 @/ 别名（node:test 无别名解析），仅使用相对路径 + .ts 后缀。
 */

import type { QueryValue } from '../../api/client.ts';
import type { JdQueryResult } from '../../api/types.ts';
import type { ReactNode } from 'react';

// ---------- 配置类型 ----------

export type JdFieldKind = 'text' | 'number' | 'list' | 'flag';

/** 参数字段定义：text 输入框 / number 整数输入 / list 逗号分隔列表 / flag 选项下拉（不参与提交）。 */
export interface JdQueryField {
  name: string;
  label: string;
  kind?: JdFieldKind;
  placeholder?: string;
  required?: boolean;
  /** 必填校验文案；默认「请填写{label}」。 */
  requiredMessage?: string;
  /** flag 字段选项（如 返回/不返回）。 */
  options?: { value: string | number; label: string }[];
  /** Form.Item tooltip。 */
  tip?: string;
  /** 控件宽度（像素或百分比）。 */
  width?: number | '100%';
  /** InputNumber 最小值；默认 1。 */
  min?: number;
}

export interface JdResultRow {
  label: string;
  value: string;
}

/** 单个查询配置：查询名、参数字段、执行函数、结果白名单、结果视图。 */
export interface JdQueryOption {
  key: string;
  label: string;
  description?: string;
  /** 接口路径；未提供 run 时默认 apiRequest(path, { params: 已构建参数 })。 */
  path?: string;
  fields: JdQueryField[];
  /** 查询执行函数（接收已构建的参数）。 */
  run?: (values: Record<string, unknown>) => Promise<JdQueryResult>;
  /** 结果白名单：normalized 字段名（去符号小写）→ 展示名。 */
  whitelist: Record<string, string>;
  /** 结果视图：'panel'（默认，Descriptions）| 'table'（数组结果表格，退货列表）。 */
  view?: 'panel' | 'table';
  /** 表格单元格自定义渲染（如系统退货入库单号点击直达详情）。 */
  renderCell?: (key: string, value: unknown) => ReactNode;
}

export interface JdStatusData {
  client_mode?: 'MOCK' | 'REAL';
  live_ready?: boolean;
}

export interface JdStatusCtx {
  data: JdStatusData | null;
  loading: boolean;
  error: Error | null;
  reload: () => void;
}

/** SDK 连接状态插槽：头部 Tag、PageState 加载/错误态、未就绪警告、按钮禁用。 */
export interface JdStatusSlot {
  load: () => Promise<JdStatusData | undefined>;
  /** 头部连接状态 Tag（data 为 null 时返回 null，loading/error 由 PageState 承载）。 */
  renderTag: (data: JdStatusData | null) => ReactNode;
  /** 是否用 PageState 渲染加载/错误态；false 只渲染头部 Tag（如退货页）。 */
  usePageState?: boolean;
  /** PageState 错误标题。 */
  errorTitle?: string;
  /** 查询按钮在状态数据就绪前禁用（如基础信息页）。 */
  disableQueryUntilReady?: boolean;
  /** 状态就绪后的附加警告（如 真实连接尚未就绪）。 */
  warning?: (data: JdStatusData) => ReactNode | null;
}

/** 白名单收集配置：各页面原收集器的行为开关。 */
export interface JdCollectConfig {
  /** 最大行数；默认 40（序列号页 24，退货页不设限）。 */
  maxRows?: number;
  /** 按 label|value 去重（序列号页）。 */
  dedupe?: boolean;
  /** 标量数组合并分隔符：null 不合并（基础信息/退货），'、'（库存/单据），', '（序列号）。 */
  arrayJoin?: string | null;
  /** 嵌套对象以「label · 」前缀展示（退货页）。 */
  prefixNested?: boolean;
  /** 只递归白名单内的对象键（退货页）；false 时未收录对象也递归（其余页）。 */
  skipUnlisted?: boolean;
  /** 数组元素按「index+1」前缀（退货页）。 */
  indexArrays?: boolean;
  /** 白名单键为 null/undefined 时也展示（退货页 String(null) 口径）。 */
  includeNull?: boolean;
}

export interface JdSuccessCtx {
  label: string;
  result: JdQueryResult;
  mock: boolean;
  mode?: string;
}

/** 结果区展示配置：成功容器、权限未开通提示、失败提示。 */
export interface JdResultConfig {
  /** 成功容器：'alert'（默认）| 'card'（退货页）。 */
  container?: 'alert' | 'card';
  /** 成功标题（Alert message / Card title）。 */
  successTitle?: (ctx: JdSuccessCtx) => ReactNode;
  /** Card 右上角（退货页 requestId）。 */
  cardExtra?: (result: JdQueryResult) => ReactNode | null;
  /** requestId 展示：'top'（基础信息）/ 'bottom'（库存、单据，含业务码）/ 'card-extra'（退货）/ 'none'（序列号）。 */
  requestId?: 'top' | 'bottom' | 'card-extra' | 'none';
  /** 空结果文案。 */
  emptyText?: string;
  /** Descriptions 是否 bordered（退货页）。 */
  bordered?: boolean;
  /** 表格分页 pageSize；默认 10。 */
  tablePageSize?: number;
  denied?: {
    title?: ReactNode;
    description?: (result: JdQueryResult) => ReactNode;
  };
  failed?: {
    title?: (label: string, result: JdQueryResult) => ReactNode;
    description?: (result: JdQueryResult) => ReactNode;
    alertType?: 'warning' | 'error';
  };
}

/** message 反馈配置。 */
export interface JdFeedbackConfig {
  /** 是否提示成功/权限/失败 message；默认 true（退货页 false，只保留 catch 错误提示）。 */
  enabled?: boolean;
  success?: (label: string, result: JdQueryResult) => string;
  denied?: (label: string, result: JdQueryResult) => string;
  failed?: (label: string, result: JdQueryResult) => string;
  /** catch 时是否清空结果；默认 true（序列号页 false：保留上一次结果）。 */
  clearResultOnError?: boolean;
}

/** JdQueryPage 页面级配置。 */
export interface JdQueryPageConfig {
  title: string;
  subtitle: string;
  icon: ReactNode;
  options: JdQueryOption[];
  /** 头部右侧静态标签（无状态页：序列号 / 单据页）。 */
  headerTags?: ReactNode;
  /** 表单布局：'inline'（默认）| 'vertical'。 */
  formLayout?: 'inline' | 'vertical';
  /** 查询栏形态：'compact'（库存/单据：Select+查询按钮相连）| 'inline'（基础信息：Select 与表单同行）
   *  | 'row'（序列号：Select 一行、表单一行）| 'form'（退货：Select 作为表单首项）。 */
  queryBar?: 'compact' | 'inline' | 'row' | 'form';
  /** form 形态下 Select 的前置标签（退货页「接口」）。 */
  selectLabel?: string;
  /** Select 宽度；默认 180。 */
  selectWidth?: number;
  /** 切换查询时是否重挂表单（库存/单据/序列号）；false 时手动 resetFields（基础信息/退货）。 */
  remountForm?: boolean;
  /** 每查询默认值（序列号页 page_size/current_page）。 */
  defaults?: Record<string, Record<string, unknown>>;
  /** URL 预填（库存/序列号页 kind + 字段值）。 */
  prefill?: (params: URLSearchParams) => { kind: string; values: Record<string, unknown> };
  /** SDK 连接状态（基础信息/库存/退货页）。 */
  status?: JdStatusSlot;
  /** 参数构建；默认 buildJdParams（按字段 kind 归一化）。 */
  buildParams?: (fields: JdQueryField[], values: Record<string, unknown>) => Record<string, QueryValue>;
  /** 权限未开通判定；默认 2001 或消息含权限字样（序列号/单据页），基础信息/库存/退货页为严格 2001。 */
  isPermissionDenied?: (result: JdQueryResult) => boolean;
  /** mock 判定；默认 business_code === 'MOCK_SUCCESS'（序列号/单据页），基础信息/库存页为 client_mode === 'MOCK'。 */
  mock?: (ctx: { result: JdQueryResult; mode?: string }) => boolean;
  collect?: JdCollectConfig;
  result?: JdResultConfig;
  feedback?: JdFeedbackConfig;
  /** 表单下方附加说明（退货页）。 */
  hint?: ReactNode;
  /** 主卡片下方附加区（库存页提示+刷新按钮 / 单据页提示）。 */
  footer?: ReactNode | ((ctx: { status: JdStatusCtx }) => ReactNode);
}

// ---------- 纯函数 ----------

/** 字段名归一化：去符号 + 小写（与各原页 whitelist 口径一致）。 */
export function normalizeKey(key: string): string {
  return key.replace(/[^A-Za-z0-9]/g, '').toLowerCase();
}

/** 标量值字符串化：字符串去首尾空白；数字/布尔直接转；标量数组按 arrayJoin 合并；对象返回 null。 */
export function scalarString(raw: unknown, arrayJoin: string | null): string | null {
  if (raw === null || raw === undefined) return null;
  if (typeof raw === 'string') {
    const trimmed = raw.trim();
    return trimmed || null;
  }
  if (typeof raw === 'number' || typeof raw === 'boolean') return String(raw);
  if (Array.isArray(raw)) {
    if (arrayJoin === null) return null;
    const items = raw.map((item) => scalarString(item, arrayJoin));
    return items.length && items.every((item) => item !== null) ? (items as string[]).join(arrayJoin) : null;
  }
  return null;
}

/**
 * 白名单结果收集：递归遍历结果对象，只透出白名单字段（normalized → 展示名）。
 * 数组逐条展开；标量数组按 arrayJoin 合并；嵌套对象按 prefixNested 带「label · 」前缀；
 * skipUnlisted 时未收录键不递归（退货页）；dedupe 时按 label|value 去重（序列号页）。
 */
export function collectWhitelisted(
  value: unknown,
  whitelist: Record<string, string>,
  rows: JdResultRow[],
  cfg: JdCollectConfig = {},
  prefix = '',
  seen: Set<string> = new Set(),
): void {
  const maxRows = cfg.maxRows ?? 40;
  if (rows.length >= maxRows || value === null || value === undefined) return;
  if (Array.isArray(value)) {
    for (let index = 0; index < value.length; index += 1) {
      if (rows.length >= maxRows) return;
      const item = value[index];
      if (item !== null && typeof item === 'object') {
        collectWhitelisted(item, whitelist, rows, cfg, cfg.indexArrays ? `${prefix}${index + 1}` : prefix, seen);
      }
    }
    return;
  }
  if (typeof value !== 'object') return;
  for (const [key, raw] of Object.entries(value as Record<string, unknown>)) {
    if (rows.length >= maxRows) return;
    const label = whitelist[normalizeKey(key)];
    if (label === undefined) {
      if (typeof raw === 'object' && !cfg.skipUnlisted) {
        collectWhitelisted(raw, whitelist, rows, cfg, prefix, seen);
      }
      continue;
    }
    const scalar = scalarString(raw, cfg.arrayJoin ?? null);
    if (scalar !== null) {
      pushRow(rows, prefix, label, scalar, cfg, seen);
    } else if (cfg.includeNull && (raw === null || raw === undefined)) {
      pushRow(rows, prefix, label, String(raw), cfg, seen);
    } else if (typeof raw === 'object') {
      collectWhitelisted(raw, whitelist, rows, cfg, cfg.prefixNested ? `${prefix}${label} · ` : prefix, seen);
    }
  }
}

function pushRow(
  rows: JdResultRow[],
  prefix: string,
  label: string,
  value: string,
  cfg: JdCollectConfig,
  seen: Set<string>,
): void {
  if (cfg.dedupe) {
    const key = `${label}|${value}`;
    if (seen.has(key)) return;
    seen.add(key);
  }
  rows.push({ label: `${prefix}${label}`, value });
}

/**
 * 参数构建：按字段 kind 归一化表单值。
 * - 'list'：按 /[,，]/ 拆分为字符串数组；
 * - 'number'：取整后转字符串（InputNumber 回填 number 统一处理）；
 * - 'flag'：不参与提交（原页「返回明细」等选项仅展示）；
 * - 'text'：直接字符串化。
 * 空值（undefined/null/空白字符串）一律跳过（apiRequest 也会忽略）。
 */
export function buildJdParams(fields: JdQueryField[], values: Record<string, unknown>): Record<string, QueryValue> {
  const params: Record<string, QueryValue> = {};
  for (const field of fields) {
    if (field.kind === 'flag') continue;
    const raw = values[field.name];
    if (raw === undefined || raw === null) continue;
    if (typeof raw === 'string' && raw.trim() === '') continue;
    if (field.kind === 'number') {
      const parsed = Number(raw);
      if (Number.isFinite(parsed)) params[field.name] = String(Math.trunc(parsed));
    } else if (field.kind === 'list') {
      const items = String(raw)
        .split(/[,，]/)
        .map((item) => item.trim())
        .filter(Boolean);
      if (items.length) params[field.name] = items;
    } else {
      params[field.name] = String(raw);
    }
  }
  return params;
}

/** 权限未开通判定：业务码 2001，或业务消息含权限/authorization 字样（序列号/单据页口径）。 */
export function isJdPermissionDenied(result: JdQueryResult): boolean {
  if (result.business_code === '2001') return true;
  return /权限|permission|authoriz/i.test(result.message ?? '');
}

/** 权限未开通判定（严格）：仅业务码 2001（基础信息/库存/退货页口径）。 */
export function isJdBusinessCodeDenied(result: JdQueryResult): boolean {
  return result.business_code === '2001';
}
