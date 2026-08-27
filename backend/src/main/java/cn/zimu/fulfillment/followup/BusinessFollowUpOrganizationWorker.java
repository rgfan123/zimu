package cn.zimu.fulfillment.followup;

import cn.zimu.fulfillment.followup.BusinessFollowUpOrganizationService.FollowUpOrganizationException;
import cn.zimu.fulfillment.message.AsyncTaskStore;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Recoverable, type-scoped worker for BUSINESS_FOLLOWUP_ORGANIZE tasks. */
@Component
public class BusinessFollowUpOrganizationWorker {

    private static final Logger log = LoggerFactory.getLogger(BusinessFollowUpOrganizationWorker.class);

    private final AsyncTaskStore tasks;
    private final BusinessFollowUpOrganizationService organization;
    private final BusinessFollowUpDraftApplicationService application;
    private final boolean enabled;
    private final Duration lease;
    private final Duration backoff;
    private final String owner = "followup-worker-" + UUID.randomUUID();

    public BusinessFollowUpOrganizationWorker(
            AsyncTaskStore tasks,
            BusinessFollowUpOrganizationService organization,
            BusinessFollowUpDraftApplicationService application,
            @Value("${app.followup-worker.enabled:false}") boolean enabled,
            @Value("${app.followup-worker.lease-seconds:30}") long leaseSeconds,
            @Value("${app.followup-worker.backoff-seconds:5}") long backoffSeconds) {
        this.tasks = tasks;
        this.organization = organization;
        this.application = application;
        this.enabled = enabled;
        this.lease = Duration.ofSeconds(Math.max(1, leaseSeconds));
        this.backoff = Duration.ofSeconds(Math.max(1, backoffSeconds));
    }

    @Scheduled(fixedDelayString = "${app.followup-worker.poll-ms:500}")
    public void poll() {
        if (!enabled) {
            return;
        }
        while (true) {
            Optional<AsyncTaskStore.AsyncTask> claimed = tasks.claim(
                    BusinessFollowUpService.ORGANIZE_TASK_TYPE, owner, lease);
            if (claimed.isEmpty()) {
                return;
            }
            process(claimed.get());
        }
    }

    void process(AsyncTaskStore.AsyncTask task) {
        try {
            if ("FINALIZING".equals(task.status())) {
                application.resumeFinalization(task, owner);
            } else {
                organization.organize(task, owner, lease);
            }
        } catch (RuntimeException ex) {
            String code = ex instanceof FollowUpOrganizationException failure
                    ? failure.code()
                    : "FOLLOWUP_ORGANIZATION_FAILED";
            if ("FOLLOWUP_TASK_LEASE_LOST".equals(code)) {
                log.info("Business Follow-up task {} lease was lost; the current owner stops", task.id());
                return;
            }
            log.warn(
                    "Business Follow-up task {} failed with code {} and exception type {}",
                    task.id(), code, ex.getClass().getSimpleName());
            application.recordFailure(task, owner, code, backoff);
        }
    }
}
