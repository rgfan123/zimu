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

    /** label 只对单聊有值（运营人员姓名）；群名企微协议不下发，只有 chatid。 */
    public record KnownChat(
            String chatId, String chatType, String label, long eventCount, OffsetDateTime lastSeenAt) {}

    public record Directory(List<KnownChat> chats) {}

    private final JdbcTemplate jdbc;

    public WecomChatDirectoryController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/chats")
    public Directory chats(@RequestHeader(value = "X-Operator", required = false) String operator) {
        if (operator == null || operator.isBlank()) {
            throw new BusinessException(401, "ADMIN_AUTH_REQUIRED", "管理后台查询需要认证");
        }
        List<KnownChat> chats = new ArrayList<>(jdbc.query(
                """
                SELECT chat_id, count(*) AS event_count, max(received_at) AS last_seen_at
                FROM app.wecom_events
                WHERE chat_type = 'group' AND chat_id IS NOT NULL AND chat_id <> ''
                GROUP BY chat_id
                ORDER BY max(received_at) DESC
                LIMIT 50
                """,
                (rs, rowNum) -> new KnownChat(
                        rs.getString("chat_id"),
                        "group",
                        null,
                        rs.getLong("event_count"),
                        rs.getObject("last_seen_at", OffsetDateTime.class))));
        chats.addAll(jdbc.query(
                """
                SELECT wecom_userid, display_name
                FROM app.internal_operators
                WHERE active AND wecom_userid IS NOT NULL AND btrim(wecom_userid) <> ''
                ORDER BY id
                LIMIT 50
                """,
                (rs, rowNum) -> new KnownChat(
                        rs.getString("wecom_userid"),
                        "single",
                        rs.getString("display_name"),
                        0,
                        null)));
        return new Directory(chats);
    }
}
