package cn.zimu.fulfillment.message;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 持久化领取会话 Agent 前置路由任务；意外失败耗尽后仍最终补排原收单任务。 */
@Component
public class WecomChatAgentWorker {

    private static final Logger log = LoggerFactory.getLogger(WecomChatAgentWorker.class);

    private final AsyncTaskStore tasks;
    private final WecomChatAgentRoutingService routing;
    private final boolean enabled;
    private final Duration lease;
    private final Duration backoff;
    private final Duration claimErrorSuppressWindow;
    private final String owner = "wecom-chat-agent-worker-" + UUID.randomUUID();
    private volatile Instant claimSuppressUntil;

    public WecomChatAgentWorker(
            AsyncTaskStore tasks,
            WecomChatAgentRoutingService routing,
            boolean enabled,
            long leaseSeconds,
            long backoffSeconds) {
        this(tasks, routing, enabled, leaseSeconds, backoffSeconds, 60);
    }

    @Autowired
    public WecomChatAgentWorker(
            AsyncTaskStore tasks,
            WecomChatAgentRoutingService routing,
            @Value("${app.wecom-chat-agent-worker.enabled:${app.message-worker.enabled:true}}") boolean enabled,
            @Value("${app.wecom-chat-agent-worker.lease-seconds:330}") long leaseSeconds,
            @Value("${app.wecom-chat-agent-worker.backoff-seconds:5}") long backoffSeconds,
            @Value("${app.wecom-chat-agent-worker.claim-error-suppress-seconds:60}")
                    long claimErrorSuppressSeconds) {
        this.tasks = tasks;
        this.routing = routing;
        this.enabled = enabled;
        this.lease = Duration.ofSeconds(leaseSeconds);
        this.backoff = Duration.ofSeconds(backoffSeconds);
        this.claimErrorSuppressWindow = Duration.ofSeconds(claimErrorSuppressSeconds);
    }

    @Scheduled(fixedDelayString = "${app.wecom-chat-agent-worker.poll-ms:500}")
    public void poll() {
        if (!enabled) {
            return;
        }
        Instant suppressedUntil = claimSuppressUntil;
        if (suppressedUntil != null) {
            if (Instant.now().isBefore(suppressedUntil)) {
                return;
            }
            claimSuppressUntil = null;
        }
        while (true) {
            Optional<AsyncTaskStore.AsyncTask> claimed;
            try {
                claimed = tasks.claim(MessageSubmissionService.WECOM_CHAT_AGENT_TASK_TYPE, owner, lease);
            } catch (RuntimeException ex) {
                boolean firstFailure = claimSuppressUntil == null;
                claimSuppressUntil = Instant.now().plus(claimErrorSuppressWindow);
                if (firstFailure) {
                    log.warn(
                            "会话 Agent Worker 领取任务失败，{} 秒内暂停轮询",
                            claimErrorSuppressWindow.toSeconds(),
                            ex);
                }
                return;
            }
            if (claimed.isEmpty()) {
                claimSuppressUntil = null;
                return;
            }
            process(claimed.get());
        }
    }

    private void process(AsyncTaskStore.AsyncTask task) {
        try {
            if ("FINALIZING".equals(task.status())) {
                routing.resumeFinalFallback(task, task.lastError());
            } else {
                routing.route(task, lease);
            }
        } catch (RuntimeException ex) {
            log.warn(
                    "会话 Agent Worker 任务失败，转持久化重试 task_id={} error=WECOM_CHAT_AGENT_WORKER_FAILED",
                    task.id(),
                    ex);
            routing.recordWorkerFailure(task, "WECOM_CHAT_AGENT_WORKER_FAILED", backoff);
        }
    }
}
