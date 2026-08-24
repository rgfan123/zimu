package cn.zimu.fulfillment.connector.wecom.card;

import cn.zimu.fulfillment.message.AsyncTaskStore;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 业务卡入队（#87/#88）：在业务事务内落投递行 + worker 任务。
 *
 * <p>**入队失败不得阻断业务**。卡片是通知手段，复核事项该建还是要建、告警该落还是要落；
 * 因为群发不出去就让业务写失败，是把通知的可用性凌驾于业务之上。因此本方法吞异常并
 * 记 ERROR 日志（与观测/审计的失败隔离契约一致）。
 */
@Service
public class WecomBusinessCardEnqueuer {

    public static final String TASK_TYPE = "WECOM_BUSINESS_CARD";
    private static final int MAX_ATTEMPTS = 3;

    private static final Logger log = LoggerFactory.getLogger(WecomBusinessCardEnqueuer.class);

    private final WecomBusinessCardStore cards;
    private final WecomBusinessCardSourceRegistry sources;
    private final AsyncTaskStore tasks;

    public WecomBusinessCardEnqueuer(
            WecomBusinessCardStore cards,
            WecomBusinessCardSourceRegistry sources,
            AsyncTaskStore tasks) {
        this.cards = cards;
        this.sources = sources;
        this.tasks = tasks;
    }

    /**
     * 为某个业务实体的某个版本排一张卡。
     *
     * @return 投递行 id；未注册来源 / 未配置路由 / 入队失败均返回 empty（业务不受影响）
     */
    @Transactional
    public Optional<Long> enqueue(WecomTaskId taskId) {
        try {
            Optional<WecomBusinessCardSource> source = sources.find(taskId.domain());
            if (source.isEmpty()) {
                log.warn("未注册的卡片来源域，跳过发卡: {}", taskId.domain());
                return Optional.empty();
            }
            Optional<WecomBusinessCardSource.Route> route = source.get().route(taskId.entityId());
            if (route.isEmpty()) {
                // 未配置会话路由是配置问题，不是故障：如实记录，不制造重试风暴
                log.info("未配置企微会话路由，跳过发卡: {}", taskId);
                return Optional.empty();
            }
            WecomBusinessCard card = cards.create(taskId, route.get().type(), route.get().chatId());
            if (!"PENDING".equals(card.status())) {
                // 同一版本已经排过卡：幂等返回，不重复入队
                return Optional.of(card.id());
            }
            tasks.enqueue(TASK_TYPE, "card:" + card.id(), "wecom-business-card:" + card.id(), MAX_ATTEMPTS);
            return Optional.of(card.id());
        } catch (RuntimeException ex) {
            log.error("企微业务卡入队失败，业务继续: {}", taskId, ex);
            return Optional.empty();
        }
    }
}
