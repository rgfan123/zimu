package cn.zimu.fulfillment.fulfillment;

import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.domain.DataScope;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.event.OrderEventService;
import cn.zimu.fulfillment.common.idempotency.IdempotencyService;
import cn.zimu.fulfillment.common.idempotency.IdempotencyService.ExternalCompletion;
import cn.zimu.fulfillment.common.idempotency.IdempotentResult;
import cn.zimu.fulfillment.common.version.OrderVersionService;
import cn.zimu.fulfillment.common.web.CommandContext;
import cn.zimu.fulfillment.common.web.WriteCommands;
import cn.zimu.fulfillment.connector.jd.JdResult;
import cn.zimu.fulfillment.fulfillment.JdShipmentSubmissionPlan.Blocker;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 京东云仓建出库单用例（Shipment 边界）的事务编排单元：负责提交三阶段幂等编排、
 * 预览/收货地址/候选查询等对外用例的组装与审计记录，以及提交成功/失败事实的原子归档。
 *
 * <p>出库单构造（{@link ShipmentJdOutboundPreparer}）、京东外部调用（{@link ShipmentJdOutboundExecutor}）
 * 与审计（{@link ShipmentJdOutboundAuditService}）已拆分为独立单元，本服务只编排它们：
 * 一个 Shipment 及其全部 ShipmentItems 聚合为唯一一张京东出库单请求（addSoOrder），
 * 同一发货批次内的多个 Fulfillment 共享一个京东出库引用。
 *
 * <p>京东商家出库引用（{@code shipment_jd_outbounds.erp_delivery_no}，ZIMU-SO 独占命名空间）
 * 与本地导出号（{@code shipments.outbound_order_no}）分离；同步状态、失败阶段与重试信息由
 * Shipment 级京东出库集成记录（app.shipment_jd_outbounds，1:1）承载，不写入 Fulfillment，
 * 也不写入或扩展 OrderLine {@code processing_stage}（权威业务阶段保持原值集合）。
 *
 * <p>防重复建单：集成记录对 shipment_id / erp_delivery_no 的唯一约束 + 业务校验（已 SUBMITTED 拒绝、
 * 请求哈希漂移拒绝）；失败后同一 Shipment 可重试，重试只更新同一条记录，不产生第二张京东出库单。
 *
 * <p>写模式门闩：addSoOrder 在 {@code app.jd.write-mode=OFF} 时返回 WRITE_MODE_DISABLED，
 * 本服务将其映射为 409 JD_SHIPMENT_OUTBOUND_WRITE_MODE_DISABLED。提交先以独立事务落
 * SUBMITTING 意图，外部调用结束后再以本地事务归档成功或失败事实，均不推进伪造的完成阶段。
 */
@Service
public class ShipmentJdOutboundService {

    static final String ADDRESS_CONFIRM_SCOPE = "shipment.jd_receiver_address.confirm";
    static final String FAILURE_PHASE_SUBMIT = "SUBMIT";
    private static final String WRITE_MODE_DISABLED = "WRITE_MODE_DISABLED";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final IdempotencyService idempotency;
    private final OrderEventService events;
    private final OrderVersionService versions;
    private final AuditLogService audits;
    private final ShipmentJdOutboundPreparer preparer;
    private final ShipmentJdOutboundExecutor executor;
    private final ShipmentJdErpDeliveryNoPreflight erpDeliveryNoPreflight;
    private final ShipmentJdOutboundAuditService auditService;
    private final JdReceiverAddressNormalizer addressNormalizer;
    private final Set<String> authorizedOperators;
    private final String clientMode;
    private final Duration outboundIdempotencyLease;

    public ShipmentJdOutboundService(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            IdempotencyService idempotency,
            OrderEventService events,
            OrderVersionService versions,
            AuditLogService audits,
            ShipmentJdOutboundPreparer preparer,
            ShipmentJdOutboundExecutor executor,
            ShipmentJdErpDeliveryNoPreflight erpDeliveryNoPreflight,
            ShipmentJdOutboundAuditService auditService,
            JdReceiverAddressNormalizer addressNormalizer,
            @Value("${app.jd.outbound-authorized-operators:}") String authorizedOperators,
            @Value("${app.jd.client-mode:MOCK}") String clientMode,
            @Value("${app.jd.outbound-idempotency-lease:PT5M}") Duration outboundIdempotencyLease) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.idempotency = idempotency;
        this.events = events;
        this.versions = versions;
        this.audits = audits;
        this.preparer = preparer;
        this.executor = executor;
        this.erpDeliveryNoPreflight = erpDeliveryNoPreflight;
        this.auditService = auditService;
        this.addressNormalizer = addressNormalizer;
        this.authorizedOperators = java.util.Arrays.stream(authorizedOperators.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        this.clientMode = "REAL".equalsIgnoreCase(clientMode == null ? "" : clientMode.trim())
                ? "REAL" : "MOCK";
        this.outboundIdempotencyLease = outboundIdempotencyLease;
    }

    public IdempotentResult<Map<String, Object>> submit(
            long shipmentId, ShipmentJdOutboundCommand command, String idempotencyKey, CommandContext context) {
        requireAuthorized(shipmentId, context);
        JdShipmentSubmissionPlan prepared = preparer.planInNewTransaction(shipmentId);
        try {
            IdempotentResult<Map<String, Object>> result = idempotency.executeWithPreparedExternalWriteIntent(
                    ShipmentJdOutboundAuditService.SCOPE,
                    WriteCommands.requireIdempotencyKey(idempotencyKey),
                    Map.of(
                            "shipment_id", shipmentId,
                            "command", command),
                    201,
                    outboundIdempotencyLease,
                    () -> erpDeliveryNoPreflight.prepare(prepared),
                    (JdShipmentSubmissionPlan available) -> persistSubmitIntent(available, context),
                    (intent, activeClaim) ->
                            executor.executeSubmit(intent, idempotencyKey, context, activeClaim),
                    (ShipmentJdOutboundExecutor.SubmitIntent intent,
                     ShipmentJdOutboundExecutor.SubmitExternalResult external) ->
                            completeSubmit(intent, external, context));
            if (result.replayed()) {
                auditService.auditIdempotencyOutcome(
                        prepared, context, 201,
                        "JD_SHIPMENT_OUTBOUND_IDEMPOTENT_REPLAY", "幂等重放首次京东建单结果", clientMode);
            }
            return result;
        } catch (BusinessException exception) {
            if ("IDEMPOTENCY_CONFLICT".equals(exception.getBusinessCode())) {
                auditService.auditIdempotencyOutcome(
                        prepared, context, exception.getHttpStatus(),
                        exception.getBusinessCode(), exception.getMessage(), clientMode);
            }
            throw exception;
        }
    }

    /**
     * 京东出库请求预览：返回脱敏展示请求、逐字段来源与所有可诊断阻断项。
     * 该方法不依赖也不调用 {@link JdSalesOutboundWriter}，因此不可能触发 addSoOrder。
     */
    @Transactional
    public Map<String, Object> preview(long shipmentId, CommandContext context) {
        JdShipmentSubmissionPlan plan = preparer.plan(shipmentId);
        auditService.reconcilePreviewReviewCase(plan, context.operator());
        Map<String, Object> response = ShipmentJdOutboundPreview.from(plan).toResponse();
        audits.record(new AuditLogService.AuditCommand()
                .dataScope(DataScope.BUSINESS).orderId(plan.orderId())
                .requestId(context.requestId()).traceId(context.traceId()).operator(context.operator())
                .actorType(AuditActorType.HUMAN).service("fulfillment").operation(ShipmentJdOutboundAuditService.PREVIEW_SCOPE)
                .requestPayload(Map.of("shipment_id", String.valueOf(shipmentId)))
                .responsePayload(Map.of(
                        "shipment_id", String.valueOf(shipmentId),
                        "erp_delivery_no", plan.erpDeliveryNo(),
                        "submittable", plan.submittable(),
                        "blocker_codes", plan.blockers().stream().map(Blocker::code).distinct().toList()))
                .httpStatus(200)
                .businessCode(plan.submittable()
                        ? "JD_SHIPMENT_OUTBOUND_PREVIEW_READY"
                        : "JD_SHIPMENT_OUTBOUND_PREVIEW_BLOCKED"));
        return response;
    }

    /** 幂等保存操作员确认的结构化地址；不从 receiver_address_snapshot 自动拆分。 */
    @Transactional
    public IdempotentResult<Map<String, Object>> confirmReceiverAddress(
            long shipmentId,
            ShipmentJdReceiverAddressCommand command,
            String idempotencyKey,
            CommandContext context) {
        return idempotency.execute(
                ADDRESS_CONFIRM_SCOPE,
                WriteCommands.requireIdempotencyKey(idempotencyKey),
                Map.of("shipment_id", shipmentId, "command", command),
                200,
                () -> doConfirmReceiverAddress(shipmentId, command, context));
    }

    /**
     * 批量确认京东结构化收货地址；逐条复用单条确认的同一应用层用例与审计。
     * 整体幂等：同一 Idempotency-Key 重放返回首次批量结果。
     */
    @Transactional
    public IdempotentResult<Map<String, Object>> confirmReceiverAddresses(
            List<ShipmentJdReceiverAddressBatchItem> items,
            String idempotencyKey,
            CommandContext context) {
        if (items == null || items.isEmpty()) {
            throw BusinessException.unprocessable(
                    "JD_SHIPMENT_RECEIVER_ADDRESS_BATCH_EMPTY", "批量确认项不能为空");
        }
        return idempotency.execute(
                ADDRESS_CONFIRM_SCOPE,
                WriteCommands.requireIdempotencyKey(idempotencyKey),
                Map.of("batch_item_count", items.size(), "items", items),
                200,
                () -> {
                    List<Map<String, Object>> results = new ArrayList<>();
                    for (ShipmentJdReceiverAddressBatchItem item : items) {
                        results.add(doConfirmReceiverAddress(
                                item.shipmentId(), item.toCommand(), context));
                    }
                    Map<String, Object> response = new LinkedHashMap<>();
                    response.put("confirmed_count", results.size());
                    response.put("items", results);
                    return response;
                });
    }

    /**
     * 只读生成京东结构化收货地址候选（jd-real-sdk-switch 04）。
     * 优先取来源表格结构化单元格（彩食鲜的省/市/区/详细地址列）；缺少结构化列时，
     * 用确定性行政区划词典拆分自由文本快照作为候选。候选只用于人工确认，
     * 未确认前不参与建单；任一路径无法唯一解析时 candidate 为 null，落到人工。
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> receiverAddressCandidates(
            Long importBatchId, boolean onlyMissing) {
        StringBuilder sql = new StringBuilder("""
                SELECT DISTINCT ON (s.id)
                       s.id AS shipment_id,
                       s.lock_version,
                       s.receiver_address_snapshot,
                       s.jd_receiver_province,
                       s.jd_receiver_city,
                       s.jd_receiver_county,
                       s.jd_receiver_town,
                       s.jd_receiver_detail_address,
                       s.jd_receiver_confirmed_by,
                       s.jd_receiver_confirmed_at,
                       o.source_channel,
                       rir.raw_cells->>'省' AS source_province,
                       rir.raw_cells->>'市' AS source_city,
                       rir.raw_cells->>'区' AS source_county,
                       rir.raw_cells->>'详细地址' AS source_detail_address
                FROM app.shipments s
                JOIN app.orders o ON o.id = s.order_id
                JOIN app.order_lines ol ON ol.order_id = o.id
                JOIN app.raw_import_rows rir ON rir.order_line_id = ol.id
                JOIN app.fulfillment_providers fp
                  ON fp.id = s.fulfillment_provider_id AND fp.provider_type = 'JD_WAREHOUSE'
                WHERE 1=1
                """);
        List<Object> params = new ArrayList<>();
        if (importBatchId != null) {
            sql.append(" AND rir.import_batch_id = ?");
            params.add(importBatchId);
        }
        if (onlyMissing) {
            sql.append(" AND (s.jd_receiver_confirmed_at IS NULL OR s.jd_receiver_confirmed_at IS NOT NULL AND s.jd_receiver_province IS NULL)");
        }
        sql.append(" ORDER BY s.id, rir.id");
        return jdbc.query(sql.toString(), (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("shipment_id", String.valueOf(rs.getLong("shipment_id")));
            row.put("expected_version", rs.getLong("lock_version"));
            row.put("receiver_address_snapshot", rs.getString("receiver_address_snapshot"));
            row.put("source_channel", rs.getString("source_channel"));
            row.put("confirmed", rs.getTimestamp("jd_receiver_confirmed_at") != null);
            row.put("confirmed_by", rs.getString("jd_receiver_confirmed_by"));
            row.put("province", rs.getString("jd_receiver_province"));
            row.put("city", rs.getString("jd_receiver_city"));
            row.put("county", rs.getString("jd_receiver_county"));
            row.put("town", rs.getString("jd_receiver_town"));
            row.put("detail_address", rs.getString("jd_receiver_detail_address"));
            Map<String, Object> candidate = candidateFromSourceColumns(
                    rs.getString("source_province"),
                    rs.getString("source_city"),
                    rs.getString("source_county"),
                    rs.getString("source_detail_address"));
            if (candidate == null) {
                candidate = candidateFromFreeText(rs.getString("receiver_address_snapshot"));
            }
            row.put("candidate", candidate);
            row.put("candidate_incomplete", candidate == null);
            return row;
        }, params.toArray());
    }

    private Map<String, Object> candidateFromSourceColumns(
            String province, String city, String county, String detailAddress) {
        if (!ShipmentJdOutboundPreparer.hasText(province)
                || !ShipmentJdOutboundPreparer.hasText(city)
                || !ShipmentJdOutboundPreparer.hasText(county)
                || !ShipmentJdOutboundPreparer.hasText(detailAddress)) {
            return null;
        }
        return candidateMap(province, city, county, detailAddress);
    }

    private Map<String, Object> candidateFromFreeText(String freeText) {
        if (!ShipmentJdOutboundPreparer.hasText(freeText)) {
            return null;
        }
        return addressNormalizer.normalize(freeText)
                .map(normalized -> candidateMap(
                        normalized.province(),
                        normalized.city(),
                        normalized.county(),
                        normalized.detailAddress()))
                .orElse(null);
    }

    private Map<String, Object> candidateMap(
            String province, String city, String county, String detailAddress) {
        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put("province", province);
        candidate.put("city", city);
        candidate.put("county", county);
        candidate.put("town", null);
        candidate.put("detail_address", detailAddress);
        return candidate;
    }

    private Map<String, Object> doConfirmReceiverAddress(
            long shipmentId, ShipmentJdReceiverAddressCommand command, CommandContext context) {
        JdShipmentSubmissionPlan plan = preparer.plan(shipmentId);
        if (!ShipmentJdOutboundPreparer.JD_WAREHOUSE.equals(plan.providerType())) {
            throw BusinessException.unprocessable(
                    "JD_SHIPMENT_OUTBOUND_PROVIDER_UNSUPPORTED", "仅京东云仓发货批次可确认京东结构化收货地址");
        }
        if (plan.shipmentVersion() != command.expectedVersion()) {
            throw BusinessException.conflict("VERSION_CONFLICT", "发货批次已更新，请刷新预览后重试");
        }
        String province = ShipmentJdOutboundPreparer.requiredText(command.province());
        String city = ShipmentJdOutboundPreparer.requiredText(command.city());
        String county = ShipmentJdOutboundPreparer.requiredText(command.county());
        String town = ShipmentJdOutboundPreparer.optionalText(command.town());
        String detailAddress = ShipmentJdOutboundPreparer.requiredText(command.detailAddress());
        int updated = jdbc.update(
                """
                UPDATE app.shipments
                SET jd_receiver_province=?, jd_receiver_city=?, jd_receiver_county=?, jd_receiver_town=?,
                    jd_receiver_detail_address=?, jd_receiver_confirmed_by=?,
                    jd_receiver_confirmed_at=CURRENT_TIMESTAMP, lock_version=lock_version+1,
                    updated_at=CURRENT_TIMESTAMP
                WHERE id=? AND lock_version=?
                """,
                province, city, county, town, detailAddress, context.operator(), shipmentId,
                command.expectedVersion());
        if (updated != 1) {
            throw BusinessException.conflict("VERSION_CONFLICT", "发货批次已更新，请刷新预览后重试");
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("shipment_id", String.valueOf(shipmentId));
        response.put("confirmed", true);
        response.put("confirmed_by", context.operator());
        response.put("version", command.expectedVersion() + 1);
        audits.record(new AuditLogService.AuditCommand()
                .dataScope(DataScope.BUSINESS).orderId(plan.orderId())
                .requestId(context.requestId()).traceId(context.traceId()).operator(context.operator())
                .actorType(AuditActorType.HUMAN).service("fulfillment").operation(ADDRESS_CONFIRM_SCOPE)
                .requestPayload(Map.of(
                        "shipment_id", String.valueOf(shipmentId),
                        "confirmed_fields", Map.of(
                                "province_present", true,
                                "city_present", true,
                                "county_present", true,
                                "town_present", town != null,
                                "detail_address_present", true)))
                .responsePayload(response).httpStatus(200)
                .businessCode("JD_SHIPMENT_RECEIVER_ADDRESS_CONFIRMED"));
        return response;
    }

    /** 第一阶段：在独立事务中重建并锁定同一提交计划，先提交可恢复写意图。 */
    private ShipmentJdOutboundExecutor.SubmitIntent persistSubmitIntent(
            JdShipmentSubmissionPlan prepared, CommandContext context) {
        JdShipmentSubmissionPlan current = preparer.plan(prepared.shipmentId());
        JdShipmentSubmissionPlan.PriorSubmission previous = current.priorSubmission();
        boolean reconciliationOnly = previous != null && previous.requiresReconciliation();
        FrozenSubmission frozen;
        int attempt;
        if (reconciliationOnly) {
            // Point-in-time Provider/SKU/Shipment facts may have drifted since the uncertain write.
            // Reconciliation must consume only the row-locked durable intent and must never backfill
            // missing historical authority from today's configuration.
            frozen = loadFrozenSubmissionForUpdate(current.shipmentId());
            if (!clientMode.equals(frozen.clientMode())) {
                String code = "JD_SHIPMENT_OUTBOUND_CLIENT_MODE_CHANGED";
                String message = "未决京东写入只能在原客户端模式下对账，禁止跨 MOCK/REAL 模式重试";
                auditService.auditRejectedSubmit(
                        current.shipmentId(), current.orderId(), context, 409, code, message, List.of(code));
                throw BusinessException.conflict(code, message);
            }
            requireFrozenReconciliationFacts(current, frozen, context);
            if (ShipmentJdOutboundPreparer.SYNC_STATUS_SUBMITTED.equals(frozen.syncStatus())) {
                throw BusinessException.conflict(
                        "JD_SHIPMENT_OUTBOUND_ALREADY_SUBMITTED", "该发货批次已提交京东出库单，禁止重复提交");
            }
            attempt = frozen.retryCount() + 1;
            int updated = jdbc.update(
                    """
                    UPDATE app.shipment_jd_outbounds
                    SET sync_status='SUBMITTING', failure_phase=NULL, retry_count=?,
                        last_error_code=NULL, last_error_message=NULL, updated_at=CURRENT_TIMESTAMP
                    WHERE shipment_id=? AND sync_status IN ('SUBMITTING', 'SYNC_FAILED')
                    """,
                    attempt,
                    current.shipmentId());
            if (updated != 1) {
                throw BusinessException.conflict(
                        "JD_SHIPMENT_OUTBOUND_STATE_CHANGED",
                        "京东未决记录已被其他请求更新，请刷新后查看最新状态");
            }
        } else {
            if (current.shipmentVersion() != prepared.shipmentVersion()
                    || !Objects.equals(current.requestHash(), prepared.requestHash())) {
                throw BusinessException.conflict(
                        "JD_SHIPMENT_OUTBOUND_PREVIEW_CHANGED",
                        "发货批次在提交意图落盘前已变化，请刷新预览后重试");
            }
            rejectBlockedPlan(current, context);
            if (previous != null && retryFactsChanged(previous, current)) {
                throw BusinessException.conflict(
                        "JD_SHIPMENT_OUTBOUND_REQUEST_CHANGED",
                        "同一发货批次的出库请求已发生变化（数量/商品/收货信息），禁止在失败记录上以不同请求重试");
            }
            if (!JdErpDeliveryNoAllocator.belongsToOwnedNamespace(current.erpDeliveryNo())) {
                throw BusinessException.conflict(
                        "JD_ERP_DELIVERY_NO_NAMESPACE_REQUIRED",
                        "京东外部单号不在 ZIMU-SO 独占命名空间，禁止创建出库单");
            }
            attempt = (previous == null ? 0 : previous.retryCount()) + 1;
            List<Map<String, Object>> frozenCargos = submittedCargoSnapshot(cargosOf(current.request()));
            jdbc.update(
                    """
                    INSERT INTO app.shipment_jd_outbounds
                        (shipment_id, erp_delivery_no, sync_status, failure_phase,
                         retry_count, last_error_code, last_error_message, request_hash,
                         business_facts_hash, client_mode, submitted_pin, submitted_owner_no,
                         submitted_warehouse_no, submitted_cargo_snapshot)
                    VALUES (?, ?, 'SUBMITTING', NULL, ?, NULL, NULL, ?, ?, ?, ?, ?, ?, ?::jsonb)
                    ON CONFLICT (shipment_id) DO UPDATE SET
                        erp_delivery_no = EXCLUDED.erp_delivery_no,
                        sync_status = 'SUBMITTING', failure_phase = NULL,
                        retry_count = EXCLUDED.retry_count,
                        last_error_code = NULL, last_error_message = NULL,
                        request_hash = EXCLUDED.request_hash,
                        business_facts_hash = EXCLUDED.business_facts_hash,
                        client_mode = EXCLUDED.client_mode,
                        submitted_pin = COALESCE(shipment_jd_outbounds.submitted_pin, EXCLUDED.submitted_pin),
                        submitted_owner_no = COALESCE(
                            shipment_jd_outbounds.submitted_owner_no, EXCLUDED.submitted_owner_no),
                        submitted_warehouse_no = COALESCE(
                            shipment_jd_outbounds.submitted_warehouse_no, EXCLUDED.submitted_warehouse_no),
                        submitted_cargo_snapshot = COALESCE(
                            shipment_jd_outbounds.submitted_cargo_snapshot,
                            EXCLUDED.submitted_cargo_snapshot),
                        updated_at = CURRENT_TIMESTAMP
                    """,
                    current.shipmentId(), current.erpDeliveryNo(), attempt,
                    current.requestHash(), current.businessFactsHash(), clientMode,
                    current.pin(), current.ownerNo(),
                    ShipmentJdOutboundPreparer.text(current.request().get("warehouseNo")),
                    json(frozenCargos));
            frozen = loadFrozenSubmissionForUpdate(current.shipmentId());
            if (!frozen.facts().valid()) {
                throw new IllegalStateException("new JD outbound intent did not persist complete frozen facts");
            }
        }
        auditService.recordSubmitIntent(current, context, attempt);
        return new ShipmentJdOutboundExecutor.SubmitIntent(
                current,
                attempt,
                frozen.clientMode(),
                frozen.facts(),
                frozen.requestHash(),
                frozen.businessFactsHash(),
                reconciliationOnly);
    }

    private FrozenSubmission loadFrozenSubmissionForUpdate(long shipmentId) {
        FrozenSubmission frozen = jdbc.query(
                """
                SELECT erp_delivery_no, jd_delivery_no, sync_status, retry_count, client_mode,
                       request_hash, business_facts_hash, submitted_pin, submitted_owner_no,
                       submitted_warehouse_no, submitted_cargo_snapshot::text cargo_snapshot
                FROM app.shipment_jd_outbounds
                WHERE shipment_id=?
                FOR UPDATE
                """,
                rs -> {
                    if (!rs.next()) {
                        return null;
                    }
                    Object cargos;
                    try {
                        String json = rs.getString("cargo_snapshot");
                        cargos = json == null
                                ? List.of()
                                : objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
                    } catch (Exception exception) {
                        cargos = List.of();
                    }
                    return new FrozenSubmission(
                            JdOutboundReadbackVerifier.frozen(
                                    rs.getString("erp_delivery_no"),
                                    rs.getString("jd_delivery_no"),
                                    rs.getString("submitted_pin"),
                                    rs.getString("submitted_owner_no"),
                                    rs.getString("submitted_warehouse_no"),
                                    cargos),
                            rs.getString("request_hash"),
                            rs.getString("business_facts_hash"),
                            rs.getString("client_mode"),
                            rs.getString("sync_status"),
                            rs.getString("jd_delivery_no"),
                            rs.getInt("retry_count"));
                },
                shipmentId);
        if (frozen == null) {
            throw BusinessException.conflict(
                    "JD_SHIPMENT_OUTBOUND_INTENT_MISSING", "京东出库提交意图不存在，请刷新后重试");
        }
        return frozen;
    }

    private void requireFrozenReconciliationFacts(
            JdShipmentSubmissionPlan current,
            FrozenSubmission frozen,
            CommandContext context) {
        if (frozen.facts().valid()) {
            return;
        }
        String code = "JD_SHIPMENT_OUTBOUND_FROZEN_FACTS_MISSING";
        String message = "历史未决京东写入缺少冻结的 pin、owner、仓库或货品快照，禁止使用当前配置猜测对账";
        auditService.auditRejectedSubmit(
                current.shipmentId(), current.orderId(), context, 409, code, message, List.of(code));
        throw BusinessException.conflict(code, message);
    }

    /** 第三阶段：重新锁定本地事实，将外部结果、业务状态、事件、版本与响应原子归档。 */
    private ExternalCompletion<Map<String, Object>> completeSubmit(
            ShipmentJdOutboundExecutor.SubmitIntent intent,
            ShipmentJdOutboundExecutor.SubmitExternalResult external,
            CommandContext context) {
        JdShipmentSubmissionPlan current = preparer.plan(intent.plan().shipmentId());
        FrozenSubmission durable = loadFrozenSubmissionForUpdate(current.shipmentId());
        if (ShipmentJdOutboundPreparer.SYNC_STATUS_SUBMITTED.equals(durable.syncStatus())) {
            if (!submittedMatchesIntent(durable, intent)) {
                return ExternalCompletion.failed(BusinessException.conflict(
                        "JD_SHIPMENT_OUTBOUND_SUBMITTED_FACTS_MISMATCH",
                        "京东出库记录已由不同冻结事实确认成功，禁止覆盖或重新归属"));
            }
            return ExternalCompletion.succeeded(submittedResponse(
                    current,
                    durable.facts().erpDeliveryNo(),
                    durable.jdDeliveryNo(),
                    durable.retryCount(),
                    durable.facts()));
        }
        if (!sameFrozenIntent(durable, intent)) {
            return ExternalCompletion.failed(BusinessException.conflict(
                    "JD_SHIPMENT_OUTBOUND_STATE_CHANGED",
                    "京东出库提交意图已被其他请求更新，禁止归档迟到结果"));
        }
        boolean localEligibilityChanged = !intent.requiresReconciliation()
                && (current.shipmentVersion() != intent.plan().shipmentVersion()
                        || !Objects.equals(current.requestHash(), intent.plan().requestHash())
                        || !current.submittable());
        boolean externalReportedSuccess = external.accepted()
                && external.jdResult() != null
                && external.jdResult().success();
        if (localEligibilityChanged && externalReportedSuccess) {
            persistSubmitFailure(current, intent, context, "RECONCILIATION_REQUIRED",
                    "外部调用期间本地建单资格或请求事实已变化，必须按原 erpDeliveryNo 对账", "SUBMIT", null);
            return ExternalCompletion.failed(BusinessException.conflict(
                    "RECONCILIATION_REQUIRED", "京东外部调用后本地建单资格变化，必须先对账，禁止盲目重试"));
        }
        if (!external.accepted()) {
            persistSubmitFailure(
                    current,
                    intent,
                    context,
                    external.businessCode(),
                    external.message(),
                    external.failurePhase(),
                    external.jdResult() == null
                            ? null
                            : ShipmentJdOutboundPreparer.text(external.jdResult().requestId()));
            return ExternalCompletion.failed(submitFailure(external.businessCode()));
        }
        JdResult result = external.jdResult();
        if (result == null || !result.success()) {
            String code = result == null || ShipmentJdOutboundPreparer.text(result.businessCode()) == null
                    ? "UNKNOWN" : ShipmentJdOutboundPreparer.text(result.businessCode());
            persistSubmitFailure(
                    current, intent, context, code,
                    result == null ? "京东出库单提交失败（无响应）" : ShipmentJdOutboundPreparer.text(result.message()),
                    FAILURE_PHASE_SUBMIT,
                    result == null ? null : ShipmentJdOutboundPreparer.text(result.requestId()));
            return ExternalCompletion.failed(submitFailure(code));
        }
        String jdDeliveryNo = ShipmentJdOutboundExecutor.extractDeliveryNo(result);
        String responseErpDeliveryNo = ShipmentJdOutboundExecutor.extractErpDeliveryNo(result);
        if (jdDeliveryNo == null
                || responseErpDeliveryNo == null
                || !Objects.equals(intent.frozenFacts().erpDeliveryNo(), responseErpDeliveryNo)) {
            persistSubmitFailure(
                    current,
                    intent,
                    context,
                    "RECONCILIATION_REQUIRED",
                    "京东成功响应缺少出库引用或商户出库号不匹配，必须按原 erpDeliveryNo 对账",
                    FAILURE_PHASE_SUBMIT,
                    ShipmentJdOutboundPreparer.text(result.requestId()));
            return ExternalCompletion.failed(BusinessException.conflict(
                    "RECONCILIATION_REQUIRED",
                    "京东成功响应无法确认 deliveryNo 与 erpDeliveryNo 映射，禁止标记已提交"));
        }
        JdOutboundReadbackVerifier.Expected frozenFacts = intent.frozenFacts();
        List<Map<String, Object>> cargos = cargoSnapshot(frozenFacts);
        long planQuantity = planQuantity(frozenFacts);
        int effectiveRetryCount = Math.max(durable.retryCount(), intent.retryCount());

        jdbc.update(
                """
                INSERT INTO app.shipment_jd_outbounds
                    (shipment_id, erp_delivery_no, jd_delivery_no, sync_status, failure_phase,
                     retry_count, last_error_code, last_error_message, request_hash,
                     business_facts_hash, submitted_at, client_mode,
                     submitted_cargo_snapshot, submitted_warehouse_no, submitted_owner_no, submitted_pin)
                VALUES (?, ?, ?, 'SUBMITTED', NULL, ?, NULL, NULL, ?, ?, CURRENT_TIMESTAMP, ?, ?::jsonb, ?, ?, ?)
                ON CONFLICT (shipment_id) DO UPDATE SET
                    erp_delivery_no = EXCLUDED.erp_delivery_no,
                    jd_delivery_no = EXCLUDED.jd_delivery_no,
                    sync_status = 'SUBMITTED',
                    failure_phase = NULL,
                    retry_count = GREATEST(shipment_jd_outbounds.retry_count, EXCLUDED.retry_count),
                    last_error_code = NULL,
                    last_error_message = NULL,
                    request_hash = EXCLUDED.request_hash,
                    business_facts_hash = EXCLUDED.business_facts_hash,
                    client_mode = EXCLUDED.client_mode,
                    submitted_cargo_snapshot = EXCLUDED.submitted_cargo_snapshot,
                    submitted_warehouse_no = EXCLUDED.submitted_warehouse_no,
                    submitted_owner_no = EXCLUDED.submitted_owner_no,
                    submitted_pin = EXCLUDED.submitted_pin,
                    submitted_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                """,
                current.shipmentId(), frozenFacts.erpDeliveryNo(), jdDeliveryNo, effectiveRetryCount,
                intent.frozenRequestHash(), intent.frozenBusinessFactsHash(), intent.clientMode(),
                json(cargos),
                frozenFacts.warehouseNo(),
                frozenFacts.ownerNo(),
                frozenFacts.pin());

        for (JdShipmentSubmissionPlan.OrderLineState line : current.orderLines()) {
            if (ShipmentJdOutboundPreparer.READY_TO_EXPORT.equals(line.processingStage())) {
                jdbc.update(
                        "UPDATE app.order_lines SET processing_stage=?, updated_at=CURRENT_TIMESTAMP WHERE id=?",
                        ShipmentJdOutboundPreparer.WAITING_PROVIDER, line.orderLineId());
            }
        }
        jdbc.update(
                "UPDATE app.orders SET order_status=?, updated_at=CURRENT_TIMESTAMP WHERE id=?",
                ShipmentJdOutboundPreparer.FULFILLING, current.orderId());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("erp_delivery_no", frozenFacts.erpDeliveryNo());
        if (jdDeliveryNo != null) {
            payload.put("jd_delivery_no", jdDeliveryNo);
        }
        payload.put("shipment_id", String.valueOf(current.shipmentId()));
        payload.put("outbound_order_no", current.outboundOrderNo());
        payload.put("plan_quantity", planQuantity);
        payload.put("goods_count", cargos.size());
        events.append(
                current.orderId(), "JD_OUTBOUND_SUBMITTED", null, null, current.shipmentId(),
                null, DataScope.BUSINESS, payload, context.operator());
        versions.append(current.orderId(), null, "京东云仓建出库单", context.operator(), snapshot(current.orderId()));

        Map<String, Object> response = submittedResponse(
                current,
                frozenFacts.erpDeliveryNo(),
                jdDeliveryNo,
                effectiveRetryCount,
                frozenFacts);
        audits.record(new AuditLogService.AuditCommand()
                .dataScope(DataScope.BUSINESS).orderId(current.orderId())
                .requestId(context.requestId()).traceId(context.traceId()).operator(context.operator())
                .actorType(ShipmentJdOutboundAuditService.actorType(context))
                .service("fulfillment").operation(ShipmentJdOutboundAuditService.SCOPE)
                .requestPayload(Map.of(
                        "shipment_id", String.valueOf(current.shipmentId()),
                        "erp_delivery_no", frozenFacts.erpDeliveryNo()))
                .responsePayload(response).httpStatus(201).businessCode("JD_SHIPMENT_OUTBOUND_SUBMITTED"));
        return ExternalCompletion.succeeded(response);
    }

    /** 外部失败结果与安全审计在 completion 事务中归档；从不伪造 Shipment/Tracking/完成阶段。 */
    private void persistSubmitFailure(
            JdShipmentSubmissionPlan current,
            ShipmentJdOutboundExecutor.SubmitIntent intent,
            CommandContext context,
            String businessCode,
            String message,
            String failurePhase,
            String requestId) {
        String safeCode = ShipmentJdOutboundPreparer.text(businessCode) == null
                ? "UNKNOWN" : ShipmentJdOutboundPreparer.text(businessCode);
        String safeMessage = ShipmentJdOutboundPreparer.text(message) == null
                ? "京东出库单提交失败" : ShipmentJdOutboundPreparer.text(message);
        boolean writeModeDisabled = WRITE_MODE_DISABLED.equals(businessCode);
        int updated = jdbc.update(
                """
                UPDATE app.shipment_jd_outbounds
                SET sync_status='SYNC_FAILED', failure_phase=?, retry_count=?,
                    last_error_code=?, last_error_message=?, updated_at=CURRENT_TIMESTAMP
                WHERE shipment_id=? AND sync_status<>'SUBMITTED'
                """,
                failurePhase, intent.retryCount(), safeCode, safeMessage, current.shipmentId());
        if (updated != 1) {
            return;
        }
        jdbc.update(
                """
                INSERT INTO app.operational_alerts
                    (alert_no, alert_type, severity, order_id, shipment_id, message, detail)
                VALUES (?, 'JD_SHIPMENT_OUTBOUND_SUBMIT_FAILED', 'YELLOW', ?, ?, ?, ?::jsonb)
                ON CONFLICT DO NOTHING
                """,
                "ALERT-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12),
                current.orderId(), current.shipmentId(),
                writeModeDisabled ? "京东写模式未启用，无法建出库单" : "京东建出库单失败",
                json(Map.of("business_code", safeCode, "request_id", requestId == null ? "" : requestId)));
        events.append(
                current.orderId(), "JD_OUTBOUND_FAILED", null, null, current.shipmentId(), null,
                DataScope.BUSINESS,
                Map.of(
                        "shipment_id", String.valueOf(current.shipmentId()),
                        "erp_delivery_no", intent.frozenFacts().erpDeliveryNo(),
                        "failure_phase", failurePhase,
                        "business_code", safeCode,
                        "retryable", !"RECONCILIATION_REQUIRED".equals(safeCode)),
                context.operator());
        versions.append(
                current.orderId(), null, "京东云仓建出库单失败", context.operator(), snapshot(current.orderId()));
        audits.record(new AuditLogService.AuditCommand()
                .dataScope(DataScope.BUSINESS).orderId(current.orderId())
                .requestId(context.requestId()).traceId(context.traceId()).operator(context.operator())
                .actorType(ShipmentJdOutboundAuditService.actorType(context))
                .service("fulfillment").operation(ShipmentJdOutboundAuditService.SCOPE)
                .requestPayload(failureRequestPayload(current, intent))
                .responsePayload(Map.of(
                        "business_code", safeCode,
                        "request_id", requestId == null ? "" : requestId,
                        "retryable", !"RECONCILIATION_REQUIRED".equals(safeCode)))
                .httpStatus(writeModeDisabled ? 409 : 502)
                .businessCode(writeModeDisabled
                        ? "JD_SHIPMENT_OUTBOUND_WRITE_MODE_DISABLED"
                        : "JD_SHIPMENT_OUTBOUND_REJECTED"));
    }

    private boolean sameFrozenIntent(
            FrozenSubmission durable,
            ShipmentJdOutboundExecutor.SubmitIntent intent) {
        return durable.facts().sameFrozenFacts(intent.frozenFacts())
                && Objects.equals(durable.clientMode(), intent.clientMode())
                && Objects.equals(durable.requestHash(), intent.frozenRequestHash())
                && Objects.equals(durable.businessFactsHash(), intent.frozenBusinessFactsHash());
    }

    private boolean submittedMatchesIntent(
            FrozenSubmission durable,
            ShipmentJdOutboundExecutor.SubmitIntent intent) {
        return ShipmentJdOutboundPreparer.hasText(durable.jdDeliveryNo())
                && sameFrozenIntent(durable, intent);
    }

    private List<Map<String, Object>> cargoSnapshot(JdOutboundReadbackVerifier.Expected facts) {
        return facts.cargos().stream().map(cargo -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("orderLine", cargo.orderLine());
            item.put("goodsNo", cargo.goodsNo());
            item.put("planQuantity", cargo.planQuantity());
            return item;
        }).toList();
    }

    static long planQuantity(JdOutboundReadbackVerifier.Expected facts) {
        return facts.cargos().stream()
                .mapToLong(JdOutboundReadbackVerifier.Cargo::planQuantity)
                .sum();
    }

    private Map<String, Object> submittedResponse(
            JdShipmentSubmissionPlan current,
            String erpDeliveryNo,
            String jdDeliveryNo,
            int retryCount,
            JdOutboundReadbackVerifier.Expected facts) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("shipment_id", String.valueOf(current.shipmentId()));
        response.put("erp_delivery_no", erpDeliveryNo);
        if (jdDeliveryNo != null) {
            response.put("jd_delivery_no", jdDeliveryNo);
        }
        response.put("outbound_order_no", current.outboundOrderNo());
        response.put("sync_status", ShipmentJdOutboundPreparer.SYNC_STATUS_SUBMITTED);
        response.put("retry_count", retryCount);
        response.put("plan_quantity", planQuantity(facts));
        response.put("goods_count", facts.cargos().size());
        return response;
    }

    private Map<String, Object> failureRequestPayload(
            JdShipmentSubmissionPlan current,
            ShipmentJdOutboundExecutor.SubmitIntent intent) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("shipment_id", String.valueOf(current.shipmentId()));
        payload.put("erp_delivery_no", intent.frozenFacts().erpDeliveryNo());
        if (intent.frozenRequestHash() != null) {
            payload.put("request_hash", intent.frozenRequestHash());
        }
        return payload;
    }

    private RuntimeException submitFailure(String businessCode) {
        if (WRITE_MODE_DISABLED.equals(businessCode)) {
            return BusinessException.conflict(
                    "JD_SHIPMENT_OUTBOUND_WRITE_MODE_DISABLED",
                    "京东写模式未启用（app.jd.write-mode=OFF），出库单未创建，请先开启写模式后重试");
        }
        if ("RECONCILIATION_REQUIRED".equals(businessCode)) {
            return BusinessException.conflict(
                    "RECONCILIATION_REQUIRED",
                    "上次京东写入结果仍未确认，必须继续按原 erpDeliveryNo 对账，禁止再次创建");
        }
        return new BusinessException(
                502, "JD_SHIPMENT_OUTBOUND_REJECTED",
                "京东建出库单失败（" + businessCode + "），已记录失败阶段并告警，请检查后重试");
    }

    private void requireAuthorized(long shipmentId, CommandContext context) {
        if (context.authenticatedOperator() != null
                && context.authenticatedOperator().equals(context.operator())
                && authorizedOperators.contains(context.authenticatedOperator())) {
            return;
        }
        String code = "JD_SHIPMENT_OUTBOUND_OPERATOR_UNAUTHORIZED";
        String message = "当前操作人未获得京东出库建单授权";
        auditService.auditRejectedSubmit(shipmentId, null, context, 403, code, message, List.of());
        throw new BusinessException(403, code, message);
    }

    private void rejectBlockedPlan(
            JdShipmentSubmissionPlan plan, CommandContext context) {
        if (plan.blockers().isEmpty()) {
            return;
        }
        Blocker first = plan.blockers().getFirst();
        auditService.auditRejectedSubmit(
                plan.shipmentId(), plan.orderId(), context,
                first.httpStatus(), first.code(), first.message(),
                plan.blockers().stream().map(Blocker::code).distinct().toList());
        throw new BusinessException(
                first.httpStatus(), first.code(), first.message(), List.of(),
                Map.of("blockers", plan.blockers().stream()
                        .map(ShipmentJdOutboundPreparer::blockerMap).toList()));
    }

    /** 回填只需要建单时的货品标识、行号、整数件数与独立 warehouseNo；收件人与凭据不落库。 */
    private List<Map<String, Object>> submittedCargoSnapshot(List<Map<String, Object>> cargos) {
        return cargos.stream().map(cargo -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("orderLine", cargo.get("orderLine"));
            item.put("goodsNo", cargo.get("goodsNo"));
            item.put("planQuantity", cargo.get("planQuantity"));
            return item;
        }).toList();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> cargosOf(Map<String, Object> request) {
        return (List<Map<String, Object>>) request.get("cargoInfos");
    }

    private boolean retryFactsChanged(
            JdShipmentSubmissionPlan.PriorSubmission previous,
            JdShipmentSubmissionPlan current) {
        if (previous.businessFactsHash() != null) {
            return !Objects.equals(previous.businessFactsHash(), current.businessFactsHash());
        }
        if (previous.requestHash() == null) {
            return false;
        }
        return !Objects.equals(previous.requestHash(), current.requestHash());
    }

    private Map<String, Object> snapshot(long orderId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("order", jdbc.queryForMap("SELECT * FROM app.orders WHERE id=?", orderId));
        result.put("lines", jdbc.queryForList("SELECT * FROM app.order_lines WHERE order_id=? ORDER BY line_no", orderId));
        result.put("fulfillments", jdbc.queryForList(
                "SELECT f.* FROM app.fulfillments f JOIN app.order_lines ol ON ol.id=f.order_line_id WHERE ol.order_id=?",
                orderId));
        result.put("shipments", jdbc.queryForList(
                "SELECT * FROM app.shipments WHERE order_id=? ORDER BY shipment_sequence", orderId));
        result.put("shipment_jd_outbounds", jdbc.queryForList(
                """
                SELECT j.*
                FROM app.shipment_jd_outbounds j
                JOIN app.shipments s ON s.id=j.shipment_id
                WHERE s.order_id=?
                ORDER BY s.shipment_sequence
                """,
                orderId));
        return result;
    }

    private record FrozenSubmission(
            JdOutboundReadbackVerifier.Expected facts,
            String requestHash,
            String businessFactsHash,
            String clientMode,
            String syncStatus,
            String jdDeliveryNo,
            int retryCount) {
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
