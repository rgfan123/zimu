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
import cn.zimu.fulfillment.connector.jd.JDWarehouseService;
import cn.zimu.fulfillment.connector.jd.write.JdWriteOpsService;
import cn.zimu.fulfillment.fulfillment.ShipmentJdOutboundPreviewSnapshot.Blocker;
import cn.zimu.fulfillment.fulfillment.ShipmentJdOutboundPreviewSnapshot.Validation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 京东云仓建出库单用例（Shipment 边界）：一个 Shipment 及其全部 ShipmentItems 聚合为唯一一张
 * 京东出库单请求（addSoOrder），同一发货批次内的多个 Fulfillment 共享一个京东出库引用。
 *
 * <p>商户侧出库引用（erpDeliveryNo = {@code shipments.outbound_order_no}，系统生成的稳定出库单号）、
 * 同步状态、失败阶段与重试信息由 Shipment 级京东出库集成记录（app.shipment_jd_outbounds，1:1）
 * 承载，不写入 Fulfillment，也不写入或扩展 OrderLine {@code processing_stage}（权威业务阶段保持原值集合）。
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

    private static final Logger log = LoggerFactory.getLogger(ShipmentJdOutboundService.class);

    static final String SCOPE = "shipment.jd_outbound.submit";
    static final String PREVIEW_SCOPE = "shipment.jd_outbound.preview";
    static final String ADDRESS_CONFIRM_SCOPE = "shipment.jd_receiver_address.confirm";
    static final String PREVIEW_BLOCKED_REASON = "JD_SHIPMENT_OUTBOUND_PREVIEW_BLOCKED";
    static final String FAILURE_PHASE_SUBMIT = "SUBMIT";
    private static final String RECONCILE_SCOPE = "shipment.jd_outbound.reconcile";

    private static final String READY_TO_EXPORT = "READY_TO_EXPORT";
    private static final String WAITING_PROVIDER = "WAITING_PROVIDER";
    private static final String FULFILLING = "FULFILLING";
    private static final String JD_WAREHOUSE = "JD_WAREHOUSE";
    private static final String SYNC_STATUS_SUBMITTING = "SUBMITTING";
    private static final String SYNC_STATUS_SUBMITTED = "SUBMITTED";
    private static final String SYNC_STATUS_SYNC_FAILED = "SYNC_FAILED";
    private static final String WRITE_MODE_DISABLED = "WRITE_MODE_DISABLED";
    private static final Set<String> UNCERTAIN_EXTERNAL_RESULTS = Set.of(
            "SDK_CALL_FAILED", "EMPTY_RESPONSE_CODE", "UNKNOWN", "RECONCILIATION_REQUIRED",
            // 外层成功但内层缺失时 normalize 可能仅保留这些成功码，仍属部分响应。
            "0", "200", "1000", "10000", "SUCCESS");
    private static final String SHIPMENT_STATUS_CREATED = "CREATED";
    private static final String PASS = "PASS";
    private static final String BLOCKED = "BLOCKED";
    private static final String SOURCE_PROVIDER_CONFIG = "fulfillment_providers.config.";
    private static final String SOURCE_CONVERSION =
            "shipment_items.instructed_quantity × provider_skus.external_codes.jd_pieces_per_unit";

    /**
     * fulfillment_providers.config JSONB 配置键；京东标识一律来自履约方配置，缺失时阻断建单
     * （spec: identifiers come from the selected FulfillmentProvider configuration, never hard-coded）。
     */
    static final String CONFIG_SOURCE_NO = "sourceNo";
    static final String CONFIG_WAREHOUSE_NO = "warehouseNo";
    static final String CONFIG_ERP_SHOP_NO = "erpShopNo";
    static final String CONFIG_SHOP_NO = "shopNo";
    static final String CONFIG_OWNER_NO = "ownerNo";
    static final String CONFIG_SALES_PLATFORM_SOURCE = "salesPlatformSource";
    static final String CONFIG_PIN = "pin";
    static final String CONFIG_CARRIER_NO = "carrierNo";
    static final String CONFIG_TOWN_REQUIRED = "townRequired";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final IdempotencyService idempotency;
    private final OrderEventService events;
    private final OrderVersionService versions;
    private final AuditLogService audits;
    private final JdWriteOpsService jdWrite;
    private final JDWarehouseService jdWarehouse;
    private final ShipmentJdStockCheckService stockChecks;
    private final Set<String> authorizedOperators;
    private final String clientMode;
    private final TransactionTemplate requiresNew;

    public ShipmentJdOutboundService(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            IdempotencyService idempotency,
            OrderEventService events,
            OrderVersionService versions,
            AuditLogService audits,
            JdWriteOpsService jdWrite,
            JDWarehouseService jdWarehouse,
            @Lazy ShipmentJdStockCheckService stockChecks,
            @Value("${app.jd.outbound-authorized-operators:}") String authorizedOperators,
            @Value("${app.jd.client-mode:MOCK}") String clientMode,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.idempotency = idempotency;
        this.events = events;
        this.versions = versions;
        this.audits = audits;
        this.jdWrite = jdWrite;
        this.jdWarehouse = jdWarehouse;
        this.stockChecks = stockChecks;
        this.authorizedOperators = java.util.Arrays.stream(authorizedOperators.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        this.clientMode = "REAL".equalsIgnoreCase(clientMode == null ? "" : clientMode.trim())
                ? "REAL" : "MOCK";
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public IdempotentResult<Map<String, Object>> submit(
            long shipmentId, ShipmentJdOutboundCommand command, String idempotencyKey, CommandContext context) {
        requireAuthorized(shipmentId, context);
        ShipmentJdOutboundPreviewSnapshot prepared = preparePreviewInNewTransaction(shipmentId);
        try {
            IdempotentResult<Map<String, Object>> result = idempotency.executeWithExternalWriteIntent(
                    SCOPE,
                    WriteCommands.requireIdempotencyKey(idempotencyKey),
                    Map.of(
                            "shipment_id", shipmentId,
                            "shipment_version", prepared.shipmentVersion(),
                            "request_hash", prepared.requestHash(),
                            "client_mode", clientMode,
                            "command", command),
                    201,
                    () -> persistSubmitIntent(prepared, context),
                    intent -> executeSubmit(intent, idempotencyKey, context),
                    (intent, external) -> completeSubmit(intent, external, context));
            if (result.replayed()) {
                auditIdempotencyOutcome(
                        prepared, context, 201,
                        "JD_SHIPMENT_OUTBOUND_IDEMPOTENT_REPLAY", "幂等重放首次京东建单结果");
            }
            return result;
        } catch (BusinessException exception) {
            if ("IDEMPOTENCY_CONFLICT".equals(exception.getBusinessCode())) {
                auditIdempotencyOutcome(
                        prepared, context, exception.getHttpStatus(),
                        exception.getBusinessCode(), exception.getMessage());
            }
            throw exception;
        }
    }

    /**
     * 京东出库请求预览：返回脱敏展示请求、逐字段来源与所有可诊断阻断项。
     * 该方法不依赖也不调用 {@link JdWriteOpsService}，因此不可能触发 addSoOrder。
     */
    @Transactional
    public Map<String, Object> preview(long shipmentId, CommandContext context) {
        ShipmentJdOutboundPreviewSnapshot preview = preparePreview(lockContext(shipmentId));
        reconcilePreviewReviewCase(preview, context.operator());
        Map<String, Object> response = previewResponse(preview);
        audits.record(new AuditLogService.AuditCommand()
                .dataScope(DataScope.BUSINESS).orderId(preview.orderId())
                .requestId(context.requestId()).traceId(context.traceId()).operator(context.operator())
                .actorType(AuditActorType.HUMAN).service("fulfillment").operation(PREVIEW_SCOPE)
                .requestPayload(Map.of("shipment_id", String.valueOf(shipmentId)))
                .responsePayload(Map.of(
                        "shipment_id", String.valueOf(shipmentId),
                        "erp_delivery_no", preview.erpDeliveryNo(),
                        "submittable", preview.submittable(),
                        "blocker_codes", preview.blockers().stream().map(Blocker::code).distinct().toList()))
                .httpStatus(200)
                .businessCode(preview.submittable()
                        ? "JD_SHIPMENT_OUTBOUND_PREVIEW_READY"
                        : "JD_SHIPMENT_OUTBOUND_PREVIEW_BLOCKED"));
        return response;
    }

    /**
     * 构建不带审计、不触发京东写操作的应用快照。后续库存判定只消费此 seam，
     * 不得复制本服务的数量换算或请求映射。查询期间锁定 Shipment 事实，避免请求组装内部漂移。
     */
    @Transactional
    public ShipmentJdOutboundPreviewSnapshot preparePreview(long shipmentId) {
        return preparePreview(lockContext(shipmentId));
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
     * 候选来自来源表格原始单元格（彩食鲜的省/市/区/详细地址列），只用于人工确认；
     * 未确认前不参与建单。非彩食鲜或缺少结构化列时 candidate 为 null。
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
            Map<String, Object> candidate = new LinkedHashMap<>();
            candidate.put("province", rs.getString("source_province"));
            candidate.put("city", rs.getString("source_city"));
            candidate.put("county", rs.getString("source_county"));
            candidate.put("town", null);
            candidate.put("detail_address", rs.getString("source_detail_address"));
            boolean complete = hasText(rs.getString("source_province"))
                    && hasText(rs.getString("source_city"))
                    && hasText(rs.getString("source_county"))
                    && hasText(rs.getString("source_detail_address"));
            row.put("candidate", complete ? candidate : null);
            row.put("candidate_incomplete", !complete);
            return row;
        }, params.toArray());
    }

    private Map<String, Object> doConfirmReceiverAddress(
            long shipmentId, ShipmentJdReceiverAddressCommand command, CommandContext context) {
        Context state = lockContext(shipmentId);
        if (!JD_WAREHOUSE.equals(state.providerType())) {
            throw BusinessException.unprocessable(
                    "JD_SHIPMENT_OUTBOUND_PROVIDER_UNSUPPORTED", "仅京东云仓发货批次可确认京东结构化收货地址");
        }
        if (state.shipmentVersion() != command.expectedVersion()) {
            throw BusinessException.conflict("VERSION_CONFLICT", "发货批次已更新，请刷新预览后重试");
        }
        String province = requiredText(command.province());
        String city = requiredText(command.city());
        String county = requiredText(command.county());
        String town = optionalText(command.town());
        String detailAddress = requiredText(command.detailAddress());
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
                .dataScope(DataScope.BUSINESS).orderId(state.orderId())
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

    /** 第一阶段：在独立事务中重建并锁定同一 typed preview，先提交可恢复写意图。 */
    private SubmitIntent persistSubmitIntent(
            ShipmentJdOutboundPreviewSnapshot prepared, CommandContext context) {
        Context state = lockContext(prepared.shipmentId());
        ShipmentJdOutboundPreviewSnapshot current = preparePreview(state);
        if (current.shipmentVersion() != prepared.shipmentVersion()
                || !Objects.equals(current.requestHash(), prepared.requestHash())) {
            throw BusinessException.conflict(
                    "JD_SHIPMENT_OUTBOUND_PREVIEW_CHANGED",
                    "发货批次在提交意图落盘前已变化，请刷新预览后重试");
        }
        rejectBlockedPreview(current, context);
        if (state.jdOutbound() != null
                && state.jdOutbound().requestHash() != null
                && !state.jdOutbound().requestHash().equals(current.requestHash())) {
            throw BusinessException.conflict(
                    "JD_SHIPMENT_OUTBOUND_REQUEST_CHANGED",
                    "同一发货批次的出库请求已发生变化（数量/商品/收货信息），禁止在失败记录上以不同请求重试");
        }
        JdOutbound previous = state.jdOutbound();
        if (previous != null
                && previous.requiresReconciliation()
                && !clientMode.equals(previous.clientMode())) {
            String code = "JD_SHIPMENT_OUTBOUND_CLIENT_MODE_CHANGED";
            String message = "未决京东写入只能在原客户端模式下对账，禁止跨 MOCK/REAL 模式重试";
            auditRejectedSubmit(
                    current.shipmentId(), current.orderId(), context, 409, code, message, List.of(code));
            throw BusinessException.conflict(code, message);
        }
        int attempt = (previous == null ? 0 : previous.retryCount()) + 1;
        jdbc.update(
                """
                INSERT INTO app.shipment_jd_outbounds
                    (shipment_id, erp_delivery_no, sync_status, failure_phase,
                     retry_count, last_error_code, last_error_message, request_hash, client_mode)
                VALUES (?, ?, 'SUBMITTING', NULL, ?, NULL, NULL, ?, ?)
                ON CONFLICT (shipment_id) DO UPDATE SET
                    erp_delivery_no = EXCLUDED.erp_delivery_no,
                    sync_status = 'SUBMITTING', failure_phase = NULL,
                    retry_count = EXCLUDED.retry_count,
                    last_error_code = NULL, last_error_message = NULL,
                    request_hash = EXCLUDED.request_hash,
                    client_mode = EXCLUDED.client_mode,
                    updated_at = CURRENT_TIMESTAMP
                """,
                current.shipmentId(), current.erpDeliveryNo(), attempt, current.requestHash(), clientMode);
        audits.record(new AuditLogService.AuditCommand()
                .dataScope(DataScope.BUSINESS).orderId(current.orderId())
                .requestId(context.requestId()).traceId(context.traceId()).operator(context.operator())
                .actorType(AuditActorType.HUMAN).service("fulfillment").operation(SCOPE + ".intent")
                .requestPayload(Map.of(
                        "shipment_id", String.valueOf(current.shipmentId()),
                        "erp_delivery_no", current.erpDeliveryNo(),
                        "request_hash", current.requestHash()))
                .responsePayload(Map.of("sync_status", SYNC_STATUS_SUBMITTING, "retry_count", attempt))
                .httpStatus(202).businessCode("JD_SHIPMENT_OUTBOUND_INTENT_RECORDED"));
        return new SubmitIntent(
                current,
                attempt,
                previous == null ? null : previous.syncStatus(),
                previous == null ? null : previous.lastErrorCode(),
                clientMode);
    }

    /** 第二阶段：实时 SKU/库存门禁与 addSoOrder 全部发生在业务事务之外。 */
    private SubmitExternalResult executeSubmit(
            SubmitIntent intent, String idempotencyKey, CommandContext context) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("JD outbound external phase must run outside a database transaction");
        }
        try {
            // A prior write with an indeterminate result is a monotonic recovery fact: reconcile it
            // before any fresh stock/preview validation can fail and overwrite the unresolved intent.
            // The original create may already have succeeded, so no other failure may make a second
            // addSoOrder attempt reachable.
            if (intent.requiresReconciliation()) {
                return reconcileUncertainSubmit(intent, context);
            }
            IdempotentResult<Map<String, Object>> checked = stockChecks.check(
                    intent.preview().shipmentId(),
                    "jd-submit-stock-" + sha256(idempotencyKey + ":" + intent.retryCount()
                            + ":" + intent.preview().requestHash()),
                    context);
            Map<String, Object> stock = checked.replayed()
                    ? objectMapper.convertValue(checked.replayedBody(), new TypeReference<>() {})
                    : checked.result();
            if (!"PASSED".equals(text(stock.get("stock_status")))) {
                return SubmitExternalResult.validationFailure(
                        "JD_STOCK_CHECK_BLOCKED", "京东实时库存判定未通过，出库单未创建");
            }
            if (!Objects.equals(intent.preview().requestHash(), text(stock.get("preview_hash")))) {
                return SubmitExternalResult.validationFailure(
                        "JD_STOCK_PREVIEW_CHANGED", "库存判定使用的预览已变化，出库单未创建");
            }
            ShipmentJdOutboundPreviewSnapshot immediate =
                    preparePreviewInNewTransaction(intent.preview().shipmentId());
            if (immediate.shipmentVersion() != intent.preview().shipmentVersion()
                    || !Objects.equals(immediate.requestHash(), intent.preview().requestHash())) {
                return SubmitExternalResult.validationFailure(
                        "JD_SHIPMENT_OUTBOUND_PREVIEW_CHANGED", "发货批次在库存复查后已变化，出库单未创建");
            }
            if (!immediate.submittable()) {
                Blocker first = immediate.blockers().getFirst();
                return SubmitExternalResult.validationFailure(first.code(), first.message());
            }
            JdResult result;
            try {
                result = jdWrite.orderSoCreate(intent.preview().request());
            } catch (RuntimeException exception) {
                log.warn("JD orderSoCreate adapter failed for shipment {}", intent.preview().shipmentId());
                result = new JdResult(false, "SDK_CALL_FAILED", "京东出库单提交调用失败", null, null);
            }
            return SubmitExternalResult.submitted(result);
        } catch (BusinessException exception) {
            return SubmitExternalResult.validationFailure(exception.getBusinessCode(), exception.getMessage());
        } catch (RuntimeException exception) {
            log.warn("JD outbound pre-submit validation failed for shipment {}", intent.preview().shipmentId());
            return SubmitExternalResult.validationFailure(
                    "PRE_SUBMIT_CHECK_FAILED", "京东提交前复查失败，出库单未创建");
        }
    }

    /**
     * 未决外部写只能沿对账路径收敛；查询、解析或审计任一异常都保持单调的
     * {@code RECONCILIATION_REQUIRED}，绝不降级为可重新建单的普通预检失败。
     */
    private SubmitExternalResult reconcileUncertainSubmit(SubmitIntent intent, CommandContext context) {
        try {
            Map<String, Object> reconciliationRequest = Map.of(
                    "erpDeliveryNo", intent.preview().erpDeliveryNo(),
                    "deliveryItemFlag", 1,
                    "deliveryPackageFlag", 0,
                    "deliveryStatusFlag", 1);
            JdResult reconciliation;
            try {
                reconciliation = jdWarehouse.queryOutboundOrder(reconciliationRequest);
            } catch (RuntimeException exception) {
                reconciliation = null;
            }
            recordReconciliationQuery(intent, context, reconciliationRequest, reconciliation);
            if (reconciliation != null
                    && reconciliation.success()
                    && extractDeliveryNo(reconciliation) != null
                    && Objects.equals(
                            intent.preview().erpDeliveryNo(), extractErpDeliveryNo(reconciliation))) {
                return SubmitExternalResult.submitted(reconciliation);
            }
            return SubmitExternalResult.validationFailure(
                    "RECONCILIATION_REQUIRED",
                    "上次京东写结果不确定，按原 erpDeliveryNo 查询未确认结果，禁止再次创建");
        } catch (RuntimeException exception) {
            log.warn("JD outbound reconciliation failed for shipment {}", intent.preview().shipmentId());
            return SubmitExternalResult.validationFailure(
                    "RECONCILIATION_REQUIRED",
                    "上次京东写结果不确定，对账查询或审计未完成，禁止再次创建");
        }
    }

    /** 第三阶段：重新锁定本地事实，将外部结果、业务状态、事件、版本与响应原子归档。 */
    private ExternalCompletion<Map<String, Object>> completeSubmit(
            SubmitIntent intent, SubmitExternalResult external, CommandContext context) {
        Context state = lockContext(intent.preview().shipmentId());
        ShipmentJdOutboundPreviewSnapshot current = preparePreview(state);
        boolean localEligibilityChanged = current.shipmentVersion() != intent.preview().shipmentVersion()
                || !Objects.equals(current.requestHash(), intent.preview().requestHash())
                || !current.submittable();
        boolean externalReportedSuccess = external.accepted()
                && external.jdResult() != null
                && external.jdResult().success();
        if (localEligibilityChanged && externalReportedSuccess) {
            persistSubmitFailure(state, intent, context, "RECONCILIATION_REQUIRED",
                    "外部调用期间本地建单资格或请求事实已变化，必须按原 erpDeliveryNo 对账", "SUBMIT", null);
            return ExternalCompletion.failed(BusinessException.conflict(
                    "RECONCILIATION_REQUIRED", "京东外部调用后本地建单资格变化，必须先对账，禁止盲目重试"));
        }
        if (!external.accepted()) {
            persistSubmitFailure(
                    state, intent, context, external.businessCode(), external.message(), external.failurePhase(), null);
            return ExternalCompletion.failed(submitFailure(external.businessCode()));
        }
        JdResult result = external.jdResult();
        if (result == null || !result.success()) {
            String code = result == null || text(result.businessCode()) == null
                    ? "UNKNOWN" : text(result.businessCode());
            persistSubmitFailure(
                    state, intent, context, code,
                    result == null ? "京东出库单提交失败（无响应）" : text(result.message()),
                    FAILURE_PHASE_SUBMIT,
                    result == null ? null : text(result.requestId()));
            return ExternalCompletion.failed(submitFailure(code));
        }
        String jdDeliveryNo = extractDeliveryNo(result);
        String responseErpDeliveryNo = extractErpDeliveryNo(result);
        if (jdDeliveryNo == null
                || responseErpDeliveryNo == null
                || !Objects.equals(state.outboundOrderNo(), responseErpDeliveryNo)) {
            persistSubmitFailure(
                    state,
                    intent,
                    context,
                    "RECONCILIATION_REQUIRED",
                    "京东成功响应缺少出库引用或商户出库号不匹配，必须按原 erpDeliveryNo 对账",
                    FAILURE_PHASE_SUBMIT,
                    text(result.requestId()));
            return ExternalCompletion.failed(BusinessException.conflict(
                    "RECONCILIATION_REQUIRED",
                    "京东成功响应无法确认 deliveryNo 与 erpDeliveryNo 映射，禁止标记已提交"));
        }
        List<Map<String, Object>> cargos = cargosOf(intent.preview().request());
        String submittedOwnerNo = submittedOwnerNo(intent.preview().request());
        int planQuantity = cargos.stream()
                .mapToInt(cargo -> ((Number) cargo.get("planQuantity")).intValue())
                .sum();

        jdbc.update(
                """
                INSERT INTO app.shipment_jd_outbounds
                    (shipment_id, erp_delivery_no, jd_delivery_no, sync_status, failure_phase,
                     retry_count, last_error_code, last_error_message, request_hash, submitted_at, client_mode,
                     submitted_cargo_snapshot, submitted_warehouse_no, submitted_owner_no)
                VALUES (?, ?, ?, 'SUBMITTED', NULL, ?, NULL, NULL, ?, CURRENT_TIMESTAMP, ?, ?::jsonb, ?, ?)
                ON CONFLICT (shipment_id) DO UPDATE SET
                    erp_delivery_no = EXCLUDED.erp_delivery_no,
                    jd_delivery_no = EXCLUDED.jd_delivery_no,
                    sync_status = 'SUBMITTED',
                    failure_phase = NULL,
                    retry_count = EXCLUDED.retry_count,
                    last_error_code = NULL,
                    last_error_message = NULL,
                    request_hash = EXCLUDED.request_hash,
                    client_mode = EXCLUDED.client_mode,
                    submitted_cargo_snapshot = EXCLUDED.submitted_cargo_snapshot,
                    submitted_warehouse_no = EXCLUDED.submitted_warehouse_no,
                    submitted_owner_no = EXCLUDED.submitted_owner_no,
                    submitted_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                """,
                state.id(), state.outboundOrderNo(), jdDeliveryNo, intent.retryCount(),
                intent.preview().requestHash(), intent.clientMode(), json(submittedCargoSnapshot(cargos)),
                text(intent.preview().request().get("warehouseNo")), submittedOwnerNo);

        for (Item item : state.items()) {
            if (READY_TO_EXPORT.equals(item.processingStage())) {
                jdbc.update(
                        "UPDATE app.order_lines SET processing_stage=?, updated_at=CURRENT_TIMESTAMP WHERE id=?",
                        WAITING_PROVIDER, item.orderLineId());
            }
        }
        jdbc.update(
                "UPDATE app.orders SET order_status=?, updated_at=CURRENT_TIMESTAMP WHERE id=?",
                FULFILLING, state.orderId());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("erp_delivery_no", state.outboundOrderNo());
        if (jdDeliveryNo != null) {
            payload.put("jd_delivery_no", jdDeliveryNo);
        }
        payload.put("shipment_id", String.valueOf(state.id()));
        payload.put("outbound_order_no", state.outboundOrderNo());
        payload.put("plan_quantity", planQuantity);
        payload.put("goods_count", cargos.size());
        events.append(
                state.orderId(), "JD_OUTBOUND_SUBMITTED", null, null, state.id(),
                null, DataScope.BUSINESS, payload, context.operator());
        versions.append(state.orderId(), null, "京东云仓建出库单", context.operator(), snapshot(state.orderId()));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("shipment_id", String.valueOf(state.id()));
        response.put("erp_delivery_no", state.outboundOrderNo());
        if (jdDeliveryNo != null) {
            response.put("jd_delivery_no", jdDeliveryNo);
        }
        response.put("outbound_order_no", state.outboundOrderNo());
        response.put("sync_status", SYNC_STATUS_SUBMITTED);
        response.put("retry_count", intent.retryCount());
        response.put("plan_quantity", planQuantity);
        response.put("goods_count", cargos.size());
        audits.record(new AuditLogService.AuditCommand()
                .dataScope(DataScope.BUSINESS).orderId(state.orderId())
                .requestId(context.requestId()).traceId(context.traceId()).operator(context.operator())
                .actorType(AuditActorType.SYSTEM).service("fulfillment").operation(SCOPE)
                .requestPayload(Map.of(
                        "shipment_id", String.valueOf(state.id()),
                        "erp_delivery_no", state.outboundOrderNo()))
                .responsePayload(response).httpStatus(201).businessCode("JD_SHIPMENT_OUTBOUND_SUBMITTED"));
        return ExternalCompletion.succeeded(response);
    }

    /** 预览与提交共用的唯一请求构建器；预览收集所有 blocker，提交仅消费其通过结果。 */
    private ShipmentJdOutboundPreviewSnapshot preparePreview(Context state) {
        Map<String, Object> request = new LinkedHashMap<>();
        List<Validation> validations = new ArrayList<>();
        List<Blocker> blockers = new ArrayList<>();

        validateShipmentEligibility(state, validations, blockers);
        putRequiredConfig(request, "sourceNo", state, CONFIG_SOURCE_NO, "sourceNo", false, validations, blockers);
        request.put("erpDeliveryNo", state.outboundOrderNo());
        pass(validations, "erpDeliveryNo", "shipments.outbound_order_no");
        putRequiredConfig(request, "warehouseNo", state, CONFIG_WAREHOUSE_NO, "warehouseNo", false, validations, blockers);

        // 这三个值来自已审批的当前销售出库政策，不是由商品或地址猜测。
        request.put("orderType", "1");
        pass(validations, "orderType", "JD sales-outbound policy (B2C=1)");
        request.put("orderMark", "0".repeat(50));
        pass(validations, "orderMark", "non-COD outbound policy (50 zero bits)");
        putRequiredConfig(request, "pin", state, CONFIG_PIN, "pin", true, validations, blockers);

        Map<String, Object> channelInfo = new LinkedHashMap<>();
        putRequiredConfig(channelInfo, "erpShopNo", state, CONFIG_ERP_SHOP_NO,
                "channelInfo.erpShopNo", false, validations, blockers);
        if (hasText(state.sourceRef())) {
            channelInfo.put("salesPlatformDeliveryNo", state.sourceRef());
            pass(validations, "channelInfo.salesPlatformDeliveryNo", "orders.source_ref");
        } else {
            validations.add(new Validation(
                    "channelInfo.salesPlatformDeliveryNo", "OMITTED", "orders.source_ref", "来源未提供可选外部订单号"));
        }
        putRequiredConfig(channelInfo, "salesPlatformSource", state, CONFIG_SALES_PLATFORM_SOURCE,
                "channelInfo.salesPlatformSource", false, validations, blockers);
        request.put("channelInfo", channelInfo);

        Map<String, Object> customerInfo = new LinkedHashMap<>();
        putCustomerCode(customerInfo, state, validations, blockers);
        putRequiredConfig(customerInfo, "ownerNo", state, CONFIG_OWNER_NO,
                "customerInfo.ownerNo", false, validations, blockers);
        putRequiredConfig(customerInfo, "shopNo", state, CONFIG_SHOP_NO,
                "customerInfo.shopNo", false, validations, blockers);
        request.put("customerInfo", customerInfo);

        Boolean townRequired = requiredTownPolicy(state, validations, blockers);
        Map<String, Object> receiverInfo = receiverPreview(state, townRequired, validations, blockers);
        request.put("receiverInfo", receiverInfo);

        Map<String, Object> carrierInfo = new LinkedHashMap<>();
        putRequiredConfig(carrierInfo, "carrierNo", state, CONFIG_CARRIER_NO,
                "carrierInfo.carrierNo", false, validations, blockers);
        request.put("carrierInfo", carrierInfo);

        List<Map<String, Object>> cargos = new ArrayList<>();
        for (Item item : state.items()) {
            if ("SINGLE".equals(item.lineType())) {
                cargos.add(cargoPreview(
                        state, item.skuId(), String.valueOf(item.lineNo()), item.productName(),
                        item.unit(), item.instructedQuantity(), SOURCE_CONVERSION,
                        cargos.size(), validations, blockers));
            } else {
                for (Component component : state.componentsByOrderLine()
                        .getOrDefault(item.orderLineId(), List.of())) {
                    BigDecimal componentQuantity = item.instructedQuantity()
                            .multiply(component.quantityPerBundle());
                    cargos.add(cargoPreview(
                            state, component.skuId(),
                            item.lineNo() + "-" + component.componentNo(),
                            component.productName(),
                            component.unit(),
                            componentQuantity,
                            "shipment_items.instructed_quantity × order_line_components.quantity_per_bundle "
                                    + "× provider_skus.external_codes.jd_pieces_per_unit",
                            cargos.size(), validations, blockers));
                }
            }
        }
        if (cargos.isEmpty()) {
            block(
                    blockers, validations, 422, "JD_SHIPMENT_OUTBOUND_CARGO_EMPTY", "cargoInfos",
                    "shipment_items", "shipment_items",
                    "发货批次没有可建出库单的商品明细");
        }
        request.put("cargoInfos", cargos);
        List<ShipmentJdOutboundPreviewSnapshot.StockDemand> stockDemands = cargos.stream()
                .filter(cargo -> cargo.get("skuId") instanceof Number
                        && cargo.get("goodsNo") != null
                        && cargo.get("planQuantity") instanceof Number)
                .map(cargo -> new ShipmentJdOutboundPreviewSnapshot.StockDemand(
                        ((Number) cargo.get("skuId")).longValue(),
                        String.valueOf(cargo.get("goodsNo")),
                        ((Number) cargo.get("planQuantity")).intValue()))
                .toList();
        cargos.forEach(cargo -> cargo.remove("skuId"));
        return new ShipmentJdOutboundPreviewSnapshot(
                state.id(),
                state.shipmentVersion(),
                state.orderId(),
                state.providerId(),
                state.outboundOrderNo(),
                request,
                sha256(json(request)),
                stockDemands,
                validations,
                blockers,
                state.receiverAddress());
    }

    private void validateShipmentEligibility(
            Context state, List<Validation> validations, List<Blocker> blockers) {
        if (JD_WAREHOUSE.equals(state.providerType())) {
            pass(validations, "shipment.provider", "fulfillment_providers.provider_type");
        } else {
            block(
                    blockers, validations, 422, "JD_SHIPMENT_OUTBOUND_PROVIDER_UNSUPPORTED", "shipment.provider",
                    "fulfillment_providers.provider_type", "fulfillment provider master data",
                    "仅京东云仓（JD_WAREHOUSE）发货批次可提交京东出库单");
        }
        if (SHIPMENT_STATUS_CREATED.equals(state.shipmentStatus())) {
            pass(validations, "shipment.status", "shipments.shipment_status");
        } else {
            block(
                    blockers, validations, 409, "JD_SHIPMENT_OUTBOUND_SHIPMENT_STATUS_INVALID", "shipment.status",
                    "shipments.shipment_status", "shipment lifecycle",
                    "发货批次状态必须是 CREATED 才能提交京东出库单（当前 " + state.shipmentStatus() + "）");
        }
        for (Item item : state.items()) {
            String path = "shipment.items[" + item.lineNo() + "].processingStage";
            if (READY_TO_EXPORT.equals(item.processingStage()) || WAITING_PROVIDER.equals(item.processingStage())) {
                pass(validations, path, "order_lines.processing_stage");
            } else {
                block(
                        blockers, validations, 409, "JD_SHIPMENT_OUTBOUND_STAGE_INVALID", path,
                        "order_lines.processing_stage", "order-line workflow",
                        "订单行必须处于 READY_TO_EXPORT 或 WAITING_PROVIDER 阶段（当前 "
                                + item.processingStage() + "）");
            }
        }
        if (state.jdOutbound() != null && SYNC_STATUS_SUBMITTED.equals(state.jdOutbound().syncStatus())) {
            block(
                    blockers, validations, 409, "JD_SHIPMENT_OUTBOUND_ALREADY_SUBMITTED", "shipment.jdOutbound",
                    "shipment_jd_outbounds.sync_status", "existing JD outbound integration record",
                    "该发货批次已提交京东出库单，禁止重复提交");
        }
    }

    private Map<String, Object> receiverPreview(
            Context state, Boolean townRequired, List<Validation> validations, List<Blocker> blockers) {
        Map<String, Object> receiver = new LinkedHashMap<>();
        receiver.put("name", state.receiverName());
        pass(validations, "receiverInfo.name", "shipments.receiver_name_snapshot");
        receiver.put("mobile", state.receiverPhone());
        pass(validations, "receiverInfo.mobile", "shipments.receiver_phone_snapshot");

        boolean confirmed = state.jdReceiverConfirmed();
        putConfirmedAddress(receiver, "province", state.jdReceiverProvince(), true, confirmed,
                "jd_receiver_province", validations, blockers);
        putConfirmedAddress(receiver, "city", state.jdReceiverCity(), true, confirmed,
                "jd_receiver_city", validations, blockers);
        putConfirmedAddress(receiver, "county", state.jdReceiverCounty(), true, confirmed,
                "jd_receiver_county", validations, blockers);
        putConfirmedAddress(receiver, "town", state.jdReceiverTown(), Boolean.TRUE.equals(townRequired), confirmed,
                "jd_receiver_town", validations, blockers);
        putConfirmedAddress(receiver, "detailAddress", state.jdReceiverDetailAddress(), true, confirmed,
                "jd_receiver_detail_address", validations, blockers);
        return receiver;
    }

    private void putConfirmedAddress(
            Map<String, Object> target,
            String requestKey,
            String value,
            boolean required,
            boolean confirmed,
            String column,
            List<Validation> validations,
            List<Blocker> blockers) {
        String path = "receiverInfo." + requestKey;
        String source = "shipments." + column + " (operator confirmed)";
        if (confirmed && hasText(value)) {
            target.put(requestKey, value);
            pass(validations, path, source);
        } else if (required) {
            block(
                    blockers, validations, 422, "JD_SHIPMENT_OUTBOUND_RECEIVER_ADDRESS_NOT_CONFIRMED", path,
                    source, "shipment JD receiver address confirmation",
                    "京东结构化收货地址未经人工确认或缺少必填层级；系统不从自由文本猜测");
        } else {
            validations.add(new Validation(path, "OMITTED", source, "京东未要求时乡镇可留空"));
        }
    }

    private Map<String, Object> cargoPreview(
            Context state,
            Long skuId,
            String orderLine,
            String goodsName,
            String unit,
            BigDecimal quantity,
            String quantitySource,
            int cargoIndex,
            List<Validation> validations,
            List<Blocker> blockers) {
        String base = "cargoInfos[" + cargoIndex + "]";
        Map<String, Object> cargo = new LinkedHashMap<>();
        cargo.put("orderLine", orderLine);
        pass(validations, base + ".orderLine", "order_lines.line_no / order_line_components.component_no");
        cargo.put("goodsName", goodsName);
        pass(validations, base + ".goodsName", "order_lines/product component confirmed snapshot");
        cargo.put("unit", unit);
        pass(validations, base + ".unit", "order_lines/product component confirmed unit snapshot");
        cargo.put("goodsLevel", "100");
        pass(validations, base + ".goodsLevel", "JD salable-good policy (100)");

        JdGoods goods = skuId == null ? null : state.goodsBySku().get(skuId);
        if (goods == null) {
            block(
                    blockers, validations, 422, "JD_SHIPMENT_OUTBOUND_SKU_MAPPING_MISSING", base + ".goodsNo",
                    "provider_skus.provider_sku_code", "provider SKU mapping",
                    "SKU " + skuId + " 未配置有效京东商品编码，无法建出库单");
            return cargo;
        }
        cargo.put("goodsNo", goods.goodsNo());
        // Internal-only binding removed before the public/submission payload is frozen.
        cargo.put("skuId", skuId);
        pass(validations, base + ".goodsNo", "provider_skus.provider_sku_code");
        if (hasText(goods.merchantSkuCode())) {
            cargo.put("erpGoodsNo", goods.merchantSkuCode());
            pass(validations, base + ".erpGoodsNo", "provider_skus.merchant_sku_code");
        } else {
            validations.add(new Validation(
                    base + ".erpGoodsNo", "OMITTED", "provider_skus.merchant_sku_code", "可选商家 SKU 编码未配置"));
        }

        BigDecimal factor;
        if (!goods.externalCodes().containsKey(JdStockUnitConverter.FACTOR_CONFIG_KEY)) {
            if (JdStockUnitConverter.PIECES_UNIT.equals(unit)) {
                factor = BigDecimal.ONE;
            } else {
                block(
                        blockers, validations, 422, "JD_SHIPMENT_OUTBOUND_UNIT_CONVERSION_MISSING",
                        base + ".planQuantity", quantitySource, "provider SKU unit conversion",
                        "非‘件’单位必须配置显式京东件数换算；系统不默认为 1");
                return cargo;
            }
        } else {
            factor = JdStockUnitConverter.explicitFactorOrNull(goods.externalCodes());
            if (factor == null) {
                block(
                        blockers, validations, 422, "JD_SHIPMENT_OUTBOUND_UNIT_CONFIG_INVALID",
                        base + ".planQuantity", quantitySource, "provider SKU unit conversion",
                        "SKU " + skuId + " 的京东单位换算必须是正数");
                return cargo;
            }
            // jd-real-sdk-switch 03: 换算值必须为正整数件数；小数系数(如 0.5 件/盒)不用于建单
            if (factor.stripTrailingZeros().scale() > 0) {
                block(
                        blockers, validations, 422, "JD_SHIPMENT_OUTBOUND_UNIT_CONFIG_INVALID",
                        base + ".planQuantity", quantitySource, "provider SKU unit conversion",
                        "SKU " + skuId + " 的京东件数换算必须是正整数件数（当前 " + factor.toPlainString() + "）");
                return cargo;
            }
        }

        BigDecimal exact = JdStockUnitConverter.exactPiecesOrNull(quantity, factor);
        if (exact == null) {
            block(
                    blockers, validations, 422, "JD_SHIPMENT_OUTBOUND_NON_INTEGRAL_QUANTITY",
                    base + ".planQuantity", quantitySource, "shipment quantity or provider SKU unit conversion",
                    "数量与换算系数无法得到精确正整数件数（" + quantity + " × " + factor
                            + "）；系统不四舍五入也不向上取整");
            return cargo;
        }
        try {
            cargo.put("planQuantity", exact.intValueExact());
            pass(validations, base + ".planQuantity", quantitySource);
        } catch (ArithmeticException exception) {
            block(
                    blockers, validations, 422, "JD_SHIPMENT_OUTBOUND_QUANTITY_OUT_OF_RANGE",
                    base + ".planQuantity", quantitySource, "shipment quantity",
                    "换算后件数超出京东 planQuantity 整数范围");
        }
        return cargo;
    }

    /**
     * 京东客户编码按订单客户取值（jd-real-sdk-switch 02）：来自客户档案而非履约方配置，
     * 缺失时给出指向该客户的明确阻塞，不回落到任何默认值。
     */
    private void putCustomerCode(
            Map<String, Object> target,
            Context state,
            List<Validation> validations,
            List<Blocker> blockers) {
        String path = "customerInfo.customerCode";
        String source = "customers.profile.jd_customer_code";
        if (hasText(state.jdCustomerCode())) {
            target.put("customerCode", state.jdCustomerCode());
            pass(validations, path, source + " (customer archive)");
        } else {
            block(
                    blockers, validations, 422, "JD_SHIPMENT_OUTBOUND_CUSTOMER_CODE_MISSING", path, source,
                    "customer master data",
                    "订单客户 " + state.orderCustomerCode() + "（" + state.orderCustomerName()
                            + "）缺少京东客户编码，请先在客户档案维护");
        }
    }

    private void putRequiredConfig(
            Map<String, Object> target,
            String requestKey,
            Context state,
            String configKey,
            String path,
            boolean secret,
            List<Validation> validations,
            List<Blocker> blockers) {
        String value = configValue(state.config(), configKey, null);
        String source = SOURCE_PROVIDER_CONFIG + configKey;
        if (hasText(value)) {
            target.put(requestKey, value);
            pass(validations, path, secret ? source + " (secret value hidden)" : source);
        } else {
            block(
                    blockers, validations, 422, "JD_SHIPMENT_OUTBOUND_CONFIG_MISSING", path, source,
                    "fulfillment provider configuration",
                    "履约方配置缺少京东标识 " + configKey + "，请先补齐后再建单");
        }
    }

    /**
     * 乡镇是否必填必须是履约方明确配置的布尔政策；缺失或形状错误时 fail closed，
     * 不能用地址文本、行政区名称或默认值猜测京东当前要求。
     */
    private Boolean requiredTownPolicy(
            Context state, List<Validation> validations, List<Blocker> blockers) {
        String path = "receiverInfo.townPolicy";
        String source = SOURCE_PROVIDER_CONFIG + CONFIG_TOWN_REQUIRED;
        Object raw = state.config().get(CONFIG_TOWN_REQUIRED);
        if (raw instanceof Boolean required) {
            pass(validations, path, source);
            return required;
        }
        String code = raw == null
                ? "JD_SHIPMENT_OUTBOUND_CONFIG_MISSING"
                : "JD_SHIPMENT_OUTBOUND_CONFIG_INVALID";
        block(
                blockers, validations, 422, code, path, source,
                "fulfillment provider address policy",
                raw == null
                        ? "履约方配置缺少显式乡镇必填策略 townRequired；系统不猜测京东要求"
                        : "履约方乡镇必填策略 townRequired 必须是 JSON 布尔值");
        return null;
    }

    private Map<String, Object> previewResponse(ShipmentJdOutboundPreviewSnapshot preview) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("shipment_id", String.valueOf(preview.shipmentId()));
        response.put("shipment_version", preview.shipmentVersion());
        response.put("erp_delivery_no", preview.erpDeliveryNo());
        response.put("request_hash", preview.requestHash());
        response.put("submittable", preview.submittable());
        Map<String, Object> displayRequest = new LinkedHashMap<>(preview.request());
        if (displayRequest.containsKey("pin")) {
            displayRequest.put("pin", "***");
        }
        response.put("request", displayRequest);
        response.put("validations", preview.validations().stream().map(this::validationMap).toList());
        response.put("blockers", preview.blockers().stream().map(this::blockerMap).toList());
        boolean needsAddressCorrection = preview.blockers().stream()
                .anyMatch(blocker -> "JD_SHIPMENT_OUTBOUND_RECEIVER_ADDRESS_NOT_CONFIRMED".equals(blocker.code()));
        if (needsAddressCorrection && hasText(preview.manualCorrectionSource())) {
            response.put("manual_correction_source", preview.manualCorrectionSource());
        }
        return response;
    }

    /** HTTP 预览把瞬时 blocker 同步为一个可处理且可复用的 Shipment 级 ReviewCase。 */
    private void reconcilePreviewReviewCase(
            ShipmentJdOutboundPreviewSnapshot preview, String operator) {
        // SKU 映射门禁由 Ticket 03 的 JD_SKU_MAPPING_BLOCKED case 独占维护；
        // 此 case 只承载地址、履约配置、数量换算等预览阻断，避免一个原因两张票。
        List<Blocker> reviewBlockers = preview.blockers().stream()
                .filter(blocker -> !"JD_SHIPMENT_OUTBOUND_SKU_MAPPING_MISSING".equals(blocker.code()))
                .toList();
        if (reviewBlockers.isEmpty()) {
            jdbc.update(
                    """
                    UPDATE app.review_cases
                    SET status='RESOLVED',
                        resolution=?::jsonb,
                        resolution_version=resolution_version+1,
                        resolved_by=?, resolved_at=CURRENT_TIMESTAMP, updated_at=CURRENT_TIMESTAMP
                    WHERE shipment_id=? AND reason_code=? AND status='OPEN'
                    """,
                    json(Map.of(
                            "reason", "JD outbound preview address/configuration/conversion blockers cleared",
                            "request_hash", preview.requestHash())),
                    operator, preview.shipmentId(), PREVIEW_BLOCKED_REASON);
            return;
        }

        String detail = json(Map.of(
                "message", "京东出库请求预览存在阻断项，请修正后重新预览",
                "request_hash", preview.requestHash(),
                "blockers", reviewBlockers.stream().map(this::blockerMap).toList()));
        List<Long> existing = jdbc.queryForList(
                """
                SELECT id FROM app.review_cases
                WHERE shipment_id=? AND reason_code=? AND status='OPEN'
                FOR UPDATE
                """,
                Long.class, preview.shipmentId(), PREVIEW_BLOCKED_REASON);
        if (existing.isEmpty()) {
            jdbc.update(
                    """
                    INSERT INTO app.review_cases
                        (case_no, case_type, status, responsible_team, reason_code,
                         order_id, shipment_id, detail)
                    VALUES (?, 'JD_OUTBOUND_PREVIEW', 'OPEN', 'FULFILLMENT_OPS', ?, ?, ?, ?::jsonb)
                    ON CONFLICT DO NOTHING
                    """,
                    "RC-JD-PREVIEW-" + java.util.UUID.randomUUID().toString().replace("-", "").toUpperCase(),
                    PREVIEW_BLOCKED_REASON, preview.orderId(), preview.shipmentId(), detail);
        } else {
            jdbc.update(
                    "UPDATE app.review_cases SET detail=?::jsonb, updated_at=CURRENT_TIMESTAMP WHERE id=?",
                    detail, existing.getFirst());
        }
    }

    private void rejectBlockedPreview(
            ShipmentJdOutboundPreviewSnapshot preview, CommandContext context) {
        if (preview.blockers().isEmpty()) {
            return;
        }
        Blocker first = preview.blockers().getFirst();
        auditRejectedSubmit(
                preview.shipmentId(), preview.orderId(), context,
                first.httpStatus(), first.code(), first.message(),
                preview.blockers().stream().map(Blocker::code).distinct().toList());
        throw new BusinessException(
                first.httpStatus(), first.code(), first.message(), List.of(),
                Map.of("blockers", preview.blockers().stream().map(this::blockerMap).toList()));
    }

    private void pass(List<Validation> validations, String path, String source) {
        validations.add(new Validation(path, PASS, source, null));
    }

    private void block(
            List<Blocker> blockers,
            List<Validation> validations,
            int httpStatus,
            String code,
            String path,
            String source,
            String correctionTarget,
            String message) {
        validations.add(new Validation(path, BLOCKED, source, message));
        blockers.add(new Blocker(httpStatus, code, path, source, correctionTarget, message));
    }

    private Map<String, Object> validationMap(Validation validation) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("path", validation.path());
        result.put("status", validation.status());
        result.put("source", validation.source());
        if (validation.message() != null) {
            result.put("message", validation.message());
        }
        return result;
    }

    private Map<String, Object> blockerMap(Blocker blocker) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("code", blocker.code());
        result.put("path", blocker.path());
        result.put("source", blocker.source());
        result.put("correction_target", blocker.correctionTarget());
        result.put("message", blocker.message());
        return result;
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

    /**
     * 以 NO KEY UPDATE 串行同一 Shipment 的预览/提交，但保持与失败留痕 REQUIRES_NEW 所需的
     * 外键 KEY SHARE 兼容，避免同一请求在两个事务间自锁。再锁全部 ShipmentItem 的 Fulfillment/OrderLine。
     */
    private Context lockContext(long shipmentId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("JD outbound preview lock requires an active database transaction");
        }
        Context value = jdbc.query(
                """
                SELECT s.id, s.shipment_no, s.outbound_order_no, s.shipment_status, s.shipment_sequence,
                       s.order_id, s.fulfillment_provider_id, s.lock_version,
                       o.source_ref,
                       s.receiver_name_snapshot AS receiver_name,
                       s.receiver_phone_snapshot AS receiver_phone,
                       s.receiver_address_snapshot AS receiver_address,
                       s.jd_receiver_province, s.jd_receiver_city, s.jd_receiver_county,
                       s.jd_receiver_town, s.jd_receiver_detail_address,
                       (s.jd_receiver_confirmed_at IS NOT NULL) AS jd_receiver_confirmed,
                       fp.provider_type, fp.config::text AS config,
                       c.customer_code AS order_customer_code,
                       c.customer_name AS order_customer_name,
                       c.profile->>'jd_customer_code' AS jd_customer_code,
                       j.sync_status jd_sync_status, j.request_hash jd_request_hash,
                       j.retry_count jd_retry_count, j.last_error_code jd_last_error_code,
                       j.client_mode jd_client_mode
                FROM app.shipments s
                JOIN app.orders o ON o.id=s.order_id AND o.data_scope='BUSINESS'
                JOIN app.fulfillment_providers fp ON fp.id=s.fulfillment_provider_id
                LEFT JOIN app.customers c ON c.id=o.customer_id
                LEFT JOIN app.shipment_jd_outbounds j ON j.shipment_id=s.id
                WHERE s.id=? FOR NO KEY UPDATE OF s, o
                """,
                rs -> {
                    if (!rs.next()) {
                        return null;
                    }
                    List<Item> items = jdbc.query(
                            """
                            SELECT si.fulfillment_id, si.instructed_quantity,
                                   ol.id order_line_id, ol.line_type, ol.sku_id, ol.line_no,
                                   ol.product_name_snapshot, ol.processing_stage,
                                   -- 京东货品行的 unit 描述的是 planQuantity 的计量单位，
                                   -- 而 planQuantity 已换算为京东件数，故以内部 SKU 单位为准；
                                   -- 来源表格缺单位列时 ol.unit_snapshot 是占位符，不可直接外发。
                                   COALESCE(sk.unit, ol.unit_snapshot) unit_snapshot
                            FROM app.shipment_items si
                            JOIN app.fulfillments f ON f.id=si.fulfillment_id
                            JOIN app.order_lines ol ON ol.id=f.order_line_id
                            LEFT JOIN app.skus sk ON sk.id=ol.sku_id
                            WHERE si.shipment_id=? ORDER BY si.id
                            FOR UPDATE OF f, ol
                            """,
                            (resultSet, rowNum) -> new Item(
                                    resultSet.getLong("fulfillment_id"),
                                    resultSet.getBigDecimal("instructed_quantity"),
                                    resultSet.getLong("order_line_id"),
                                    resultSet.getString("line_type"),
                                    resultSet.getObject("sku_id", Long.class),
                                    resultSet.getInt("line_no"),
                                    resultSet.getString("product_name_snapshot"),
                                    resultSet.getString("unit_snapshot"),
                                    resultSet.getString("processing_stage")),
                            shipmentId);
                    Map<Long, List<Component>> componentsByOrderLine = loadComponents(shipmentId);
                    Map<Long, JdGoods> goodsBySku = loadGoods(
                            rs.getLong("fulfillment_provider_id"), shipmentId);
                    return new Context(
                            rs.getLong("id"), rs.getString("shipment_no"), rs.getString("outbound_order_no"),
                            rs.getString("shipment_status"), rs.getInt("shipment_sequence"),
                            rs.getLong("order_id"), rs.getLong("fulfillment_provider_id"),
                            rs.getLong("lock_version"),
                            rs.getString("source_ref"), rs.getString("receiver_name"),
                            rs.getString("receiver_phone"), rs.getString("receiver_address"),
                            rs.getString("jd_receiver_province"), rs.getString("jd_receiver_city"),
                            rs.getString("jd_receiver_county"), rs.getString("jd_receiver_town"),
                            rs.getString("jd_receiver_detail_address"), rs.getBoolean("jd_receiver_confirmed"),
                            rs.getString("provider_type"), parseJsonMap(rs.getString("config")),
                            rs.getString("order_customer_code"), rs.getString("order_customer_name"),
                            rs.getString("jd_customer_code"),
                            rs.getString("jd_sync_status") == null
                                    ? null
                                    : new JdOutbound(
                                            rs.getString("jd_sync_status"),
                                            rs.getString("jd_request_hash"),
                                            rs.getInt("jd_retry_count"),
                                            rs.getString("jd_last_error_code"),
                                            rs.getString("jd_client_mode")),
                            List.copyOf(items), componentsByOrderLine, goodsBySku);
                },
                shipmentId);
        if (value == null) {
            throw BusinessException.notFound("BUSINESS 发货批次不存在");
        }
        return value;
    }

    /** 一次读取并锁定本 Shipment 的组件行，后续构建期间不再回表查询。 */
    private Map<Long, List<Component>> loadComponents(long shipmentId) {
        List<Component> components = jdbc.query(
                """
                SELECT c.order_line_id, c.component_no, c.sku_id, c.quantity_per_bundle,
                       c.product_name_snapshot,
                       COALESCE(sk.unit, c.unit_snapshot) unit_snapshot
                FROM app.order_line_components c
                JOIN app.order_lines ol ON ol.id=c.order_line_id
                JOIN app.fulfillments f ON f.order_line_id=ol.id
                JOIN app.shipment_items si ON si.fulfillment_id=f.id
                LEFT JOIN app.skus sk ON sk.id=c.sku_id
                WHERE si.shipment_id=?
                ORDER BY c.order_line_id, c.component_no
                FOR SHARE OF c
                """,
                (rs, rowNum) -> new Component(
                        rs.getLong("order_line_id"),
                        rs.getInt("component_no"),
                        rs.getLong("sku_id"),
                        rs.getBigDecimal("quantity_per_bundle"),
                        rs.getString("product_name_snapshot"),
                        rs.getString("unit_snapshot")),
                shipmentId);
        Map<Long, List<Component>> grouped = new HashMap<>();
        for (Component component : components) {
            grouped.computeIfAbsent(component.orderLineId(), ignored -> new ArrayList<>()).add(component);
        }
        grouped.replaceAll((ignored, rows) -> List.copyOf(rows));
        return Map.copyOf(grouped);
    }

    /** 将本 Shipment 引用的履约方映射一次性加载到快照；未启用行不能成为可提交映射。 */
    private Map<Long, JdGoods> loadGoods(long providerId, long shipmentId) {
        List<JdGoods> rows = jdbc.query(
                """
                SELECT ps.sku_id, ps.provider_sku_code, ps.merchant_sku_code,
                       ps.external_codes::text AS external_codes, ps.active
                FROM app.provider_skus ps
                WHERE ps.fulfillment_provider_id=?
                  AND ps.sku_id IN (
                      SELECT ol.sku_id
                      FROM app.shipment_items si
                      JOIN app.fulfillments f ON f.id=si.fulfillment_id
                      JOIN app.order_lines ol ON ol.id=f.order_line_id
                      WHERE si.shipment_id=? AND ol.sku_id IS NOT NULL
                      UNION
                      SELECT c.sku_id
                      FROM app.shipment_items si
                      JOIN app.fulfillments f ON f.id=si.fulfillment_id
                      JOIN app.order_line_components c ON c.order_line_id=f.order_line_id
                      WHERE si.shipment_id=?
                  )
                ORDER BY ps.sku_id
                FOR SHARE OF ps
                """,
                (rs, rowNum) -> new JdGoods(
                        rs.getLong("sku_id"),
                        rs.getString("provider_sku_code"),
                        rs.getString("merchant_sku_code"),
                        parseJsonMap(rs.getString("external_codes")),
                        rs.getBoolean("active")),
                providerId, shipmentId, shipmentId);
        Map<Long, JdGoods> activeBySku = new HashMap<>();
        for (JdGoods row : rows) {
            if (row.active()) {
                activeBySku.put(row.skuId(), row);
            }
        }
        return Map.copyOf(activeBySku);
    }

    /** 外部失败结果与安全审计在 completion 事务中归档；从不伪造 Shipment/Tracking/完成阶段。 */
    private void persistSubmitFailure(
            Context state,
            SubmitIntent intent,
            CommandContext context,
            String businessCode,
            String message,
            String failurePhase,
            String requestId) {
        String safeCode = text(businessCode) == null ? "UNKNOWN" : text(businessCode);
        String safeMessage = text(message) == null ? "京东出库单提交失败" : text(message);
        boolean writeModeDisabled = WRITE_MODE_DISABLED.equals(businessCode);
        jdbc.update(
                """
                UPDATE app.shipment_jd_outbounds
                SET sync_status='SYNC_FAILED', failure_phase=?, retry_count=?,
                    last_error_code=?, last_error_message=?, request_hash=?, updated_at=CURRENT_TIMESTAMP
                WHERE shipment_id=?
                """,
                failurePhase, intent.retryCount(), safeCode, safeMessage,
                intent.preview().requestHash(), state.id());
        jdbc.update(
                """
                INSERT INTO app.operational_alerts
                    (alert_no, alert_type, severity, order_id, shipment_id, message, detail)
                VALUES (?, 'JD_SHIPMENT_OUTBOUND_SUBMIT_FAILED', 'YELLOW', ?, ?, ?, ?::jsonb)
                ON CONFLICT DO NOTHING
                """,
                "ALERT-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12),
                state.orderId(), state.id(),
                writeModeDisabled ? "京东写模式未启用，无法建出库单" : "京东建出库单失败",
                json(Map.of("business_code", safeCode, "request_id", requestId == null ? "" : requestId)));
        events.append(
                state.orderId(), "JD_OUTBOUND_FAILED", null, null, state.id(), null,
                DataScope.BUSINESS,
                Map.of(
                        "shipment_id", String.valueOf(state.id()),
                        "erp_delivery_no", state.outboundOrderNo(),
                        "failure_phase", failurePhase,
                        "business_code", safeCode,
                        "retryable", !"RECONCILIATION_REQUIRED".equals(safeCode)),
                context.operator());
        versions.append(
                state.orderId(), null, "京东云仓建出库单失败", context.operator(), snapshot(state.orderId()));
        audits.record(new AuditLogService.AuditCommand()
                .dataScope(DataScope.BUSINESS).orderId(state.orderId())
                .requestId(context.requestId()).traceId(context.traceId()).operator(context.operator())
                .actorType(AuditActorType.SYSTEM).service("fulfillment").operation(SCOPE)
                .requestPayload(Map.of(
                        "shipment_id", String.valueOf(state.id()),
                        "erp_delivery_no", state.outboundOrderNo(),
                        "request_hash", intent.preview().requestHash()))
                .responsePayload(Map.of(
                        "business_code", safeCode,
                        "request_id", requestId == null ? "" : requestId,
                        "retryable", !"RECONCILIATION_REQUIRED".equals(safeCode)))
                .httpStatus(writeModeDisabled ? 409 : 502)
                .businessCode(writeModeDisabled
                        ? "JD_SHIPMENT_OUTBOUND_WRITE_MODE_DISABLED"
                        : "JD_SHIPMENT_OUTBOUND_REJECTED"));
    }

    /** 对账查询是独立可追溯事实；只留稳定参考和结果摘要，不留京东返回原文。 */
    private void recordReconciliationQuery(
            SubmitIntent intent,
            CommandContext context,
            Map<String, Object> request,
            JdResult result) {
        requiresNew.executeWithoutResult(status -> {
            jdbc.update(
                    "UPDATE app.shipment_jd_outbounds SET last_query_at=CURRENT_TIMESTAMP, "
                            + "updated_at=CURRENT_TIMESTAMP WHERE shipment_id=?",
                    intent.preview().shipmentId());
            String businessCode = result == null || text(result.businessCode()) == null
                    ? "QUERY_FAILED" : text(result.businessCode());
            audits.record(new AuditLogService.AuditCommand()
                    .dataScope(DataScope.BUSINESS).orderId(intent.preview().orderId())
                    .requestId(context.requestId()).traceId(context.traceId()).operator(context.operator())
                    .actorType(AuditActorType.SYSTEM).service("fulfillment").operation(RECONCILE_SCOPE)
                    .requestPayload(Map.of(
                            "shipment_id", String.valueOf(intent.preview().shipmentId()),
                            "erp_delivery_no", request.get("erpDeliveryNo")))
                    .responsePayload(Map.of(
                            "success", result != null && result.success(),
                            "business_code", businessCode,
                            "delivery_no_present", result != null && extractDeliveryNo(result) != null,
                            "erp_delivery_no_matches", result != null && Objects.equals(
                                    intent.preview().erpDeliveryNo(), extractErpDeliveryNo(result))))
                    .httpStatus(result != null && result.success() ? 200 : 502)
                    .businessCode(businessCode));
        });
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

    private ShipmentJdOutboundPreviewSnapshot preparePreviewInNewTransaction(long shipmentId) {
        ShipmentJdOutboundPreviewSnapshot snapshot = requiresNew.execute(
                status -> preparePreview(lockContext(shipmentId)));
        if (snapshot == null) {
            throw new IllegalStateException("JD outbound preview transaction returned no snapshot");
        }
        return snapshot;
    }

    private void requireAuthorized(long shipmentId, CommandContext context) {
        if (context.authenticatedOperator() != null
                && context.authenticatedOperator().equals(context.operator())
                && authorizedOperators.contains(context.authenticatedOperator())) {
            return;
        }
        String code = "JD_SHIPMENT_OUTBOUND_OPERATOR_UNAUTHORIZED";
        String message = "当前操作人未获得京东出库建单授权";
        auditRejectedSubmit(shipmentId, null, context, 403, code, message, List.of());
        throw new BusinessException(403, code, message);
    }

    /** 拒绝发生在业务意图事务回滚之前，以独立事务保留可审计事实。 */
    private void auditRejectedSubmit(
            long shipmentId,
            Long orderId,
            CommandContext context,
            int httpStatus,
            String businessCode,
            String message,
            List<String> blockerCodes) {
        requiresNew.executeWithoutResult(status -> {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("business_code", businessCode);
            response.put("message", message);
            response.put("blocker_codes", blockerCodes);
            AuditLogService.AuditCommand audit = new AuditLogService.AuditCommand()
                    .dataScope(DataScope.BUSINESS)
                    .requestId(context.requestId()).traceId(context.traceId()).operator(auditOperator(context))
                    .actorType(AuditActorType.HUMAN).service("fulfillment").operation(SCOPE)
                    .requestPayload(Map.of("shipment_id", String.valueOf(shipmentId)))
                    .responsePayload(response).httpStatus(httpStatus).businessCode(businessCode);
            if (orderId != null) {
                audit.orderId(orderId);
            }
            audits.record(audit);
        });
    }

    /** 幂等注册表在业务 callback 前返回时，也保留本次操作人的重放/冲突事实。 */
    private void auditIdempotencyOutcome(
            ShipmentJdOutboundPreviewSnapshot preview,
            CommandContext context,
            int httpStatus,
            String businessCode,
            String message) {
        requiresNew.executeWithoutResult(status -> audits.record(new AuditLogService.AuditCommand()
                .dataScope(DataScope.BUSINESS).orderId(preview.orderId())
                .requestId(context.requestId()).traceId(context.traceId()).operator(auditOperator(context))
                .actorType(AuditActorType.HUMAN).service("fulfillment").operation(SCOPE)
                .requestPayload(Map.of(
                        "shipment_id", String.valueOf(preview.shipmentId()),
                        "erp_delivery_no", preview.erpDeliveryNo(),
                        "request_hash", preview.requestHash(),
                        "client_mode", clientMode))
                .responsePayload(Map.of("business_code", businessCode, "message", message))
                .httpStatus(httpStatus)
                .businessCode(businessCode)));
    }

    private static String auditOperator(CommandContext context) {
        return context.authenticatedOperator() == null
                ? "unauthenticated"
                : context.authenticatedOperator();
    }

    /** 兼容 Mock（data.response.deliveryNo）与 REAL（data.deliveryNo）两种响应形状提取京东出库单号。 */
    private String extractDeliveryNo(JdResult result) {
        if (result.data() instanceof Map<?, ?> data) {
            String direct = text(data.get("deliveryNo"));
            if (direct != null) {
                return direct;
            }
            Object nested = data.get("response");
            if (nested instanceof Map<?, ?> envelope) {
                return text(envelope.get("deliveryNo"));
            }
        }
        return null;
    }

    /** 成功建单必须同时回传并匹配商户侧稳定出库号。 */
    private String extractErpDeliveryNo(JdResult result) {
        if (result.data() instanceof Map<?, ?> data) {
            String direct = text(data.get("erpDeliveryNo"));
            if (direct != null) {
                return direct;
            }
            Object nested = data.get("response");
            if (nested instanceof Map<?, ?> envelope) {
                return text(envelope.get("erpDeliveryNo"));
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> cargosOf(Map<String, Object> request) {
        return (List<Map<String, Object>>) request.get("cargoInfos");
    }

    private String submittedOwnerNo(Map<String, Object> request) {
        Object rawCustomerInfo = request.get("customerInfo");
        if (!(rawCustomerInfo instanceof Map<?, ?> customerInfo)) {
            return null;
        }
        return text(customerInfo.get("ownerNo"));
    }

    private String configValue(Map<String, Object> config, String key, String fallback) {
        Object value = config == null ? null : config.get(key);
        return value == null || value.toString().isBlank() ? fallback : value.toString();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String requiredText(String value) {
        return value == null ? null : value.trim();
    }

    private static String optionalText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private Map<String, Object> parseJsonMap(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("履约方配置 JSON 无法解析", exception);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String text(Object value) {
        return value == null || value.toString().isBlank() ? null : value.toString();
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

    private record Item(
            long fulfillmentId,
            BigDecimal instructedQuantity,
            long orderLineId,
            String lineType,
            Long skuId,
            int lineNo,
            String productName,
            String unit,
            String processingStage) {
    }

    private record Component(
            long orderLineId,
            int componentNo,
            long skuId,
            BigDecimal quantityPerBundle,
            String productName,
            String unit) {
    }

    private record JdOutbound(
            String syncStatus,
            String requestHash,
            int retryCount,
            String lastErrorCode,
            String clientMode) {

        boolean requiresReconciliation() {
            return SYNC_STATUS_SUBMITTING.equals(syncStatus)
                    || (SYNC_STATUS_SYNC_FAILED.equals(syncStatus)
                            && UNCERTAIN_EXTERNAL_RESULTS.contains(lastErrorCode));
        }
    }

    private record SubmitIntent(
            ShipmentJdOutboundPreviewSnapshot preview,
            int retryCount,
            String previousSyncStatus,
            String previousErrorCode,
            String clientMode) {

        boolean requiresReconciliation() {
            return SYNC_STATUS_SUBMITTING.equals(previousSyncStatus)
                    || (SYNC_STATUS_SYNC_FAILED.equals(previousSyncStatus)
                            && UNCERTAIN_EXTERNAL_RESULTS.contains(previousErrorCode));
        }
    }

    private record SubmitExternalResult(
            boolean accepted,
            String failurePhase,
            String businessCode,
            String message,
            JdResult jdResult) {

        static SubmitExternalResult validationFailure(String businessCode, String message) {
            return new SubmitExternalResult(false, "VALIDATION", businessCode, message, null);
        }

        static SubmitExternalResult submitted(JdResult result) {
            return new SubmitExternalResult(true, null, null, null, result);
        }
    }

    private record Context(
            long id,
            String shipmentNo,
            String outboundOrderNo,
            String shipmentStatus,
            int shipmentSequence,
            long orderId,
            long providerId,
            long shipmentVersion,
            String sourceRef,
            String receiverName,
            String receiverPhone,
            String receiverAddress,
            String jdReceiverProvince,
            String jdReceiverCity,
            String jdReceiverCounty,
            String jdReceiverTown,
            String jdReceiverDetailAddress,
            boolean jdReceiverConfirmed,
            String providerType,
            Map<String, Object> config,
            String orderCustomerCode,
            String orderCustomerName,
            String jdCustomerCode,
            JdOutbound jdOutbound,
            List<Item> items,
            Map<Long, List<Component>> componentsByOrderLine,
            Map<Long, JdGoods> goodsBySku) {
    }

    private record JdGoods(
            long skuId,
            String goodsNo,
            String merchantSkuCode,
            Map<String, Object> externalCodes,
            boolean active) {
    }

}
