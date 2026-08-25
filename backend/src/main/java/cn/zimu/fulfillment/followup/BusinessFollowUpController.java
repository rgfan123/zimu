package cn.zimu.fulfillment.followup;

import com.fasterxml.jackson.annotation.JsonProperty;
import cn.zimu.fulfillment.common.dto.PageResponse;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.idempotency.IdempotencyService;
import cn.zimu.fulfillment.common.idempotency.IdempotentResult;
import cn.zimu.fulfillment.common.web.CommandContext;
import cn.zimu.fulfillment.common.web.WriteCommands;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;
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

/** Authorized workbench seam for intake and explicit +1 organization dispatch. */
@RestController
@RequestMapping("/api/v1/business-followups")
@Validated
public class BusinessFollowUpController {

    private final BusinessFollowUpService service;
    private final IdempotencyService idempotency;

    public BusinessFollowUpController(
            BusinessFollowUpService service, IdempotencyService idempotency) {
        this.service = service;
        this.idempotency = idempotency;
    }

    @PostMapping
    public ResponseEntity<?> create(
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = "X-Operator", required = false) String operator,
            @Valid @RequestBody CreateRequest request) {
        CommandContext context = WriteCommands.writeContext(operator);
        BusinessFollowUpService.CreateCommand command = new BusinessFollowUpService.CreateCommand(
                WriteCommands.parseIdentifier(request.messageSubmissionId()), request.employeeDraft());
        IdempotentResult<BusinessFollowUpSummaryDto> result = idempotency.execute(
                "business_followup.create",
                WriteCommands.requireIdempotencyKey(key),
                command,
                201,
                () -> service.create(command, context));
        return WriteCommands.respond(result);
    }

    @PostMapping("/{followup_id}/organize")
    public ResponseEntity<?> organize(
            @PathVariable("followup_id") String followupId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = "X-Operator", required = false) String operator,
            @Valid @RequestBody OrganizeRequest request) {
        long id = WriteCommands.parseIdentifier(followupId);
        CommandContext context = WriteCommands.writeContext(operator);
        BusinessFollowUpService.OrganizeCommand command =
                new BusinessFollowUpService.OrganizeCommand(
                        id,
                        request.agentSlug(),
                        request.agentVersion(),
                        WriteCommands.parseIdentifier(request.reviewerOperatorId()));
        IdempotentResult<BusinessFollowUpSummaryDto> result = idempotency.execute(
                "business_followup.organize",
                WriteCommands.requireIdempotencyKey(key),
                command,
                202,
                () -> service.organize(command, context));
        return WriteCommands.respond(result);
    }

    @PostMapping("/{followup_id}/decisions")
    public ResponseEntity<?> decide(
            @PathVariable("followup_id") String followupId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = "X-Operator", required = false) String operator,
            @Valid @RequestBody DecideRequest request) {
        long id = WriteCommands.parseIdentifier(followupId);
        String idempotencyKey = WriteCommands.requireIdempotencyKey(key);
        CommandContext context = WriteCommands.writeContext(operator);
        BusinessFollowUpService.DecideCommand command = new BusinessFollowUpService.DecideCommand(
                id,
                request.expectedDraftVersion(),
                request.decision(),
                request.reason(),
                idempotencyKey,
                request.capability());
        IdempotentResult<BusinessFollowUpDto> result = idempotency.execute(
                "business_followup.decide",
                idempotencyKey,
                command,
                202,
                () -> service.decide(command, context));
        return WriteCommands.respond(result);
    }

    @GetMapping("/{followup_id}")
    public BusinessFollowUpDto detail(
            @PathVariable("followup_id") String followupId,
            @RequestHeader(value = "X-Operator", required = false) String operator) {
        requireAuthorized(operator);
        return service.detail(WriteCommands.parseIdentifier(followupId));
    }

    @GetMapping
    public PageResponse<BusinessFollowUpSummaryDto> list(
            @RequestHeader(value = "X-Operator", required = false) String operator,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int size,
            @RequestParam(required = false) String stage) {
        requireAuthorized(operator);
        return service.list(page, size, stage);
    }

    private static void requireAuthorized(String operator) {
        if (operator == null || operator.isBlank()) {
            throw new BusinessException(401, "ADMIN_AUTH_REQUIRED", "客户跟进材料需要认证");
        }
    }

    public record CreateRequest(
            @JsonProperty("message_submission_id")
                    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "^[1-9][0-9]*$")
                    @NotBlank
                    @Pattern(regexp = "^[1-9][0-9]*$")
                    String messageSubmissionId,
            @JsonProperty("employee_draft")
                    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 20000)
                    @NotBlank
                    @Size(max = 20_000)
                    String employeeDraft) {}

    public record OrganizeRequest(
            @JsonProperty("agent_slug")
                    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 64)
                    @NotBlank
                    @Size(max = 64)
                    String agentSlug,
            @JsonProperty("agent_version")
                    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1")
                    @Positive
                    int agentVersion,
            @JsonProperty("reviewer_operator_id")
                    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "^[1-9][0-9]*$")
                    @NotBlank
                    @Pattern(regexp = "^[1-9][0-9]*$")
                    String reviewerOperatorId) {}

    public record DecideRequest(
            @JsonProperty("expected_draft_version")
                    @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1")
                    @Positive
                    int expectedDraftVersion,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    allowableValues = {"CONFIRM", "REDO", "NEEDS_INPUT", "PAUSE"})
                    @NotBlank
                    @Pattern(regexp = "^(CONFIRM|REDO|NEEDS_INPUT|PAUSE)$")
                    String decision,
            @Size(max = 2000)
                    String reason,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minLength = 1, maxLength = 128)
                    @NotBlank
                    @Size(max = 128)
                    @Pattern(regexp = "^[0-9a-f]{32}$")
                    String capability) {}
}
