package cn.zimu.fulfillment.order;

import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.domain.DataScope;
import cn.zimu.fulfillment.common.dto.PageResponse;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.idempotency.IdempotencyService;
import cn.zimu.fulfillment.common.idempotency.IdempotentResult;
import cn.zimu.fulfillment.common.web.CommandContext;
import cn.zimu.fulfillment.order.dto.OperationalAlertDto;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.postgresql.util.PGobject;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 非阻断运营提醒查询与确认；确认动作不改变订单领域状态。 */
@Service
public class OperationalAlertService {

    private static final String BUSINESS_JOINS = """
            FROM app.operational_alerts oa
            LEFT JOIN app.orders direct_order ON direct_order.id=oa.order_id
            LEFT JOIN app.order_lines direct_line ON direct_line.id=oa.order_line_id
            LEFT JOIN app.orders line_order ON line_order.id=direct_line.order_id
            LEFT JOIN app.fulfillments alert_fulfillment ON alert_fulfillment.id=oa.fulfillment_id
            LEFT JOIN app.order_lines fulfillment_line ON fulfillment_line.id=alert_fulfillment.order_line_id
            LEFT JOIN app.orders fulfillment_order ON fulfillment_order.id=fulfillment_line.order_id
            LEFT JOIN app.shipments alert_shipment ON alert_shipment.id=oa.shipment_id
            LEFT JOIN app.orders shipment_order ON shipment_order.id=alert_shipment.order_id
            WHERE (direct_order.data_scope='BUSINESS' OR line_order.data_scope='BUSINESS'
                   OR fulfillment_order.data_scope='BUSINESS' OR shipment_order.data_scope='BUSINESS')
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final IdempotencyService idempotency;
    private final AuditLogService audits;

    public OperationalAlertService(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            IdempotencyService idempotency,
            AuditLogService audits) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.idempotency = idempotency;
        this.audits = audits;
    }

    @Transactional(readOnly = true)
    public PageResponse<OperationalAlertDto> list(
            int page, int size, OperationalAlertStatus status, OperationalAlertSeverity severity) {
        StringBuilder filters = new StringBuilder();
        List<Object> args = new ArrayList<>();
        if (status != null) {
            filters.append(" AND oa.status=?");
            args.add(status.name());
        }
        if (severity != null) {
            filters.append(" AND oa.severity=?");
            args.add(severity.name());
        }
        Long total = jdbc.queryForObject(
                "SELECT count(*) " + BUSINESS_JOINS + filters, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((long) page * size);
        List<OperationalAlertDto> items = jdbc.query(
                "SELECT oa.* " + BUSINESS_JOINS + filters + " ORDER BY oa.created_at DESC, oa.id DESC LIMIT ? OFFSET ?",
                (rs, rowNum) -> dto(rs),
                pageArgs.toArray());
        long totalElements = total == null ? 0 : total;
        int totalPages = totalElements == 0 ? 0 : (int) ((totalElements + size - 1) / size);
        return new PageResponse<>(items, page, size, totalElements, totalPages);
    }

    @Transactional
    public IdempotentResult<OperationalAlertDto> acknowledge(
            long alertId,
            VersionedNoteCommand command,
            String idempotencyKey,
            CommandContext context) {
        Map<String, Object> payload = Map.of("alert_id", alertId, "command", command);
        return idempotency.execute("operational_alert.acknowledge", idempotencyKey, payload, 200, () -> {
            LockedAlert current = lockBusinessAlert(alertId);
            if (!Objects.equals(current.version(), command.expectedVersion())) {
                throw BusinessException.conflict("VERSION_CONFLICT", "运营提醒已被其他操作修改，请刷新后重试");
            }
            if (!"OPEN".equals(current.status())) {
                throw BusinessException.conflict("ALERT_NOT_OPEN", "运营提醒已确认或关闭，不能重复处理");
            }
            jdbc.update(
                    """
                    UPDATE app.operational_alerts
                    SET status='ACKNOWLEDGED', acknowledged_by=?, acknowledged_at=CURRENT_TIMESTAMP,
                        detail=detail || jsonb_build_object('acknowledgement_note', ?),
                        lock_version=lock_version+1, updated_at=CURRENT_TIMESTAMP
                    WHERE id=?
                    """,
                    context.operator(),
                    command.note(),
                    alertId);
            OperationalAlertDto result = loadBusinessAlert(alertId);
            audits.record(new AuditLogService.AuditCommand()
                    .dataScope(DataScope.BUSINESS)
                    .orderId(current.businessOrderId())
                    .requestId(context.requestId())
                    .traceId(context.traceId())
                    .operator(context.operator())
                    .actorType(AuditActorType.HUMAN)
                    .service("OperationalAlertService")
                    .operation("operational_alert.acknowledge")
                    .requestPayload(payload)
                    .responsePayload(result)
                    .httpStatus(200)
                    .businessCode("OPERATIONAL_ALERT_ACKNOWLEDGED"));
            return result;
        });
    }

    /**
     * 创建一条运营告警。幂等语义：同一 Idempotency-Key 同 payload 重放返回首次结果，
     * 不同 payload 返回 409。主体四选一至少一个非空（与表 CHECK 一致），否则 422 ALERT_SUBJECT_REQUIRED。
     */
    @Transactional
    public IdempotentResult<OperationalAlertDto> create(
            CreateOperationalAlertCommand command,
            String idempotencyKey,
            CommandContext context) {
        validateCreate(command);
        return idempotency.execute("operational_alert.create", idempotencyKey, command, 201, () -> {
            Long alertId = jdbc.queryForObject(
                    """
                    INSERT INTO app.operational_alerts
                        (alert_no, alert_type, severity, order_id, order_line_id, fulfillment_id, shipment_id,
                         message, detail)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb) RETURNING id
                    """,
                    Long.class,
                    "ALERT-" + token(),
                    command.alertType(),
                    command.severity().name(),
                    command.orderId(),
                    command.orderLineId(),
                    command.fulfillmentId(),
                    command.shipmentId(),
                    command.message(),
                    writeJson(command.detail() == null ? Map.of() : command.detail()));
            OperationalAlertDto result = loadAlert(alertId);
            audits.record(new AuditLogService.AuditCommand()
                    .dataScope(DataScope.BUSINESS)
                    .orderId(command.orderId())
                    .requestId(context.requestId())
                    .traceId(context.traceId())
                    .operator(context.operator())
                    .actorType(AuditActorType.SYSTEM)
                    .service("OperationalAlertService")
                    .operation("operational_alert.create")
                    .requestPayload(command)
                    .responsePayload(result)
                    .httpStatus(201)
                    .businessCode("OPERATIONAL_ALERT_CREATED"));
            return result;
        });
    }

    /**
     * 系统侧（Worker）创建运营告警：无 Idempotency-Key/CommandContext 的路径。
     *
     * <p>同一 {@code (alert_type, shipment_id, detail.export_id)} 的既有 OPEN/ACKNOWLEDGED
     * 告警先自动关闭（detail 留 superseded 证据）再插入新告警——同一导出同一 delivery 多次
     * ensure 只保留一条活动告警，绝不跨导出误关（续发导出可共享 fulfillment/shipment，按
     * detail 的 export_id 隔离）；并发插入由
     * {@code uq_operational_alert_active_wecom_export} 唯一索引兜底（冲突时返回既有告警）。
     */
    @Transactional
    public long createSystem(CreateOperationalAlertCommand command) {
        validateCreate(command);
        resolveActiveForExport(command.alertType(), command.shipmentId(), exportIdOf(command), "superseded_by_new_delivery");
        try {
            Long alertId = jdbc.queryForObject(
                    """
                    INSERT INTO app.operational_alerts
                        (alert_no, alert_type, severity, order_id, order_line_id, fulfillment_id, shipment_id,
                         message, detail)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb) RETURNING id
                    """,
                    Long.class,
                    "ALERT-" + token(),
                    command.alertType(),
                    command.severity().name(),
                    command.orderId(),
                    command.orderLineId(),
                    command.fulfillmentId(),
                    command.shipmentId(),
                    command.message(),
                    writeJson(command.detail() == null ? Map.of() : command.detail()));
            audits.record(new AuditLogService.AuditCommand()
                    .dataScope(DataScope.BUSINESS)
                    .orderId(command.orderId())
                    .requestId("system:wecom-export")
                    .operator("system")
                    .actorType(AuditActorType.SYSTEM)
                    .service("OperationalAlertService")
                    .operation("operational_alert.create_system")
                    .requestPayload(command)
                    .responsePayload(loadAlert(alertId))
                    .httpStatus(201)
                    .businessCode("OPERATIONAL_ALERT_CREATED"));
            return alertId;
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            Long existing = jdbc.queryForObject(
                    """
                    SELECT id FROM app.operational_alerts
                    WHERE alert_type=? AND shipment_id=?
                      AND detail->>'export_id'=?
                      AND status IN ('OPEN', 'ACKNOWLEDGED')
                    ORDER BY id DESC LIMIT 1
                    """,
                    Long.class,
                    command.alertType(),
                    command.shipmentId(),
                    exportIdOf(command));
            return existing == null ? -1 : existing;
        }
    }

    /**
     * 人工重发成功（新 initial ack）后只关闭**该导出**的活动告警：按
     * {@code (alert_type, shipment_id, detail.export_id)} 精确隔离，不误关共享同一
     * fulfillment/shipment 的其他导出告警；detail 留下可追溯关闭证据。
     */
    @Transactional
    public void resolveWecomExportAlerts(Long shipmentId, long exportId, String reason) {
        if (shipmentId == null) {
            return;
        }
        jdbc.update(
                """
                UPDATE app.operational_alerts
                SET status='RESOLVED', resolved_at=CURRENT_TIMESTAMP,
                    detail=detail || jsonb_build_object('auto_resolved_reason', ?),
                    lock_version=lock_version+1, updated_at=CURRENT_TIMESTAMP
                WHERE alert_type='FULFILLMENT_EXPORT_WECOM' AND shipment_id=?
                  AND detail->>'export_id'=?
                  AND status IN ('OPEN', 'ACKNOWLEDGED')
                """,
                reason,
                shipmentId,
                String.valueOf(exportId));
    }

    private void resolveActiveForExport(String alertType, Long shipmentId, String exportId, String reason) {
        if (shipmentId == null || exportId == null) {
            return;
        }
        jdbc.update(
                """
                UPDATE app.operational_alerts
                SET status='RESOLVED', resolved_at=CURRENT_TIMESTAMP,
                    detail=detail || jsonb_build_object('auto_resolved_reason', ?),
                    lock_version=lock_version+1, updated_at=CURRENT_TIMESTAMP
                WHERE alert_type=? AND shipment_id=? AND detail->>'export_id'=?
                  AND status IN ('OPEN', 'ACKNOWLEDGED')
                """,
                reason,
                alertType,
                shipmentId,
                exportId);
    }

    /** 告警 detail 中的 export_id（字符串），缺失时返回 null（调用方按无隔离处理）。 */
    private static String exportIdOf(CreateOperationalAlertCommand command) {
        Object exportId = command.detail() == null ? null : command.detail().get("export_id");
        return exportId == null ? null : String.valueOf(exportId);
    }

    private static void validateCreate(CreateOperationalAlertCommand command) {
        if (command == null || command.alertType() == null || command.alertType().isBlank()) {
            throw BusinessException.unprocessable("ALERT_TYPE_REQUIRED", "运营告警类型不能为空");
        }
        if (command.severity() == null) {
            throw BusinessException.unprocessable("ALERT_SEVERITY_REQUIRED", "运营告警级别不能为空");
        }
        if (command.message() == null || command.message().isBlank()) {
            throw BusinessException.unprocessable("ALERT_MESSAGE_REQUIRED", "运营告警内容不能为空");
        }
        if (numNonNull(
                        command.orderId(), command.orderLineId(),
                        command.fulfillmentId(), command.shipmentId())
                == 0) {
            throw BusinessException.unprocessable(
                    "ALERT_SUBJECT_REQUIRED",
                    "运营告警必须关联订单、订单行、履约或发货主体之一（与 app.operational_alerts 的 CHECK 一致）");
        }
    }

    private static long numNonNull(Object... values) {
        long count = 0;
        for (Object value : values) {
            if (value != null) {
                count++;
            }
        }
        return count;
    }

    private OperationalAlertDto loadAlert(long alertId) {
        List<OperationalAlertDto> values = jdbc.query(
                "SELECT oa.* FROM app.operational_alerts oa WHERE oa.id=?",
                (rs, rowNum) -> dto(rs),
                alertId);
        if (values.isEmpty()) {
            throw BusinessException.notFound("运营提醒不存在");
        }
        return values.getFirst();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("运营告警 detail 序列化失败", ex);
        }
    }

    private static String token() {
        return UUID.randomUUID().toString().replace("-", "").toUpperCase();
    }

    private LockedAlert lockBusinessAlert(long alertId) {
        List<LockedAlert> values = jdbc.query(
                """
                SELECT oa.status, oa.lock_version,
                       COALESCE(direct_order.id, line_order.id, fulfillment_order.id, shipment_order.id) business_order_id
                """ + BUSINESS_JOINS + " AND oa.id=? FOR UPDATE OF oa",
                (rs, rowNum) -> new LockedAlert(
                        rs.getString("status"), rs.getLong("lock_version"), rs.getLong("business_order_id")),
                alertId);
        if (values.isEmpty()) {
            throw BusinessException.notFound("运营提醒不存在");
        }
        return values.getFirst();
    }

    private OperationalAlertDto loadBusinessAlert(long alertId) {
        List<OperationalAlertDto> values = jdbc.query(
                "SELECT oa.* " + BUSINESS_JOINS + " AND oa.id=?",
                (rs, rowNum) -> dto(rs),
                alertId);
        if (values.isEmpty()) {
            throw BusinessException.notFound("运营提醒不存在");
        }
        return values.getFirst();
    }

    private OperationalAlertDto dto(ResultSet rs) throws SQLException {
        return new OperationalAlertDto(
                String.valueOf(rs.getLong("id")),
                rs.getString("alert_no"),
                rs.getString("alert_type"),
                rs.getString("severity"),
                rs.getString("status"),
                identifier(rs, "order_id"),
                identifier(rs, "order_line_id"),
                identifier(rs, "fulfillment_id"),
                identifier(rs, "shipment_id"),
                rs.getString("message"),
                jsonObject(rs.getObject("detail")),
                rs.getString("acknowledged_by"),
                instant(rs, "acknowledged_at"),
                instant(rs, "resolved_at"),
                rs.getLong("lock_version"),
                instant(rs, "created_at"));
    }

    private static String identifier(ResultSet rs, String column) throws SQLException {
        Long value = rs.getObject(column, Long.class);
        return value == null ? null : String.valueOf(value);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        var value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private Map<String, Object> jsonObject(Object value) {
        if (value == null) {
            return Map.of();
        }
        try {
            String json = value instanceof PGobject pg ? pg.getValue() : String.valueOf(value);
            return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception ex) {
            throw new IllegalStateException("运营提醒 detail 不是合法 JSON", ex);
        }
    }

    private record LockedAlert(String status, long version, long businessOrderId) {}
}
