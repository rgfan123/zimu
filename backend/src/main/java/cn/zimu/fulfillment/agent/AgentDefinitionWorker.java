package cn.zimu.fulfillment.agent;

import cn.zimu.fulfillment.message.AsyncTaskStore;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定义域写动作后台 Worker（meta-agent-platform-impl 11）：租约式领取 {@code AGENT_*}
 * 任务并执行（与消息链路 {@code InterpretationWorker} / QUALITY {@code QualityEvalWorker}
 * 同款 Spring Worker 模式）。多 Worker 共享 {@code app.async_tasks} 时必须按类型领取
 * （09 票约定），本 Worker 逐个类型循环领取，不抢 INTERPRET_MESSAGE / QUALITY_EVAL。
 *
 * <p>执行失败收口：{@link AgentDefinitionTaskFailure} 携带稳定错误码（与
 * {@code agent_runs.error_type} 同空间）→ 任务行 FAILED（maxAttempts=1，业务失败不重试，
 * 幂等由目标状态承担）；意外异常同样收口任务并标记运行行 ASYNC_TASK_FAILED。运行行的
 * SUCCESS/FAILED 收口由服务在业务事务外完成（观测失败隔离），Worker 只负责任务行。
 */
@Component
public class AgentDefinitionWorker {

    private static final Logger log = LoggerFactory.getLogger(AgentDefinitionWorker.class);

    private static final List<String> TASK_TYPES = List.of(
            AgentDefinitionWriteService.TASK_DRAFT_CREATE,
            AgentDefinitionWriteService.TASK_CONFIRM,
            AgentDefinitionWriteService.TASK_REJECT,
            AgentDefinitionWriteService.TASK_SET_ENABLED,
            AgentDefinitionWriteService.TASK_ROLLBACK);

    private final AsyncTaskStore taskStore;
    private final AgentDefinitionWriteService service;
    private final boolean enabled;
    private final Duration lease;
    private final Duration backoff;
    private final String owner = "agent-worker-" + UUID.randomUUID();

    public AgentDefinitionWorker(
            AsyncTaskStore taskStore,
            AgentDefinitionWriteService service,
            @Value("${app.agent-worker.enabled:true}") boolean enabled,
            @Value("${app.agent-worker.lease-seconds:30}") long leaseSeconds,
            @Value("${app.agent-worker.backoff-seconds:5}") long backoffSeconds) {
        this.taskStore = taskStore;
        this.service = service;
        this.enabled = enabled;
        this.lease = Duration.ofSeconds(leaseSeconds);
        this.backoff = Duration.ofSeconds(backoffSeconds);
    }

    @Scheduled(fixedDelayString = "${app.agent-worker.poll-ms:500}")
    public void poll() {
        if (!enabled) {
            return;
        }
        for (String taskType : TASK_TYPES) {
            while (true) {
                Optional<AsyncTaskStore.AsyncTask> claimed = taskStore.claim(taskType, owner, lease);
                if (claimed.isEmpty()) {
                    break;
                }
                process(claimed.get());
            }
        }
    }

    private void process(AsyncTaskStore.AsyncTask task) {
        try {
            switch (task.taskType()) {
                case AgentDefinitionWriteService.TASK_DRAFT_CREATE -> service.executeDraftCreate(task);
                case AgentDefinitionWriteService.TASK_CONFIRM -> service.executeConfirm(task);
                case AgentDefinitionWriteService.TASK_REJECT -> service.executeReject(task);
                case AgentDefinitionWriteService.TASK_SET_ENABLED -> service.executeSetEnabled(task);
                case AgentDefinitionWriteService.TASK_ROLLBACK -> service.executeRollback(task);
                default -> throw new IllegalStateException("未知任务类型: " + task.taskType());
            }
            taskStore.succeed(task.id(), owner);
        } catch (AgentDefinitionTaskFailure ex) {
            log.warn("定义域任务 {} ({}) 失败: {} — {}", task.id(), task.taskType(), ex.code(), ex.getMessage());
            taskStore.fail(task.id(), owner, ex.code(), backoff);
        } catch (Exception ex) {
            log.warn("定义域任务 {} ({}) 执行异常: {}", task.id(), task.taskType(), ex.getClass().getSimpleName());
            service.markRunFailed(task, "ASYNC_TASK_FAILED");
            taskStore.fail(task.id(), owner, "ASYNC_TASK_FAILED", backoff);
        }
    }
}
