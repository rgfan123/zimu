package cn.zimu.fulfillment.connector;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.stereotype.Component;

/**
 * PostgreSQL 会话锁实现的跨实例单渠道单飞门禁。锁随专用连接存活，正常结束显式解锁；
 * 连接或 JVM 异常终止时由 PostgreSQL 自动释放，不产生持久配额或陈旧租约窗口。
 */
@Component
class PlatformPullSingleFlight {

    private static final long LOCK_NAMESPACE = 115L;
    private static final String TRY_LOCK_SQL =
            "SELECT pg_try_advisory_lock(hashtextextended(?, ?))";
    private static final String UNLOCK_SQL =
            "SELECT pg_advisory_unlock(hashtextextended(?, ?))";

    private final DataSource dataSource;

    PlatformPullSingleFlight(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    Lease tryAcquire(String channel) {
        Connection connection = null;
        try {
            connection = dataSource.getConnection();
            if (!queryBoolean(connection, TRY_LOCK_SQL, channel)) {
                connection.close();
                return Lease.notAcquired();
            }
            return Lease.acquired(connection, channel);
        } catch (SQLException exception) {
            abortAndClose(connection);
            throw new IllegalStateException("平台拉取单飞锁获取失败: " + channel, exception);
        }
    }

    private static boolean queryBoolean(Connection connection, String sql, String channel) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, channel);
            statement.setLong(2, LOCK_NAMESPACE);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("平台拉取单飞锁未返回结果");
                }
                return result.getBoolean(1);
            }
        }
    }

    private static void abortAndClose(Connection connection) {
        if (connection == null) return;
        try {
            connection.abort(Runnable::run);
        } catch (SQLException ignored) {
            // close 是最后兜底；若物理连接已断，PostgreSQL 已自动释放会话锁。
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
            // 获取/释放异常会向调用方显式失败，此处不覆盖原始异常。
        }
    }

    static final class Lease implements AutoCloseable {
        private Connection connection;
        private final String channel;
        private final boolean acquired;

        private Lease(Connection connection, String channel, boolean acquired) {
            this.connection = connection;
            this.channel = channel;
            this.acquired = acquired;
        }

        static Lease acquired(Connection connection, String channel) {
            return new Lease(connection, channel, true);
        }

        static Lease notAcquired() {
            return new Lease(null, null, false);
        }

        boolean acquired() {
            return acquired;
        }

        @Override
        public void close() {
            Connection held = connection;
            connection = null;
            if (held == null) return;
            try {
                if (!queryBoolean(held, UNLOCK_SQL, channel)) {
                    throw new SQLException("平台拉取单飞锁并非当前连接持有");
                }
                held.close();
            } catch (SQLException exception) {
                abortAndClose(held);
                throw new IllegalStateException("平台拉取单飞锁释放失败: " + channel, exception);
            }
        }
    }
}
