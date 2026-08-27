package cn.zimu.fulfillment.connector.wecom;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 会话级回复策略：同一个机器人在客户群要闭嘴、在个人助手会话可自由应答。
 *
 * <p>策略只约束「对话性」出口——泛文本回执（已接收）、追问缺失信息的草稿卡。
 * 业务投递（文件回执、回填文件、发货清单、业务确认卡、卡片点击回执）不在管辖范围：
 * 它们是用户明确要的「特定回执或回填文件」，静默模式也照发。
 *
 * <p>缺省（无策略行）= FULL：与既有行为一致，配置是收紧而不是放开。
 */
@Service
public class WecomChatReplyPolicyService {

    public static final String MODE_FULL = "FULL";
    public static final String MODE_RECEIPTS_ONLY = "RECEIPTS_ONLY";

    private final JdbcTemplate jdbc;

    public WecomChatReplyPolicyService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 该会话是否允许对话性回复（泛回执/追问卡）。查不到策略即允许。 */
    public boolean allowsConversational(String chatId) {
        if (chatId == null || chatId.isBlank()) {
            return true;
        }
        List<String> modes = jdbc.query(
                "SELECT reply_mode FROM app.wecom_chat_reply_policies WHERE chat_id = ?",
                (rs, rowNum) -> rs.getString(1),
                chatId);
        return modes.isEmpty() || MODE_FULL.equals(modes.getFirst());
    }

    /** 会话当前绑定的 Agent；无策略、空绑定或非法空白均视为未绑定。 */
    public Optional<String> assignedAgent(String chatId) {
        return assignedAgent(chatId, null);
    }

    /**
     * 会话当前绑定的 Agent。企微单聊入站证据使用 {@code single:userid} 防空值，
     * 会话目录/主动发送使用真实 userid；这里在边界统一为目录键。
     */
    public Optional<String> assignedAgent(String chatId, String chatType) {
        if (chatId == null || chatId.isBlank()) {
            return Optional.empty();
        }
        String policyChatId = outboundChatId(chatId, chatType);
        List<String> slugs = jdbc.query(
                "SELECT agent_slug FROM app.wecom_chat_reply_policies WHERE chat_id = ?",
                (rs, rowNum) -> rs.getString(1),
                policyChatId);
        if (slugs.isEmpty() || slugs.getFirst() == null || slugs.getFirst().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(slugs.getFirst().strip());
    }

    /** 企微主动发送目标：单聊去掉证据层 {@code single:} 前缀，群聊保持原 chatid。 */
    public String outboundChatId(String chatId, String chatType) {
        if ("single".equals(chatType) && chatId != null && chatId.startsWith("single:")) {
            return chatId.substring("single:".length());
        }
        return chatId;
    }

    /**
     * 会话档案的部分更新：null=不动该字段，空串=清除该字段（备注名/Agent/备注）。
     * reply_mode 只接受 FULL / RECEIPTS_ONLY（库 CHECK 兜底），null 保持现值。
     */
    public void upsertProfile(
            String chatId, String mode, String displayName, String agentSlug, String note, String operator) {
        jdbc.update(
                """
                INSERT INTO app.wecom_chat_reply_policies AS p
                    (chat_id, reply_mode, display_name, agent_slug, note, updated_by, updated_at)
                VALUES (?, COALESCE(?::text, 'FULL'), NULLIF(?::text, ''), NULLIF(?::text, ''), NULLIF(?::text, ''), ?, now())
                ON CONFLICT (chat_id) DO UPDATE
                    SET reply_mode   = COALESCE(?::text, p.reply_mode),
                        display_name = CASE WHEN ?::text IS NULL THEN p.display_name ELSE NULLIF(?::text, '') END,
                        agent_slug   = CASE WHEN ?::text IS NULL THEN p.agent_slug ELSE NULLIF(?::text, '') END,
                        note         = CASE WHEN ?::text IS NULL THEN p.note ELSE NULLIF(?::text, '') END,
                        updated_by   = ?,
                        updated_at   = now()
                """,
                chatId, mode, displayName, agentSlug, note, operator,
                mode, displayName, displayName, agentSlug, agentSlug, note, note, operator);
    }
}
