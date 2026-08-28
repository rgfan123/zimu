package cn.zimu.fulfillment.connector.wecom.card;

import cn.zimu.fulfillment.message.AsyncTaskStore;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
            BatchPreShipConfirmCard.DOMAIN,
            ReviewCaseCard.DOMAIN,
            OperationalAlertCard.DOMAIN,
            JdOutboundFailureCard.DOMAIN,
            ShipmentResultCard.DOMAIN,
            BatchConfirmedCard.DOMAIN,
            BusinessFollowUpResultCard.DOMAIN);

    /**
     * 播报卡的 ack 按钮：域 → 唯一合法的按钮 key。
     *
     * <p>这三张卡改成 {@code button_interaction} 之后就带了按钮，点击必须由本服务认领——
     * 域不在 {@link #DOMAINS} 里的话，点击会落回订单草稿卡处理器并报
     * {@code WECOM_CARD_TASK_ID_INVALID}，读者看到的是「无法识别这张卡片」。
     */
    private static final Map<String, String> BROADCAST_ACK_BUTTON_KEYS = Map.of(
            BatchConfirmedCard.DOMAIN, BatchConfirmedCard.ACKNOWLEDGE_BUTTON_KEY,
            ShipmentResultCard.DOMAIN, ShipmentResultCard.ACKNOWLEDGE_BUTTON_KEY,
            BusinessFollowUpResultCard.DOMAIN, BusinessFollowUpResultCard.ACKNOWLEDGE_BUTTON_KEY);

    private static final Logger log = LoggerFactory.getLogger(WecomBusinessCardInteractionService.class);
    private static final int MAX_ATTEMPTS = 3;

    private final JdbcTemplate jdbc;
    private final AsyncTaskStore tasks;
    private final WecomBusinessCardStore cards;

    public WecomBusinessCardInteractionService(
            JdbcTemplate jdbc, AsyncTaskStore tasks, WecomBusinessCardStore cards) {
        this.jdbc = jdbc;
        this.tasks = tasks;
        this.cards = cards;
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

    /**
     * 回执卡 task_id 前缀。aibot 协议没有按钮级禁用（官方 101032/101463 逐字核对，
     * update_button 只存在于自建应用回调），回执卡的「灰按钮」只是 style=4 的视觉灰、
     * 协议上仍可点——所以点它必须由本服务幂等收口，不能落回草稿卡处理器报错。
     */
    public static final String ACK_TASK_PREFIX = "ack_";

    /** task_id 是否属于本服务；解析不出来或域不认识的一律留给订单草稿卡。 */
    public static boolean handles(String rawTaskId) {
        if (rawTaskId != null && rawTaskId.startsWith(ACK_TASK_PREFIX)) {
            return true;
        }
        return WecomTaskId.parse(rawTaskId).map(id -> DOMAINS.contains(id.domain())).orElse(false);
    }

    /**
     * 从点击事件帧里取 task_id。
     *
     * <p><b>真实帧把它嵌在 {@code body.event.template_card_event} 里</b>，不是平铺在
     * {@code body.event} 上（2026-08-26 生产实证，wecom_events#10 原始载荷）。
     * 只读平铺键会一律取到空串，表现是业务卡按钮全部落回订单草稿卡的处理器、
     * 报 {@code WECOM_CARD_TASK_ID_INVALID}——也就是「点了没反应」。
     * 平铺键作为兜底保留：老回调示例里确实是平铺的，两种都认才不会因协议版本翻车。
     */
    public static String taskId(JsonNode frame) {
        JsonNode event = frame.path("body").path("event");
        JsonNode card = event.path("template_card_event");
        return firstNonBlank(
                card.path("task_id").asText(""),
                firstNonBlank(event.path("task_id").asText(""), event.path("taskid").asText("")));
    }

    /** 按钮 key，取法与 {@link #taskId} 同源：嵌套优先、平铺兜底。 */
    public static String buttonKey(JsonNode frame) {
        JsonNode event = frame.path("body").path("event");
        JsonNode card = event.path("template_card_event");
        return firstNonBlank(
                card.path("event_key").asText(""),
                firstNonBlank(event.path("event_key").asText(""), event.path("eventkey").asText("")));
    }

    /**
     * 处理一次业务卡点击。
     *
     * <p>本方法**永不抛异常**：回调线程炸掉的表现是点击石沉大海，比给出一句"处理失败"糟得多。
     */
    @Transactional
    public Outcome handle(JsonNode frame) {
        JsonNode body = frame.path("body");
        String msgId = body.path("msgid").asText("");
        String taskIdRaw = taskId(frame);
        String buttonKey = buttonKey(frame);
        String actor = body.path("from").path("userid").asText("");

        if (taskIdRaw.startsWith(ACK_TASK_PREFIX)) {
            // 点的是回执卡上的灰按钮：操作早已生效，这里只做幂等应答，不产生任何业务写
            return new Outcome(
                    true, "WECOM_CARD_ACK_NOOP", "操作早已生效",
                    "这张是回执卡，无需再点；最新进展以后续结果卡为准");
        }

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
        WecomBusinessCard card = cards.findSentByTaskId(taskIdRaw)
                .filter(value -> value.cardDomain().equals(taskId.domain()))
                .filter(value -> value.entityId() == taskId.entityId())
                .filter(value -> value.entityVersion() == taskId.version())
                .orElse(null);
        if (card == null) {
            return new Outcome(
                    false, "WECOM_CARD_NOT_SENT", "未执行", "这张卡未确认送达或授权已失效");
        }
        if (!matchesRoute(card, body, actor)) {
            return new Outcome(
                    false, "WECOM_CARD_ROUTE_MISMATCH", "未执行", "这次回调不属于卡片的送达会话");
        }

        // 企微会重推同一事件；幂等键含 msgid，收敛由 async_tasks 的唯一约束承担。
        // msgid 为空时退化为 task_id + 按钮，否则一条没有 msgid 的重推会被当成新点击。
        String idempotencyKey = "wbc:" + taskId.value() + ":" + buttonKey
                + ":" + (msgId.isBlank() ? "no-msgid" : msgId);

        return switch (taskId.domain()) {
            case PreShipConfirmCard.DOMAIN -> preship(taskId, buttonKey, actor, idempotencyKey);
            case BatchPreShipConfirmCard.DOMAIN -> batchPreship(taskId, buttonKey, actor, idempotencyKey);
            case ReviewCaseCard.DOMAIN -> review(taskId, buttonKey, actor);
            case OperationalAlertCard.DOMAIN -> alert(taskId, buttonKey, actor);
            case JdOutboundFailureCard.DOMAIN -> new Outcome(
                    false, "JD_OUTBOUND_RETRY_NOT_WIRED",
                    "暂未接线", "京东重试建单请回后台执行");
            case BatchConfirmedCard.DOMAIN,
                    ShipmentResultCard.DOMAIN,
                    BusinessFollowUpResultCard.DOMAIN -> broadcastAck(taskId, buttonKey, actor);
            default -> new Outcome(
                    false, "WECOM_CARD_ACTION_NOT_APPLICABLE", "这张卡没有可执行的动作", "它是事后播报");
        };
    }

    // ------------------------------------------------------------------
    // 播报卡：知道了（零业务写）
    // ------------------------------------------------------------------

    /**
     * 播报卡的「知道了」：**不产生任何业务写**。
     *
     * <p>这三张卡（整批确认已完成 / 发货结果 / 客户跟进审批终态）都是事后播报，动作早已发生。
     * 按钮存在的理由只有两个：给读者一个「我看到了」的收口动作，以及让卡型成为
     * {@code button_interaction}——从而不再需要 {@code text_notice} 强制的深链
     * （公网入口只有明文 HTTP，https-only 的深链规则给不出合法基址）。
     *
     * <p><b>不做版本断言</b>：{@code preship} / {@code alert} 那类按钮会改业务状态，点旧卡等于
     * 对着过期事实下命令，必须比对版本。这里没有任何状态可改，一次「我看到了」在哪个版本上
     * 都成立；反而是拿版本去卡它，会让读者点一张播报卡收到「这张卡已过期」，纯属噪音。
     *
     * <p>幂等因此是天然的：再点一次仍然是同一句回执，卡面由整卡替换换成 style=4 的灰态。
     */
    private Outcome broadcastAck(WecomTaskId taskId, String buttonKey, String actor) {
        if (!Objects.equals(BROADCAST_ACK_BUTTON_KEYS.get(taskId.domain()), buttonKey)) {
            return new Outcome(false, "WECOM_CARD_BUTTON_UNKNOWN", "未执行", "不认识这个按钮");
        }
        log.info(
                "播报卡已知悉 domain={} entity_id={} actor={}",
                taskId.domain(), taskId.entityId(), actor);
        return new Outcome(
                true, "WECOM_CARD_BROADCAST_ACKNOWLEDGED", "已知悉",
                "这张是事后播报，无需其它操作");
    }

    // ------------------------------------------------------------------
    // preship-batch：整批确认发货 / 驳回（一批一卡）
    // ------------------------------------------------------------------

    private Outcome batchPreship(WecomTaskId taskId, String buttonKey, String actor, String idempotencyKey) {
        // 版本断言：批版本 = 批内订单 lock_version 之和，与卡渲染同一口径
        List<Long> current = jdbc.query(
                """
                SELECT sum(o.lock_version)
                FROM app.orders o
                WHERE o.source_import_batch_id = ? AND o.data_scope = 'BUSINESS'
                GROUP BY o.source_import_batch_id
                """,
                (rs, rowNum) -> rs.getLong(1),
                taskId.entityId());
        if (current.isEmpty()) {
            return new Outcome(false, "IMPORT_BATCH_NOT_FOUND", "未执行", "批次不存在或没有订单");
        }
        if (!taskId.matchesCurrent(taskId.domain(), taskId.entityId(), current.getFirst())) {
            return new Outcome(
                    false, "VERSION_CONFLICT", "这张卡已过期",
                    "批内订单已被改动，请等新卡或回后台查看");
        }
        return switch (buttonKey) {
            case BatchPreShipConfirmCard.CONFIRM_BUTTON_KEY -> {
                tasks.enqueue(
                        TASK_TYPE,
                        "preship-batch:" + taskId.entityId() + ":" + taskId.version() + ":" + actor + ":",
                        idempotencyKey,
                        MAX_ATTEMPTS);
                log.info("整批发货前确认已受理 task_id={} actor={}", taskId, actor);
                yield new Outcome(
                        true, "PRESHIP_BATCH_CONFIRM_ACCEPTED", "已确认，整批建单中",
                        "正在为整批订单建出库单，建成后每单一张结果卡");
            }
            case BatchPreShipConfirmCard.REJECT_BUTTON_KEY -> {
                log.info("整批发货前确认被驳回 task_id={} actor={}", taskId, actor);
                yield new Outcome(false, "PRESHIP_BATCH_REJECTED", "已驳回", "这一批不会发出");
            }
            default -> new Outcome(false, "WECOM_CARD_BUTTON_UNKNOWN", "未执行", "不认识这个按钮");
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
        int updated = jdbc.update(
                """
                UPDATE app.review_cases
                   SET claimed_by = ?, claimed_at = now(), updated_at = now()
                 WHERE id = ? AND status = 'OPEN' AND resolution_version = ? AND claimed_by IS NULL
                """,
                "wecom:" + actor,
                taskId.entityId(),
                taskId.version());
        if (updated == 0) {
            return new Outcome(
                    false, "REVIEW_CASE_ALREADY_CLAIMED", "已有人认领", "该事项刚被其他人认领，请刷新后查看");
        }
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

    private static boolean matchesRoute(WecomBusinessCard card, JsonNode body, String actor) {
        String chatId = body.path("chatid").asText("");
        return switch (card.routeType()) {
            case "SINGLE" -> chatId.isBlank() && card.chatId().equals(actor);
            case "GROUP" -> !chatId.isBlank() && card.chatId().equals(chatId);
            default -> false;
        };
    }
}
