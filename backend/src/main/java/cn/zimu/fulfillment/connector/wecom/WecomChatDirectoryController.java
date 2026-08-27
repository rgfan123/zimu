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
     * displayName：人起的会话备注名（企微协议不下发群名，帧里只有 chatid）；
     * label：单聊的运营人员姓名（自动兜底名）；agentSlug：服务该会话的 Agent；
     * replyMode：FULL=自由回复；RECEIPTS_ONLY=仅业务消息（回执/回填/清单照发，不闲聊不追问）。
     */
    public record KnownChat(
            String chatId, String chatType, String displayName, String label,
            long eventCount, OffsetDateTime lastSeenAt, String agentSlug, String replyMode) {}

    public record Directory(List<KnownChat> chats) {}

    /** 部分更新：字段为 null 不动，空串清除；replyMode 只接受 FULL/RECEIPTS_ONLY。 */
    public record ChatProfileWrite(String replyMode, String displayName, String agentSlug, String note) {}

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
                       min(p.reply_mode) AS reply_mode, min(p.display_name) AS display_name,
                       min(p.agent_slug) AS agent_slug
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
                        rs.getString("display_name"),
                        null,
                        rs.getLong("event_count"),
                        rs.getObject("last_seen_at", OffsetDateTime.class),
                        rs.getString("agent_slug"),
                        mode(rs.getString("reply_mode")))));
        chats.addAll(jdbc.query(
                """
                SELECT o.wecom_userid, o.display_name AS operator_name, p.reply_mode,
                       p.display_name AS alias, p.agent_slug
                FROM app.internal_operators o
                LEFT JOIN app.wecom_chat_reply_policies p ON p.chat_id = o.wecom_userid
                WHERE o.active AND o.wecom_userid IS NOT NULL AND btrim(o.wecom_userid) <> ''
                ORDER BY o.id
                LIMIT 50
                """,
                (rs, rowNum) -> new KnownChat(
                        rs.getString("wecom_userid"),
                        "single",
                        rs.getString("alias"),
                        rs.getString("operator_name"),
                        0,
                        null,
                        rs.getString("agent_slug"),
                        mode(rs.getString("reply_mode")))));
        return new Directory(chats);
    }

    private static String mode(String stored) {
        return stored == null ? WecomChatReplyPolicyService.MODE_FULL : stored;
    }

    /**
     * 会话档案部分更新：备注名 / 服务 Agent / 回复权限，字段 null 不动、空串清除。
     * 客户群配 RECEIPTS_ONLY 仅业务消息，个人助手保持 FULL 自由应答。
     */
    @org.springframework.web.bind.annotation.PutMapping("/chats/{chatId}/profile")
    public KnownChat setProfile(
            @org.springframework.web.bind.annotation.PathVariable String chatId,
            @org.springframework.web.bind.annotation.RequestBody ChatProfileWrite body,
            @RequestHeader(value = "X-Operator", required = false) String operator) {
        if (operator == null || operator.isBlank()) {
            throw new BusinessException(401, "ADMIN_AUTH_REQUIRED", "管理后台操作需要认证");
        }
        if (chatId == null || chatId.isBlank() || chatId.length() > 128) {
            throw new BusinessException(400, "INVALID_CHAT_ID", "chat_id 非法");
        }
        String mode = body == null ? null : body.replyMode();
        if (mode != null
                && !WecomChatReplyPolicyService.MODE_FULL.equals(mode)
                && !WecomChatReplyPolicyService.MODE_RECEIPTS_ONLY.equals(mode)) {
            throw new BusinessException(400, "INVALID_REPLY_MODE", "reply_mode 只接受 FULL / RECEIPTS_ONLY");
        }
        String agentSlug = body == null ? null : body.agentSlug();
        if (agentSlug != null && !agentSlug.isBlank()) {
            Integer known = jdbc.queryForObject(
                    "SELECT count(*) FROM app.agent_definitions WHERE agent_slug = ?",
                    Integer.class, agentSlug.trim());
            if (known == null || known == 0) {
                throw new BusinessException(400, "UNKNOWN_AGENT", "Agent 不存在：" + agentSlug);
            }
            agentSlug = agentSlug.trim();
        }
        String displayName = body == null ? null : body.displayName();
        if (displayName != null && displayName.length() > 128) {
            throw new BusinessException(400, "INVALID_DISPLAY_NAME", "备注名最长 128 字");
        }
        replyPolicies.upsertProfile(
                chatId, mode, displayName, agentSlug, body == null ? null : body.note(), operator);
        List<KnownChat> saved = jdbc.query(
                """
                SELECT reply_mode, display_name, agent_slug FROM app.wecom_chat_reply_policies WHERE chat_id = ?
                """,
                (rs, rowNum) -> new KnownChat(
                        chatId, null, rs.getString("display_name"), null, 0, null,
                        rs.getString("agent_slug"), rs.getString("reply_mode")),
                chatId);
        return saved.isEmpty()
                ? new KnownChat(chatId, null, null, null, 0, null, null, WecomChatReplyPolicyService.MODE_FULL)
                : saved.getFirst();
    }
}
