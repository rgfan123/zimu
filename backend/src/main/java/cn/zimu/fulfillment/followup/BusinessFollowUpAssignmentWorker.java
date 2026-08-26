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

/** Durable local projection/execution worker. It performs no network call in foundation #148. */
@Component
public class BusinessFollowUpAssignmentWorker {
    private static final Logger log = LoggerFactory.getLogger(BusinessFollowUpAssignmentWorker.class);
    private final AsyncTaskStore tasks;
    private final BusinessFollowUpAssignmentApplication application;
    private final boolean enabled;
    private final Duration lease;
    private final Duration backoff;
    private final String owner = "followup-assignment-worker-" + UUID.randomUUID();

    public BusinessFollowUpAssignmentWorker(
            AsyncTaskStore tasks,
            BusinessFollowUpAssignmentApplication application,
            @Value("${app.followup-assignment-worker.enabled:false}") boolean enabled,
            @Value("${app.followup-assignment-worker.lease-seconds:30}") long leaseSeconds,
            @Value("${app.followup-assignment-worker.backoff-seconds:5}") long backoffSeconds) {
        this.tasks = tasks;
        this.application = application;
        this.enabled = enabled;
        this.lease = Duration.ofSeconds(Math.max(1, leaseSeconds));
        this.backoff = Duration.ofSeconds(Math.max(1, backoffSeconds));
    }

    @Scheduled(fixedDelayString = "${app.followup-assignment-worker.poll-ms:500}")
    public void poll() {
        if (!enabled) return;
        drain(BusinessFollowUpAssignmentApplication.PROJECTION_TASK_TYPE);
        drain(BusinessFollowUpAssignmentApplication.EXECUTION_TASK_TYPE);
    }

    private void drain(String taskType) {
        while (true) {
            Optional<AsyncTaskStore.AsyncTask> claimed = tasks.claim(taskType, owner, lease);
            if (claimed.isEmpty()) return;
            process(claimed.get());
        }
    }

    void process(AsyncTaskStore.AsyncTask task) {
        try {
            if (BusinessFollowUpAssignmentApplication.PROJECTION_TASK_TYPE.equals(task.taskType())) {
                if ("FINALIZING".equals(task.status())) application.resumeProjectionFinalization(task, owner);
                else application.project(task, owner);
            } else if (BusinessFollowUpAssignmentApplication.EXECUTION_TASK_TYPE.equals(task.taskType())) {
                if ("FINALIZING".equals(task.status())) application.resumeExecutionFinalization(task, owner);
                else application.execute(task, owner);
            } else {
                throw new IllegalArgumentException("Unsupported Assignment task type: " + task.taskType());
            }
        } catch (RuntimeException ex) {
            log.warn("Business Follow-up Assignment task {} failed", task.id(), ex);
            if ("FINALIZING".equals(task.status())) return;
            if (BusinessFollowUpAssignmentApplication.EXECUTION_TASK_TYPE.equals(task.taskType())) {
                application.recordExecutionFailure(task, owner, "ASSIGNMENT_EXECUTION_FAILED", backoff);
            } else {
                application.recordProjectionFailure(
                        task, owner, "ASSIGNMENT_PROJECTION_FAILED", backoff);
            }
        }
    }
}
