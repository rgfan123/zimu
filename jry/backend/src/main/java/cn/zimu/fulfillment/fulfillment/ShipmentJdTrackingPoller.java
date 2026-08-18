package cn.zimu.fulfillment.fulfillment;

import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 默认关闭的 JD Shipment 运单轮询器；与手工入口复用同一应用用例。 */
@Component
public class ShipmentJdTrackingPoller {

    private static final Logger log = LoggerFactory.getLogger(ShipmentJdTrackingPoller.class);

    private final ShipmentJdTrackingBackfillService service;
    private final boolean enabled;
    private final int batchSize;
    private final Duration minInterval;

    public ShipmentJdTrackingPoller(
            ShipmentJdTrackingBackfillService service,
            @Value("${app.jd.tracking-backfill.enabled:false}") boolean enabled,
            @Value("${app.jd.tracking-backfill.batch-size:20}") int batchSize,
            @Value("${app.jd.tracking-backfill.min-interval:PT1M}") Duration minInterval) {
        this.service = service;
        this.enabled = enabled;
        this.batchSize = Math.max(1, Math.min(batchSize, 200));
        this.minInterval = minInterval.isNegative() ? Duration.ZERO : minInterval;
    }

    @Scheduled(fixedDelayString = "${app.jd.tracking-backfill.poll-ms:60000}")
    public void poll() {
        if (!enabled) return;
        Instant cutoff = Instant.now().minus(minInterval);
        for (ShipmentJdTrackingBackfillService.Candidate candidate : service.pollingCandidates(batchSize)) {
            if (candidate.lastQueryAt() != null && candidate.lastQueryAt().isAfter(cutoff)) continue;
            // DB 持久的上次观察时刻就是候选 generation；不同实例即使时钟或
            // min-interval 配置漂移，也会为同一代候选生成同一幂等键。
            String generation = candidate.lastQueryAt() == null
                    ? "initial"
                    : candidate.lastQueryAt().getEpochSecond() + "-" + candidate.lastQueryAt().getNano();
            String key = "jd-track-poll-" + candidate.shipmentId() + "-" + generation;
            try {
                service.scheduledBackfill(candidate.shipmentId(), key);
            } catch (RuntimeException exception) {
                log.warn("JD tracking poll failed for shipment {}", candidate.shipmentId());
            }
        }
    }
}
