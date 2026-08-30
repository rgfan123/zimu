package cn.zimu.fulfillment.sku;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 目录写事务必须先串行取得 advisory lock，不能先持有不同业务行再互相等待。 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SkuReadinessCatalogLockIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;

    @Test
    void concurrentCatalogWritersCannotCreateAdvisoryAndRowLockInversion() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        long firstProduct = insertProduct("LOCK-A-" + suffix);
        long secondProduct = insertProduct("LOCK-B-" + suffix);

        try (Connection first = dataSource.getConnection();
                Connection second = dataSource.getConnection()) {
            first.setAutoCommit(false);
            second.setAutoCommit(false);
            configureFastDeadlockDetection(first);
            configureFastDeadlockDetection(second);
            int secondPid = backendPid(second);

            updateProduct(first, firstProduct, "first-holds-catalog-lock");
            CompletableFuture<Void> secondWrite = CompletableFuture.runAsync(() -> {
                try {
                    updateProduct(second, secondProduct, "second-waits-for-catalog-lock");
                    second.commit();
                } catch (SQLException exception) {
                    rollback(second);
                    throw new RuntimeException(exception);
                }
            });

            waitUntilAdvisoryBlocked(secondPid);
            Throwable firstFailure = null;
            try {
                updateProduct(first, secondProduct, "first-can-still-update-second-row");
                first.commit();
            } catch (Throwable exception) {
                firstFailure = exception;
                rollback(first);
            }

            assertThat(firstFailure)
                    .as("第二个写事务等待目录锁时不得已经占住另一条业务行")
                    .isNull();
            secondWrite.get(5, TimeUnit.SECONDS);
        }
    }

    private long insertProduct(String code) {
        return jdbc.queryForObject(
                "INSERT INTO app.products(product_code, product_name) VALUES (?, ?) RETURNING id",
                Long.class,
                code,
                code);
    }

    private void configureFastDeadlockDetection(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET LOCAL deadlock_timeout='100ms'");
            statement.execute("SET LOCAL lock_timeout='4s'");
        }
    }

    private int backendPid(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                var result = statement.executeQuery("SELECT pg_backend_pid()")) {
            result.next();
            return result.getInt(1);
        }
    }

    private void updateProduct(Connection connection, long productId, String name) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE app.products SET product_name=? WHERE id=?")) {
            statement.setString(1, name);
            statement.setLong(2, productId);
            assertThat(statement.executeUpdate()).isEqualTo(1);
        }
    }

    private void waitUntilAdvisoryBlocked(int backendPid) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            Boolean waiting = jdbc.queryForObject(
                    """
                    SELECT wait_event_type='Lock' AND wait_event='advisory'
                    FROM pg_stat_activity WHERE pid=?
                    """,
                    Boolean.class,
                    backendPid);
            if (Boolean.TRUE.equals(waiting)) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("并发目录写事务未进入 advisory lock 等待态");
    }

    private void rollback(Connection connection) {
        try {
            connection.rollback();
        } catch (SQLException ignored) {
            // 保留原始并发失败。
        }
    }
}
