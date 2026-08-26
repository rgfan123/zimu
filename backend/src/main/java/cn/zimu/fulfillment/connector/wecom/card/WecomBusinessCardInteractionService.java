package cn.zimu.fulfillment.connector.wecom.card;

import cn.zimu.fulfillment.message.AsyncTaskStore;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 业务卡回调处理：按 {@link WecomTaskId} 的域分派到各自的动作。
 *
 * <p><b>为什么不复用订单草稿卡那套</b>：那套的 {@code CardEventInput} 把 {@code orderDraftId}
 * 编进了结构里，整条链路围绕草稿建模。业务卡是另一个实体族（订单 / 复核事项 / 告警），
 * 塞进去只会让两边都变形。共用的是 {@link WecomTaskId} 的版本断言语义，不是数据结构。
 *
 * <p><b>只承载零参数且幂等的动作</b>。需要选客户、选 SKU、填数量的处置一律回后台——
 * 把带参动作压进按钮，等于让人点了才发现做不成。
 *
 * <p><b>确认发货是异步的</b>：企微给的更新窗口是 5 秒，而京东建单（{@code addSoOrder}）
 * 是外部写、耗时不可控。把建单压进 5 秒里，结局是窗口过期后卡片永远停在「处理中」，
 * 而京东那边可能已经建了单——最坏的一种不一致。所以回调只做两件确定的事：
 * 落证据、排任务；建单交给 Worker，结果另发一张卡。
 */
@Service
public class WecomBusinessCardInteractionService {

    /** 异步建单任务类型；与业务卡投递任务分开领取，互不抢单。 */
    public static final String TASK_TYPE = "PRESHIP_CONFIRM";

    /** 本服务负责的域。不在此列的 task_id 留给订单草稿卡按原逻辑处理。 */
    public static final Set<String> DOMAINS = Set.of(
            PreShipConfirmCard.DOMAIN,
            ReviewCaseCard.DOMAIN,
            OperationalAlertCard.DOMAIN,
            JdOutboundFailureCard.DOMAIN,
            ShipmentResultCard.DOMAIN);

    private static final Logger log = LoggerFactory.getLogger(WecomBusinessCardInteractionService.class);
    private static final int MAX_ATTEMPTS = 3;

    private final JdbcTemplate jdbc;
    private final AsyncTaskStore tasks;

    public WecomBusinessCardInteractionService(JdbcTemplate jdbc, AsyncTaskStore tasks) {
        this.jdbc = jdbc;
        this.tasks = tasks;
    }

    /**
     * 回调判定结果。
     *
     * @param accepted     是否受理（受理才有后续动作）
     * @param businessCode 稳定业务码，落证据用
     * @param title        回执卡的标题
     * @param message      回执卡的正文——要让点击人一眼知道「点完之后发生了什么」
     */
    public record Outcome(boolean accepted, String businessCode, String title, String message) {}

    /** task_id 是否属于本服务；解析不出来或域不认识的一律留给订单草稿卡。 */
    public static boolean handles(String rawTaskId) {
        return WecomTaskId.parse(rawTaskId).map(id -> DOMAINS.contains(id.domain())).orElse(false);
    }

    /**
     * 处理一次业务卡点击。
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

        Optional<WecomTaskId> parsed = WecomTaskId.parse(taskIdRaw);
        if (parsed.isEmpty() || !DOMAINS.contains(parsed.get().domain())) {
            return new Outcome(
                    false, "WECOM_CARD_TASK_ID_INVALID", "无法识别这张卡片", "请回后台处理");
        }
        WecomTaskId taskId = parsed.get();

        if (actor.isBlank()) {
            // 点卡片的是谁决定了这次动作的审计主体；认不出人就什么都不做
            return new Outcome(
                    false, "WECOM_CARD_ACTOR_REQUIRED", "未执行", "认不出点击人，无法记录操作主体");
        }

        // 企微会重推同一事件；幂等键含 msgid，收敛由 async_tasks 的唯一约束承担。
        // msgid 为空时退化为 task_id + 按钮，否则一条没有 msgid 的重推会被当成新点击。
        String idempotencyKey = "wbc:" + taskId.value() + ":" + buttonKey
                + ":" + (msgId.isBlank() ? "no-msgid" : msgId);

        return switch (taskId.domain()) {
            case PreShipConfirmCard.DOMAIN -> preship(taskId, buttonKey, actor, idempotencyKey);
            case ReviewCaseCard.DOMAIN -> review(taskId, buttonKey, actor);
            case OperationalAlertCard.DOMAIN -> alert(taskId, buttonKey, actor);
            case JdOutboundFailureCard.DOMAIN -> new Outcome(
                    false, "JD_OUTBOUND_RETRY_NOT_WIRED",
                    "暂未接线", "京东重试建单请回后台执行");
            default -> new Outcome(
                    false, "WECOM_CARD_ACTION_NOT_APPLICABLE", "这张卡没有可执行的动作", "它是事后播报");
        };
    }

    // ------------------------------------------------------------------
    // preship：确认发货 / 驳回
    // ------------------------------------------------------------------

    private Outcome preship(WecomTaskId taskId, String buttonKey, String actor, String idempotencyKey) {
        List<Long> current = jdbc.query(
                "SELECT lock_version FROM app.orders WHERE id = ?",
                (rs, rowNum) -> rs.getLong(1),
                taskId.entityId());
        if (current.isEmpty()) {
            return new Outcome(false, "ORDER_NOT_FOUND", "未执行", "订单不存在");
        }
        if (!taskId.matchesCurrent(taskId.domain(), taskId.entityId(), current.getFirst())) {
            // 点旧卡等于对着过期事实下命令
            return new Outcome(
                    false, "VERSION_CONFLICT", "这张卡已过期",
                    "订单已被改动，请到后台查看最新状态");
        }
        return switch (buttonKey) {
            case PreShipConfirmCard.CONFIRM_BUTTON_KEY -> {
                tasks.enqueue(
                        TASK_TYPE,
                        "preship:" + taskId.entityId() + ":" + taskId.version() + ":" + actor + ":",
                        idempotencyKey,
                        MAX_ATTEMPTS);
                log.info("发货前确认已受理 task_id={} actor={}", taskId, actor);
                yield new Outcome(
                        true, "PRESHIP_CONFIRM_ACCEPTED", "已确认，正在建单",
                        "正在向京东建出库单，建成后会再发一张结果卡给你");
            }
            // 驳回不产生外部写，也不改订单状态——「这单先别发」该落成什么业务状态
            // 需要另行裁决，此处不替人决定。
            case PreShipConfirmCard.REJECT_BUTTON_KEY -> {
                log.info("发货前确认被驳回 task_id={} actor={}", taskId, actor);
                yield new Outcome(false, "PRESHIP_REJECTED", "已驳回", "这一单不会发出");
            }
            default -> new Outcome(false, "WECOM_CARD_BUTTON_UNKNOWN", "未执行", "不认识这个按钮");
        };
    }

    // ------------------------------------------------------------------
    // review：我来处理（认领）
    // ------------------------------------------------------------------

    /**
     * 认领不是处置：只写 {@code claimed_by/claimed_at}，事项仍是 OPEN，
     * {@code resolution_version} 不推进——推进了会让卡上的版本断言立刻失效，
     * 认领人自己反而点不动后续按钮。
     */
    private Outcome review(WecomTaskId taskId, String buttonKey, String actor) {
        if (!ReviewCaseCard.CLAIM_BUTTON_KEY.equals(buttonKey)) {
            return new Outcome(false, "WECOM_CARD_BUTTON_UNKNOWN", "未执行", "不认识这个按钮");
        }
        List<String> existing = jdbc.query(
                """
                SELECT COALESCE(claimed_by, '') FROM app.review_cases
                 WHERE id = ? AND status = 'OPEN' AND resolution_version = ?
                """,
                (rs, rowNum) -> rs.getString(1),
                taskId.entityId(),
                taskId.version());
        if (existing.isEmpty()) {
            return new Outcome(
                    false, "REVIEW_CASE_NOT_OPEN", "这张卡已过期",
                    "该事项已被处理或版本已推进");
        }
        String claimedBy = existing.getFirst();
        if (!claimedBy.isBlank()) {
            // 已被人认领：如实说是谁，而不是假装认领成功
            return new Outcome(
                    false, "REVIEW_CASE_ALREADY_CLAIMED", "已有人认领",
                    claimedBy.equals("wecom:" + actor) ? "你已经认领过了" : claimedBy + " 正在处理");
        }
        jdbc.update(
                """
                UPDATE app.review_cases
                   SET claimed_by = ?, claimed_at = now(), updated_at = now()
                 WHERE id = ? AND status = 'OPEN' AND claimed_by IS NULL
                """,
                "wecom:" + actor,
                taskId.entityId());
        log.info("复核事项已认领 case_id={} actor={}", taskId.entityId(), actor);
        return new Outcome(true, "REVIEW_CASE_CLAIMED", "已认领", "这件事挂在你名下了");
    }

    // ------------------------------------------------------------------
    // alert：知道了
    // ------------------------------------------------------------------

    private Outcome alert(WecomTaskId taskId, String buttonKey, String actor) {
        if (!OperationalAlertCard.ACKNOWLEDGE_BUTTON_KEY.equals(buttonKey)) {
            return new Outcome(false, "WECOM_CARD_BUTTON_UNKNOWN", "未执行", "不认识这个按钮");
        }
        // 表约束要求 ACKNOWLEDGED 必须同时有确认人与确认时间——少写一个就整条 UPDATE 失败，
        // 表现是「点了知道了，告警还在」。约束是对的：没有确认人的「已确认」没有意义。
        int updated = jdbc.update(
                """
                UPDATE app.operational_alerts
                   SET status = 'ACKNOWLEDGED',
                       acknowledged_by = ?,
                       acknowledged_at = now(),
                       lock_version = lock_version + 1,
                       updated_at = now()
                 WHERE id = ? AND status = 'OPEN' AND lock_version = ?
                """,
                "wecom:" + actor,
                taskId.entityId(),
                taskId.version());
        if (updated == 0) {
            return new Outcome(
                    false, "ALERT_NOT_OPEN", "这张卡已过期", "该告警已被确认或版本已推进");
        }
        log.info("运营告警已确认 alert_id={} actor={}", taskId.entityId(), actor);
        return new Outcome(true, "ALERT_ACKNOWLEDGED", "已知悉", "该告警不再提醒");
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : (second == null ? "" : second);
    }
}
