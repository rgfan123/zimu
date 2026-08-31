package cn.zimu.fulfillment.sku;

import java.sql.PreparedStatement;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 与数据库写触发器配对的 SKU readiness 目录共享事务锁。 */
@Service
public class SkuReadinessCatalogLock {

    public static final long LOCK_KEY = 756426269156L;

    private final JdbcTemplate jdbc;

    public SkuReadinessCatalogLock(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 调用方必须处于事务内；共享锁一直持有到当前事务提交或回滚。 */
    public void acquireShared() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("SKU readiness catalog lock requires an active transaction");
        }
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            try (PreparedStatement statement =
                    connection.prepareStatement("SELECT pg_advisory_xact_lock_shared(?)")) {
                statement.setLong(1, LOCK_KEY);
                statement.execute();
            }
            return null;
        });
    }
}
