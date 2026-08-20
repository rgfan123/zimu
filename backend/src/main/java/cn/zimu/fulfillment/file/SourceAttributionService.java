package cn.zimu.fulfillment.file;

import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.audit.SecretRedactor;
import cn.zimu.fulfillment.common.domain.DataScope;
import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.common.domain.SourceChannelDisplayNames;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.event.OrderEventService;
import cn.zimu.fulfillment.common.idempotency.IdempotencyService;
import cn.zimu.fulfillment.common.idempotency.IdempotentResult;
import cn.zimu.fulfillment.common.version.OrderVersionService;
import cn.zimu.fulfillment.common.web.CommandContext;
import cn.zimu.fulfillment.order.OrderQueryService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 不改写来源事实的追加式归因纠正事务边界。 */
@Service
public class SourceAttributionService {

    private final JdbcTemplate jdbc;
    private final IdempotencyService idempotency;
    private final OrderEventService events;
    private final OrderVersionService versions;
    private final OrderQueryService orders;
    private final AuditLogService audit;
    private final TrackingFileService trackingFiles;
    private final ObjectMapper objectMapper;

    public SourceAttributionService(
            JdbcTemplate jdbc,
            IdempotencyService idempotency,
            OrderEventService events,
            OrderVersionService versions,
            OrderQueryService orders,
            AuditLogService audit,
            TrackingFileService trackingFiles,
            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.idempotency = idempotency;
        this.events = events;
        this.versions = versions;
        this.orders = orders;
        this.audit = audit;
        this.trackingFiles = trackingFiles;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public IdempotentResult<Map<String, Object>> correct(
            long batchId,
            SourceAttributionCorrectionWrite input,
            String idempotencyKey,
            CommandContext context) {
        Map<String, Object> payload = Map.of("batch_id", batchId, "body", input);
        return idempotency.execute("source_attribution.correct", idempotencyKey, payload, 201, () -> {
            BatchSource source = lockSourceBatch(batchId);
            SourceChannel target = SourceChannelDisplayNames.fromDisplayName(input.sourceChannelDisplayName());
            boolean historicalDazheMisattribution = "WANGQI".equals(source.recordedChannel())
                    && "WANGQI".equals(source.effectiveChannel())
                    && "WANGQI_SOURCE_ORDER".equals(source.effectiveTemplateFamily())
                    && source.effectiveTemplateFingerprint().startsWith("WANGQI-v1-");
            if (target != SourceChannel.DAZHE || !historicalDazheMisattribution) {
                throw BusinessException.unprocessable(
                        "SOURCE_ATTRIBUTION_SCOPE_UNSUPPORTED",
                        "当前仅支持把历史十五列表格从旧误标纠正为大者");
            }
            if (target.name().equals(source.effectiveChannel())) {
                throw BusinessException.conflict("SOURCE_ATTRIBUTION_UNCHANGED", "来源归因没有变化");
            }
            Boolean hasUnsafeReturnPush = jdbc.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM app.source_return_exports "
                            + "WHERE import_batch_id=? AND push_status IN ('PUSHING', 'SUCCESS'))",
                    Boolean.class,
                    batchId);
            if (Boolean.TRUE.equals(hasUnsafeReturnPush)) {
                throw BusinessException.conflict(
                        "SOURCE_ATTRIBUTION_RETURN_PUSH_UNSAFE", "来源回填正在推送或已推送成功，必须转人工纠正");
            }

            int correctionNo = source.latestCorrectionNo() + 1;
            String targetFamily = technicalPrefix(target, source.effectiveTemplateFamily());
            String targetFingerprint = technicalPrefix(target, source.effectiveTemplateFingerprint());
            Map<String, Object> safeEvidence = SecretRedactor.redact(
                    input.evidence() == null ? Map.of() : input.evidence());
            long correctionId = jdbc.queryForObject(
                    """
                    INSERT INTO app.source_attribution_corrections
                        (import_batch_id, correction_no, attributed_source_channel,
                         attributed_template_family, attributed_template_fingerprint,
                         reason, evidence, corrected_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?) RETURNING id
                    """,
                    Long.class,
                    batchId,
                    correctionNo,
                    target.name(),
                    targetFamily,
                    targetFingerprint,
                    input.reason(),
                    json(safeEvidence),
                    context.operator());

            int invalidated = jdbc.update(
                    """
                    INSERT INTO app.source_return_export_invalidations
                        (source_return_export_id, source_attribution_correction_id,
                         reason_code, invalidated_by)
                    SELECT sre.id, ?, 'SOURCE_ATTRIBUTION_CORRECTED', ?
                    FROM app.source_return_exports sre
                    WHERE sre.import_batch_id=? AND sre.push_status IN ('NOT_PUSHED', 'FAILED')
                      AND NOT EXISTS (
                          SELECT 1 FROM app.source_return_export_invalidations invalidation
                          WHERE invalidation.source_return_export_id=sre.id)
                    """,
                    correctionId,
                    context.operator(),
                    batchId);

            String recordedName = SourceChannelDisplayNames.displayName(source.recordedChannel());
            String effectiveName = SourceChannelDisplayNames.displayName(target);
            List<Long> orderIds = jdbc.queryForList(
                    "SELECT id FROM app.orders WHERE source_import_batch_id=? ORDER BY id",
                    Long.class,
                    batchId);
            for (Long orderId : orderIds) {
                Map<String, Object> attribution = Map.of(
                        "recorded_source_channel_display_name", recordedName,
                        "effective_source_channel_display_name", effectiveName,
                        "correction_id", correctionId,
                        "reason", input.reason());
                events.append(
                        orderId,
                        "SOURCE_ATTRIBUTION_CORRECTED",
                        null,
                        null,
                        null,
                        null,
                        DataScope.BUSINESS,
                        attribution,
                        context.operator());
                Map<String, Object> snapshot = new LinkedHashMap<>(objectMapper.convertValue(
                        orders.getDetail(orderId), new TypeReference<Map<String, Object>>() {}));
                snapshot.remove("source_channel");
                snapshot.remove("sourceChannel");
                snapshot.put("source_attribution", attribution);
                versions.append(orderId, null, "来源归因纠正", context.operator(), snapshot);
            }

            Long successorReturnId = trackingFiles.regenerateSourceReturnAfterAttributionCorrection(
                    batchId, context.operator());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("id", Long.toString(correctionId));
            result.put("import_batch_id", Long.toString(batchId));
            result.put("correction_no", correctionNo);
            result.put("recorded_source_channel_display_name", recordedName);
            result.put("effective_source_channel_display_name", effectiveName);
            result.put("reason", input.reason());
            result.put("invalidated_source_return_count", invalidated);
            result.put("successor_source_return_export_id",
                    successorReturnId == null ? null : Long.toString(successorReturnId));

            audit.record(new AuditLogService.AuditCommand()
                    .dataScope(DataScope.BUSINESS)
                    .requestId(context.requestId())
                    .traceId(context.traceId())
                    .operator(context.operator())
                    .actorType(AuditActorType.HUMAN)
                    .service("source-attribution")
                    .operation("source-attribution.correct")
                    .requestPayload(payload)
                    .responsePayload(result)
                    .httpStatus(201)
                    .businessCode("SOURCE_ATTRIBUTION_CORRECTED"));
            return result;
        });
    }

    private BatchSource lockSourceBatch(long batchId) {
        List<BatchSource> rows = jdbc.query(
                """
                SELECT source.recorded_source_channel, source.effective_source_channel,
                       source.effective_template_family, source.effective_template_fingerprint,
                       COALESCE(source.latest_correction_no, 0) latest_correction_no
                FROM app.v_import_batch_effective_source source
                JOIN app.import_batches ib ON ib.id=source.import_batch_id
                WHERE ib.id=? AND ib.batch_type='SOURCE_ORDER'
                FOR UPDATE OF ib
                """,
                (resultSet, rowNum) -> new BatchSource(
                        resultSet.getString("recorded_source_channel"),
                        resultSet.getString("effective_source_channel"),
                        resultSet.getString("effective_template_family"),
                        resultSet.getString("effective_template_fingerprint"),
                        resultSet.getInt("latest_correction_no")),
                batchId);
        if (rows.isEmpty()) {
            throw BusinessException.notFound("来源订单导入批次不存在");
        }
        return rows.getFirst();
    }

    private String technicalPrefix(SourceChannel target, String current) {
        int separator = current.indexOf(current.contains("-") ? '-' : '_');
        return separator < 0 ? target.name() + "_SOURCE_ORDER" : target.name() + current.substring(separator);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record BatchSource(
            String recordedChannel,
            String effectiveChannel,
            String effectiveTemplateFamily,
            String effectiveTemplateFingerprint,
            int latestCorrectionNo) {}
}
