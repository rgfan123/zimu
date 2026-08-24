package cn.zimu.fulfillment.connector.wecom.card;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 业务卡扫描器（#87/#88）：周期性问各个域「有什么该发卡的」，然后入队。
 *
 * <p>选扫描而不是在创建处埋点，是因为创建点根本收不拢——复核事项 37 处、
 * 运营告警 3 处绕过 Service 的裸 INSERT。逐处接线漏掉的那处，表现是「这类事项
 * 从来不推送」，而这种缺失没有任何报错，只能靠人发现。扫描覆盖所有创建路径，
 * 且完全只读，不可能让业务写失败。
 *
 * <p>两条硬约束防止首次开启时轰炸：
 * <ul>
 *   <li>{@code lookback}（默认 24h）：只看窗口内的实体，历史积压不补发；</li>
 *   <li>{@code batch-limit}（默认 20）：单域单次扫描上限，宁可分几轮发完。</li>
 * </ul>
 * 幂等由 task_id 唯一约束兜底——同一 (域, 实体, 版本) 重复扫到也只会有一张卡。
 */
@Component
public class WecomBusinessCardScanner {

    private static final Logger log = LoggerFactory.getLogger(WecomBusinessCardScanner.class);

    private final WecomBusinessCardSourceRegistry sources;
    private final WecomBusinessCardEnqueuer enqueuer;
    private final boolean enabled;
    private final Duration lookback;
    private final int batchLimit;

    public WecomBusinessCardScanner(
            WecomBusinessCardSourceRegistry sources,
            WecomBusinessCardEnqueuer enqueuer,
            @Value("${app.wecom-business-card.scan-enabled:${app.wecom-business-card.enabled:false}}")
                    boolean enabled,
            @Value("${app.wecom-business-card.lookback-hours:24}") long lookbackHours,
            @Value("${app.wecom-business-card.batch-limit:20}") int batchLimit) {
        this.sources = sources;
        this.enqueuer = enqueuer;
        this.enabled = enabled;
        this.lookback = Duration.ofHours(Math.max(1, lookbackHours));
        this.batchLimit = Math.max(1, batchLimit);
    }

    @Scheduled(fixedDelayString = "${app.wecom-business-card.scan-ms:30000}")
    public void scan() {
        if (!enabled) {
            return;
        }
        OffsetDateTime since = OffsetDateTime.now().minus(lookback);
        for (String domain : sources.domains()) {
            scanDomain(domain, since);
        }
    }

    /** 单域扫描：一个域挂了不影响其它域——通知能力不该整体因为一个查询出错而消失。 */
    private void scanDomain(String domain, OffsetDateTime since) {
        try {
            List<WecomTaskId> pending = sources.find(domain)
                    .map(source -> source.pending(since, batchLimit))
                    .orElse(List.of());
            if (pending.isEmpty()) {
                return;
            }
            int enqueued = 0;
            for (WecomTaskId taskId : pending) {
                if (enqueuer.enqueue(taskId).isPresent()) {
                    enqueued++;
                }
            }
            // 扫到上限时如实说明还有剩余，避免「扫过了」被读成「都发完了」
            log.info(
                    "业务卡扫描 domain={} 待发={} 入队={}{}",
                    domain,
                    pending.size(),
                    enqueued,
                    pending.size() >= batchLimit ? "（已达单次上限，剩余下一轮继续）" : "");
        } catch (RuntimeException ex) {
            log.error("业务卡扫描失败 domain={}", domain, ex);
        }
    }
}
