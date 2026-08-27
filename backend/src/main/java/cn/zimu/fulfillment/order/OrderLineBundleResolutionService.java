package cn.zimu.fulfillment.order;

import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.idempotency.IdempotencyService;
import cn.zimu.fulfillment.common.idempotency.IdempotentResult;
import cn.zimu.fulfillment.common.domain.DataScope;
import cn.zimu.fulfillment.common.event.OrderEventService;
import cn.zimu.fulfillment.common.web.CommandContext;
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

    public OrderLineBundleResolutionService(
            JdbcTemplate jdbc, IdempotencyService idempotency, OrderEventService events) {
        this.jdbc = jdbc;
        this.idempotency = idempotency;
        this.events = events;
    }

    public IdempotentResult<Map<String, Object>> resolveBundle(
            long orderLineId, long bundleId, String idempotencyKey, CommandContext context) {
        Map<String, Object> payload = Map.of("order_line_id", orderLineId, "bundle_id", bundleId);
        return idempotency.execute("order_line.resolve_bundle", idempotencyKey, payload, 200, () -> {
            Map<String, Object> line = requireResolvableLine(orderLineId);
            long orderId = ((Number) line.get("order_id")).longValue();
            String sourceChannel = (String) line.get("source_channel");
            String sourceRef = (String) line.get("sku_code_snapshot");

            requireConsistentMapping(sourceChannel, sourceRef, bundleId);
            List<Map<String, Object>> bom = requireActiveBundleBom(bundleId);

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

            // 整单门禁与创建端同构：仍有未解析行则维持 NEED_REVIEW，否则进 SKU_MAPPED
            Integer unresolved = jdbc.queryForObject(
                    "SELECT count(*) FROM app.order_lines WHERE order_id=? AND processing_stage='NEED_REVIEW'",
                    Integer.class, orderId);
            boolean fullyMapped = unresolved != null && unresolved == 0;
            if (fullyMapped) {
                jdbc.update(
                        """
                        UPDATE app.orders SET order_status='SKU_MAPPED',
                               lock_version = lock_version + 1, updated_at = now()
                         WHERE id = ? AND order_status = 'NEED_REVIEW'
                        """,
                        orderId);
                events.append(orderId, "SKU_MAPPED", orderLineId, null, null, null,
                        DataScope.BUSINESS,
                        Map.of("resolved_by_bundle", bundleId, "order_line_id", orderLineId),
                        context.operator());
            }

            // 原始行回到 ACCEPTED：血缘（order_id/order_line_id）从建单起就在行上，触发器放行
            jdbc.update(
                    """
                    UPDATE app.raw_import_rows
                       SET status='ACCEPTED', error_code=NULL, error_detail=NULL, updated_at=now()
                     WHERE order_line_id = ? AND status = 'NEED_REVIEW'
                    """,
                    orderLineId);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("order_line_id", String.valueOf(orderLineId));
            result.put("order_id", String.valueOf(orderId));
            result.put("bundle_id", String.valueOf(bundleId));
            result.put("component_count", bom.size());
            result.put("order_fully_mapped", fullyMapped);
            return result;
        });
    }

    /** 只接受「礼包行、待复核、因映射缺失、尚未展开」的行——其它状态就地修都是危险动作。 */
    private Map<String, Object> requireResolvableLine(long orderLineId) {
        List<Map<String, Object>> rows = jdbc.query(
                """
                SELECT ol.id, ol.order_id, ol.line_type, ol.processing_stage, ol.exception_code,
                       ol.bundle_id, ol.sku_code_snapshot, ol.requested_quantity,
                       o.source_channel, o.order_status,
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
                    row.put("sku_code_snapshot", rs.getString("sku_code_snapshot"));
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
        if (!"CUSTOM_BUNDLE".equals(line.get("line_type"))
                || !"NEED_REVIEW".equals(line.get("processing_stage"))
                || !"SKU_MAPPING_REQUIRED".equals(line.get("exception_code"))
                || line.get("bundle_id") != null
                || ((Number) line.get("components")).intValue() != 0) {
            throw BusinessException.unprocessable(
                    "BUNDLE_LINE_NOT_RESOLVABLE",
                    "只有「礼包映射缺失、尚未展开」的待复核礼包行可以就地解析");
        }
        return line;
    }

    /** 主数据一致性门禁：映射必须已存在、启用、乘数 1，且指向的就是这个礼包——与 resolveSku 的冲突语义同构。 */
    private void requireConsistentMapping(String sourceChannel, String sourceRef, long bundleId) {
        List<Long> mapped = jdbc.query(
                """
                SELECT scb.bundle_id FROM app.source_channel_bundles scb
                WHERE scb.source_channel = ? AND scb.source_bundle_ref = ?
                  AND scb.active AND scb.quantity_multiplier = 1
                """,
                (rs, n) -> rs.getLong(1),
                sourceChannel, sourceRef);
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
