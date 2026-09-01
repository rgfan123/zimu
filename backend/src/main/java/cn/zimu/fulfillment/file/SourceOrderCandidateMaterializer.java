package cn.zimu.fulfillment.file;

import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.web.CommandContext;
import cn.zimu.fulfillment.customer.ImportedCustomerService;
import cn.zimu.fulfillment.order.OrderCreateService;
import cn.zimu.fulfillment.order.dto.CanonicalOrderInput;
import cn.zimu.fulfillment.order.dto.CustomerInput;
import cn.zimu.fulfillment.order.dto.OrderDetailDto;
import cn.zimu.fulfillment.order.dto.OrderItemInput;
import cn.zimu.fulfillment.order.dto.OrderLineDto;
import cn.zimu.fulfillment.order.dto.Receiver;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 将已通过整批门禁的来源候选原子转换为 CanonicalOrder 与 raw-row 血缘。 */
@Service
class SourceOrderCandidateMaterializer {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final OrderCreateService orders;
    private final ImportedCustomerService importedCustomers;
    private final SourceBatchSkuReadinessGate readinessGate;

    SourceOrderCandidateMaterializer(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            OrderCreateService orders,
            ImportedCustomerService importedCustomers,
            SourceBatchSkuReadinessGate readinessGate) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.orders = orders;
        this.importedCustomers = importedCustomers;
        this.readinessGate = readinessGate;
    }

    /**
     * 确认前的内部工作台安全预览。候选快照仍只保存在服务端；这里按 raw row 白名单投影，
     * 不返回完整 CanonicalOrderInput，也不改写会进入企微证据链的 raw_cells。
     *
     * <p>结构化礼包在候选构造时已按履约方展开成多个 CanonicalOrderInput.items，
     * candidate.rows 仍按来源原始商品行保存；因此必须用 CandidateRow.partitionCount
     * 切出同一 raw row 对应的候选商品分片，不能按两个列表的下标直接一一配对。</p>
     */
    Map<Long, CandidateRowPreview> stagedPreviews(long batchId) {
        List<String> details = jdbc.query(
                """
                SELECT error_detail::text
                FROM app.import_batches
                WHERE id=? AND batch_type='SOURCE_ORDER'
                """,
                (resultSet, rowNumber) -> resultSet.getString(1),
                batchId);
        if (details.isEmpty()) {
            return Map.of();
        }
        StagedPayload payload = stagedPayload(details.getFirst());
        if (payload == null || !"PENDING".equals(payload.status())) {
            return Map.of();
        }

        Map<Long, CandidateRowPreview> previews = new LinkedHashMap<>();
        for (SourceOrderCandidate candidate : payload.candidates()) {
            CanonicalOrderInput order = candidate.order();
            if (order == null) {
                throw new IllegalStateException("来源候选缺少 CanonicalOrderInput");
            }
            List<OrderItemInput> items = order.items() == null ? List.of() : order.items();
            int itemCursor = 0;
            for (SourceOrderCandidate.CandidateRow row : candidate.rows()) {
                int nextCursor = itemCursor + row.partitionCount();
                if (nextCursor > items.size()) {
                    throw new IllegalStateException("来源候选的分片数超过候选商品行数");
                }
                CandidateRowPreview previous = previews.put(
                        row.rawImportRowId(),
                        CandidateRowPreview.from(order.receiver(), items.subList(itemCursor, nextCursor)));
                if (previous != null) {
                    throw new IllegalStateException("来源候选重复关联 raw_import_row_id=" + row.rawImportRowId());
                }
                itemCursor = nextCursor;
            }
            if (itemCursor != items.size()) {
                throw new IllegalStateException("来源候选的分片未覆盖全部候选商品行");
            }
        }
        return Map.copyOf(previews);
    }

    /**
     * 确认待放行批次。加入调用方的确认事务，保证候选成单、Provider 校验、履约路由和
     * confirmed_at 要么一起提交，要么一起回滚。
     */
    @Transactional
    boolean materializeStaged(long batchId, CommandContext context) {
        StagedBatch batch = jdbc.query(
                        """
                        SELECT source.effective_source_channel, ib.error_detail::text error_detail,
                               ib.confirmed_at
                        FROM app.import_batches ib
                        JOIN app.v_import_batch_effective_source source ON source.import_batch_id=ib.id
                        WHERE ib.id=? AND ib.batch_type='SOURCE_ORDER'
                        FOR UPDATE OF ib
                        """,
                        (resultSet, rowNumber) -> new StagedBatch(
                                SourceChannel.valueOf(resultSet.getString("effective_source_channel")),
                                resultSet.getString("error_detail"),
                                resultSet.getObject("confirmed_at") != null),
                        batchId)
                .stream()
                .findFirst()
                .orElseThrow(() -> BusinessException.notFound("来源订单批次不存在: " + batchId));
        StagedPayload payload = stagedPayload(batch.errorDetail());
        if (payload == null) {
            // 候选流水线（2026-08-31）之前的历史批次没有候选快照：正式订单已在上传期创建。
            // 确认仍必须按原始行证据整批重跑 SKU 门禁——特别是 legacy 行 raw_cells 缺
            // source_sku_ref 时失败关闭（SOURCE_SKU_MAPPING_REQUIRED），这是合并前确认
            // 路径的既有语义；带快照的现代批次才改走下方候选门禁。
            readinessGate.requireReady(batchId);
            return false;
        }
        if ("MATERIALIZED".equals(payload.status())) {
            if (!batch.confirmed()) {
                readinessGate.requireReadySnapshot(batch.mappingChannel(), payload.readinessCandidates());
            }
            return false;
        }
        if (!"PENDING".equals(payload.status())) {
            return false;
        }
        // 部分放行（与人工「部分确认」同一条产品语义）：候选=一张来源订单，独立评估、独立成单。
        // 原子性保持在候选内（一单的行要么全部成单要么全不成）；批次层面允许就绪候选先行，
        // 阻断候选原地留批，行上带阻断原因，修复后再次确认由本方法重新逐候选评估（补做闭环）。
        // 硬阻断（文件/数据问题）同样按候选算账：只有自己行上有问题的候选被跳过。
        java.util.Set<Long> hardBlockedRowIds = new java.util.HashSet<>(jdbc.query(
                """
                SELECT id FROM app.raw_import_rows
                WHERE import_batch_id=?
                  AND status IN ('NEED_REVIEW', 'REJECTED')
                  AND COALESCE(error_code, '')
                      NOT IN ('SKU_READINESS', 'BATCH_ATOMIC_RELEASE_BLOCKED', 'ORDER_ALREADY_EXISTS')
                """,
                (resultSet, rowNumber) -> resultSet.getLong(1),
                batchId));
        List<SourceOrderCandidate> materialized = new ArrayList<>();
        List<SourceOrderCandidate> remaining = new ArrayList<>();
        List<Map<String, Object>> blockedLines = new ArrayList<>();
        for (SourceOrderCandidate candidate : payload.candidates()) {
            boolean hardBlocked = candidate.rows().stream()
                    .map(SourceOrderCandidate.CandidateRow::rawImportRowId)
                    .anyMatch(hardBlockedRowIds::contains);
            if (hardBlocked) {
                remaining.add(candidate);
                continue;
            }
            try {
                readinessGate.requireReady(batch.mappingChannel(), List.of(candidate));
            } catch (BusinessException exception) {
                if (!"IMPORT_BATCH_BLOCKED".equals(exception.getBusinessCode())) {
                    throw exception;
                }
                collectBlockedLines(exception, blockedLines);
                remaining.add(candidate);
                continue;
            }
            materialize(batchId, List.of(candidate), context);
            materialized.add(candidate);
        }
        if (materialized.isEmpty()) {
            if (payload.candidates().isEmpty()) {
                // 没有任何候选（例如仅表头的空文件，或全部行都是解析期硬阻断）：交回确认事务的
                // 行级 readiness 闸门定夺——硬阻断行仍会被 confirm 的 blockedRows 判据挡下，
                // 真正的空批次按既有语义空转放行。
                return false;
            }
            // 一个候选都放不出去：维持既有失败面（含逐行阻断明细），让确认动作明确失败
            // 而不是静默空转——运营要在响应里直接看到卡在哪个 SKU、哪个原因。
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("blocking_type", "SKU_READINESS");
            if (!blockedLines.isEmpty()) {
                details.put("lines", List.copyOf(blockedLines));
            }
            throw new BusinessException(
                    409, "IMPORT_BATCH_BLOCKED", "批次仍有待处理的 SKU、文件或数据问题", List.of(), details);
        }
        Map<String, Object> updated = new LinkedHashMap<>(payload.root());
        List<SourceOrderReadinessCandidate> readinessSnapshots =
                new ArrayList<>(payload.readinessCandidates());
        readinessSnapshots.addAll(readinessCandidates(materialized));
        updated.put("source_order_readiness_candidates", readinessSnapshots);
        if (remaining.isEmpty()) {
            updated.put("candidate_status", "MATERIALIZED");
            updated.remove("readiness");
            updated.remove("source_order_candidates");
            jdbc.update(
                    """
                    UPDATE app.review_cases
                    SET status='RESOLVED',
                        resolution=jsonb_build_object(
                            'resolution_type', 'MASTER_DATA_REPAIRED',
                            'candidate_status', 'MATERIALIZED'),
                        resolved_by=?, resolved_at=CURRENT_TIMESTAMP,
                        resolution_version=resolution_version+1, updated_at=CURRENT_TIMESTAMP
                    WHERE import_batch_id=? AND case_type='SOURCE_ORDER_CANDIDATE' AND status='OPEN'
                    """,
                    context.operator(),
                    batchId);
            jdbc.update(
                    """
                    UPDATE app.import_batches
                    SET error_detail=?::jsonb, status='COMPLETED', processed_at=CURRENT_TIMESTAMP
                    WHERE id=?
                    """,
                    json(updated),
                    batchId);
        } else {
            updated.put("candidate_status", "PENDING");
            updated.put("source_order_candidates", remaining);
            jdbc.update(
                    "UPDATE app.import_batches SET error_detail=?::jsonb WHERE id=?",
                    json(updated),
                    batchId);
        }
        return true;
    }

    private List<SourceOrderReadinessCandidate> readinessCandidates(
            List<SourceOrderCandidate> candidates) {
        return candidates.stream().map(SourceOrderReadinessCandidate::from).toList();
    }

    /** 汇总单候选门禁异常里的行级阻断明细，供全阻断确认失败时原样透出。 */
    private void collectBlockedLines(BusinessException exception, List<Map<String, Object>> target) {
        if (!(exception.getDetails().get("lines") instanceof List<?> lines)) {
            return;
        }
        for (Object value : lines) {
            if (value instanceof Map<?, ?> line) {
                Map<String, Object> copy = new LinkedHashMap<>();
                line.forEach((key, item) -> copy.put(String.valueOf(key), item));
                target.add(Map.copyOf(copy));
            }
        }
    }

    private void materialize(
            long batchId, List<SourceOrderCandidate> candidates, CommandContext context) {
        for (SourceOrderCandidate candidate : candidates) {
            CanonicalOrderInput input = resolveStructuredCustomer(candidate.order());
            OrderDetailDto created = orders.createImportedWithinBatch(
                    input, batchId, context, candidate.actor());
            linkRows(batchId, candidate, created);
        }
    }

    private void linkRows(long batchId, SourceOrderCandidate candidate, OrderDetailDto order) {
        int lineCursor = 0;
        for (SourceOrderCandidate.CandidateRow row : candidate.rows()) {
            if (lineCursor + row.partitionCount() > order.lines().size()) {
                throw new IllegalStateException("来源候选的分片数超过正式订单行数");
            }
            List<OrderLineDto> partitionLines = order.lines()
                    .subList(lineCursor, lineCursor + row.partitionCount());
            lineCursor += row.partitionCount();
            List<String> exceptions = partitionLines.stream()
                    .map(OrderLineDto::exceptionCode)
                    .filter(Objects::nonNull)
                    .toList();
            if (!exceptions.isEmpty()) {
                throw new BusinessException(
                        409,
                        "IMPORT_BATCH_BLOCKED",
                        "候选在正式订单创建前发生映射漂移",
                        List.of(),
                        Map.of(
                                "blocking_type", "ORDER_MAPPING",
                                "lines", List.of(Map.of(
                                        "raw_import_row_id", Long.toString(row.rawImportRowId()),
                                        "reason_codes", exceptions))));
            }
            OrderLineDto primary = partitionLines.getFirst();
            jdbc.update(
                    """
                    UPDATE app.raw_import_rows
                    SET status='ACCEPTED', error_code=NULL, error_detail=NULL,
                        order_id=?, order_line_id=?, updated_at=CURRENT_TIMESTAMP
                    WHERE id=? AND import_batch_id=?
                    """,
                    Long.valueOf(order.id()),
                    Long.valueOf(primary.id()),
                    row.rawImportRowId(),
                    batchId);
            for (int partitionNo = 0; partitionNo < partitionLines.size(); partitionNo++) {
                jdbc.update(
                        """
                        INSERT INTO app.raw_import_row_order_lines(raw_import_row_id, order_line_id, partition_no)
                        VALUES (?, ?, ?)
                        ON CONFLICT DO NOTHING
                        """,
                        row.rawImportRowId(),
                        Long.valueOf(partitionLines.get(partitionNo).id()),
                        partitionNo + 1);
            }
        }
        if (lineCursor != order.lines().size()) {
            throw new IllegalStateException("来源候选的分片未覆盖全部正式订单行");
        }
    }

    private CanonicalOrderInput resolveStructuredCustomer(CanonicalOrderInput input) {
        CustomerInput candidate = input.customer();
        if (candidate == null
                || candidate.customerCode() != null
                || candidate.sourceCustomerRef() == null
                || !candidate.sourceCustomerRef().startsWith("CONTACT-")) {
            return input;
        }
        Receiver receiver = input.receiver();
        CustomerInput resolved = importedCustomers.resolve(
                input.source(),
                receiver == null ? null : receiver.name(),
                receiver == null ? null : receiver.phone());
        if (!Objects.equals(candidate.sourceCustomerRef(), resolved.sourceCustomerRef())) {
            throw BusinessException.unprocessable(
                    "STRUCTURED_CUSTOMER_IDENTITY_MISMATCH",
                    "结构化订单的客户身份与收货信息不一致，已停止创建订单");
        }
        return new CanonicalOrderInput(
                input.source(),
                input.sourceRef(),
                input.sourceVersion(),
                resolved,
                input.receiver(),
                input.items(),
                input.settlement(),
                input.sourceOrderedAt(),
                input.remark(),
                input.evidenceRefs());
    }

    private StagedPayload stagedPayload(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            if (!root.isObject()) {
                return null;
            }
            String status = root.path("candidate_status").asText();
            int snapshotVersion = root.path("candidate_snapshot_version").asInt(0);
            boolean hasMaterializationCandidates = root.path("source_order_candidates").isArray();
            boolean hasReadinessCandidates = root.path("source_order_readiness_candidates").isArray();
            if ("PENDING".equals(status) && !hasMaterializationCandidates) {
                throw new IllegalStateException("待成单来源批次缺少候选快照");
            }
            if ("PENDING".equals(status) && snapshotVersion != SourceOrderCandidate.SNAPSHOT_VERSION) {
                throw new BusinessException(
                        409,
                        "IMPORT_BATCH_BLOCKED",
                        "来源订单候选缺少可验证的主数据快照版本，请重新导入批次",
                        List.of(),
                        Map.of(
                                "blocking_type", "CANDIDATE_SNAPSHOT_VERSION",
                                "snapshot_version", snapshotVersion,
                                "required_version", SourceOrderCandidate.SNAPSHOT_VERSION));
            }
            if ("MATERIALIZED".equals(status) && !hasReadinessCandidates) {
                throw new IllegalStateException("已成单来源批次缺少 SKU 门禁快照");
            }
            List<SourceOrderCandidate> candidates = hasMaterializationCandidates
                    ? objectMapper.convertValue(
                            root.path("source_order_candidates"),
                            new TypeReference<List<SourceOrderCandidate>>() {})
                    : List.of();
            List<SourceOrderReadinessCandidate> readinessCandidates;
            if (hasReadinessCandidates) {
                readinessCandidates = objectMapper.convertValue(
                        root.path("source_order_readiness_candidates"),
                        new TypeReference<List<SourceOrderReadinessCandidate>>() {});
            } else {
                readinessCandidates = readinessCandidates(candidates);
            }
            Map<String, Object> rootMap = objectMapper.convertValue(root, new TypeReference<>() {});
            return new StagedPayload(status, candidates, readinessCandidates, rootMap);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            throw new IllegalStateException("来源订单候选快照无法解析", exception);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("来源订单候选无法序列化", exception);
        }
    }

    /** 仅供受认证业务工作台 rows API 使用的候选白名单；不持久化、不进入批次公开详情。 */
    record CandidateRowPreview(
            String receiverName,
            String receiverPhone,
            String receiverAddress,
            String productName,
            String specification,
            String sourceSkuRef) {

        static CandidateRowPreview from(Receiver receiver, List<OrderItemInput> items) {
            return new CandidateRowPreview(
                    receiver == null ? null : receiver.name(),
                    receiver == null ? null : receiver.phone(),
                    fullAddress(receiver),
                    joinedDistinct(items, OrderItemInput::productName),
                    joinedDistinct(items, OrderItemInput::specification),
                    unique(items, OrderItemInput::sourceSkuRef));
        }

        Map<String, Object> asParsedProjection() {
            Map<String, Object> parsed = new LinkedHashMap<>();
            putIfText(parsed, "receiver_name", receiverName);
            putIfText(parsed, "receiver_phone", receiverPhone);
            putIfText(parsed, "receiver_address", receiverAddress);
            putIfText(parsed, "product_name", productName);
            putIfText(parsed, "specification", specification);
            putIfText(parsed, "source_sku_ref", sourceSkuRef);
            return Map.copyOf(parsed);
        }

        private static String fullAddress(Receiver receiver) {
            if (receiver == null) {
                return null;
            }
            List<String> parts = new ArrayList<>();
            addPart(parts, receiver.province());
            addPart(parts, receiver.city());
            addPart(parts, receiver.district());
            addPart(parts, receiver.town());
            addPart(parts, receiver.address());
            return parts.isEmpty() ? null : String.join(" ", parts);
        }

        private static String joinedDistinct(
                List<OrderItemInput> items, Function<OrderItemInput, String> reader) {
            LinkedHashSet<String> values = values(items, reader);
            return values.isEmpty() ? null : String.join(" / ", values);
        }

        private static String unique(
                List<OrderItemInput> items, Function<OrderItemInput, String> reader) {
            LinkedHashSet<String> values = values(items, reader);
            return values.size() == 1 ? values.iterator().next() : null;
        }

        private static LinkedHashSet<String> values(
                List<OrderItemInput> items, Function<OrderItemInput, String> reader) {
            LinkedHashSet<String> values = new LinkedHashSet<>();
            for (OrderItemInput item : items) {
                if (item == null) {
                    continue;
                }
                String value = reader.apply(item);
                if (value != null && !value.isBlank()) {
                    values.add(value.trim());
                }
            }
            return values;
        }

        private static void addPart(List<String> parts, String value) {
            if (value != null && !value.isBlank()) {
                parts.add(value.trim());
            }
        }

        private static void putIfText(Map<String, Object> target, String key, String value) {
            if (value != null && !value.isBlank()) {
                target.put(key, value.trim());
            }
        }
    }

    private record StagedBatch(SourceChannel mappingChannel, String errorDetail, boolean confirmed) {}

    private record StagedPayload(
            String status,
            List<SourceOrderCandidate> candidates,
            List<SourceOrderReadinessCandidate> readinessCandidates,
            Map<String, Object> root) {}
}
