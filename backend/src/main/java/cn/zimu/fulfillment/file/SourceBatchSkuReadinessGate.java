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
        List<SourceOrderReadinessCandidate> snapshots = candidates.stream()
                .map(SourceOrderReadinessCandidate::from)
                .toList();
        requireCandidateBundlesReady(mappingChannel, snapshots);
        requireCandidateLinesReady(mappingChannel, readinessCandidateLines(snapshots));
    }

    /** 已成单但尚未确认的批次按最小、无 PII 的候选快照重新执行相同门禁。 */
    void requireReadySnapshot(
            SourceChannel mappingChannel, List<SourceOrderReadinessCandidate> candidates) {
        requireCandidateBundlesReady(mappingChannel, candidates);
        requireCandidateLinesReady(mappingChannel, readinessCandidateLines(candidates));
    }

    /** 在 catalog 共享锁仍由导入事务持有时，冻结来源单品映射及静态礼包映射/BOM。 */
    List<SourceOrderCandidate> snapshotCurrentMappings(
            SourceChannel mappingChannel, List<SourceOrderCandidate> candidates) {
        catalogLock.acquireShared();
        Set<String> refs = candidates.stream()
                .flatMap(candidate -> candidate.order().items().stream())
                .filter(item -> item.lineType() == LineType.SINGLE)
                .map(OrderItemInput::sourceSkuRef)
                .filter(Objects::nonNull)
                .filter(ref -> !ref.isBlank())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Map<String, SourceChannelSku> mappingByRef = new LinkedHashMap<>();
        if (!refs.isEmpty()) {
            sourceMappings.findAllBySourceChannelAndSourceSkuRefIn(mappingChannel, refs)
                    .forEach(mapping -> mappingByRef.put(mapping.getSourceSkuRef(), mapping));
        }
        Map<Long, Sku> skuById = new LinkedHashMap<>();
        skus.findAllById(mappingByRef.values().stream().map(SourceChannelSku::getSkuId).distinct().toList())
                .forEach(sku -> skuById.put(sku.getId(), sku));
        Set<String> bundleRefs = candidates.stream()
                .flatMap(candidate -> candidate.order().items().stream())
                .filter(item -> item.lineType() == LineType.CUSTOM_BUNDLE && item.bundleId() != null)
                .map(OrderItemInput::sourceSkuRef)
                .filter(Objects::nonNull)
                .filter(ref -> !ref.isBlank())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Map<String, SourceBundleFact> bundleByRef = sourceBundleFacts(mappingChannel, bundleRefs);
        Map<Long, List<SourceOrderCandidate.BundleComponentSnapshot>> bomByBundle = bundleComponents(
                bundleByRef.values().stream().map(SourceBundleFact::bundleId).collect(java.util.stream.Collectors.toSet()));
        List<SourceOrderCandidate> frozen = new ArrayList<>();
        for (SourceOrderCandidate candidate : candidates) {
            List<SourceOrderCandidate.SourceMappingSnapshot> snapshots = new ArrayList<>();
            for (int itemIndex = 0; itemIndex < candidate.order().items().size(); itemIndex++) {
                OrderItemInput item = candidate.order().items().get(itemIndex);
                SourceChannelSku mapping = item.lineType() == LineType.SINGLE
                        ? mappingByRef.get(item.sourceSkuRef())
                        : null;
                Sku sku = mapping == null ? null : skuById.get(mapping.getSkuId());
                if (mapping != null) {
                    snapshots.add(new SourceOrderCandidate.SourceMappingSnapshot(
                            itemIndex,
                            mapping.getSourceSkuRef(),
                            mapping.getSkuId(),
                            sku == null ? null : sku.getSkuCode(),
                            mapping.getQuantityMultiplier()));
                }
            }
            List<SourceOrderCandidate.SourceBundleMappingSnapshot> bundleSnapshots = new ArrayList<>();
            int itemCursor = 0;
            for (SourceOrderCandidate.CandidateRow row : candidate.rows()) {
                int firstItemIndex = itemCursor;
                List<OrderItemInput> rowItems = candidate.order().items()
                        .subList(itemCursor, itemCursor + row.partitionCount());
                itemCursor += row.partitionCount();
                OrderItemInput bundleItem = rowItems.stream()
                        .filter(item -> item.lineType() == LineType.CUSTOM_BUNDLE && item.bundleId() != null)
                        .findFirst()
                        .orElse(null);
                if (bundleItem == null) {
                    continue;
                }
                SourceBundleFact fact = bundleByRef.get(bundleItem.sourceSkuRef());
                if (fact != null) {
                    bundleSnapshots.add(new SourceOrderCandidate.SourceBundleMappingSnapshot(
                            row.rawImportRowId(),
                            firstItemIndex,
                            row.partitionCount(),
                            bundleItem.sourceSkuRef(),
                            fact.bundleId(),
                            fact.quantityMultiplier(),
                            bomByBundle.getOrDefault(fact.bundleId(), List.of())));
                }
            }
            frozen.add(new SourceOrderCandidate(
                    candidate.candidateKey(),
                    candidate.order(),
                    candidate.rows(),
                    candidate.createIdempotencyKey(),
                    candidate.actor(),
                    snapshots,
                    bundleSnapshots));
        }
        return List.copyOf(frozen);
    }

    /** 静态礼包候选必须继续命中上传时同一来源映射、ACTIVE 礼包和同一完整 BOM。 */
    private void requireCandidateBundlesReady(
            SourceChannel mappingChannel, List<SourceOrderReadinessCandidate> candidates) {
        catalogLock.acquireShared();
        List<CandidateBundle> bundles = candidateBundles(candidates);
        if (bundles.isEmpty()) {
            return;
        }
        Set<String> refs = bundles.stream()
                .map(CandidateBundle::sourceBundleRef)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Map<String, SourceBundleFact> currentByRef = sourceBundleFacts(mappingChannel, refs);
        Set<Long> bundleIds = new LinkedHashSet<>();
        bundles.stream()
                .map(CandidateBundle::snapshot)
                .filter(Objects::nonNull)
                .map(SourceOrderCandidate.SourceBundleMappingSnapshot::bundleId)
                .forEach(bundleIds::add);
        currentByRef.values().stream().map(SourceBundleFact::bundleId).forEach(bundleIds::add);
        Map<Long, List<SourceOrderCandidate.BundleComponentSnapshot>> currentBom = bundleComponents(bundleIds);
        Map<Long, String> currentStatuses = bundleStatuses(bundleIds);

        List<Map<String, Object>> blocked = new ArrayList<>();
        for (CandidateBundle candidate : bundles) {
            List<Map<String, String>> issues = new ArrayList<>();
            SourceOrderCandidate.SourceBundleMappingSnapshot snapshot = candidate.snapshot();
            if (snapshot == null) {
                issues.add(issue(
                        "SOURCE_BUNDLE_SNAPSHOT_REQUIRED",
                        "来源礼包候选缺少上传时映射与 BOM 快照",
                        "重新导入该批次，禁止按当前主数据重新解释历史候选"));
            } else {
                if (snapshot.rawImportRowId() != candidate.rawImportRowId()
                        || snapshot.itemIndex() != candidate.itemIndex()
                        || snapshot.partitionCount() != candidate.partitionCount()
                        || !snapshot.sourceBundleRef().equals(candidate.sourceBundleRef())
                        || !snapshot.bundleId().equals(candidate.bundleId())
                        || !candidate.identityValid()
                        || !candidate.components().valid()
                        || !snapshotComponents(snapshot.components()).equals(candidate.components())) {
                    issues.add(issue(
                            "SOURCE_BUNDLE_SNAPSHOT_CONFLICT",
                            "来源礼包候选与上传时冻结的映射/BOM 身份不一致",
                            "重新导入该批次，禁止覆盖或重解释已冻结候选"));
                }
                SourceBundleFact current = currentByRef.get(snapshot.sourceBundleRef());
                if (current == null || !current.active()) {
                    issues.add(issue(
                            "SOURCE_BUNDLE_MAPPING_REQUIRED",
                            "来源礼包映射不存在或已停用",
                            "恢复正确的长期来源礼包映射后重新确认"));
                } else if (!snapshot.bundleId().equals(current.bundleId())) {
                    issues.add(issue(
                            "SOURCE_BUNDLE_MAPPING_CONFLICT",
                            "当前来源礼包映射已改指其他礼包",
                            "核对映射变更后重新导入，禁止覆盖历史候选"));
                }
                BigDecimal currentMultiplier = current == null ? null : current.quantityMultiplier();
                if (snapshot.quantityMultiplier() == null
                        || snapshot.quantityMultiplier().signum() <= 0
                        || currentMultiplier == null
                        || currentMultiplier.signum() <= 0
                        || currentMultiplier.compareTo(snapshot.quantityMultiplier()) != 0) {
                    issues.add(issue(
                            "MAPPING_MULTIPLIER",
                            "来源礼包包装乘数缺失、无效或与上传快照冲突",
                            "核对一个来源销售单位包含的礼包数量后重新导入"));
                }
                if (!"ACTIVE".equals(currentStatuses.get(snapshot.bundleId()))) {
                    issues.add(issue(
                            "BUNDLE_INACTIVE",
                            "上传时命中的静态礼包当前已停用或不存在",
                            "恢复正确礼包状态，或重新导入使用新的礼包版本"));
                }
                ComponentIdentity currentComponents = snapshotComponents(
                        currentBom.getOrDefault(snapshot.bundleId(), List.of()));
                ComponentIdentity frozenComponents = snapshotComponents(snapshot.components());
                if (!currentComponents.valid()
                        || frozenComponents.values().isEmpty()
                        || !currentComponents.equals(frozenComponents)) {
                    issues.add(issue(
                            "BUNDLE_BOM_CONFLICT",
                            "静态礼包当前 BOM 与上传时冻结内容不一致",
                            "核对礼包组件和每礼包数量后重新导入"));
                }
            }
            if (issues.isEmpty()) {
                continue;
            }
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("raw_import_row_id", Long.toString(candidate.rawImportRowId()));
            detail.put("candidate_key", candidate.candidateKey());
            detail.put("source_channel", mappingChannel.name());
            detail.put("source_bundle_ref", candidate.sourceBundleRef());
            if (candidate.bundleId() != null) {
                detail.put("bundle_id", Long.toString(candidate.bundleId()));
            }
            SourceBundleFact current = currentByRef.get(candidate.sourceBundleRef());
            if (current != null) {
                detail.put("current_bundle_id", Long.toString(current.bundleId()));
            }
            List<String> reasons = issues.stream().map(issue -> issue.get("code")).distinct().toList();
            detail.put("ready", false);
            detail.put("reason_codes", reasons);
            detail.put("issues", List.copyOf(issues));
            blocked.add(Map.copyOf(detail));
        }
        throwIfBlocked(blocked);
    }

    private List<CandidateBundle> candidateBundles(List<SourceOrderReadinessCandidate> candidates) {
        List<CandidateBundle> result = new ArrayList<>();
        for (SourceOrderReadinessCandidate candidate : candidates) {
            Map<Long, List<SourceOrderCandidate.SourceBundleMappingSnapshot>> snapshotsByRaw =
                    new LinkedHashMap<>();
            candidate.sourceBundleMappings().forEach(snapshot -> snapshotsByRaw
                    .computeIfAbsent(snapshot.rawImportRowId(), ignored -> new ArrayList<>())
                    .add(snapshot));
            int itemCursor = 0;
            for (SourceOrderReadinessCandidate.CandidateRow row : candidate.rows()) {
                int firstItemIndex = itemCursor;
                List<OrderItemInput> rowItems = candidate.items()
                        .subList(itemCursor, itemCursor + row.partitionCount());
                itemCursor += row.partitionCount();
                List<OrderItemInput> bundleItems = rowItems.stream()
                        .filter(item -> item.lineType() == LineType.CUSTOM_BUNDLE && item.bundleId() != null)
                        .toList();
                if (bundleItems.isEmpty()) {
                    continue;
                }
                OrderItemInput first = bundleItems.getFirst();
                boolean identityValid = bundleItems.size() == rowItems.size()
                        && bundleItems.stream().allMatch(item ->
                                Objects.equals(item.sourceSkuRef(), first.sourceSkuRef())
                                        && Objects.equals(item.bundleId(), first.bundleId()));
                Long bundleId;
                try {
                    bundleId = Long.valueOf(first.bundleId());
                } catch (NumberFormatException exception) {
                    bundleId = null;
                }
                List<SourceOrderCandidate.SourceBundleMappingSnapshot> matchingSnapshots =
                        snapshotsByRaw.getOrDefault(row.rawImportRowId(), List.of());
                SourceOrderCandidate.SourceBundleMappingSnapshot snapshot = matchingSnapshots.size() == 1
                        ? matchingSnapshots.getFirst()
                        : null;
                result.add(new CandidateBundle(
                        row.rawImportRowId(),
                        candidate.candidateKey(),
                        firstItemIndex,
                        row.partitionCount(),
                        first.sourceSkuRef(),
                        bundleId,
                        identityValid,
                        candidateComponents(bundleItems),
                        snapshot));
            }
        }
        return List.copyOf(result);
    }

    private ComponentIdentity candidateComponents(List<OrderItemInput> items) {
        Map<String, String> values = new LinkedHashMap<>();
        boolean valid = true;
        for (OrderItemInput item : items) {
            if (item.components() == null) {
                valid = false;
                continue;
            }
            for (var component : item.components()) {
                String code = component.skuCode();
                String quantity = quantityIdentity(component.quantityPerBundle());
                if (code == null || code.isBlank() || quantity == null || values.putIfAbsent(code, quantity) != null) {
                    valid = false;
                }
            }
        }
        return new ComponentIdentity(Map.copyOf(values), valid);
    }

    private ComponentIdentity snapshotComponents(
            List<SourceOrderCandidate.BundleComponentSnapshot> components) {
        Map<String, String> values = new LinkedHashMap<>();
        boolean valid = true;
        for (SourceOrderCandidate.BundleComponentSnapshot component : components) {
            String quantity = quantityIdentity(component.quantityPerBundle());
            if (component.skuCode() == null
                    || component.skuCode().isBlank()
                    || quantity == null
                    || values.putIfAbsent(component.skuCode(), quantity) != null) {
                valid = false;
            }
        }
        return new ComponentIdentity(Map.copyOf(values), valid);
    }

    private String quantityIdentity(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            return new BigDecimal(raw.toString()).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private Map<String, SourceBundleFact> sourceBundleFacts(
            SourceChannel channel, Set<String> refs) {
        if (refs.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(refs.size(), "?"));
        List<Object> arguments = new ArrayList<>();
        arguments.add(channel.name());
        arguments.addAll(refs);
        Map<String, SourceBundleFact> result = new LinkedHashMap<>();
        jdbc.query(
                """
                SELECT scb.source_bundle_ref, scb.bundle_id, scb.quantity_multiplier, scb.active
                FROM app.source_channel_bundles scb
                WHERE scb.source_channel=? AND scb.source_bundle_ref IN ("""
                        + placeholders + ")",
                (org.springframework.jdbc.core.RowCallbackHandler) resultSet -> result.put(
                        resultSet.getString("source_bundle_ref"),
                        new SourceBundleFact(
                                resultSet.getString("source_bundle_ref"),
                                resultSet.getLong("bundle_id"),
                                resultSet.getBigDecimal("quantity_multiplier"),
                                resultSet.getBoolean("active"))),
                arguments.toArray());
        return Map.copyOf(result);
    }

    private Map<Long, List<SourceOrderCandidate.BundleComponentSnapshot>> bundleComponents(Set<Long> bundleIds) {
        if (bundleIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(bundleIds.size(), "?"));
        Map<Long, List<SourceOrderCandidate.BundleComponentSnapshot>> result = new LinkedHashMap<>();
        jdbc.query(
                """
                SELECT bi.bundle_id, bi.sku_id, s.sku_code, bi.quantity_per_bundle
                FROM app.bundle_items bi
                JOIN app.skus s ON s.id=bi.sku_id
                WHERE bi.bundle_id IN (""" + placeholders + ") ORDER BY bi.bundle_id, bi.sort_no",
                (org.springframework.jdbc.core.RowCallbackHandler) resultSet -> result
                        .computeIfAbsent(resultSet.getLong("bundle_id"), ignored -> new ArrayList<>())
                        .add(new SourceOrderCandidate.BundleComponentSnapshot(
                                resultSet.getLong("sku_id"),
                                resultSet.getString("sku_code"),
                                resultSet.getBigDecimal("quantity_per_bundle"))),
                bundleIds.toArray());
        Map<Long, List<SourceOrderCandidate.BundleComponentSnapshot>> immutable = new LinkedHashMap<>();
        result.forEach((id, components) -> immutable.put(id, List.copyOf(components)));
        return Map.copyOf(immutable);
    }

    private Map<Long, String> bundleStatuses(Set<Long> bundleIds) {
        if (bundleIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(bundleIds.size(), "?"));
        Map<Long, String> result = new LinkedHashMap<>();
        jdbc.query(
                "SELECT id, status FROM app.product_bundles WHERE id IN (" + placeholders + ")",
                (org.springframework.jdbc.core.RowCallbackHandler) resultSet ->
                        result.put(resultSet.getLong("id"), resultSet.getString("status")),
                bundleIds.toArray());
        return Map.copyOf(result);
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
        List<String> directSkuCodes = lines.stream()
                .map(CandidateLine::skuCode)
                .filter(Objects::nonNull)
                .filter(code -> !code.isBlank())
                .distinct()
                .toList();
        Map<String, Sku> skuByCode = new LinkedHashMap<>();
        if (!directSkuCodes.isEmpty()) {
            skus.findBySkuCodeIn(directSkuCodes)
                    .forEach(sku -> skuByCode.put(sku.getSkuCode(), sku));
        }
        List<Long> skuIds = java.util.stream.Stream.concat(
                        mappingByRef.values().stream().map(SourceChannelSku::getSkuId),
                        skuByCode.values().stream().map(Sku::getId))
                .distinct()
                .toList();
        Map<Long, Sku> skuById = new LinkedHashMap<>();
        skus.findAllById(skuIds).forEach(sku -> skuById.put(sku.getId(), sku));
        Map<Long, SkuFulfillmentReadiness> readinessBySku = readiness.evaluateAll(skuById.values());
        Set<Long> jdProviderIds = new LinkedHashSet<>(jdbc.queryForList(
                "SELECT id FROM app.fulfillment_providers WHERE provider_type='JD_WAREHOUSE'",
                Long.class));

        List<Map<String, Object>> blockedLines = new ArrayList<>();
        for (CandidateLine line : lines) {
            SourceChannelSku mapping = line.sourceSkuRef() == null
                    ? null
                    : mappingByRef.get(line.sourceSkuRef());
            Sku sku = line.directInternalSku()
                    ? skuByCode.get(line.skuCode())
                    : mapping == null ? null : skuById.get(mapping.getSkuId());
            List<Map<String, String>> issues = candidateMappingIssues(line, mapping, sku);
            BigDecimal convertedQuantity = candidateRequestedQuantity(line, mapping);
            if (sku != null
                    && jdProviderIds.contains(sku.getFulfillmentProviderId())
                    && (convertedQuantity == null
                        || convertedQuantity.signum() <= 0
                        || convertedQuantity.stripTrailingZeros().scale() > 0)) {
                issues.add(issue(
                        "QUANTITY_SCALE",
                        "京东出库数量必须为正整数",
                        "核对来源数量和包装乘数后重新导入批次"));
            }
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
            Long expectedSkuId = line.frozenSkuId() == null
                    ? sku == null ? null : sku.getId()
                    : line.frozenSkuId();
            String expectedSkuCode = line.skuCode() == null
                    ? sku == null ? null : sku.getSkuCode()
                    : line.skuCode();
            if (expectedSkuId != null) {
                detail.put("sku_id", Long.toString(expectedSkuId));
            }
            if (expectedSkuCode != null) {
                detail.put("sku_code", expectedSkuCode);
            }
            if (sku != null && line.frozenSkuId() != null && !line.frozenSkuId().equals(sku.getId())) {
                detail.put("current_sku_id", Long.toString(sku.getId()));
                detail.put("current_sku_code", sku.getSkuCode());
            }
            if (line.sourceQuantity() != null) {
                detail.put("source_quantity", line.sourceQuantity().toPlainString());
            }
            if (convertedQuantity != null) {
                detail.put("converted_quantity", convertedQuantity.toPlainString());
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

    private List<CandidateLine> readinessCandidateLines(
            List<SourceOrderReadinessCandidate> candidates) {
        List<CandidateLine> lines = new ArrayList<>();
        for (SourceOrderReadinessCandidate candidate : candidates) {
            Map<Integer, SourceOrderCandidate.SourceMappingSnapshot> mappingByItem = new LinkedHashMap<>();
            candidate.sourceMappings().forEach(snapshot -> mappingByItem.put(snapshot.itemIndex(), snapshot));
            int itemCursor = 0;
            int lineNo = 1;
            for (SourceOrderReadinessCandidate.CandidateRow row : candidate.rows()) {
                for (int partition = 0; partition < row.partitionCount(); partition++) {
                    if (itemCursor >= candidate.items().size()) {
                        throw new IllegalStateException("来源订单候选的原始行分片超过商品行数量");
                    }
                    int currentItemIndex = itemCursor;
                    OrderItemInput item = candidate.items().get(itemCursor++);
                    if (item.lineType() == LineType.SINGLE) {
                        SourceOrderCandidate.SourceMappingSnapshot mapping = mappingByItem.get(currentItemIndex);
                        lines.add(new CandidateLine(
                                row.rawImportRowId(),
                                candidate.candidateKey(),
                                lineNo,
                                item.sourceSkuRef(),
                                mapping == null ? null : mapping.skuId(),
                                mapping == null ? item.skuCode() : mapping.skuCode(),
                                mapping == null ? null : mapping.quantityMultiplier(),
                                decimal(item.quantity())));
                    } else if (item.components() == null || item.components().isEmpty()) {
                        lines.add(new CandidateLine(
                                row.rawImportRowId(), candidate.candidateKey(), lineNo,
                                null, null, null, null, decimal(item.quantity())));
                    } else {
                        int candidateLineNo = lineNo;
                        item.components().forEach(component -> lines.add(new CandidateLine(
                                row.rawImportRowId(),
                                candidate.candidateKey(),
                                candidateLineNo,
                                component.sourceSkuRef(),
                                null,
                                component.skuCode(),
                                null,
                                decimal(item.quantity()))));
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

    private BigDecimal candidateRequestedQuantity(CandidateLine line, SourceChannelSku mapping) {
        if (line.sourceQuantity() == null) {
            return null;
        }
        BigDecimal multiplier;
        if (line.directInternalSku()) {
            multiplier = BigDecimal.ONE;
        } else if (line.mappingMultiplier() != null) {
            multiplier = line.mappingMultiplier();
        } else {
            multiplier = mapping == null ? null : mapping.getQuantityMultiplier();
        }
        return multiplier == null ? null : line.sourceQuantity().multiply(multiplier);
    }

    private BigDecimal decimal(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            return new BigDecimal(raw.toString());
        } catch (NumberFormatException exception) {
            return null;
        }
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
        if (line.mappingMultiplier() != null
                && (mapping.getQuantityMultiplier() == null
                    || mapping.getQuantityMultiplier().compareTo(line.mappingMultiplier()) != 0)) {
            issues.add(issue(
                    "MAPPING_MULTIPLIER",
                    "当前来源包装乘数与上传时冻结值冲突",
                    "核对一个来源销售单位包含的 Canonical SKU 件数后重新导入批次"));
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
        if (line.directInternalSku()) {
            return issues;
        }
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
                       o.id order_id, ol.id order_line_id, ol.line_no,
                       COALESCE(olc.sku_id, ol.sku_id) sku_id,
                       COALESCE(component_sku.sku_code, ol.sku_code_snapshot) sku_code_snapshot,
                       CASE WHEN olc.id IS NULL THEN ol.mapping_multiplier_snapshot END mapping_multiplier_snapshot,
                       (olc.id IS NOT NULL) direct_internal_sku
                FROM raw_line_links rll
                JOIN app.raw_import_rows rir ON rir.id=rll.raw_import_row_id AND rir.status='ACCEPTED'
                JOIN app.import_batches ib ON ib.id=rir.import_batch_id
                JOIN app.v_import_batch_effective_source source ON source.import_batch_id=ib.id
                JOIN app.order_lines ol ON ol.id=rll.order_line_id
                    AND ((ol.line_type='SINGLE' AND ol.sku_id IS NOT NULL)
                         OR (ol.line_type='CUSTOM_BUNDLE' AND ol.bundle_id IS NOT NULL))
                LEFT JOIN app.order_line_components olc
                    ON olc.order_line_id=ol.id AND ol.line_type='CUSTOM_BUNDLE'
                LEFT JOIN app.skus component_sku ON component_sku.id=olc.sku_id
                JOIN app.orders o ON o.id=ol.order_id AND o.source_import_batch_id=?
                WHERE ol.line_type='SINGLE' OR olc.id IS NOT NULL
                ORDER BY rll.raw_import_row_id, o.id, ol.line_no, ol.id,
                         COALESCE(olc.sku_id, ol.sku_id)
                """,
                (resultSet, rowNumber) -> {
                    SourceChannel recordedChannel =
                            SourceChannel.valueOf(resultSet.getString("recorded_source_channel"));
                    boolean directInternalSku = resultSet.getBoolean("direct_internal_sku");
                    return new BatchLine(
                            resultSet.getLong("raw_import_row_id"),
                            SourceChannel.valueOf(resultSet.getString("mapping_source_channel")),
                            directInternalSku
                                    ? null
                                    : sourceSkuRef(recordedChannel, resultSet.getString("raw_cells")),
                            resultSet.getLong("order_id"),
                            resultSet.getLong("order_line_id"),
                            resultSet.getInt("line_no"),
                            resultSet.getLong("sku_id"),
                            resultSet.getString("sku_code_snapshot"),
                            resultSet.getBigDecimal("mapping_multiplier_snapshot"),
                            directInternalSku);
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
            BigDecimal mappingMultiplier,
            boolean directInternalSku) {}

    private record CandidateLine(
            long rawImportRowId,
            String candidateKey,
            int lineNo,
            String sourceSkuRef,
            Long frozenSkuId,
            String skuCode,
            BigDecimal mappingMultiplier,
            BigDecimal sourceQuantity) {

        boolean directInternalSku() {
            return (sourceSkuRef == null || sourceSkuRef.isBlank())
                    && skuCode != null
                    && !skuCode.isBlank();
        }
    }

    private record CandidateBundle(
            long rawImportRowId,
            String candidateKey,
            int itemIndex,
            int partitionCount,
            String sourceBundleRef,
            Long bundleId,
            boolean identityValid,
            ComponentIdentity components,
            SourceOrderCandidate.SourceBundleMappingSnapshot snapshot) {}

    private record ComponentIdentity(Map<String, String> values, boolean valid) {}

    private record SourceBundleFact(
            String sourceBundleRef,
            long bundleId,
            BigDecimal quantityMultiplier,
            boolean active) {}
}
