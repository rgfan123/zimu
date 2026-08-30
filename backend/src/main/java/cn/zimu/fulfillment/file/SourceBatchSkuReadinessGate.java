package cn.zimu.fulfillment.file;

import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.sku.Sku;
import cn.zimu.fulfillment.sku.SkuFulfillmentReadiness;
import cn.zimu.fulfillment.sku.SkuFulfillmentReadinessService;
import cn.zimu.fulfillment.sku.SkuRepository;
import cn.zimu.fulfillment.sku.SkuReadinessCatalogLock;
import cn.zimu.fulfillment.sku.SourceChannelSku;
import cn.zimu.fulfillment.sku.SourceChannelSkuRepository;
import cn.zimu.fulfillment.order.domain.LineType;
import cn.zimu.fulfillment.order.dto.OrderItemInput;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 来源批次放行前的共享 SKU readiness 门禁；人工确认与非 HTTP 自动放行调用同一接缝。 */
@Service
class SourceBatchSkuReadinessGate {

    private final JdbcTemplate jdbc;
    private final SkuRepository skus;
    private final SourceChannelSkuRepository sourceMappings;
    private final SkuFulfillmentReadinessService readiness;
    private final SkuReadinessCatalogLock catalogLock;
    private final SourceFileParser parser;
    private final ObjectMapper objectMapper;

    SourceBatchSkuReadinessGate(
            JdbcTemplate jdbc,
            SkuRepository skus,
            SourceChannelSkuRepository sourceMappings,
            SkuFulfillmentReadinessService readiness,
            SkuReadinessCatalogLock catalogLock,
            SourceFileParser parser,
            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.skus = skus;
        this.sourceMappings = sourceMappings;
        this.readiness = readiness;
        this.catalogLock = catalogLock;
        this.parser = parser;
        this.objectMapper = objectMapper;
    }

    /** 在调用方事务结束前冻结全部 readiness 主数据写入，覆盖候选成单到最终放行的完整窗口。 */
    void acquireCatalogSnapshot() {
        catalogLock.acquireShared();
    }

    void requireReady(long sourceBatchId) {
        catalogLock.acquireShared();
        List<BatchLine> lines = lines(sourceBatchId);
        if (lines.isEmpty()) {
            return;
        }

        List<Long> skuIds = lines.stream().map(BatchLine::skuId).distinct().toList();
        Map<Long, Sku> skuById = new LinkedHashMap<>();
        skus.findAllById(skuIds).forEach(sku -> skuById.put(sku.getId(), sku));
        Map<Long, SkuFulfillmentReadiness> readinessBySku = readiness.evaluateAll(skuById.values());
        Map<String, SourceChannelSku> mappingByIdentity = mappings(lines);

        List<Map<String, Object>> blockedLines = new ArrayList<>();
        for (BatchLine line : lines) {
            SkuFulfillmentReadiness result = readinessBySku.get(line.skuId());
            List<Map<String, String>> mappingIssues = mappingIssues(line, mappingByIdentity);
            if (result != null && result.ready() && mappingIssues.isEmpty()) {
                continue;
            }
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("raw_import_row_id", Long.toString(line.rawImportRowId()));
            detail.put("order_id", Long.toString(line.orderId()));
            detail.put("order_line_id", Long.toString(line.orderLineId()));
            detail.put("line_no", line.lineNo());
            detail.put("sku_id", Long.toString(line.skuId()));
            detail.put("sku_code", line.skuCode());
            if (line.sourceSkuRef() != null) {
                detail.put("source_channel", line.mappingChannel().name());
                detail.put("source_sku_ref", line.sourceSkuRef());
            }
            List<String> reasons = new ArrayList<>();
            List<Map<String, String>> issues = new ArrayList<>();
            for (Map<String, String> issue : mappingIssues) {
                if (!reasons.contains(issue.get("code"))) {
                    reasons.add(issue.get("code"));
                    issues.add(issue);
                }
            }
            if (result != null) {
                for (SkuFulfillmentReadiness.SkuReadinessIssue issue : result.issues()) {
                    if (!reasons.contains(issue.code())) {
                        reasons.add(issue.code());
                        issues.add(Map.of(
                                "code", issue.code(),
                                "message", issue.message(),
                                "action", issue.action()));
                    }
                }
            }
            detail.put("ready", false);
            detail.put("reason_codes", List.copyOf(reasons));
            detail.put("issues", List.copyOf(issues));
            blockedLines.add(Map.copyOf(detail));
        }
        throwIfBlocked(blockedLines);
    }

    /** 在任何 CanonicalOrder/Fulfillment 写入前，对整批来源候选执行同一 readiness 判定。 */
    void requireReady(SourceChannel mappingChannel, List<SourceOrderCandidate> candidates) {
        requireCandidateLinesReady(mappingChannel, candidateLines(candidates));
    }

    /** 已成单但尚未确认的批次按最小、无 PII 的候选快照重新执行相同门禁。 */
    void requireReadySnapshot(
            SourceChannel mappingChannel, List<SourceOrderReadinessCandidate> candidates) {
        requireCandidateLinesReady(mappingChannel, readinessCandidateLines(candidates));
    }

    private void requireCandidateLinesReady(
            SourceChannel mappingChannel, List<CandidateLine> lines) {
        catalogLock.acquireShared();
        if (lines.isEmpty()) {
            return;
        }
        Set<String> refs = lines.stream()
                .map(CandidateLine::sourceSkuRef)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Map<String, SourceChannelSku> mappingByRef = new LinkedHashMap<>();
        if (!refs.isEmpty()) {
            sourceMappings.findAllBySourceChannelAndSourceSkuRefIn(mappingChannel, refs)
                    .forEach(mapping -> mappingByRef.put(mapping.getSourceSkuRef(), mapping));
        }
        Map<String, Sku> skuByCode = new LinkedHashMap<>();
        lines.stream()
                .map(CandidateLine::skuCode)
                .filter(Objects::nonNull)
                .filter(code -> !code.isBlank())
                .distinct()
                .forEach(code -> skus.findBySkuCode(code).ifPresent(sku -> skuByCode.put(code, sku)));
        List<Long> skuIds = java.util.stream.Stream.concat(
                        mappingByRef.values().stream().map(SourceChannelSku::getSkuId),
                        skuByCode.values().stream().map(Sku::getId))
                .distinct()
                .toList();
        Map<Long, Sku> skuById = new LinkedHashMap<>();
        skus.findAllById(skuIds).forEach(sku -> skuById.put(sku.getId(), sku));
        Map<Long, SkuFulfillmentReadiness> readinessBySku = readiness.evaluateAll(skuById.values());

        List<Map<String, Object>> blockedLines = new ArrayList<>();
        for (CandidateLine line : lines) {
            SourceChannelSku mapping = line.sourceSkuRef() == null
                    ? null
                    : mappingByRef.get(line.sourceSkuRef());
            Sku sku = line.directInternalSku()
                    ? skuByCode.get(line.skuCode())
                    : mapping == null ? null : skuById.get(mapping.getSkuId());
            List<Map<String, String>> issues = candidateMappingIssues(line, mapping, sku);
            SkuFulfillmentReadiness result = sku == null ? null : readinessBySku.get(sku.getId());
            if (issues.isEmpty() && result != null && result.ready()) {
                continue;
            }
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("raw_import_row_id", Long.toString(line.rawImportRowId()));
            detail.put("candidate_key", line.candidateKey());
            detail.put("line_no", line.lineNo());
            detail.put("source_channel", mappingChannel.name());
            if (line.sourceSkuRef() != null) {
                detail.put("source_sku_ref", line.sourceSkuRef());
            }
            if (sku != null) {
                detail.put("sku_id", Long.toString(sku.getId()));
                detail.put("sku_code", sku.getSkuCode());
            }
            List<String> reasons = new ArrayList<>();
            List<Map<String, String>> actionable = new ArrayList<>();
            appendIssues(issues, reasons, actionable);
            if (result != null) {
                for (SkuFulfillmentReadiness.SkuReadinessIssue issue : result.issues()) {
                    if (!reasons.contains(issue.code())) {
                        reasons.add(issue.code());
                        actionable.add(Map.of(
                                "code", issue.code(),
                                "message", issue.message(),
                                "action", issue.action()));
                    }
                }
            }
            detail.put("ready", false);
            detail.put("reason_codes", List.copyOf(reasons));
            detail.put("issues", List.copyOf(actionable));
            blockedLines.add(Map.copyOf(detail));
        }
        throwIfBlocked(blockedLines);
    }

    private List<CandidateLine> candidateLines(List<SourceOrderCandidate> candidates) {
        return readinessCandidateLines(candidates.stream()
                .map(SourceOrderReadinessCandidate::from)
                .toList());
    }

    private List<CandidateLine> readinessCandidateLines(
            List<SourceOrderReadinessCandidate> candidates) {
        List<CandidateLine> lines = new ArrayList<>();
        for (SourceOrderReadinessCandidate candidate : candidates) {
            int itemCursor = 0;
            int lineNo = 1;
            for (SourceOrderReadinessCandidate.CandidateRow row : candidate.rows()) {
                for (int partition = 0; partition < row.partitionCount(); partition++) {
                    if (itemCursor >= candidate.items().size()) {
                        throw new IllegalStateException("来源订单候选的原始行分片超过商品行数量");
                    }
                    OrderItemInput item = candidate.items().get(itemCursor++);
                    if (item.lineType() == LineType.SINGLE) {
                        lines.add(new CandidateLine(
                                row.rawImportRowId(),
                                candidate.candidateKey(),
                                lineNo,
                                item.sourceSkuRef(),
                                item.skuCode()));
                    } else if (item.components() == null || item.components().isEmpty()) {
                        lines.add(new CandidateLine(
                                row.rawImportRowId(), candidate.candidateKey(), lineNo, null, null));
                    } else {
                        int candidateLineNo = lineNo;
                        item.components().forEach(component -> lines.add(new CandidateLine(
                                row.rawImportRowId(),
                                candidate.candidateKey(),
                                candidateLineNo,
                                component.sourceSkuRef(),
                                component.skuCode())));
                    }
                    lineNo++;
                }
            }
            if (itemCursor != candidate.items().size()) {
                throw new IllegalStateException("来源订单候选的原始行分片未覆盖全部商品行");
            }
        }
        return List.copyOf(lines);
    }

    private List<Map<String, String>> candidateMappingIssues(
            CandidateLine line, SourceChannelSku mapping, Sku sku) {
        List<Map<String, String>> issues = new ArrayList<>();
        if (line.directInternalSku()) {
            if (sku == null) {
                issues.add(issue(
                        "SKU_MAPPING_CONFLICT",
                        "礼包候选引用的内部 SKU 不存在",
                        "修复礼包组件的内部 SKU 编码后重新确认批次"));
            }
            return issues;
        }
        if (line.sourceSkuRef() == null || mapping == null || !mapping.isActive()) {
            issues.add(issue(
                    "SOURCE_SKU_MAPPING_REQUIRED",
                    line.sourceSkuRef() == null ? "来源商品编码缺失" : "来源商品映射不存在或已停用",
                    "维护可跨订单复用的有效来源商品映射后重新确认批次"));
            return issues;
        }
        if (mapping.getQuantityMultiplier() == null || mapping.getQuantityMultiplier().signum() <= 0) {
            issues.add(issue(
                    "MAPPING_MULTIPLIER",
                    "来源包装乘数缺失或不是正数",
                    "核对一个来源销售单位包含的 Canonical SKU 件数"));
        }
        if (sku == null) {
            issues.add(issue(
                    "SKU_MAPPING_CONFLICT",
                    "来源商品映射指向不存在的内部 SKU",
                    "修复来源映射目标后重新确认批次"));
        } else if (line.skuCode() != null
                && !line.skuCode().isBlank()
                && !line.skuCode().equals(sku.getSkuCode())) {
            issues.add(issue(
                    "SKU_MAPPING_CONFLICT",
                    "来源商品映射与候选中的内部 SKU 编码不一致",
                    "核对映射目标，禁止用候选值覆盖主数据"));
        }
        return issues;
    }

    private void appendIssues(
            List<Map<String, String>> source,
            List<String> reasons,
            List<Map<String, String>> actionable) {
        for (Map<String, String> issue : source) {
            if (!reasons.contains(issue.get("code"))) {
                reasons.add(issue.get("code"));
                actionable.add(issue);
            }
        }
    }

    private void throwIfBlocked(List<Map<String, Object>> blockedLines) {
        if (blockedLines.isEmpty()) {
            return;
        }
        throw new BusinessException(
                409,
                "IMPORT_BATCH_BLOCKED",
                "批次包含尚未达到履约就绪条件的 SKU",
                List.of(),
                Map.of("blocking_type", "SKU_READINESS", "lines", List.copyOf(blockedLines)));
    }

    private Map<String, SourceChannelSku> mappings(List<BatchLine> lines) {
        Map<SourceChannel, Set<String>> refsByChannel = new LinkedHashMap<>();
        for (BatchLine line : lines) {
            if (line.sourceSkuRef() != null) {
                refsByChannel
                        .computeIfAbsent(line.mappingChannel(), ignored -> new LinkedHashSet<>())
                        .add(line.sourceSkuRef());
            }
        }
        Map<String, SourceChannelSku> result = new LinkedHashMap<>();
        for (Map.Entry<SourceChannel, Set<String>> entry : refsByChannel.entrySet()) {
            sourceMappings
                    .findAllBySourceChannelAndSourceSkuRefIn(entry.getKey(), entry.getValue())
                    .forEach(mapping -> result.put(mappingKey(
                            mapping.getSourceChannel(), mapping.getSourceSkuRef()), mapping));
        }
        return result;
    }

    private List<Map<String, String>> mappingIssues(
            BatchLine line, Map<String, SourceChannelSku> mappingByIdentity) {
        List<Map<String, String>> issues = new ArrayList<>();
        SourceChannelSku mapping = line.sourceSkuRef() == null
                ? null
                : mappingByIdentity.get(mappingKey(line.mappingChannel(), line.sourceSkuRef()));
        if (line.sourceSkuRef() == null) {
            issues.add(issue(
                    "SOURCE_SKU_MAPPING_REQUIRED",
                    "来源商品编码缺失，无法复核当前来源映射",
                    "补齐可跨订单复用的来源商品编码后重新导入批次"));
            return issues;
        }
        if (line.sourceSkuRef() != null && (mapping == null || !mapping.isActive())) {
            issues.add(issue(
                    "SOURCE_SKU_MAPPING_REQUIRED",
                    "来源商品映射不存在或已停用",
                    "恢复正确的长期来源商品映射，或重新完成人工 SKU 复核"));
            return issues;
        }
        if (mapping != null && !mapping.getSkuId().equals(line.skuId())) {
            issues.add(issue(
                    "SKU_MAPPING_CONFLICT",
                    "当前来源商品映射与订单冻结的内部 SKU 不一致",
                    "核对映射变更后重新导入批次，禁止覆盖历史订单快照"));
        }
        BigDecimal currentMultiplier = mapping == null ? line.mappingMultiplier() : mapping.getQuantityMultiplier();
        if (line.mappingMultiplier() == null
                || line.mappingMultiplier().signum() <= 0
                || currentMultiplier == null
                || currentMultiplier.signum() <= 0
                || currentMultiplier.compareTo(line.mappingMultiplier()) != 0) {
            issues.add(issue(
                    "MAPPING_MULTIPLIER",
                    "来源包装乘数缺失、无效或与订单冻结值冲突",
                    "核对一个来源销售单位包含的 Canonical SKU 件数后重新导入"));
        }
        return issues;
    }

    private Map<String, String> issue(String code, String message, String action) {
        return Map.of("code", code, "message", message, "action", action);
    }

    private String mappingKey(SourceChannel channel, String sourceSkuRef) {
        return channel.name() + "\u0000" + sourceSkuRef;
    }

    private List<BatchLine> lines(long sourceBatchId) {
        return jdbc.query(
                """
                WITH raw_line_links AS (
                    SELECT rir.id raw_import_row_id, rir.order_line_id
                    FROM app.raw_import_rows rir
                    WHERE rir.import_batch_id=? AND rir.order_line_id IS NOT NULL
                    UNION
                    SELECT rirol.raw_import_row_id, rirol.order_line_id
                    FROM app.raw_import_row_order_lines rirol
                    JOIN app.raw_import_rows rir ON rir.id=rirol.raw_import_row_id
                    WHERE rir.import_batch_id=?
                )
                SELECT DISTINCT rll.raw_import_row_id, ib.source_channel recorded_source_channel,
                       source.effective_source_channel mapping_source_channel, rir.raw_cells::text raw_cells,
                       o.id order_id, ol.id order_line_id, ol.line_no, ol.sku_id,
                       ol.sku_code_snapshot, ol.mapping_multiplier_snapshot
                FROM raw_line_links rll
                JOIN app.raw_import_rows rir ON rir.id=rll.raw_import_row_id AND rir.status='ACCEPTED'
                JOIN app.import_batches ib ON ib.id=rir.import_batch_id
                JOIN app.v_import_batch_effective_source source ON source.import_batch_id=ib.id
                JOIN app.order_lines ol ON ol.id=rll.order_line_id
                    AND ol.line_type='SINGLE' AND ol.sku_id IS NOT NULL
                JOIN app.orders o ON o.id=ol.order_id AND o.source_import_batch_id=?
                ORDER BY rll.raw_import_row_id, o.id, ol.line_no, ol.id
                """,
                (resultSet, rowNumber) -> {
                    SourceChannel recordedChannel =
                            SourceChannel.valueOf(resultSet.getString("recorded_source_channel"));
                    return new BatchLine(
                            resultSet.getLong("raw_import_row_id"),
                            SourceChannel.valueOf(resultSet.getString("mapping_source_channel")),
                            sourceSkuRef(recordedChannel, resultSet.getString("raw_cells")),
                            resultSet.getLong("order_id"),
                            resultSet.getLong("order_line_id"),
                            resultSet.getInt("line_no"),
                            resultSet.getLong("sku_id"),
                            resultSet.getString("sku_code_snapshot"),
                            resultSet.getBigDecimal("mapping_multiplier_snapshot"));
                },
                sourceBatchId,
                sourceBatchId,
                sourceBatchId);
    }

    private String sourceSkuRef(SourceChannel recordedChannel, String rawCellsJson) {
        if (rawCellsJson == null || rawCellsJson.isBlank()) {
            return null;
        }
        try {
            JsonNode cells = objectMapper.readTree(rawCellsJson);
            String structured = text(cells.path("source_sku_ref"));
            if (structured != null) {
                return structured;
            }
            Map<String, String> flatCells = new LinkedHashMap<>();
            cells.fields().forEachRemaining(entry -> {
                if (entry.getValue().isTextual() || entry.getValue().isNumber()) {
                    flatCells.put(entry.getKey(), entry.getValue().asText());
                }
            });
            return parser.projection(recordedChannel, flatCells).get("source_sku_ref");
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            return null;
        }
    }

    private String text(JsonNode value) {
        if (value == null || !value.isValueNode()) {
            return null;
        }
        String text = value.asText();
        return text == null || text.isBlank() ? null : text;
    }

    private record BatchLine(
            long rawImportRowId,
            SourceChannel mappingChannel,
            String sourceSkuRef,
            long orderId,
            long orderLineId,
            int lineNo,
            long skuId,
            String skuCode,
            BigDecimal mappingMultiplier) {}

    private record CandidateLine(
            long rawImportRowId,
            String candidateKey,
            int lineNo,
            String sourceSkuRef,
            String skuCode) {

        boolean directInternalSku() {
            return (sourceSkuRef == null || sourceSkuRef.isBlank())
                    && skuCode != null
                    && !skuCode.isBlank();
        }
    }
}
