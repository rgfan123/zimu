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

/** 消耗汇总的分组维度（129 票；后端 TokenUsageGroupBy 枚举，前端不得自造值）。 */
export type TokenUsageGroupBy = 'AGENT' | 'DAY' | 'BUSINESS_ENTITY_TYPE';

export interface TokenUsageSummaryItem {
  /** 分组键：agent_slug / 业务日（Asia/Shanghai）/ 业务实体类型；合计行为空串 */
  group_key: string;
  runs: number;
  failed_runs: number;
  /**
   * 该组内**没有任何计量**的运行数。求和只覆盖有计量的运行，因此此值 > 0 时
   * 汇总是下界而非全量（未配置模型的 fail-closed 运行、进程中断的运行都落这里）。
   * 界面必须把这件事说出来——不说，读者会把下界当全量。
   */
  runs_without_token_usage: number;
  /** 单次超阈值的运行数；阈值默认关闭时恒为 0 */
  over_threshold_runs: number;
  prompt_tokens: number;
  completion_tokens: number;
  total_tokens: number;
  /** 单次运行的 token 峰值；无计量时为 null */
  max_run_total_tokens: number | null;
  /** 模型调用轮数累计（含工具轮）；算单轮均耗的分母 */
  model_calls: number;
  total_latency_ms: number;
  /** 单次运行的耗时峰值；无收口运行时为 null */
  max_run_latency_ms: number | null;
}

export interface TokenUsageSummaryResponse {
  group_by: TokenUsageGroupBy;
  run_mode: RunMode;
  items: TokenUsageSummaryItem[];
  /** 合计行；group_key 为空串。峰值列是各组峰值的最大值，不是求和 */
  totals: TokenUsageSummaryItem;
}

// ---------- meta-agent 对话式创建（agent-console 06） ----------

export type MetaAgentOutcomeKind = 'SUCCESS' | 'NEEDS_INPUT' | 'REJECTED' | 'FAILED';

export interface MetaAgentOutcome {
  outcome: MetaAgentOutcomeKind;
  /** 关联 agent_runs 与审计，双向可追溯 */
  run_id: string | null;
  agent_slug: string | null;
  draft_version: number | null;
  /**
   * 草稿上的 enabled 值。**这只是草稿里的一个字段，不代表已启用**——
   * 启用永远是人到 Agent 详情页单独做的动作。
   */
  draft_enabled: boolean | null;
  /** NEEDS_INPUT 时的澄清问题；模型同时给草稿和问题时两者都会带回 */
  questions: string[];
  /** REJECTED 时的理由，必须可操作 */
  rejection_reason: string | null;
  error: string | null;
  /** 模型原始输出，供右侧草稿预览 */
  raw: unknown | null;
}

// ---------- 履约单据 Agent（Excel 四段闭环） ----------

export interface ImportBatchStage {
  name: string;
  /**
   * 该段是否已接入。**false 时 total/done/blocked 不会返回**——
   * 「该段不适用」与「0 待办」是完全不同的行动信号。
   */
  supported: boolean;
  total?: number;
  done?: number;
  blocked?: number;
  complete?: boolean;
}

export interface ImportBatchBlocker {
  stage: string;
  /** 稳定码（复核 reason_code / 京东失败码 / 回传失败码） */
  code: string;
  count: number;
  /** 可去后台搜的业务号 */
  sampleNo: string | null;
}

export interface ImportBatchProgress {
  batchId: number;
  batchNo: string;
  batchType: string;
  sourceChannel: string | null;
  status: string;
  revisionNo: number;
  receivedAt: string | null;
  processedAt: string | null;
  intake: ImportBatchStage;
  outbound: ImportBatchStage;
  tracking: ImportBatchStage;
  sourceReturn: ImportBatchStage;
  blockers: ImportBatchBlocker[];
}

export interface FulfillmentFileAssessment {
  batchNo: string;
  currentStage: string | null;
  summary: string;
  stageNotes: Array<{ stage: string; note: string }>;
  suggestedActions: Array<{ action: string; reason: string; targetNo: string | null }>;
  requiresHuman: boolean;
  missingFields: string[];
}

export interface FulfillmentFileRunResult {
  /** 确定性事实，永远存在——模型挂了也不该让运营连事实都看不到 */
  progress: ImportBatchProgress;
  /** 模型解读；失败时为 null */
  assessment: FulfillmentFileAssessment | null;
  provider: string | null;
  model: string | null;
  promptVersion: string | null;
  error: string | null;
}
