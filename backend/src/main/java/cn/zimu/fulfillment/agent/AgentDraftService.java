package cn.zimu.fulfillment.agent;

import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.idempotency.IdempotencyService;
import cn.zimu.fulfillment.common.idempotency.IdempotentResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Agent 定义草稿写入（06 决策；meta-agent-platform-impl 10）：{@code create_agent_draft} /
 * {@code update_agent_draft} 两个定义写工具的领域实现——写 {@code agent_definitions}
 * draft 行（全量快照）与 {@code agent_eval_cases} PENDING 用例（suggested_eval_cases）。
 *
 * <p>服务端校验：slug 格式 {@code ^[a-z][a-z0-9-]{0,63}$}、唯一性（create 拒绝已存在）、
 * 版本分配（新 slug=v1；update 对 draft 最新版原地覆盖、对 active/retired 最新版开新版本）、
 * target 只能是 draft 行且 ≠ meta-agent（防自改，与白名单不含自身写路径双重拒绝）、
 * allow_write 为合法布尔（矛盾草稿由 08 静态门禁只读不变式拒绝）。
 *
 * <p>接入 08 静态门禁（{@link AgentGateEngine}）：六项阻断任一命中 → 拒绝落库（不产生脏
 * 草稿）。幂等经 {@link IdempotencyService}（写工具外层 executeWrite 的幂等键同一把）。
 * 全量快照语义：草稿行携带输入的全部定义字段，版本链事实（version/status/activated）由
 * 服务端分配。
 */
@Service
public class AgentDraftService {

    public static final String META_AGENT_SLUG = "meta-agent";

    private final JdbcTemplate jdbc;
    private final IdempotencyService idempotency;
    private final AgentDefinitionRepository definitions;
    private final ObjectProvider<AgentGateEngine> gateEngineProvider;
    private final ObjectMapper mapper;
    /** PENDING 用例的 expected 占位（确认时人工补充 answer_contains 片段）。 */
    private final JsonNode pendingCaseExpected;

    public AgentDraftService(
            JdbcTemplate jdbc,
            IdempotencyService idempotency,
            AgentDefinitionRepository definitions,
            ObjectProvider<AgentGateEngine> gateEngineProvider,
            ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.idempotency = idempotency;
        this.definitions = definitions;
        this.gateEngineProvider = gateEngineProvider;
        this.mapper = mapper;
        com.fasterxml.jackson.databind.node.ObjectNode expected = mapper.createObjectNode();
        expected.putArray("answer_contains");
        this.pendingCaseExpected = expected;
    }

    /** 创建新 slug 的 v1 草稿（含建议评测用例落 PENDING）。 */
    public IdempotentResult<JsonNode> createDraft(String operator, String idempotencyKey, JsonNode draftPayload) {
        return idempotency.execute(
                "agent_draft.create",
                idempotencyKey,
                Map.of("draft", draftPayload),
                200,
                () -> createDraftTx(operator, draftPayload));
    }

    /** 更新已有 slug 的草稿（draft 最新版原地覆盖；否则开新版本草稿）。 */
    public IdempotentResult<JsonNode> updateDraft(String operator, String idempotencyKey, JsonNode draftPayload) {
        return idempotency.execute(
                "agent_draft.update",
                idempotencyKey,
                Map.of("draft", draftPayload),
                200,
                () -> updateDraftTx(operator, draftPayload));
    }

    @Transactional
    JsonNode createDraftTx(String operator, JsonNode payload) {
        Draft draft = Draft.parse(payload, mapper);
        rejectTargetIsMetaAgent(draft.slug());
        if (definitions.findVersion(draft.slug(), maxVersion(draft.slug())).isPresent()) {
            throw BusinessException.conflict("AGENT_SLUG_EXISTS", "agent_slug 已存在: " + draft.slug());
        }
        AgentDefinition definition = draft.toDefinition(1, AgentStatus.DRAFT);
        rejectGateBlocked(definition);
        insertDefinition(definition);
        insertPendingCases(definition, draft.suggestedEvalCases(), operator);
        return draft.outputJson(definition, mapper);
    }

    @Transactional
    JsonNode updateDraftTx(String operator, JsonNode payload) {
        Draft draft = Draft.parse(payload, mapper);
        rejectTargetIsMetaAgent(draft.slug());
        int currentMax = maxVersion(draft.slug());
        if (currentMax == 0) {
            throw BusinessException.notFound("agent_slug 不存在: " + draft.slug());
        }
        AgentDefinition latest = definitions.findVersion(draft.slug(), currentMax).orElseThrow();
        boolean draftOverwritable = latest.status() == AgentStatus.DRAFT
                && !hasConfirmedCases(draft.slug(), latest.version());
        AgentDefinition definition = draftOverwritable
                ? draft.toDefinition(latest.version(), AgentStatus.DRAFT)
                : draft.toDefinition(currentMax + 1, AgentStatus.DRAFT);
        rejectGateBlocked(definition);
        if (draftOverwritable) {
            // 原地覆盖 draft 最新版：UPDATE 行（保留 PENDING 用例外键引用的定义行），
            // 并替换该版本的建议评测用例（旧 PENDING 不再代表新草稿内容）。
            // 该版本已有 CONFIRMED 用例（冻结评测集）时不覆盖——转开新版本，冻结集不被改写
            updateDefinition(definition);
            replacePendingCases(definition, draft.suggestedEvalCases(), operator);
        } else {
            insertDefinition(definition);
            insertPendingCases(definition, draft.suggestedEvalCases(), operator);
        }
        return draft.outputJson(definition, mapper);
    }

    /** 该版本是否存在 CONFIRMED 用例（07 决策 #2：冻结评测集，draft 覆盖不得改写其指向内容）。 */
    private boolean hasConfirmedCases(String agentSlug, int version) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM app.agent_eval_cases"
                        + " WHERE agent_slug = ? AND agent_version = ? AND status = 'CONFIRMED'",
                Long.class,
                agentSlug,
                version);
        return count != null && count > 0;
    }

    private int maxVersion(String agentSlug) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version), 0) FROM app.agent_definitions WHERE agent_slug = ?",
                Integer.class,
                agentSlug);
        return max == null ? 0 : max;
    }

    private void rejectTargetIsMetaAgent(String agentSlug) {
        if (META_AGENT_SLUG.equals(agentSlug)) {
            throw BusinessException.forbidden("AGENT_TARGET_FORBIDDEN", "禁止修改 meta-agent 自身定义（防自改）");
        }
    }

    /** 08 静态门禁：六项阻断任一命中 → 拒绝落库（不产生脏草稿）。懒解析打破与写工具循环依赖。 */
    private void rejectGateBlocked(AgentDefinition definition) {
        AgentGateReport report = gateEngineProvider.getObject().evaluate(definition);
        if (!report.passed()) {
            throw BusinessException.badRequest(
                    "AGENT_GATE_BLOCKED", String.join("；", report.blockers()));
        }
    }

    /** 插入定义行（全量快照）。包可见：T11 回滚任务复制目标版本为新草稿复用同一落库 SQL。 */
    void insertDefinition(AgentDefinition definition) {
        jdbc.update(
                """
                INSERT INTO app.agent_definitions
                    (agent_slug, name, description, system_prompt, prompt_version, model_ref,
                     enabled, version, status, activated_by, activated_at, allow_write,
                     guard_exemptions, output_schema, tool_whitelist, input_format)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'draft', NULL, NULL, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?)
                """,
                definition.agentSlug(),
                definition.name(),
                definition.description(),
                definition.systemPrompt(),
                definition.promptVersion(),
                definition.modelRef(),
                definition.enabled(),
                definition.version(),
                definition.allowWrite(),
                toJsonArray(definition.guardExemptions()),
                definition.outputSchema() == null ? null : definition.outputSchema().toString(),
                toJsonArray(definition.toolNames()),
                definition.inputFormat().name());
    }

    /** 原地覆盖 draft 行（全量快照语义：同一 (slug, version) 行承载新内容）。 */
    private void updateDefinition(AgentDefinition definition) {
        jdbc.update(
                """
                UPDATE app.agent_definitions
                SET name = ?, description = ?, system_prompt = ?, prompt_version = ?, model_ref = ?,
                    enabled = ?, allow_write = ?, guard_exemptions = ?::jsonb,
                    output_schema = ?::jsonb, tool_whitelist = ?::jsonb, input_format = ?
                WHERE agent_slug = ? AND version = ? AND status = 'draft'
                """,
                definition.name(),
                definition.description(),
                definition.systemPrompt(),
                definition.promptVersion(),
                definition.modelRef(),
                definition.enabled(),
                definition.allowWrite(),
                toJsonArray(definition.guardExemptions()),
                definition.outputSchema() == null ? null : definition.outputSchema().toString(),
                toJsonArray(definition.toolNames()),
                definition.inputFormat().name(),
                definition.agentSlug(),
                definition.version());
    }

    /** 替换某版本的 PENDING 建议用例（旧建议不再代表新草稿内容）。 */
    private void replacePendingCases(AgentDefinition definition, List<String> suggested, String operator) {
        jdbc.update(
                "DELETE FROM app.agent_eval_cases"
                        + " WHERE agent_slug = ? AND agent_version = ? AND status = 'PENDING'",
                definition.agentSlug(),
                definition.version());
        insertPendingCases(definition, suggested, operator);
    }

    /** 建议评测输入落 PENDING 用例（QUALITY 待确认；expected 占位，确认时人工补充）。 */
    private void insertPendingCases(AgentDefinition definition, List<String> suggested, String operator) {
        if (suggested.isEmpty()) {
            return;
        }
        for (String input : suggested) {
            jdbc.update(
                    """
                    INSERT INTO app.agent_eval_cases
                        (agent_slug, agent_version, metric_kind, input, expected, status, created_by)
                    VALUES (?, ?, 'QUALITY', ?::jsonb, ?::jsonb, 'PENDING', ?)
                    """,
                    definition.agentSlug(),
                    definition.version(),
                    mapper.createObjectNode().put("input", input).toString(),
                    pendingCaseExpected.toString(),
                    operator);
        }
    }

    private String toJsonArray(List<String> values) {
        ArrayNode array = mapper.createArrayNode();
        values.forEach(array::add);
        return array.toString();
    }

    /** 草稿载荷解析与归一化（含服务端字段校验）。 */
    record Draft(
            String slug,
            String name,
            String description,
            String systemPrompt,
            String promptVersion,
            String modelRef,
            boolean enabled,
            List<String> toolWhitelist,
            boolean allowWrite,
            List<String> guardExemptions,
            JsonNode outputSchema,
            AgentInputFormat inputFormat,
            List<String> suggestedEvalCases) {

        static Draft parse(JsonNode node, ObjectMapper mapper) {
            if (node == null || !node.isObject()) {
                throw BusinessException.badRequest("INVALID_PARAMETERS", "draft 必须是 JSON 对象");
            }
            String slug = requiredText(node, "agent_slug");
            if (!slug.matches(AgentDefinition.SLUG_PATTERN)) {
                throw BusinessException.badRequest(
                        "INVALID_PARAMETERS", "agent_slug 必须匹配 ^[a-z][a-z0-9-]{0,63}$: " + slug);
            }
            return new Draft(
                    slug,
                    requiredText(node, "name"),
                    requiredText(node, "description"),
                    requiredText(node, "system_prompt"),
                    requiredText(node, "prompt_version"),
                    requiredText(node, "model_ref"),
                    optionalBoolean(node, "enabled", true),
                    stringArray(node, "tool_whitelist"),
                    optionalBoolean(node, "allow_write", false),
                    stringArray(node, "guard_exemptions"),
                    node.get("output_schema"),
                    AgentInputFormat.fromDb(optionalText(node, "input_format", AgentInputFormat.NATURAL_LANGUAGE.name())),
                    stringArray(node, "suggested_eval_cases"));
        }

        /** 布尔字段严格校验（拒绝字符串/数字静默强转，allow_write 判定语义）。 */
        private static boolean optionalBoolean(JsonNode node, String field, boolean fallback) {
            JsonNode value = node.get(field);
            if (value == null || value.isNull()) {
                return fallback;
            }
            if (!value.isBoolean()) {
                throw BusinessException.badRequest("INVALID_PARAMETERS", field + " 必须是布尔值");
            }
            return value.asBoolean();
        }

        AgentDefinition toDefinition(int version, AgentStatus status) {
            return AgentDefinition.of(
                    slug, name, description, systemPrompt, promptVersion, modelRef,
                    enabled, toolWhitelist, version, status, null, null,
                    allowWrite, guardExemptions, outputSchema, inputFormat);
        }

        /** 回写全量草稿 JSON（含服务端分配的版本/状态）。 */
        JsonNode outputJson(AgentDefinition definition, ObjectMapper mapper) {
            ObjectNode out = mapper.createObjectNode();
            out.put("agent_slug", definition.agentSlug());
            out.put("name", definition.name());
            out.put("description", definition.description());
            out.put("system_prompt", definition.systemPrompt());
            out.put("prompt_version", definition.promptVersion());
            out.put("model_ref", definition.modelRef());
            out.put("enabled", definition.enabled());
            ArrayNode tools = out.putArray("tool_whitelist");
            definition.toolNames().forEach(tools::add);
            out.put("allow_write", definition.allowWrite());
            ArrayNode exemptions = out.putArray("guard_exemptions");
            definition.guardExemptions().forEach(exemptions::add);
            out.set("output_schema", definition.outputSchema());
            out.put("input_format", definition.inputFormat().name());
            out.put("version", definition.version());
            out.put("status", definition.status().name());
            ArrayNode cases = out.putArray("suggested_eval_cases");
            suggestedEvalCases.forEach(cases::add);
            return out;
        }

        private static String requiredText(JsonNode node, String field) {
            String value = optionalText(node, field, null);
            if (value == null || value.isBlank()) {
                throw BusinessException.badRequest("INVALID_PARAMETERS", field + " 不能为空");
            }
            return value;
        }

        private static String optionalText(JsonNode node, String field, String fallback) {
            JsonNode value = node.get(field);
            if (value == null || value.isNull()) {
                return fallback;
            }
            if (!value.isTextual()) {
                throw BusinessException.badRequest("INVALID_PARAMETERS", field + " 必须是字符串");
            }
            return value.asText().strip();
        }

        private static List<String> stringArray(JsonNode node, String field) {
            JsonNode value = node.get(field);
            if (value == null || value.isNull()) {
                return List.of();
            }
            if (!value.isArray()) {
                throw BusinessException.badRequest("INVALID_PARAMETERS", field + " 必须是字符串数组");
            }
            List<String> result = new ArrayList<>();
            value.forEach(item -> {
                if (!item.isTextual()) {
                    throw BusinessException.badRequest("INVALID_PARAMETERS", field + " 每项必须是字符串");
                }
                result.add(item.asText().strip());
            });
            return List.copyOf(result);
        }
    }
}
