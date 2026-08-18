package cn.zimu.fulfillment.connector.wecom;

import cn.zimu.fulfillment.message.ChannelMessageCommand;
import cn.zimu.fulfillment.message.MessageSubmissionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 企业微信长连接接收链路：把 {@code aibot_msg_callback} 消息帧映射进 {@link MessageSubmissionService}
 * 证据链路并回执「已接收」，把 {@code aibot_event_callback} 事件按 04 决策留档或忽略。
 *
 * <p>映射决策（04 票）：chattype 区分单聊（无 chatid）与群聊；from.userid 明文直用；msgid 幂等由
 * {@code channel_messages} 的 ON CONFLICT 保证；回执透传回调 req_id，发送失败重试 1 次、仍失败只告警；
 * 媒体（image/mixed）**不在回调线程下载**——仅保存原始载荷并创建解释任务，由解释任务内下载解密
 * （wecom-message-intake 07 票 checkbox 1：不等待下载或识别即可回复「已接收」）；voice/file/video
 * 落证据但不下载；enter_chat / disconnected_event 留档，template_card_event / feedback_event 忽略。
 * 不设白名单。
 */
@Component
public class WecomMessageDispatchHandler implements WecomFrameHandler {

    private static final Logger log = LoggerFactory.getLogger(WecomMessageDispatchHandler.class);

    static final String CONNECTION_ID = "wecom-long-connection";
    static final String RECEIPT_TEXT = "已接收";

    private final MessageSubmissionService submissionService;
    private final ObjectProvider<WecomConnectionManager> connectionManagerProvider;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    private volatile WecomConnectionManager connectionManager;

    public WecomMessageDispatchHandler(
            MessageSubmissionService submissionService,
            ObjectProvider<WecomConnectionManager> connectionManagerProvider,
            JdbcTemplate jdbc,
            ObjectMapper objectMapper) {
        this.submissionService = submissionService;
        this.connectionManagerProvider = connectionManagerProvider;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public void onFrame(String cmd, JsonNode frame) {
        switch (cmd == null ? "" : cmd) {
            case "aibot_msg_callback" -> handleMessage(frame);
            case "aibot_event_callback" -> handleEvent(frame);
            default -> log.debug("忽略未知企微长连接帧: {}", cmd);
        }
    }

    void handleMessage(JsonNode frame) {
        JsonNode body = frame.path("body");
        String reqId = frame.path("headers").path("req_id").asText("");
        String messageId = body.path("msgid").asText("");
        if (messageId.isBlank()) {
            log.warn("企微消息回调缺少 msgid，已丢弃（不回复）");
            return;
        }
        String botId = body.path("aibotid").asText("");
        String chatId = body.path("chatid").asText("");
        String chatType = body.path("chattype").asText("single");
        String senderUserId = body.path("from").path("userid").asText("");
        String msgType = body.path("msgtype").asText("");
        // 单聊回调无 chatid；表约束要求非空，用发送人构造稳定会话标识（幂等键不含 chat_id，不影响去重）。
        if (chatId.isBlank()) {
            chatId = "single:" + senderUserId;
        }

        JsonNode quote = body.path("quote");
        String quoteType = quote.isMissingNode() || quote.isNull() ? null : quote.path("msgtype").asText(null);
        String quoteContent = quote.isMissingNode() || quote.isNull()
                ? null
                : quote.path("text").path("content").asText(null);

        String content = extractTextContent(body, msgType);
        ChannelMessageCommand command = new ChannelMessageCommand(
                corpId(botId),
                CONNECTION_ID,
                botId,
                messageId,
                chatId,
                chatType,
                senderUserId,
                msgType,
                // image/voice 等无文本消息：content 列 NOT NULL，落库用空串。
                content == null ? "" : content,
                quoteType,
                quoteContent,
                frame);
        try {
            long submissionId = submissionService.submit(command);
            deliverReceipt(reqId);
        } catch (RuntimeException ex) {
            // 不回执：企微会按回调重试（重复回调由幂等键收敛）；证据若已落库则保留。
            log.error("企微消息处理失败，等待通道重试 msgid={}", messageId, ex);
        }
    }

    void handleEvent(JsonNode frame) {
        JsonNode body = frame.path("body");
        String eventType = body.path("event").path("eventtype").asText("");
        switch (eventType) {
            case "enter_chat", "disconnected_event" -> persistEvent(frame, body, eventType);
            case "template_card_event", "feedback_event" -> log.debug("按决策忽略企微事件: {}", eventType);
            default -> log.debug("未知企微事件类型: {}", eventType);
        }
    }

    private void persistEvent(JsonNode frame, JsonNode body, String eventType) {
        String msgid = body.path("msgid").asText("");
        if (msgid.isBlank()) {
            log.warn("企微事件缺少 msgid，已丢弃 event_type={}", eventType);
            return;
        }
        int rows = jdbc.update(
                """
                INSERT INTO app.wecom_events (
                    event_type, msgid, aibot_id, chat_id, chat_type, from_user_id, create_time, raw_payload
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                ON CONFLICT (event_type, msgid) DO NOTHING
                """,
                eventType,
                msgid,
                body.path("aibotid").asText(""),
                body.path("chatid").asText(""),
                body.path("chattype").asText(""),
                body.path("from").path("userid").asText(""),
                body.path("create_time").asLong(0),
                frame.toString());
        if (rows == 0) {
            log.debug("企微事件重复，已忽略 event_type={} msgid={}", eventType, msgid);
        }
    }

    /** 单机器人收敛后 bot 即企业维度标识；corp_id 只作幂等键 (corp_id, connection_id, message_id) 的稳定分量。 */
    private String corpId(String botId) {
        return botId;
    }

    /** 回执「已接收」：透传回调 req_id；失败重试 1 次，仍失败只告警不重推（04 决策）。 */
    private void deliverReceipt(String reqId) {
        if (reqId.isBlank()) {
            return;
        }
        ObjectNode body = objectMapper.createObjectNode();
        body.put("msgtype", "text");
        body.putObject("text").put("content", RECEIPT_TEXT);
        boolean sent = connectionManager().respond(reqId, body);
        if (!sent) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            sent = connectionManager.respond(reqId, body);
        }
        if (!sent) {
            log.warn("企微回执发送失败（已重试 1 次），不再重推 req_id={}", reqId);
        }
    }

    /** 懒解析连接管理器：handler 与 manager 互相依赖，用 provider 打破构造期循环。 */
    private WecomConnectionManager connectionManager() {
        WecomConnectionManager resolved = connectionManager;
        if (resolved == null) {
            synchronized (this) {
                resolved = connectionManager;
                if (resolved == null) {
                    resolved = connectionManagerProvider.getObject();
                    connectionManager = resolved;
                }
            }
        }
        return resolved;
    }

    private static String extractTextContent(JsonNode body, String msgType) {
        return switch (msgType) {
            case "text" -> body.path("text").path("content").asText(null);
            case "mixed" -> joinMixedText(body);
            default -> null;
        };
    }

    private static String joinMixedText(JsonNode body) {
        JsonNode items = mixedItems(body);
        if (items == null) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        for (JsonNode item : items) {
            if ("text".equals(item.path("msgtype").asText())) {
                String content = item.path("content").asText(null);
                if (content != null && !content.isBlank()) {
                    parts.add(content);
                }
            }
        }
        return parts.isEmpty() ? null : String.join(" ", parts);
    }

    /** mixed 消息的图片/文字数组：兼容 items 与 msg_item 两种字段名。 */
    private static JsonNode mixedItems(JsonNode body) {
        JsonNode mixed = body.path("mixed");
        JsonNode items = mixed.path("items");
        if (items.isMissingNode() || items.isNull()) {
            items = mixed.path("msg_item");
        }
        return items.isMissingNode() || items.isNull() || !items.isArray() ? null : items;
    }
}
