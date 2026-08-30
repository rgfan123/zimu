package cn.zimu.fulfillment.file;

import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.web.CommandContext;
import cn.zimu.fulfillment.customer.ImportedCustomerService;
import cn.zimu.fulfillment.order.OrderCreateService;
import cn.zimu.fulfillment.order.dto.CanonicalOrderInput;
import cn.zimu.fulfillment.order.dto.CustomerInput;
import cn.zimu.fulfillment.order.dto.OrderDetailDto;
import cn.zimu.fulfillment.order.dto.OrderLineDto;
import cn.zimu.fulfillment.order.dto.Receiver;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
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

    void materializePrepared(
            long batchId,
            List<SourceOrderCandidate> candidates,
            CommandContext context) {
        materialize(batchId, candidates, context);
        persistMaterializedReadinessSnapshot(batchId, candidates);
    }

    /**
     * 重新确认曾因 SKU readiness 阻断的批次。独立事务保证候选要么全部成为正式订单，
     * 要么一个也不落库；提交后既有 ProviderFile 校验才能读取到完整订单集合。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
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
        Integer hardBlockers = jdbc.queryForObject(
                """
                SELECT count(*) FROM app.raw_import_rows
                WHERE import_batch_id=? AND status<>'ACCEPTED'
                  AND COALESCE(error_code, '') NOT IN ('SKU_READINESS', 'BATCH_ATOMIC_RELEASE_BLOCKED')
                """,
                Integer.class,
                batchId);
        if (hardBlockers != null && hardBlockers > 0) {
            throw BusinessException.conflict("IMPORT_BATCH_BLOCKED", "批次仍有文件或数据问题，不能创建正式订单");
        }
        readinessGate.requireReady(batch.mappingChannel(), payload.candidates());
        materialize(batchId, payload.candidates(), context);
        Map<String, Object> updated = new LinkedHashMap<>(payload.root());
        updated.put("candidate_status", "MATERIALIZED");
        updated.remove("readiness");
        updated.remove("source_order_candidates");
        updated.put("source_order_readiness_candidates", readinessCandidates(payload.candidates()));
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
        return true;
    }

    private void persistMaterializedReadinessSnapshot(
            long batchId, List<SourceOrderCandidate> candidates) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("candidate_status", "MATERIALIZED");
        detail.put("source_order_readiness_candidates", readinessCandidates(candidates));
        jdbc.update(
                "UPDATE app.import_batches SET error_detail=?::jsonb WHERE id=?",
                json(detail),
                batchId);
    }

    private List<SourceOrderReadinessCandidate> readinessCandidates(
            List<SourceOrderCandidate> candidates) {
        return candidates.stream().map(SourceOrderReadinessCandidate::from).toList();
    }

    private void materialize(
            long batchId, List<SourceOrderCandidate> candidates, CommandContext context) {
        for (SourceOrderCandidate candidate : candidates) {
            CanonicalOrderInput input = resolveStructuredCustomer(candidate.order());
            OrderDetailDto created = orders.createImported(
                            input,
                            batchId,
                            candidate.createIdempotencyKey(),
                            context,
                            candidate.actor())
                    .result();
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
            boolean hasMaterializationCandidates = root.path("source_order_candidates").isArray();
            boolean hasReadinessCandidates = root.path("source_order_readiness_candidates").isArray();
            if ("PENDING".equals(status) && !hasMaterializationCandidates) {
                throw new IllegalStateException("待成单来源批次缺少候选快照");
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

    private record StagedBatch(SourceChannel mappingChannel, String errorDetail, boolean confirmed) {}

    private record StagedPayload(
            String status,
            List<SourceOrderCandidate> candidates,
            List<SourceOrderReadinessCandidate> readinessCandidates,
            Map<String, Object> root) {}
}
