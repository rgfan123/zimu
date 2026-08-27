package cn.zimu.fulfillment.connector.wecom.card;

import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.web.CommandContext;
import cn.zimu.fulfillment.order.SourceBatchConfirmer;
import cn.zimu.fulfillment.message.AsyncTaskStore;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 企微「确认发货」的执行面：领 {@code PRESHIP_CONFIRM} 任务，调用与后台按钮完全同一条
 * 建单路径（{@link ShipmentJdOutboundService#submit}）。
 *
 * <p><b>不另开建单捷径</b>是本类唯一重要的设计约束。授权校验、阻塞项拒绝、幂等键、
 * 请求哈希、审计与告警全在那个 Service 里；绕过去等于让「从企微点的」和「从后台点的」
 * 走两套规则，而两套规则迟早会分叉——分叉的那天，真单已经建出去了。
 *
 * <p>操作人取点击人（{@code wecom:{userid}}），并同时作为 authenticatedOperator：
 * 长连接帧里的 {@code from.userid} 由企微签发，不是客户端自填的字段。
 * 该身份仍需在 {@code app.jd.outbound-authorized-operators} 白名单里，否则 403 ——
 * 「谁能建真单」的裁决权留在配置面，不因为换了入口就放宽。
 */
@Component
public class PreShipConfirmWorker {

    private static final Logger log = LoggerFactory.getLogger(PreShipConfirmWorker.class);

    private final AsyncTaskStore tasks;
    private final JdbcTemplate jdbc;
    private final SourceBatchConfirmer sourceBatches;
    private final WecomBusinessCardEnqueuer cards;
    private final boolean enabled;
    private final Duration lease;
    private final String owner = "preship-confirm-worker-" + UUID.randomUUID();

    public PreShipConfirmWorker(
            AsyncTaskStore tasks,
            JdbcTemplate jdbc,
            SourceBatchConfirmer sourceBatches,
            WecomBusinessCardEnqueuer cards,
            @Value("${app.wecom-business-card.enabled:${app.wecom.enabled:false}}") boolean enabled,
            @Value("${app.wecom-business-card.lease-seconds:60}") long leaseSeconds) {
        this.tasks = tasks;
        this.jdbc = jdbc;
        this.sourceBatches = sourceBatches;
        this.cards = cards;
        this.enabled = enabled;
        this.lease = Duration.ofSeconds(Math.max(60, leaseSeconds));
    }

    @Scheduled(fixedDelayString = "${app.wecom-business-card.poll-ms:1000}")
    public void poll() {
        if (!enabled) {
            return;
        }
        Optional<AsyncTaskStore.AsyncTask> task;
        try {
            task = tasks.claim(WecomBusinessCardInteractionService.TASK_TYPE, owner, lease);
        } catch (RuntimeException ex) {
            return;
        }
        task.ifPresent(this::process);
    }

    private void process(AsyncTaskStore.AsyncTask task) {
        Payload payload;
        try {
            payload = Payload.parse(task.payloadRef());
        } catch (RuntimeException ex) {
            // 载荷解析不出来重试多少次都一样，直接终态
            tasks.failTerminal(task.id(), owner, "PRESHIP_PAYLOAD_INVALID");
            return;
        }

        // 走「整批确认」而不是直接 submit 单个 shipment：
        // SKU_MAPPED 的订单**还没有 shipment**——shipment 是确认过程中才建的。
        // 直接去找现成 shipment 的写法在 SKU_MAPPED 订单上必然扑空，
        // 而那正是确认卡最常见的触发时点。
        long batchId;
        if (payload.batchScoped()) {
            // 整批卡：载荷里就是批次号
            batchId = payload.entityId();
        } else {
            List<Long> batchIds = jdbc.query(
                    "SELECT source_import_batch_id FROM app.orders WHERE id = ? AND source_import_batch_id IS NOT NULL",
                    (rs, rowNum) -> rs.getLong(1),
                    payload.entityId());
            if (batchIds.isEmpty()) {
                log.warn("发货前确认找不到来源批次 order_id={}", payload.entityId());
                tasks.failTerminal(task.id(), owner, "PRESHIP_NO_SOURCE_BATCH");
                return;
            }
            batchId = batchIds.getFirst();
        }

        String operator = "wecom:" + payload.actor();
        CommandContext context = new CommandContext(
                "preship-" + task.id(), null, operator, operator);
        // 幂等键绑住批次：同一张卡被重复点击、或企微重推同一事件，
        // 都只会确认一次、建一张京东出库单
        String idempotencyKey = payload.batchScoped()
                ? "preship-batch-" + batchId + "-v" + payload.version()
                : "preship-batch-" + batchId + "-order-" + payload.entityId()
                        + "-v" + payload.version();

        try {
            var confirmed = sourceBatches.confirmSourceBatch(batchId, idempotencyKey, context);
            // 与 SourceImportController 同一条纪律：只有首次执行才触发外部建单，
            // 幂等重放返回首次结果，不再发起新的京东调用
            if (!confirmed.replayed()) {
                sourceBatches.submitJdOutboundsForSourceBatch(batchId, context);
            }
            log.info("企微确认整批完成 batch_id={} entity_id={} operator={}",
                    batchId, payload.entityId(), operator);
        } catch (BusinessException ex) {
            // 失败已由确认/建单链路自己落审计与告警，这里只收口任务，不重复告警
            log.warn("企微确认被拒 batch_id={} code={} msg={}",
                    batchId, ex.getBusinessCode(), ex.getMessage());
            tasks.failTerminal(task.id(), owner, "PRESHIP_CONFIRM_" + ex.getBusinessCode());
            return;
        } catch (RuntimeException ex) {
            // 结局未知（可能已在京东侧建单）：交给租约超时后重试，
            // 幂等键保证重试不会建出第二张单
            log.error("企微确认异常 batch_id={}", batchId, ex);
            tasks.fail(task.id(), owner, "PRESHIP_CONFIRM_EXCEPTION", Duration.ofSeconds(30));
            return;
        }
        tasks.succeed(task.id(), owner);
        if (payload.batchScoped()) {
            // 整批：每单一张结果卡——读者点的是一张卡，要看的是每一单的结局
            jdbc.query(
                            "SELECT id FROM app.orders WHERE source_import_batch_id = ? ORDER BY id",
                            (rs, rowNum) -> rs.getLong(1),
                            batchId)
                    .forEach(this::enqueueResultCard);
        } else {
            enqueueResultCard(payload.entityId());
        }
    }

    /** 建单成功后播报：这张卡的读者刚点过确认，他要的是「建成了没有」。 */
    private void enqueueResultCard(long orderId) {
        try {
            Long version = jdbc.queryForObject(
                    "SELECT lock_version FROM app.orders WHERE id = ?", Long.class, orderId);
            if (version != null) {
                cards.enqueue(WecomTaskId.ofVersion(ShipmentResultCard.DOMAIN, orderId, version));
            }
        } catch (RuntimeException ex) {
            // 播报失败不得回退已建的真单
            log.warn("发货结果卡入队失败 order_id={}", orderId, ex);
        }
    }

    /**
     * {@code preship:{orderId}:{version}:{actor}:{chatId}}（单卡）或
     * {@code preship-batch:{batchId}:{version}:{actor}:{chatId}}（整批卡）；chatId 可为空。
     */
    record Payload(boolean batchScoped, long entityId, long version, String actor, String chatId) {
        static Payload parse(String payloadRef) {
            String[] parts = payloadRef.split(":", 5);
            boolean batch = "preship-batch".equals(parts[0]);
            if (parts.length < 4 || (!batch && !"preship".equals(parts[0]))) {
                throw new IllegalArgumentException("非法的确认任务载荷: " + payloadRef);
            }
            return new Payload(
                    batch,
                    Long.parseLong(parts[1]),
                    Long.parseLong(parts[2]),
                    parts[3],
                    parts.length > 4 ? parts[4] : "");
        }
    }
}
