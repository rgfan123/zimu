package cn.zimu.fulfillment.common.version;

import cn.zimu.fulfillment.common.event.CanonicalOrderAppendLock;
import jakarta.persistence.EntityManager;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 共享 OrderVersion 追加服务：按订单内 version_no 递增写入完整快照，供后续履约/Excel 票复用。 */
@Service
public class OrderVersionService {

    private final OrderVersionRepository repository;
    private final JdbcTemplate jdbcTemplate;
    private final EntityManager entityManager;
    private final CanonicalOrderAppendLock appendLock;

    public OrderVersionService(
            OrderVersionRepository repository,
            JdbcTemplate jdbcTemplate,
            EntityManager entityManager,
            CanonicalOrderAppendLock appendLock) {
        this.repository = repository;
        this.jdbcTemplate = jdbcTemplate;
        this.entityManager = entityManager;
        this.appendLock = appendLock;
    }

    @Transactional
    public OrderVersion append(
            Long orderId, String sourceVersion, String changeReason, String triggeredBy, Map<String, Object> snapshot) {
        appendLock.acquire(orderId);
        OrderVersion version = new OrderVersion();
        version.setOrderId(orderId);
        version.setVersionNo(nextVersionNo(orderId));
        version.setSourceVersion(sourceVersion);
        version.setChangeReason(changeReason);
        version.setTriggeredBy(triggeredBy);
        version.setSnapshot(snapshot);
        OrderVersion saved = repository.save(version);
        // append-only 行不可变：persist 后立即脱离上下文，避免后续 flush 因 dirty-check 误发 UPDATE 触发 append-only 触发器
        entityManager.detach(saved);
        return saved;
    }

    private long nextVersionNo(Long orderId) {
        Long max = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(version_no), 0) FROM app.order_versions WHERE order_id = ?",
                Long.class,
                orderId);
        return max + 1;
    }
}
