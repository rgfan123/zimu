package cn.zimu.fulfillment.order;

import cn.zimu.fulfillment.common.dto.PageResponse;
import cn.zimu.fulfillment.common.web.WriteCommands;
import cn.zimu.fulfillment.order.dto.ConfirmOrderDraftCommand;
import cn.zimu.fulfillment.order.dto.OrderDraftDetailDto;
import cn.zimu.fulfillment.order.dto.OrderDraftSupplementCommand;
import cn.zimu.fulfillment.order.dto.RejectOrderDraftCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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

/** 订单草稿管理 API：列表、详情、确认与拒绝（写接口延续幂等键/操作员/乐观版本约定）。 */
@RestController
@RequestMapping("/api/v1/order-drafts")
@Validated
public class OrderDraftController {

    private final OrderDraftQueryService queryService;
    private final OrderDraftService draftService;

    public OrderDraftController(OrderDraftQueryService queryService, OrderDraftService draftService) {
        this.queryService = queryService;
        this.draftService = draftService;
    }

    @GetMapping
    public PageResponse<OrderDraftDetailDto> list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int size,
            @RequestParam(required = false) OrderDraft.Status status,
            @RequestParam(name = "submission_id", required = false) String submissionId) {
        Long submission = submissionId == null || submissionId.isBlank()
                ? null
                : WriteCommands.parseIdentifier(submissionId);
        return queryService.list(status, submission, page, size);
    }

    @GetMapping("/{draft_id}")
    public OrderDraftDetailDto detail(@PathVariable("draft_id") String draftId) {
        return queryService.detail(WriteCommands.parseIdentifier(draftId));
    }

    @PostMapping("/{draft_id}/confirm")
    public ResponseEntity<?> confirm(
            @PathVariable("draft_id") String draftId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = "X-Operator", required = false) String operator,
            @Valid @RequestBody ConfirmOrderDraftCommand command) {
        return WriteCommands.respond(draftService.confirm(
                WriteCommands.parseIdentifier(draftId),
                command,
                WriteCommands.requireIdempotencyKey(key),
                WriteCommands.writeContext(operator)));
    }

    @PostMapping("/{draft_id}/supplement")
    public ResponseEntity<?> supplement(
            @PathVariable("draft_id") String draftId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = "X-Operator", required = false) String operator,
            @Valid @RequestBody OrderDraftSupplementCommand command) {
        return WriteCommands.respond(draftService.supplement(
                WriteCommands.parseIdentifier(draftId),
                command,
                WriteCommands.requireIdempotencyKey(key),
                WriteCommands.writeContext(operator)));
    }

    @PostMapping("/{draft_id}/reject")
    public ResponseEntity<?> reject(
            @PathVariable("draft_id") String draftId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = "X-Operator", required = false) String operator,
            @Valid @RequestBody RejectOrderDraftCommand command) {
        return WriteCommands.respond(draftService.reject(
                WriteCommands.parseIdentifier(draftId),
                command,
                WriteCommands.requireIdempotencyKey(key),
                WriteCommands.writeContext(operator)));
    }
}
