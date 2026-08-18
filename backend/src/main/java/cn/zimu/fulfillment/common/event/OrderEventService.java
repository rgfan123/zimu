package cn.zimu.fulfillment.common.event;

import cn.zimu.fulfillment.common.domain.DataScope;
import jakarta.persistence.EntityManager;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 共享 OrderEvent 追加服务：按订单内 sequence_no 递增写入，供后续履约/Excel 票复用。 */
@Service
public class OrderEventService {

    private final OrderEventRepository repository;
    private final JdbcTemplate jdbcTemplate;
    private final EntityManager entityManager;
    private final CanonicalOrderAppendLock appendLock;

    public OrderEventService(
            OrderEventRepository repository,
            JdbcTemplate jdbcTemplate,
            EntityManager entityManager,
            CanonicalOrderAppendLock appendLock) {
        this.repository = repository;
        this.jdbcTemplate = jdbcTemplate;
        this.entityManager = entityManager;
        this.appendLock = appendLock;
    }

    @Transactional
    public OrderEvent append(
            Long orderId,
            String eventTypeCode,
            Long orderLineId,
            Long fulfillmentId,
            Long shipmentId,
            Long procurementTicketId,
            DataScope dataScope,
            Map<String, Object> payload,
            String operator) {
        appendLock.acquire(orderId);
        OrderEvent event = new OrderEvent();
        event.setOrderId(orderId);
        event.setSequenceNo(nextSequence(orderId));
        event.setEventTypeCode(eventTypeCode);
        event.setOrderLineId(orderLineId);
        event.setFulfillmentId(fulfillmentId);
        event.setShipmentId(shipmentId);
        event.setProcurementTicketId(procurementTicketId);
        event.setDataScope(dataScope);
        event.setPayload(payload);
        event.setOperator(operator);
        OrderEvent saved = repository.save(event);
        // append-only 行不可变：persist 后立即脱离上下文，避免后续 flush 因 dirty-check 误发 UPDATE 触发 append-only 触发器
        entityManager.detach(saved);
        return saved;
    }

    private long nextSequence(Long orderId) {
        Long max = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(sequence_no), 0) FROM app.order_events WHERE order_id = ?",
                Long.class,
                orderId);
        return max + 1;
    }
}
