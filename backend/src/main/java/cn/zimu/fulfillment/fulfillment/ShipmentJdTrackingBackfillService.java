package cn.zimu.fulfillment.fulfillment;

import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.domain.DataScope;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.idempotency.IdempotencyService;
import cn.zimu.fulfillment.common.idempotency.IdempotentResult;
import cn.zimu.fulfillment.common.web.CommandContext;
import cn.zimu.fulfillment.common.web.RequestContext;
import cn.zimu.fulfillment.common.web.WriteCommands;
import cn.zimu.fulfillment.connector.jd.JDWarehouseService;
import cn.zimu.fulfillment.connector.jd.JdResult;
import cn.zimu.fulfillment.file.SourceReturnDerivationQueue;
import cn.zimu.fulfillment.file.SourceReturnDerivationRunner;
import cn.zimu.fulfillment.file.TrackingFileService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Shipment 级 querySoOrder 回填编排：本地准备、JD 只读查询、本地原子完成三阶段。
 * 原始 JD 响应可含收件人 PII，本服务只保存白名单事实摘要。
 */
@Service
public class ShipmentJdTrackingBackfillService {

    static final String SCOPE = "shipment.jd_tracking.backfill";
    private static final Logger log = LoggerFactory.getLogger(ShipmentJdTrackingBackfillService.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final IdempotencyService idempotency;
    private final JDWarehouseService jdWarehouse;
    private final ShipmentTrackingService tracking;
    private final CarrierPrefixMatcher carrierMatcher;
    private final AuditLogService audits;
    private final TrackingFileService trackingFiles;
    private final SourceReturnDerivationQueue sourceReturnDerivations;
    private final SourceReturnDerivationRunner sourceReturnDerivationRunner;
    private final String clientMode;

    public ShipmentJdTrackingBackfillService(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            IdempotencyService idempotency,
            JDWarehouseService jdWarehouse,
            ShipmentTrackingService tracking,
            CarrierPrefixMatcher carrierMatcher,
            AuditLogService audits,
            TrackingFileService trackingFiles,
            SourceReturnDerivationQueue sourceReturnDerivations,
            SourceReturnDerivationRunner sourceReturnDerivationRunner,
            @Value("${app.jd.client-mode:MOCK}") String clientMode) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.idempotency = idempotency;
        this.jdWarehouse = jdWarehouse;
        this.tracking = tracking;
        this.carrierMatcher = carrierMatcher;
        this.audits = audits;
        this.trackingFiles = trackingFiles;
        this.sourceReturnDerivations = sourceReturnDerivations;
        this.sourceReturnDerivationRunner = sourceReturnDerivationRunner;
        this.clientMode = "REAL".equalsIgnoreCase(clientMode == null ? "" : clientMode.trim())
                ? "REAL" : "MOCK";
    }

    public IdempotentResult<Map<String, Object>> backfill(
            long shipmentId, String idempotencyKey, CommandContext context) {
        try {
            IdempotentResult<Map<String, Object>> result =
                    idempotency.executeWithPreparedReadOnlyExternalWork(
                            SCOPE,
                            WriteCommands.requireIdempotencyKey(idempotencyKey),
                            200,
                            () -> prepare(shipmentId),
                            prepared -> Map.of(
                                    "shipment_id", prepared.shipmentId(),
                                    "shipment_version", prepared.shipmentVersion(),
                                    "erp_delivery_no", prepared.erpDeliveryNo(),
                                    "client_mode", prepared.clientMode(),
                                    "submitted_warehouse_no", prepared.submittedWarehouseNo(),
                                    "submitted_owner_no", prepared.submittedOwnerNo(),
                                    "submitted_cargo_snapshot", prepared.cargos(),
                                    "shipment_items", prepared.items()),
                            this::query,
                            (prepared, external) -> complete(prepared, external, context));
            if (result.replayed()) {
                auditOutOfBandOutcome(
                        shipmentId, context, 200,
                        "JD_TRACKING_IDEMPOTENT_REPLAY", "幂等重放首次京东运单查询结果");
            }
            return completeSourceReturnDerivations(
                    shipmentId, context.operator(), result);
        } catch (BusinessException exception) {
            auditOutOfBandOutcome(
                    shipmentId, context, exception.getHttpStatus(),
                    exception.getBusinessCode(), exception.getMessage());
            throw exception;
        }
    }

    /** 调度器和手工入口共用同一用例，仅幂等键来源不同。 */
    public Map<String, Object> scheduledBackfill(long shipmentId, String idempotencyKey) {
        CommandContext context = new CommandContext(
                "jd-tracking-poll-" + UUID.randomUUID().toString().replace("-", ""),
                "jd-tracking-poller",
                "jd-tracking-poller");
        RequestContext previous = RequestContext.current();
        RequestContext.set(new RequestContext(
                context.requestId(), context.traceId(), context.operator()));
        try {
            IdempotentResult<Map<String, Object>> result = backfill(
                    shipmentId, idempotencyKey, context);
            if (result.replayed()) {
                return objectMapper.convertValue(result.replayedBody(), new TypeReference<>() {});
            }
            return result.result();
        } finally {
            if (previous == null) {
                RequestContext.clear();
            } else {
                RequestContext.set(previous);
            }
        }
    }

    @Transactional
    public Prepared prepare(long shipmentId) {
        Prepared prepared = jdbc.query(
                """
                SELECT s.id, s.order_id, s.outbound_order_no, s.shipment_status, s.lock_version,
                       fp.provider_type, j.erp_delivery_no, j.jd_delivery_no, j.sync_status,
                       j.client_mode, j.submitted_warehouse_no, j.submitted_owner_no,
                       j.submitted_pin, j.tracking_query_status,
                       COALESCE(cargo_review.id, 0) cargo_review_case_id,
                       COALESCE(cargo_review.resolution_version, 0) cargo_review_version,
                       j.submitted_cargo_snapshot::text cargo_snapshot
                FROM app.shipments s
                JOIN app.orders o ON o.id=s.order_id AND o.data_scope='BUSINESS'
                JOIN app.fulfillment_providers fp ON fp.id=s.fulfillment_provider_id
                JOIN app.shipment_jd_outbounds j ON j.shipment_id=s.id
                LEFT JOIN LATERAL (
                    SELECT rc.id, rc.resolution_version
                    FROM app.review_cases rc
                    WHERE rc.shipment_id=s.id
                      AND rc.reason_code='JD_TRACKING_CARGO_MISMATCH'
                    ORDER BY rc.id DESC
                    LIMIT 1
                ) cargo_review ON TRUE
                WHERE s.id=?
                FOR NO KEY UPDATE OF s
                """,
                rs -> {
                    if (!rs.next()) return null;
                    List<Item> items = jdbc.query(
                            """
                            SELECT si.fulfillment_id, f.order_line_id, si.instructed_quantity
                            FROM app.shipment_items si
                            JOIN app.fulfillments f ON f.id=si.fulfillment_id
                            WHERE si.shipment_id=? ORDER BY si.id
                            """,
                            (itemRs, row) -> new Item(
                                    itemRs.getLong("fulfillment_id"),
                                    itemRs.getLong("order_line_id"),
                                    itemRs.getInt("instructed_quantity")),
                            shipmentId);
                    return new Prepared(
                            rs.getLong("id"), rs.getLong("order_id"),
                            rs.getString("outbound_order_no"), rs.getString("erp_delivery_no"),
                            rs.getString("jd_delivery_no"), rs.getString("shipment_status"),
                            rs.getLong("lock_version"), rs.getString("provider_type"),
                            rs.getString("sync_status"), rs.getString("client_mode"),
                            rs.getString("submitted_warehouse_no"), rs.getString("submitted_owner_no"),
                            rs.getString("submitted_pin"),
                            rs.getString("tracking_query_status"),
                            rs.getLong("cargo_review_case_id"),
                            rs.getLong("cargo_review_version"),
                            parseCargoSnapshot(rs.getString("cargo_snapshot")), items);
                },
                shipmentId);
        if (prepared == null) {
            throw BusinessException.notFound("京东发货批次或已提交记录不存在");
        }
        requireEligible(prepared);
        return prepared;
    }

    private JdResult query(Prepared prepared) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("JD tracking query must run outside a database transaction");
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("erpDeliveryNo", prepared.erpDeliveryNo());
        request.put("ownerNo", prepared.submittedOwnerNo());
        request.put("pin", prepared.submittedPin());
        request.put("deliveryItemFlag", 1);
        request.put("deliveryPackageFlag", 1);
        request.put("deliveryStatusFlag", 1);
        // SoQueryRequest itself has no warehouse field.  The local Mock receives the
        // submitted warehouse only so it can echo a contract-valid response; REAL keeps
        // the official strict request shape and validates warehouseNo from the response.
        if ("MOCK".equals(prepared.clientMode())) {
            request.put("warehouseNo", prepared.submittedWarehouseNo());
            request.put("mockExpectedDeliveryItemList", prepared.cargos().stream()
                    .map(cargo -> Map.<String, Object>of(
                            "orderLine", cargo.orderLine(),
                            "goodsNo", cargo.goodsNo(),
                            "planQuantity", cargo.planQuantity()))
                    .toList());
        }
        try {
            return jdWarehouse.queryOutboundOrder(request);
        } catch (RuntimeException exception) {
            // Connector/audit/SDK adapters must not turn a read failure into an unaudited
            // 500 or leak their raw exception text.  The normal completion transaction
            // persists this fixed diagnostic and the sanitized backfill audit.
            return new JdResult(
                    false,
                    "JD_TRACKING_QUERY_EXCEPTION",
                    "京东出库单查询失败，可使用新幂等键重试",
                    null,
                    null);
        }
    }

    private Map<String, Object> complete(
            Prepared prepared, JdResult result, CommandContext context) {
        Prepared current = prepare(prepared.shipmentId());
        OpenConflictCase openConflictCase = openConflictCase(current.shipmentId());
        if (openConflictCase != null) {
            return absorbOpenConflict(current, result, context, openConflictCase);
        }
        if ("TRACKED".equals(current.trackingQueryStatus())) {
            return completeTrackedObservation(current, result, context);
        }
        if (current.shipmentVersion() != prepared.shipmentVersion()
                || !Objects.equals(current.erpDeliveryNo(), prepared.erpDeliveryNo())
                || !Objects.equals(current.clientMode(), prepared.clientMode())
                || !Objects.equals(current.submittedWarehouseNo(), prepared.submittedWarehouseNo())
                || !Objects.equals(current.submittedOwnerNo(), prepared.submittedOwnerNo())
                || current.cargoReviewCaseId() != prepared.cargoReviewCaseId()
                || current.cargoReviewVersion() != prepared.cargoReviewVersion()
                || !Objects.equals(current.cargos(), prepared.cargos())
                || !Objects.equals(current.items(), prepared.items())) {
            throw BusinessException.conflict(
                    "JD_TRACKING_BACKFILL_FACTS_CHANGED", "京东查询期间 Shipment 或建单事实已变化，请重试");
        }
        Parsed parsed = parse(current, result);
        return switch (parsed.status()) {
            case "TRACKED" -> completeTracked(current, parsed, result, context);
            case "CONFLICT" -> completeConflict(current, parsed, result, context);
            default -> completeDiagnostic(current, parsed, result, context);
        };
    }

    /** TRACKED 业务事实不回退，但新的冲突或失败观察仍必须显式进入 Case/诊断。 */
    private Map<String, Object> completeTrackedObservation(
            Prepared current, JdResult result, CommandContext context) {
        TrackingFact accepted = jdbc.query(
                        """
                        SELECT logistics_company_code, logistics_company_name, tracking_number
                        FROM app.trackings WHERE shipment_id=?
                        """,
                        (rs, row) -> new TrackingFact(
                                rs.getString("logistics_company_code"),
                                rs.getString("logistics_company_name"),
                                rs.getString("tracking_number")),
                        current.shipmentId())
                .stream()
                .findFirst()
                .orElseThrow(() -> BusinessException.conflict(
                        "JD_TRACKING_TERMINAL_FACT_MISSING", "京东回填状态已完成但本地运单事实缺失"));
        Parsed parsed = parse(current, result);
        if ("CONFLICT".equals(parsed.status())) {
            return completeConflict(current, parsed, result, context);
        }
        if ("QUERY_FAILED".equals(parsed.status())) {
            return completeTrackedFailure(current, parsed, result, context, accepted.trackingNumber());
        }
        if ("TRACKED".equals(parsed.status())
                && (!Objects.equals(accepted.carrierCode(), parsed.carrierCode())
                        || !Objects.equals(accepted.carrierName(), parsed.carrierName())
                        || !Objects.equals(accepted.trackingNumber(), parsed.waybillNo()))) {
            return completeConflict(current, parsed.asLocalConflict(), result, context);
        }
        return absorbTrackedTerminal(current, result, context, accepted.trackingNumber());
    }

    /** 同一已接受事实或迟到的 pending/partial 只记观察，不回退 Shipment 履约事实。 */
    private Map<String, Object> absorbTrackedTerminal(
            Prepared current,
            JdResult result,
            CommandContext context,
            String trackingNumber) {
        jdbc.update(
                """
                UPDATE app.shipment_jd_outbounds
                SET tracking_query_attempt_count=tracking_query_attempt_count+1,
                    tracking_last_query_at=CURRENT_TIMESTAMP, tracking_last_request_id=?,
                    tracking_last_error_code=NULL, tracking_last_error_message=NULL,
                    updated_at=CURRENT_TIMESTAMP
                WHERE shipment_id=? AND tracking_query_status='TRACKED'
                """,
                safeRequestId(result), current.shipmentId());
        audit(
                current,
                context,
                Parsed.absorbed(trackingNumber),
                result,
                200,
                "JD_TRACKING_TERMINAL_ABSORBED");
        Map<String, Object> response = response(current, "TRACKED", trackingNumber, false, null);
        enqueueSourceReturnDerivation(current.shipmentId(), context.operator(), response);
        return response;
    }

    private Map<String, Object> completeTrackedFailure(
            Prepared current,
            Parsed parsed,
            JdResult result,
            CommandContext context,
            String trackingNumber) {
        jdbc.update(
                """
                UPDATE app.shipment_jd_outbounds
                SET tracking_query_attempt_count=tracking_query_attempt_count+1,
                    tracking_last_query_at=CURRENT_TIMESTAMP, tracking_last_request_id=?,
                    tracking_last_error_code=?, tracking_last_error_message=?,
                    updated_at=CURRENT_TIMESTAMP
                WHERE shipment_id=? AND tracking_query_status='TRACKED'
                """,
                safeRequestId(result),
                parsed.businessCode(),
                parsed.message(),
                current.shipmentId());
        audit(current, context, parsed, result, 200, parsed.businessCode());
        return response(current, "QUERY_FAILED", trackingNumber, true, parsed.businessCode());
    }

    /** OPEN 冲突 Case 是人工裁决边界；已在途的另一把 key 不得越过它自动写 Tracking。 */
    private Map<String, Object> absorbOpenConflict(
            Prepared current,
            JdResult result,
            CommandContext context,
            OpenConflictCase reviewCase) {
        persistDiagnostic(current, "CONFLICT", null, null, result);
        audit(
                current,
                context,
                Parsed.conflict(null, List.of()),
                result,
                200,
                "JD_TRACKING_CONFLICT_ABSORBED");
        Map<String, Object> response = response(
                current, "CONFLICT", null, false, reviewCase.reasonCode());
        response.put("review_case_id", String.valueOf(reviewCase.id()));
        return response;
    }

    private Map<String, Object> completeTracked(
            Prepared current, Parsed parsed, JdResult result, CommandContext context) {
        Map<String, Object> rawPayload = new LinkedHashMap<>();
        rawPayload.put("source", "JD_QUERY_SO_ORDER");
        rawPayload.put("erp_delivery_no", current.erpDeliveryNo());
        rawPayload.put("jd_delivery_no", current.jdDeliveryNo());
        rawPayload.put("jd_status", parsed.jdStatus());
        rawPayload.put("request_id", safeRequestId(result));
        ShipmentTrackingAcceptance accepted = tracking.acceptShipment(new ShipmentTrackingBatchCommand(
                null,
                current.shipmentId(),
                current.orderId(),
                current.items().stream()
                        .map(item -> new ShipmentTrackingBatchCommand.Item(
                                item.fulfillmentId(), item.orderLineId(), item.instructedQuantity()))
                        .toList(),
                parsed.carrierCode(),
                parsed.carrierName(),
                parsed.waybillNo(),
                null,
                rawPayload,
                "京东云仓运单回填"), context);
        if (accepted.conflicted()) {
            return completeConflict(current, parsed.asLocalConflict(), result, context);
        }
        persistDiagnostic(current, "TRACKED", null, null, result);
        audit(current, context, parsed, result, 200,
                accepted.replayed() ? "JD_TRACKING_ALREADY_RECORDED" : "JD_TRACKING_BACKFILLED");
        if (!accepted.replayed()) {
            openBackfilledPendingReviewCase(current, parsed);
        }
        Map<String, Object> response = response(current, "TRACKED", parsed.waybillNo(), false, null);
        enqueueSourceReturnDerivation(current.shipmentId(), context.operator(), response);
        return response;
    }

    /** Tracking 完成事务内只写持久派生任务，不读取或生成来源文件。 */
    private void enqueueSourceReturnDerivation(
            long shipmentId, String operator, Map<String, Object> response) {
        sourceReturnDerivations.enqueue(shipmentId, null, operator);
        response.put("generated_source_return_export_ids", List.of());
    }

    /** 幂等完成事务提交后执行快路；失败任务留在队列重试，不能改写已接受的运单事实。 */
    private IdempotentResult<Map<String, Object>> completeSourceReturnDerivations(
            long shipmentId,
            String operator,
            IdempotentResult<Map<String, Object>> result) {
        String pollStatus = result.replayed()
                ? result.replayedBody().path("poll_status").asText()
                : Objects.toString(result.result().get("poll_status"), "");
        if (!"TRACKED".equals(pollStatus)) {
            return result;
        }
        List<String> sourceReturnIds;
        try {
            long taskId = sourceReturnDerivations.enqueue(shipmentId, null, operator);
            sourceReturnDerivationRunner.runDue(List.of(taskId));
            sourceReturnIds = trackingFiles.sourceReturnIdsForShipment(shipmentId);
        } catch (RuntimeException exception) {
            log.warn(
                    "Source return derivation fast path deferred after JD tracking commit ({})",
                    exception.getClass().getSimpleName());
            log.debug("JD tracking source return fast path failed", exception);
            return result;
        }
        if (result.replayed()) {
            Map<String, Object> replayed = objectMapper.convertValue(
                    result.replayedBody(), new TypeReference<>() {});
            replayed.put("generated_source_return_export_ids", sourceReturnIds);
            return IdempotentResult.replayed(result.httpStatus(), objectMapper.valueToTree(replayed));
        }
        Map<String, Object> executed = new LinkedHashMap<>(result.result());
        executed.put("generated_source_return_export_ids", sourceReturnIds);
        return IdempotentResult.executed(executed, result.httpStatus());
    }

    private void openBackfilledPendingReviewCase(Prepared current, Parsed parsed) {
        String reasonCode = "JD_TRACKING_BACKFILLED_PENDING_REVIEW";
        String caseNo = "RC-JD-TRACK-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        String detail = json(Map.of(
                "message", "京东运单回填完成，待人工确认发货信息",
                "erp_delivery_no", current.erpDeliveryNo(),
                "waybill_no", nullToEmpty(parsed.waybillNo()),
                "carrier_code", nullToEmpty(parsed.carrierCode()),
                "carrier_name", nullToEmpty(parsed.carrierName())));
        jdbc.update(
                """
                INSERT INTO app.review_cases
                    (case_no, case_type, status, responsible_team, reason_code,
                     order_id, shipment_id, detail)
                VALUES (?, 'JD_TRACKING', 'OPEN', 'FULFILLMENT_OPS',
                        ?, ?, ?, ?::jsonb)
                ON CONFLICT DO NOTHING
                """,
                caseNo, reasonCode, current.orderId(), current.shipmentId(), detail);
        jdbc.update(
                """
                UPDATE app.review_cases SET detail=?::jsonb, updated_at=CURRENT_TIMESTAMP
                WHERE shipment_id=? AND status='OPEN' AND reason_code=?
                """,
                detail, current.shipmentId(), reasonCode);
    }

    private Map<String, Object> completeConflict(
            Prepared current, Parsed parsed, JdResult result, CommandContext context) {
        String reviewReason = switch (nullToEmpty(parsed.businessCode())) {
            case "JD_TRACKING_CARRIER_MAPPING_REQUIRED" -> "JD_TRACKING_CARRIER_MAPPING_REQUIRED";
            case "JD_TRACKING_TERMINAL_EXCEPTION" -> "JD_TRACKING_TERMINAL_EXCEPTION";
            case "JD_TRACKING_CARGO_MISMATCH" -> "JD_TRACKING_CARGO_MISMATCH";
            default -> "MULTIPLE_TRACKINGS_FOR_OUTBOUND";
        };
        String reviewMessage = switch (reviewReason) {
            case "JD_TRACKING_CARRIER_MAPPING_REQUIRED" ->
                    "京东承运商无法唯一匹配启用的内部 Carrier 主数据，禁止自动回填";
            case "JD_TRACKING_TERMINAL_EXCEPTION" ->
                    "京东出库单已进入取消、拉回或拒收等异常终态，需人工复核";
            case "JD_TRACKING_CARGO_MISMATCH" ->
                    "京东货品与本次建单冻结快照不一致，禁止自动归属运单，需人工核对";
            default -> "京东返回多个或与本地冲突的运单，P0 不自动拆单";
        };
        Map<String, Object> detailData = new LinkedHashMap<>();
        detailData.put("message", reviewMessage);
        detailData.put("erp_delivery_no", current.erpDeliveryNo());
        detailData.put("tracking_candidates", parsed.candidates());
        detailData.put("jd_status", nullToEmpty(parsed.jdStatus()));
        detailData.put("carrier_mapping", parsed.carrierMappingDiagnostic());
        detailData.putAll(parsed.cargoDiagnostic());
        String detail = json(detailData);
        // case_no 自身全局唯一：旧 Case 终结后的新冲突应创建新 Case，
        // 并发的当前冲突则由 open-subject 唯一索引收敛并复用。
        String caseNo = "RC-JD-TRACK-" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        jdbc.update(
                """
                INSERT INTO app.review_cases
                    (case_no, case_type, status, responsible_team, reason_code,
                     order_id, shipment_id, detail)
                VALUES (?, 'JD_TRACKING', 'OPEN', 'FULFILLMENT_OPS',
                        ?, ?, ?, ?::jsonb)
                ON CONFLICT DO NOTHING
                """,
                caseNo, reviewReason, current.orderId(), current.shipmentId(), detail);
        jdbc.update(
                """
                UPDATE app.review_cases SET detail=?::jsonb, updated_at=CURRENT_TIMESTAMP
                WHERE shipment_id=? AND status='OPEN' AND reason_code=?
                """,
                detail, current.shipmentId(), reviewReason);
        persistDiagnostic(current, "CONFLICT", null, null, result);
        audit(current, context, parsed, result, 200, reviewReason);
        Long caseId = jdbc.queryForObject(
                """
                SELECT id FROM app.review_cases
                WHERE shipment_id=? AND status='OPEN' AND reason_code=?
                """,
                Long.class,
                current.shipmentId(),
                reviewReason);
        Map<String, Object> response = response(current, "CONFLICT", null, false, reviewReason);
        response.put("review_case_id", String.valueOf(caseId));
        return response;
    }

    private OpenConflictCase openConflictCase(long shipmentId) {
        return jdbc.query(
                        """
                        SELECT id, reason_code FROM app.review_cases
                        WHERE shipment_id=? AND status='OPEN'
                          AND reason_code IN (
                              'MULTIPLE_TRACKINGS_FOR_OUTBOUND',
                              'JD_TRACKING_CARRIER_MAPPING_REQUIRED',
                              'JD_TRACKING_TERMINAL_EXCEPTION',
                              'JD_TRACKING_CARGO_MISMATCH')
                        ORDER BY id
                        """,
                        (rs, row) -> new OpenConflictCase(
                                rs.getLong("id"), rs.getString("reason_code")),
                        shipmentId)
                .stream()
                .findFirst()
                .orElse(null);
    }

    private Map<String, Object> completeDiagnostic(
            Prepared current, Parsed parsed, JdResult result, CommandContext context) {
        String errorCode = "QUERY_FAILED".equals(parsed.status()) ? parsed.businessCode() : null;
        String errorMessage = "QUERY_FAILED".equals(parsed.status()) ? parsed.message() : null;
        persistDiagnostic(current, parsed.status(), errorCode, errorMessage, result);
        audit(
                current,
                context,
                parsed,
                result,
                200,
                errorCode == null ? "JD_TRACKING_" + parsed.status() : errorCode);
        return response(
                current, parsed.status(), null, "QUERY_FAILED".equals(parsed.status()), errorCode);
    }

    private Parsed parse(Prepared prepared, JdResult result) {
        if (result == null || !result.success()) {
            return Parsed.failed(
                    safeCode(result == null ? null : result.businessCode()),
                    "京东出库单查询失败，可使用新幂等键重试");
        }
        try {
            Map<String, Object> data = remoteResponseEnvelope(result.data());
            if (!Objects.equals(
                    prepared.erpDeliveryNo(), remoteRequiredText(data.get("erpDeliveryNo"), 64))) {
                return Parsed.failed("JD_TRACKING_ERP_REFERENCE_MISMATCH", "京东返回的商户侧出库引用不匹配");
            }
            if (!Objects.equals(
                    prepared.submittedWarehouseNo(), remoteRequiredText(data.get("warehouseNo"), 128))) {
                return Parsed.failed("JD_TRACKING_WAREHOUSE_MISMATCH", "京东返回的仓库与建单快照不匹配");
            }
            String deliveryNo = remoteRequiredText(data.get("deliveryNo"), 64);
            if (!Objects.equals(prepared.jdDeliveryNo(), deliveryNo)) {
                return Parsed.failed("JD_TRACKING_DELIVERY_REFERENCE_MISMATCH", "京东出库单引用不匹配");
            }
            String jdStatus = remoteRequiredToken(data.get("status"), 16, "[0-9]+");
            if (JdOutboundStatus.TERMINAL_EXCEPTION_STATUS.contains(jdStatus)) {
                return Parsed.terminalException(jdStatus);
            }
            List<String> splitNos = remoteCommaTokens(data.get("splitDeliveryNos"), 20, 128);
            String splitValue = remoteRequiredToken(data.get("isSplit"), 1, "[01]");
            boolean split = "1".equals(splitValue) || !splitNos.isEmpty();
            Map<String, Object> carrier = remoteOptionalMap(data.get("carrierInfo"));
            String carrierCode = remoteOptionalToken(carrier.get("carrierNo"), 64, "[A-Za-z0-9._:/-]+");
            String carrierName = remoteOptionalText(carrier.get("carrierName"), 128);
            String waybillNo = remoteOptionalToken(carrier.get("waybillNo"), 128, "[A-Za-z0-9._:/-]+");
            LinkedHashSet<String> candidates = new LinkedHashSet<>(splitNos);
            if (waybillNo != null) candidates.add(waybillNo);
            if (candidates.size() > 20) throw MalformedRemoteResponse.INSTANCE;
            if (split || candidates.size() > 1) {
                return Parsed.conflict(jdStatus, List.copyOf(candidates));
            }
            if (!JdOutboundStatus.SHIPPED_STATUS.contains(jdStatus)) {
                return Parsed.pending(jdStatus);
            }
            if (carrierCode == null || carrierName == null || waybillNo == null) {
                return Parsed.failed("JD_TRACKING_CARRIER_INCOMPLETE", "已出库结果缺少唯一承运商或运单号");
            }
            CarrierPrefixMatcher.Carrier canonicalByCode =
                    carrierMatcher.resolveStated(carrierCode).orElse(null);
            CarrierPrefixMatcher.Carrier canonicalByName =
                    carrierMatcher.resolveStated(carrierName).orElse(null);
            // 京东 stated 承运商是京东内部编码/名称（如 CYS0000010/京东配送），不在内部主数据时
            // 回退到运单号前缀映射（V21 主数据权威，如 JDVA→JD）；仍是恰好一个启用 Carrier 才接受。
            CarrierPrefixMatcher.Carrier canonicalByPrefix =
                    carrierMatcher.resolvePrefix(waybillNo).orElse(null);
            if (canonicalByCode != null
                    && canonicalByCode.name() != null
                    && !canonicalByCode.name().isBlank()
                    && canonicalByCode.equals(canonicalByName)) {
                // stated 代码与名称一致命中，维持既有严格路径。
            } else if (canonicalByPrefix != null) {
                canonicalByCode = canonicalByPrefix;
                canonicalByName = canonicalByPrefix;
            } else {
                return Parsed.carrierMappingRequired(
                        jdStatus,
                        carrierCode,
                        carrierName,
                        waybillNo,
                        canonicalByCode == null ? "" : canonicalByCode.code(),
                        canonicalByName == null ? "" : canonicalByName.code());
            }
            List<RemoteCargo> remoteCargos = normalizeRemoteCargos(
                    remoteListOfMaps(data.get("deliveryItemList")));
            QuantityCheck quantities = compareQuantities(prepared.cargos(), remoteCargos);
            if (quantities.mismatch()) {
                return Parsed.cargoMismatch(
                        jdStatus,
                        waybillNo,
                        cargoMismatchDiagnostic(prepared.cargos(), remoteCargos));
            }
            if (!quantities.complete()) {
                return Parsed.partial(jdStatus);
            }
            return Parsed.tracked(
                    jdStatus, canonicalByCode.code(), canonicalByCode.name(), waybillNo);
        } catch (MalformedRemoteResponse ignored) {
            return Parsed.failed("JD_TRACKING_RESPONSE_MALFORMED", "京东查询响应格式异常，禁止自动回填");
        }
    }

    /** 先严格规范化全部远端行；结构畸形必须进入 RESPONSE_MALFORMED，不能永久停轮询。 */
    private List<RemoteCargo> normalizeRemoteCargos(List<Map<String, Object>> rows) {
        List<RemoteCargo> result = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            String orderLine = remoteRequiredText(row.get("orderLine"), 128);
            String goodsNo = remoteRequiredText(row.get("goodsNo"), 128);
            Integer planQuantity = exactInt(row.get("planQuantity"));
            if (planQuantity == null) {
                throw MalformedRemoteResponse.INSTANCE;
            }
            Object rawRealQuantity = row.get("realQuantity");
            Integer realQuantity = exactInt(rawRealQuantity);
            if (rawRealQuantity != null && realQuantity == null) {
                throw MalformedRemoteResponse.INSTANCE;
            }
            result.add(new RemoteCargo(orderLine, goodsNo, planQuantity, realQuantity));
        }
        return List.copyOf(result);
    }

    private QuantityCheck compareQuantities(List<Cargo> expected, List<RemoteCargo> actual) {
        if (expected.size() != actual.size()) return new QuantityCheck(false, true);
        Map<String, Cargo> byKey = new LinkedHashMap<>();
        for (Cargo cargo : expected) {
            if (byKey.putIfAbsent(cargo.key(), cargo) != null) return new QuantityCheck(false, true);
        }
        boolean complete = true;
        Set<String> seen = new LinkedHashSet<>();
        for (RemoteCargo row : actual) {
            Cargo cargo = byKey.get(Cargo.key(row.orderLine(), row.goodsNo()));
            int plan = row.planQuantity();
            Integer real = row.realQuantity();
            if (cargo == null || !seen.add(cargo.key()) || plan != cargo.planQuantity()) {
                return new QuantityCheck(false, true);
            }
            if (real == null) {
                // 京东在 100130 预分拣-获取运单即返回 waybillNo，但 realQuantity 要到拣货完成
                // 才填写（真实探测 2026-08-18：10015/10016 realQuantity=null）。运单号已是最终
                // 物流承诺，数量未报不等同于部分发货，按指令量回填；真实少发（real<plan）
                // 仍保持 PARTIAL 等待。
            } else if (real < 0 || real > plan) {
                return new QuantityCheck(false, true);
            } else if (real != plan) {
                complete = false;
            }
        }
        return new QuantityCheck(complete, false);
    }

    /**
     * 只保留人工判断货品归属所需的白名单字段；绝不复制 querySoOrder 原始响应、收件人或凭据。
     */
    private Map<String, Object> cargoMismatchDiagnostic(
            List<Cargo> expected,
            List<RemoteCargo> actual) {
        List<Map<String, Object>> local = expected.stream().map(cargo -> Map.<String, Object>of(
                "order_line", cargo.orderLine(),
                "goods_no", cargo.goodsNo(),
                "plan_quantity", cargo.planQuantity())).toList();
        List<Map<String, Object>> remote = new ArrayList<>();
        LinkedHashSet<String> mismatchFields = new LinkedHashSet<>();
        if (expected.size() != actual.size()) {
            mismatchFields.add("item_count");
        }

        Map<String, Cargo> localByKey = new LinkedHashMap<>();
        for (Cargo cargo : expected) {
            if (localByKey.putIfAbsent(cargo.key(), cargo) != null) {
                mismatchFields.add("duplicate_local_cargo");
            }
        }
        Set<String> remoteKeys = new LinkedHashSet<>();
        for (RemoteCargo row : actual) {
            String orderLine = row.orderLine();
            String goodsNo = row.goodsNo();
            int planQuantity = row.planQuantity();
            Integer realQuantity = row.realQuantity();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("order_line", orderLine);
            item.put("goods_no", goodsNo);
            item.put("plan_quantity", planQuantity);
            if (realQuantity != null) item.put("real_quantity", realQuantity);
            remote.add(item);

            String key = Cargo.key(orderLine, goodsNo);
            if (!remoteKeys.add(key)) {
                mismatchFields.add("duplicate_jd_cargo");
            }
            Cargo localCargo = localByKey.get(key);
            if (localCargo == null) {
                mismatchFields.add("unexpected_jd_cargo");
            } else if (planQuantity != localCargo.planQuantity()) {
                mismatchFields.add("plan_quantity");
            } else if (realQuantity != null
                    && (realQuantity < 0 || realQuantity > planQuantity)) {
                mismatchFields.add("real_quantity");
            }
        }
        if (!remoteKeys.containsAll(localByKey.keySet())) {
            mismatchFields.add("missing_jd_cargo");
        }
        if (mismatchFields.isEmpty()) {
            mismatchFields.add("cargo");
        }
        remote.sort(Comparator.comparing(item ->
                Objects.toString(item.get("order_line"), "") + "\u0000"
                        + Objects.toString(item.get("goods_no"), "")));

        Map<String, Object> diagnostic = new LinkedHashMap<>();
        diagnostic.put("mismatch_fields", List.copyOf(mismatchFields));
        diagnostic.put("local_cargo", local);
        diagnostic.put("jd_cargo", List.copyOf(remote));
        return diagnostic;
    }

    private void persistDiagnostic(
            Prepared prepared, String status, String errorCode, String errorMessage, JdResult result) {
        jdbc.update(
                """
                UPDATE app.shipment_jd_outbounds
                SET tracking_query_status=?, tracking_query_attempt_count=tracking_query_attempt_count+1,
                    tracking_last_query_at=CURRENT_TIMESTAMP, tracking_last_error_code=?,
                    tracking_last_error_message=?, tracking_last_request_id=?, updated_at=CURRENT_TIMESTAMP
                WHERE shipment_id=? AND tracking_query_status<>'TRACKED'
                """,
                status,
                errorCode,
                errorMessage,
                safeRequestId(result),
                prepared.shipmentId());
    }

    private void audit(
            Prepared prepared,
            CommandContext context,
            Parsed parsed,
            JdResult result,
            int httpStatus,
            String businessCode) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("poll_status", parsed.status());
        response.put("jd_status", nullToEmpty(parsed.jdStatus()));
        response.put("business_code", parsed.businessCode() == null ? businessCode : parsed.businessCode());
        response.put("request_id", safeRequestId(result));
        response.put("waybill_present", parsed.waybillNo() != null);
        response.put("candidate_count", parsed.candidates().size());
        audits.record(new AuditLogService.AuditCommand()
                .dataScope(DataScope.BUSINESS).orderId(prepared.orderId())
                .requestId(context.requestId()).traceId(context.traceId()).operator(context.operator())
                .actorType("jd-tracking-poller".equals(context.operator())
                        ? AuditActorType.SYSTEM : AuditActorType.HUMAN)
                .service("fulfillment").operation(SCOPE)
                .requestPayload(Map.of(
                        "shipment_id", String.valueOf(prepared.shipmentId()),
                        "erp_delivery_no", prepared.erpDeliveryNo()))
                .responsePayload(response)
                .httpStatus(httpStatus).businessCode(businessCode));
    }

    /** completion 前的重放/冲突与 prepare/completion 拒绝也都保留本次独立尝试。 */
    private void auditOutOfBandOutcome(
            long shipmentId,
            CommandContext context,
            int httpStatus,
            String businessCode,
            String message) {
        Long orderId = jdbc.query(
                        "SELECT order_id FROM app.shipments WHERE id=?",
                        (rs, row) -> rs.getLong("order_id"),
                        shipmentId)
                .stream()
                .findFirst()
                .orElse(null);
        audits.record(new AuditLogService.AuditCommand()
                .dataScope(DataScope.BUSINESS).orderId(orderId)
                .requestId(context.requestId()).traceId(context.traceId()).operator(context.operator())
                .actorType("jd-tracking-poller".equals(context.operator())
                        ? AuditActorType.SYSTEM : AuditActorType.HUMAN)
                .service("fulfillment").operation(SCOPE)
                .requestPayload(Map.of("shipment_id", String.valueOf(shipmentId)))
                .responsePayload(Map.of("message", nullToEmpty(message)))
                .httpStatus(httpStatus).businessCode(businessCode));
    }

    private Map<String, Object> response(
            Prepared prepared, String status, String trackingNumber, boolean retryable, String code) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("shipment_id", String.valueOf(prepared.shipmentId()));
        response.put("erp_delivery_no", prepared.erpDeliveryNo());
        response.put("poll_status", status);
        response.put("retryable", retryable);
        if (trackingNumber != null) response.put("tracking_number", trackingNumber);
        if (code != null) response.put("business_code", code);
        return response;
    }

    private void requireEligible(Prepared prepared) {
        if (!"JD_WAREHOUSE".equals(prepared.providerType())) {
            throw BusinessException.unprocessable(
                    "JD_TRACKING_PROVIDER_UNSUPPORTED", "仅京东云仓 Shipment 可查询回填运单");
        }
        if (!"SUBMITTED".equals(prepared.syncStatus())) {
            throw BusinessException.conflict(
                    "JD_TRACKING_OUTBOUND_NOT_SUBMITTED", "京东出库单尚未成功提交");
        }
        if (!clientMode.equals(prepared.clientMode())) {
            throw BusinessException.conflict(
                    "JD_TRACKING_CLIENT_MODE_CHANGED",
                    "当前京东客户端模式与建单时模式不一致，禁止跨模式回填运单");
        }
        if ("TERMINAL_REVIEWED".equals(prepared.trackingQueryStatus())) {
            throw BusinessException.conflict(
                    "JD_TRACKING_TERMINAL_EXCEPTION_REVIEWED",
                    "京东出库单异常终态已人工复核，不再自动查询回填");
        }
        if (prepared.cargos().isEmpty()) {
            throw BusinessException.conflict(
                    "JD_TRACKING_SUBMITTED_CARGO_MISSING", "历史建单缺少可验证的货品快照，禁止自动回填");
        }
        if (prepared.submittedWarehouseNo() == null) {
            throw BusinessException.conflict(
                    "JD_TRACKING_SUBMITTED_WAREHOUSE_MISSING", "历史建单缺少可验证的仓库快照，禁止自动回填");
        }
        if (prepared.submittedOwnerNo() == null) {
            throw BusinessException.conflict(
                    "JD_TRACKING_SUBMITTED_OWNER_MISSING", "历史建单缺少可验证的货主快照，禁止自动回填");
        }
        if (prepared.submittedPin() == null) {
            throw BusinessException.conflict(
                    "JD_TRACKING_SUBMITTED_PIN_MISSING", "历史建单缺少可验证的 pin 快照，禁止自动回填");
        }
        if (!ShipmentStatus.acceptsTrackingBackfill(prepared.shipmentStatus())) {
            throw BusinessException.conflict(
                    "JD_TRACKING_SHIPMENT_STATUS_INVALID", "当前 Shipment 状态不允许回填运单");
        }
    }

    private List<Cargo> parseCargoSnapshot(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<Map<String, Object>> rows = objectMapper.readValue(json, new TypeReference<>() {});
            List<Cargo> result = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                String orderLine = text(row.get("orderLine"));
                String goodsNo = text(row.get("goodsNo"));
                Integer quantity = exactInt(row.get("planQuantity"));
                if (orderLine == null || goodsNo == null || quantity == null || quantity <= 0) return List.of();
                result.add(new Cargo(orderLine, goodsNo, quantity));
            }
            result.sort(Comparator.comparing(Cargo::key));
            return List.copyOf(result);
        } catch (Exception exception) {
            return List.of();
        }
    }

    private Map<String, Object> remoteRequiredMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) throw MalformedRemoteResponse.INSTANCE;
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) throw MalformedRemoteResponse.INSTANCE;
            result.put(key, entry.getValue());
        }
        return result;
    }

    private Map<String, Object> remoteOptionalMap(Object value) {
        return value == null ? Map.of() : remoteRequiredMap(value);
    }

    /** REAL querySoOrder 直接返回 data；本地 Mock 额外包一层 response。 */
    private Map<String, Object> remoteResponseEnvelope(Object value) {
        Map<String, Object> data = remoteRequiredMap(value);
        return data.containsKey("response") ? remoteRequiredMap(data.get("response")) : data;
    }

    private List<Map<String, Object>> remoteListOfMaps(Object value) {
        if (!(value instanceof Collection<?> values) || values.size() > 1000) {
            throw MalformedRemoteResponse.INSTANCE;
        }
        List<Map<String, Object>> result = new ArrayList<>(values.size());
        for (Object item : values) result.add(remoteRequiredMap(item));
        return List.copyOf(result);
    }

    private List<String> remoteCommaTokens(Object value, int maxItems, int maxItemLength) {
        String raw = remoteOptionalText(value, Math.min(4096, maxItems * (maxItemLength + 1)));
        if (raw == null) return List.of();
        String[] parts = raw.split(",", -1);
        if (parts.length > maxItems) throw MalformedRemoteResponse.INSTANCE;
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String part : parts) {
            result.add(remoteRequiredToken(part, maxItemLength, "[A-Za-z0-9._:/-]+"));
        }
        return List.copyOf(result);
    }

    private String remoteRequiredToken(Object value, int maxLength, String pattern) {
        String result = remoteRequiredText(value, maxLength);
        if (!result.matches(pattern)) throw MalformedRemoteResponse.INSTANCE;
        return result;
    }

    private String remoteOptionalToken(Object value, int maxLength, String pattern) {
        String result = remoteOptionalText(value, maxLength);
        if (result != null && !result.matches(pattern)) throw MalformedRemoteResponse.INSTANCE;
        return result;
    }

    private String remoteRequiredText(Object value, int maxLength) {
        String result = remoteOptionalText(value, maxLength);
        if (result == null) throw MalformedRemoteResponse.INSTANCE;
        return result;
    }

    private String remoteOptionalText(Object value, int maxLength) {
        if (value == null) return null;
        if (!(value instanceof String raw)) throw MalformedRemoteResponse.INSTANCE;
        String result = raw.trim();
        if (result.isEmpty()) return null;
        if (result.length() > maxLength
                || result.codePoints().anyMatch(Character::isISOControl)) {
            throw MalformedRemoteResponse.INSTANCE;
        }
        return result;
    }

    private Integer exactInt(Object value) {
        if (value == null) return null;
        try {
            return cn.zimu.fulfillment.common.domain.CountQuantity.fromNonNegativeFileValue(value.toString());
        } catch (cn.zimu.fulfillment.common.domain.CountQuantity.InvalidCountQuantityException exception) {
            return null;
        }
    }

    private String safeCode(String code) {
        String value = text(code);
        if (value == null) return "JD_TRACKING_QUERY_FAILED";
        value = value.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]", "_");
        return value.length() <= 64 ? value : value.substring(0, 64);
    }

    private String safeRequestId(JdResult result) {
        String requestId = result == null ? null : text(result.requestId());
        if (requestId == null) return "";
        return requestId.length() <= 128 ? requestId : requestId.substring(0, 128);
    }

    private String text(Object value) {
        return value == null || value.toString().isBlank() ? null : value.toString().trim();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static final class MalformedRemoteResponse extends RuntimeException {
        private static final MalformedRemoteResponse INSTANCE = new MalformedRemoteResponse();

        private MalformedRemoteResponse() {
            super(null, null, false, false);
        }
    }

    public record Candidate(long shipmentId, Instant lastQueryAt) {
    }

    @Transactional(readOnly = true)
    public List<Candidate> pollingCandidates(int batchSize) {
        return jdbc.query(
                """
                SELECT j.shipment_id, j.tracking_last_query_at
                FROM app.shipment_jd_outbounds j
                JOIN app.shipments s ON s.id=j.shipment_id
                JOIN app.fulfillment_providers fp ON fp.id=s.fulfillment_provider_id
                LEFT JOIN app.trackings t ON t.shipment_id=s.id
                LEFT JOIN app.review_cases rc ON rc.shipment_id=s.id AND rc.status='OPEN'
                    AND rc.reason_code IN (
                        'MULTIPLE_TRACKINGS_FOR_OUTBOUND',
                        'JD_TRACKING_CARRIER_MAPPING_REQUIRED',
                        'JD_TRACKING_TERMINAL_EXCEPTION',
                        'JD_TRACKING_CARGO_MISMATCH')
                WHERE j.sync_status='SUBMITTED' AND j.client_mode=?
                  AND j.submitted_cargo_snapshot IS NOT NULL
                  AND j.submitted_warehouse_no IS NOT NULL
                  AND j.submitted_owner_no IS NOT NULL
                  AND j.submitted_pin IS NOT NULL
                  AND j.tracking_query_status NOT IN ('TRACKED', 'TERMINAL_REVIEWED')
                  AND s.shipment_status='CREATED'
                  AND t.id IS NULL AND rc.id IS NULL
                ORDER BY j.tracking_last_query_at NULLS FIRST, j.shipment_id
                LIMIT ?
                """,
                (rs, row) -> new Candidate(
                        rs.getLong("shipment_id"),
                        rs.getObject("tracking_last_query_at", java.time.OffsetDateTime.class) == null
                                ? null
                                : rs.getObject("tracking_last_query_at", java.time.OffsetDateTime.class).toInstant()),
                clientMode,
                batchSize);
    }

    public record Prepared(
            long shipmentId,
            long orderId,
            String outboundOrderNo,
            String erpDeliveryNo,
            String jdDeliveryNo,
            String shipmentStatus,
            long shipmentVersion,
            String providerType,
            String syncStatus,
            String clientMode,
            String submittedWarehouseNo,
            String submittedOwnerNo,
            String submittedPin,
            String trackingQueryStatus,
            long cargoReviewCaseId,
            long cargoReviewVersion,
            List<Cargo> cargos,
            List<Item> items) {

        public Prepared {
            cargos = List.copyOf(cargos);
            items = List.copyOf(items);
        }
    }

    public record Cargo(String orderLine, String goodsNo, int planQuantity) {
        String key() {
            return key(orderLine, goodsNo);
        }

        static String key(String orderLine, String goodsNo) {
            return String.valueOf(orderLine) + "\u0000" + String.valueOf(goodsNo);
        }
    }

    private record RemoteCargo(
            String orderLine,
            String goodsNo,
            int planQuantity,
            Integer realQuantity) {
    }

    public record Item(long fulfillmentId, long orderLineId, int instructedQuantity) {
    }

    private record QuantityCheck(boolean complete, boolean mismatch) {
    }

    private record TrackingFact(String carrierCode, String carrierName, String trackingNumber) {
    }

    private record OpenConflictCase(long id, String reasonCode) {
    }

    private record Parsed(
            String status,
            String jdStatus,
            String carrierCode,
            String carrierName,
            String waybillNo,
            List<String> candidates,
            String businessCode,
            String message,
            Map<String, String> carrierMappingDiagnostic,
            Map<String, Object> cargoDiagnostic) {

        static Parsed tracked(String status, String code, String name, String waybill) {
            return new Parsed(
                    "TRACKED", status, code, name, waybill, List.of(waybill), null, null,
                    Map.of(), Map.of());
        }

        static Parsed pending(String status) {
            return new Parsed(
                    "PENDING", status, null, null, null, List.of(), null, null,
                    Map.of(), Map.of());
        }

        static Parsed partial(String status) {
            return new Parsed(
                    "PARTIAL", status, null, null, null, List.of(), null, null,
                    Map.of(), Map.of());
        }

        static Parsed conflict(String status, List<String> candidates) {
            return new Parsed(
                    "CONFLICT", status, null, null, null, List.copyOf(candidates), null, null,
                    Map.of(), Map.of());
        }

        static Parsed carrierMappingRequired(
                String status,
                String externalCode,
                String externalName,
                String waybill,
                String codeMatch,
                String nameMatch) {
            return new Parsed(
                    "CONFLICT",
                    status,
                    null,
                    null,
                    null,
                    List.of(waybill),
                    "JD_TRACKING_CARRIER_MAPPING_REQUIRED",
                    "京东承运商无法唯一映射到内部 Carrier 主数据",
                    Map.of(
                            "external_code", externalCode,
                            "external_name", externalName,
                            "code_match", codeMatch,
                            "name_match", nameMatch),
                    Map.of());
        }

        static Parsed terminalException(String status) {
            return new Parsed(
                    "CONFLICT",
                    status,
                    null,
                    null,
                    null,
                    List.of(),
                    "JD_TRACKING_TERMINAL_EXCEPTION",
                    "京东出库单已进入异常终态，需人工复核",
                    Map.of(),
                    Map.of());
        }

        static Parsed cargoMismatch(
                String status,
                String waybill,
                Map<String, Object> diagnostic) {
            return new Parsed(
                    "CONFLICT",
                    status,
                    null,
                    null,
                    null,
                    waybill == null ? List.of() : List.of(waybill),
                    "JD_TRACKING_CARGO_MISMATCH",
                    "京东货品与建单冻结快照不一致，需人工核对归属",
                    Map.of(),
                    Map.copyOf(diagnostic));
        }

        static Parsed failed(String code, String message) {
            return new Parsed(
                    "QUERY_FAILED", null, null, null, null, List.of(), code, message,
                    Map.of(), Map.of());
        }

        static Parsed absorbed(String waybill) {
            return new Parsed(
                    "TRACKED", null, null, null, waybill, List.of(waybill), null, null,
                    Map.of(), Map.of());
        }

        Parsed asLocalConflict() {
            return new Parsed("CONFLICT", jdStatus, carrierCode, carrierName, waybillNo,
                    waybillNo == null ? List.of() : List.of(waybillNo), null, null,
                    Map.of(), Map.of());
        }
    }
}
