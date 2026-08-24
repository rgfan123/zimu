package cn.zimu.fulfillment.file;

import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.domain.DataScope;
import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.common.domain.SourceChannelDisplayNames;
import cn.zimu.fulfillment.common.dto.PageResponse;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.web.CommandContext;
import cn.zimu.fulfillment.common.idempotency.IdempotencyService;
import cn.zimu.fulfillment.common.idempotency.IdempotentResult;
import cn.zimu.fulfillment.customer.ImportedCustomerService;
import cn.zimu.fulfillment.fulfillment.ShipmentJdOutboundCommand;
import cn.zimu.fulfillment.fulfillment.ShipmentJdOutboundService;
import cn.zimu.fulfillment.order.OrderCreateService;
import cn.zimu.fulfillment.order.domain.LineType;
import cn.zimu.fulfillment.order.domain.SettlementMethod;
import cn.zimu.fulfillment.order.dto.BundleComponentInput;
import cn.zimu.fulfillment.order.dto.CanonicalOrderInput;
import cn.zimu.fulfillment.order.dto.CustomerInput;
import cn.zimu.fulfillment.order.dto.OrderDetailDto;
import cn.zimu.fulfillment.order.dto.OrderItemInput;
import cn.zimu.fulfillment.order.dto.OrderLineDto;
import cn.zimu.fulfillment.order.dto.Receiver;
import cn.zimu.fulfillment.order.dto.Settlement;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 来源文件编排：先留存原文件/原始行，再通过共用订单应用用例产生 CanonicalOrder。 */
@Service
public class SourceImportService {

    private static final Logger log = LoggerFactory.getLogger(SourceImportService.class);

    private final SourceFileParser parser;
    private final OrderCreateService orderCreateService;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final AuditLogService auditLogService;
    private final ProviderFileService providerFileService;
    private final ImportedCustomerService importedCustomers;
    private final ShipmentJdOutboundService shipmentJdOutboundService;
    private final ImportRowJdCargoProjectionService jdCargoProjectionService;
    private final IdempotencyService idempotency;
    private final Path fileRoot;

    SourceImportService(
            SourceFileParser parser,
            OrderCreateService orderCreateService,
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            AuditLogService auditLogService,
            ProviderFileService providerFileService,
            ImportedCustomerService importedCustomers,
            ShipmentJdOutboundService shipmentJdOutboundService,
            ImportRowJdCargoProjectionService jdCargoProjectionService,
            IdempotencyService idempotency,
            @Value("${app.file-store.root:${java.io.tmpdir}/zimu-fulfillment-files}") String fileRoot) {
        this.parser = parser;
        this.orderCreateService = orderCreateService;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.auditLogService = auditLogService;
        this.providerFileService = providerFileService;
        this.importedCustomers = importedCustomers;
        this.shipmentJdOutboundService = shipmentJdOutboundService;
        this.jdCargoProjectionService = jdCargoProjectionService;
        this.idempotency = idempotency;
        this.fileRoot = Path.of(fileRoot).toAbsolutePath().normalize();
    }

    @Transactional
    public Map<String, Object> upload(
            byte[] bytes,
            String originalFilename,
            String importMode,
            Long parentBatchId,
            String idempotencyKey,
            CommandContext context) {
        long started = System.nanoTime();
        String mode = normalizeMode(importMode, parentBatchId);
        ParsedSourceFile parsed = parser.parse(bytes);
        ParentBatch parent = validateParent(mode, parentBatchId, parsed);
        parsed = effectiveRevisionSource(parsed, parent);
        String sha256 = sha256(bytes);
        Long existing = existing(parsed, sha256, mode, parentBatchId);
        if (existing != null) {
            return get(existing);
        }
        String batchNo = "IMP-" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
        String safeFilename = safeFilename(originalFilename);
        Path retained = retain(bytes, sha256, parsed.csv() ? ".csv" : ".xlsx");
        int revision = parent == null ? 1 : parent.revisionNo() + 1;
        Long batchId = jdbc.queryForObject(
                """
                INSERT INTO app.import_batches
                    (batch_no, batch_type, import_mode, parent_import_batch_id, revision_no,
                     source_channel, template_family, template_version, template_fingerprint,
                     original_file_name, content_sha256, file_ref, status, uploaded_by, settlement_missing)
                VALUES (?, 'SOURCE_ORDER', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PROCESSING', ?, ?)
                RETURNING id
                """,
                Long.class,
                batchNo,
                mode,
                parentBatchId,
                revision,
                parsed.sourceChannel().name(),
                parsed.templateFamily(),
                parsed.templateVersion(),
                parsed.templateFingerprint(),
                safeFilename,
                sha256,
                retained.toString(),
                context.operator(),
                parsed.sourceChannel() == SourceChannel.WANQI);

        Map<RowKey, Long> rawIds = new LinkedHashMap<>();
        for (ParsedSourceRow row : parsed.rows()) {
            Long rawId = jdbc.queryForObject(
                    """
                    INSERT INTO app.raw_import_rows
                        (import_batch_id, sheet_name, sheet_index, row_index, raw_cells,
                         source_order_ref, status, error_code, error_detail)
                    VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?::jsonb)
                    RETURNING id
                    """,
                    Long.class,
                    batchId,
                    row.sheetName(),
                    row.sheetIndex(),
                    row.rowIndex(),
                    json(row.rawCells()),
                    row.sourceOrderRef(),
                    row.valid() ? "RECEIVED" : "NEED_REVIEW",
                    row.errorCode(),
                    row.valid() ? null : json(Map.of("message", row.errorMessage())));
            rawIds.put(new RowKey(row.sheetIndex(), row.rowIndex()), rawId);
        }

        Map<String, List<ParsedSourceRow>> groups = group(parsed.rows(), batchNo);
        for (Map.Entry<String, List<ParsedSourceRow>> entry : groups.entrySet()) {
            List<ParsedSourceRow> group = entry.getValue();
            if (!consistentReceiver(group)) {
                group.forEach(row -> markReview(batchId, row, "IMPORT_VALIDATION", "同一来源订单的收货人快照不一致"));
                continue;
            }
            CanonicalizedGroup canonical = canonical(parsed, batchNo, entry.getKey(), group);
            OrderDetailDto order = orderCreateService.createImported(
                            canonical.order(),
                            batchId,
                            "import-" + batchId + "-" + sha256(entry.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                            context)
                    .result();
            int lineCursor = 0;
            for (int index = 0; index < group.size(); index++) {
                ParsedSourceRow row = group.get(index);
                int partitionCount = canonical.partitionCounts().get(index);
                List<OrderLineDto> partitionLines = order.lines().subList(lineCursor, lineCursor + partitionCount);
                lineCursor += partitionCount;
                OrderLineDto primaryLine = partitionLines.getFirst();
                String errorCode = partitionLines.stream()
                        .map(OrderLineDto::exceptionCode)
                        .filter(Objects::nonNull)
                        .map(this::importErrorCode)
                        .filter(Objects::nonNull)
                        .findFirst()
                        .orElse(null);
                jdbc.update(
                        """
                        UPDATE app.raw_import_rows
                        SET status=?, error_code=?, error_detail=?::jsonb, order_id=?, order_line_id=?, updated_at=CURRENT_TIMESTAMP
                        WHERE import_batch_id=? AND sheet_index=? AND row_index=?
                        """,
                        errorCode == null ? "ACCEPTED" : "NEED_REVIEW",
                        errorCode,
                        errorCode == null ? null : json(Map.of(
                                "order_line_exceptions",
                                partitionLines.stream().map(OrderLineDto::exceptionCode).filter(Objects::nonNull).toList())),
                        Long.valueOf(order.id()),
                        Long.valueOf(primaryLine.id()),
                        batchId,
                        row.sheetIndex(),
                        row.rowIndex());
                Long rawId = rawIds.get(new RowKey(row.sheetIndex(), row.rowIndex()));
                for (int partitionNo = 0; partitionNo < partitionLines.size(); partitionNo++) {
                    jdbc.update(
                            """
                            INSERT INTO app.raw_import_row_order_lines(raw_import_row_id, order_line_id, partition_no)
                            VALUES (?, ?, ?)
                            """,
                            rawId,
                            Long.valueOf(partitionLines.get(partitionNo).id()),
                            partitionNo + 1);
                }
            }
        }
        // raw 行已与订单行建立血缘后，把 sheet/行号并入 SKU 映射类复核事项并直连 raw_import_row_id。
        enrichReviewCasesWithSourceRow(batchId);

        return finalizeBatch(batchId, started, context, AuditActorType.HUMAN,
                "source-file-import", "source-orders.upload",
                Map.of(
                        "idempotency_key", idempotencyKey,
                        "original_file_name", safeFilename,
                        "content_sha256", sha256,
                        "import_mode", mode,
                        "settlement_missing", parsed.sourceChannel() == SourceChannel.WANQI));
    }

    /** 批次收尾共享：counts → 状态（NEED_REVIEW 阻断 confirm 语义不变）→ SYSTEM/HUMAN 审计。 */
    private Map<String, Object> finalizeBatch(
            long batchId,
            long started,
            CommandContext context,
            AuditActorType actor,
            String auditService,
            String auditOperation,
            Map<String, Object> auditPayload) {
        Map<String, Integer> counts = counts(batchId);
        String status = counts.get("need_review") > 0 || counts.get("rejected") > 0
                ? "COMPLETED_WITH_REVIEW"
                : "COMPLETED";
        jdbc.update(
                "UPDATE app.import_batches SET status=?, processed_at=CURRENT_TIMESTAMP WHERE id=?",
                status,
                batchId);
        Map<String, Object> result = get(batchId);
        auditLogService.record(new AuditLogService.AuditCommand()
                .dataScope(DataScope.BUSINESS)
                .requestId(context.requestId())
                .traceId(context.traceId())
                .operator(context.operator())
                .actorType(actor)
                .service(auditService)
                .operation(auditOperation)
                .requestPayload(auditPayload)
                .responsePayload(result)
                .httpStatus(201)
                .businessCode(status)
                .latencyMs((int) ((System.nanoTime() - started) / 1_000_000)));
        return result;
    }

    /**
     * 结构化订单导入（ticket 02）：在线 Connector 的 transform 产物直接建
     * SOURCE_ORDER 批次 + raw_import_rows 血缘 + 订单，confirm / 履约导出 / 来源回填
     * 管线与文件导入完全复用。
     *
     * <p>语义：每条 StructuredOrderRow 是一个来源订单（canonicalInput 可含多行 items）；
     * 内部按 items 展开写 raw 行（一行 = 一个来源行 = 一个 order line，与文件导入的
     * 行语义一致）。重复订单（DUPLICATE_ORDER）整单跳过：不写 raw 行、记审计，
     * 不整批回滚——confirm 的 uncovered 检查只统计 ACCEPTED 且有导出/发货关联的行，
     * 跳过行不落库即不产生阻断。内容哈希幂等——并发相同内容撞
     * uq_import_content_scope 时重查返回既有批次。</p>
     */
    @Transactional
    public Map<String, Object> importStructured(
            SourceChannel channel,
            List<StructuredOrderRow> orders,
            String batchNo,
            CommandContext context) {
        long started = System.nanoTime();
        if (orders == null || orders.isEmpty()) {
            throw BusinessException.badRequest("EMPTY_IMPORT", "结构化导入订单为空");
        }
        byte[] content = structuredContentBytes(orders);
        String contentSha = sha256(content);
        Long existingId = existingStructured(channel, contentSha);
        if (existingId != null) {
            return get(existingId);
        }
        try {
            return doImportStructured(channel, orders, batchNo, contentSha, context, started);
        } catch (DataIntegrityViolationException duplicate) {
            Long existing = existingStructured(channel, contentSha);
            if (existing != null) {
                return get(existing);
            }
            throw duplicate;
        }
    }

    private Map<String, Object> doImportStructured(
            SourceChannel channel,
            List<StructuredOrderRow> orders,
            String batchNo,
            String contentSha,
            CommandContext context,
            long started) {
        // 1) 建批次：import_mode 只能用 DDL 白名单 NEW/REVISION；template_*/file_ref 为 NOT NULL 用结构化占位
        Long batchId = jdbc.queryForObject(
                """
                INSERT INTO app.import_batches
                    (batch_no, batch_type, import_mode, parent_import_batch_id, revision_no,
                     source_channel, template_family, template_version, template_fingerprint,
                     original_file_name, content_sha256, file_ref, status, uploaded_by)
                VALUES (?, 'SOURCE_ORDER', 'NEW', NULL, 1, ?, 'STRUCTURED', '1',
                        'structured-json-v1', ?, ?, ?, 'PROCESSING', ?)
                RETURNING id
                """,
                Long.class,
                batchNo,
                channel.name(),
                batchNo + ".json",
                contentSha,
                "structured://" + batchNo,
                context.operator());

        // 2) 逐订单创建（同一事务）。重复订单在调用前预检测跳过（不写 raw 行、记审计）：
        //    doCreate 的 DUPLICATE_ORDER 一旦抛出会经 createImported 事务代理标记
        //    rollback-only，外部无法 catch 挽救——因此重复检测必须前置（同事务内可读）。
        //    并发跨批同单的极小竞态窗口由调度防重入 + 幂等键兜底（真撞则整批回滚，
        //    与文件导入的批次原子性一致）。
        int rowIndex = 0;
        for (int orderIndex = 0; orderIndex < orders.size(); orderIndex++) {
            StructuredOrderRow order = orders.get(orderIndex);
            Objects.requireNonNull(order.canonicalInput(), "结构化订单缺少 canonical 输入: " + order.sourceRef());
            List<OrderItemInput> items = order.canonicalInput().items();
            if (items.isEmpty()) {
                throw BusinessException.badRequest("EMPTY_ORDER", "订单无商品行: " + order.sourceRef());
            }
            if (orderExists(channel, order.sourceRef())) {
                auditLogService.record(new AuditLogService.AuditCommand()
                        .dataScope(DataScope.BUSINESS)
                        .requestId(context.requestId())
                        .traceId(context.traceId())
                        .operator(context.operator())
                        .actorType(AuditActorType.SYSTEM)
                        .service("source-order-structured-import")
                        .operation("source-orders.importStructured")
                        .requestPayload(Map.of("batch_no", batchNo, "source_ref", order.sourceRef()))
                        .businessCode("ORDER_ALREADY_EXISTS")
                        .httpStatus(200));
                continue;
            }
            OrderDetailDto created = orderCreateService.createImported(
                            order.canonicalInput(),
                            batchId,
                            "pull-" + batchNo + "-" + orderIndex,
                            context,
                            AuditActorType.SYSTEM)
                    .result();
            List<OrderLineDto> lines = created.lines();
            for (int itemIndex = 0; itemIndex < lines.size(); itemIndex++) {
                rowIndex++;
                OrderLineDto line = lines.get(itemIndex);
                insertStructuredRow(batchId, rowIndex, order, itemIndex, "ACCEPTED",
                        Long.valueOf(created.id()), Long.valueOf(line.id()), null, null);
            }
        }
        // raw 行已与订单行建立血缘后，把 sheet/行号并入 SKU 映射类复核事项并直连 raw_import_row_id。
        enrichReviewCasesWithSourceRow(batchId);

        // 3) 批次收尾：状态与审计口径与文件导入一致
        return finalizeBatch(batchId, started, context, AuditActorType.SYSTEM,
                "source-order-structured-import", "source-orders.importStructured",
                Map.of("batch_no", batchNo, "content_sha256", contentSha));
    }

    /** 预检测：同渠道同来源单号的订单已存在（同事务内可读自身写入）。 */
    private boolean orderExists(SourceChannel channel, String sourceRef) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                """
                SELECT EXISTS(
                    SELECT 1 FROM app.orders
                    WHERE data_scope='BUSINESS' AND source_channel=? AND source_ref=?
                )
                """,
                Boolean.class,
                channel.name(),
                sourceRef));
    }

    /** 结构化 raw 行写入（一行 = 一个来源行 = 一个 order line）。 */
    private void insertStructuredRow(
            long batchId,
            int rowIndex,
            StructuredOrderRow order,
            int itemIndex,
            String status,
            Long orderId,
            Long orderLineId,
            String errorCode,
            String errorDetail) {
        Long rawId = jdbc.queryForObject(
                """
                INSERT INTO app.raw_import_rows
                    (import_batch_id, sheet_name, sheet_index, row_index, raw_cells,
                     source_order_ref, status, error_code, error_detail, order_id, order_line_id)
                VALUES (?, 'STRUCTURED', 0, ?, ?::jsonb, ?, ?, ?, ?::jsonb, ?, ?)
                RETURNING id
                """,
                Long.class,
                batchId,
                rowIndex,
                json(rowCells(order, itemIndex)),
                order.sourceRef(),
                status,
                errorCode,
                errorDetail,
                orderId,
                orderLineId);
        if (orderLineId != null) {
            jdbc.update(
                    """
                    INSERT INTO app.raw_import_row_order_lines(raw_import_row_id, order_line_id, partition_no)
                    VALUES (?, ?, 1)
                    """,
                    rawId,
                    orderLineId);
        }
    }

    /** 结构化导入的内容哈希幂等查询；与 uq_import_content_scope（batch_type+sha+channel+provider+export）语义对齐。 */
    private Long existingStructured(SourceChannel channel, String contentSha) {
        List<Long> ids = jdbc.query(
                """
                SELECT id FROM app.import_batches
                WHERE batch_type='SOURCE_ORDER' AND content_sha256=?
                  AND source_channel=?
                  AND fulfillment_provider_id IS NULL AND source_fulfillment_export_id IS NULL
                ORDER BY id LIMIT 1
                """,
                (resultSet, rowNum) -> resultSet.getLong(1),
                contentSha,
                channel.name());
        return ids.isEmpty() ? null : ids.getFirst();
    }

    /** 内容哈希的确定性序列化：LinkedHashMap 按插入序输出，跨运行稳定。 */
    private byte[] structuredContentBytes(List<StructuredOrderRow> orders) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (StructuredOrderRow order : orders) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("source_ref", order.sourceRef());
            entry.put("source_line_ref", order.sourceLineRef());
            entry.put("canonical", order.canonicalInput());
            entry.put("raw", order.rawSnapshot());
            list.add(entry);
        }
        return json(list).getBytes(StandardCharsets.UTF_8);
    }

    /** raw_import_rows.raw_cells 快照：脱敏后的订单原始快照 + 行内商品序号（血缘证据）。 */
    private Map<String, Object> rowCells(StructuredOrderRow order, int itemIndex) {
        Map<String, Object> cells = new LinkedHashMap<>();
        cells.put("source_ref", order.sourceRef());
        cells.put("source_line_ref", order.sourceLineRef());
        cells.put("item_index", itemIndex);
        cells.put("snapshot", sanitizeSnapshot(order.rawSnapshot()));
        return cells;
    }

    /** 敏感字段掩码（姓名/电话/地址类键，浅层处理；深层嵌套由 Connector 在 transform 前自行脱敏）。 */
    private Map<String, Object> sanitizeSnapshot(Map<String, Object> raw) {
        if (raw == null) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>(raw);
        for (String key : SENSITIVE_KEYS) {
            Object value = out.get(key);
            if (value instanceof String text && !text.isBlank()) {
                out.put(key, text.length() <= 3 ? "***" : text.substring(0, 3) + "***");
            }
        }
        return out;
    }

    private static final List<String> SENSITIVE_KEYS = List.of(
            "receiverName", "receiverTelephone", "receiver_name", "receiver_phone",
            "receipt_username", "receipt_phone_number", "address_detail",
            "telephone", "phone", "contactName", "contactPhone");

    Map<String, Object> get(long batchId) {
        List<Map<String, Object>> rows = jdbc.query(
                """
                SELECT ib.id, ib.batch_no, ib.batch_type, ib.import_mode, ib.parent_import_batch_id, ib.revision_no,
                       ib.source_channel, source.recorded_source_channel, source.effective_source_channel,
                       ib.fulfillment_provider_id, ib.source_fulfillment_export_id,
                       ib.template_family, ib.template_version, ib.template_fingerprint, ib.original_file_name,
                       ib.content_sha256, ib.status, ib.error_detail::text error_detail,
                       ib.received_at, ib.processed_at, ib.confirmed_at, ib.confirmed_by,
                       ib.settlement_missing
                FROM app.import_batches ib
                JOIN app.v_import_batch_effective_source source ON source.import_batch_id=ib.id
                WHERE ib.id=?
                """,
                (resultSet, rowNum) -> {
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("id", resultSet.getString("id"));
                    value.put("batch_no", resultSet.getString("batch_no"));
                    value.put("batch_type", resultSet.getString("batch_type"));
                    value.put("import_mode", resultSet.getString("import_mode"));
                    value.put("parent_import_batch_id", nullableId(resultSet.getObject("parent_import_batch_id")));
                    value.put("revision_no", resultSet.getInt("revision_no"));
                    value.put("source_channel", resultSet.getString("source_channel"));
                    String recordedSource = resultSet.getString("recorded_source_channel");
                    String effectiveSource = resultSet.getString("effective_source_channel");
                    value.put("recorded_source_channel_display_name",
                            SourceChannelDisplayNames.displayName(recordedSource));
                    value.put("effective_source_channel_display_name",
                            SourceChannelDisplayNames.displayName(effectiveSource));
                    value.put("source_channel_display_name",
                            SourceChannelDisplayNames.displayName(effectiveSource));
                    value.put("fulfillment_provider_id", nullableId(resultSet.getObject("fulfillment_provider_id")));
                    value.put("source_fulfillment_export_id", nullableId(resultSet.getObject("source_fulfillment_export_id")));
                    value.put("template_family", resultSet.getString("template_family"));
                    value.put("template_version", resultSet.getString("template_version"));
                    value.put("template_fingerprint", resultSet.getString("template_fingerprint"));
                    value.put("original_file_name", resultSet.getString("original_file_name"));
                    value.put("content_sha256", resultSet.getString("content_sha256"));
                    value.put("status", resultSet.getString("status"));
                    value.put("error_detail", parseJson(resultSet.getString("error_detail")));
                    value.put("received_at", resultSet.getTimestamp("received_at").toInstant());
                    value.put("processed_at", resultSet.getTimestamp("processed_at") == null
                            ? null : resultSet.getTimestamp("processed_at").toInstant());
                    value.put("confirmed_at", resultSet.getTimestamp("confirmed_at") == null
                            ? null : resultSet.getTimestamp("confirmed_at").toInstant());
                    value.put("confirmed_by", resultSet.getString("confirmed_by"));
                    value.put("settlement_missing", resultSet.getBoolean("settlement_missing"));
                    return value;
                },
                batchId);
        if (rows.isEmpty()) {
            throw BusinessException.notFound("导入批次不存在: " + batchId);
        }
        Map<String, Object> result = rows.getFirst();
        result.put("row_counts", counts(batchId));
        result.put("generated_fulfillment_export_ids", ids(
                """
                SELECT DISTINCT fei.fulfillment_export_id
                FROM app.raw_import_rows rir
                JOIN app.fulfillment_export_items fei ON fei.raw_import_row_id=rir.id
                WHERE rir.import_batch_id=? ORDER BY fei.fulfillment_export_id
                """, batchId));
        result.put("generated_source_return_export_ids", ids(
                "SELECT id FROM app.source_return_exports WHERE import_batch_id=? ORDER BY version_no", batchId));
        return result;
    }

    PageResponse<Map<String, Object>> rows(long batchId, int page, int size, String status) {
        get(batchId);
        if (page < 0 || size < 1 || size > 200) {
            throw BusinessException.badRequest("INVALID_PAGINATION", "page 必须非负且 size 必须为 1..200");
        }
        List<Object> arguments = new ArrayList<>();
        String where = " WHERE import_batch_id=?";
        arguments.add(batchId);
        if (status != null && !status.isBlank()) {
            if (!List.of("RECEIVED", "ACCEPTED", "NEED_REVIEW", "REJECTED").contains(status)) {
                throw BusinessException.badRequest("INVALID_ROW_STATUS", "未知的原始行状态");
            }
            where += " AND status=?";
            arguments.add(status);
        }
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM app.raw_import_rows" + where, Long.class, arguments.toArray());
        List<Object> pageArguments = new ArrayList<>(arguments);
        pageArguments.add(size);
        pageArguments.add((long) page * size);
        Map<String, Object> batch = get(batchId);
        if (!"SOURCE_ORDER".equals(batch.get("batch_type"))) {
            throw BusinessException.badRequest("INVALID_BATCH_TYPE", "原始行明细只对来源订单批次开放");
        }
        SourceChannel sourceChannel = SourceChannel.valueOf(String.valueOf(batch.get("source_channel")));
        List<Map<String, Object>> items = jdbc.query(
                """
                SELECT id, sheet_name, sheet_index, row_index, raw_cells::text raw_cells,
                       source_order_ref, status, error_code, error_detail::text error_detail,
                       order_id, order_line_id
                FROM app.raw_import_rows
                """ + where + " ORDER BY sheet_index, row_index LIMIT ? OFFSET ?",
                (resultSet, rowNum) -> {
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("id", resultSet.getString("id"));
                    value.put("sheet_name", resultSet.getString("sheet_name"));
                    value.put("sheet_index", resultSet.getInt("sheet_index"));
                    value.put("row_index", resultSet.getInt("row_index"));
                    value.put("raw_cells", parseJson(resultSet.getString("raw_cells")));
                    value.put("source_order_ref", resultSet.getString("source_order_ref"));
                    value.put("status", resultSet.getString("status"));
                    value.put("error_code", resultSet.getString("error_code"));
                    value.put("error_detail", parseJson(resultSet.getString("error_detail")));
                    value.put("order_id", nullableId(resultSet.getObject("order_id")));
                    value.put("order_line_id", nullableId(resultSet.getObject("order_line_id")));
                    value.put("parsed", projectionFor(sourceChannel, resultSet.getString("raw_cells")));
                    return value;
                },
                pageArguments.toArray());
        // SKU 履约方归属（JD_WAREHOUSE / THIRD_PARTY）：按渠道来源 SKU 映射一次批量查询
        Map<String, SkuFulfillmentProjection> skuFulfillment = skuFulfillmentByRef(
                sourceChannel,
                items.stream()
                        .map(item -> ((Map<?, ?>) item.get("parsed")).get("source_sku_ref"))
                        .filter(value -> value instanceof String text && !text.isBlank())
                        .map(String::valueOf)
                        .toList());
        for (Map<String, Object> item : items) {
            Object ref = ((Map<?, ?>) item.get("parsed")).get("source_sku_ref");
            item.put("sku_fulfillment", ref instanceof String text && !text.isBlank()
                    ? skuFulfillment.get(text)
                    : null);
        }
        // 京东发货数量：与 SDK 建单预览/提交共用同一纯裁决单元（JdCargoPlanner），
        // 逐行投影到 jd_cargos（已提交的行优先冻结实际提交值）。
        Map<Long, List<ImportRowJdCargoProjectionService.JdCargoProjection>> jdCargos =
                jdCargoProjectionService.jdCargosByRawRowId(
                        items.stream()
                                .map(item -> Long.parseLong(String.valueOf(item.get("id"))))
                                .toList());
        for (Map<String, Object> item : items) {
            item.put("jd_cargos", jdCargos.getOrDefault(
                    Long.parseLong(String.valueOf(item.get("id"))), List.of()));
        }
        int totalPages = total == 0 ? 0 : (int) ((total + size - 1) / size);
        return new PageResponse<>(items, page, size, total, totalPages);
    }

    /** 来源 SKU 归属履约方的白名单投影（provider_type/provider_name/内部 SKU 规格默认值）。 */
    record SkuFulfillmentProjection(String providerType, String providerName, String skuSpecification) {}

    /**
     * 渠道来源 SKU → 内部 SKU 归属的履约方（仅 active 映射）。
     *
     * <p>停用的渠道映射不展示归属（显示「—」），与导入侧解析/确认时该 ref 不再可用的语义一致；
     * 归属以内部 SKU 主数据的 fulfillment_provider 为准，非来源渠道侧声明。
     */
    private Map<String, SkuFulfillmentProjection> skuFulfillmentByRef(SourceChannel channel, List<String> refs) {
        if (refs.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(refs.size(), "?"));
        List<Object> arguments = new ArrayList<>();
        arguments.add(channel.name());
        arguments.addAll(refs);
        List<Map<String, Object>> rows = jdbc.query(
                """
                SELECT scs.source_sku_ref, fp.provider_type, fp.provider_name, s.specification AS sku_specification
                FROM app.source_channel_skus scs
                JOIN app.skus s ON s.id = scs.sku_id
                JOIN app.fulfillment_providers fp ON fp.id = s.fulfillment_provider_id
                WHERE scs.source_channel = ? AND scs.active AND scs.source_sku_ref IN ("""
                        + placeholders + ")",
                (resultSet, rowNum) -> {
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("ref", resultSet.getString("source_sku_ref"));
                    value.put("projection", new SkuFulfillmentProjection(
                            resultSet.getString("provider_type"),
                            resultSet.getString("provider_name"),
                            resultSet.getString("sku_specification")));
                    return value;
                },
                arguments.toArray());
        Map<String, SkuFulfillmentProjection> byRef = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            SkuFulfillmentProjection projection = (SkuFulfillmentProjection) row.get("projection");
            byRef.put(
                    (String) row.get("ref"),
                    new SkuFulfillmentProjection(
                            projection.providerType(),
                            projection.providerName(),
                            projection.skuSpecification()));
        }
        return byRef;
    }

    /** 原始单元格（字符串值）→ 渠道模板解析投影；非对象/非字符串值一律跳过。 */
    private Map<String, String> projectionFor(SourceChannel channel, String rawCellsJson) {
        if (rawCellsJson == null || rawCellsJson.isBlank()) {
            return Map.of();
        }
        try {
            com.fasterxml.jackson.databind.JsonNode cells = objectMapper.readTree(rawCellsJson);
            if (cells == null || !cells.isObject()) {
                return Map.of();
            }
            Map<String, String> stringCells = new LinkedHashMap<>();
            cells.fields().forEachRemaining(entry -> {
                if (entry.getValue().isTextual() || entry.getValue().isNumber()) {
                    stringCells.put(entry.getKey(), entry.getValue().asText());
                }
            });
            return parser.projection(channel, stringCells);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            return Map.of();
        }
    }

    private CanonicalizedGroup canonical(
            ParsedSourceFile parsed, String batchNo, String groupKey, List<ParsedSourceRow> rows) {
        ParsedSourceRow first = rows.getFirst();
        CustomerInput customer = importedCustomers.resolve(
                parsed.sourceChannel(), first.receiverName(), first.receiverPhone());
        List<OrderItemInput> items = new ArrayList<>();
        List<Integer> partitionCounts = new ArrayList<>();
        for (ParsedSourceRow row : rows) {
            List<OrderItemInput> rowItems = canonicalItems(parsed.sourceChannel(), row);
            items.addAll(rowItems);
            partitionCounts.add(rowItems.size());
        }
        Instant settlementAt = rows.stream().map(ParsedSourceRow::orderedAt).filter(Objects::nonNull)
                .min(Comparator.naturalOrder()).orElseGet(
                        () -> parsed.sourceChannel() == SourceChannel.WANQI ? null : Instant.now());
        CanonicalOrderInput order = new CanonicalOrderInput(
                parsed.sourceChannel(),
                first.sourceOrderRef() == null ? groupKey : first.sourceOrderRef(),
                batchNo,
                customer,
                new Receiver(
                        first.receiverName(), first.receiverPhone(), first.receiverProvince(), first.receiverCity(),
                        first.receiverDistrict(), null, first.receiverAddress()),
                items,
                "UNSPECIFIED".equals(first.settlementMethod())
                        ? Settlement.unspecifiedSourceFact()
                        : new Settlement(SettlementMethod.valueOf(first.settlementMethod()), settlementAt),
                first.remark(),
                rows.stream().map(row -> "import://" + batchNo + "/" + row.sheetIndex() + "/" + row.rowIndex()).toList());
        return new CanonicalizedGroup(order, List.copyOf(partitionCounts));
    }

    /**
     * 大者/万齐显式礼包映射优先于普通来源 SKU 映射；其他渠道和非礼包行保持既有 SINGLE 语义。
     *
     * <p>显式命中权威礼包后，组件按所属履约方稳定分组为多个同质订单行。组件身份使用内部
     * sku_code 快照；EMG 是京东履约编码，不能冒充来源渠道 SKU 映射。
     */
    private List<OrderItemInput> canonicalItems(SourceChannel channel, ParsedSourceRow row) {
        StaticSourceBundle sourceBundle = activeSourceBundle(channel, row.sourceSkuRef());
        if (sourceBundle == null) {
            if (bundleSourceChannel(channel) && looksLikeBundle(row.productName())) {
                return List.of(unresolvedBundleItem(row));
            }
            return List.of(singleItem(row));
        }
        List<StaticBundleComponent> components = jdbc.query(
                """
                SELECT s.fulfillment_provider_id, s.sku_code, p.product_name, s.specification, s.unit,
                       bi.quantity_per_bundle
                FROM app.bundle_items bi
                JOIN app.skus s ON s.id=bi.sku_id
                JOIN app.products p ON p.id=s.product_id
                WHERE bi.bundle_id=?
                ORDER BY bi.sort_no
                """,
                (resultSet, rowNum) -> new StaticBundleComponent(
                        resultSet.getLong("fulfillment_provider_id"),
                        new BundleComponentInput(
                                resultSet.getString("sku_code"),
                                null,
                                resultSet.getString("product_name"),
                                resultSet.getString("specification"),
                                resultSet.getString("unit"),
                                resultSet.getBigDecimal("quantity_per_bundle").toPlainString())),
                sourceBundle.bundleId());
        Map<Long, List<BundleComponentInput>> byProvider = new LinkedHashMap<>();
        for (StaticBundleComponent component : components) {
            byProvider.computeIfAbsent(component.providerId(), ignored -> new ArrayList<>()).add(component.input());
        }
        return byProvider.values().stream()
                .map(providerComponents -> new OrderItemInput(
                        row.sourceLineRef(),
                        LineType.CUSTOM_BUNDLE,
                        null,
                        row.sourceSkuRef(),
                        row.productName(),
                        row.specification(),
                        row.unit(),
                        quantity(row),
                        Long.toString(sourceBundle.bundleId()),
                        List.copyOf(providerComponents)))
                .toList();
    }

    /**
     * 大者/万齐名称明确表示礼包/组合但未命中 ACTIVE 主数据时，构造一个必然未映射的组件候选，
     * 复用订单应用层 SKU_MAPPING_REQUIRED 分支进入人工复核；禁止降级 SINGLE 后误命中普通 SKU。
     */
    private OrderItemInput unresolvedBundleItem(ParsedSourceRow row) {
        String ref = "__BUNDLE_MAPPING_REQUIRED__:" + row.sourceSkuRef();
        BundleComponentInput unresolved = new BundleComponentInput(
                null, ref, row.productName(), row.specification(), row.unit(), "1");
        return new OrderItemInput(
                row.sourceLineRef(),
                LineType.CUSTOM_BUNDLE,
                null,
                row.sourceSkuRef(),
                row.productName(),
                row.specification(),
                row.unit(),
                quantity(row),
                List.of(unresolved));
    }

    private boolean looksLikeBundle(String productName) {
        return productName != null
                && (productName.contains("礼包") || productName.contains("礼盒") || productName.contains("组合"));
    }

    private StaticSourceBundle activeSourceBundle(SourceChannel channel, String sourceSkuRef) {
        if (!bundleSourceChannel(channel) || sourceSkuRef == null || sourceSkuRef.isBlank()) {
            return null;
        }
        List<StaticSourceBundle> matches = jdbc.query(
                """
                SELECT scb.bundle_id
                FROM app.source_channel_bundles scb
                JOIN app.product_bundles pb ON pb.id=scb.bundle_id AND pb.status='ACTIVE'
                WHERE scb.source_channel=? AND scb.source_bundle_ref=?
                  AND scb.active AND scb.quantity_multiplier=1
                """,
                (resultSet, rowNum) -> new StaticSourceBundle(resultSet.getLong("bundle_id")),
                channel.name(),
                sourceSkuRef);
        return matches.isEmpty() ? null : matches.getFirst();
    }

    private boolean bundleSourceChannel(SourceChannel channel) {
        return channel == SourceChannel.DAZHE
                || channel == SourceChannel.WANGQI
                || channel == SourceChannel.WANQI;
    }

    private OrderItemInput singleItem(ParsedSourceRow row) {
        return new OrderItemInput(
                row.sourceLineRef(),
                LineType.SINGLE,
                null,
                row.sourceSkuRef(),
                row.productName(),
                row.specification(),
                row.unit(),
                quantity(row),
                null);
    }

    private String quantity(ParsedSourceRow row) {
        return new BigDecimal(row.quantity()).setScale(3).toPlainString();
    }

    @Transactional
    IdempotentResult<Map<String, Object>> confirm(
            long batchId, String idempotencyKey, CommandContext context) {
        Map<String, Object> payload = Map.of("batch_id", batchId);
        return idempotency.execute("source_import.confirm", idempotencyKey, payload, 200, () -> {
            providerFileService.validateSourceBatchExportability(batchId);
            Map<String, Object> batch = jdbc.queryForMap(
                    "SELECT batch_type, confirmed_at FROM app.import_batches WHERE id=? FOR UPDATE",
                    batchId);
            if (!"SOURCE_ORDER".equals(batch.get("batch_type"))) {
                throw BusinessException.unprocessable("IMPORT_BATCH_TYPE_INVALID", "仅来源订单批次可以确认");
            }
            List<Long> jdSdkShipmentIds = List.of();
            if (batch.get("confirmed_at") == null) {
                Integer blockers = jdbc.queryForObject(
                        "SELECT count(*) FROM app.raw_import_rows WHERE import_batch_id=? AND status<>'ACCEPTED'",
                        Integer.class,
                        batchId);
                if (blockers != null && blockers > 0) {
                    throw BusinessException.conflict("IMPORT_BATCH_BLOCKED", "批次仍有待处理的 SKU、文件或数据问题");
                }
                // jd-real-sdk-switch 05：按履约方显式配置路由——京东 SDK 直连或导单文件，第三方始终文件。
                Map<String, Object> routing = providerFileService.routeForSourceBatch(batchId, context.operator());
                @SuppressWarnings("unchecked")
                List<Long> sdkShipments = (List<Long>) routing.get("jd_sdk_shipment_ids");
                Integer uncoveredAcceptedRows = jdbc.queryForObject(
                        """
                        WITH raw_line_links AS (
                            SELECT rir.id raw_row_id, rir.order_line_id
                            FROM app.raw_import_rows rir
                            WHERE rir.import_batch_id=? AND rir.order_line_id IS NOT NULL
                            UNION
                            SELECT rirol.raw_import_row_id, rirol.order_line_id
                            FROM app.raw_import_row_order_lines rirol
                            JOIN app.raw_import_rows rir ON rir.id=rirol.raw_import_row_id
                            WHERE rir.import_batch_id=?
                        )
                        SELECT count(*)
                        FROM app.raw_import_rows rir
                        JOIN raw_line_links rll ON rll.raw_row_id=rir.id
                        WHERE rir.import_batch_id=? AND rir.status='ACCEPTED'
                          AND NOT EXISTS (
                            SELECT 1 FROM app.fulfillment_export_items fei
                            WHERE fei.raw_import_row_id=rir.id AND fei.order_line_id=rll.order_line_id
                          )
                          AND NOT EXISTS (
                            SELECT 1 FROM app.shipment_items si
                            JOIN app.fulfillments f ON f.id=si.fulfillment_id
                            WHERE f.order_line_id=rll.order_line_id
                          )
                          AND NOT EXISTS (
                            SELECT 1 FROM app.review_cases rc
                            WHERE rc.order_line_id=rll.order_line_id
                              AND rc.status='OPEN'
                              AND rc.reason_code='PROVIDER_SKU_MAPPING_REQUIRED'
                          )
                        """,
                        Integer.class,
                        batchId,
                        batchId,
                        batchId);
                if (uncoveredAcceptedRows != null && uncoveredAcceptedRows > 0) {
                    throw BusinessException.conflict(
                            "IMPORT_BATCH_EXPORT_INCOMPLETE",
                            "批次仍有已接收行未进入发货批次或履约导出，请完成订单复核后重试");
                }
                jdbc.update(
                        "UPDATE app.import_batches SET confirmed_at=CURRENT_TIMESTAMP, confirmed_by=? WHERE id=?",
                        context.operator(),
                        batchId);
                jdSdkShipmentIds = List.copyOf(sdkShipments);
            }
            Map<String, Object> result = get(batchId);
            // 京东 SDK 路由的批次由控制器在确认事务提交后触发批量建单（见
            // SourceImportController.confirm）；失败留痕（SYNC_FAILED/告警/复核）不阻断批次确认。
            List<Long> autoSubmitShipmentIds = jdSdkShipmentIds;
            if (!autoSubmitShipmentIds.isEmpty()) {
                result.put("outbound_routing", Map.of(
                        "jd_sdk_shipment_ids", autoSubmitShipmentIds.stream().map(String::valueOf).toList()));
            }
            auditLogService.record(new AuditLogService.AuditCommand()
                    .dataScope(DataScope.BUSINESS)
                    .requestId(context.requestId())
                    .traceId(context.traceId())
                    .operator(context.operator())
                    .actorType(AuditActorType.HUMAN)
                    .service("source-file-import")
                    .operation("source-orders.confirm")
                    .requestPayload(payload)
                    .responsePayload(result)
                    .httpStatus(200)
                    .businessCode("IMPORT_BATCH_CONFIRMED")
                    .latencyMs(0));
            return result;
        });
    }

    /**
     * 对批次内京东履约发货批次批量触发 SDK 建单（jd-real-sdk-switch 05）。
     *
     * <p>每个 shipment 使用稳定幂等键（submit 自管理事务与幂等注册），已提交的跳过、
     * 失败项（前置校验未过、门闩关闭、京东拒绝）留痕后可直接重试，重试不重复提交已成功项。
     * 本方法自身不包业务事务，逐条 submit 的外部写阶段始终在事务外执行。
     */
    Map<String, Object> submitJdOutboundsForBatch(long batchId, CommandContext context) {
        List<Long> shipmentIds = jdbc.query(
                """
                SELECT DISTINCT s.id
                FROM app.shipments s
                JOIN app.fulfillment_providers fp
                  ON fp.id=s.fulfillment_provider_id AND fp.provider_type='JD_WAREHOUSE'
                JOIN app.raw_import_rows rir ON rir.order_id=s.order_id
                WHERE rir.import_batch_id=? AND rir.status='ACCEPTED'
                ORDER BY s.id
                """,
                (resultSet, rowNum) -> resultSet.getLong(1),
                batchId);
        List<Map<String, Object>> items = new ArrayList<>();
        int skipped = 0;
        // 每次批量调用使用新的随机段：失败重试时 Shipment 版本/请求指纹必然变化，
        // 若沿用固定键会被幂等注册表判为「同键不同请求」冲突；防重复提交由
        // SUBMITTED 前置跳过 + submit 业务校验（ALREADY_SUBMITTED/RECONCILIATION_REQUIRED）保证。
        String attemptToken = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        for (long shipmentId : shipmentIds) {
            List<String> statuses = jdbc.query(
                    "SELECT sync_status FROM app.shipment_jd_outbounds WHERE shipment_id=?",
                    (resultSet, rowNum) -> resultSet.getString(1),
                    shipmentId);
            if ("SUBMITTED".equals(statuses.isEmpty() ? null : statuses.getFirst())) {
                skipped++;
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("shipment_id", String.valueOf(shipmentId));
            try {
                IdempotentResult<Map<String, Object>> submitted = shipmentJdOutboundService.submit(
                        shipmentId,
                        new ShipmentJdOutboundCommand(),
                        "batch-submit-" + batchId + "-" + attemptToken + "-shipment-" + shipmentId,
                        context);
                item.putAll(new LinkedHashMap<>(submitted.result()));
            } catch (BusinessException exception) {
                item.put("business_code", exception.getBusinessCode());
                item.put("message", exception.getMessage());
            }
            items.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("submitted_count", items.size());
        result.put("skipped_count", skipped);
        result.put("items", items);
        return result;
    }

    private Map<String, List<ParsedSourceRow>> group(List<ParsedSourceRow> rows, String batchNo) {
        Map<String, List<ParsedSourceRow>> groups = new LinkedHashMap<>();
        String previousReceiver = null;
        String previousSynthetic = null;
        int syntheticSequence = 0;
        int previousSheet = -1;
        for (ParsedSourceRow row : rows) {
            if (!row.valid()) {
                continue;
            }
            String key;
            if (row.sourceOrderRef() != null) {
                key = row.sourceOrderRef();
            } else {
                String receiver = receiverKey(row);
                if (row.sheetIndex() != previousSheet || !Objects.equals(receiver, previousReceiver)) {
                    previousSynthetic = (batchNo + "-S" + row.sheetIndex() + "-" + (++syntheticSequence));
                }
                previousSheet = row.sheetIndex();
                previousReceiver = receiver;
                key = previousSynthetic;
            }
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
        }
        return groups;
    }

    private boolean consistentReceiver(List<ParsedSourceRow> rows) {
        return rows.stream().map(this::receiverKey).distinct().count() == 1;
    }

    private String receiverKey(ParsedSourceRow row) {
        return row.receiverName() + "\u001f" + row.receiverPhone() + "\u001f" + row.receiverAddress();
    }

    private void markReview(long batchId, ParsedSourceRow row, String code, String message) {
        jdbc.update(
                """
                UPDATE app.raw_import_rows SET status='NEED_REVIEW', error_code=?, error_detail=?::jsonb,
                    updated_at=CURRENT_TIMESTAMP
                WHERE import_batch_id=? AND sheet_index=? AND row_index=?
                """,
                code,
                json(Map.of("message", message)),
                batchId,
                row.sheetIndex(),
                row.rowIndex());
    }

    /**
     * 把 raw_import_rows 的来源文件位置（sheet 名 + 行号）并入 SKU 映射类复核事项的 detail，
     * 并直连 review_cases.raw_import_row_id 外键。原始单元格值已存 raw_import_rows，
     * 这里只补引用与展示字段，不新增冗余存储；文件导入与结构化导入在 raw 行与订单行
     * 建立血缘后统一调用。
     */
    private void enrichReviewCasesWithSourceRow(long batchId) {
        jdbc.update(
                """
                UPDATE app.review_cases rc
                SET detail = rc.detail || jsonb_build_object(
                        'source_sheet_name', rir.sheet_name,
                        'source_row_index', rir.row_index),
                    raw_import_row_id = rir.id,
                    updated_at = CURRENT_TIMESTAMP
                FROM app.raw_import_rows rir
                WHERE rir.import_batch_id = ?
                  AND rir.order_id = rc.order_id
                  AND rir.order_line_id = rc.order_line_id
                  AND rc.order_line_id IS NOT NULL
                  AND rc.reason_code IN ('SKU_MAPPING_REQUIRED', 'SKU_MAPPING_CONFLICT',
                                         'SOURCE_SKU_MAPPING_REQUIRED', 'PROVIDER_SKU_MAPPING_REQUIRED')
                """,
                batchId);
    }

    private Map<String, Integer> counts(long batchId) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("total", 0);
        counts.put("accepted", 0);
        counts.put("need_review", 0);
        counts.put("rejected", 0);
        jdbc.query(
                "SELECT lower(status) status, COUNT(*) count FROM app.raw_import_rows WHERE import_batch_id=? GROUP BY status",
                resultSet -> {
                    int count = resultSet.getInt("count");
                    counts.put(resultSet.getString("status"), count);
                    counts.put("total", counts.get("total") + count);
                },
                batchId);
        // RECEIVED 仅是处理中状态，完成响应中不单列。
        counts.remove("received");
        return counts;
    }

    private ParentBatch validateParent(String mode, Long parentBatchId, ParsedSourceFile parsed) {
        if ("NEW".equals(mode)) {
            return null;
        }
        List<ParentBatch> parents = jdbc.query(
                """
                SELECT source.recorded_source_channel, source.effective_source_channel,
                       source.effective_template_family, source.effective_template_fingerprint,
                       ib.revision_no
                FROM app.import_batches ib
                JOIN app.v_import_batch_effective_source source ON source.import_batch_id=ib.id
                WHERE ib.id=? AND ib.batch_type='SOURCE_ORDER'
                """,
                (resultSet, rowNum) -> new ParentBatch(
                        resultSet.getString("recorded_source_channel"),
                        resultSet.getString("effective_source_channel"),
                        resultSet.getString("effective_template_family"),
                        resultSet.getString("effective_template_fingerprint"),
                        resultSet.getInt("revision_no")),
                parentBatchId);
        if (parents.isEmpty()) {
            throw BusinessException.unprocessable("REVISION_PARENT_NOT_FOUND", "REVISION 父批次不存在");
        }
        ParentBatch parent = parents.getFirst();
        boolean exactEffectiveChannel = parent.effectiveSourceChannel().equals(parsed.sourceChannel().name());
        boolean correctedLegacyIdentity = parent.recordedSourceChannel().equals(parsed.sourceChannel().name())
                && sameTemplateIdentity(parent.effectiveTemplateFamily(), parsed.templateFamily())
                && sameTemplateIdentity(parent.effectiveTemplateFingerprint(), parsed.templateFingerprint());
        if (!exactEffectiveChannel && !correctedLegacyIdentity) {
            throw BusinessException.unprocessable("REVISION_CHANNEL_MISMATCH", "REVISION 必须与父批次属于同一来源渠道");
        }
        return parent;
    }

    private ParsedSourceFile effectiveRevisionSource(ParsedSourceFile parsed, ParentBatch parent) {
        if (parent == null || parent.effectiveSourceChannel().equals(parsed.sourceChannel().name())) {
            return parsed;
        }
        return new ParsedSourceFile(
                SourceChannel.valueOf(parent.effectiveSourceChannel()),
                parent.effectiveTemplateFamily(),
                parsed.templateVersion(),
                parent.effectiveTemplateFingerprint(),
                parsed.csv(),
                parsed.rows());
    }

    private boolean sameTemplateIdentity(String left, String right) {
        return templateSuffix(left).equals(templateSuffix(right));
    }

    private String templateSuffix(String value) {
        int separator = value.indexOf(value.contains("-") ? '-' : '_');
        return separator < 0 ? value : value.substring(separator);
    }

    private String normalizeMode(String importMode, Long parentBatchId) {
        if (!List.of("NEW", "REVISION").contains(importMode)) {
            throw BusinessException.badRequest("IMPORT_MODE_INVALID", "import_mode 必须是 NEW 或 REVISION");
        }
        if (("NEW".equals(importMode) && parentBatchId != null)
                || ("REVISION".equals(importMode) && parentBatchId == null)) {
            throw BusinessException.badRequest("REVISION_PARENT_INVALID", "NEW 不得携带父批次，REVISION 必须携带父批次");
        }
        return importMode;
    }

    private Long existing(ParsedSourceFile parsed, String hash, String mode, Long parentBatchId) {
        List<Long> ids = jdbc.query(
                """
                SELECT ib.id FROM app.import_batches ib
                JOIN app.v_import_batch_effective_source source ON source.import_batch_id=ib.id
                WHERE ib.batch_type='SOURCE_ORDER' AND source.effective_source_channel=?
                  AND ib.content_sha256=? AND ib.import_mode=?
                  AND ib.parent_import_batch_id IS NOT DISTINCT FROM ?
                ORDER BY ib.id LIMIT 1
                """,
                (resultSet, rowNum) -> resultSet.getLong(1),
                parsed.sourceChannel().name(),
                hash,
                mode,
                parentBatchId);
        return ids.isEmpty() ? null : ids.getFirst();
    }

    private Path retain(byte[] bytes, String sha256, String suffix) {
        try {
            Path directory = fileRoot.resolve("source-orders");
            Files.createDirectories(directory);
            Path destination = directory.resolve(sha256 + suffix).normalize();
            if (!destination.startsWith(directory)) {
                throw new IllegalStateException("invalid content-addressed path");
            }
            Path temporary = Files.createTempFile(directory, "upload-", ".part");
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (FileAlreadyExistsException exception) {
                Files.deleteIfExists(temporary);
            }
            return destination;
        } catch (IOException exception) {
            throw new IllegalStateException("无法留存原文件", exception);
        }
    }

    private String safeFilename(String value) {
        if (value == null || value.isBlank()) {
            return "upload.bin";
        }
        String filename = Path.of(value).getFileName().toString();
        return filename.length() > 255 ? filename.substring(filename.length() - 255) : filename;
    }

    private String importErrorCode(String lineExceptionCode) {
        if (lineExceptionCode == null) {
            return null;
        }
        return switch (lineExceptionCode) {
            case "SKU_MAPPING_REQUIRED" -> "SKU_MATCH";
            case "SKU_MAPPING_CONFLICT" -> "JD_CODE_CONFLICT";
            default -> lineExceptionCode;
        };
    }

    private List<String> ids(String sql, long id) {
        return jdbc.query(sql, (resultSet, rowNum) -> resultSet.getString(1), id);
    }

    private String nullableId(Object value) {
        return value == null ? null : value.toString();
    }

    private String json(Object value) {
        try {
            return value == null ? null : objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private Object parseJson(String value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.readValue(value, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String sha256(byte[] value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record ParentBatch(
            String recordedSourceChannel,
            String effectiveSourceChannel,
            String effectiveTemplateFamily,
            String effectiveTemplateFingerprint,
            int revisionNo) {}
    private record RowKey(int sheetIndex, int rowIndex) {}
    private record StaticSourceBundle(long bundleId) {}
    private record StaticBundleComponent(long providerId, BundleComponentInput input) {}
    private record CanonicalizedGroup(CanonicalOrderInput order, List<Integer> partitionCounts) {}
}
