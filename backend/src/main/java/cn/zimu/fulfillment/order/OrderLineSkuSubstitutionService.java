package cn.zimu.fulfillment.order;

import cn.zimu.fulfillment.common.domain.DataScope;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.event.OrderEventService;
import cn.zimu.fulfillment.common.idempotency.IdempotencyService;
import cn.zimu.fulfillment.common.idempotency.IdempotentResult;
import cn.zimu.fulfillment.common.web.CommandContext;
import cn.zimu.fulfillment.fulfillment.InitialFulfillmentService;
import cn.zimu.fulfillment.order.domain.Order;
import cn.zimu.fulfillment.order.domain.OrderLine;
import cn.zimu.fulfillment.order.domain.OrderStatus;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 订单行「换货」：把已映射订单行的 sku_id 改指到另一个可履约的 SKU，用于补救京东库存/
 * 映射阻断（如某商品缺货、映射迟迟配不齐），而不用干等人工线下改配置。
 *
 * <p><b>为什么不是一条 {@code UPDATE order_lines SET sku_id=...} 就完事</b>：
 * {@code app.validate_order_line} 触发器规定「order-line allocation fields are immutable
 * after fulfillment creation」——只要该行已经建过 {@code fulfillments}（映射一解析完就会建，
 * 早于任何 Shipment），sku_id 就再也改不动。这不是 bug，是故意的：履约单元一旦建立，
 * 分配就该是不可变事实。真正的换货因此不是「改一个字段」，而是「撤销旧履约单元、建一个
 * 新的」——先删掉挂在旧履约单元下、还没发出去的 ShipmentItem（数据库允许删除的唯一窗口是
 * shipment_status='CREATED' 且未落 fulfillment_export_items，见
 * {@code app.protect_shipment_item_delete}），删掉旧 Fulfillment（{@code fulfillments}
 * 没有删除保护触发器），这时 order_lines 上就没有 fulfillments 了，sku_id 才改得动；
 * 改完立刻用 {@link InitialFulfillmentService} 建一个新履约单元，把原来的 ShipmentItem
 * 原样搬到新履约单元下——发货批次里的行没丢，只是背后指向的 SKU变了。
 *
 * <p>换完之后不在本服务内触发库存/映射重新核对：{@code ShipmentJdStockCheckService.probe()}
 * 明确要求「must run outside a database transaction」，不能嵌套在本服务的事务里调用。
 * 由调用方（前端）在收到响应后另发一次 {@code POST /api/v1/shipments/{id}/jd-stock-check}
 * 完成重新核对，响应里的 {@code affected_shipment_ids} 就是给调用方定位要重跑哪些发货批次。
 */
@Service
public class OrderLineSkuSubstitutionService {

    private static final String SCOPE = "order_line.substitute_sku";
    private static final String EVENT_TYPE = "ORDER_LINE_SKU_SUBSTITUTED";

    /** OrderStatus 没有「DELIVERED」值；SYNCED（来源回传已同步）与 CLOSED 是最接近的「已完成」
     *  终态，连同 SHIPPED/CANCELLED 一起在换货前一律拒绝——履约单元一旦发出就不该再回头换 SKU。 */
    private static final Set<OrderStatus> BLOCKED_ORDER_STATUSES =
            Set.of(OrderStatus.SHIPPED, OrderStatus.SYNCED, OrderStatus.CLOSED, OrderStatus.CANCELLED);

    private final JdbcTemplate jdbc;
    private final IdempotencyService idempotency;
    private final OrderEventService events;
    private final OrderRepository orders;
    private final OrderLineRepository orderLines;
    private final InitialFulfillmentService initialFulfillments;

    public OrderLineSkuSubstitutionService(
            JdbcTemplate jdbc,
            IdempotencyService idempotency,
            OrderEventService events,
            OrderRepository orders,
            OrderLineRepository orderLines,
            InitialFulfillmentService initialFulfillments) {
        this.jdbc = jdbc;
        this.idempotency = idempotency;
        this.events = events;
        this.orders = orders;
        this.orderLines = orderLines;
        this.initialFulfillments = initialFulfillments;
    }

    public IdempotentResult<Map<String, Object>> substituteSku(
            long orderLineId, long newSkuId, long expectedOrderVersion,
            String idempotencyKey, CommandContext context) {
        Map<String, Object> payload = Map.of(
                "order_line_id", orderLineId,
                "new_sku_id", newSkuId,
                "expected_order_version", expectedOrderVersion);
        return idempotency.execute(SCOPE, idempotencyKey, payload, 200, () -> {
            LineSnapshot line = requireSubstitutableLine(orderLineId);
            requireOrderNotShipped(line);
            if (line.orderLockVersion() != expectedOrderVersion) {
                throw BusinessException.conflict(
                        "ORDER_VERSION_CONFLICT", "订单已被其它操作修改，请刷新后重试");
            }
            NewSku newSku = requireSubstitutableSku(newSkuId, line);

            long oldFulfillmentId = requireFulfillmentId(orderLineId);
            List<ShipmentItemSnapshot> shipmentItems = requireDetachableShipmentItems(oldFulfillmentId);
            requireNoProcurement(oldFulfillmentId);

            // 撤销旧履约单元：先删 ShipmentItem（唯一允许删除的窗口，见类注释），再删 Fulfillment，
            // 这样 order_lines 上就不再 EXISTS fulfillments，sku_id 才改得动。
            jdbc.update("DELETE FROM app.shipment_items WHERE fulfillment_id=?", oldFulfillmentId);
            jdbc.update("DELETE FROM app.fulfillments WHERE id=?", oldFulfillmentId);
            // 只动 sku_id/sku_code_snapshot：product_name_snapshot 等来源快照是渠道血缘，不碰。
            jdbc.update(
                    "UPDATE app.order_lines SET sku_id=?, sku_code_snapshot=?, updated_at=now() WHERE id=?",
                    newSkuId, newSku.skuCode(), orderLineId);

            Order orderEntity = orders.findById(line.orderId()).orElseThrow();
            OrderLine lineEntity = orderLines.findById(orderLineId).orElseThrow();
            long newFulfillmentId = initialFulfillments.create(orderEntity, lineEntity).getId();

            List<String> affectedShipmentIds = new ArrayList<>();
            for (ShipmentItemSnapshot item : shipmentItems) {
                jdbc.update(
                        "INSERT INTO app.shipment_items(shipment_id, fulfillment_id, instructed_quantity) "
                                + "VALUES (?, ?, ?)",
                        item.shipmentId(), newFulfillmentId, item.instructedQuantity());
                affectedShipmentIds.add(String.valueOf(item.shipmentId()));
            }

            Map<String, Object> eventPayload = new LinkedHashMap<>();
            eventPayload.put("order_line_id", String.valueOf(orderLineId));
            eventPayload.put("old_sku_id", String.valueOf(line.skuId()));
            eventPayload.put("old_sku_code", line.skuCode());
            eventPayload.put("new_sku_id", String.valueOf(newSkuId));
            eventPayload.put("new_sku_code", newSku.skuCode());
            eventPayload.put("old_fulfillment_id", String.valueOf(oldFulfillmentId));
            eventPayload.put("new_fulfillment_id", String.valueOf(newFulfillmentId));
            eventPayload.put("shipment_ids", affectedShipmentIds);
            events.append(
                    line.orderId(), EVENT_TYPE, orderLineId, newFulfillmentId, null, null,
                    DataScope.BUSINESS, eventPayload, context.operator());

            int bumped = jdbc.update(
                    "UPDATE app.orders SET lock_version=lock_version+1, updated_at=now() "
                            + "WHERE id=? AND lock_version=?",
                    line.orderId(), expectedOrderVersion);
            if (bumped != 1) {
                throw BusinessException.conflict(
                        "ORDER_VERSION_CONFLICT", "订单已被其它操作修改，请刷新后重试");
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("order_line_id", String.valueOf(orderLineId));
            result.put("order_id", String.valueOf(line.orderId()));
            result.put("old_sku_id", String.valueOf(line.skuId()));
            result.put("new_sku_id", String.valueOf(newSkuId));
            result.put("new_sku_code", newSku.skuCode());
            result.put("fulfillment_id", String.valueOf(newFulfillmentId));
            result.put("affected_shipment_ids", affectedShipmentIds);
            result.put("order_version", String.valueOf(expectedOrderVersion + 1));
            return result;
        });
    }

    private LineSnapshot requireSubstitutableLine(long orderLineId) {
        List<LineSnapshot> rows = jdbc.query(
                """
                SELECT ol.order_id, ol.line_type, ol.sku_id, ol.sku_code_snapshot,
                       ol.fulfillment_provider_id, ol.fulfillment_committed_at,
                       o.order_status, o.lock_version
                FROM app.order_lines ol
                JOIN app.orders o ON o.id=ol.order_id AND o.data_scope='BUSINESS'
                WHERE ol.id=?
                FOR UPDATE OF ol, o
                """,
                (rs, rowNum) -> new LineSnapshot(
                        rs.getLong("order_id"),
                        rs.getString("line_type"),
                        rs.getObject("sku_id", Long.class),
                        rs.getString("sku_code_snapshot"),
                        rs.getLong("fulfillment_provider_id"),
                        rs.getTimestamp("fulfillment_committed_at") != null,
                        rs.getString("order_status"),
                        rs.getLong("lock_version")),
                orderLineId);
        if (rows.isEmpty()) {
            throw BusinessException.notFound("BUSINESS 订单行不存在");
        }
        LineSnapshot line = rows.getFirst();
        if (!"SINGLE".equals(line.lineType())) {
            throw BusinessException.unprocessable(
                    "ORDER_LINE_NOT_SINGLE", "只有单品行可以换货，礼包行请先在主数据页处理组件映射");
        }
        if (line.skuId() == null) {
            throw BusinessException.unprocessable(
                    "ORDER_LINE_NOT_MAPPED", "该行尚未完成 SKU 映射，请先走映射补配流程");
        }
        if (line.fulfillmentCommitted()) {
            throw BusinessException.conflict(
                    "ORDER_LINE_FULFILLMENT_COMMITTED", "该行履约信息已提交，分配不可再变更");
        }
        return line;
    }

    private void requireOrderNotShipped(LineSnapshot line) {
        OrderStatus status;
        try {
            status = OrderStatus.valueOf(line.orderStatus());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("unknown order_status: " + line.orderStatus());
        }
        if (BLOCKED_ORDER_STATUSES.contains(status)) {
            throw BusinessException.conflict(
                    "ORDER_ALREADY_SHIPPED", "订单已发货或已终态（" + status + "），不能再换货");
        }
    }

    private NewSku requireSubstitutableSku(long newSkuId, LineSnapshot line) {
        List<Map<String, Object>> skuRows = jdbc.queryForList(
                "SELECT active, fulfillment_provider_id, sku_code FROM app.skus WHERE id=? FOR SHARE",
                newSkuId);
        if (skuRows.isEmpty()) {
            throw BusinessException.notFound("新 SKU 不存在");
        }
        if (newSkuId == line.skuId()) {
            throw BusinessException.unprocessable(
                    "NEW_SKU_SAME_AS_CURRENT", "新 SKU 与当前 SKU 相同，无需换货");
        }
        Map<String, Object> sku = skuRows.getFirst();
        if (!Boolean.TRUE.equals(sku.get("active"))) {
            throw BusinessException.unprocessable("NEW_SKU_INACTIVE", "新 SKU 已停用，不能换货");
        }
        long newSkuProviderId = ((Number) sku.get("fulfillment_provider_id")).longValue();
        if (newSkuProviderId != line.fulfillmentProviderId()) {
            throw BusinessException.unprocessable(
                    "NEW_SKU_PROVIDER_MISMATCH", "新 SKU 必须与原 SKU 归属同一履约方（京东仓）");
        }
        List<Map<String, Object>> mappingRows = jdbc.queryForList(
                """
                SELECT provider_sku_code, active FROM app.provider_skus
                WHERE fulfillment_provider_id=? AND sku_id=?
                FOR SHARE
                """,
                newSkuProviderId, newSkuId);
        boolean mapped = !mappingRows.isEmpty()
                && Boolean.TRUE.equals(mappingRows.getFirst().get("active"))
                && !isBlank((String) mappingRows.getFirst().get("provider_sku_code"));
        if (!mapped) {
            throw BusinessException.unprocessable(
                    "NEW_SKU_NOT_MAPPED_TO_PROVIDER", "新 SKU 尚未配置该履约方的有效商品映射（provider_skus）");
        }
        return new NewSku((String) sku.get("sku_code"));
    }

    private long requireFulfillmentId(long orderLineId) {
        List<Long> ids = jdbc.query(
                "SELECT id FROM app.fulfillments WHERE order_line_id=? FOR UPDATE",
                (rs, rowNum) -> rs.getLong("id"),
                orderLineId);
        if (ids.isEmpty()) {
            throw BusinessException.unprocessable(
                    "ORDER_LINE_FULFILLMENT_MISSING", "该行尚未建立履约单元，无法换货");
        }
        return ids.getFirst();
    }

    /** 只有 shipment_status='CREATED' 且未落 fulfillment_export_items、尚未发生实发的
     *  ShipmentItem 才允许撤销重建；任何一条不满足就整体拒绝，不做部分换货。 */
    private List<ShipmentItemSnapshot> requireDetachableShipmentItems(long fulfillmentId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                SELECT si.shipment_id, si.instructed_quantity, si.shipped_quantity,
                       s.shipment_status,
                       EXISTS(
                           SELECT 1 FROM app.fulfillment_export_items fei
                           WHERE fei.shipment_id=si.shipment_id AND fei.fulfillment_id=si.fulfillment_id
                       ) exported
                FROM app.shipment_items si
                JOIN app.shipments s ON s.id=si.shipment_id
                WHERE si.fulfillment_id=?
                FOR UPDATE OF si, s
                """,
                fulfillmentId);
        List<ShipmentItemSnapshot> items = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String status = (String) row.get("shipment_status");
            boolean exported = Boolean.TRUE.equals(row.get("exported"));
            if (!"CREATED".equals(status) || exported || row.get("shipped_quantity") != null) {
                throw BusinessException.conflict(
                        "SKU_SUBSTITUTION_SHIPMENT_NOT_MUTABLE",
                        "发货批次 " + row.get("shipment_id") + " 已发出或已导出，不能再换货");
            }
            items.add(new ShipmentItemSnapshot(
                    ((Number) row.get("shipment_id")).longValue(),
                    ((Number) row.get("instructed_quantity")).intValue()));
        }
        return items;
    }

    private void requireNoProcurement(long fulfillmentId) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM app.procurement_tickets WHERE fulfillment_id=?",
                Long.class, fulfillmentId);
        if (count != null && count > 0) {
            throw BusinessException.conflict(
                    "SKU_SUBSTITUTION_PROCUREMENT_EXISTS", "该行已存在采购工单，不能直接换货，请先处理采购单");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record LineSnapshot(
            long orderId,
            String lineType,
            Long skuId,
            String skuCode,
            long fulfillmentProviderId,
            boolean fulfillmentCommitted,
            String orderStatus,
            long orderLockVersion) {}

    private record NewSku(String skuCode) {}

    private record ShipmentItemSnapshot(long shipmentId, int instructedQuantity) {}
}
