package cn.zimu.fulfillment.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.zimu.fulfillment.FulfillmentHubApplication;
import cn.zimu.fulfillment.agent.procurement.ProcurementPriceEvalFixture;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * meta-agent-platform-impl 票 01 + 02：V33 迁移种子与注册表 DB 真源的整合测试。
 *
 * <p>真实 PostgreSQL（Testcontainers）+ 完整应用启动（Flyway 执行全部迁移）后，断言：
 * <ul>
 *   <li>DB 是定义唯一真源：上下文无代码定义 bean 残留（T02），active 种子恰为 4 个
 *       （procurement-price-agent / data-query-agent / intent-recognition / meta-agent），
 *       version=1、status='active'、allow_write 仅 meta-agent 为 true、守卫豁免为空，
 *       注册表（holder 当前实例）按 status='active' AND enabled=true 可解析全部 slug；</li>
 *   <li>meta-agent 播种行：version=1、status='active'、allow_write=true、enabled=true、
 *       白名单 = [list_agent_tools, create_agent_draft, update_agent_draft]（06/08 决策）；</li>
 *   <li>14 例评测用例（procurement-eval-v1 7 例 + data-query-eval-v1 7 条）按 metric_kind=
 *       INVARIANT 播种为 CONFIRMED，input/expected 与代码 fixture 逐字一致；</li>
 *   <li>结构约束落地：部分唯一索引（每 slug 至多一个 active）、agent_runs 新列与默认值、
 *       agent_eval_cases 外键。</li>
 * </ul>
 */
@Testcontainers
class AgentPlatformSeedVerbatimTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ConfigurableApplicationContext context;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void boot() {
        String[] properties = {
            "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
            "--spring.datasource.username=" + POSTGRES.getUsername(),
            "--spring.datasource.password=" + POSTGRES.getPassword(),
            "--spring.data.redis.repositories.enabled=false",
            "--spring.main.banner-mode=off"
        };
        context = new SpringApplicationBuilder(FulfillmentHubApplication.class)
                .web(WebApplicationType.NONE)
                .run(properties);
        jdbc = context.getBean(JdbcTemplate.class);
    }

    @AfterAll
    static void close() {
        if (context != null) {
            context.close();
        }
    }

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
        // 种子为唯一来源：active 定义恰为 4 个，身份与版本链字段与 V33 播种一致
        List<DefinitionRow> active = jdbc.query(
                "SELECT agent_slug, name, description, system_prompt, prompt_version, model_ref, "
                        + "enabled, version, status, allow_write, tool_whitelist::text, guard_exemptions::text "
                        + "FROM app.agent_definitions WHERE status = 'active' ORDER BY id",
                (rs, i) -> row(rs));
        assertThat(active).extracting(DefinitionRow::slug)
                .containsExactly("procurement-price-agent", "data-query-agent", "intent-recognition", "meta-agent");
        assertThat(active).allSatisfy(row -> {
            assertThat(row.version()).isEqualTo(1);
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
        // 运行条件 status='active' AND enabled=true：注册表（holder 当前实例）可解析全部 4 slug
        AgentRegistryHolder holder = context.getBean(AgentRegistryHolder.class);
        assertThat(holder.current().slugs())
                .containsExactlyInAnyOrder("procurement-price-agent", "data-query-agent", "intent-recognition", "meta-agent");
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

    // ------------------------------------------------------------------
    // 14 例评测用例播种（INVARIANT / CONFIRMED）
    // ------------------------------------------------------------------

    @Test
    void fourteenInvariantEvalCasesSeededConfirmed() {
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
        assertThat(rows).hasSize(14);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.metricKind()).isEqualTo("INVARIANT");
            assertThat(row.status()).isEqualTo("CONFIRMED");
        });
    }

    @Test
    void procurementEvalCasesMirrorFixture() {
        List<EvalCaseRow> rows = evalCases("procurement-price-agent");
        assertThat(rows).hasSize(ProcurementPriceEvalFixture.CASES.size());
        for (int i = 0; i < ProcurementPriceEvalFixture.CASES.size(); i++) {
            ProcurementPriceEvalFixture.EvalCase fixture = ProcurementPriceEvalFixture.CASES.get(i);
            EvalCaseRow row = rows.get(i);
            assertThat(row.agentVersion()).isEqualTo(1);
            assertThat(parse(fixture.inputJson())).as("input 逐字一致（%s）", fixture.id())
                    .isEqualTo(row.input());
            assertThat(expectedForProcurement(fixture)).as("expected 一致（%s）", fixture.id())
                    .isEqualTo(row.expected());
        }
    }

    @Test
    void dataQueryEvalCasesMirrorFixture() {
        List<EvalCaseRow> rows = evalCases("data-query-agent");
        assertThat(rows).hasSize(DataQueryAgentEvalFixture.ALL_QUERIES.size());
        for (int i = 0; i < DataQueryAgentEvalFixture.ALL_QUERIES.size(); i++) {
            String question = DataQueryAgentEvalFixture.ALL_QUERIES.get(i);
            EvalCaseRow row = rows.get(i);
            assertThat(row.agentVersion()).isEqualTo(1);
            assertThat(JsonNodeFactory.instance.textNode(question)).as("input 逐字一致（%s）", question)
                    .isEqualTo(row.input());
            assertThat(expectedForDataQuery(question)).as("expected 一致（%s）", question)
                    .isEqualTo(row.expected());
        }
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

    private static List<EvalCaseRow> evalCases(String slug) {
        return jdbc.query(
                "SELECT agent_slug, agent_version, metric_kind, status, input::text, expected::text "
                        + "FROM app.agent_eval_cases WHERE agent_slug = ? ORDER BY id",
                (rs, i) -> new EvalCaseRow(
                        rs.getString("agent_slug"),
                        rs.getInt("agent_version"),
                        rs.getString("metric_kind"),
                        rs.getString("status"),
                        parse(rs.getString("input")),
                        parse(rs.getString("expected"))),
                slug);
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

    private static JsonNode expectedForProcurement(ProcurementPriceEvalFixture.EvalCase fixture) {
        ObjectNode expected = MAPPER.createObjectNode();
        if ("schema-invalid-output".equals(fixture.id())) {
            expected.put("expected_error", "AGENT_OUTPUT_INVALID");
        } else {
            expected.put("requires_human", fixture.expectRequiresHuman());
            if (fixture.expectMissingContain() != null) {
                expected.putArray("missing_fields").add(fixture.expectMissingContain());
            }
        }
        return expected;
    }

    private static JsonNode expectedForDataQuery(String question) {
        ObjectNode expected = MAPPER.createObjectNode();
        String tool = switch (question) {
            case DataQueryAgentEvalFixture.Q_7D_OUT_OF_STOCK -> "list_procurement_tickets";
            case DataQueryAgentEvalFixture.Q_SKU_CONCRETE -> "search_skus";
            case DataQueryAgentEvalFixture.Q_TICKET_CONCRETE -> "get_procurement_ticket";
            default -> null;
        };
        if (tool != null) {
            expected.put("requires_human", false);
            expected.putArray("tool_sequence").add(tool);
        } else {
            expected.put("requires_human", true);
        }
        return expected;
    }
}
