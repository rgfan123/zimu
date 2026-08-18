package cn.zimu.fulfillment.common.event;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 按 CanonicalOrder 串行化 append-only 事件与版本号分配。使用 PostgreSQL 事务级
 * advisory lock，不锁住 orders 行，也不会把不同订单的写入串行化。
 */
@Component
public class CanonicalOrderAppendLock {

    /** 与其它 advisory-lock 用途分隔；XOR 对 orderId 保持一对一映射。 */
    private static final long ORDER_APPEND_NAMESPACE = 0x4A444F5244455200L;

    private final JdbcTemplate jdbc;

    public CanonicalOrderAppendLock(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void acquire(long orderId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("canonical order append lock requires an active database transaction");
        }
        jdbc.query(
                "SELECT pg_advisory_xact_lock(?)",
                resultSet -> {
                    resultSet.next();
                    return null;
                },
                advisoryKey(orderId));
    }

    static long advisoryKey(long orderId) {
        return ORDER_APPEND_NAMESPACE ^ orderId;
    }
}
