/**
 * Agent 中心 API 类型 —— 严格对照 backend `agent/dto/` 的 T12 读契约（snake_case JSON，
 * 全契约 SNAKE_CASE 命名策略，见 backend/common/web/JacksonConfig.java）。
 *
 * 字段逐一对应：
 *  - AgentListItem/AgentListResponse/AgentListState → AgentListItem.java / AgentListState.java
 *  - AgentDetail/AgentVersionItem/AgentEvalCaseItem → dto/AgentDetail.java 等
 *  - RunListItem/RunListResponse/RunDetail/RunToolCallItem/RunEvalResultItem → dto/Run*.java
 *  - ModelMetadataItem/ToolItem → dto/ModelMetadataItem.java / dto/ToolItem.java
 *
 * `state` 由服务端按 status×enabled 投影（AgentListState），前端只渲染不推导。
 */

// ---------- Agent 定义 ----------

/** 列表行的运行状态投影：active+enabled=运行中；active+disabled=已停用；无 active 版本=无生效版本。 */
export type AgentListState = 'RUNNING' | 'DISABLED' | 'NO_ACTIVE_VERSION';

/** 版本生命周期（draft/active/retired，无回边），与 enabled 正交。 */
export type AgentStatus = 'DRAFT' | 'ACTIVE' | 'RETIRED';

/** 输入约定：STRUCTURED_JSON 走输入解析校验；NATURAL_LANGUAGE 直接透传。 */
export type AgentInputFormat = 'STRUCTURED_JSON' | 'NATURAL_LANGUAGE';

/** 工具白名单投影：read_only=null 仅出现在 registered=false（配置漂移，按未知工具处理）。 */
export interface AgentToolItem {
  name: string;
  read_only: boolean | null;
  registered: boolean;
}

export interface AgentListItem {
  slug: string;
  name: string;
  state: AgentListState;
  enabled: boolean;
  /** 当前生效版本号；无 active 版本时为 null（显示「—」而非 0） */
  current_version: number | null;
  draft_count: number;
  /** 近 7 日 run_mode='LIVE' 的运行统计（PREVIEW 草稿试跑不污染） */
  seven_day_run_count: number;
  seven_day_failure_count: number;
  allow_write: boolean;
  model_ref: string | null;
  prompt_version: string | null;
  tools: AgentToolItem[];
}

export interface AgentListResponse {
  items: AgentListItem[];
}

export interface AgentDetail {
  slug: string;
  name: string;
  description: string | null;
  system_prompt: string;
  prompt_version: string;
  model_ref: string;
  enabled: boolean;
  version: number;
  status: AgentStatus;
  activated_by: string | null;
  activated_at: string | null;
  allow_write: boolean;
  /** 空数组 = 默认守卫全部生效 */
  guard_exemptions: string[];
  output_schema: unknown | null;
  input_format: AgentInputFormat;
  tools: AgentToolItem[];
}

export interface AgentVersionItem {
  version: number;
  status: AgentStatus;
  activated_by: string | null;
  activated_at: string | null;
}

export type EvalMetricKind = 'INVARIANT' | 'QUALITY';
export type EvalCaseStatus = 'PENDING' | 'CONFIRMED';

/** 评测用例投影：绑定 (agent_slug, agent_version) 冻结集；case id 即 DB 主键。 */
export interface AgentEvalCaseItem {
  id: number;
  agent_slug: string;
  agent_version: number;
  metric_kind: EvalMetricKind;
  input: unknown;
  expected: unknown;
  status: EvalCaseStatus;
  created_by: string | null;
  confirmed_by: string | null;
  confirmed_at: string | null;
}

// ---------- 模型元数据（服务端 allowlist 投影，三态不得折叠） ----------

export type ModelVisibility = 'EXPOSED' | 'NOT_PUBLIC' | 'NOT_CONFIGURED';

export interface ModelMetadataItem {
  /** NOT_PUBLIC / NOT_CONFIGURED 时为 "none" */
  provider: string;
  model: string;
  prompt_version: string;
  visibility: ModelVisibility;
}

// ---------- 运行记录 ----------

export type RunStatus = 'RUNNING' | 'SUCCESS' | 'FAILED';
export type RunOutcome = 'SUCCESS' | 'NEEDS_INPUT' | 'REJECTED' | 'FAILED';
export type RunMode = 'LIVE' | 'PREVIEW';

export interface RunListItem {
  run_id: string;
  agent_slug: string;
  agent_version: string;
  status: RunStatus;
  /** 运行中为 null（显示「进行中」而非空白） */
  outcome: RunOutcome | null;
  run_mode: RunMode;
  error_type: string | null;
  latency_ms: number | null;
  token_usage: unknown | null;
  business_entity_type: string | null;
  business_entity_id: string | null;
  intent: string | null;
  model_metadata: ModelMetadataItem | null;
  started_at: string | null;
  finished_at: string | null;
}

export interface RunListResponse {
  items: RunListItem[];
  /** 过滤后的总数（分页用） */
  total: number;
}

export interface RunToolCallItem {
  sequence_no: number;
  tool_name: string;
  /** 落库时经 SecretRedactor 脱敏与截断后的摘要，敏感原文不落库 */
  args_summary: string | null;
  result_summary: string | null;
  latency_ms: number | null;
  status: 'SUCCESS' | 'FAILED';
}

export interface RunEvalResultItem {
  status: 'RUNNING' | 'SUCCEEDED' | 'FAILED';
  case_count: number;
  passed_count: number;
  started_at: string | null;
  finished_at: string | null;
}

export interface RunDetail {
  run_id: string;
  thread_id: string | null;
  agent_slug: string;
  agent_version: string;
  status: RunStatus;
  outcome: RunOutcome | null;
  run_mode: RunMode;
  error_type: string | null;
  latency_ms: number | null;
  token_usage: unknown | null;
  business_entity_type: string | null;
  business_entity_id: string | null;
  intent: string | null;
  model_metadata: ModelMetadataItem | null;
  /** SHA-256 摘要；输入原文不留存（隐私设计），界面须显式说明 */
  input_digest: string | null;
  started_at: string | null;
  finished_at: string | null;
  tool_calls: RunToolCallItem[];
  /** 仅 QUALITY PREVIEW 评测行存在 */
  eval_result: RunEvalResultItem | null;
}
