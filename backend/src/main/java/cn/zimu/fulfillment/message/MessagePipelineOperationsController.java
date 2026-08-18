package cn.zimu.fulfillment.message;

import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.dto.PageResponse;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.web.CommandContext;
import cn.zimu.fulfillment.common.web.WriteCommands;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.Locale;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 消息链路后台运行可见性与保留清理（wecom-message-intake 12）：积压 / 重试中 / 最终失败 /
 * 媒体失败的筛选与摘要，以及 NON_BUSINESS 到期清理的手动触发。
 *
 * <p>全部端点受网关 Basic Auth 保护并校验 X-Operator；投影为最小必要摘要，最终失败只在后台
 * 可见和告警，不向原企微群补发任何处理消息。
 */
@RestController
@RequestMapping("/api/v1/admin/message-pipeline")
@Validated
public class MessagePipelineOperationsController {

    private final MessagePipelineQueryService queryService;
    private final MessageRetentionCleanupService cleanupService;

    public MessagePipelineOperationsController(
            MessagePipelineQueryService queryService, MessageRetentionCleanupService cleanupService) {
        this.queryService = queryService;
        this.cleanupService = cleanupService;
    }

    @GetMapping("/summary")
    public MessagePipelineSummaryDto summary(
            @RequestHeader(value = "X-Operator", required = false) String operator) {
        requireOperator(operator);
        return queryService.summary();
    }

    @GetMapping("/tasks")
    public PageResponse<AsyncTaskStore.AsyncTaskSummary> tasks(
            @RequestHeader(value = "X-Operator", required = false) String operator,
            @RequestParam(required = false) String scope,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int size) {
        requireOperator(operator);
        return queryService.tasks(parseScope(scope), status, page, size);
    }

    @GetMapping("/media-failures")
    public PageResponse<MessageMediaFailureDto> mediaFailures(
            @RequestHeader(value = "X-Operator", required = false) String operator,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int size) {
        requireOperator(operator);
        return queryService.mediaFailures(status, page, size);
    }

    /** 手动触发保留清理；任务本身幂等，可重复运行并记录审计摘要。 */
    @PostMapping("/cleanup")
    public RetentionCleanupReport cleanup(
            @RequestHeader(value = "X-Operator", required = false) String operator) {
        requireOperator(operator);
        CommandContext context = WriteCommands.writeContext(operator);
        return cleanupService.run(context.operator(), AuditActorType.HUMAN);
    }

    private static MessagePipelineQueryService.TaskScope parseScope(String scope) {
        if (scope == null || scope.isBlank()) {
            return MessagePipelineQueryService.TaskScope.ALL;
        }
        try {
            return MessagePipelineQueryService.TaskScope.valueOf(scope.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw BusinessException.badRequest("INVALID_TASK_SCOPE", "无效的任务筛选范围: " + scope);
        }
    }

    private static void requireOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            throw new BusinessException(401, "ADMIN_AUTH_REQUIRED", "管理后台查询需要认证");
        }
    }
}
