package cn.zimu.fulfillment.connector.wecom;

import cn.zimu.fulfillment.common.error.BusinessException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 机器人可达会话目录：配置「往哪个会话推送」时的候选清单（履约方企微群等）。
 *
 * <p>chatid 是天书（{@code wrn8VIbwAA…}），手抄必错。目录合成两路：
 * <ul>
 *   <li><b>群聊</b>——{@code wecom_events} 里机器人实际收到过消息的群。把机器人拉进
 *       新群、随便发一条消息，刷新即出现在这里；机器人没进过的群本来也发不进去。</li>
 *   <li><b>单聊</b>——运营人员表（V48）里已绑定企微 userid 的人（单聊的 chatid 就是 userid）。</li>
 * </ul>
 *
 * <p>只回标识符与活跃元数据，不回任何消息内容；与 readiness 同一道 X-Operator 门。
 */
@RestController
@RequestMapping("/api/v1/wecom")
public class WecomChatDirectoryController {

    /**
     * label 只对单聊有值（运营人员姓名）；群名企微协议不下发，只有 chatid。
     * replyMode：会话回复策略（FULL=自由回复；RECEIPTS_ONLY=静默，只发白名单回执/回填）。
     */
    public record KnownChat(
            String chatId, String chatType, String label, long eventCount, OffsetDateTime lastSeenAt,
            String replyMode) {}

    public record Directory(List<KnownChat> chats) {}

    public record ReplyPolicyWrite(String replyMode, String note) {}

    private final JdbcTemplate jdbc;
    private final WecomChatReplyPolicyService replyPolicies;

    public WecomChatDirectoryController(JdbcTemplate jdbc, WecomChatReplyPolicyService replyPolicies) {
        this.jdbc = jdbc;
        this.replyPolicies = replyPolicies;
    }

    @GetMapping("/chats")
    public Directory chats(@RequestHeader(value = "X-Operator", required = false) String operator) {
        if (operator == null || operator.isBlank()) {
            throw new BusinessException(401, "ADMIN_AUTH_REQUIRED", "管理后台查询需要认证");
        }
        List<KnownChat> chats = new ArrayList<>(jdbc.query(
                """
                SELECT e.chat_id, count(*) AS event_count, max(e.received_at) AS last_seen_at,
                       min(p.reply_mode) AS reply_mode
                FROM app.wecom_events e
                LEFT JOIN app.wecom_chat_reply_policies p ON p.chat_id = e.chat_id
                WHERE e.chat_type = 'group' AND e.chat_id IS NOT NULL AND e.chat_id <> ''
                GROUP BY e.chat_id
                ORDER BY max(e.received_at) DESC
                LIMIT 50
                """,
                (rs, rowNum) -> new KnownChat(
                        rs.getString("chat_id"),
                        "group",
                        null,
                        rs.getLong("event_count"),
                        rs.getObject("last_seen_at", OffsetDateTime.class),
                        mode(rs.getString("reply_mode")))));
        chats.addAll(jdbc.query(
                """
                SELECT o.wecom_userid, o.display_name, p.reply_mode
                FROM app.internal_operators o
                LEFT JOIN app.wecom_chat_reply_policies p ON p.chat_id = o.wecom_userid
                WHERE o.active AND o.wecom_userid IS NOT NULL AND btrim(o.wecom_userid) <> ''
                ORDER BY o.id
                LIMIT 50
                """,
                (rs, rowNum) -> new KnownChat(
                        rs.getString("wecom_userid"),
                        "single",
                        rs.getString("display_name"),
                        0,
                        null,
                        mode(rs.getString("reply_mode")))));
        return new Directory(chats);
    }

    private static String mode(String stored) {
        return stored == null ? WecomChatReplyPolicyService.MODE_FULL : stored;
    }

    /**
     * 设置会话回复策略。业务语义见 {@link WecomChatReplyPolicyService}：
     * 客户群配 RECEIPTS_ONLY 静默收单，个人助手保持 FULL 自由应答。
     */
    @org.springframework.web.bind.annotation.PutMapping("/chats/{chatId}/reply-policy")
    public KnownChat setReplyPolicy(
            @org.springframework.web.bind.annotation.PathVariable String chatId,
            @org.springframework.web.bind.annotation.RequestBody ReplyPolicyWrite body,
            @RequestHeader(value = "X-Operator", required = false) String operator) {
        if (operator == null || operator.isBlank()) {
            throw new BusinessException(401, "ADMIN_AUTH_REQUIRED", "管理后台操作需要认证");
        }
        String mode = body == null ? null : body.replyMode();
        if (!WecomChatReplyPolicyService.MODE_FULL.equals(mode)
                && !WecomChatReplyPolicyService.MODE_RECEIPTS_ONLY.equals(mode)) {
            throw new BusinessException(400, "INVALID_REPLY_MODE", "reply_mode 只接受 FULL / RECEIPTS_ONLY");
        }
        if (chatId == null || chatId.isBlank() || chatId.length() > 128) {
            throw new BusinessException(400, "INVALID_CHAT_ID", "chat_id 非法");
        }
        replyPolicies.upsert(chatId, mode, body.note(), operator);
        return new KnownChat(chatId, null, null, 0, null, mode);
    }
}
