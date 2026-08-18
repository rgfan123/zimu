package cn.zimu.fulfillment.procurement;

import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.domain.DataScope;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.event.OrderEventService;
import cn.zimu.fulfillment.common.idempotency.IdempotencyService;
import cn.zimu.fulfillment.common.idempotency.IdempotentResult;
import cn.zimu.fulfillment.common.version.OrderVersionService;
import cn.zimu.fulfillment.common.web.CommandContext;
import cn.zimu.fulfillment.common.web.WriteCommands;
import cn.zimu.fulfillment.fulfillment.FulfillmentReadService;
import cn.zimu.fulfillment.fulfillment.SourceFollowupProgressService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 采购回执、FAILED 重试与取消剩余量的幂等事务用例。 */
@Service
public class ProcurementService {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final IdempotencyService idempotency;
    private final FulfillmentReadService readService;
    private final OrderEventService events;
    private final OrderVersionService versions;
    private final AuditLogService audits;
    private final SourceFollowupProgressService sourceFollowups;

    public ProcurementService(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            IdempotencyService idempotency,
            FulfillmentReadService readService,
            OrderEventService events,
            OrderVersionService versions,
            AuditLogService audits,
            SourceFollowupProgressService sourceFollowups) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.idempotency = idempotency;
        this.readService = readService;
        this.events = events;
        this.versions = versions;
        this.audits = audits;
        this.sourceFollowups = sourceFollowups;
    }

    @Transactional
    public IdempotentResult<Map<String, Object>> receipt(
            long ticketId, ProcurementReceiptInput input, String key, CommandContext context) {
        return idempotency.execute("procurement.receipt", key, Map.of("ticket_id", ticketId, "body", input), 201,
                () -> doReceipt(ticketId, input, context));
    }

    @Transactional
    public IdempotentResult<Map<String, Object>> retry(
            long ticketId, VersionedNoteCommand input, String key, CommandContext context) {
        return idempotency.execute("procurement.retry", key, Map.of("ticket_id", ticketId, "body", input), 201,
                () -> doRetry(ticketId, input, context));
    }

    @Transactional
    public IdempotentResult<Map<String, Object>> cancel(
            long ticketId, CancelRemainingCommand input, String key, CommandContext context) {
        return idempotency.execute("procurement.cancel_remaining", key, Map.of("ticket_id", ticketId, "body", input), 200,
                () -> doCancel(ticketId, input, context));
    }

    private Map<String, Object> doReceipt(
            long ticketId, ProcurementReceiptInput input, CommandContext context) {
        TicketContext ticket = lockTicket(ticketId);
        if (ticket.status().equals("CANCELLED") || ticket.status().equals("SUCCESS")) {
            throw BusinessException.conflict("PROCUREMENT_TERMINAL", "采购工单已终止，不能再录入回执");
        }
        validateReceipt(input);
        lockAndValidateReceiptItems(ticketId, input.items());
        String receiptNo = "PREC-" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
        Long receiptId = jdbc.queryForObject(
                """
                INSERT INTO app.procurement_receipts
                    (receipt_no, procurement_ticket_id, result, expected_ship_time, source_ref, remark, received_by)
                VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id
                """,
                Long.class, receiptNo, ticketId, input.result().name(),
                input.expectedShipTime() == null ? null : OffsetDateTime.ofInstant(input.expectedShipTime(), ZoneOffset.UTC),
                input.sourceRef(), input.remark(), context.operator());
        for (ProcurementReceiptItemInput item : input.items()) {
            long itemId = WriteCommands.parseIdentifier(item.ticketItemId());
            int updated = jdbc.update(
                    """
                    INSERT INTO app.procurement_receipt_items
                        (procurement_receipt_id, procurement_ticket_item_id, available_quantity)
                    SELECT ?, id, ? FROM app.procurement_ticket_items
                    WHERE id=? AND procurement_ticket_id=?
                    """,
                    receiptId, new BigDecimal(item.availableQuantity()), itemId, ticketId);
            if (updated != 1) throw BusinessException.unprocessable("PROCUREMENT_ITEM_INVALID", "回执明细不属于当前采购工单");
        }
        BigDecimal remaining = jdbc.queryForObject(
                "SELECT COALESCE(sum(remaining_quantity),0) FROM app.procurement_ticket_items WHERE procurement_ticket_id=?",
                BigDecimal.class, ticketId);
        String nextStatus;
        if (remaining.signum() == 0) nextStatus = "SUCCESS";
        else if (input.result() == ProcurementReceiptInput.Result.FAILED) nextStatus = "FAILED";
        else nextStatus = "PARTIAL";
        jdbc.update(
                "UPDATE app.procurement_tickets SET procurement_status=?, lock_version=lock_version+1, updated_at=CURRENT_TIMESTAMP WHERE id=?",
                nextStatus, ticketId);
        Map<String, Object> receipt = readService.receipt(receiptId);
        append(ticket, "PROCUREMENT_RECEIPT_RECORDED", ticketId,
                map("receipt_id", String.valueOf(receiptId), "result", input.result().name(), "status", nextStatus), context);
        if (nextStatus.equals("SUCCESS")) {
            append(ticket, "PROCUREMENT_COMPLETED", ticketId, map("status", nextStatus), context);
        }
        snapshot(ticket.orderId(), "采购回执入账", context);
        audit(ticket, "procurement.receipt", input, receipt, 201, context);
        return receipt;
    }

    private Map<String, Object> doRetry(long ticketId, VersionedNoteCommand input, CommandContext context) {
        TicketContext original = lockTicket(ticketId);
        version(original.version(), input.expectedVersion());
        if (!original.status().equals("FAILED")) {
            throw BusinessException.conflict("PROCUREMENT_NOT_FAILED", "只有 FAILED 采购工单可重试");
        }
        String no = "PROC-" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
        Long newId = jdbc.queryForObject(
                """
                INSERT INTO app.procurement_tickets
                    (ticket_no, fulfillment_id, retry_of_ticket_id, priority, delivery_address,
                     required_delivery_time, remark, created_by)
                SELECT ?, fulfillment_id, id, priority, delivery_address, required_delivery_time, ?, ?
                FROM app.procurement_tickets WHERE id=? RETURNING id
                """,
                Long.class, no, input.note(), context.operator(), ticketId);
        jdbc.update(
                """
                INSERT INTO app.procurement_ticket_items
                    (procurement_ticket_id, sku_id, order_line_component_id, requested_quantity, unit_snapshot)
                SELECT ?, sku_id, order_line_component_id, remaining_quantity, unit_snapshot
                FROM app.procurement_ticket_items WHERE procurement_ticket_id=? AND remaining_quantity>0
                """,
                newId, ticketId);
        Map<String, Object> result = readService.ticket(newId);
        append(original, "PROCUREMENT_REQUESTED", newId,
                map("retry_of_ticket_id", String.valueOf(ticketId), "note", input.note()), context);
        snapshot(original.orderId(), "失败采购工单重试", context);
        audit(original, "procurement.retry", input, result, 201, context);
        return result;
    }

    private Map<String, Object> doCancel(long ticketId, CancelRemainingCommand input, CommandContext context) {
        TicketContext ticket = lockTicket(ticketId);
        version(ticket.version(), input.expectedVersion());
        if (ticket.status().equals("SUCCESS") || ticket.status().equals("CANCELLED")) {
            throw BusinessException.conflict("PROCUREMENT_TERMINAL", "采购工单已终止");
        }
        BigDecimal remaining = jdbc.queryForObject(
                "SELECT COALESCE(sum(remaining_quantity),0) FROM app.procurement_ticket_items WHERE procurement_ticket_id=?",
                BigDecimal.class, ticketId);
        if (remaining.signum() <= 0) throw BusinessException.conflict("NO_REMAINING_QUANTITY", "采购工单无剩余量");
        jdbc.update(
                "UPDATE app.procurement_tickets SET procurement_status='CANCELLED', lock_version=lock_version+1, remark=?, updated_at=CURRENT_TIMESTAMP WHERE id=?",
                input.reason(), ticketId);
        BigDecimal requested = jdbc.queryForObject(
                "SELECT requested_quantity FROM app.fulfillments WHERE id=? FOR UPDATE",
                BigDecimal.class, ticket.fulfillmentId());
        BigDecimal shipped = jdbc.queryForObject(
                "SELECT cumulative_shipped_quantity FROM app.fulfillments WHERE id=?",
                BigDecimal.class, ticket.fulfillmentId());
        BigDecimal cancelled = requested.subtract(shipped);
        String outcome = shipped.signum() == 0 ? "CANCELLED" : "PARTIALLY_FULFILLED";
        jdbc.update(
                """
                UPDATE app.fulfillments SET cancelled_quantity=?, outcome=?, exception_code='PROCUREMENT_CANCELLED',
                    exception_reason=?, lock_version=lock_version+1, updated_at=CURRENT_TIMESTAMP WHERE id=?
                """,
                cancelled, outcome, input.reason(), ticket.fulfillmentId());
        sourceFollowups.refresh(ticket.fulfillmentId());
        Map<String, Object> result = readService.ticket(ticketId);
        append(ticket, "MANUAL_INTERVENTION_REQUIRED", ticketId,
                map("reason_code", "PROCUREMENT_CANCELLED", "reason", input.reason()), context);
        snapshot(ticket.orderId(), "取消采购剩余量", context);
        audit(ticket, "procurement.cancel_remaining", input, result, 200, context);
        return result;
    }

    private TicketContext lockTicket(long ticketId) {
        TicketContext value = jdbc.query(
                """
                SELECT pt.id, pt.procurement_status, pt.lock_version, pt.fulfillment_id, ol.order_id
                FROM app.procurement_tickets pt
                JOIN app.fulfillments f ON f.id=pt.fulfillment_id
                JOIN app.order_lines ol ON ol.id=f.order_line_id
                JOIN app.orders o ON o.id=ol.order_id AND o.data_scope='BUSINESS'
                WHERE pt.id=? FOR UPDATE OF pt
                """,
                rs -> rs.next() ? new TicketContext(
                        rs.getLong("id"), rs.getString("procurement_status"), rs.getLong("lock_version"),
                        rs.getLong("fulfillment_id"), rs.getLong("order_id")) : null,
                ticketId);
        if (value == null) throw BusinessException.notFound("采购工单不存在");
        return value;
    }

    private void validateReceipt(ProcurementReceiptInput input) {
        if (input.result() == ProcurementReceiptInput.Result.SUCCESS
                && input.items().stream().anyMatch(item -> new BigDecimal(item.availableQuantity()).signum() <= 0)) {
            throw BusinessException.unprocessable("SUCCESS_QUANTITY_REQUIRED", "SUCCESS 回执必须包含正数可用量");
        }
        if (input.result() == ProcurementReceiptInput.Result.FAILED
                && input.items().stream().anyMatch(item -> new BigDecimal(item.availableQuantity()).signum() != 0)) {
            throw BusinessException.unprocessable("FAILED_QUANTITY_MUST_BE_ZERO", "FAILED 回执可用量必须为 0");
        }
        long distinct = input.items().stream().map(ProcurementReceiptItemInput::ticketItemId).distinct().count();
        if (distinct != input.items().size()) {
            throw BusinessException.unprocessable("DUPLICATE_RECEIPT_ITEM", "回执中不能重复提交同一工单明细");
        }
    }

    private void lockAndValidateReceiptItems(long ticketId, List<ProcurementReceiptItemInput> items) {
        for (ProcurementReceiptItemInput item : items) {
            long itemId = WriteCommands.parseIdentifier(item.ticketItemId());
            BigDecimal remaining = jdbc.query(
                    """
                    SELECT remaining_quantity FROM app.procurement_ticket_items
                    WHERE id=? AND procurement_ticket_id=? FOR UPDATE
                    """,
                    rs -> rs.next() ? rs.getBigDecimal("remaining_quantity") : null,
                    itemId,
                    ticketId);
            if (remaining == null) {
                throw BusinessException.unprocessable(
                        "PROCUREMENT_ITEM_INVALID", "回执明细不属于当前采购工单");
            }
            BigDecimal available = new BigDecimal(item.availableQuantity());
            if (available.compareTo(remaining) > 0) {
                throw BusinessException.unprocessable(
                        "RECEIPT_QUANTITY_EXCEEDS_REMAINING", "回执可用量不得超过工单明细剩余量");
            }
        }
    }

    private void append(
            TicketContext ticket, String type, long procurementId, Map<String, Object> payload, CommandContext context) {
        events.append(ticket.orderId(), type, null, ticket.fulfillmentId(), null, procurementId,
                DataScope.BUSINESS, payload, context.operator());
    }

    private void snapshot(long orderId, String reason, CommandContext context) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("order_id", String.valueOf(orderId));
        snapshot.put("procurement_tickets", jdbc.query(
                """
                SELECT pt.id, pt.ticket_no, pt.procurement_status
                FROM app.procurement_tickets pt
                JOIN app.fulfillments f ON f.id=pt.fulfillment_id
                JOIN app.order_lines ol ON ol.id=f.order_line_id WHERE ol.order_id=? ORDER BY pt.id
                """,
                (rs, row) -> map("id", String.valueOf(rs.getLong(1)), "ticket_no", rs.getString(2), "status", rs.getString(3)),
                orderId));
        versions.append(orderId, null, reason, context.operator(), snapshot);
    }

    private void audit(
            TicketContext ticket, String operation, Object request, Object response, int status, CommandContext context) {
        audits.record(new AuditLogService.AuditCommand()
                .dataScope(DataScope.BUSINESS)
                .orderId(ticket.orderId())
                .requestId(context.requestId())
                .traceId(context.traceId())
                .operator(context.operator())
                .actorType(AuditActorType.HUMAN)
                .service("ProcurementService")
                .operation(operation)
                .requestPayload(request)
                .responsePayload(response)
                .httpStatus(status)
                .businessCode("SUCCESS"));
    }

    private static void version(long actual, long expected) {
        if (actual != expected) throw BusinessException.conflict("VERSION_CONFLICT", "采购工单已更新，请刷新后重试");
    }

    private static Map<String, Object> map(Object... entries) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) result.put((String) entries[i], entries[i + 1]);
        return result;
    }

    private record TicketContext(long id, String status, long version, long fulfillmentId, long orderId) {}
}
