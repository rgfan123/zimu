package cn.zimu.fulfillment.catalog;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 串行权威目录导入与商品主数据写入。普通主数据写之间仍可并发。
 *
 * <p>调用方必须在完成幂等 claim 后、读取任何 catalog 行之前获取锁，并由当前事务持有到提交。
 */
@Component
public final class CatalogMasterDataLock {

    private static final long LOCK_KEY = 0x5A494D554A44434CL;

    private final JdbcTemplate jdbc;

    public CatalogMasterDataLock(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void lockForAuthoritativeImport() {
        acquire("SELECT pg_advisory_xact_lock(?)");
    }

    public void lockForMasterDataWrite() {
        acquire("SELECT pg_advisory_xact_lock_shared(?)");
    }

    private void acquire(String sql) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("catalog advisory lock requires an active transaction");
        }
        jdbc.query(
                sql,
                statement -> statement.setLong(1, LOCK_KEY),
                resultSet -> null);
    }
}
