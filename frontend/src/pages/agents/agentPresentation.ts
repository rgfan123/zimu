/**
 * Agent 中心展示层纯函数 —— 状态/结果/模式映射、URL query 构造与解析、格式化。
 *
 * 三条易错点的落实位置：
 *  1. `state`（AgentListState）由服务端投影，本模块只做「三值 → 文案/色调」的直映，
 *     不接受 enabled，绝不合成第二套状态口径；
 *  2. 模型元数据三态（EXPOSED/NOT_PUBLIC/NOT_CONFIGURED）各配独立文案，不折叠；
 *  3. run_mode 默认 LIVE：URL 构造只在 PREVIEW 时写 `run_mode=PREVIEW`，解析时
 *     缺省/显式 LIVE 都归一为 LIVE；PREVIEW 有独立文案与视觉标识。
 */

import type { Dayjs } from 'dayjs';
import type {
  AgentEvalCaseItem,
  AgentListItem,
  AgentToolItem,
  AgentListState,
  AgentStatus,
  ModelVisibility,
  RunMode,
  RunOutcome,
  RunStatus,
  RunToolCallItem,
} from '@/api/agentTypes';

// ---------- 1. Agent 列表 state：三值直映，不推导 ----------

export interface StatePresentation {
  label: string;
  /** antd Tag 的 color 语义：success / default / warning */
  tone: 'success' | 'default' | 'warning';
}

/** RUNNING→运行中（绿）；DISABLED→已停用（灰）；NO_ACTIVE_VERSION→无生效版本（橙）。 */
export function statePresentation(state: AgentListState): StatePresentation {
  switch (state) {
    case 'RUNNING':
      return { label: '运行中', tone: 'success' };
    case 'DISABLED':
      return { label: '已停用', tone: 'default' };
    case 'NO_ACTIVE_VERSION':
      return { label: '无生效版本', tone: 'warning' };
    default:
      // 契约外值：原样回显服务端，绝不依据 enabled 推导。
      return { label: String(state), tone: 'default' };
  }
}

// ---------- 2. 模型元数据三态：不得折叠 ----------

export interface ModelVisibilityPresentation {
  label: string;
  note: string;
  tone: 'success' | 'default' | 'warning';
}

/**
 * EXPOSED=命中 allowlist 展示真实值；NOT_PUBLIC=三元组存在但未登记 allowlist；
 * NOT_CONFIGURED=本来就没配模型。三者文案必须不同。
 */
export function modelVisibilityPresentation(visibility: ModelVisibility): ModelVisibilityPresentation {
  switch (visibility) {
    case 'EXPOSED':
      return { label: '已公开', note: '模型信息命中服务端 allowlist', tone: 'success' };
    case 'NOT_PUBLIC':
      return { label: '未公开', note: '模型已配置但未登记 allowlist，不对外公开', tone: 'warning' };
    case 'NOT_CONFIGURED':
      return { label: '未配置', note: '该运行未携带任何模型元数据', tone: 'default' };
    default:
      return { label: String(visibility), note: '未知可见性', tone: 'default' };
  }
}

// ---------- 3. 运行记录：LIVE/PREVIEW 隔离 + 状态/结果 ----------

/** input_digest 的隐私说明：SHA-256 摘要无原文，必须显式说明而非渲染成空白。 */
export const INPUT_DIGEST_EXPLANATION = '输入原文不留存，仅存摘要用于比对';

export function digestLabel(inputDigest: string | null | undefined): string {
  return inputDigest ?? '—';
}

export interface RunModePresentation {
  label: string;
  note: string;
  tone: 'default' | 'warning';
}

/** LIVE=线上运行；PREVIEW=草稿试跑，视觉必须区分。 */
export function runModePresentation(runMode: RunMode): RunModePresentation {
  if (runMode === 'PREVIEW') {
    return { label: 'PREVIEW', note: '草稿试跑，不计入线上行为判断', tone: 'warning' };
  }
  return { label: 'LIVE', note: '线上运行', tone: 'default' };
}

export function runStatusPresentation(status: RunStatus): { label: string; tone: 'success' | 'processing' | 'error' } {
  switch (status) {
    case 'RUNNING':
      return { label: '运行中', tone: 'processing' };
    case 'SUCCESS':
      return { label: '成功', tone: 'success' };
    case 'FAILED':
      return { label: '失败', tone: 'error' };
    default:
      return { label: String(status), tone: 'processing' };
  }
}

export interface OutcomePresentation {
  label: string;
  tone: 'success' | 'processing' | 'warning' | 'error';
}

/** outcome=null（RUNNING 中）显示「进行中」，不得渲染成空白。 */
export function runOutcomePresentation(outcome: RunOutcome | null): OutcomePresentation {
  if (outcome === null) return { label: '进行中', tone: 'processing' };
  switch (outcome) {
    case 'SUCCESS':
      return { label: '成功', tone: 'success' };
    case 'NEEDS_INPUT':
      return { label: '需澄清', tone: 'warning' };
    case 'REJECTED':
      return { label: '被拒绝', tone: 'warning' };
    case 'FAILED':
      return { label: '失败', tone: 'error' };
    default:
      return { label: String(outcome), tone: 'processing' };
  }
}

export const OUTCOME_OPTIONS: Array<{ value: RunOutcome; label: string }> = [
  { value: 'SUCCESS', label: '成功' },
  { value: 'NEEDS_INPUT', label: '需澄清' },
  { value: 'REJECTED', label: '被拒绝' },
  { value: 'FAILED', label: '失败' },
];

// ---------- 3b. 运行记录 URL query（筛选与分页全部进 query string） ----------

export interface RunsFilters {
  slug?: string;
  outcome?: RunOutcome;
  /** undefined = LIVE（后端默认即 LIVE，不传 run_mode 即可） */
  runMode?: RunMode;
  businessEntityType?: string;
  businessEntityId?: string;
  startedFrom?: string;
  startedTo?: string;
}

export interface RunsPage {
  limit: number;
  offset: number;
}

export const RUNS_DEFAULT_LIMIT = 20;

function isRunMode(value: string | null): value is RunMode {
  return value === 'LIVE' || value === 'PREVIEW';
}

function isRunOutcome(value: string | null): value is RunOutcome {
  return value === 'SUCCESS' || value === 'NEEDS_INPUT' || value === 'REJECTED' || value === 'FAILED';
}

/** 从 URL 解析筛选；缺省或显式 LIVE 都归一为 undefined（= LIVE）。 */
export function runsFiltersFromParams(params: URLSearchParams): RunsFilters {
  const runMode = params.get('run_mode');
  const outcome = params.get('outcome');
  return {
    slug: params.get('slug')?.trim() || undefined,
    outcome: isRunOutcome(outcome) ? outcome : undefined,
    runMode: isRunMode(runMode) && runMode === 'PREVIEW' ? 'PREVIEW' : undefined,
    businessEntityType: params.get('business_entity_type')?.trim() || undefined,
    businessEntityId: params.get('business_entity_id')?.trim() || undefined,
    startedFrom: params.get('started_from')?.trim() || undefined,
    startedTo: params.get('started_to')?.trim() || undefined,
  };
}

/** 构造可分享/可刷新的 /agents/runs URL；LIVE 不写 run_mode（默认值即 LIVE）。 */
export function runsSearchParams(filters: RunsFilters, page: RunsPage): URLSearchParams {
  const params = new URLSearchParams();
  if (filters.slug) params.set('slug', filters.slug);
  if (filters.outcome) params.set('outcome', filters.outcome);
  if (filters.runMode === 'PREVIEW') params.set('run_mode', 'PREVIEW');
  if (filters.businessEntityType) params.set('business_entity_type', filters.businessEntityType);
  if (filters.businessEntityId) params.set('business_entity_id', filters.businessEntityId);
  if (filters.startedFrom) params.set('started_from', filters.startedFrom);
  if (filters.startedTo) params.set('started_to', filters.startedTo);
  if (page.limit !== RUNS_DEFAULT_LIMIT) params.set('limit', String(page.limit));
  if (page.offset > 0) params.set('offset', String(page.offset));
  return params;
}

export function runsLocation(filters: RunsFilters, page: RunsPage): string {
  const query = runsSearchParams(filters, page).toString();
  return query ? `/agents/runs?${query}` : '/agents/runs';
}

/** RangePicker 值 → started_from/started_to（ISO-8601 带时区，后端 OffsetDateTime 要求）。 */
export function rangeToStartedParams(
  range: [Dayjs | null, Dayjs | null] | null,
): { startedFrom?: string; startedTo?: string } {
  if (!range || !range[0] || !range[1]) return {};
  return {
    startedFrom: range[0].format('YYYY-MM-DDTHH:mm:ssZ'),
    startedTo: range[1].format('YYYY-MM-DDTHH:mm:ssZ'),
  };
}

// ---------- 4. 近 7 日统计 ----------

export interface SevenDayPresentation {
  /** 无运行（count=0 且 failure=0）时为 null */
  total: string | null;
  failure: string | null;
}

/** 两者皆 0 时显示「无运行」；失败数 >0 时着色由调用方按 failure 非空处理。 */
export function sevenDayPresentation(count: number, failure: number): SevenDayPresentation {
  if (count <= 0 && failure <= 0) return { total: null, failure: null };
  return {
    total: `${count} 次运行`,
    failure: failure > 0 ? `${failure} 次失败` : null,
  };
}

// ---------- 5. 工具调用序列 / 评测分组 ----------

/** 工具调用按 sequence_no 升序（稳定排序，副本返回）。 */
export function sortToolCalls(calls: RunToolCallItem[]): RunToolCallItem[] {
  return [...calls].sort((a, b) => a.sequence_no - b.sequence_no);
}

export interface EvalCaseGroups {
  invariant: AgentEvalCaseItem[];
  quality: AgentEvalCaseItem[];
}

/** INVARIANT（确定性门禁）与 QUALITY（质量评测）分两组，混在一起会误读基线。 */
export function evalCaseGroups(cases: AgentEvalCaseItem[]): EvalCaseGroups {
  const invariant: AgentEvalCaseItem[] = [];
  const quality: AgentEvalCaseItem[] = [];
  for (const item of cases) {
    if (item.metric_kind === 'QUALITY') quality.push(item);
    else invariant.push(item);
  }
  return { invariant, quality };
}

// ---------- 6. 格式化 ----------

export function formatJson(value: unknown): string {
  if (value === undefined || value === null) return '—';
  try {
    return JSON.stringify(value, null, 2);
  } catch {
    return String(value);
  }
}

export function formatCompactJson(value: unknown): string {
  if (value === undefined || value === null) return '—';
  try {
    return JSON.stringify(value);
  } catch {
    return String(value);
  }
}

export function formatLatency(ms: number | null | undefined): string {
  if (ms === null || ms === undefined) return '—';
  if (ms < 1000) return `${ms} ms`;
  return `${(ms / 1000).toFixed(2)} s`;
}

export function formatTime(iso: string | null | undefined): string {
  if (!iso) return '—';
  const parsed = new Date(iso);
  if (Number.isNaN(parsed.getTime())) return '—';
  const pad = (n: number) => String(n).padStart(2, '0');
  return (
    `${parsed.getFullYear()}-${pad(parsed.getMonth() + 1)}-${pad(parsed.getDate())} ` +
    `${pad(parsed.getHours())}:${pad(parsed.getMinutes())}:${pad(parsed.getSeconds())}`
  );
}

// ---------- 7. 版本状态 ----------

export interface VersionStatusPresentation {
  label: string;
  tone: 'success' | 'default' | 'warning';
}

export function versionStatusPresentation(status: AgentStatus): VersionStatusPresentation {
  switch (status) {
    case 'ACTIVE':
      return { label: '生效中', tone: 'success' };
    case 'DRAFT':
      return { label: '草稿', tone: 'warning' };
    case 'RETIRED':
      return { label: '已下线', tone: 'default' };
    default:
      return { label: String(status), tone: 'default' };
  }
}

export function agentStatusPresentation(status: AgentStatus): VersionStatusPresentation {
  return versionStatusPresentation(status);
}

export const INPUT_FORMAT_LABELS: Record<string, string> = {
  STRUCTURED_JSON: '结构化 JSON',
  NATURAL_LANGUAGE: '自然语言',
};

export const EVAL_STATUS_PRESENTATION: Record<'PENDING' | 'CONFIRMED', { label: string; tone: string }> = {
  PENDING: { label: '待确认', tone: 'warning' },
  CONFIRMED: { label: '已确认', tone: 'success' },
};

// ---------- 8. Agent 列表 URL query（筛选进 URL；列表端点无查询参数，筛选在客户端做） ----------

export interface AgentListFilters {
  state?: AgentListState;
  hasDraft?: boolean;
  allowWrite?: boolean;
}

export function agentListFiltersFromParams(params: URLSearchParams): AgentListFilters {
  const state = params.get('state');
  return {
    state: state === 'RUNNING' || state === 'DISABLED' || state === 'NO_ACTIVE_VERSION' ? state : undefined,
    hasDraft: params.get('draft') === 'yes' ? true : params.get('draft') === 'no' ? false : undefined,
    allowWrite: params.get('write') === 'yes' ? true : params.get('write') === 'no' ? false : undefined,
  };
}

export function agentListSearchParams(filters: AgentListFilters): URLSearchParams {
  const params = new URLSearchParams();
  if (filters.state) params.set('state', filters.state);
  if (filters.hasDraft !== undefined) params.set('draft', filters.hasDraft ? 'yes' : 'no');
  if (filters.allowWrite !== undefined) params.set('write', filters.allowWrite ? 'yes' : 'no');
  return params;
}

export function agentListLocation(filters: AgentListFilters): string {
  const query = agentListSearchParams(filters).toString();
  return query ? `/agents?${query}` : '/agents';
}

/** 客户端筛选（列表端点无查询参数）；state/草稿/写权限全部进 URL 保证可分享。 */
export function filterAgentItems(
  items: AgentListItem[],
  filters: AgentListFilters,
): AgentListItem[] {
  return items.filter((item) => {
    if (filters.state && item.state !== filters.state) return false;
    if (filters.hasDraft !== undefined && (item.draft_count > 0) !== filters.hasDraft) return false;
    if (filters.allowWrite !== undefined && item.allow_write !== filters.allowWrite) return false;
    return true;
  });
}

// ---------- 12. 工具白名单摘要：读写两种读法 ----------

export interface ToolsSummary {
  /** 无工具时为 null，调用方显示「无工具」。 */
  label: string | null;
  writeCount: number;
  unregisteredCount: number;
}

/**
 * 「11 只读」与「3 · 含 2 写」是两种读法——后者才需要警觉。
 * 业务 Agent 写工具零调用是平台红线，含写工具必须在列表一眼可见，
 * 而不是藏在悬停清单里。
 */
export function toolsSummary(tools: AgentToolItem[]): ToolsSummary {
  if (!tools.length) return { label: null, writeCount: 0, unregisteredCount: 0 };
  const writeCount = tools.filter((t) => t.read_only === false).length;
  const unregisteredCount = tools.filter((t) => !t.registered).length;
  const label = writeCount > 0 ? `${tools.length} · 含 ${writeCount} 写` : `${tools.length} 只读`;
  return { label, writeCount, unregisteredCount };
}

// ---------- 13. 处理条：只在真有事时渲染 ----------

export interface AttentionSummary {
  draftTotal: number;
  /** 有待确认草稿的 Agent 名称，供处理条给出去处。 */
  draftAgents: string[];
  failureTotal: number;
  /** 两项皆为零时整条不渲染——没事时不该占落地页版面。 */
  hasAnything: boolean;
}

/**
 * 列表页唯一需要人动手的事是确认草稿。埋进表格的一列数字就浪费了落地页，
 * 所以在表格之上单独给它一条；近 7 日失败是同等级别的健康信号，并列。
 */
export function attentionSummary(items: AgentListItem[]): AttentionSummary {
  const withDrafts = items.filter((i) => i.draft_count > 0);
  const draftTotal = withDrafts.reduce((sum, i) => sum + i.draft_count, 0);
  const failureTotal = items.reduce((sum, i) => sum + i.seven_day_failure_count, 0);
  return {
    draftTotal,
    draftAgents: withDrafts.map((i) => i.name),
    failureTotal,
    hasAnything: draftTotal > 0 || failureTotal > 0,
  };
}

// ---------- 14. 工具调用耗时条 ----------

/**
 * 条宽是组内相对值（相对最慢的一步）。「哪一步最慢」应该是扫一眼的事，
 * 不该逼人比较三四个四位数。耗时缺失（如运行中断）返回 0，不画条。
 */
export function latencyBarPercents(calls: RunToolCallItem[]): Record<number, number> {
  const max = calls.reduce((m, c) => Math.max(m, c.latency_ms ?? 0), 0);
  const out: Record<number, number> = {};
  for (const c of calls) {
    out[c.sequence_no] = max > 0 && c.latency_ms != null ? Math.round((c.latency_ms / max) * 100) : 0;
  }
  return out;
}
