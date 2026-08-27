package cn.zimu.fulfillment.connector;

import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.domain.DataScope;
import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.idempotency.IdempotencyService;
import cn.zimu.fulfillment.common.idempotency.IdempotentResult;
import cn.zimu.fulfillment.common.web.CommandContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ConnectorService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final IdempotencyService idempotencyService;
    private final AuditLogService auditLogService;
    private final Map<SourceChannel, PlatformConnector> connectors = new EnumMap<>(SourceChannel.class);

    public ConnectorService(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            IdempotencyService idempotencyService,
            AuditLogService auditLogService,
            List<PlatformConnector> platformConnectors) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.idempotencyService = idempotencyService;
        this.auditLogService = auditLogService;
        platformConnectors.forEach(connector -> connectors.put(connector.channel(), connector));
    }

    public List<ConnectorConfigView> list() {
        return jdbc.query(
                """
                SELECT source_channel, mode, transport_mode, enabled, config, lock_version
                FROM app.connector_configs ORDER BY source_channel
                """,
                (rs, rowNum) -> view(
                        rs.getString("source_channel"),
                        rs.getString("mode"),
                        rs.getString("transport_mode"),
                        rs.getBoolean("enabled"),
                        rs.getString("config"),
                        rs.getLong("lock_version")));
    }

    public ConnectorConfigView get(SourceChannel channel) {
        return jdbc.query(
                        """
                        SELECT source_channel, mode, transport_mode, enabled, config, lock_version
                        FROM app.connector_configs WHERE source_channel = ?
                        """,
                        (rs, rowNum) -> view(
                                rs.getString("source_channel"),
                                rs.getString("mode"),
                                rs.getString("transport_mode"),
                                rs.getBoolean("enabled"),
                                rs.getString("config"),
                                rs.getLong("lock_version")),
                        channel.name())
                .stream()
                .findFirst()
                .orElseThrow(() -> BusinessException.notFound("Connector 不存在: " + channel));
    }

    @Transactional
    public IdempotentResult<ConnectorConfigView> patch(
            SourceChannel channel,
            ConnectorPatch patch,
            String idempotencyKey,
            CommandContext context) {
        return idempotencyService.execute(
                "connector.patch." + channel.name().toLowerCase(),
                idempotencyKey,
                Map.of("channel", channel.name(), "patch", patch),
                200,
                () -> applyPatch(channel, patch, context));
    }

    @Transactional
    public IdempotentResult<ConnectionTestResult> testConnection(
            SourceChannel channel,
            String idempotencyKey,
            CommandContext context) {
        return idempotencyService.execute(
                "connector.test." + channel.name().toLowerCase(),
                idempotencyKey,
                Map.of("channel", channel.name()),
                200,
                () -> check(channel, context));
    }

    private ConnectorConfigView applyPatch(
            SourceChannel channel, ConnectorPatch patch, CommandContext context) {
        ConnectorConfigView current = get(channel);
        Map<String, Object> config = readConfig(jdbc.queryForObject(
                "SELECT config::text FROM app.connector_configs WHERE source_channel = ?", String.class, channel.name()));
        if (patch.endpoint() != null) {
            if (!patch.endpoint().matches("^https?://.+")) {
                throw BusinessException.badRequest("ENDPOINT_INVALID", "endpoint 必须是 HTTP(S) URI");
            }
            config.put("endpoint", patch.endpoint());
        }
        if (patch.credentialSecretRef() != null) {
            if (patch.credentialSecretRef().isBlank()) {
                throw BusinessException.badRequest("CREDENTIAL_SECRET_REF_INVALID", "凭据引用不能为空");
            }
            config.put("credential_secret_ref", patch.credentialSecretRef());
        }
        if (patch.username() != null) {
            if (patch.username().isBlank()) {
                throw BusinessException.badRequest("USERNAME_INVALID", "用户名不能为空");
            }
            config.put("username", patch.username());
        }
        if (patch.password() != null) {
            if (patch.password().isBlank()) {
                throw BusinessException.badRequest("PASSWORD_INVALID", "密码不能为空");
            }
            config.put("password", patch.password());
        }
        String mode = Optional.ofNullable(patch.clientMode()).orElse(current.clientMode());
        String transport = Optional.ofNullable(patch.transportMode()).orElse(current.transportMode());
        boolean enabled = Optional.ofNullable(patch.enabled()).orElse(current.enabled());
        int updated = jdbc.update(
                """
                UPDATE app.connector_configs
                SET mode = ?, transport_mode = ?, enabled = ?, config = ?::jsonb,
                    lock_version = lock_version + 1, updated_at = CURRENT_TIMESTAMP
                WHERE source_channel = ? AND lock_version = ?
                """,
                mode,
                transport,
                enabled,
                writeJson(config),
                channel.name(),
                patch.expectedVersion());
        if (updated != 1) {
            throw BusinessException.conflict("VERSION_CONFLICT", "Connector 配置版本已变化，请刷新后重试");
        }
        ConnectorConfigView result = get(channel);
        auditLogService.record(audit(context, channel, "updateConfig", auditSafe(patch), result, "UPDATED"));
        return result;
    }

    /** 审计负载脱敏：密码明文不得进入审计记录，投影为存在性标记，与京东 pin 先例一致。 */
    private static Object auditSafe(ConnectorPatch patch) {
        if (patch.password() == null) {
            return patch;
        }
        return new ConnectorPatch(
                patch.expectedVersion(),
                patch.clientMode(),
                patch.transportMode(),
                patch.enabled(),
                patch.endpoint(),
                patch.credentialSecretRef(),
                patch.username(),
                "***");
    }

    private ConnectionTestResult check(SourceChannel channel, CommandContext context) {
        ConnectorConfigView config = get(channel);
        PlatformConnector connector = Optional.ofNullable(connectors.get(channel))
                .orElseThrow(() -> BusinessException.notFound("Connector 实现不存在: " + channel));
        ConnectionTestResult result = connector.testConnection(new ConnectorRuntime(
                config.clientMode(),
                config.transportMode(),
                config.enabled(),
                config.endpoint(),
                config.credentialConfigured()));
        auditLogService.record(audit(context, channel, "testConnection", Map.of(), result, result.businessCode()));
        return result;
    }

    private AuditLogService.AuditCommand audit(
            CommandContext context,
            SourceChannel channel,
            String operation,
            Object request,
            Object response,
            String businessCode) {
        return new AuditLogService.AuditCommand()
                .dataScope(DataScope.BUSINESS)
                .requestId(context.requestId())
                .traceId(context.traceId())
                .operator(context.operator())
                .actorType(AuditActorType.HUMAN)
                .service("connector." + channel.name())
                .operation(operation)
                .requestPayload(request)
                .responsePayload(response)
                .httpStatus(200)
                .businessCode(businessCode)
                .latencyMs(0);
    }

    private ConnectorConfigView view(
            String channel, String mode, String transport, boolean enabled, String configJson, long version) {
        Map<String, Object> config = readConfig(configJson);
        String endpoint = config.get("endpoint") instanceof String value ? value : null;
        // username 非敏感，直接回显；password 与 credential_secret_ref 一样只投影存在性标记，永不回显明文。
        String username = config.get("username") instanceof String value ? value : null;
        boolean credentialConfigured = config.get("credential_secret_ref") instanceof String value && !value.isBlank();
        boolean passwordConfigured = config.get("password") instanceof String value && !value.isBlank();
        return new ConnectorConfigView(
                channel, mode, transport, enabled, endpoint, username, credentialConfigured, passwordConfigured, version);
    }

    private Map<String, Object> readConfig(String json) {
        try {
            if (json == null || json.isBlank()) {
                return new LinkedHashMap<>();
            }
            return new LinkedHashMap<>(objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {}));
        } catch (Exception ex) {
            throw new IllegalStateException("Connector config 解析失败", ex);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("Connector config 序列化失败", ex);
        }
    }
}
