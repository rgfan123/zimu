package cn.zimu.fulfillment.followup;

import cn.zimu.fulfillment.message.AsyncTaskStore;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Durable worker for confirmed +1 decisions; callback threads never run these projections. */
@Component
public class BusinessFollowUpApprovalWorker {

    private static final Logger log = LoggerFactory.getLogger(BusinessFollowUpApprovalWorker.class);

    private final AsyncTaskStore tasks;
    private final BusinessFollowUpApprovalApplication application;
    private final boolean enabled;
    private final Duration lease;
    private final String owner = "followup-approval-worker-" + UUID.randomUUID();

    public BusinessFollowUpApprovalWorker(
            AsyncTaskStore tasks,
            BusinessFollowUpApprovalApplication application,
            @Value("${app.followup-approval-worker.enabled:false}") boolean enabled,
            @Value("${app.followup-approval-worker.lease-seconds:30}") long leaseSeconds) {
        this.tasks = tasks;
        this.application = application;
        this.enabled = enabled;
        this.lease = Duration.ofSeconds(Math.max(1, leaseSeconds));
    }

    @Scheduled(fixedDelayString = "${app.followup-approval-worker.poll-ms:500}")
    public void poll() {
        if (!enabled) {
            return;
        }
        while (true) {
            Optional<AsyncTaskStore.AsyncTask> claimed = tasks.claim(
                    BusinessFollowUpApprovalApplication.TASK_TYPE, owner, lease);
            if (claimed.isEmpty()) {
                return;
            }
            try {
                if ("FINALIZING".equals(claimed.get().status())) {
                    application.resumeFinalization(claimed.get(), owner);
                } else {
                    application.apply(claimed.get(), owner);
                }
            } catch (RuntimeException ex) {
                log.warn("Business Follow-up Approval task {} failed", claimed.get().id(), ex);
                if (!"FINALIZING".equals(claimed.get().status())) {
                    application.recordFailure(
                            claimed.get(),
                            owner,
                            "FOLLOWUP_APPROVAL_APPLY_FAILED",
                            Duration.ofSeconds(5));
                }
            }
        }
    }
}
