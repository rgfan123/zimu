package cn.zimu.fulfillment.connector.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 重启后把租约已失效的 SYNCING 收敛到安全失败或待对账。 */
@Component
@ConditionalOnProperty(
        name = "app.source-sync.recovery.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class SourceSyncRecoveryWorker {

    private static final Logger log = LoggerFactory.getLogger(SourceSyncRecoveryWorker.class);
    private final SourceSyncStore store;
    private final int batchSize;

    public SourceSyncRecoveryWorker(
            SourceSyncStore store,
            @Value("${app.source-sync.recovery.batch-size:50}") int batchSize) {
        this.store = store;
        this.batchSize = batchSize;
    }

    @Scheduled(
            initialDelayString = "${app.source-sync.recovery.initial-delay-ms:60000}",
            fixedDelayString = "${app.source-sync.recovery.poll-ms:60000}")
    public void recover() {
        int recovered = store.recoverExpiredSyncing(batchSize);
        if (recovered > 0) {
            log.warn("已恢复 {} 条过期 Shipment source-sync 执行", recovered);
        }
    }
}
