package cn.zimu.fulfillment.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.zimu.fulfillment.agent.procurement.ProcurementPriceEvalFixture;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * meta-agent-platform-impl 票 01 + 02：V33 迁移种子与注册表 DB 真源的整合测试。
 *
 * <p>真实 PostgreSQL（Testcontainers，{@link AgentTestcontainersBase}）+ 完整应用启动
 * （Flyway 执行全部迁移）后，断言：
 * <ul>
 *   <li>DB 是定义唯一真源：上下文无代码定义 bean 残留（T02），active 种子恰为 5 个
 *       （procurement-price-agent / data-query-agent / intent-recognition / meta-agent /
 *       source-sync-reviewer），
 *       procurement-price-agent version=2、其余 version=1、status='active'、allow_write 仅 meta-agent 为 true、守卫豁免为空，
 *       注册表（holder 当前实例）按 status='active' AND enabled=true 可解析全部 slug；</li>
 *   <li>meta-agent 播种行：version=1、status='active'、allow_write=true、enabled=true、
 *       白名单 = [list_agent_tools, create_agent_draft, update_agent_draft]（06/08 决策）；</li>
 *   <li>历史与 active 共 26 例评测用例；active 集合为 procurement-eval-v2 12 例 +
 *       data-query-eval-v1 7 例，旧 procurement v1 七例继续冻结保留；</li>
 *   <li>结构约束落地：部分唯一索引（每 slug 至多一个 active）、agent_runs 新列与默认值、
 *       agent_eval_cases 外键。</li>
 * </ul>
 */
class AgentPlatformSeedVerbatimTest extends AgentTestcontainersBase {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private record DefinitionRow(
            String slug,
            String name,
            String description,
            String systemPrompt,
            String promptVersion,
            String modelRef,
            boolean enabled,
            int version,
            String status,
            boolean allowWrite,
            List<String> toolWhitelist,
            List<String> guardExemptions) {}

    private record EvalCaseRow(
            String agentSlug, int agentVersion, String metricKind, String status,
            JsonNode input, JsonNode expected) {}

    // ------------------------------------------------------------------
    // 种子定义 ↔ DB 唯一真源（T02 后代码定义 bean 已删除）
    // ------------------------------------------------------------------

    @Test
    void dbIsTheOnlyDefinitionSourceNoCodeDefinitionBeansRemain() {
        // T02：三个代码定义 Configuration 已删，注册表从 DB 加载——上下文不得再有定义 bean
        for (String beanName : new String[] {
                "procurementPriceAgentDefinition", "dataQueryAgentDefinition", "intentRecognitionAgentDefinition"}) {
            assertThatThrownBy(() -> context.getBean(beanName))
                    .as("代码定义 bean %s 必须已删除", beanName)
                    .isInstanceOf(org.springframework.beans.factory.NoSuchBeanDefinitionException.class);
        }
        // 种子为唯一来源：active 定义恰为 5 个，身份与版本链字段与迁移播种一致
        List<DefinitionRow> active = jdbc.query(
                "SELECT agent_slug, name, description, system_prompt, prompt_version, model_ref, "
                        + "enabled, version, status, allow_write, tool_whitelist::text, guard_exemptions::text "
                        + "FROM app.agent_definitions WHERE status = 'active' ORDER BY id",
                (rs, i) -> row(rs));
        assertThat(active).extracting(DefinitionRow::slug)
                .containsExactlyInAnyOrder(
                        "procurement-price-agent", "data-query-agent", "intent-recognition", "meta-agent",
                        "source-sync-reviewer");
        assertThat(active).allSatisfy(row -> {
            assertThat(row.version()).isEqualTo("procurement-price-agent".equals(row.slug()) ? 2 : 1);
            assertThat(row.status()).isEqualTo("active");
            assertThat(row.enabled()).isTrue();
            assertThat(row.allowWrite()).as("仅 meta-agent 允许写（%s）", row.slug())
                    .isEqualTo("meta-agent".equals(row.slug()));
            assertThat(row.guardExemptions()).isEmpty();
            assertThat(row.systemPrompt()).isNotBlank();
            if ("intent-recognition".equals(row.slug())) {
                assertThat(row.toolWhitelist()).as("意图识别无工具调用（单次分类接缝）").isEmpty();
            } else {
                assertThat(row.toolWhitelist()).isNotEmpty();
            }
        });
        // 运行条件 status='active' AND enabled=true：注册表（holder 当前实例）可解析全部 5 slug
        AgentRegistryHolder holder = context.getBean(AgentRegistryHolder.class);
        assertThat(holder.current().slugs())
                .containsExactlyInAnyOrder(
                        "procurement-price-agent", "data-query-agent", "intent-recognition", "meta-agent",
                        "source-sync-reviewer");
        assertThat(holder.current().isEnabled("procurement-price-agent")).isTrue();
        assertThat(holder.current().isEnabled("meta-agent")).isTrue();
    }

    @Test
    void metaAgentSeedIsActiveWriteAllowed() {
        DefinitionRow row = loadDefinition("meta-agent");
        assertThat(row.slug()).isEqualTo("meta-agent");
        assertThat(row.version()).isEqualTo(1);
        assertThat(row.status()).isEqualTo("active");
        assertThat(row.enabled()).isTrue();
        assertThat(row.allowWrite()).as("meta-agent 是唯一 allow_write=true 的种子（08 决策 3）").isTrue();
        assertThat(row.toolWhitelist())
                .containsExactly("list_agent_tools", "create_agent_draft", "update_agent_draft");
        assertThat(row.guardExemptions()).isEmpty();
    }

    @Test
    void sourceSyncReviewerSeedIsReadOnlyAdvisoryAndUnique() {
        DefinitionRow row = loadDefinition("source-sync-reviewer");
        assertThat(row.version()).isEqualTo(1);
        assertThat(row.status()).isEqualTo("active");
        assertThat(row.enabled()).isTrue();
        assertThat(row.allowWrite()).isFalse();
        assertThat(row.toolWhitelist()).containsExactly("check_shipment_source_sync");
        assertThat(row.guardExemptions())
                .as("source-sync reviewer must not gain a blanket PII exemption")
                .isEmpty();
        assertThat(row.systemPrompt())
                .contains("只读", "建议", "不得执行回传", "不得对账");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM app.agent_definitions "
                                + "WHERE agent_slug='source-sync-reviewer' AND version=1",
                        Long.class))
                .as("Flyway/restart replay must retain exactly one seeded definition")
                .isEqualTo(1L);
    }

    // ------------------------------------------------------------------
    // 26 例历史评测用例播种（INVARIANT / CONFIRMED）
    // ------------------------------------------------------------------

    @Test
    void invariantEvalCasesKeepHistoryAndActiveVersion() {
        List<EvalCaseRow> rows = jdbc.query(
                "SELECT agent_slug, agent_version, metric_kind, status, input::text, expected::text "
                        + "FROM app.agent_eval_cases ORDER BY id",
                (rs, i) -> new EvalCaseRow(
                        rs.getString("agent_slug"),
                        rs.getInt("agent_version"),
                        rs.getString("metric_kind"),
                        rs.getString("status"),
                        parse(rs.getString("input")),
                        parse(rs.getString("expected"))));
        assertThat(rows).hasSize(26);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.metricKind()).isEqualTo("INVARIANT");
            assertThat(row.status()).isEqualTo("CONFIRMED");
        });
    }

    @Test
    void activeEvalCaseSeedMatchesCurrentDesignedCases() {
        // 只核对各 slug 的 active 版本；旧版本用例由总数与外键/版本链测试保证继续保留。
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT c.agent_slug, c.input::text, c.expected::text FROM app.agent_eval_cases c "
                        + "JOIN app.agent_definitions d ON d.agent_slug=c.agent_slug "
                        + "AND d.version=c.agent_version AND d.status='active' "
                        + "WHERE c.metric_kind = 'INVARIANT' AND c.status = 'CONFIRMED' "
                        + "ORDER BY c.agent_slug, c.id");
        assertThat(rows).hasSize(19);

        List<String> procurementInputs = normalizedInputs(rows, "procurement-price-agent");
        assertThat(procurementInputs).hasSize(ProcurementPriceEvalFixture.CASES.size());
        assertThat(procurementInputs).containsExactlyInAnyOrderElementsOf(
                ProcurementPriceEvalFixture.CASES.stream().map(c -> norm(c.inputJson())).toList());

        List<String> dataQueryInputs = normalizedInputs(rows, "data-query-agent");
        assertThat(dataQueryInputs).hasSize(7);
        assertThat(dataQueryInputs).containsExactlyInAnyOrder(
                DataQueryEvalInputs.Q_7D_OUT_OF_STOCK,
                DataQueryEvalInputs.Q_SKU_PLACEHOLDER,
                DataQueryEvalInputs.Q_TICKET_NO_PLACEHOLDER,
                DataQueryEvalInputs.Q_PROVIDER_AMBIGUOUS,
                DataQueryEvalInputs.Q_SKU_CONCRETE,
                DataQueryEvalInputs.Q_TICKET_CONCRETE,
                DataQueryEvalInputs.Q_PII_RECEIVER);

        // 关键 expected 形态：负例 expected_error、可答 tool_sequence、门禁 requires_human=true
        Map<String, JsonNode> expectedByInput = new java.util.HashMap<>();
        for (Map<String, Object> row : rows) {
            expectedByInput.put(canonical(parse((String) row.get("input"))), parse((String) row.get("expected")));
        }
        assertThat(expectedByInput.get(norm("{\"sku_id\":\"1002\"}")).path("expected_error").asText())
                .isEqualTo("AGENT_OUTPUT_INVALID");
        assertThat(expectedByInput.get(DataQueryEvalInputs.Q_TICKET_CONCRETE).path("tool_sequence").get(0).asText())
                .isEqualTo("get_procurement_ticket");
        assertThat(expectedByInput.get(DataQueryEvalInputs.Q_PII_RECEIVER).path("requires_human").asBoolean())
                .isTrue();
    }

    private static List<String> normalizedInputs(List<Map<String, Object>> rows, String slug) {
        return rows.stream()
                .filter(r -> slug.equals(r.get("agent_slug")))
                .map(r -> canonical(parse((String) r.get("input"))))
                .toList();
    }

    /** 规范形：对象键递归排序后序列化（jsonb 按键长排序、Jackson 保留解析序，统一到键排序消除两处差异）。 */
    private static String canonical(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted = MAPPER.createObjectNode();
            List<String> names = new java.util.ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            names.sort(String::compareTo);
            for (String name : names) {
                sorted.set(name, parse(canonical(node.get(name))));
            }
            return sorted.toString();
        }
        if (node.isArray()) {
            StringBuilder out = new StringBuilder("[");
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                out.append(canonical(node.get(i)));
            }
            return out.append(']').toString();
        }
        // 文本节点（数据查询问题）取原文不带引号，与字面量直接可比
        return node.isTextual() ? node.asText() : node.toString();
    }

    private static String norm(String json) {
        return canonical(parse(json));
    }

    // ------------------------------------------------------------------
    // 结构约束落地
    // ------------------------------------------------------------------

    @Test
    void partialUniqueIndexEnforcesSingleActivePerSlug() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO app.agent_definitions ("
                        + "agent_slug, name, description, system_prompt, prompt_version, model_ref, "
                        + "enabled, version, status, allow_write, guard_exemptions, tool_whitelist) "
                        + "VALUES ('data-query-agent', 'x', 'x', 'x', 'x', 'x', "
                        + "true, 2, 'active', false, '[]'::jsonb, '[]'::jsonb)"))
                .as("同一 slug 已存在 active 版本时，部分唯一索引必须拒绝第二个 active 行")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void agentRunsGainedRunModeIntentProviderColumns() {
        for (String column : new String[] {"run_mode", "intent", "provider"}) {
            Integer count = jdbc.queryForObject(
                    "SELECT count(*) FROM information_schema.columns "
                            + "WHERE table_schema = 'app' AND table_name = 'agent_runs' AND column_name = ?",
                    Integer.class,
                    column);
            assertThat(count).as("agent_runs 应含 %s 列", column).isEqualTo(1);
        }
        String runModeDefault = jdbc.queryForObject(
                "SELECT column_default FROM information_schema.columns "
                        + "WHERE table_schema = 'app' AND table_name = 'agent_runs' AND column_name = 'run_mode'",
                String.class);
        assertThat(runModeDefault).as("run_mode 默认 LIVE（既有运行记录语义不变）").contains("LIVE");
        Integer runModeCheck = jdbc.queryForObject(
                "SELECT count(*) FROM pg_constraint c "
                        + "JOIN pg_class t ON t.oid = c.conrelid "
                        + "JOIN pg_namespace n ON n.oid = t.relnamespace "
                        + "WHERE n.nspname = 'app' AND t.relname = 'agent_runs' "
                        + "AND c.conname = 'agent_runs_run_mode_check'",
                Integer.class);
        assertThat(runModeCheck).isEqualTo(1);
    }

    @Test
    void evalCaseForeignKeyRejectsUnknownDefinition() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO app.agent_eval_cases ("
                        + "agent_slug, agent_version, metric_kind, input, expected, status, created_by) "
                        + "VALUES ('no-such-agent', 1, 'INVARIANT', '{}'::jsonb, '{}'::jsonb, 'PENDING', 'system')"))
                .as("评测用例必须绑定已存在的 (agent_slug, agent_version)")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ------------------------------------------------------------------
    // 读取辅助
    // ------------------------------------------------------------------

    private static DefinitionRow loadDefinition(String slug) {
        return jdbc.queryForObject(
                "SELECT agent_slug, name, description, system_prompt, prompt_version, model_ref, "
                        + "enabled, version, status, allow_write, tool_whitelist::text, guard_exemptions::text "
                        + "FROM app.agent_definitions WHERE agent_slug = ? AND version = 1",
                (rs, i) -> row(rs),
                slug);
    }

    private static DefinitionRow row(ResultSet rs) throws SQLException {
        return new DefinitionRow(
                rs.getString("agent_slug"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("system_prompt"),
                rs.getString("prompt_version"),
                rs.getString("model_ref"),
                rs.getBoolean("enabled"),
                rs.getInt("version"),
                rs.getString("status"),
                rs.getBoolean("allow_write"),
                readStringList(rs.getString("tool_whitelist")),
                readStringList(rs.getString("guard_exemptions")));
    }

    private static List<String> readStringList(String json) {
        return MAPPER.convertValue(parse(json), MAPPER.getTypeFactory().constructCollectionType(List.class, String.class));
    }

    private static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception ex) {
            throw new IllegalStateException("JSON 解析失败: " + json, ex);
        }
    }
}
