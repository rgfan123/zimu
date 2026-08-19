package cn.zimu.fulfillment.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/**
 * Agent 定义的 DB 真源访问（meta-agent-platform-impl 02）：从 {@code app.agent_definitions}
 * 加载定义行构造不可变 {@link AgentDefinition}。注册表只关心「当前生效」的版本——
 * 部分唯一索引保证每 slug 至多一个 active 行，故按 {@code status='active'} 过滤即得
 * 每 slug 当前生效版本（version 链的完整历史由管理端点/评测按需直接查表）。
 *
 * <p>DB 不可用时加载抛异常 → 应用启动失败（fail-closed，不劣于既有「未注册=未启用」语义）。
 */
@Component
public class AgentDefinitionRepository {

    private static final String SELECT_BASE =
            "SELECT agent_slug, name, description, system_prompt, prompt_version, model_ref, "
                    + "enabled, version, status, activated_by, activated_at, allow_write, "
                    + "guard_exemptions::text, output_schema::text, tool_whitelist::text, input_format "
                    + "FROM app.agent_definitions ";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public AgentDefinitionRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    /** 加载全部 active 定义（每 slug 至多一行），按播种/创建顺序。 */
    public List<AgentDefinition> loadActive() {
        return jdbc.query(SELECT_BASE + "WHERE status = 'active' ORDER BY id", ROW_MAPPER);
    }

    /** 以当前 active 定义构造注册表。 */
    public AgentRegistry loadRegistry() {
        return new AgentRegistry(loadActive());
    }

    /**
     * 按 (agent_slug, version) 加载指定版本定义（09 票：QUALITY 评测按提交时冻结的版本取
     * 定义与用例集，保证可复现可回滚；全快照版本链行在表内恒在）。
     */
    public Optional<AgentDefinition> findVersion(String agentSlug, int version) {
        List<AgentDefinition> rows = jdbc.query(
                SELECT_BASE + "WHERE agent_slug = ? AND version = ?",
                ROW_MAPPER,
                agentSlug,
                version);
        return rows.stream().findFirst();
    }

    private final RowMapper<AgentDefinition> ROW_MAPPER = (ResultSet rs, int rowNum) ->
            AgentDefinition.of(
                    rs.getString("agent_slug"),
                    rs.getString("name"),
                    rs.getString("description"),
                    rs.getString("system_prompt"),
                    rs.getString("prompt_version"),
                    rs.getString("model_ref"),
                    rs.getBoolean("enabled"),
                    readStringList(rs.getString("tool_whitelist")),
                    rs.getInt("version"),
                    AgentStatus.fromDb(rs.getString("status")),
                    rs.getString("activated_by"),
                    rs.getObject("activated_at", OffsetDateTime.class),
                    rs.getBoolean("allow_write"),
                    readStringList(rs.getString("guard_exemptions")),
                    readJson(rs.getString("output_schema")),
                    AgentInputFormat.fromDb(rs.getString("input_format")));

    private List<String> readStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(
                    json,
                    mapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception ex) {
            throw new IllegalStateException("agent_definitions JSON 数组解析失败: " + json, ex);
        }
    }

    private JsonNode readJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return mapper.readTree(json);
        } catch (Exception ex) {
            throw new IllegalStateException("agent_definitions JSONB 解析失败: " + json, ex);
        }
    }
}
