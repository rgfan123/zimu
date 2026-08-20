package cn.zimu.fulfillment.agent;

import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.domain.DataScope;
import cn.zimu.fulfillment.message.AsyncTaskStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 定义域写端点领域实现（meta-agent-platform-impl 11，决策 12）：五个写动作的入队（202
 * 异步）与任务执行（Spring Worker，复用 message-worker 模式）。
 *
 * <p><b>异步闭环</b>：每个写动作在入队事务内落一条 {@code agent_runs} 行
 * （run_mode=PREVIEW，12 决策 3：隔离草稿试跑，轮询复用 T12 的 {@code GET /api/agent-runs}）
 * 并入队一条 {@code app.async_tasks} 任务（载荷 JSON 存 payload_ref，V40 放宽为 TEXT）；
 * 任务执行期把门禁结果/确认影响范围等明细经 {@code agent_tool_calls} 合成行落库——T12 的
 * run 详情（含工具调用序列）即轮询面，不另建任务查询端点。任务 maxAttempts=1：业务失败
 * 重试无意义，幂等由「目标状态幂等」承担（confirm 已 active 同版本 → 200 同步重放、并发
 * 确认由 DB 部分唯一索引兜底败者 AGENT_CONFLICT）。
 *
 * <p><b>红线</b>：operator 一律取自 Basic Auth 认证身份（入队时经 {@link
 * cn.zimu.fulfillment.common.web.RequestContext#getAuthenticatedOperator()} 捕获进任务载荷，
 * 请求体无 operator 字段，控制器层显式拒绝）；审计与观测失败 try/catch 隔离，不回滚业务
 * 事务；confirm 的「定义行 draft→active + 该版本 PENDING 用例→CONFIRMED」在单一事务内
 * 原子提交（部分成功整体回滚）；reject 先 FOR UPDATE 锁定义行再删（防并发确认窗口内误删
 * 已冻结用例集）；rollback 复制目标版本为 v{n+1} 新草稿（append-only 版本链无回边）。
 */
@Service
public class AgentDefinitionWriteService {

    public static final String TASK_DRAFT_CREATE = "AGENT_DRAFT_CREATE";
    public static final String TASK_CONFIRM = "AGENT_CONFIRM";
    public static final String TASK_REJECT = "AGENT_REJECT";
    public static final String TASK_SET_ENABLED = "AGENT_SET_ENABLED";
    public static final String TASK_ROLLBACK = "AGENT_ROLLBACK";

    /** 任务失败稳定错误码（与 agent_runs.error_type 同一枚举空间，T12 轮询面直接呈现）。 */
    public static final String ERR_GATE_BLOCKED = "AGENT_GATE_BLOCKED";
    public static final String ERR_INVARIANT_BLOCKED = "AGENT_INVARIANT_BLOCKED";
    public static final String ERR_CONFLICT = "AGENT_CONFLICT";
    public static final String ERR_VERSION_NOT_FOUND = "AGENT_VERSION_NOT_FOUND";
    public static final String ERR_VERSION_RETIRED = "AGENT_VERSION_RETIRED";
    public static final String ERR_NO_ACTIVE_VERSION = "AGENT_NO_ACTIVE_VERSION";
    public static final String ERR_ROLLBACK_TARGET_NOT_ACTIVE = "AGENT_ROLLBACK_TARGET_NOT_ACTIVE";

    private static final String BUSINESS_ENTITY_TYPE = "AGENT_DEFINITION";
    private static final String THREAD_ID = "agent-task";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AsyncTaskStore taskStore;
    private final AgentObservability observability;
    private final AgentDefinitionRepository definitions;
    private final AgentDraftService drafts;
    private final AgentInvariantEval invariantEval;
    private final AgentRegistryHolder holder;
    private final AuditLogService audits;
    /** 懒解析门禁引擎（与 AgentDraftService 同款）：门禁引擎 → McpToolRegistry → 写工具链
     * 含 JD 写客户端（条件装配），直注入会把整条链在无 JD 配置的上下文里强制拉起（基线
     * 语义：写工具链只在真正评估时解析）。 */
    private final ObjectProvider<AgentGateEngine> gateEngineProvider;

    public AgentDefinitionWriteService(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            AsyncTaskStore taskStore,
            AgentObservability observability,
            AgentDefinitionRepository definitions,
            AgentDraftService drafts,
            AgentInvariantEval invariantEval,
            AgentRegistryHolder holder,
            AuditLogService audits,
            ObjectProvider<AgentGateEngine> gateEngineProvider) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.taskStore = taskStore;
        this.observability = observability;
        this.definitions = definitions;
        this.drafts = drafts;
        this.invariantEval = invariantEval;
        this.holder = holder;
        this.audits = audits;
        this.gateEngineProvider = gateEngineProvider;
    }

    /** 入队/同步重放结果：{@code replayed=true} 时控制器返回 200 + 当前状态，否则 202 + run_id。 */
    public record SubmitResult(boolean replayed, String runId, Map<String, Object> state) {

        static SubmitResult accepted(String runId) {
            return new SubmitResult(false, runId, null);
        }

        static SubmitResult replay(Map<String, Object> state) {
            return new SubmitResult(true, null, state);
        }
    }

    // ==================================================================
    // 入队（控制器面，@Transactional：运行行 + 任务行同事务原子）
    // ==================================================================

    /** 人工建草稿（202）：载荷校验 + slug 冲突 409 同步拒绝；任务内静态门禁 + INVARIANT stub 评测闭环。 */
    @Transactional
    public SubmitResult enqueueDraftCreate(String operator, JsonNode payload) {
        AgentDraftService.Draft draft = AgentDraftService.Draft.parse(payload, mapper);
        if (definitions.findVersion(draft.slug(), maxVersion(draft.slug())).isPresent()) {
            throw BusinessException.conflict("AGENT_SLUG_EXISTS", "agent_slug 已存在: " + draft.slug());
        }
        String runId = startRun(
                draft.slug(), "1", draft.promptVersion(), draft.modelRef(),
                AgentPayloadRedactor.digest(payload.toString()));
        taskStore.enqueue(
                TASK_DRAFT_CREATE,
                payloadJson(Map.of("run_id", runId, "operator", operator, "draft", payload)),
                taskKey("draft-create", draft.slug(), 1),
                1);
        return SubmitResult.accepted(runId);
    }

    /** 确认草稿（202）：目标状态幂等——已 active 同版本 → 200 重放；retired/不存在 → 409/404。 */
    @Transactional
    public SubmitResult enqueueConfirm(String operator, String slug, int version) {
        AgentDefinition definition = definitions.findVersion(slug, version)
                .orElseThrow(() -> BusinessException.notFound("版本不存在: " + slug + " v" + version));
        if (definition.status() == AgentStatus.ACTIVE) {
            return SubmitResult.replay(Map.of(
                    "agent_slug", slug, "version", version, "status", "active", "enabled", definition.enabled()));
        }
        if (definition.status() != AgentStatus.DRAFT) {
            throw BusinessException.conflict("AGENT_VERSION_RETIRED", "版本已退役，不可确认: " + slug + " v" + version);
        }
        // 捕获「当前生效版本」进任务载荷：执行期只退役该具体版本——并发确认不同版本时，
        // 后到者的退役命中 0 行（旧版已被先到者退役），随后激活语句在 DB 部分唯一索引上
        // 失败（败者 409）；绝不可能退役掉先到者刚激活的版本（防静默覆盖）。
        Map<String, Object> active = activeState(slug);
        int previousActiveVersion = active == null ? 0 : ((Number) active.get("version")).intValue();
        String runId = startRun(slug, version, definition);
        taskStore.enqueue(
                TASK_CONFIRM,
                payloadJson(Map.of(
                        "run_id", runId, "agent_slug", slug, "version", version,
                        "prev_active_version", previousActiveVersion, "operator", operator)),
                taskKey("confirm", slug, version),
                1);
        return SubmitResult.accepted(runId);
    }

    /** 拒绝草稿（202）：对已拒绝幂等 200；active/retired 版本 409；不存在 404。 */
    @Transactional
    public SubmitResult enqueueReject(String operator, String slug, int version) {
        AgentDefinition definition = definitions.findVersion(slug, version).orElse(null);
        if (definition == null) {
            if (wasRejected(slug, version)) {
                // 行已硬删（03 决策：拒绝 = 硬删行）且该版本曾存在过拒绝任务 → 幂等 200
                return SubmitResult.replay(Map.of(
                        "agent_slug", slug, "version", version, "status", "rejected"));
            }
            throw BusinessException.notFound("版本不存在: " + slug + " v" + version);
        }
        if (definition.status() == AgentStatus.DRAFT) {
            String runId = startRun(slug, version, definition);
            taskStore.enqueue(
                    TASK_REJECT,
                    commandPayload(runId, slug, version, operator),
                    taskKey("reject", slug, version),
                    1);
            return SubmitResult.accepted(runId);
        }
        throw BusinessException.conflict("AGENT_VERSION_NOT_DRAFT", "只有草稿可拒绝: " + slug + " v" + version);
    }

    /**
     * 该 (slug, version) 是否已被拒绝过：存在已领取的拒绝任务（RUNNING/FINALIZING/
     * SUCCEEDED）即视为已拒绝——删除与任务收口同事务，SUCCEEDED ⟺ 行已硬删；RUNNING/
     * FINALIZING 是首拒尚在执行/租约恢复的窗口，按已拒绝处理（幂等 200）无害。版本号
     * 只增不减，拒绝后不会再现同版本新草稿，判定无歧义。
     */
    private boolean wasRejected(String slug, int version) {
        Boolean rejected = jdbc.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1 FROM app.async_tasks
                    WHERE task_type = 'AGENT_REJECT' AND status IN ('RUNNING', 'FINALIZING', 'SUCCEEDED')
                      AND payload_ref::jsonb ->> 'agent_slug' = ?
                      AND (payload_ref::jsonb ->> 'version')::int = ?
                )
                """,
                Boolean.class,
                slug, version);
        return Boolean.TRUE.equals(rejected);
    }

    /** 运维启停（202）：显式目标值幂等——已处于目标值 → 200 重放；无生效版本 404。 */
    @Transactional
    public SubmitResult enqueueSetEnabled(String operator, String slug, boolean enabled) {
        Map<String, Object> active = activeState(slug);
        if (active == null) {
            throw BusinessException.notFound("无生效版本: " + slug);
        }
        if (Boolean.TRUE.equals(active.get("enabled")) == enabled) {
            return SubmitResult.replay(Map.of(
                    "agent_slug", slug,
                    "version", active.get("version"),
                    "status", "active",
                    "enabled", enabled));
        }
        String runId = startRun(
                slug, String.valueOf(active.get("version")), String.valueOf(active.get("prompt_version")),
                String.valueOf(active.get("model_ref")), AgentPayloadRedactor.digest(slug + ":" + enabled));
        taskStore.enqueue(
                TASK_SET_ENABLED,
                payloadJson(Map.of(
                        "run_id", runId, "agent_slug", slug, "enabled", enabled, "operator", operator)),
                taskKey("set-enabled", slug, (Integer) active.get("version")),
                1);
        return SubmitResult.accepted(runId);
    }

    /** 回滚（202）：目标版本须曾 active（active/retired 行）；草稿目标 409；不存在 404。 */
    @Transactional
    public SubmitResult enqueueRollback(String operator, String slug, int targetVersion) {
        AgentDefinition target = definitions.findVersion(slug, targetVersion)
                .orElseThrow(() -> BusinessException.notFound("版本不存在: " + slug + " v" + targetVersion));
        if (target.status() == AgentStatus.DRAFT) {
            throw BusinessException.conflict(
                    "AGENT_ROLLBACK_TARGET_NOT_ACTIVE", "目标版本须曾 active（当前为草稿）: " + slug + " v" + targetVersion);
        }
        String runId = startRun(slug, targetVersion, target);
        taskStore.enqueue(
                TASK_ROLLBACK,
                payloadJson(Map.of(
                        "run_id", runId, "agent_slug", slug, "target_version", targetVersion, "operator", operator)),
                taskKey("rollback", slug, targetVersion),
                1);
        return SubmitResult.accepted(runId);
    }

    // ==================================================================
    // 任务执行（Worker 面）：门禁结果/影响范围经 agent_tool_calls 合成行落库
    // ==================================================================

    /** 建草稿任务：静态门禁 → INVARIANT stub 评测 → 草稿落库 + PENDING 建议用例（同一闭环）。 */
    public void executeDraftCreate(AsyncTaskStore.AsyncTask task) {
        long started = System.nanoTime();
        JsonNode payload = parsePayload(task);
        String runId = payload.path("run_id").asText();
        String operator = payload.path("operator").asText("");
        try {
            AgentDraftService.Draft draft = AgentDraftService.Draft.parse(payload.path("draft"), mapper);
            String slug = draft.slug();
            int version = 1;
            AgentDefinition definition = draft.toDefinition(version, AgentStatus.DRAFT);

            AgentGateReport gate = gateEngineProvider.getObject().evaluate(definition);
            AgentInvariantEval.Report invariant =
                    invariantEval.evaluate(definition, invariantEval.loadInvariantCases(slug, version));
            if (!gate.passed() || !invariant.passed()) {
                recordGateResults(runId, slug, version, gate, invariant, started);
                markRunFailed(runId, gate.passed() ? ERR_INVARIANT_BLOCKED : ERR_GATE_BLOCKED, started);
                throw new AgentDefinitionTaskFailure(
                        gate.passed() ? ERR_INVARIANT_BLOCKED : ERR_GATE_BLOCKED,
                        String.join("；", gate.passed() ? invariant.blockers() : gate.blockers()));
            }

            try {
                drafts.createDraftTx(operator, payload.path("draft"));
            } catch (BusinessException ex) {
                if ("AGENT_SLUG_EXISTS".equals(ex.getBusinessCode())) {
                    // 租约恢复重试：草稿已由前一次尝试创建 → 幂等视为成功（不产生第二条草稿）
                    AgentDefinition existing = definitions.findVersion(slug, maxVersion(slug)).orElse(null);
                    if (existing != null && existing.status() == AgentStatus.DRAFT) {
                        recordTaskTools(runId, List.of(
                                tool(runId, 1, "agent_gate",
                                        Map.of("agent_slug", slug, "version", version), gateResult(gate), started, true),
                                tool(runId, 2, "agent_invariant_eval",
                                        Map.of("agent_slug", slug, "version", version), invariantResult(invariant), started, true),
                                tool(runId, 3, "agent_draft_persist",
                                        Map.of("agent_slug", slug),
                                        Map.of("action", "create_draft", "version", existing.version(),
                                                "status", "draft", "replayed", true), started, true)));
                        markRunSuccess(runId, started);
                        return;
                    }
                }
                markRunFailed(runId, ex.getBusinessCode(), started);
                throw new AgentDefinitionTaskFailure(ex.getBusinessCode(), ex.getMessage());
            }

            safeAudit(new AuditLogService.AuditCommand()
                    .dataScope(DataScope.BUSINESS)
                    .requestId(runId).traceId(runId)
                    .operator(operator)
                    .actorType(AuditActorType.HUMAN)
                    .service("agent")
                    .operation("agent.definition.draft-created")
                    .requestPayload(Map.of("agent_slug", slug, "version", version, "source", "manual"))
                    .responsePayload(Map.of("status", "draft"))
                    .businessCode("AGENT_DRAFT_CREATED"));
            recordTaskTools(runId, List.of(
                    tool(runId, 1, "agent_gate",
                            Map.of("agent_slug", slug, "version", version), gateResult(gate), started, true),
                    tool(runId, 2, "agent_invariant_eval",
                            Map.of("agent_slug", slug, "version", version), invariantResult(invariant), started, true),
                    tool(runId, 3, "agent_draft_persist",
                            Map.of("agent_slug", slug),
                            Map.of("action", "create_draft", "version", version, "status", "draft"), started, true)));
            markRunSuccess(runId, started);
        } catch (AgentDefinitionTaskFailure ex) {
            throw ex;
        } catch (RuntimeException ex) {
            markRunFailed(runId, "ASYNC_TASK_FAILED", started);
            throw ex;
        }
    }

    /** 确认任务：确认前全量门禁复跑（静态 + INVARIANT stub 评测，05 决策 #2）→ 原子联动确认。 */
    public void executeConfirm(AsyncTaskStore.AsyncTask task) {
        long started = System.nanoTime();
        JsonNode payload = parsePayload(task);
        String runId = payload.path("run_id").asText();
        String slug = payload.path("agent_slug").asText();
        int version = payload.path("version").asInt(0);
        int previousActiveVersion = payload.path("prev_active_version").asInt(0);
        String operator = payload.path("operator").asText("");
        try {
            AgentDefinition definition = definitions.findVersion(slug, version).orElse(null);
            if (definition == null) {
                markRunFailed(runId, ERR_VERSION_NOT_FOUND, started);
                throw new AgentDefinitionTaskFailure(ERR_VERSION_NOT_FOUND, "版本不存在: " + slug + " v" + version);
            }
            if (definition.status() == AgentStatus.ACTIVE) {
                // 幂等：目标状态已达成（并发同版本确认胜出/重试）→ 无操作成功
                recordTaskTools(runId, List.of(tool(runId, 1, "agent_confirm",
                        Map.of("agent_slug", slug, "version", version),
                        Map.of("action", "confirm", "status", "active", "replayed", true), started, true)));
                markRunSuccess(runId, started);
                return;
            }
            if (definition.status() != AgentStatus.DRAFT) {
                markRunFailed(runId, ERR_VERSION_RETIRED, started);
                throw new AgentDefinitionTaskFailure(ERR_VERSION_RETIRED, "版本已退役: " + slug + " v" + version);
            }

            // 1) 确认前全量门禁复跑（防提交后内容被编辑导致状态过期；全绿才可确认）
            AgentGateReport gate = gateEngineProvider.getObject().evaluate(definition);
            AgentInvariantEval.Report invariant =
                    invariantEval.evaluate(definition, invariantEval.loadInvariantCases(slug, version));
            if (!gate.passed() || !invariant.passed()) {
                recordGateResults(runId, slug, version, gate, invariant, started);
                markRunFailed(runId, gate.passed() ? ERR_INVARIANT_BLOCKED : ERR_GATE_BLOCKED, started);
                throw new AgentDefinitionTaskFailure(
                        gate.passed() ? ERR_INVARIANT_BLOCKED : ERR_GATE_BLOCKED,
                        String.join("；", gate.passed() ? invariant.blockers() : gate.blockers()));
            }

            // 2) 原子联动：替换（只退役入队时捕获的前任版本）+ 定义行 draft→active + PENDING 用例→CONFIRMED
            int confirmedCases = confirmStateTx(runId, slug, version, operator, previousActiveVersion);
            // 3) 注册表换实例：新 active 版本被运行路径感知（审计 diff 留 ACTIVATED/RETIRED）
            holder.reload();
            recordTaskTools(runId, List.of(
                    tool(runId, 1, "agent_gate",
                            Map.of("agent_slug", slug, "version", version), gateResult(gate), started, true),
                    tool(runId, 2, "agent_invariant_eval",
                            Map.of("agent_slug", slug, "version", version), invariantResult(invariant), started, true),
                    tool(runId, 3, "agent_confirm",
                            Map.of("agent_slug", slug, "version", version),
                            Map.of("action", "confirm", "status", "active",
                                    "confirmed_pending_cases", Math.max(0, confirmedCases)), started, true)));
            markRunSuccess(runId, started);
        } catch (AgentDefinitionTaskFailure ex) {
            // 事务内抛出的失败（版本消失/已退役等竞态）同样收口运行行（已标记则 no-op）
            markRunFailed(runId, ex.code(), started);
            throw ex;
        } catch (DuplicateKeyException ex) {
            // 并发确认不同版本：DB 部分唯一索引 UNIQUE(slug) WHERE status='active' 兜底，败者 409 语义
            markRunFailed(runId, ERR_CONFLICT, started);
            throw new AgentDefinitionTaskFailure(ERR_CONFLICT, "并发确认冲突：已有其他版本生效: " + slug);
        } catch (RuntimeException ex) {
            markRunFailed(runId, "ASYNC_TASK_FAILED", started);
            throw ex;
        }
    }

    /** 拒绝任务：FOR UPDATE 锁行后硬删（03 决策），防并发确认窗口内误删冻结用例集。 */
    public void executeReject(AsyncTaskStore.AsyncTask task) {
        long started = System.nanoTime();
        JsonNode payload = parsePayload(task);
        String runId = payload.path("run_id").asText();
        String slug = payload.path("agent_slug").asText();
        int version = payload.path("version").asInt(0);
        String operator = payload.path("operator").asText("");
        try {
            boolean deleted = rejectTx(runId, slug, version, operator);
            recordTaskTools(runId, List.of(tool(runId, 1, "agent_reject",
                    Map.of("agent_slug", slug, "version", version),
                    Map.of("action", "reject", "deleted", deleted, "replayed", !deleted), started, true)));
            markRunSuccess(runId, started);
        } catch (AgentDefinitionTaskFailure ex) {
            throw ex;
        } catch (RuntimeException ex) {
            markRunFailed(runId, "ASYNC_TASK_FAILED", started);
            throw ex;
        }
    }

    /** 启停任务：只改 active 行的 enabled（与 status 正交，03 决策），随后注册表换实例。 */
    public void executeSetEnabled(AsyncTaskStore.AsyncTask task) {
        long started = System.nanoTime();
        JsonNode payload = parsePayload(task);
        String runId = payload.path("run_id").asText();
        String slug = payload.path("agent_slug").asText();
        boolean enabled = payload.path("enabled").asBoolean(false);
        String operator = payload.path("operator").asText("");
        try {
            setEnabledTx(runId, slug, enabled, operator);
            holder.reload();
            recordTaskTools(runId, List.of(tool(runId, 1, "agent_set_enabled",
                    Map.of("agent_slug", slug, "enabled", enabled),
                    Map.of("action", "set_enabled", "enabled", enabled), started, true)));
            markRunSuccess(runId, started);
        } catch (AgentDefinitionTaskFailure ex) {
            // 事务内抛出的失败（如无生效版本）同样收口运行行（已标记则 no-op）
            markRunFailed(runId, ex.code(), started);
            throw ex;
        } catch (RuntimeException ex) {
            markRunFailed(runId, "ASYNC_TASK_FAILED", started);
            throw ex;
        }
    }

    /** 回滚任务：复制目标版本为 v{n+1} 新草稿（含冻结用例集副本），绝不动旧版本行。 */
    public void executeRollback(AsyncTaskStore.AsyncTask task) {
        long started = System.nanoTime();
        JsonNode payload = parsePayload(task);
        String runId = payload.path("run_id").asText();
        String slug = payload.path("agent_slug").asText();
        int targetVersion = payload.path("target_version").asInt(0);
        String operator = payload.path("operator").asText("");
        try {
            AgentDefinition target = definitions.findVersion(slug, targetVersion)
                    .orElseThrow(() -> fail(runId, ERR_VERSION_NOT_FOUND, started, "版本不存在: " + slug + " v" + targetVersion));
            if (target.status() == AgentStatus.DRAFT) {
                throw fail(runId, ERR_ROLLBACK_TARGET_NOT_ACTIVE, started,
                        "目标版本须曾 active（当前为草稿）: " + slug + " v" + targetVersion);
            }
            int newVersion = maxVersion(slug) + 1;
            AgentDefinition copy = AgentDefinition.of(
                    target.agentSlug(), target.name(), target.description(), target.systemPrompt(),
                    target.promptVersion(), target.modelRef(), target.enabled(), target.toolNames(),
                    newVersion, AgentStatus.DRAFT, null, null,
                    target.allowWrite(), target.guardExemptions(), target.outputSchema(), target.inputFormat());

            AgentGateReport gate = gateEngineProvider.getObject().evaluate(copy);
            AgentInvariantEval.Report invariant =
                    invariantEval.evaluate(copy, invariantEval.loadInvariantCases(slug, newVersion));
            if (!gate.passed() || !invariant.passed()) {
                recordGateResults(runId, slug, newVersion, gate, invariant, started);
                markRunFailed(runId, gate.passed() ? ERR_INVARIANT_BLOCKED : ERR_GATE_BLOCKED, started);
                throw new AgentDefinitionTaskFailure(
                        gate.passed() ? ERR_INVARIANT_BLOCKED : ERR_GATE_BLOCKED,
                        String.join("；", gate.passed() ? invariant.blockers() : gate.blockers()));
            }

            int copiedCases = rollbackTx(runId, copy, operator, targetVersion);
            recordTaskTools(runId, List.of(
                    tool(runId, 1, "agent_gate",
                            Map.of("agent_slug", slug, "version", newVersion), gateResult(gate), started, true),
                    tool(runId, 2, "agent_invariant_eval",
                            Map.of("agent_slug", slug, "version", newVersion), invariantResult(invariant), started, true),
                    tool(runId, 3, "agent_rollback",
                            Map.of("agent_slug", slug, "target_version", targetVersion),
                            Map.of("action", "rollback", "target_version", targetVersion,
                                    "new_version", newVersion, "status", "draft",
                                    "copied_cases", copiedCases), started, true)));
            markRunSuccess(runId, started);
        } catch (AgentDefinitionTaskFailure ex) {
            throw ex;
        } catch (RuntimeException ex) {
            markRunFailed(runId, "ASYNC_TASK_FAILED", started);
            throw ex;
        }
    }

    // ==================================================================
    // 事务边界（原子性：部分成功整体回滚）
    // ==================================================================

    /**
     * 确认状态迁移：同一事务内完成「替换（只退役入队时捕获的前任版本）→ 定义行
     * draft→active → 该版本 PENDING 用例→CONFIRMED」，部分成功整体回滚。
     * 返回确认的用例数；-1 表示同版本已被并发确认（目标状态已达成，无操作）。
     *
     * <p>替换语义（03：active→retired 被替换或下线）：退役语句只匹配
     * {@code previousActiveVersion}（入队时捕获的当时生效版本，守卫 status='active'）——
     * 并发确认不同版本时，先到者退役前任并激活成功，后到者的退役命中 0 行（前任已被
     * 退役），随后的激活语句在部分唯一索引 {@code UNIQUE(slug) WHERE status='active'} 上
     * 命中冲突 → {@link DuplicateKeyException} 上抛（败者 409，不靠应用层加锁；也绝不可能
     * 退役掉先到者刚激活的版本——防静默覆盖）。激活 0 行（同版本已被并发确认/行已消失）
     * 时返回/抛错，整笔事务回滚，退役不残留。
     */
    @Transactional
    int confirmStateTx(String runId, String slug, int version, String operator, int previousActiveVersion) {
        AgentDefinition current = definitions.findVersion(slug, version).orElse(null);
        if (current == null) {
            throw new AgentDefinitionTaskFailure(ERR_VERSION_NOT_FOUND, "版本不存在: " + slug + " v" + version);
        }
        if (current.status() == AgentStatus.ACTIVE) {
            return -1;
        }
        if (current.status() != AgentStatus.DRAFT) {
            throw new AgentDefinitionTaskFailure(ERR_VERSION_RETIRED, "版本已退役: " + slug + " v" + version);
        }
        // 只退役入队时捕获的前任版本（如有）：为激活腾出部分唯一索引槽位
        if (previousActiveVersion > 0) {
            jdbc.update(
                    """
                    UPDATE app.agent_definitions
                    SET status = 'retired'
                    WHERE agent_slug = ? AND version = ? AND status = 'active'
                    """,
                    slug, previousActiveVersion);
        }
        int updated = jdbc.update(
                """
                UPDATE app.agent_definitions
                SET status = 'active', activated_by = ?, activated_at = CURRENT_TIMESTAMP
                WHERE agent_slug = ? AND version = ? AND status = 'draft'
                """,
                operator, slug, version);
        if (updated != 1) {
            // 并发同版本确认已先行生效 → 目标状态已达成，无操作成功（本事务零写入，退役
            // 必为 0 行——先到者持 v1 行锁，后到者退役必然在其提交后重估）；行已消失则抛错
            // 整笔回滚，退役不残留。
            AgentDefinition latest = definitions.findVersion(slug, version).orElse(null);
            if (latest != null && latest.status() == AgentStatus.ACTIVE) {
                return -1;
            }
            throw new AgentDefinitionTaskFailure(ERR_VERSION_NOT_FOUND, "版本已不存在: " + slug + " v" + version);
        }
        // 07 决策联动：同一动作确认该版本全部 PENDING 用例（INVARIANT + QUALITY）
        int confirmed = jdbc.update(
                """
                UPDATE app.agent_eval_cases
                SET status = 'CONFIRMED', confirmed_by = ?, confirmed_at = CURRENT_TIMESTAMP
                WHERE agent_slug = ? AND agent_version = ? AND status = 'PENDING'
                """,
                operator, slug, version);
        safeAudit(new AuditLogService.AuditCommand()
                .dataScope(DataScope.BUSINESS)
                .requestId(runId).traceId(runId)
                .operator(operator)
                .actorType(AuditActorType.HUMAN)
                .service("agent")
                .operation("agent.definition.activated")
                .requestPayload(Map.of("agent_slug", slug, "version", version))
                .responsePayload(Map.of("status", "active", "confirmed_pending_cases", confirmed))
                .businessCode("AGENT_DEFINITION_ACTIVATED"));
        return confirmed;
    }

    /**
     * 拒绝事务：先 FOR UPDATE 锁定义行（与并发确认串行化），确认仍是 draft 才删——
     * 行已被确认/已拒绝时无操作（幂等）；用例行随定义行同一事务删除（版本消亡，冻结集
     * 不残留孤儿行）。
     */
    @Transactional
    boolean rejectTx(String runId, String slug, int version, String operator) {
        List<String> statuses = jdbc.query(
                "SELECT status FROM app.agent_definitions WHERE agent_slug = ? AND version = ? FOR UPDATE",
                (rs, row) -> rs.getString("status"),
                slug, version);
        if (statuses.isEmpty() || !"draft".equals(statuses.getFirst())) {
            return false;
        }
        jdbc.update(
                "DELETE FROM app.agent_eval_cases WHERE agent_slug = ? AND agent_version = ?",
                slug, version);
        jdbc.update(
                "DELETE FROM app.agent_definitions WHERE agent_slug = ? AND version = ? AND status = 'draft'",
                slug, version);
        safeAudit(new AuditLogService.AuditCommand()
                .dataScope(DataScope.BUSINESS)
                .requestId(runId).traceId(runId)
                .operator(operator)
                .actorType(AuditActorType.HUMAN)
                .service("agent")
                .operation("agent.definition.rejected")
                .requestPayload(Map.of("agent_slug", slug, "version", version))
                .responsePayload(Map.of("status", "rejected"))
                .businessCode("AGENT_DEFINITION_REJECTED"));
        return true;
    }

    /** 启停事务：只改 active 行 enabled（不碰 status，不铸版本）。 */
    @Transactional
    void setEnabledTx(String runId, String slug, boolean enabled, String operator) {
        int updated = jdbc.update(
                "UPDATE app.agent_definitions SET enabled = ? WHERE agent_slug = ? AND status = 'active'",
                enabled, slug);
        if (updated != 1) {
            throw new AgentDefinitionTaskFailure(ERR_NO_ACTIVE_VERSION, "无生效版本可启停: " + slug);
        }
        safeAudit(new AuditLogService.AuditCommand()
                .dataScope(DataScope.BUSINESS)
                .requestId(runId).traceId(runId)
                .operator(operator)
                .actorType(AuditActorType.HUMAN)
                .service("agent")
                .operation("agent.definition.set-enabled")
                .requestPayload(Map.of("agent_slug", slug, "enabled", enabled))
                .responsePayload(Map.of("enabled", enabled))
                .businessCode("AGENT_DEFINITION_ENABLED_CHANGED"));
    }

    /**
     * 回滚事务：复制目标版本全量内容为 v{n+1} draft 行，并把目标版本的 CONFIRMED 用例集
     * 复制为该新版本的 PENDING 用例（07 冻结集语义：换版本 = 换用例集，评测可复现可回滚）。
     * 旧版本行零改动（append-only 版本链无回边）。
     */
    @Transactional
    int rollbackTx(String runId, AgentDefinition copy, String operator, int targetVersion) {
        drafts.insertDefinition(copy);
        int copied = 0;
        List<Map<String, Object>> frozen = jdbc.queryForList(
                "SELECT metric_kind, input::text, expected::text FROM app.agent_eval_cases"
                        + " WHERE agent_slug = ? AND agent_version = ? AND status = 'CONFIRMED' ORDER BY id",
                copy.agentSlug(), targetVersion);
        for (Map<String, Object> evalCase : frozen) {
            jdbc.update(
                    """
                    INSERT INTO app.agent_eval_cases
                        (agent_slug, agent_version, metric_kind, input, expected, status, created_by)
                    VALUES (?, ?, ?, ?::jsonb, ?::jsonb, 'PENDING', ?)
                    """,
                    copy.agentSlug(), copy.version(), evalCase.get("metric_kind"),
                    evalCase.get("input"), evalCase.get("expected"), operator);
            copied++;
        }
        safeAudit(new AuditLogService.AuditCommand()
                .dataScope(DataScope.BUSINESS)
                .requestId(runId).traceId(runId)
                .operator(operator)
                .actorType(AuditActorType.HUMAN)
                .service("agent")
                .operation("agent.definition.rollback")
                .requestPayload(Map.of(
                        "agent_slug", copy.agentSlug(), "target_version", targetVersion,
                        "new_version", copy.version()))
                .responsePayload(Map.of("status", "draft", "copied_cases", copied))
                .businessCode("AGENT_DEFINITION_ROLLBACK"));
        return copied;
    }

    // ==================================================================
    // 运行行/工具调用行（观测失败隔离：try/catch，不影响业务事务）
    // ==================================================================

    /** 任务执行失败且 Worker 兜底路径需要收口运行行时使用（error_type 稳定码）。 */
    public void markRunFailed(AsyncTaskStore.AsyncTask task, String errorType) {
        String runId;
        try {
            runId = parsePayload(task).path("run_id").asText("");
        } catch (RuntimeException ex) {
            return;
        }
        if (runId.isBlank()) {
            return;
        }
        try {
            observability.runFinished(AgentObservability.Finish.of(runId, errorType, 0, null));
        } catch (RuntimeException ignored) {
            // 观测失败不掩盖任务收口
        }
    }

    /** 门禁未过时的统一明细记录：agent_gate + agent_invariant_eval 两行（阻塞原因可轮询）。 */
    private void recordGateResults(
            String runId, String slug, int version, AgentGateReport gate,
            AgentInvariantEval.Report invariant, long started) {
        recordTaskTools(runId, List.of(
                tool(runId, 1, "agent_gate",
                        Map.of("agent_slug", slug, "version", version), gateResult(gate), started,
                        gate.passed()),
                tool(runId, 2, "agent_invariant_eval",
                        Map.of("agent_slug", slug, "version", version), invariantResult(invariant), started,
                        invariant.passed())));
    }

    private void recordTaskTools(String runId, List<AgentObservability.ToolCall> calls) {
        for (AgentObservability.ToolCall call : calls) {
            try {
                observability.toolCallFinished(call);
            } catch (RuntimeException ignored) {
                // 观测失败不掩盖任务结果
            }
        }
    }

    private AgentObservability.ToolCall tool(
            String runId, int sequenceNo, String name, Map<String, Object> args,
            Map<String, Object> result, long started, boolean success) {
        return new AgentObservability.ToolCall(
                runId, sequenceNo, name, toJson(args), toJson(result),
                (System.nanoTime() - started) / 1_000_000, success);
    }

    private void markRunSuccess(String runId, long started) {
        try {
            observability.runFinished(
                    AgentObservability.Finish.of(runId, null, (System.nanoTime() - started) / 1_000_000, null));
        } catch (RuntimeException ignored) {
            // 观测失败不掩盖任务结果
        }
    }

    private void markRunFailed(String runId, String errorType, long started) {
        try {
            observability.runFinished(AgentObservability.Finish.of(
                    runId, errorType, (System.nanoTime() - started) / 1_000_000, null));
        } catch (RuntimeException ignored) {
            // 观测失败不掩盖任务结果
        }
    }

    /** 入队：运行行 RUNNING（run_mode=PREVIEW）+ 任务行同事务原子落库。 */
    private String startRun(String slug, int version, AgentDefinition definition) {
        return startRun(
                slug, String.valueOf(version), definition.promptVersion(), definition.modelRef(),
                AgentPayloadRedactor.digest(slug + ":v" + version));
    }

    private String startRun(String slug, String version, String promptVersion, String model, String digest) {
        String runId = AgentRuntimeFacade.newRunId();
        observability.runStarted(new AgentObservability.Start(
                runId, THREAD_ID, slug, version, promptVersion, model, digest,
                BUSINESS_ENTITY_TYPE, slug, "PREVIEW"));
        return runId;
    }

    // ==================================================================
    // 辅助
    // ==================================================================

    private Map<String, Object> activeState(String slug) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT version, enabled, model_ref, prompt_version FROM app.agent_definitions"
                        + " WHERE agent_slug = ? AND status = 'active'",
                slug);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private int maxVersion(String agentSlug) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version), 0) FROM app.agent_definitions WHERE agent_slug = ?",
                Integer.class, agentSlug);
        return max == null ? 0 : max;
    }

    private AgentDefinitionTaskFailure fail(String runId, String code, long started, String message) {
        markRunFailed(runId, code, started);
        return new AgentDefinitionTaskFailure(code, message);
    }

    /** 任务幂等键：每次请求唯一（重试/重放语义由目标状态幂等承担，任务级去重会锁死失败重试）。 */
    private static String taskKey(String action, String slug, int version) {
        return "agent-" + action + ":" + slug + ":v" + version + ":" + UUID.randomUUID();
    }

    private String commandPayload(String runId, String slug, int version, String operator) {
        return payloadJson(Map.of(
                "run_id", runId, "agent_slug", slug, "version", version, "operator", operator));
    }

    private String payloadJson(Map<String, Object> fields) {
        try {
            return mapper.writeValueAsString(fields);
        } catch (Exception ex) {
            throw new IllegalStateException("任务载荷序列化失败", ex);
        }
    }

    private JsonNode parsePayload(AsyncTaskStore.AsyncTask task) {
        try {
            return mapper.readTree(task.payloadRef());
        } catch (Exception ex) {
            throw new IllegalStateException("定义域任务载荷非法: " + task.taskType() + " #" + task.id(), ex);
        }
    }

    private String toJson(Map<String, Object> value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private static Map<String, Object> gateResult(AgentGateReport report) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("passed", report.passed());
        result.put("blockers", report.blockers());
        result.put("pii_warnings", report.piiWarnings());
        return result;
    }

    private static Map<String, Object> invariantResult(AgentInvariantEval.Report report) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("passed", report.passed());
        result.put("case_count", report.caseCount());
        result.put("blockers", report.blockers());
        return result;
    }

    private void safeAudit(AuditLogService.AuditCommand command) {
        try {
            audits.record(command);
        } catch (RuntimeException ignored) {
            // 审计失败隔离：审计只做流水，失败不回滚业务事务（红线）
        }
    }
}
