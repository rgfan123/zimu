package cn.zimu.fulfillment.file;

import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.domain.DataScope;
import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.common.domain.SourceChannelDisplayNames;
import cn.zimu.fulfillment.common.dto.PageResponse;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.web.AuthenticationKind;
import cn.zimu.fulfillment.common.web.CommandContext;
import cn.zimu.fulfillment.common.idempotency.IdempotencyService;
import cn.zimu.fulfillment.common.idempotency.IdempotentResult;
import cn.zimu.fulfillment.customer.ImportedCustomerService;
import cn.zimu.fulfillment.fulfillment.ShipmentJdOutboundCommand;
import cn.zimu.fulfillment.fulfillment.ShipmentJdOutboundService;
import cn.zimu.fulfillment.order.SourceBundleResolver;
import cn.zimu.fulfillment.order.domain.LineType;
import cn.zimu.fulfillment.order.domain.SettlementMethod;
import cn.zimu.fulfillment.order.dto.BundleComponentInput;
import cn.zimu.fulfillment.order.dto.CanonicalOrderInput;
import cn.zimu.fulfillment.order.dto.CustomerInput;
import cn.zimu.fulfillment.order.dto.OrderItemInput;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 来源文件编排：先留存原文件/原始行，再通过共用订单应用用例产生 CanonicalOrder。 */
@Service
public class SourceImportService implements cn.zimu.fulfillment.order.SourceBatchConfirmer {

    private static final Logger log = LoggerFactory.getLogger(SourceImportService.class);

    private final SourceFileParser parser;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final AuditLogService auditLogService;
    private final ProviderFileService providerFileService;
    private final ImportedCustomerService importedCustomers;
    private final ShipmentJdOutboundService shipmentJdOutboundService;
    private final ImportRowJdCargoProjectionService jdCargoProjectionService;
    private final SourceBatchSkuReadinessGate sourceBatchSkuReadinessGate;
    private final SourceOrderCandidateMaterializer candidateMaterializer;
    private final SourceTemplateProfileService templateProfiles;
    private final IdempotencyService idempotency;
    private final SourceBatchConfirmReadiness confirmReadiness;
    private final SourceBundleResolver sourceBundleResolver;
    private final Path fileRoot;

    SourceImportService(
            SourceFileParser parser,
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            AuditLogService auditLogService,
            ProviderFileService providerFileService,
            ImportedCustomerService importedCustomers,
            ShipmentJdOutboundService shipmentJdOutboundService,
            ImportRowJdCargoProjectionService jdCargoProjectionService,
            SourceBatchSkuReadinessGate sourceBatchSkuReadinessGate,
            SourceOrderCandidateMaterializer candidateMaterializer,
            SourceTemplateProfileService templateProfiles,
            IdempotencyService idempotency,
            SourceBatchConfirmReadiness confirmReadiness,
            SourceBundleResolver sourceBundleResolver,
            @Value("${app.file-store.root:${java.io.tmpdir}/zimu-fulfillment-files}") String fileRoot) {
        this.parser = parser;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.auditLogService = auditLogService;
        this.providerFileService = providerFileService;
        this.importedCustomers = importedCustomers;
        this.shipmentJdOutboundService = shipmentJdOutboundService;
        this.jdCargoProjectionService = jdCargoProjectionService;
        this.sourceBatchSkuReadinessGate = sourceBatchSkuReadinessGate;
        this.candidateMaterializer = candidateMaterializer;
        this.templateProfiles = templateProfiles;
        this.idempotency = idempotency;
        this.confirmReadiness = confirmReadiness;
        this.sourceBundleResolver = sourceBundleResolver;
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

        // canonicalItems 会读取来源映射/礼包 BOM；从读取开始到成单结束使用同一主数据快照。
        sourceBatchSkuReadinessGate.acquireCatalogSnapshot();
        Map<String, List<ParsedSourceRow>> groups = group(parsed.rows(), batchNo);
        List<SourceOrderCandidate> candidates = new ArrayList<>();
        boolean nonSkuBlocker = parsed.rows().stream().anyMatch(row -> !row.valid());
        for (Map.Entry<String, List<ParsedSourceRow>> entry : groups.entrySet()) {
            List<ParsedSourceRow> group = entry.getValue();
            if (!consistentReceiver(group)) {
                group.forEach(row -> markReview(batchId, row, "IMPORT_VALIDATION", "同一来源订单的收货人快照不一致"));
                nonSkuBlocker = true;
                continue;
            }
            CanonicalizedGroup canonical = canonical(parsed, batchNo, entry.getKey(), group);
            // A12：重复订单（同渠道+来源单号已存在）整组跳过，不进入候选。原始成因见
            // importStructured 的 Javadoc：直接成单时代 DUPLICATE_ORDER 会把整批事务标记
            // rollback-only；候选/放行流水线下，重复单若进入候选，同样会在放行成单事务里
            // 毒化整批确认——预检必须发生在候选构建之前（结构化拉取路径已有同样的预检）。
            if (orderExists(parsed.sourceChannel(), canonical.order().sourceRef())) {
                group.forEach(row -> markRejected(
                        batchId, row, "ORDER_ALREADY_EXISTS", "相同来源渠道与来源单号的订单已存在，本行已跳过（非失败）"));
                continue;
            }
            List<SourceOrderCandidate.CandidateRow> candidateRows = new ArrayList<>();
            for (int index = 0; index < group.size(); index++) {
                ParsedSourceRow row = group.get(index);
                candidateRows.add(new SourceOrderCandidate.CandidateRow(
                        rawIds.get(new RowKey(row.sheetIndex(), row.rowIndex())),
                        canonical.partitionCounts().get(index)));
            }
            candidates.add(new SourceOrderCandidate(
                    entry.getKey(),
                    canonical.order(),
                    candidateRows,
                    "import-" + batchId + "-"
                            + sha256(entry.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                    AuditActorType.AGENT));
        }
        candidates = sourceBatchSkuReadinessGate.snapshotCurrentMappings(parsed.sourceChannel(), candidates);
        // 部分确认（2026-08-28 生产痛点）× 候选放行流水线（SKU 主数据线）的合成语义：
        // 就绪性按【候选=一张来源订单】逐个评估，阻断候选原地留批等补做，就绪候选不陪等。
        // 文件级问题行（解析失败等）各自带着复核标记，不再连坐干净候选。
        stageSplit(parsed.sourceChannel(), batchId, candidates);
        // 兼容已成单/重放路径：若 raw 行已有订单血缘，则补齐 SKU 映射复核事项的 sheet/行号与 raw_import_row_id。
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

    /**
     * 候选粒度的就绪分流：每个候选（=一张来源订单）独立过 SKU 就绪门禁。
     *
     * <p>与整批 requireReady 的差别是失败面：任一候选阻断只标记它自己的行
     * （问题行 SKU_READINESS、同候选兄弟行 BATCH_ATOMIC_RELEASE_BLOCKED），
     * 其余就绪候选保持干净 RECEIVED，等放行事务成单。整批仍共享同一个
     * PENDING 候选快照，补做时 materializer 会重新逐候选评估。
     */
    private void stageSplit(SourceChannel channel, long batchId, List<SourceOrderCandidate> candidates) {
        List<SourceOrderCandidate> ready = new ArrayList<>();
        List<Map<String, Object>> readinessDetails = new ArrayList<>();
        for (SourceOrderCandidate candidate : candidates) {
            BusinessException block = null;
            try {
                sourceBatchSkuReadinessGate.requireReady(channel, List.of(candidate));
            } catch (BusinessException exception) {
                if (!"IMPORT_BATCH_BLOCKED".equals(exception.getBusinessCode())) {
                    throw exception;
                }
                block = exception;
            }
            if (block == null) {
                ready.add(candidate);
            } else {
                markBlockedCandidateRows(batchId, candidate, block);
                readinessDetails.add(Map.of(
                        "candidate_key", candidate.candidateKey(),
                        "details", block.getDetails()));
            }
        }
        for (SourceOrderCandidate candidate : ready) {
            for (SourceOrderCandidate.CandidateRow row : candidate.rows()) {
                jdbc.update(
                        """
                        UPDATE app.raw_import_rows
                        SET error_code=NULL, error_detail=NULL,
                            order_id=NULL, order_line_id=NULL, updated_at=CURRENT_TIMESTAMP
                        WHERE id=? AND import_batch_id=? AND status='RECEIVED'
                        """,
                        row.rawImportRowId(),
                        batchId);
            }
        }
        Map<String, Object> batchDetail = new LinkedHashMap<>();
        batchDetail.put("candidate_status", "PENDING");
        batchDetail.put("candidate_snapshot_version", SourceOrderCandidate.SNAPSHOT_VERSION);
        batchDetail.put("source_order_candidates", candidates);
        if (!readinessDetails.isEmpty()) {
            batchDetail.put("readiness", Map.of("blocked_candidates", readinessDetails));
        }
        jdbc.update(
                "UPDATE app.import_batches SET error_detail=?::jsonb WHERE id=?",
                json(batchDetail),
                batchId);
    }

    /** 阻断候选的行标记：问题行按门禁明细标 SKU_READINESS，其余兄弟行标整单联动阻断。 */
    private void markBlockedCandidateRows(long batchId, SourceOrderCandidate candidate, BusinessException block) {
        Map<Long, List<Map<String, Object>>> detailsByRawId = new LinkedHashMap<>();
        if (block.getDetails().get("lines") instanceof List<?> lines) {
            for (Object value : lines) {
                if (!(value instanceof Map<?, ?> line) || line.get("raw_import_row_id") == null) {
                    continue;
                }
                long rawId = Long.parseLong(line.get("raw_import_row_id").toString());
                Map<String, Object> copy = new LinkedHashMap<>();
                line.forEach((key, item) -> copy.put(String.valueOf(key), item));
                detailsByRawId.computeIfAbsent(rawId, ignored -> new ArrayList<>()).add(Map.copyOf(copy));
            }
        }
        for (SourceOrderCandidate.CandidateRow row : candidate.rows()) {
            List<Map<String, Object>> blockedLines = detailsByRawId.getOrDefault(row.rawImportRowId(), List.of());
            Map<String, Object> detail = blockedLines.isEmpty()
                    ? Map.of(
                            "message", "同一来源订单存在阻断项，候选已保留且未创建正式订单",
                            "candidate_key", candidate.candidateKey())
                    : blockedRowDetail(blockedLines);
            jdbc.update(
                    """
                    UPDATE app.raw_import_rows
                    SET status='NEED_REVIEW', error_code=?, error_detail=?::jsonb,
                        order_id=NULL, order_line_id=NULL, updated_at=CURRENT_TIMESTAMP
                    WHERE id=? AND import_batch_id=? AND status<>'REJECTED'
                    """,
                    blockedLines.isEmpty() ? "BATCH_ATOMIC_RELEASE_BLOCKED" : "SKU_READINESS",
                    json(detail),
                    row.rawImportRowId(),
                    batchId);
            for (Map<String, Object> blocked : blockedLines) {
                createCandidateReviewCase(batchId, row.rawImportRowId(), blocked);
            }
        }
    }

    private void stageCandidates(
            long batchId,
            List<SourceOrderCandidate> candidates,
            BusinessException readinessBlock) {
        Map<Long, List<Map<String, Object>>> detailsByRawId = new LinkedHashMap<>();
        if (readinessBlock != null && readinessBlock.getDetails().get("lines") instanceof List<?> lines) {
            for (Object value : lines) {
                if (!(value instanceof Map<?, ?> line) || line.get("raw_import_row_id") == null) {
                    continue;
                }
                long rawId = Long.parseLong(line.get("raw_import_row_id").toString());
                Map<String, Object> copy = new LinkedHashMap<>();
                line.forEach((key, item) -> copy.put(String.valueOf(key), item));
                detailsByRawId.computeIfAbsent(rawId, ignored -> new ArrayList<>()).add(Map.copyOf(copy));
            }
        }
        for (SourceOrderCandidate candidate : candidates) {
            for (SourceOrderCandidate.CandidateRow row : candidate.rows()) {
                List<Map<String, Object>> blockedLines = detailsByRawId.getOrDefault(
                        row.rawImportRowId(), List.of());
                Map<String, Object> detail = blockedLines.isEmpty()
                        ? Map.of(
                                "message", "同批次存在阻断项，候选已保留且未创建正式订单",
                                "candidate_key", candidate.candidateKey())
                        : blockedRowDetail(blockedLines);
                jdbc.update(
                        """
                        UPDATE app.raw_import_rows
                        SET status='NEED_REVIEW', error_code=?, error_detail=?::jsonb,
                            order_id=NULL, order_line_id=NULL, updated_at=CURRENT_TIMESTAMP
                        WHERE id=? AND import_batch_id=? AND status<>'REJECTED'
                        """,
                        blockedLines.isEmpty() ? "BATCH_ATOMIC_RELEASE_BLOCKED" : "SKU_READINESS",
                        json(detail),
                        row.rawImportRowId(),
                        batchId);
                for (Map<String, Object> blocked : blockedLines) {
                    createCandidateReviewCase(batchId, row.rawImportRowId(), blocked);
                }
            }
        }
        Map<String, Object> batchDetail = new LinkedHashMap<>();
        batchDetail.put("candidate_status", "PENDING");
        batchDetail.put("candidate_snapshot_version", SourceOrderCandidate.SNAPSHOT_VERSION);
        batchDetail.put("source_order_candidates", candidates);
        if (readinessBlock != null) {
            batchDetail.put("readiness", readinessBlock.getDetails());
        }
        jdbc.update(
                "UPDATE app.import_batches SET error_detail=?::jsonb WHERE id=?",
                json(batchDetail),
                batchId);
    }

    /**
     * 解析与 SKU readiness 均通过时也只保存整批候选；正式订单只能由人工确认或
     * AutomaticRelease 在同一个放行事务中创建。原始行保持 RECEIVED，直到正式订单血缘与它
     * 在同一事务中写入；这样也继续满足数据库的“ACCEPTED 必须有订单血缘”不变量。
     */
    private void stageReadyCandidates(long batchId, List<SourceOrderCandidate> candidates) {
        for (SourceOrderCandidate candidate : candidates) {
            for (SourceOrderCandidate.CandidateRow row : candidate.rows()) {
                jdbc.update(
                        """
                        UPDATE app.raw_import_rows
                        SET error_code=NULL, error_detail=NULL,
                            order_id=NULL, order_line_id=NULL, updated_at=CURRENT_TIMESTAMP
                        WHERE id=? AND import_batch_id=? AND status='RECEIVED'
                        """,
                        row.rawImportRowId(),
                        batchId);
            }
        }
        jdbc.update(
                "UPDATE app.import_batches SET error_detail=?::jsonb WHERE id=?",
                json(Map.of(
                        "candidate_status", "PENDING",
                        "candidate_snapshot_version", SourceOrderCandidate.SNAPSHOT_VERSION,
                        "source_order_candidates", candidates)),
                batchId);
    }

    private Map<String, Object> blockedRowDetail(List<Map<String, Object>> blockedLines) {
        if (blockedLines.size() == 1) {
            return blockedLines.getFirst();
        }
        LinkedHashSet<String> reasons = new LinkedHashSet<>();
        for (Map<String, Object> blocked : blockedLines) {
            if (blocked.get("reason_codes") instanceof List<?> values) {
                values.forEach(value -> reasons.add(String.valueOf(value)));
            }
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("ready", false);
        detail.put("reason_codes", List.copyOf(reasons));
        detail.put("blocking_lines", List.copyOf(blockedLines));
        return detail;
    }

    private void createCandidateReviewCase(
            long batchId, long rawImportRowId, Map<String, Object> blocked) {
        List<String> reasons = blocked.get("reason_codes") instanceof List<?> values
                ? values.stream().map(String::valueOf).toList()
                : List.of();
        if (reasons.isEmpty()) {
            return;
        }
        String reason = switch (reasons.getFirst()) {
            case "SOURCE_SKU_MAPPING_REQUIRED" -> blocked.get("source_sku_ref") == null
                    ? "SOURCE_SKU_MAPPING_REQUIRED"
                    : "SKU_MAPPING_REQUIRED";
            default -> reasons.getFirst();
        };
        Map<String, Object> detail = new LinkedHashMap<>(blocked);
        Map<String, Object> source = jdbc.queryForMap(
                """
                SELECT ib.source_channel, rir.sheet_name, rir.row_index, rir.raw_cells::text raw_cells
                FROM app.raw_import_rows rir
                JOIN app.import_batches ib ON ib.id=rir.import_batch_id
                WHERE rir.id=? AND rir.import_batch_id=?
                """,
                rawImportRowId,
                batchId);
        detail.put("source_channel", blocked.getOrDefault("source_channel", source.get("source_channel")));
        detail.put("source_sheet_name", source.get("sheet_name"));
        detail.put("source_row_index", source.get("row_index"));
        SourceChannel channel = SourceChannel.valueOf(source.get("source_channel").toString());
        Map<String, String> projection = projectionFor(channel, source.get("raw_cells").toString());
        copyIfPresent(detail, "source_sku_ref", projection.get("source_sku_ref"));
        copyIfPresent(detail, "source_product_name", projection.get("product_name"));
        copyIfPresent(detail, "source_specification", projection.get("specification"));
        copyIfPresent(detail, "source_quantity", projection.get("quantity"));
        jdbc.update(
                """
                INSERT INTO app.review_cases
                    (case_no, case_type, status, responsible_team, reason_code,
                     import_batch_id, raw_import_row_id, detail)
                VALUES (?, 'SOURCE_ORDER_CANDIDATE', 'OPEN', 'SKU_OPS', ?, ?, ?, ?::jsonb)
                ON CONFLICT (case_no) DO NOTHING
                """,
                candidateReviewCaseNo(rawImportRowId, blocked, reason),
                reason,
                batchId,
                rawImportRowId,
                json(detail));
    }

    private String candidateReviewCaseNo(
            long rawImportRowId, Map<String, Object> blocked, String reason) {
        String identity = blocked.getOrDefault("line_no", "") + "|"
                + blocked.getOrDefault("sku_id", "") + "|"
                + blocked.getOrDefault("source_sku_ref", "") + "|" + reason;
        String suffix = sha256(identity.getBytes(java.nio.charset.StandardCharsets.UTF_8)).substring(0, 12);
        return "RC-IMPORT-CANDIDATE-" + rawImportRowId + "-" + suffix;
    }

    private void copyIfPresent(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.putIfAbsent(key, value);
        }
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
     * 内部按原始 items 写 raw 行（一行 = 一个来源商品；混合履约礼包可关联多个 partition order line，
     * 与文件导入的行语义一致）。重复订单（DUPLICATE_ORDER）整单跳过：不写 raw 行、记审计，
     * 不整批回滚——confirm 的 uncovered 检查只统计 ACCEPTED 且有导出/发货关联的行，
     * 跳过行不落库即不产生阻断。内容哈希幂等——同渠道同内容先取得事务级 advisory lock，
     * 后到请求在前一事务提交后重查并返回既有批次。</p>
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
        lockStructuredContent(channel, contentSha);
        Long existingId = existingStructured(channel, contentSha);
        if (existingId != null) {
            return get(existingId);
        }
        return doImportStructured(channel, orders, batchNo, contentSha, context, started);
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
        boolean nonSkuBlocker = false;
        List<SourceOrderCandidate> candidates = new ArrayList<>();
        for (int orderIndex = 0; orderIndex < orders.size(); orderIndex++) {
            StructuredOrderRow order = orders.get(orderIndex);
            Objects.requireNonNull(order.canonicalInput(), "结构化订单缺少 canonical 输入: " + order.sourceRef());
            List<OrderItemInput> items = order.canonicalInput().items();
            if (order.reviewRequired() != null) {
                nonSkuBlocker = true;
                StructuredOrderRow.ReviewRequired review = order.reviewRequired();
                // 商品行全部不可用时仍保留一条订单级原始证据；item_index=0 只表示
                // 复核占位，不代表已生成或猜测出任何商品行。
                int reviewRowCount = Math.max(1, items.size());
                for (int itemIndex = 0; itemIndex < reviewRowCount; itemIndex++) {
                    rowIndex++;
                    insertStructuredRow(
                            batchId,
                            rowIndex,
                            order,
                            itemIndex,
                            "NEED_REVIEW",
                            null,
                            null,
                            review.code(),
                            json(Map.of("message", review.message())));
                }
                continue;
            }
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
            // 礼包判定接缝（与文件导入同一处）：transform 产物恒为 SINGLE，礼包行必须在候选
            // 构建时就被重判成 CUSTOM_BUNDLE——SKU 就绪门禁与放行成单看到的必须是拆解后的
            // 真实组件行，分片数也由此而来（一个礼包行可对应多个订单行）。客户解析刻意不在
            // 这里做：候选保持 CONTACT-* 原样，放行时由 SourceOrderCandidateMaterializer
            // 统一解析并做身份漂移守卫（成单时点的映射比导入时点新鲜）。
            ResolvedStructuredItems resolvedItems = resolveStructuredItems(channel, items);
            items = resolvedItems.items();
            CanonicalOrderInput canonicalInput = withItems(order.canonicalInput(), items);
            List<SourceOrderCandidate.CandidateRow> candidateRows = new ArrayList<>();
            for (int itemIndex = 0; itemIndex < resolvedItems.partitionCounts().size(); itemIndex++) {
                rowIndex++;
                long rawId = insertStructuredRow(
                        batchId, rowIndex, order, itemIndex, "RECEIVED", null, null, null, null);
                candidateRows.add(new SourceOrderCandidate.CandidateRow(
                        rawId, resolvedItems.partitionCounts().get(itemIndex)));
            }
            candidates.add(new SourceOrderCandidate(
                    order.sourceRef(),
                    canonicalInput,
                    candidateRows,
                    "pull-" + batchNo + "-" + orderIndex,
                    AuditActorType.SYSTEM));
        }
        candidates = sourceBatchSkuReadinessGate.snapshotCurrentMappings(channel, candidates);
        // 与文件导入同一条部分化规矩：候选粒度评估就绪性，阻断候选留批，就绪候选先行。
        stageSplit(channel, batchId, candidates);
        // 兼容已成单/重放路径：若 raw 行已有订单血缘，则补齐 SKU 映射复核事项的 sheet/行号与 raw_import_row_id。
        enrichReviewCasesWithSourceRow(batchId);

        // 3) 批次收尾：状态与审计口径与文件导入一致
        return finalizeBatch(batchId, started, context, AuditActorType.SYSTEM,
                "source-order-structured-import", "source-orders.importStructured",
                Map.of("batch_no", batchNo, "content_sha256", contentSha));
    }

    /** 礼包判定后的商品行回填进 canonical 输入；除 items 外逐字段原样保留。 */
    private CanonicalOrderInput withItems(CanonicalOrderInput input, List<OrderItemInput> items) {
        return new CanonicalOrderInput(
                input.source(),
                input.sourceRef(),
                input.sourceVersion(),
                input.customer(),
                input.receiver(),
                items,
                input.settlement(),
                input.sourceOrderedAt(),
                input.remark(),
                input.evidenceRefs());
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

    /**
     * 结构化 raw 行写入：一行固定对应原始 item_index。候选/放行流水线下，导入期只落
     * RECEIVED 行；订单行血缘（含礼包一行多分片）由放行时的 SourceOrderCandidateMaterializer
     * 按候选分片数回填 raw_import_row_order_lines。
     */
    private long insertStructuredRow(
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
        return rawId;
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

    /**
     * 串行化同一唯一键范围内的结构化导入。
     *
     * <p>PostgreSQL 唯一约束冲突会把当前事务置为 aborted，不能在 catch 后继续重查。
     * transaction advisory lock 随事务提交/回滚自动释放；READ COMMITTED 下，等待者取得锁后的
     * 下一条查询能看到前一事务刚提交的批次。</p>
     */
    private void lockStructuredContent(SourceChannel channel, String contentSha) {
        String lockScope = "source-order-structured:" + channel.name() + ":" + contentSha;
        jdbc.query(
                "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                statement -> statement.setString(1, lockScope),
                resultSet -> {
                    // pg_advisory_xact_lock 返回 void；执行并消费结果行即可。
                });
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
            if (order.reviewRequired() != null) {
                entry.put("review_required", order.reviewRequired());
            }
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
        if (order.canonicalInput() != null
                && itemIndex >= 0
                && itemIndex < order.canonicalInput().items().size()) {
            String sourceSkuRef = order.canonicalInput().items().get(itemIndex).sourceSkuRef();
            if (sourceSkuRef != null && !sourceSkuRef.isBlank()) {
                cells.put("source_sku_ref", sourceSkuRef);
            }
        }
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
                    value.put("error_detail", publicBatchErrorDetail(resultSet.getString("error_detail")));
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
        // 确认闸门的判据随批次一起返回：前端按钮可用性直接读它，不再自己按 row_counts 推算，
        // 否则两边口径一旦分叉，用户就会点了才发现被拒。仅来源订单批次有确认语义。
        if ("SOURCE_ORDER".equals(result.get("batch_type"))) {
            result.put("confirm_readiness", confirmReadiness.of(batchId).toPayload());
        }
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
        // 来源订单创建时间：渠道平台的真实下单时刻，与上面 settlementAt（历史口径，缺失时借用
        // 导入时刻/current time 兜底）分开——这里没有兜底，来源没给就如实为 null。
        Instant sourceOrderedAt = rows.stream().map(ParsedSourceRow::orderedAt).filter(Objects::nonNull)
                .min(Comparator.naturalOrder()).orElse(null);
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
                sourceOrderedAt,
                first.remark(),
                rows.stream().map(row -> "import://" + batchNo + "/" + row.sheetIndex() + "/" + row.rowIndex()).toList());
        return new CanonicalizedGroup(order, List.copyOf(partitionCounts));
    }

    /**
     * 文件行的礼包判定：全部交给共用接缝 {@link SourceBundleResolver}，与 API 拉单、人工
     * resolve-bundle 用同一把键、同一个判定顺序（礼包映射 → SKU 映射 → 名字启发式）。
     *
     * <p>显式命中权威礼包后，组件按所属履约方稳定分组为多个同质订单行。组件身份使用内部
     * sku_code 快照；EMG 是京东履约编码，不能冒充来源渠道 SKU 映射。
     *
     * <p>本方法只负责把判定结果贴回「文件行」这个形状；查库逻辑一处都不许再长在这里，
     * 否则三条路径又会各自漂移——这正是本次统一要根除的病。
     */
    private List<OrderItemInput> canonicalItems(SourceChannel channel, ParsedSourceRow row) {
        SourceBundleResolver.Decision decision =
                sourceBundleResolver.decide(channel, row.sourceSkuRef(), row.productName());
        if (decision.kind() != SourceBundleResolver.Kind.SINGLE) {
            // 三条路径同一判据（V99）：礼包行数量必须为正整数，拒绝而不是降级或静默取整。
            SourceBundleResolver.requireIntegerQuantityForBundle(row.quantity());
        }
        return switch (decision.kind()) {
            case STATIC_BUNDLE -> decision.componentGroups().stream()
                    .map(providerComponents -> new OrderItemInput(
                            row.sourceLineRef(),
                            LineType.CUSTOM_BUNDLE,
                            null,
                            row.sourceSkuRef(),
                            row.productName(),
                            row.specification(),
                            row.unit(),
                            quantity(row),
                            Long.toString(decision.bundleId()),
                            providerComponents))
                    .toList();
            case UNRESOLVED_BUNDLE -> List.of(unresolvedBundleItem(row));
            case SINGLE -> List.of(singleItem(row));
        };
    }

    /**
     * 结构化（API 拉单）商品行的礼包判定：与文件行走同一个接缝，因此同一个商品两条链路同结果。
     *
     * <p><b>为什么必须在这里做</b>：改造前拉单是直通的——transform 产物恒为
     * {@code LineType.SINGLE}（如 {@code JufubaoOrderTransform}），礼包商品找不到 SKU 映射就落
     * SKU_MAPPING_REQUIRED，而 {@code resolve-bundle} 只受理 CUSTOM_BUNDLE 行，于是拉进来的
     * 礼包行「进不来也修不了」，是死行。2026-08-28 那次只把文件链路的白名单放开了，
     * 结构化链路的同一个死锁没修。
     *
     * <p>只改写「连接器自己没下结论」的行：仍是 SINGLE、且没带组件清单的行才重判。连接器
     * 已显式构造的礼包行（带 components / 非 SINGLE）保持原样——那是来源侧的权威结论，
     * 不该被这里的启发式覆盖。
     */
    private ResolvedStructuredItems resolveStructuredItems(SourceChannel channel, List<OrderItemInput> items) {
        List<OrderItemInput> resolved = new ArrayList<>(items.size());
        List<Integer> partitionCounts = new ArrayList<>(items.size());
        for (OrderItemInput item : items) {
            List<OrderItemInput> partitions = resolveStructuredItem(channel, item);
            resolved.addAll(partitions);
            partitionCounts.add(partitions.size());
        }
        return new ResolvedStructuredItems(List.copyOf(resolved), List.copyOf(partitionCounts));
    }

    private List<OrderItemInput> resolveStructuredItem(SourceChannel channel, OrderItemInput item) {
        boolean connectorAlreadyDecided =
                item.lineType() != LineType.SINGLE || (item.components() != null && !item.components().isEmpty());
        if (connectorAlreadyDecided) {
            return List.of(item);
        }
        SourceBundleResolver.Decision decision =
                sourceBundleResolver.decide(channel, item.sourceSkuRef(), item.productName());
        if (decision.kind() != SourceBundleResolver.Kind.SINGLE) {
            // 三条路径同一判据（V99）：礼包行数量必须为正整数，拒绝而不是降级或静默取整。
            SourceBundleResolver.requireIntegerQuantityForBundle(item.quantity());
        }
        return switch (decision.kind()) {
            case STATIC_BUNDLE -> decision.componentGroups().stream()
                    .map(providerComponents -> new OrderItemInput(
                            item.sourceLineRef(),
                            LineType.CUSTOM_BUNDLE,
                            null,
                            item.sourceSkuRef(),
                            item.productName(),
                            item.specification(),
                            item.unit(),
                            item.quantity(),
                            Long.toString(decision.bundleId()),
                            providerComponents))
                    .toList();
            case UNRESOLVED_BUNDLE -> List.of(unresolvedBundleItem(item));
            case SINGLE -> List.of(item);
        };
    }

    /**
     * 名称明确表示礼包/组合但未命中 ACTIVE 主数据时，构造一个必然未映射的组件候选，
     * 复用订单应用层 SKU_MAPPING_REQUIRED 分支进入人工复核；禁止降级 SINGLE 后误命中普通 SKU。
     *
     * <p>落成 CUSTOM_BUNDLE 是 {@code resolve-bundle} 唯一受理的形状——这一步就是给运营留门。
     */
    private OrderItemInput unresolvedBundleItem(ParsedSourceRow row) {
        return unresolvedBundleItem(
                row.sourceLineRef(),
                row.sourceSkuRef(),
                row.productName(),
                row.specification(),
                row.unit(),
                quantity(row));
    }

    private OrderItemInput unresolvedBundleItem(OrderItemInput item) {
        return unresolvedBundleItem(
                item.sourceLineRef(),
                item.sourceSkuRef(),
                item.productName(),
                item.specification(),
                item.unit(),
                item.quantity());
    }

    private OrderItemInput unresolvedBundleItem(
            String sourceLineRef,
            String sourceSkuRef,
            String productName,
            String specification,
            String unit,
            String quantity) {
        String ref = "__BUNDLE_MAPPING_REQUIRED__:" + sourceSkuRef;
        BundleComponentInput unresolved =
                new BundleComponentInput(null, ref, productName, specification, unit, "1");
        return new OrderItemInput(
                sourceLineRef,
                LineType.CUSTOM_BUNDLE,
                null,
                sourceSkuRef,
                productName,
                specification,
                unit,
                quantity,
                List.of(unresolved));
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
    @Override
    public IdempotentResult<Map<String, Object>> confirmSourceBatch(
            long sourceBatchId, String idempotencyKey, cn.zimu.fulfillment.common.web.CommandContext context) {
        return confirm(sourceBatchId, null, idempotencyKey, context);
    }

    @Override
    @Transactional
    public IdempotentResult<Map<String, Object>> confirmTrustedSourceBatch(
            long sourceBatchId,
            long templateProfileId,
            String idempotencyKey,
            cn.zimu.fulfillment.common.web.CommandContext context) {
        return confirm(sourceBatchId, templateProfileId, idempotencyKey, context);
    }

    @Override
    public Map<String, Object> submitJdOutboundsForSourceBatch(
            long sourceBatchId, cn.zimu.fulfillment.common.web.CommandContext context) {
        return submitJdOutboundsForBatch(sourceBatchId, context);
    }

    IdempotentResult<Map<String, Object>> confirm(
            long batchId, String idempotencyKey, CommandContext context) {
        return confirm(batchId, null, idempotencyKey, context);
    }

    private IdempotentResult<Map<String, Object>> confirm(
            long batchId, Long templateProfileId, String idempotencyKey, CommandContext context) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("batch_id", batchId);
        if (templateProfileId != null) {
            payload.put("template_profile_id", templateProfileId);
        }
        return idempotency.execute("source_import.confirm", idempotencyKey, payload, 200, () -> {
            SourceTemplateProfileService.TrustedTemplate automaticProfile = templateProfileId == null
                    ? null
                    : templateProfiles.requireTrustedBatchMatchForRelease(templateProfileId, batchId);
            // 覆盖候选成单、Provider 校验和最终路由，避免阶段之间穿插主数据写入。
            sourceBatchSkuReadinessGate.acquireCatalogSnapshot();
            // 所有上传都只保存整批候选；确认事务内先复核再成单，后续任一门禁失败时
            // 整个事务回滚，不能留下 CanonicalOrder/Fulfillment 半成品。
            candidateMaterializer.materializeStaged(batchId, context);
            providerFileService.validateSourceBatchExportability(batchId);
            Map<String, Object> batch = jdbc.queryForMap(
                    "SELECT batch_type, confirmed_at FROM app.import_batches WHERE id=? FOR UPDATE",
                    batchId);
            if (!"SOURCE_ORDER".equals(batch.get("batch_type"))) {
                throw BusinessException.unprocessable("IMPORT_BATCH_TYPE_INVALID", "仅来源订单批次可以确认");
            }
            // 部分确认：跳过阻断行，先把能发的发出去。
            //
            // 旧闸门是全有或全无——一行有问题整批不能确认。2026-08-28 生产实例：一批 5 行里
            // 4 张就绪新单被 1 行挡住。良性重复（ORDER_ALREADY_EXISTS）此前已单独豁免，但
            // NEED_REVIEW / 缺 SKU 映射等仍会整批卡死，而这些行的修复往往要等外部信息，
            // 就绪的货没有理由陪着一起等。
            //
            // 与「整批原子放行」（SKU 主数据线，2026-08-31 合并）的分工：候选 → 正式订单的
            // 转换在上方 materializeStaged 里保持整批原子 + SKU 就绪门禁（staged 批次的
            // 就绪校验发生在那一步，不在这里重复）；本段管的是成单之后的发货路由，按行部分
            // 推进。原子性属于订单创建，部分性属于发货节奏，两者不冲突。
            //
            // 现在的判据只问一件事：有没有「已接收但还没进履约导出/发货批次」的行。有就干活，
            // 阻断行原地留在批次里（状态不变、复核事项不变），修好后再次确认即可补做——
            // ProviderFileService#candidateRows 本身就排除已导出行，所以重复确认只会捡起新就绪的行。
            SourceBatchConfirmReadiness.Readiness readiness = confirmReadiness.of(batchId);
            if (!readiness.confirmable() && readiness.blockedRows() > 0) {
                // 一行都发不了、却还有待处理行：和从前一样拒绝，让人先去处理。
                throw BusinessException.conflict("IMPORT_BATCH_BLOCKED", "批次仍有待处理的 SKU、文件或数据问题");
            }
            // 没有待发货行也没有阻断行（空批次、或已全部确认过）时放行，走完下面的空转。
            // 与从前的差别要说清楚：旧代码用 confirmed_at==null 把整段路由都跳过，已确认批次
            // 再点一次是彻底的空操作；现在为了支持补做不能再按 confirmed_at 分叉，于是路由照跑，
            // 但 ProviderFileService#candidateRows 排除已导出行，跑出来是空集——结果同样是空操作，
            // 只是空在更下游。confirmed_at 也只在首次确认时写（见下方），补做不会改写它。
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
            if (batch.get("confirmed_at") == null) {
                // 可信模板的自动放行授权按「首次确认」消费一次；补做不重复消费。
                if (automaticProfile != null) {
                    templateProfiles.recordConsumedAuthorization(batchId, automaticProfile);
                }
                jdbc.update(
                        "UPDATE app.import_batches SET confirmed_at=CURRENT_TIMESTAMP, confirmed_by=? WHERE id=?",
                        context.operator(),
                        batchId);
            }
            // 补做时 confirmed_at 保持首次确认时间：它记录的是「这批开始发货」的时刻，
            // 不是最后一次补做的时刻。补做痕迹由审计日志与导出记录承载。
            return confirmResult(batchId, List.copyOf(sdkShipments), confirmReadiness.of(batchId), context, payload);
        });
    }

    /**
     * 组装确认响应并留审计痕迹。
     *
     * <p>响应里带上本次被跳过的阻断行：用户点了确认必须当场知道哪些行没发出去、为什么，
     * 以及它们还留在批次里等补做——否则「部分确认」就成了静默丢单。
     */
    private Map<String, Object> confirmResult(
            long batchId,
            List<Long> jdSdkShipmentIds,
            SourceBatchConfirmReadiness.Readiness readiness,
            CommandContext context,
            Map<String, Object> payload) {
        Map<String, Object> result = get(batchId);
        // 京东 SDK 路由的批次由控制器在确认事务提交后触发批量建单（见
        // SourceImportController.confirm）；失败留痕（SYNC_FAILED/告警/复核）不阻断批次确认。
        if (!jdSdkShipmentIds.isEmpty()) {
            result.put("outbound_routing", Map.of(
                    "jd_sdk_shipment_ids", jdSdkShipmentIds.stream().map(String::valueOf).toList()));
        }
        result.put("skipped_rows", readiness.blockers().stream()
                .map(SourceBatchConfirmReadiness.BlockedRow::toPayload)
                .toList());
        auditLogService.record(new AuditLogService.AuditCommand()
                .dataScope(DataScope.BUSINESS)
                .requestId(context.requestId())
                .traceId(context.traceId())
                .operator(context.operator())
                // AutomaticRelease 走内部服务身份触发同一个确认入口：审计必须忠实记录为系统动作。
                .actorType(context.authenticationKind() == AuthenticationKind.INTERNAL_SERVICE
                        ? AuditActorType.SYSTEM
                        : AuditActorType.HUMAN)
                .service("source-file-import")
                .operation("source-orders.confirm")
                .requestPayload(payload)
                .responsePayload(result)
                .httpStatus(200)
                .businessCode(readiness.blockedRows() > 0
                        ? "IMPORT_BATCH_PARTIALLY_CONFIRMED"
                        : "IMPORT_BATCH_CONFIRMED")
                .latencyMs(0));
        return result;
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
        result.put("failed_count", items.stream().filter(item -> item.containsKey("business_code")).count());
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
     * A12：与 {@link #markReview} 同形，但落 REJECTED 而非 NEED_REVIEW——重复订单是已经
     * 做出的确定性判断（无需人工复核决定），语义与既有 REJECTED 行状态词汇一致
     * （见 {@link #rows}/{@link #counts} 已识别的 RECEIVED/ACCEPTED/NEED_REVIEW/REJECTED 四态）。
     */
    private void markRejected(long batchId, ParsedSourceRow row, String code, String message) {
        jdbc.update(
                """
                UPDATE app.raw_import_rows SET status='REJECTED', error_code=?, error_detail=?::jsonb,
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

    /** 候选 CanonicalOrderInput 含收货快照，只在服务端重放，不通过批次读取 API 暴露。 */
    private Object publicBatchErrorDetail(String value) {
        Object parsed = parseJson(value);
        if (!(parsed instanceof Map<?, ?> source)) {
            return parsed;
        }
        Map<String, Object> safe = new LinkedHashMap<>();
        source.forEach((key, item) -> {
            String name = String.valueOf(key);
            if (!"source_order_candidates".equals(name)
                    && !"source_order_readiness_candidates".equals(name)
                    && !"candidate_status".equals(name)
                    && !"candidate_snapshot_version".equals(name)
                    && !"automatic_release".equals(name)) {
                safe.put(String.valueOf(key), item);
            }
        });
        return safe.isEmpty() ? null : safe;
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
    private record CanonicalizedGroup(CanonicalOrderInput order, List<Integer> partitionCounts) {}

    /** 结构化来源商品与展开后订单行的稳定血缘：partitionCounts 的下标就是原始 item_index。 */
    private record ResolvedStructuredItems(List<OrderItemInput> items, List<Integer> partitionCounts) {}

    /** 订单行异常聚合后的 raw 行状态；errorDetail 已序列化，可直接传给 jsonb 参数。 */
}
