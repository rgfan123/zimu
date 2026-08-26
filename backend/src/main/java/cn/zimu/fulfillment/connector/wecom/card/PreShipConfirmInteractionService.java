package cn.zimu.fulfillment.connector.wecom.card;

import cn.zimu.fulfillment.message.AsyncTaskStore;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 发货前确认卡的回调处理（{@code preship} 域）。
 *
 * <p><b>为什么不复用订单草稿卡那套</b>：那套的 {@code CardEventInput} 把 {@code orderDraftId}
 * 编进了结构里，整条链路围绕草稿建模。业务卡是另一个实体族（订单 / 复核事项 / 告警），
 * 塞进去只会让两边都变形。共用的是 {@link WecomTaskId} 的版本断言语义，不是数据结构。
 *
 * <p><b>为什么确认动作是异步的</b>：企微给的更新窗口是 5 秒，而京东建单
 * （{@code addSoOrder}）是外部写、耗时不可控。把建单压进 5 秒里，结局是窗口过期后
 * 卡片永远停在「处理中」，而京东那边可能已经建了单——最坏的一种不一致。
 * 所以回调只做两件确定的事：落证据、排任务；建单交给 Worker，结果另发一张卡。
 */
@Service
public class PreShipConfirmInteractionService {

    /** 异步建单任务类型；与业务卡投递任务分开领取，互不抢单。 */
    public static final String TASK_TYPE = "PRESHIP_CONFIRM";

    private static final Logger log = LoggerFactory.getLogger(PreShipConfirmInteractionService.class);
    private static final int MAX_ATTEMPTS = 3;

    private final JdbcTemplate jdbc;
    private final AsyncTaskStore tasks;

    public PreShipConfirmInteractionService(JdbcTemplate jdbc, AsyncTaskStore tasks) {
        this.jdbc = jdbc;
        this.tasks = tasks;
    }

    /** 回调判定结果：{@code cardText} 是要回给点击人的一句话，{@code accepted} 决定是否排建单任务。 */
    public record Outcome(boolean accepted, String businessCode, String cardText) {}

    /**
     * 处理一次 preship 卡片点击。
     *
     * <p>本方法**永不抛异常**：回调线程炸掉的表现是点击石沉大海，比给出一句"处理失败"糟得多。
     */
    @Transactional
    public Outcome handle(JsonNode frame) {
        JsonNode body = frame.path("body");
        JsonNode event = body.path("event");
        String msgId = body.path("msgid").asText("");
        String taskIdRaw = firstNonBlank(
                event.path("taskid").asText(""), event.path("task_id").asText(""));
        String buttonKey = firstNonBlank(
                event.path("eventkey").asText(""), event.path("event_key").asText(""));
        String actor = body.path("from").path("userid").asText("");
        String chatId = body.path("chatid").asText("");

        Optional<WecomTaskId> parsed = WecomTaskId.parse(taskIdRaw);
        if (parsed.isEmpty() || !PreShipConfirmCard.DOMAIN.equals(parsed.get().domain())) {
            return new Outcome(false, "WECOM_CARD_TASK_ID_INVALID", "无法识别这张卡片，请回后台处理");
        }
        WecomTaskId taskId = parsed.get();

        if (actor.isBlank()) {
            // 点卡片的是谁决定了这次建单的审计主体；认不出人就不能代他建真单
            return new Outcome(false, "WECOM_CARD_ACTOR_REQUIRED", "认不出点击人，未执行");
        }

        // 证据先落库：企微会重推，唯一约束天然幂等；msgid 为空时退化为 task_id+按钮，
        // 否则一条没有 msgid 的重推会被当成新点击，重复建单。
        String idempotencyKey = "preship-confirm:" + taskId.value() + ":" + buttonKey
                + ":" + (msgId.isBlank() ? "no-msgid" : msgId);

        // 版本断言：点旧卡等于对着过期事实下命令
        List<Long> current = jdbc.query(
                "SELECT lock_version FROM app.orders WHERE id = ?",
                (rs, rowNum) -> rs.getLong(1),
                taskId.entityId());
        if (current.isEmpty()) {
            return new Outcome(false, "ORDER_NOT_FOUND", "订单不存在，未执行");
        }
        if (!taskId.matchesCurrent(PreShipConfirmCard.DOMAIN, taskId.entityId(), current.getFirst())) {
            return new Outcome(
                    false, "VERSION_CONFLICT",
                    "这张卡已过期（订单已被改动），请到后台查看最新状态");
        }

        return switch (buttonKey) {
            case PreShipConfirmCard.CONFIRM_BUTTON_KEY -> confirm(taskId, actor, chatId, idempotencyKey);
            case PreShipConfirmCard.REJECT_BUTTON_KEY -> reject(taskId, actor);
            default -> new Outcome(false, "WECOM_CARD_BUTTON_UNKNOWN", "未知的按钮，未执行");
        };
    }

    private Outcome confirm(WecomTaskId taskId, String actor, String chatId, String idempotencyKey) {
        String payloadRef = "preship:" + taskId.entityId() + ":" + taskId.version()
                + ":" + actor + ":" + (chatId == null ? "" : chatId);
        tasks.enqueue(TASK_TYPE, payloadRef, idempotencyKey, MAX_ATTEMPTS);
        log.info("发货前确认已受理 task_id={} actor={}", taskId, actor);
        return new Outcome(true, "PRESHIP_CONFIRM_ACCEPTED", "已确认，正在向京东建单…");
    }

    /**
     * 驳回：不产生任何外部写，也不改订单状态。
     *
     * <p>「这单先别发」是人的判断，该落成什么业务状态需要另行裁决——此处不替人决定，
     * 更不把队列表当证据表用。重推导致重复回一句话是无害的，因此不做幂等收敛。
     */
    private Outcome reject(WecomTaskId taskId, String actor) {
        log.info("发货前确认被驳回 task_id={} actor={}", taskId, actor);
        return new Outcome(false, "PRESHIP_REJECTED", "已驳回，这一单不会发出");
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : (second == null ? "" : second);
    }
}
