package cn.zimu.fulfillment.connector.jd;

import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.web.RequestContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.lop.open.api.sdk.JdlClient;
import com.lop.open.api.sdk.request.DomainAbstractRequest;
import com.lop.open.api.sdk.response.AbstractResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 京东 ISC 调用内核：凭据装配、SDK 调用、响应规整与审计的唯一实现。
 *
 * <p>七个 ISC 客户端（warehouse / basicinfo / order / returns / serial / stock / write）
 * 此前各持一份逐字节相同的 execute / normalize / audit / 凭据字段（互相 diff 110 行只差 5 行），
 * 成功码集合复制 7 份、7 字段凭据 {@code @Value} 块出现在 10 个构造器里（票 03）。
 * 收编后各客户端只保留自己真正独有的东西：把命令装配成 SDK 请求 DTO、指明操作名与响应信封。
 *
 * <p>本单元只负责传输与留痕，**不持有任何业务政策**：写模式门闩仍留在
 * {@code JdWriteOpsClient} / {@code JdWriteOpsController}（写专属政策），拒绝时经
 * {@link #refuse} 走同一审计口径，保证「未调用京东」与「调用失败」在审计里同样可见。
 */
@Service
public class JdIscGateway {

    /**
     * 外层与内层同时命中才算成功。京东各接口成功码不统一，这里是全库唯一一份集合
     * （注意与 {@code ShipmentJdOutboundPreparer.UNCERTAIN_EXTERNAL_RESULTS} 不是同一概念：
     * 那里描述的是「写结果不确定、必须对账」的码集）。
     */
    private static final Set<String> SUCCESS_CODES = Set.of("0", "200", "1000", "10000", "SUCCESS");

    private static final String AUDIT_SERVICE = "jd.isc";
    private static final String AUDIT_OPERATOR_FALLBACK = "jd-client";

    private final ObjectMapper sdkMapper;
    private final AuditLogService auditLogService;
    private final String serverUrl;
    private final String appKey;
    private final String appSecret;
    private final String accessToken;
    private final String pin;
    private final String ownerNo;

    public JdIscGateway(
            ObjectMapper objectMapper,
            AuditLogService auditLogService,
            @Value("${app.jd.server-url:}") String serverUrl,
            @Value("${app.jd.app-key:}") String appKey,
            @Value("${app.jd.app-secret:}") String appSecret,
            @Value("${app.jd.access-token:}") String accessToken,
            @Value("${app.jd.pin:}") String pin,
            @Value("${app.jd.owner-no:}") String ownerNo) {
        this.sdkMapper = objectMapper.copy()
                .setPropertyNamingStrategy(PropertyNamingStrategies.LOWER_CAMEL_CASE);
        this.auditLogService = auditLogService;
        this.serverUrl = serverUrl;
        this.appKey = appKey;
        this.appSecret = appSecret;
        this.accessToken = accessToken;
        this.pin = pin;
        this.ownerNo = ownerNo;
    }

    /**
     * 命令 Map → SDK 请求 DTO：只注入目标 DTO 真实支持的 {@code pin}/{@code ownerNo} 默认值，
     * 避免未知属性转换失败。收编前三个客户端各写一套策略（反射探测 / 无条件注入 / 手写白名单），
     * 三者等价性由 {@code JdIscDefaultsPolicyEquivalenceTest} 对真实 SDK DTO 实测锁定。
     */
    public <D> D body(Map<String, Object> command, Class<D> dtoType) {
        return sdkMapper.convertValue(withDefaults(command, dtoType), dtoType);
    }

    /** 执行一次 ISC 调用（审计原样记录）。含 PII 的接口请用带投影的重载。 */
    public <T extends AbstractResponse> JdResult execute(
            String operation,
            Map<String, Object> command,
            DomainAbstractRequest<T> request,
            Function<T, Object> envelopeExtractor) {
        return execute(operation, command, request, envelopeExtractor, JdAuditProjection.FULL);
    }

    /** 执行一次 ISC 调用：凭据校验 → SDK 调用 → 响应规整 → 按投影审计；异常一律收敛为稳定失败码。 */
    public <T extends AbstractResponse> JdResult execute(
            String operation,
            Map<String, Object> command,
            DomainAbstractRequest<T> request,
            Function<T, Object> envelopeExtractor,
            JdAuditProjection projection) {
        Instant startedAt = Instant.now();
        JdResult result;
        if (!configured()) {
            result = new JdResult(false, "CREDENTIALS_REQUIRED",
                    "JD REAL client requires server-url, app-key, app-secret and access-token",
                    null, null);
        } else {
            try {
                T response = new JdlClient(serverUrl, appKey, appSecret, accessToken).execute(request);
                result = normalize(response, envelopeExtractor.apply(response));
            } catch (Exception exception) {
                result = new JdResult(false, "SDK_CALL_FAILED", safeMessage(), null, null);
            }
        }
        audit(operation, command, result, startedAt, projection);
        return result;
    }

    /**
     * 调用方政策拒绝（如写模式未启用）：不接触京东，但按同一口径留下审计，
     * 使「未调用」与「调用失败」在审计流水里同样可追。
     */
    public JdResult refuse(
            String operation, Map<String, Object> command, String businessCode, String message) {
        Instant startedAt = Instant.now();
        JdResult result = new JdResult(false, businessCode, message, null, null);
        audit(operation, command, result, startedAt, JdAuditProjection.FULL);
        return result;
    }

    private JdResult normalize(AbstractResponse outer, Object envelope) {
        Map<String, Object> values = envelope == null
                ? Map.of()
                : sdkMapper.convertValue(envelope, new TypeReference<>() {});
        String outerCode = text(outer.getCode());
        String innerCode = text(values.get("code"));
        boolean success = SUCCESS_CODES.contains(outerCode) && SUCCESS_CODES.contains(innerCode);
        String businessCode = innerCode == null ? outerCode : innerCode;
        String message = text(values.get("message"));
        if (message == null) {
            message = outer.getMsg();
        }
        // 供应商查询的 JdlApiListResponseBase 用 setRequestID（大写 D），LOWER_CAMEL_CASE
        // 序列化为 requestID。收编前只有 basicinfo 客户端处理了这个变体，其余envelope 没有该
        // 键、兜底不会触发，因此统一采用是安全的超集（票 03）。
        String requestId = text(values.get("requestId"));
        if (requestId == null) {
            requestId = text(values.get("requestID"));
        }
        return new JdResult(
                success,
                businessCode == null ? "EMPTY_RESPONSE_CODE" : businessCode,
                message,
                requestId,
                values.get("data"));
    }

    private Map<String, Object> withDefaults(Map<String, Object> command, Class<?> dtoType) {
        Map<String, Object> request = new LinkedHashMap<>();
        if (command != null) {
            request.putAll(command);
        }
        if (pin != null && !pin.isBlank() && supports(dtoType, "pin")) {
            request.putIfAbsent("pin", pin);
        }
        if (ownerNo != null && !ownerNo.isBlank() && supports(dtoType, "ownerNo")) {
            request.putIfAbsent("ownerNo", ownerNo);
        }
        return request;
    }

    private boolean supports(Class<?> dtoType, String property) {
        String setter = "set" + Character.toUpperCase(property.charAt(0)) + property.substring(1);
        return Arrays.stream(dtoType.getMethods()).anyMatch(method -> method.getName().equals(setter));
    }

    private void audit(
            String operation,
            Map<String, Object> command,
            JdResult result,
            Instant startedAt,
            JdAuditProjection projection) {
        RequestContext context = RequestContext.current();
        auditLogService.record(new AuditLogService.AuditCommand()
                .requestId(projection.requestId(
                        context == null ? result.requestId() : context.getRequestId()))
                .traceId(context == null ? null : context.getTraceId())
                .operator(context == null || context.getOperator() == null
                        ? AUDIT_OPERATOR_FALLBACK : context.getOperator())
                .actorType(AuditActorType.SYSTEM)
                .service(AUDIT_SERVICE)
                .operation(operation)
                .requestPayload(projection.request(operation, command))
                .responsePayload(projection.response(operation, result))
                .httpStatus(result.success() ? 200 : 502)
                .businessCode(projection.businessCode(result.businessCode()))
                .latencyMs((int) Duration.between(startedAt, Instant.now()).toMillis()));
    }

    private boolean configured() {
        return !serverUrl.isBlank() && !appKey.isBlank() && !appSecret.isBlank() && !accessToken.isBlank();
    }

    private String text(Object value) {
        return value == null || value.toString().isBlank() ? null : value.toString();
    }

    /** SDK 异常一律不外泄细节（可能含凭据或内网地址），只给稳定话术。 */
    private String safeMessage() {
        return "京东服务暂时不可用，请稍后重试";
    }
}
