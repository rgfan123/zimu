package cn.zimu.fulfillment.file;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 重启可恢复的来源回填派生 Worker。 */
@Component
public class SourceReturnDerivationWorker {

    private final SourceReturnDerivationRunner runner;
    private final boolean enabled;

    public SourceReturnDerivationWorker(
            SourceReturnDerivationRunner runner,
            @Value("${app.source-return-worker.enabled:true}") boolean enabled) {
        this.runner = runner;
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${app.source-return-worker.poll-ms:1000}")
    public void poll() {
        if (enabled) runner.drainDue();
    }
}
