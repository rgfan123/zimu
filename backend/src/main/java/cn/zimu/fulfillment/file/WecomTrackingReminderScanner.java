package cn.zimu.fulfillment.file;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 到期未收齐运单的周期提醒扫描器（Issue #84）。
 *
 * <p>不给每条导出挂内存延迟链：每 N 秒扫一次到期索引（ACTIVE + next_reminder_at<=now），
 * 对每个候选在短事务内原子创建唯一 reminder delivery（UNIQUE (export_id, kind, sequence)）
 * 与幂等 async task；多实例并发/重复轮询只创建一个 sequence，重启后从持久化恢复，不重复
 * 轰炸也不漏。
 */
@Component
public class WecomTrackingReminderScanner {

    private static final Logger log = LoggerFactory.getLogger(WecomTrackingReminderScanner.class);

    private final FulfillmentExportWecomService service;
    private final boolean enabled;
    private final int batchLimit;

    @Autowired
    public WecomTrackingReminderScanner(
            FulfillmentExportWecomService service,
            @Value("${app.wecom-reminder.enabled:true}") boolean enabled,
            @Value("${app.wecom-reminder.scan-batch-limit:20}") int batchLimit) {
        this.service = service;
        this.enabled = enabled;
        this.batchLimit = Math.max(1, batchLimit);
    }

    @Scheduled(fixedDelayString = "${app.wecom-reminder.scan-ms:30000}")
    public void scan() {
        if (!enabled) {
            return;
        }
        try {
            int created = service.scanDueReminders(batchLimit);
            if (created > 0) {
                log.info("履约导出企微提醒扫描：本次创建 {} 条提醒 delivery", created);
            }
        } catch (RuntimeException ex) {
            // 扫描失败（数据库不可达等）由调度器记录；下一轮自然重试，不丢提醒
            log.warn("履约导出企微提醒扫描失败，下轮重试: {}", ex.getMessage());
        }
    }
}
