package cn.zimu.fulfillment.connector.wecom;

import java.util.List;
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

    /** 登记/更新策略；mode 只接受 FULL / RECEIPTS_ONLY（库 CHECK 兜底）。 */
    public void upsert(String chatId, String mode, String note, String operator) {
        jdbc.update(
                """
                INSERT INTO app.wecom_chat_reply_policies (chat_id, reply_mode, note, updated_by, updated_at)
                VALUES (?, ?, ?, ?, now())
                ON CONFLICT (chat_id) DO UPDATE
                    SET reply_mode = EXCLUDED.reply_mode,
                        note = EXCLUDED.note,
                        updated_by = EXCLUDED.updated_by,
                        updated_at = now()
                """,
                chatId, mode, note, operator);
    }
}
