package cn.zimu.fulfillment.agent;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zimu.fulfillment.agent.procurement.ProcurementPriceEvalFixture;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Agent 平台化 T01 —— V33 播种与代码定义的逐字对照（Testcontainers，真实 Flyway 链）。
 *
 * <p>expand 阶段代码定义与 DB 播种并行存在，二者一旦漂移，后续「注册表切 DB 真源」就会
 * 静默改变运行行为。本类把「播种 = 代码常量」钉成断言：三个代码 Agent 的 8 个定义字段
 * 逐字相等，meta-agent 作为唯一无代码定义的播种行（{@code allow_write=true}），19 例评测
 * 用例按 metric_kind 与 fixture 内容逐条对照（01 票：采购比价 12 例 = 既有 7 例 + 不可比
 * 候选剔除 5 例，版本 procurement-eval-v2）。
 *
 * <p>{@code app.message-interpreter.prompt-version} 显式置空：意图识别的 prompt_version
 * 在代码里由该配置镜像、未配置时回退到固定版本，播种取的是回退值；测试固定该输入，
 * 使对照不受运行环境影响（该耦合在注册表切 DB 后消失）。
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
            "app.message-worker.enabled=false",
            "app.mcp.enabled=false",
            "app.message-interpreter.prompt-version="
        })
class AgentDefinitionSeedParityTest {

    private static final String META_AGENT = "meta-agent";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AgentRegistry registry;

    @Autowired
    private ObjectMapper mapper;

    @Test
    void seededDefinitionsMatchCodeConstantsVerbatim() {
        Map<String, SeededDefinition> seeded = seededDefinitions();

        assertThat(seeded.keySet())
                .containsExactlyInAnyOrderElementsOf(withMetaAgent(registry.slugs()));

        for (AgentDefinition code : registry.definitions()) {
            SeededDefinition row = seeded.get(code.agentSlug());
            assertThat(row).as("代码定义 %s 必须有播种行", code.agentSlug()).isNotNull();
            assertThat(row.version()).as("%s 的生效版本号", code.agentSlug()).isPositive();
            assertThat(row.status()).isEqualTo("active");
            assertThat(row.name()).isEqualTo(code.name());
            assertThat(row.description()).isEqualTo(code.description());
            assertThat(row.systemPrompt()).isEqualTo(code.systemPrompt());
            assertThat(row.promptVersion()).isEqualTo(code.promptVersion());
            assertThat(row.modelRef()).isEqualTo(code.modelRef());
            assertThat(row.enabled()).isEqualTo(code.enabled());
            assertThat(row.toolWhitelist()).isEqualTo(code.toolNames());
            assertThat(row.allowWrite()).as("业务 Agent 不得带写权限").isFalse();
        }
    }

    @Test
    void metaAgentIsTheOnlySeededWriteAgentAndHasNoCodeDefinition() {
        SeededDefinition meta = seededDefinitions().get(META_AGENT);

        assertThat(registry.has(META_AGENT)).as("meta-agent 只存在于播种，不在代码定义里").isFalse();
        assertThat(meta.allowWrite()).isTrue();
        assertThat(meta.status()).isEqualTo("active");
        assertThat(meta.toolWhitelist())
                .containsExactly("list_agent_tools", "create_agent_draft", "update_agent_draft");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM app.agent_definitions WHERE allow_write", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void everySeededDefinitionCarriesAnOutputSchemaAndNoGuardExemption() {
        for (SeededDefinition row : seededDefinitions().values()) {
            assertThat(row.outputSchemaType()).as("%s 必须携带对象型 output_schema", row.slug()).isEqualTo("object");
            assertThat(row.guardExemptions()).as("%s 默认不豁免任何守卫", row.slug()).isEmpty();
        }
    }

    @Test
    void procurementEvalCasesAreSeededFromTheFixtureVerbatim() {
        Map<String, SeededEvalCase> seeded = seededEvalCases("procurement-price-agent");

        assertThat(seeded).hasSize(ProcurementPriceEvalFixture.CASES.size());
        for (ProcurementPriceEvalFixture.EvalCase expected : ProcurementPriceEvalFixture.CASES) {
            SeededEvalCase row = seeded.get(expected.id());
            assertThat(row).as("评测用例 %s 必须播种", expected.id()).isNotNull();
            assertThat(row.evalSetVersion()).isEqualTo(ProcurementPriceEvalFixture.VERSION);
            assertThat(row.metricKind()).isEqualTo("INVARIANT");
            assertThat(row.status()).isEqualTo("CONFIRMED");
            assertThat(row.agentVersion())
                    .as("用例绑定当前生效版本")
                    .isEqualTo(activeVersion("procurement-price-agent"));
            assertThat(json(row.input()).get("model_output").asText()).isEqualTo(expected.modelOutput());
            assertThat(json(row.input()).get("input")).isEqualTo(json(expected.inputJson()));
            assertThat(json(row.expected()).get("requires_human").asBoolean())
                    .isEqualTo(expected.expectRequiresHuman());
            assertThat(json(row.expected()).get("write_tool_calls").asInt()).isZero();
            if (expected.expectMissingContain() != null) {
                assertThat(json(row.expected()).get("missing_fields_contains").asText())
                        .isEqualTo(expected.expectMissingContain());
            }
        }
    }

    @Test
    void dataQueryEvalCasesCoverEveryFixtureQueryWithItsGatePath() {
        Map<String, SeededEvalCase> seeded = seededEvalCases("data-query-agent");

        assertThat(seeded).hasSize(DataQueryAgentEvalFixture.ALL_QUERIES.size());
        List<String> seededQuestions = new ArrayList<>();
        for (SeededEvalCase row : seeded.values()) {
            assertThat(row.evalSetVersion()).isEqualTo(DataQueryAgentEvalFixture.VERSION);
            assertThat(row.metricKind()).isEqualTo("INVARIANT");
            assertThat(row.status()).isEqualTo("CONFIRMED");
            String question = json(row.input()).get("question").asText();
            seededQuestions.add(question);

            JsonNode expected = json(row.expected());
            if (DataQueryAgentEvalFixture.EXPECT_ANSWER.contains(question)) {
                assertThat(expected.get("requires_human").asBoolean()).isFalse();
                assertThat(expected.get("tool_sequence").get(0).asText())
                        .isEqualTo(DataQueryAgentEvalFixture.expectedTool(question));
                assertThat(expected.get("answer_contains")).isNotEmpty();
            } else {
                assertThat(expected.get("requires_human").asBoolean()).isTrue();
                assertThat(expected.get("tool_sequence")).isEmpty();
                assertThat(expected.get("clarification_needed").asBoolean())
                        .isEqualTo(DataQueryAgentEvalFixture.EXPECT_CLARIFICATION.contains(question));
            }
        }
        assertThat(seededQuestions)
                .containsExactlyInAnyOrderElementsOf(DataQueryAgentEvalFixture.ALL_QUERIES);
    }

    @Test
    void seededEvalCasesAreExactlyTheNineteenFixtureCases() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app.agent_eval_cases", Integer.class))
                .isEqualTo(26);
        assertThat(jdbc.queryForList(
                        "SELECT DISTINCT metric_kind FROM app.agent_eval_cases", String.class))
                .containsExactly("INVARIANT");
    }

    @Test
    void versionChainIsAppendOnlyWithAtMostOneActivePerSlug() {
        // append-only 全快照（票 03）：定义变更走新版本，不原地改 active 行。
        // 原地刷新会让版本链失去意义——改完之后没有记录能说明旧版本长什么样，
        // 也无法按「复制旧版本为新草稿」的既定路径回滚。
        assertThat(jdbc.queryForList(
                        """
                        SELECT agent_slug FROM app.agent_definitions
                        WHERE status = 'active' GROUP BY agent_slug HAVING count(*) > 1
                        """,
                        String.class))
                .as("每个 slug 至多一个生效版本")
                .isEmpty();

        // 退役版本必须留在链上，且其冻结的评测用例不得被删除（票 07：换例 = 换版本）
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*) FROM app.agent_definitions
                        WHERE agent_slug = 'procurement-price-agent' AND version = 1 AND status = 'retired'
                        """,
                        Integer.class))
                .as("v1 退役后仍留在版本链上")
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*) FROM app.agent_eval_cases
                        WHERE agent_slug = 'procurement-price-agent' AND agent_version = 1
                        """,
                        Integer.class))
                .as("v1 冻结的评测用例原样保留")
                .isEqualTo(7);
    }

    // ------------------------------------------------------------------

    private int activeVersion(String agentSlug) {
        return jdbc.queryForObject(
                "SELECT version FROM app.agent_definitions WHERE agent_slug = ? AND status = 'active'",
                Integer.class,
                agentSlug);
    }

    private static List<String> withMetaAgent(Iterable<String> slugs) {
        List<String> all = new ArrayList<>();
        slugs.forEach(all::add);
        all.add(META_AGENT);
        return all;
    }

    private Map<String, SeededDefinition> seededDefinitions() {
        Map<String, SeededDefinition> byslug = new LinkedHashMap<>();
        jdbc.query(
                """
                SELECT agent_slug, version, name, description, system_prompt, prompt_version, model_ref,
                       enabled, tool_whitelist, jsonb_typeof(output_schema) AS output_schema_type,
                       allow_write, guard_exemptions, status
                FROM app.agent_definitions WHERE status = 'active' ORDER BY agent_slug
                """,
                rs -> {
                    SeededDefinition row = new SeededDefinition(
                            rs.getString("agent_slug"),
                            rs.getInt("version"),
                            rs.getString("name"),
                            rs.getString("description"),
                            rs.getString("system_prompt"),
                            rs.getString("prompt_version"),
                            rs.getString("model_ref"),
                            rs.getBoolean("enabled"),
                            strings(rs, "tool_whitelist"),
                            rs.getString("output_schema_type"),
                            rs.getBoolean("allow_write"),
                            strings(rs, "guard_exemptions"),
                            rs.getString("status"));
                    byslug.put(row.slug(), row);
                });
        return byslug;
    }

    private Map<String, SeededEvalCase> seededEvalCases(String agentSlug) {
        Map<String, SeededEvalCase> byKey = new LinkedHashMap<>();
        jdbc.query(
                """
                SELECT case_key, agent_version, eval_set_version, metric_kind,
                       input::text AS input, expected::text AS expected, status
                FROM app.agent_eval_cases
                WHERE agent_slug = ?
                  AND agent_version = (SELECT version FROM app.agent_definitions
                                       WHERE agent_slug = ? AND status = 'active')
                ORDER BY id
                """,
                rs -> {
                    SeededEvalCase row = new SeededEvalCase(
                            rs.getString("case_key"),
                            rs.getInt("agent_version"),
                            rs.getString("eval_set_version"),
                            rs.getString("metric_kind"),
                            rs.getString("input"),
                            rs.getString("expected"),
                            rs.getString("status"));
                    byKey.put(row.caseKey(), row);
                },
                agentSlug, agentSlug);
        return byKey;
    }

    private static List<String> strings(ResultSet rs, String column) throws SQLException {
        Array array = rs.getArray(column);
        return array == null ? List.of() : List.of((String[]) array.getArray());
    }

    private JsonNode json(String raw) {
        try {
            return mapper.readTree(raw);
        } catch (Exception e) {
            throw new IllegalStateException("播种 JSON 不可解析: " + raw, e);
        }
    }

    private record SeededDefinition(
            String slug,
            int version,
            String name,
            String description,
            String systemPrompt,
            String promptVersion,
            String modelRef,
            boolean enabled,
            List<String> toolWhitelist,
            String outputSchemaType,
            boolean allowWrite,
            List<String> guardExemptions,
            String status) {}

    private record SeededEvalCase(
            String caseKey,
            int agentVersion,
            String evalSetVersion,
            String metricKind,
            String input,
            String expected,
            String status) {}
}
