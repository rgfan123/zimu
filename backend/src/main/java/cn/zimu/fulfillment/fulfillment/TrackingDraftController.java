package cn.zimu.fulfillment.fulfillment;

import cn.zimu.fulfillment.common.dto.PageResponse;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.web.CommandContext;
import cn.zimu.fulfillment.common.web.WriteCommands;
import cn.zimu.fulfillment.fulfillment.dto.ProviderTrackingDraftDetailDto;
import cn.zimu.fulfillment.fulfillment.dto.TrackingDraftBatchConfirmCommand;
import cn.zimu.fulfillment.fulfillment.dto.TrackingDraftConfirmCommand;
import cn.zimu.fulfillment.fulfillment.dto.TrackingDraftRejectCommand;
import jakarta.validation.Valid;
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

/** 运单草稿的管理 API：查询、单条确认与批量确认。 */
@RestController
@RequestMapping("/api/v1/tracking-drafts")
@Validated
public class TrackingDraftController {

    private final TrackingDraftService service;

    public TrackingDraftController(TrackingDraftService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<ProviderTrackingDraftDetailDto> list(
            @RequestHeader(value = "X-Operator", required = false) String operator,
            @RequestParam(required = false) String status,
            @RequestParam(name = "submission_id", required = false) String submissionId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int size) {
        requireOperator(operator);
        Long parsedSubmission = submissionId == null || submissionId.isBlank()
                ? null
                : WriteCommands.parseIdentifier(submissionId);
        return service.list(page, size, status, parsedSubmission);
    }

    @GetMapping("/{draft_id}")
    public ProviderTrackingDraftDetailDto detail(
            @RequestHeader(value = "X-Operator", required = false) String operator,
            @PathVariable("draft_id") String draftId) {
        requireOperator(operator);
        return service.detail(WriteCommands.parseIdentifier(draftId));
    }

    @PostMapping("/{draft_id}/confirm")
    public ResponseEntity<?> confirm(
            @PathVariable("draft_id") String draftId,
            @Valid @RequestBody TrackingDraftConfirmCommand body,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Operator") String operator) {
        CommandContext context = WriteCommands.writeContext(operator);
        return WriteCommands.respond(service.confirm(
                WriteCommands.parseIdentifier(draftId),
                body,
                WriteCommands.requireIdempotencyKey(idempotencyKey),
                context));
    }

    @PostMapping("/{draft_id}/reject")
    public ResponseEntity<?> reject(
            @PathVariable("draft_id") String draftId,
            @Valid @RequestBody TrackingDraftRejectCommand body,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Operator") String operator) {
        CommandContext context = WriteCommands.writeContext(operator);
        return WriteCommands.respond(service.reject(
                WriteCommands.parseIdentifier(draftId),
                body,
                WriteCommands.requireIdempotencyKey(idempotencyKey),
                context));
    }

    @PostMapping("/batch-confirm")
    public ResponseEntity<?> batchConfirm(
            @Valid @RequestBody TrackingDraftBatchConfirmCommand body,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Operator") String operator) {
        CommandContext context = WriteCommands.writeContext(operator);
        return WriteCommands.respond(service.batchConfirm(
                body, WriteCommands.requireIdempotencyKey(idempotencyKey), context));
    }

    private static void requireOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            throw new BusinessException(401, "ADMIN_AUTH_REQUIRED", "管理后台查询需要认证");
        }
    }
}
