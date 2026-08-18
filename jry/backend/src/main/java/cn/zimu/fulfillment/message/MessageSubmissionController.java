package cn.zimu.fulfillment.message;

import cn.zimu.fulfillment.common.dto.PageResponse;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.idempotency.IdempotencyService;
import cn.zimu.fulfillment.common.idempotency.IdempotentResult;
import cn.zimu.fulfillment.common.web.CommandContext;
import cn.zimu.fulfillment.common.web.WriteCommands;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 消息提交与解释历史的管理查询与重新解释命令。 */
@RestController
@RequestMapping("/api/v1/message-submissions")
@Validated
public class MessageSubmissionController {

    private final MessageSubmissionQueryService queryService;
    private final MessageSubmissionService submissionService;
    private final IdempotencyService idempotency;

    public MessageSubmissionController(
            MessageSubmissionQueryService queryService,
            MessageSubmissionService submissionService,
            IdempotencyService idempotency) {
        this.queryService = queryService;
        this.submissionService = submissionService;
        this.idempotency = idempotency;
    }

    @GetMapping("/{submission_id}")
    public MessageSubmissionDetailDto detail(
            @RequestHeader(value = "X-Operator", required = false) String operator,
            @PathVariable("submission_id") String submissionId) {
        requireOperator(operator);
        return queryService.detail(WriteCommands.parseIdentifier(submissionId));
    }

    @PostMapping("/{submission_id}/reinterpret")
    public ResponseEntity<?> reinterpret(
            @PathVariable("submission_id") String submissionId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Operator") String operator,
            @RequestBody(required = false) ReinterpretRequest body) {
        long parsed = WriteCommands.parseIdentifier(submissionId);
        CommandContext context = WriteCommands.writeContext(operator);
        Map<String, Object> payload = Map.of("submission_id", parsed);
        IdempotentResult<MessageSubmissionDetailDto> result = idempotency.execute(
                "message_submission.reinterpret",
                WriteCommands.requireIdempotencyKey(idempotencyKey),
                payload,
                200,
                () -> {
                    submissionService.reinterpret(parsed, context);
                    return queryService.detail(parsed);
                });
        return WriteCommands.respond(result);
    }

    @GetMapping("/tasks")
    public PageResponse<AsyncTaskStore.AsyncTaskSummary> tasks(
            @RequestHeader(value = "X-Operator", required = false) String operator,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int size) {
        requireOperator(operator);
        return queryService.listTasks(status, page, size);
    }

    private static void requireOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            throw new BusinessException(401, "ADMIN_AUTH_REQUIRED", "管理后台查询需要认证");
        }
    }

    /** 重新解释请求体当前为空对象，保留以兼容未来参数。 */
    public record ReinterpretRequest() {}
}
