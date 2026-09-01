package cn.zimu.fulfillment.order;

import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.idempotency.IdempotencyService;
import cn.zimu.fulfillment.common.idempotency.IdempotentResult;
import cn.zimu.fulfillment.common.domain.DataScope;
import cn.zimu.fulfillment.common.domain.CountQuantity;
import cn.zimu.fulfillment.common.domain.CountQuantity.InvalidCountQuantityException;
import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.common.event.OrderEventService;
import cn.zimu.fulfillment.common.web.CommandContext;
import cn.zimu.fulfillment.fulfillment.FulfillmentRepository;
import cn.zimu.fulfillment.fulfillment.InitialFulfillmentService;
import cn.zimu.fulfillment.order.domain.Order;
import cn.zimu.fulfillment.order.domain.OrderLine;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 礼包行的就地解析（codex「礼包归一化」spec 02 票的最小闭环）。
 *
 * <p><b>为什么必须就地修，而不是删了重导</b>：2026-08-27 生产实证，四条绕行路
 * 全部被架构有意堵死——订单删不掉（order_events 只增触发器）、渠道单号改不了
 * （身份不可变触发器）、已接受行改不了（血缘冻结触发器）、同号重导插不进
 * （uq_orders_scope_source_ref）。架构给「映射补配之后」留的唯一一扇门就是
 * 原地把礼包行展开。单品行早有同型入口（resolveSku），礼包一直缺席。
 *
 * <p>字段语义逐项对照 {@code OrderCreateService.createBundleLine}：
 * 组件快照、数量换算、同履约方门禁、READY_TO_EXPORT 落点全部同源；
 * 差别只在组件清单来源——创建时来自来源文件，此处来自礼包档案 BOM
 * （映射补配后档案就是权威，正是 spec 的裁决：礼包档案是 BOM 真源）。
 */
@Service
public class OrderLineBundleResolutionService {

    private final JdbcTemplate jdbc;
    private final IdempotencyService idempotency;
    private final OrderEventService events;
    private final OrderRepository orders;
    private final OrderLineRepository orderLines;
    private final FulfillmentRepository fulfillments;
    private final InitialFulfillmentService initialFulfillments;
    private final SourceBundleResolver sourceBundleResolver;

    public OrderLineBundleResolutionService(
            JdbcTemplate jdbc,
            IdempotencyService idempotency,
            OrderEventService events,
            OrderRepository orders,
            OrderLineRepository orderLines,
            FulfillmentRepository fulfillments,
            InitialFulfillmentService initialFulfillments,
            SourceBundleResolver sourceBundleResolver) {
        this.jdbc = jdbc;
        this.idempotency = idempotency;
        this.events = events;
        this.orders = orders;
        this.orderLines = orderLines;
        this.fulfillments = fulfillments;
        this.initialFulfillments = initialFulfillments;
        this.sourceBundleResolver = sourceBundleResolver;
    }

    public IdempotentResult<Map<String, Object>> resolveBundle(
            long orderLineId, long bundleId, String idempotencyKey, CommandContext context) {
        Map<String, Object> payload = Map.of("order_line_id", orderLineId, "bundle_id", bundleId);
        return idempotency.execute("order_line.resolve_bundle", idempotencyKey, payload, 200, () -> {
            // 不同幂等键仍可能同时操作同一订单。所有该入口的写事务统一先锁订单，再锁订单行，
            // 最后锁复核事项；后续判定 OPEN 工单、全行结构及建 fulfillment 都基于同一串行快照。
            long orderId = lockOrderForLine(orderLineId);
            lockOrderLines(orderId);
            Map<String, Object> line = requireResolvableLine(orderLineId, orderId, bundleId);
            lockOrderReviews(orderId);
            String sourceChannel = (String) line.get("source_channel");
            String sourceRef = (String) line.get("source_bundle_ref");
            String productName = (String) line.get("product_name_snapshot");
            boolean alreadyExpanded = line.get("bundle_id") != null;

            requireConsistentMapping(sourceChannel, sourceRef, productName, bundleId);
            List<Map<String, Object>> bom = requireActiveBundleBom(bundleId);
            List<List<Map<String, Object>>> providerGroups = groupBomByProvider(bom);

            // V88 前存量行没有 source_sku_ref。必须在任何分片复制和 fulfillment 创建之前，
            // 把本次实际命中的来源键原子写回；V89 会在分配完成后冻结它。
            backfillSourceSkuRef(orderLineId, sourceRef);
            List<Long> partitionLineIds;

            if (!alreadyExpanded) {
                int requested = ((Number) line.get("requested_quantity")).intValue();

                partitionLineIds = expandByProvider(
                        line, orderLineId, orderId, bundleId, requested, providerGroups);
            } else {
                partitionLineIds = existingPartitionLineIds(orderLineId);
            }
            backfillPartitionSourceSkuRefs(partitionLineIds, sourceRef);

            closeResolvedMappingReviews(orderLineId, bundleId, context.operator());

            // 不能用 processing_stage 作为「结构已映射」的替身：订单级复核会让已经映射的普通 SKU
            // 同样停在 NEED_REVIEW。先关本工单，再同时检查其它 OPEN 工单与每一行真实结构。
            boolean fullyMapped = !hasOpenReviews(orderId) && allLineStructuresMapped(orderId);
            if (fullyMapped) {
                resumeWholeOrder(orderId, orderLineId, bundleId, context);
            } else {
                keepWholeOrderFailClosed(orderId);
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("order_line_id", String.valueOf(orderLineId));
            result.put("order_id", String.valueOf(orderId));
            result.put("bundle_id", String.valueOf(bundleId));
            result.put("component_count", bom.size());
            result.put("partition_count", providerGroups.size());
            result.put("order_fully_mapped", fullyMapped);
            return result;
        });
    }

    /**
     * 可解析 = 两种状态之一：
     * ① 未展开——礼包行、待复核、因映射缺失、无组件（首次解析）；
     * ② 已按同一礼包展开——收敛重放，只补缺的后续步骤（履约单元/整单状态/原始行），
     *    生产实证：展开与建履约单元之间一旦断电/缺步骤，必须能重进把状态收敛齐。
     * 已按「其它」礼包展开的行拒绝——那是主数据冲突，不是重放。
     */
    private long lockOrderForLine(long orderLineId) {
        List<Long> orderIds = jdbc.query(
                "SELECT order_id FROM app.order_lines WHERE id=?",
                (rs, n) -> rs.getLong(1),
                orderLineId);
        if (orderIds.isEmpty()) {
            throw BusinessException.notFound("订单行不存在");
        }
        long orderId = orderIds.getFirst();
        List<Long> locked = jdbc.query(
                "SELECT id FROM app.orders WHERE id=? FOR UPDATE",
                (rs, n) -> rs.getLong(1),
                orderId);
        if (locked.isEmpty()) {
            throw BusinessException.notFound("订单不存在");
        }
        return orderId;
    }

    private void lockOrderLines(long orderId) {
        jdbc.query(
                "SELECT id FROM app.order_lines WHERE order_id=? ORDER BY id FOR UPDATE",
                rs -> {
                    // 行锁本身就是结果；无需把主键物化到业务对象。
                },
                orderId);
    }

    private void lockOrderReviews(long orderId) {
        jdbc.query(
                "SELECT id FROM app.review_cases WHERE order_id=? ORDER BY id FOR UPDATE",
                rs -> {
                    // 与订单行相同，只消费结果以持有到事务结束。
                },
                orderId);
    }

    private Map<String, Object> requireResolvableLine(
            long orderLineId, long lockedOrderId, long requestedBundleId) {
        List<Map<String, Object>> rows = jdbc.query(
                """
                SELECT ol.id, ol.order_id, ol.line_no, ol.line_type, ol.processing_stage, ol.exception_code,
                       ol.bundle_id, ol.requested_quantity, ol.source_sku_ref,
                       o.source_channel, o.order_status,
                       ol.product_name_snapshot,
                       -- 来源礼包第一把键 = 与 SKU 映射同源的 source_sku_ref（V88 起随建单落行）。
                       -- 后三级是存量行的回退链：V88 之前建的行没有这一列，行为必须逐字节不变，
                       -- 否则运营配好的礼包会突然查不到（编码快照 → 原始行主商品编码 → 商品名）。
                       COALESCE(
                           NULLIF(ol.source_sku_ref, ''),
                           NULLIF(ol.sku_code_snapshot, ''),
                           (SELECT rir.raw_cells->>'主商品编码' FROM app.raw_import_rows rir
                             WHERE rir.order_line_id = ol.id LIMIT 1),
                           ol.product_name_snapshot)                       AS source_bundle_ref,
                       (SELECT count(*) FROM app.order_line_components c WHERE c.order_line_id = ol.id) AS components
                FROM app.order_lines ol JOIN app.orders o ON o.id = ol.order_id
                WHERE ol.id = ? AND ol.order_id = ?
                FOR UPDATE OF ol
                """,
                (rs, n) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("order_id", rs.getLong("order_id"));
                    row.put("line_no", rs.getInt("line_no"));
                    row.put("line_type", rs.getString("line_type"));
                    row.put("processing_stage", rs.getString("processing_stage"));
                    row.put("exception_code", rs.getString("exception_code"));
                    row.put("bundle_id", rs.getObject("bundle_id"));
                    row.put("source_sku_ref", rs.getString("source_sku_ref"));
                    row.put("source_bundle_ref", rs.getString("source_bundle_ref"));
                    row.put("product_name_snapshot", rs.getString("product_name_snapshot"));
                    row.put("requested_quantity", rs.getInt("requested_quantity"));
                    row.put("source_channel", rs.getString("source_channel"));
                    row.put("components", rs.getInt("components"));
                    return row;
                },
                orderLineId,
                lockedOrderId);
        if (rows.isEmpty()) {
            throw BusinessException.notFound("订单行不存在");
        }
        Map<String, Object> line = rows.getFirst();
        if (!"CUSTOM_BUNDLE".equals(line.get("line_type"))) {
            throw BusinessException.unprocessable(
                    "BUNDLE_LINE_NOT_RESOLVABLE", "只有礼包行可以就地解析礼包");
        }
        Object boundBundle = line.get("bundle_id");
        if (boundBundle != null) {
            if (((Number) boundBundle).longValue() != requestedBundleId
                    || ((Number) line.get("components")).intValue() == 0) {
                throw BusinessException.conflict(
                        "SOURCE_BUNDLE_MAPPING_CONFLICT", "该行已按其它礼包档案展开，请先处理主数据冲突");
            }
            return line;
        }
        if (!"NEED_REVIEW".equals(line.get("processing_stage"))
                || !"SKU_MAPPING_REQUIRED".equals(line.get("exception_code"))
                || ((Number) line.get("components")).intValue() != 0) {
            throw BusinessException.unprocessable(
                    "BUNDLE_LINE_NOT_RESOLVABLE",
                    "只有「礼包映射缺失、尚未展开」的待复核礼包行可以就地解析");
        }
        return line;
    }

    private void backfillSourceSkuRef(long orderLineId, String sourceRef) {
        if (sourceRef == null || sourceRef.isBlank()) {
            throw BusinessException.unprocessable(
                    "SOURCE_BUNDLE_MAPPING_MISSING", "待解析礼包缺少可持久化的来源商品标识");
        }
        jdbc.update(
                """
                UPDATE app.order_lines
                   SET source_sku_ref=?, updated_at=now()
                 WHERE id=? AND NULLIF(btrim(source_sku_ref), '') IS NULL
                """,
                sourceRef,
                orderLineId);
    }

    private void backfillPartitionSourceSkuRefs(List<Long> lineIds, String sourceRef) {
        for (Long lineId : lineIds) {
            jdbc.update(
                    """
                    UPDATE app.order_lines
                       SET source_sku_ref=?, updated_at=now()
                     WHERE id=? AND NULLIF(btrim(source_sku_ref), '') IS NULL
                    """,
                    sourceRef,
                    lineId);
        }
    }

    private void closeResolvedMappingReviews(long orderLineId, long bundleId, String operator) {
        jdbc.update(
                """
                UPDATE app.review_cases
                   SET status='RESOLVED',
                       resolution=jsonb_build_object(
                           'resolution_type', 'BUNDLE_RESOLVED',
                           'bundle_id', ?::text),
                       resolved_by=?, resolved_at=now(), updated_at=now()
                 WHERE order_line_id = ? AND status = 'OPEN'
                   AND reason_code IN ('SKU_MAPPING_REQUIRED', 'SKU_MAPPING_CONFLICT')
                """,
                bundleId,
                operator,
                orderLineId);
    }

    private boolean hasOpenReviews(long orderId) {
        Boolean exists = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM app.review_cases WHERE order_id=? AND status='OPEN')",
                Boolean.class,
                orderId);
        return Boolean.TRUE.equals(exists);
    }

    private boolean allLineStructuresMapped(long orderId) {
        Boolean mapped = jdbc.queryForObject(
                """
                SELECT NOT EXISTS (
                    SELECT 1
                    FROM app.order_lines ol
                    WHERE ol.order_id=?
                      AND CASE ol.line_type
                          WHEN 'SINGLE' THEN ol.sku_id IS NULL OR ol.fulfillment_provider_id IS NULL
                          WHEN 'CUSTOM_BUNDLE' THEN ol.bundle_id IS NULL
                              OR ol.fulfillment_provider_id IS NULL
                              OR NOT EXISTS (
                                  SELECT 1 FROM app.order_line_components c WHERE c.order_line_id=ol.id)
                          ELSE true
                      END
                )
                """,
                Boolean.class,
                orderId);
        return Boolean.TRUE.equals(mapped);
    }

    private void resumeWholeOrder(
            long orderId, long resolvedLineId, long bundleId, CommandContext context) {
        jdbc.update(
                """
                UPDATE app.order_lines
                   SET processing_stage='READY_TO_EXPORT', exception_code=NULL, exception_reason=NULL,
                       updated_at=now()
                 WHERE order_id=? AND processing_stage='NEED_REVIEW'
                """,
                orderId);

        // 发货批次路由只认挂了履约单元的行。最后一道复核关闭时必须补齐整单，
        // 不能只给刚解析的礼包行建 fulfillment。
        Order orderEntity = orders.findById(orderId).orElseThrow();
        for (OrderLine lineEntity : orderLines.findByOrderIdOrderByLineNoAsc(orderId)) {
            if (!fulfillments.existsByOrderLineId(lineEntity.getId())) {
                initialFulfillments.create(orderEntity, lineEntity);
            }
        }

        int flipped = jdbc.update(
                """
                UPDATE app.orders SET order_status='SKU_MAPPED',
                       lock_version=lock_version+1, updated_at=now()
                 WHERE id=? AND order_status='NEED_REVIEW'
                """,
                orderId);
        jdbc.update(
                """
                UPDATE app.raw_import_rows
                   SET status='ACCEPTED', error_code=NULL, error_detail=NULL, updated_at=now()
                 WHERE order_id=? AND status='NEED_REVIEW'
                """,
                orderId);
        jdbc.update(
                """
                UPDATE app.import_batches ib
                   SET status=CASE WHEN EXISTS (
                           SELECT 1 FROM app.raw_import_rows rir
                           WHERE rir.import_batch_id=ib.id
                             AND rir.status IN ('NEED_REVIEW','REJECTED'))
                       THEN 'COMPLETED_WITH_REVIEW' ELSE 'COMPLETED' END,
                       processed_at=now()
                 WHERE ib.id=(SELECT source_import_batch_id FROM app.orders WHERE id=?)
                   AND ib.batch_type='SOURCE_ORDER'
                """,
                orderId);
        if (flipped > 0) {
            events.append(orderId, "SKU_MAPPED", resolvedLineId, null, null, null,
                    DataScope.BUSINESS,
                    Map.of("resolved_by_bundle", bundleId, "order_line_id", resolvedLineId),
                    context.operator());
        }
    }

    private void keepWholeOrderFailClosed(long orderId) {
        jdbc.update(
                """
                UPDATE app.order_lines
                   SET processing_stage='NEED_REVIEW', updated_at=now()
                 WHERE order_id=? AND processing_stage='READY_TO_EXPORT'
                   AND fulfillment_committed_at IS NULL
                """,
                orderId);
    }

    /**
     * 主数据一致性门禁：映射必须已存在、启用、乘数 1，且指向的就是这个礼包——与 resolveSku 的冲突语义同构。
     *
     * <p>查法本身不在这里，而在共用接缝 {@link SourceBundleResolver#mappedBundleIds}：
     * 人工补救与两条自动链路必须用同一把键，否则又会回到「自动展开按 ID 查、人工补救按名称查」
     * 的老毛病——同一条映射，一条路命中另一条不命中。
     */
    private void requireConsistentMapping(
            String sourceChannel, String sourceRef, String productName, long bundleId) {
        List<Long> mapped = sourceBundleResolver.mappedBundleIds(
                SourceChannel.valueOf(sourceChannel), sourceRef, productName);
        if (mapped.isEmpty()) {
            throw BusinessException.unprocessable(
                    "SOURCE_BUNDLE_MAPPING_MISSING",
                    "来源礼包映射仍未配置（" + sourceChannel + ":" + sourceRef + "），请先在映射矩阵补配");
        }
        if (mapped.size() != 1 || mapped.getFirst() != bundleId) {
            throw BusinessException.conflict(
                    "SOURCE_BUNDLE_MAPPING_CONFLICT", "该来源礼包已映射到其它礼包档案，请先处理主数据冲突");
        }
    }

    /** 礼包必须 ACTIVE 且 BOM 非空；组件 SKU 必须启用。空礼包展开出来就是一张发不了货的单。 */
    private List<Map<String, Object>> requireActiveBundleBom(long bundleId) {
        List<Map<String, Object>> bom = jdbc.query(
                """
                SELECT bi.sku_id, bi.quantity_per_bundle, s.fulfillment_provider_id, s.active,
                       p.product_name, s.specification, s.unit
                FROM app.bundle_items bi
                JOIN app.product_bundles b ON b.id = bi.bundle_id AND b.status = 'ACTIVE'
                JOIN app.skus s ON s.id = bi.sku_id
                JOIN app.products p ON p.id = s.product_id
                WHERE bi.bundle_id = ?
                ORDER BY bi.sort_no
                """,
                (rs, n) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("sku_id", rs.getLong("sku_id"));
                    row.put("quantity_per_bundle", rs.getInt("quantity_per_bundle"));
                    row.put("fulfillment_provider_id", rs.getLong("fulfillment_provider_id"));
                    row.put("sku_active", rs.getBoolean("active"));
                    row.put("product_name", rs.getString("product_name"));
                    row.put("specification", rs.getString("specification"));
                    row.put("unit", rs.getString("unit"));
                    return row;
                },
                bundleId);
        if (bom.isEmpty()) {
            throw BusinessException.unprocessable(
                    "BUNDLE_BOM_EMPTY", "礼包档案无有效 BOM（未启用或组件 SKU 停用），不能展开");
        }
        if (bom.stream().anyMatch(item -> !Boolean.TRUE.equals(item.get("sku_active")))) {
            throw BusinessException.unprocessable(
                    "BUNDLE_BOM_INACTIVE", "礼包 BOM 含停用 SKU，不能展开");
        }
        return bom;
    }

    private List<List<Map<String, Object>>> groupBomByProvider(List<Map<String, Object>> bom) {
        Map<Long, List<Map<String, Object>>> groups = new LinkedHashMap<>();
        for (Map<String, Object> item : bom) {
            long providerId = ((Number) item.get("fulfillment_provider_id")).longValue();
            groups.computeIfAbsent(providerId, ignored -> new ArrayList<>()).add(item);
        }
        return groups.values().stream().map(List::copyOf).toList();
    }

    /**
     * 人工补配与自动导入保持同一分片模型：原行承载第一 provider，其余 provider 新增相邻订单行；
     * 同一 raw_import_row 通过 partition_no=1..N 关联所有分片，primary order_line_id 保持不变。
     */
    private List<Long> expandByProvider(
            Map<String, Object> sourceLine,
            long primaryLineId,
            long orderId,
            long bundleId,
            int requested,
            List<List<Map<String, Object>>> providerGroups) {
        int additional = providerGroups.size() - 1;
        int originalLineNo = ((Number) sourceLine.get("line_no")).intValue();
        if (additional > 0) {
            int maxLineNo = jdbc.queryForObject(
                    "SELECT max(line_no) FROM app.order_lines WHERE order_id=?", Integer.class, orderId);
            int offset = maxLineNo + additional + 1;
            jdbc.update(
                    "UPDATE app.order_lines SET line_no=line_no+? WHERE order_id=? AND line_no>?",
                    offset,
                    orderId,
                    originalLineNo);
            jdbc.update(
                    "UPDATE app.order_lines SET line_no=line_no-?+? WHERE order_id=? AND line_no>?",
                    offset,
                    additional,
                    orderId,
                    originalLineNo + offset);
        }

        List<Long> lineIds = new ArrayList<>(providerGroups.size());
        lineIds.add(primaryLineId);
        for (int partitionIndex = 0; partitionIndex < providerGroups.size(); partitionIndex++) {
            List<Map<String, Object>> group = providerGroups.get(partitionIndex);
            long providerId = ((Number) group.getFirst().get("fulfillment_provider_id")).longValue();
            long lineId;
            if (partitionIndex == 0) {
                lineId = primaryLineId;
                jdbc.update(
                        """
                        UPDATE app.order_lines
                           SET bundle_id=?, fulfillment_provider_id=?, processing_stage='NEED_REVIEW',
                               exception_code=NULL, exception_reason=NULL,
                               mapping_multiplier_snapshot=1.000,
                               source_quantity_snapshot=COALESCE(source_quantity_snapshot, requested_quantity),
                               updated_at=now()
                         WHERE id=?
                        """,
                        bundleId,
                        providerId,
                        primaryLineId);
            } else {
                lineId = jdbc.queryForObject(
                        """
                        INSERT INTO app.order_lines
                            (order_id, line_no, line_type, bundle_id, fulfillment_provider_id,
                             product_name_snapshot, sku_code_snapshot, source_sku_ref,
                             specification_snapshot, unit_snapshot, source_quantity_snapshot,
                             mapping_multiplier_snapshot, requested_quantity, processing_stage)
                        SELECT order_id, ?, line_type, ?, ?, product_name_snapshot, sku_code_snapshot,
                               source_sku_ref, specification_snapshot, unit_snapshot,
                               COALESCE(source_quantity_snapshot, requested_quantity), 1.000,
                               requested_quantity, 'NEED_REVIEW'
                        FROM app.order_lines WHERE id=?
                        RETURNING id
                        """,
                        Long.class,
                        originalLineNo + partitionIndex,
                        bundleId,
                        providerId,
                        primaryLineId);
                lineIds.add(lineId);
            }
            insertComponents(lineId, requested, group);
        }
        attachRawLineage(primaryLineId, lineIds);
        return List.copyOf(lineIds);
    }

    private void insertComponents(long lineId, int requested, List<Map<String, Object>> group) {
        int componentNo = 1;
        for (Map<String, Object> item : group) {
            int perBundle = ((Number) item.get("quantity_per_bundle")).intValue();
            jdbc.update(
                    """
                    INSERT INTO app.order_line_components
                        (order_line_id, component_no, sku_id, quantity_per_bundle, total_quantity,
                         product_name_snapshot, specification_snapshot, unit_snapshot)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    lineId,
                    componentNo++,
                    ((Number) item.get("sku_id")).longValue(),
                    perBundle,
                    multiplyComponentCount(requested, perBundle),
                    item.get("product_name"),
                    item.get("specification"),
                    item.get("unit"));
        }
    }

    private static int multiplyComponentCount(int requested, int perBundle) {
        try {
            return CountQuantity.multiplyPositive(requested, perBundle);
        } catch (InvalidCountQuantityException exception) {
            throw BusinessException.unprocessable(
                    "QUANTITY_SCALE", "礼包份数乘组件件数后超出 int32 件数范围，复核事项保持开放");
        }
    }

    private void attachRawLineage(long primaryLineId, List<Long> lineIds) {
        List<Long> rawIds = jdbc.query(
                """
                SELECT DISTINCT rir.id
                FROM app.raw_import_rows rir
                LEFT JOIN app.raw_import_row_order_lines rirol ON rirol.raw_import_row_id=rir.id
                WHERE rir.order_line_id=? OR rirol.order_line_id=?
                """,
                (rs, n) -> rs.getLong(1),
                primaryLineId,
                primaryLineId);
        for (Long rawId : rawIds) {
            for (int index = 0; index < lineIds.size(); index++) {
                jdbc.update(
                        """
                        INSERT INTO app.raw_import_row_order_lines(raw_import_row_id,order_line_id,partition_no)
                        VALUES (?,?,?) ON CONFLICT (raw_import_row_id,order_line_id) DO NOTHING
                        """,
                        rawId,
                        lineIds.get(index),
                        index + 1);
            }
        }
    }

    private List<Long> existingPartitionLineIds(long primaryLineId) {
        List<Long> lineIds = jdbc.query(
                """
                SELECT rirol.order_line_id
                FROM app.raw_import_rows rir
                JOIN app.raw_import_row_order_lines rirol ON rirol.raw_import_row_id=rir.id
                WHERE rir.order_line_id=?
                ORDER BY rirol.partition_no
                """,
                (rs, n) -> rs.getLong(1),
                primaryLineId);
        return lineIds.isEmpty() ? List.of(primaryLineId) : List.copyOf(lineIds);
    }
}
