package cn.zimu.fulfillment.connector.wecom;

import cn.zimu.fulfillment.message.ChannelMessageCommand;
import cn.zimu.fulfillment.message.MessageSubmissionService;
import cn.zimu.fulfillment.followup.BusinessFollowUpCardInteractionOutcome;
import cn.zimu.fulfillment.followup.BusinessFollowUpCardInteractionService;
import cn.zimu.fulfillment.connector.wecom.card.WecomTaskId;
import cn.zimu.fulfillment.connector.wecom.card.WecomCardBuilder;
import cn.zimu.fulfillment.connector.wecom.card.source.CardDeepLinks;
import cn.zimu.fulfillment.order.card.CardConfirmationResult;
import cn.zimu.fulfillment.order.card.CardConfirmationStatus;
import cn.zimu.fulfillment.order.card.CardFallbackStatus;
import cn.zimu.fulfillment.order.card.CardInteractionOutcome;
import cn.zimu.fulfillment.order.card.CardUpdateStatus;
import cn.zimu.fulfillment.order.card.WecomOrderDraftCardInteractionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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
 * （wecom-message-intake 07 票 checkbox 1：不等待下载或识别即可回复「已接收」）；单聊 file
 * 进入 {@code WECOM_TRACKING_FILE} 专用任务下载/解析且不进模型；voice/video 只落消息证据；
 * enter_chat / disconnected_event 留档；template_card_event 走持久化人工确认与
 * 5 秒 updateCard 快路径，feedback_event 忽略。
 * 不设白名单。
 */
@Component
public class WecomMessageDispatchHandler implements WecomFrameHandler {

    private static final Logger log = LoggerFactory.getLogger(WecomMessageDispatchHandler.class);

    static final String CONNECTION_ID = "wecom-long-connection";
    static final String RECEIPT_TEXT = "已接收";
    static final long UPDATE_CARD_BUDGET_NANOS = 4_500_000_000L;
    private static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss");

    private final MessageSubmissionService submissionService;
    private final ObjectProvider<WecomConnectionManager> connectionManagerProvider;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final WecomOrderDraftCardInteractionService cardInteractions;
    private final BusinessFollowUpCardInteractionService followUpCardInteractions;
    private final ObjectProvider<WecomOutboundGateway> outboundGatewayProvider;
    private final CardDeepLinks deepLinks;

    private volatile WecomConnectionManager connectionManager;
    private volatile WecomOutboundGateway outboundGateway;

    public WecomMessageDispatchHandler(
            MessageSubmissionService submissionService,
            ObjectProvider<WecomConnectionManager> connectionManagerProvider,
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            WecomOrderDraftCardInteractionService cardInteractions,
            BusinessFollowUpCardInteractionService followUpCardInteractions,
            ObjectProvider<WecomOutboundGateway> outboundGatewayProvider,
            CardDeepLinks deepLinks) {
        this.submissionService = submissionService;
        this.connectionManagerProvider = connectionManagerProvider;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.cardInteractions = cardInteractions;
        this.followUpCardInteractions = followUpCardInteractions;
        this.outboundGatewayProvider = outboundGatewayProvider;
        this.deepLinks = deepLinks;
    }

    @Override
    public void onFrame(String cmd, JsonNode frame) {
        onFrame(cmd, frame, System.nanoTime());
    }

    @Override
    public void onFrame(String cmd, JsonNode frame, long receivedNanos) {
        switch (cmd == null ? "" : cmd) {
            case "aibot_msg_callback" -> handleMessage(frame);
            case "aibot_event_callback" -> handleEvent(frame, receivedNanos);
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

    void handleEvent(JsonNode frame, long startedNanos) {
        JsonNode body = frame.path("body");
        String eventType = body.path("event").path("eventtype").asText("");
        switch (eventType) {
            case "enter_chat", "disconnected_event" -> persistEvent(frame, body, eventType);
            case "template_card_event" -> handleTemplateCardEvent(frame, startedNanos);
            case "feedback_event" -> log.debug("按决策忽略企微事件: {}", eventType);
            default -> log.debug("未知企微事件类型: {}", eventType);
        }
    }

    /**
     * 业务处理先提交；updateCard 与文字兜底随后执行，任何通道失败都不会回滚已确认订单。
     * 4.5 秒本地预算为官方 5 秒窗口预留 500ms 发送余量。
     */
    void handleTemplateCardEvent(JsonNode frame, long startedNanos) {
        if (isFollowUpCard(frame)) {
            handleFollowUpCardEvent(frame, startedNanos);
            return;
        }
        CardInteractionOutcome outcome;
        try {
            outcome = cardInteractions.handle(frame);
        } catch (RuntimeException ex) {
            log.error("企微卡片事件处理失败，无法进入 updateCard 快路径", ex);
            return;
        }
        String messageId = frame.path("body").path("msgid").asText(outcome.messageId());
        String reqId = frame.path("headers").path("req_id").asText(outcome.requestId());
        if (outcome.duplicate()
                && "ORDER_DRAFT_CARD_EVENT_IN_PROGRESS".equals(outcome.result().businessCode())) {
            // The original callback still owns the business action and its req_id fast path. A
            // concurrent redelivery must not mutate either the live card or the original
            // callback's persisted send outcome. Both callbacks intentionally share the active
            // claim token, so even a token-CAS write here could race after the owner records SENT.
            log.debug("企微卡片事件正在由原回调处理，忽略并发重投 msgid={}", messageId);
            return;
        }
        if (outcome.duplicate()
                && "WECOM_CARD_EVENT_FACTS_MISMATCH".equals(outcome.result().businessCode())) {
            // The persisted first callback owns this msgid. A transformed redelivery receives no
            // business action, card update or fallback and cannot overwrite the original evidence.
            log.warn("企微卡片事件首次事实不一致，拒绝变形重投 msgid={}", messageId);
            return;
        }
        if (outcome.duplicate()) {
            // A completed callback already owns the persisted update/fallback observation. A
            // platform redelivery must be read-only: resending can duplicate fallback text and a
            // late timeout/failure could otherwise downgrade an earlier SENT result.
            log.debug("企微卡片事件已完成，忽略终态重投 msgid={}", messageId);
            return;
        }
        String updateErrorCode = null;
        String fallbackErrorCode = null;
        CardUpdateStatus updateStatus;
        CardFallbackStatus fallbackStatus = CardFallbackStatus.NOT_ATTEMPTED;
        long deadlineNanos = startedNanos + UPDATE_CARD_BUDGET_NANOS;
        long elapsed = System.nanoTime() - startedNanos;
        if (reqId == null || reqId.isBlank()) {
            updateStatus = CardUpdateStatus.FAILED;
            updateErrorCode = "UPDATE_REQUEST_ID_MISSING";
        } else if (outcome.taskId() == null || outcome.taskId().isBlank()) {
            updateStatus = CardUpdateStatus.FAILED;
            updateErrorCode = "UPDATE_TASK_ID_INVALID";
        } else if (elapsed >= UPDATE_CARD_BUDGET_NANOS) {
            updateStatus = CardUpdateStatus.TIMED_OUT;
            updateErrorCode = "FAST_PATH_DEADLINE_EXCEEDED";
        } else {
            try {
                WecomSendResult updateResult = connectionManager()
                        .respondUpdateUntil(reqId, updateCard(outcome), deadlineNanos);
                updateStatus = switch (updateResult.status()) {
                    case SUCCESS -> CardUpdateStatus.SENT;
                    case TIMEOUT -> CardUpdateStatus.TIMED_OUT;
                    case FAILED -> CardUpdateStatus.FAILED;
                };
                updateErrorCode = updateStatus == CardUpdateStatus.SENT
                        ? null
                        : sendFailureCode(updateResult, "UPDATE_NOT_ACKNOWLEDGED");
            } catch (RuntimeException ex) {
                updateStatus = CardUpdateStatus.FAILED;
                updateErrorCode = "UPDATE_SEND_EXCEPTION";
                log.warn("企微卡片同步更新失败，改发文字兜底 msgid={}", messageId, ex);
            }
        }

        if (updateStatus != CardUpdateStatus.SENT) {
            FallbackDelivery fallback = sendFallback(outcome);
            fallbackStatus = fallback.status();
            fallbackErrorCode = fallback.errorCode();
        }
        int latencyMs = nanosToMillis(System.nanoTime() - startedNanos);
        recordCardUpdateOutcome(
                messageId,
                outcome.claimToken(),
                updateStatus,
                fallbackStatus,
                latencyMs,
                updateErrorCode,
                fallbackErrorCode);
    }

    private void handleFollowUpCardEvent(JsonNode frame, long startedNanos) {
        BusinessFollowUpCardInteractionOutcome outcome;
        try {
            outcome = followUpCardInteractions.handle(frame);
        } catch (RuntimeException ex) {
            log.error("企微客户跟进卡片事件处理失败，等待平台重试", ex);
            return;
        }
        if (outcome.duplicate()) {
            log.debug("企微客户跟进卡片事件已由首次回调持有 msgid={}", outcome.messageId());
            return;
        }
        String updateErrorCode = null;
        String fallbackErrorCode = null;
        CardUpdateStatus updateStatus;
        CardFallbackStatus fallbackStatus = CardFallbackStatus.NOT_ATTEMPTED;
        long deadlineNanos = startedNanos + UPDATE_CARD_BUDGET_NANOS;
        if (outcome.requestId() == null || outcome.requestId().isBlank()) {
            updateStatus = CardUpdateStatus.FAILED;
            updateErrorCode = "UPDATE_REQUEST_ID_MISSING";
        } else if (outcome.taskId() == null || outcome.taskId().isBlank()) {
            updateStatus = CardUpdateStatus.FAILED;
            updateErrorCode = "UPDATE_TASK_ID_INVALID";
        } else if (System.nanoTime() >= deadlineNanos) {
            updateStatus = CardUpdateStatus.TIMED_OUT;
            updateErrorCode = "FAST_PATH_DEADLINE_EXCEEDED";
        } else {
            try {
                WecomSendResult result = connectionManager().respondUpdateUntil(
                        outcome.requestId(), followUpAcceptedCard(outcome), deadlineNanos);
                updateStatus = switch (result.status()) {
                    case SUCCESS -> CardUpdateStatus.SENT;
                    case TIMEOUT -> CardUpdateStatus.TIMED_OUT;
                    case FAILED -> CardUpdateStatus.FAILED;
                };
                updateErrorCode = updateStatus == CardUpdateStatus.SENT
                        ? null
                        : sendFailureCode(result, "UPDATE_NOT_ACKNOWLEDGED");
            } catch (RuntimeException ex) {
                updateStatus = CardUpdateStatus.FAILED;
                updateErrorCode = "UPDATE_SEND_EXCEPTION";
            }
        }
        if (updateStatus != CardUpdateStatus.SENT) {
            try {
                WecomSendResult result = outboundGateway().send(WecomOutboundMessage.markdown(
                        outcome.replyTarget(), followUpFallback(outcome)));
                fallbackStatus = result.status() == WecomSendStatus.SUCCESS
                        ? CardFallbackStatus.SENT
                        : CardFallbackStatus.FAILED;
                fallbackErrorCode = fallbackStatus == CardFallbackStatus.SENT
                        ? null
                        : sendFailureCode(result, "FALLBACK_NOT_ACKNOWLEDGED");
            } catch (RuntimeException ex) {
                fallbackStatus = CardFallbackStatus.FAILED;
                fallbackErrorCode = "FALLBACK_SEND_EXCEPTION";
            }
        }
        try {
            followUpCardInteractions.recordUpdateOutcome(
                    outcome.messageId(),
                    outcome.claimToken(),
                    updateStatus,
                    fallbackStatus,
                    nanosToMillis(System.nanoTime() - startedNanos),
                    updateErrorCode,
                    fallbackErrorCode);
        } catch (RuntimeException ex) {
            log.error("企微客户跟进卡片更新结果落库失败 msgid={}", outcome.messageId(), ex);
        }
    }

    private ObjectNode followUpAcceptedCard(BusinessFollowUpCardInteractionOutcome outcome) {
        WecomTaskId taskId = WecomTaskId.parse(outcome.taskId())
                .filter(value -> BusinessFollowUpCardInteractionService.CARD_DOMAIN.equals(value.domain()))
                .orElseThrow(() -> new IllegalStateException("客户跟进 updateCard task_id 无效"));
        String detailUrl = deepLinks.of(
                "/workbench/business-followups?followup_id=" + taskId.entityId());
        ObjectNode card = WecomCardBuilder.textNotice(taskId)
                .title("ACCEPTED".equals(outcome.status()) ? "操作已受理" : "操作被拒绝")
                .desc(outcome.followupNo() + " · " + outcome.businessCode())
                .cardAction(detailUrl)
                .build();
        ObjectNode body = objectMapper.createObjectNode();
        body.put("response_type", "update_template_card");
        body.set("template_card", card);
        return body;
    }

    private static String followUpFallback(BusinessFollowUpCardInteractionOutcome outcome) {
        return ("ACCEPTED".equals(outcome.status()) ? "**操作已受理**" : "**操作被拒绝**")
                + "\n> " + outcome.followupNo() + " · " + outcome.businessCode();
    }

    private static boolean isFollowUpCard(JsonNode frame) {
        JsonNode event = frame.path("body").path("event");
        String raw = event.path("template_card_event").path("task_id")
                .asText(event.path("task_id").asText(""));
        return WecomTaskId.parse(raw)
                .map(WecomTaskId::domain)
                .filter(BusinessFollowUpCardInteractionService.CARD_DOMAIN::equals)
                .isPresent();
    }

    private void recordCardUpdateOutcome(
            String messageId,
            String claimToken,
            CardUpdateStatus updateStatus,
            CardFallbackStatus fallbackStatus,
            int latencyMs,
            String updateErrorCode,
            String fallbackErrorCode) {
        if (claimToken == null || claimToken.isBlank()) {
            return;
        }
        try {
            cardInteractions.recordUpdateOutcome(
                    messageId,
                    claimToken,
                    updateStatus,
                    fallbackStatus,
                    latencyMs,
                    updateErrorCode,
                    fallbackErrorCode);
        } catch (RuntimeException ex) {
            log.error("企微卡片更新结果落库失败 msgid={} status={}", messageId, updateStatus, ex);
        }
    }

    private ObjectNode updateCard(CardInteractionOutcome outcome) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("response_type", "update_template_card");
        ObjectNode card = body.putObject("template_card");
        card.put("card_type", "text_notice");
        card.putObject("main_title")
                .put("title", title(outcome.result().status()))
                .put("desc", outcome.result().draftNo() + " · " + operatorAndTime(outcome.result()));
        card.put("task_id", outcome.taskId());
        return body;
    }

    private FallbackDelivery sendFallback(CardInteractionOutcome outcome) {
        if (outcome.replyTarget() == null || outcome.replyTarget().isBlank()) {
            return FallbackDelivery.failed("FALLBACK_TARGET_MISSING");
        }
        String text = title(outcome.result().status()) + "：" + outcome.result().draftNo() + "，"
                + operatorAndTime(outcome.result());
        if (outcome.result().status() == CardConfirmationStatus.MISSING_INFORMATION
                && !outcome.result().missingFields().isEmpty()) {
            text += "；待补充 " + String.join("、", outcome.result().missingFields());
        }
        try {
            WecomSendResult result = outboundGateway()
                    .send(WecomOutboundMessage.markdown(outcome.replyTarget(), text));
            return result.status() == WecomSendStatus.SUCCESS
                    ? FallbackDelivery.sent()
                    : FallbackDelivery.failed(sendFailureCode(result, "FALLBACK_NOT_ACKNOWLEDGED"));
        } catch (RuntimeException ex) {
            log.warn("企微卡片文字兜底发送失败 target={}", outcome.replyTarget(), ex);
            return FallbackDelivery.failed("FALLBACK_SEND_EXCEPTION");
        }
    }

    private static String sendFailureCode(WecomSendResult result, String fallback) {
        if (result.errorCode() != null) {
            return "WECOM_" + result.errorCode();
        }
        return result.errorMessage() == null || result.errorMessage().isBlank()
                ? fallback
                : result.errorMessage();
    }

    private record FallbackDelivery(CardFallbackStatus status, String errorCode) {

        static FallbackDelivery sent() {
            return new FallbackDelivery(CardFallbackStatus.SENT, null);
        }

        static FallbackDelivery failed(String errorCode) {
            return new FallbackDelivery(CardFallbackStatus.FAILED, errorCode);
        }
    }

    private static String title(CardConfirmationStatus status) {
        return switch (status) {
            case CONFIRMED -> "订单已确认";
            case ALREADY_CONFIRMED -> "订单已确认（重复点击）";
            case MISSING_INFORMATION -> "订单信息待补充";
            case REJECTED -> "卡片操作被拒绝";
            case FAILED -> "卡片处理状态待确认";
        };
    }

    private static String operatorAndTime(CardConfirmationResult result) {
        String actor = result.confirmedBy() == null ? "unknown" : result.confirmedBy();
        if (actor.startsWith("wecom:")) {
            actor = actor.substring("wecom:".length());
        }
        return "操作人：" + actor + " · " + DISPLAY_TIME.format(result.processedAt().atZone(DISPLAY_ZONE));
    }

    private static int nanosToMillis(long nanos) {
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, nanos / 1_000_000L));
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

    /** Lazy outbound gateway lookup breaks handler → gateway → connection manager → handler construction. */
    private WecomOutboundGateway outboundGateway() {
        WecomOutboundGateway resolved = outboundGateway;
        if (resolved == null) {
            synchronized (this) {
                resolved = outboundGateway;
                if (resolved == null) {
                    resolved = outboundGatewayProvider.getObject();
                    outboundGateway = resolved;
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
