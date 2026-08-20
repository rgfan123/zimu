package cn.zimu.fulfillment.agent;

import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.web.RequestContext;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Iterator;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 定义域写端点（meta-agent-platform-impl 11，决策 12）：五个写动作全部 202 异步 +
 * 可轮询（轮询面 = T12 的 {@code GET /api/agent-runs/{runId}}，本票只落 run_mode=PREVIEW
 * 运行行，不实现任何 GET 查询端点）。
 *
 * <p><b>身份红线</b>：operator 一律取自 Basic Auth 认证身份（{@link
 * RequestContext#getAuthenticatedOperator()}，RequestContextFilter 复验通过后的登录主体），
 * 请求体禁止携带 operator 字段——客户端自报身份不是授权凭证；请求体出现 operator 一律 400。
 *
 * <p>幂等语义 = 目标状态幂等（12 决策）：confirm 已 active 同版本 → 200 + 当前状态（无任务）；
 * reject 对已拒绝 → 200；set-enabled 已处于目标值 → 200；retired/不存在 → 409/404。并发
 * 确认不同版本由 DB 部分唯一索引兜底，败者在任务结果（agent_runs.error_type=AGENT_CONFLICT）
 * 呈现 409 语义。
 */
@RestController
@RequestMapping("/api/agents")
public class AgentDefinitionWriteController {

    private final AgentDefinitionWriteService service;

    public AgentDefinitionWriteController(AgentDefinitionWriteService service) {
        this.service = service;
    }

    /** 人工建草稿（202；载荷即定义全量快照 + 建议评测用例，与 create_agent_draft 工具同构）。 */
    @PostMapping("/drafts")
    public ResponseEntity<?> createDraft(@RequestBody(required = false) JsonNode body) {
        String operator = requireAuthenticatedOperator();
        JsonNode draft = requireObject(body, "draft 必须是 JSON 对象");
        rejectOperatorField(draft);
        return respond(service.enqueueDraftCreate(operator, draft));
    }

    /** 确认草稿（202；已 active 同版本 → 200 + 当前状态）。 */
    @PostMapping("/{slug}/drafts/{version}/confirm")
    public ResponseEntity<?> confirm(
            @PathVariable String slug,
            @PathVariable int version,
            @RequestBody(required = false) JsonNode body) {
        String operator = requireAuthenticatedOperator();
        String normalizedSlug = requireSlug(slug);
        requireVersion(version);
        rejectCommandFields(body);
        return respond(service.enqueueConfirm(operator, normalizedSlug, version));
    }

    /** 拒绝草稿（202；对已拒绝幂等 200）。 */
    @PostMapping("/{slug}/drafts/{version}/reject")
    public ResponseEntity<?> reject(
            @PathVariable String slug,
            @PathVariable int version,
            @RequestBody(required = false) JsonNode body) {
        String operator = requireAuthenticatedOperator();
        String normalizedSlug = requireSlug(slug);
        requireVersion(version);
        rejectCommandFields(body);
        return respond(service.enqueueReject(operator, normalizedSlug, version));
    }

    /** 运维启停（202；显式目标值，已处于目标值 → 200）。 */
    @PostMapping("/{slug}/set-enabled")
    public ResponseEntity<?> setEnabled(
            @PathVariable String slug,
            @RequestBody(required = false) JsonNode body) {
        String operator = requireAuthenticatedOperator();
        String normalizedSlug = requireSlug(slug);
        boolean enabled = requireEnabledField(body);
        return respond(service.enqueueSetEnabled(operator, normalizedSlug, enabled));
    }

    /** 回滚（202；目标版本须曾 active，服务端复制为 v{n+1} 新草稿，版本链无回边）。 */
    @PostMapping("/{slug}/rollback")
    public ResponseEntity<?> rollback(
            @PathVariable String slug,
            @RequestBody(required = false) JsonNode body) {
        String operator = requireAuthenticatedOperator();
        String normalizedSlug = requireSlug(slug);
        int targetVersion = requireTargetVersionField(body);
        return respond(service.enqueueRollback(operator, normalizedSlug, targetVersion));
    }

    // ------------------------------------------------------------------
    // 身份与请求体严格校验
    // ------------------------------------------------------------------

    /** operator 一律取自 Basic Auth 认证身份（过滤器复验通过后的登录主体），与请求体无关。 */
    private static String requireAuthenticatedOperator() {
        RequestContext context = RequestContext.current();
        String operator = context == null ? null : context.getAuthenticatedOperator();
        if (operator == null || operator.isBlank()) {
            throw new BusinessException(401, "ADMIN_AUTH_REQUIRED", "写操作需要已认证的网关操作人");
        }
        return operator;
    }

    private JsonNode requireObject(JsonNode body, String message) {
        if (body == null || !body.isObject()) {
            throw BusinessException.badRequest("INVALID_PARAMETERS", message);
        }
        return body;
    }

    /** 命令体必须为空对象（confirm/reject 不接受任何字段，含 operator）。 */
    private static void rejectCommandFields(JsonNode body) {
        if (body == null || body.isNull()) {
            return;
        }
        if (!body.isObject()) {
            throw BusinessException.badRequest("INVALID_PARAMETERS", "命令体必须是 JSON 对象");
        }
        if (body.isEmpty()) {
            return;
        }
        if (body.has("operator")) {
            throw BusinessException.badRequest(
                    "OPERATOR_FIELD_FORBIDDEN", "请求体禁止携带 operator 字段，身份取自 Basic Auth 认证");
        }
        throw BusinessException.badRequest("INVALID_PARAMETERS", "该命令不接受请求体字段");
    }

    private boolean requireEnabledField(JsonNode body) {
        JsonNode object = requireObject(body, "set-enabled 命令体必须是 JSON 对象");
        Iterator<String> names = object.fieldNames();
        while (names.hasNext()) {
            String field = names.next();
            if ("operator".equals(field)) {
                throw BusinessException.badRequest(
                        "OPERATOR_FIELD_FORBIDDEN", "请求体禁止携带 operator 字段，身份取自 Basic Auth 认证");
            }
            if (!"enabled".equals(field)) {
                throw BusinessException.badRequest("INVALID_PARAMETERS", "set-enabled 只接受 enabled 字段");
            }
        }
        JsonNode value = object.get("enabled");
        if (value == null || !value.isBoolean()) {
            throw BusinessException.badRequest("INVALID_PARAMETERS", "enabled 必须是布尔值（显式目标值）");
        }
        return value.asBoolean();
    }

    private int requireTargetVersionField(JsonNode body) {
        JsonNode object = requireObject(body, "rollback 命令体必须是 JSON 对象");
        Iterator<String> names = object.fieldNames();
        while (names.hasNext()) {
            String field = names.next();
            if ("operator".equals(field)) {
                throw BusinessException.badRequest(
                        "OPERATOR_FIELD_FORBIDDEN", "请求体禁止携带 operator 字段，身份取自 Basic Auth 认证");
            }
            if (!"target_version".equals(field)) {
                throw BusinessException.badRequest("INVALID_PARAMETERS", "rollback 只接受 target_version 字段");
            }
        }
        JsonNode value = object.get("target_version");
        if (value == null || !value.isInt() || value.asInt() <= 0) {
            throw BusinessException.badRequest("INVALID_PARAMETERS", "target_version 必须是正整数");
        }
        return value.asInt();
    }

    private static void rejectOperatorField(JsonNode body) {
        if (body.has("operator")) {
            throw BusinessException.badRequest(
                    "OPERATOR_FIELD_FORBIDDEN", "请求体禁止携带 operator 字段，身份取自 Basic Auth 认证");
        }
    }

    private static String requireSlug(String slug) {
        String normalized = slug == null ? "" : slug.strip();
        if (!normalized.matches(AgentDefinition.SLUG_PATTERN)) {
            throw BusinessException.badRequest(
                    "INVALID_PARAMETERS", "agent_slug 必须匹配 ^[a-z][a-z0-9-]{0,63}$: " + slug);
        }
        return normalized;
    }

    private static void requireVersion(int version) {
        if (version <= 0) {
            throw BusinessException.badRequest("INVALID_PARAMETERS", "version 必须是正整数");
        }
    }

    private static ResponseEntity<?> respond(AgentDefinitionWriteService.SubmitResult result) {
        if (result.replayed()) {
            return ResponseEntity.ok(result.state());
        }
        return ResponseEntity.accepted().body(Map.of("run_id", result.runId()));
    }
}
