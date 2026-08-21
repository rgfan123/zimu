package cn.zimu.fulfillment.operator;

import cn.zimu.fulfillment.common.dto.PageResponse;
import cn.zimu.fulfillment.common.web.WriteCommands;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内部运营人员 JSON API（Issue #89）：CRUD 写命令对齐既有 masterdata 公开 seam
 * （Idempotency-Key / X-Operator / expected_version / 审计）；团队解析为只读诊断 seam。
 */
@RestController
@RequestMapping("/api/v1")
@Validated
public class OperatorController {

    private final OperatorService service;
    private final OperatorResolver resolver;

    public OperatorController(OperatorService service, OperatorResolver resolver) {
        this.service = service;
        this.resolver = resolver;
    }

    @GetMapping("/operators")
    public PageResponse<OperatorDto> operators(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int size,
            @RequestParam(name = "responsible_team", required = false) String responsibleTeam,
            @RequestParam(required = false) String query) {
        return service.operators(page, size, responsibleTeam, query);
    }

    @GetMapping("/operators/{id}")
    public OperatorDto operator(@PathVariable long id) {
        return service.operator(id);
    }

    @PostMapping("/operators")
    public ResponseEntity<?> createOperator(@Valid @RequestBody OperatorWrite body,
            @RequestHeader("Idempotency-Key") String key, @RequestHeader("X-Operator") String operator) {
        return WriteCommands.respond(service.createOperator(
                body, WriteCommands.requireIdempotencyKey(key), WriteCommands.writeContext(operator)));
    }

    @PatchMapping("/operators/{id}")
    public ResponseEntity<?> patchOperator(@PathVariable long id, @Valid @RequestBody OperatorPatch body,
            @RequestHeader("Idempotency-Key") String key, @RequestHeader("X-Operator") String operator) {
        return WriteCommands.respond(service.patchOperator(
                id, body, WriteCommands.requireIdempotencyKey(key), WriteCommands.writeContext(operator)));
    }

    /**
     * 责任团队 → 运营人员/可推送 userid 的只读诊断 seam（不推送、不改数据）。
     * {@code require_pushable=true} 时走 fail-closed 语义：存在未绑定/无人员等不可推送
     * 情形返回 422 OPERATOR_TEAM_NOT_PUSHABLE（消息含未绑定名单与运营应对），不静默跳过。
     */
    @GetMapping("/operator-team-resolutions")
    public OperatorTeamResolution teamResolution(
            @RequestParam(name = "responsible_team") String responsibleTeam,
            @RequestParam(name = "require_pushable", defaultValue = "false") boolean requirePushable) {
        return requirePushable
                ? resolver.requirePushable(responsibleTeam)
                : resolver.resolve(responsibleTeam);
    }
}
