package cn.zimu.fulfillment.agent.meta;

import cn.zimu.fulfillment.agent.AgentDraftService;
import cn.zimu.fulfillment.agent.AgentFailureCode;
import cn.zimu.fulfillment.agent.AgentRunContext;
import cn.zimu.fulfillment.agent.AgentRunResult;
import cn.zimu.fulfillment.agent.AgentRuntimeFacade;
import cn.zimu.fulfillment.common.error.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 对话式创建 Agent（agent-console 06 / meta-agent 启用面）。
 *
 * <p>本服务是 meta-agent 的唯一人工入口。它做三件事，且**三件都不依赖模型的自我陈述**：
 * <ol>
 *   <li>把自然语言交给 meta-agent 跑一次（草稿由它的 create_agent_draft 工具落库）；</li>
 *   <li>**按数据库事实**判定结局——模型说「我建好了」不算数，库里查得到 draft 行才算；</li>
 *   <li>启用红线的事后核验：本次产生的定义行必须是 draft 且非 active。</li>
 * </ol>
 *
 * <p>为什么不信模型的自述：SUCCESS 与 NEEDS_INPUT 在自然语言里极易混淆（模型经常
 * 一边问问题一边宣称已完成）。以库里有没有草稿行为准，这个判定永远可复现。
 *
 * <p>平台红线在此重申并由代码保证：**meta-agent 只能写草稿，启用永远是人工在详情页做的。**
 * 本服务不提供任何启用路径，也不接受任何「创建并启用」参数。
 */
@Service
public class MetaAgentConversationService {

    /** 单次对话输入上限：超长输入既烧 token 又极少是真实需求，宁可让用户拆开说。 */
    public static final int MAX_MESSAGE_LENGTH = 4000;

    private final AgentRuntimeFacade facade;
    private final JdbcTemplate jdbc;

    public MetaAgentConversationService(AgentRuntimeFacade facade, JdbcTemplate jdbc) {
        this.facade = facade;
        this.jdbc = jdbc;
    }

    public MetaAgentOutcome converse(String message, String operator, String threadId) {
        String trimmed = message == null ? "" : message.strip();
        if (trimmed.isEmpty()) {
            throw BusinessException.badRequest("INVALID_PARAMETERS", "对话内容不能为空");
        }
        if (trimmed.length() > MAX_MESSAGE_LENGTH) {
            throw BusinessException.badRequest(
                    "INVALID_PARAMETERS", "对话内容超过 " + MAX_MESSAGE_LENGTH + " 字，请拆分描述");
        }

        AgentRunContext context = new AgentRunContext(
                threadId == null || threadId.isBlank() ? "meta-agent" : threadId.strip(),
                operator,
                null,
                null);
        AgentRunResult result = facade.invoke(AgentDraftService.META_AGENT_SLUG, trimmed, context);

        if (result.error() != null) {
            boolean rejected = AgentFailureCode.PII_GUARDED.name().equals(result.error());
            return new MetaAgentOutcome(
                    rejected ? MetaAgentOutcome.REJECTED : MetaAgentOutcome.FAILED,
                    result.runId(),
                    null,
                    null,
                    null,
                    List.of(),
                    rejected ? "输入命中隐私守卫（疑似包含个人信息），请去掉后重试" : null,
                    result.error(),
                    null);
        }

        JsonNode output = result.output();
        String slug = text(output, "agent_slug");
        List<String> questions = questions(output);

        // 事实优先：库里查得到草稿行才算建成，模型的自述不作数
        Map<String, Object> draft = slug == null ? null : findLatestDraft(slug);
        if (draft != null) {
            assertNotActivated(slug);
            return new MetaAgentOutcome(
                    MetaAgentOutcome.SUCCESS,
                    result.runId(),
                    slug,
                    (Integer) draft.get("version"),
                    (Boolean) draft.get("enabled"),
                    questions,
                    null,
                    null,
                    output);
        }
        if (!questions.isEmpty()) {
            return new MetaAgentOutcome(
                    MetaAgentOutcome.NEEDS_INPUT,
                    result.runId(), null, null, null, questions, null, null, output);
        }
        // 既没落草稿也没提问：按拒绝呈现，并把理由交给用户——空白结局最难处理
        String reason = text(output, "rejection_reason");
        return new MetaAgentOutcome(
                MetaAgentOutcome.REJECTED,
                result.runId(),
                slug,
                null,
                null,
                List.of(),
                reason == null ? "本次未产出草稿，也未提出澄清问题；请更具体地描述这个 Agent 的职责与可用工具" : reason,
                null,
                output);
    }

    // ------------------------------------------------------------------

    private Map<String, Object> findLatestDraft(String slug) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                SELECT version, enabled, status
                FROM app.agent_definitions
                WHERE agent_slug = ? AND status = 'draft'
                ORDER BY version DESC
                LIMIT 1
                """,
                slug);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /**
     * 启用红线的事后核验：meta-agent 一次运行都不该让任何定义变成 active。
     * {@link AgentDraftService} 结构上只插 draft 行，本断言是纵深防御——
     * 这条红线一旦被绕过，后果是「没人确认过的 Agent 在跑」，值得多一道检查。
     */
    private void assertNotActivated(String slug) {
        Long active = jdbc.queryForObject(
                "SELECT count(*) FROM app.agent_definitions WHERE agent_slug = ? AND status = 'active'",
                Long.class,
                slug);
        if (active != null && active > 0) {
            throw new IllegalStateException(
                    "平台红线被破坏：meta-agent 运行后出现 active 定义 slug=" + slug);
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText().trim() : null;
    }

    /** 澄清问题：兼容 questions / clarifying_questions 两种字段名（定义未固定 output_schema）。 */
    private static List<String> questions(JsonNode node) {
        if (node == null) {
            return List.of();
        }
        List<String> questions = new ArrayList<>();
        for (String field : List.of("questions", "clarifying_questions")) {
            JsonNode array = node.path(field);
            if (array.isArray()) {
                array.forEach(item -> {
                    if (item.isTextual() && !item.asText().isBlank()) {
                        questions.add(item.asText().trim());
                    }
                });
            }
        }
        return List.copyOf(questions);
    }
}
