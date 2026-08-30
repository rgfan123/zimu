package cn.zimu.fulfillment.order;

import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.idempotency.IdempotencyService;
import cn.zimu.fulfillment.common.idempotency.IdempotentResult;
import cn.zimu.fulfillment.common.domain.DataScope;
import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.common.event.OrderEventService;
import cn.zimu.fulfillment.common.web.CommandContext;
import cn.zimu.fulfillment.fulfillment.FulfillmentRepository;
import cn.zimu.fulfillment.fulfillment.InitialFulfillmentService;
import cn.zimu.fulfillment.order.domain.Order;
import cn.zimu.fulfillment.order.domain.OrderLine;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
            Map<String, Object> line = requireResolvableLine(orderLineId, bundleId);
            long orderId = ((Number) line.get("order_id")).longValue();
            String sourceChannel = (String) line.get("source_channel");
            String sourceRef = (String) line.get("source_bundle_ref");
            String productName = (String) line.get("product_name_snapshot");
            boolean alreadyExpanded = line.get("bundle_id") != null;

            requireConsistentMapping(sourceChannel, sourceRef, productName, bundleId);
            List<Map<String, Object>> bom = requireActiveBundleBom(bundleId);

            if (!alreadyExpanded) {
                BigDecimal requested = (BigDecimal) line.get("requested_quantity");
                if (requested.stripTrailingZeros().scale() > 0) {
                    throw BusinessException.unprocessable("BUNDLE_QUANTITY_NOT_INTEGER", "礼包行数量必须为整数");
                }

                // 同履约方门禁：与 createBundleLine 的 BUNDLE_MIXED_PROVIDERS 同源
                Long providerId = ((Number) bom.getFirst().get("fulfillment_provider_id")).longValue();
                for (Map<String, Object> item : bom) {
                    if (!Objects.equals(((Number) item.get("fulfillment_provider_id")).longValue(), providerId)) {
                        throw BusinessException.unprocessable(
                                "BUNDLE_MIXED_PROVIDERS", "礼包组件必须归属同一履约方");
                    }
                }

                // 库触发器规定：礼包行必须先落履约方，才允许挂组件——先改行，后插组件
                jdbc.update(
                        """
                        UPDATE app.order_lines
                           SET bundle_id = ?, fulfillment_provider_id = ?,
                               processing_stage = 'READY_TO_EXPORT',
                               exception_code = NULL, exception_reason = NULL,
                               mapping_multiplier_snapshot = 1.000,
                               source_quantity_snapshot = COALESCE(source_quantity_snapshot, requested_quantity),
                               updated_at = now()
                         WHERE id = ?
                        """,
                        bundleId, providerId, orderLineId);

                int componentNo = 1;
                for (Map<String, Object> item : bom) {
                    BigDecimal perBundle = (BigDecimal) item.get("quantity_per_bundle");
                    jdbc.update(
                            """
                            INSERT INTO app.order_line_components
                                (order_line_id, component_no, sku_id, quantity_per_bundle, total_quantity,
                                 product_name_snapshot, specification_snapshot, unit_snapshot)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                            """,
                            orderLineId,
                            componentNo++,
                            ((Number) item.get("sku_id")).longValue(),
                            perBundle,
                            requested.multiply(perBundle).setScale(3, RoundingMode.HALF_UP),
                            item.get("product_name"),
                            item.get("specification"),
                            item.get("unit"));
                }
            }

            // 发货批次路由（candidateRows）只认挂了履约单元的行——创建端逐行建
            // （OrderCreateService），复核端补建（resumeOrderIfReady），此处同样必须建，
            // 否则批次确认时该行进不了发货批次，卡 IMPORT_BATCH_EXPORT_INCOMPLETE（生产实证）
            if (!fulfillments.existsByOrderLineId(orderLineId)) {
                Order orderEntity = orders.findById(orderId).orElseThrow();
                OrderLine lineEntity = orderLines.findById(orderLineId).orElseThrow();
                initialFulfillments.create(orderEntity, lineEntity);
            }

            // 整单门禁与创建端同构：仍有未解析行则维持 NEED_REVIEW，否则进 SKU_MAPPED
            Integer unresolved = jdbc.queryForObject(
                    "SELECT count(*) FROM app.order_lines WHERE order_id=? AND processing_stage='NEED_REVIEW'",
                    Integer.class, orderId);
            boolean fullyMapped = unresolved != null && unresolved == 0;
            if (fullyMapped) {
                int flipped = jdbc.update(
                        """
                        UPDATE app.orders SET order_status='SKU_MAPPED',
                               lock_version = lock_version + 1, updated_at = now()
                         WHERE id = ? AND order_status = 'NEED_REVIEW'
                        """,
                        orderId);
                if (flipped > 0) {
                    events.append(orderId, "SKU_MAPPED", orderLineId, null, null, null,
                            DataScope.BUSINESS,
                            Map.of("resolved_by_bundle", bundleId, "order_line_id", orderLineId),
                            context.operator());
                }
            }

            // 原始行回到 ACCEPTED：血缘（order_id/order_line_id）从建单起就在行上，触发器放行
            jdbc.update(
                    """
                    UPDATE app.raw_import_rows
                       SET status='ACCEPTED', error_code=NULL, error_detail=NULL, updated_at=now()
                     WHERE order_line_id = ? AND status = 'NEED_REVIEW'
                    """,
                    orderLineId);

            // 顺手关掉本行自己的映射工单：批次确认闸与发货批次路由都把 OPEN 工单当拦路石，
            // 就地解析完还留着 OPEN 等于修好了门却锁着锁
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
                    bundleId, context.operator(), orderLineId);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("order_line_id", String.valueOf(orderLineId));
            result.put("order_id", String.valueOf(orderId));
            result.put("bundle_id", String.valueOf(bundleId));
            result.put("component_count",
                    alreadyExpanded ? ((Number) line.get("components")).intValue() : bom.size());
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
    private Map<String, Object> requireResolvableLine(long orderLineId, long requestedBundleId) {
        List<Map<String, Object>> rows = jdbc.query(
                """
                SELECT ol.id, ol.order_id, ol.line_type, ol.processing_stage, ol.exception_code,
                       ol.bundle_id, ol.requested_quantity,
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
                WHERE ol.id = ?
                """,
                (rs, n) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("order_id", rs.getLong("order_id"));
                    row.put("line_type", rs.getString("line_type"));
                    row.put("processing_stage", rs.getString("processing_stage"));
                    row.put("exception_code", rs.getString("exception_code"));
                    row.put("bundle_id", rs.getObject("bundle_id"));
                    row.put("source_bundle_ref", rs.getString("source_bundle_ref"));
                    row.put("product_name_snapshot", rs.getString("product_name_snapshot"));
                    row.put("requested_quantity", rs.getBigDecimal("requested_quantity"));
                    row.put("source_channel", rs.getString("source_channel"));
                    row.put("components", rs.getInt("components"));
                    return row;
                },
                orderLineId);
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
        if (mapped.stream().noneMatch(id -> id == bundleId)) {
            throw BusinessException.conflict(
                    "SOURCE_BUNDLE_MAPPING_CONFLICT", "该来源礼包已映射到其它礼包档案，请先处理主数据冲突");
        }
    }

    /** 礼包必须 ACTIVE 且 BOM 非空；组件 SKU 必须启用。空礼包展开出来就是一张发不了货的单。 */
    private List<Map<String, Object>> requireActiveBundleBom(long bundleId) {
        List<Map<String, Object>> bom = jdbc.query(
                """
                SELECT bi.sku_id, bi.quantity_per_bundle, s.fulfillment_provider_id,
                       p.product_name, s.specification, s.unit
                FROM app.bundle_items bi
                JOIN app.product_bundles b ON b.id = bi.bundle_id AND b.status = 'ACTIVE'
                JOIN app.skus s ON s.id = bi.sku_id AND s.active
                JOIN app.products p ON p.id = s.product_id
                WHERE bi.bundle_id = ?
                ORDER BY bi.sort_no
                """,
                (rs, n) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("sku_id", rs.getLong("sku_id"));
                    row.put("quantity_per_bundle", rs.getBigDecimal("quantity_per_bundle"));
                    row.put("fulfillment_provider_id", rs.getLong("fulfillment_provider_id"));
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
        return bom;
    }
}
