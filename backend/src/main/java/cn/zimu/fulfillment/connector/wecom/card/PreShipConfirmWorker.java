package cn.zimu.fulfillment.connector.wecom.card;

import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.web.CommandContext;
import cn.zimu.fulfillment.fulfillment.ShipmentJdOutboundCommand;
import cn.zimu.fulfillment.fulfillment.ShipmentJdOutboundService;
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
    private final ShipmentJdOutboundService outbound;
    private final WecomBusinessCardEnqueuer cards;
    private final boolean enabled;
    private final Duration lease;
    private final String owner = "preship-confirm-worker-" + UUID.randomUUID();

    public PreShipConfirmWorker(
            AsyncTaskStore tasks,
            JdbcTemplate jdbc,
            ShipmentJdOutboundService outbound,
            WecomBusinessCardEnqueuer cards,
            @Value("${app.wecom-business-card.enabled:${app.wecom.enabled:false}}") boolean enabled,
            @Value("${app.wecom-business-card.lease-seconds:60}") long leaseSeconds) {
        this.tasks = tasks;
        this.jdbc = jdbc;
        this.outbound = outbound;
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

        List<Long> shipmentIds = jdbc.query(
                """
                SELECT s.id FROM app.shipments s
                 WHERE s.order_id = ?
                   AND s.shipment_status NOT IN ('SHIPPED', 'CANCELLED')
                 ORDER BY s.id
                """,
                (rs, rowNum) -> rs.getLong(1),
                payload.orderId());
        if (shipmentIds.isEmpty()) {
            log.warn("发货前确认无可建单的发货批次 order_id={}", payload.orderId());
            tasks.failTerminal(task.id(), owner, "PRESHIP_NO_OPEN_SHIPMENT");
            return;
        }

        String operator = "wecom:" + payload.actor();
        CommandContext context = new CommandContext(
                "preship-" + task.id(), null, operator, operator);

        for (long shipmentId : shipmentIds) {
            try {
                outbound.submit(
                        shipmentId,
                        new ShipmentJdOutboundCommand(),
                        // 幂等键绑住 (发货批次, 订单版本)：同一张卡被重复点击、
                        // 或企微重推同一事件，都只会建出一张京东出库单
                        "preship-" + shipmentId + "-v" + payload.version(),
                        context);
                log.info("企微确认建单成功 shipment_id={} operator={}", shipmentId, operator);
            } catch (BusinessException ex) {
                // 建单失败已由 ShipmentJdOutboundService 落审计并触发 jd-outbound 失败卡，
                // 这里只负责收口任务，不重复告警
                log.warn(
                        "企微确认建单被拒 shipment_id={} code={} msg={}",
                        shipmentId, ex.getBusinessCode(), ex.getMessage());
                tasks.failTerminal(task.id(), owner, "PRESHIP_SUBMIT_" + ex.getBusinessCode());
                return;
            } catch (RuntimeException ex) {
                // 结局未知（可能已在京东侧建单）：交给租约超时后重试，
                // 幂等键保证重试不会建出第二张单
                log.error("企微确认建单异常 shipment_id={}", shipmentId, ex);
                tasks.fail(task.id(), owner, "PRESHIP_SUBMIT_EXCEPTION", Duration.ofSeconds(30));
                return;
            }
        }
        tasks.succeed(task.id(), owner);
        enqueueResultCard(payload.orderId());
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

    /** {@code preship:{orderId}:{version}:{actor}:{chatId}}；chatId 可为空。 */
    record Payload(long orderId, long version, String actor, String chatId) {
        static Payload parse(String payloadRef) {
            String[] parts = payloadRef.split(":", 5);
            if (parts.length < 4 || !"preship".equals(parts[0])) {
                throw new IllegalArgumentException("非法的确认任务载荷: " + payloadRef);
            }
            return new Payload(
                    Long.parseLong(parts[1]),
                    Long.parseLong(parts[2]),
                    parts[3],
                    parts.length > 4 ? parts[4] : "");
        }
    }
}
