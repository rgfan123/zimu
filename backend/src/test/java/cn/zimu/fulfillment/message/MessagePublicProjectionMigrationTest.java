package cn.zimu.fulfillment.message;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** V20 removes legacy exception text before an upgraded database serves management APIs. */
@Testcontainers
class MessagePublicProjectionMigrationTest {

    private static final String SENTINEL = "raw provider exception secret=pre-v20";

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void upgradesV19MessageErrorsAndReviewDetailsToStablePublicValues() throws Exception {
        flyway(MigrationVersion.fromVersion("19")).migrate();

        long submissionId;
        try (var connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            long messageId;
            try (var statement = connection.prepareStatement(
                    """
                    INSERT INTO app.channel_messages
                        (corp_id, connection_id, bot_id, message_id, chat_id, chat_type,
                         sender_user_id, message_type, content, raw_payload)
                    VALUES ('ww-migration', 'migration-relay', 'bot-migration', 'msg-migration',
                            'chat-migration', 'group', 'user-migration', 'text', '历史消息', '{}'::jsonb)
                    RETURNING id
                    """)) {
                messageId = id(statement.executeQuery());
            }
            try (var statement = connection.prepareStatement(
                    """
                    INSERT INTO app.message_submissions (submission_no, source_message_id, status)
                    VALUES ('SUB-MIGRATION-V20', ?, 'FAILED')
                    RETURNING id
                    """)) {
                statement.setLong(1, messageId);
                submissionId = id(statement.executeQuery());
            }
            try (var statement = connection.prepareStatement(
                    """
                    INSERT INTO app.message_interpretations
                        (submission_id, version, provider, model, prompt_version, intent,
                         structured_output, error)
                    VALUES (?, 1, 'provider-a', 'model-a', 'prompt-v1', 'NEED_REVIEW', '{}'::jsonb, ?)
                    """)) {
                statement.setLong(1, submissionId);
                statement.setString(2, SENTINEL);
                assertThat(statement.executeUpdate()).isEqualTo(1);
            }
            try (var statement = connection.prepareStatement(
                    """
                    INSERT INTO app.async_tasks
                        (task_type, payload_ref, status, attempts, max_attempts, last_error, idempotency_key)
                    VALUES ('INTERPRET_MESSAGE', ?, 'FAILED', 3, 3, ?, 'migration-v20-task')
                    """)) {
                statement.setString(1, "submission:" + submissionId);
                statement.setString(2, SENTINEL);
                assertThat(statement.executeUpdate()).isEqualTo(1);
            }
            try (var statement = connection.prepareStatement(
                    """
                    INSERT INTO app.review_cases
                        (case_no, case_type, responsible_team, reason_code,
                         message_submission_id, detail)
                    VALUES ('RC-MIGRATION-V20', 'WECOM_INTAKE', 'ORDER_OPS',
                            'WECOM_ORDER_CHANGE', ?, ?::jsonb)
                    """)) {
                statement.setLong(1, submissionId);
                statement.setString(
                        2,
                        "{\"intent\":\"ORDER_CHANGE\",\"provider\":\"provider-a\","
                                + "\"model\":\"model-a\",\"prompt_version\":\"prompt-v1\","
                                + "\"error_code\":\"" + SENTINEL + "\","
                                + "\"error\":\"" + SENTINEL + "\","
                                + "\"order_no\":\"请取消订单 ORD-2026-001\","
                                + "\"unknown_secret\":\"pre-v20\"}");
                assertThat(statement.executeUpdate()).isEqualTo(1);
            }
        }

        flyway(null).migrate();

        try (var connection = DriverManager.getConnection(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                var statement = connection.createStatement()) {
            assertThat(single(statement.executeQuery(
                            "SELECT last_error FROM app.async_tasks WHERE idempotency_key='migration-v20-task'")))
                    .isEqualTo("MODEL_CALL_FAILED");
            assertThat(single(statement.executeQuery(
                            "SELECT error FROM app.message_interpretations WHERE submission_id=" + submissionId)))
                    .isEqualTo("MODEL_CALL_FAILED");
            String detail = single(statement.executeQuery(
                    "SELECT detail::text FROM app.review_cases WHERE case_no='RC-MIGRATION-V20'"));
            assertThat(detail)
                    .contains("\"error_code\": \"MODEL_CALL_FAILED\"")
                    .doesNotContain(SENTINEL, "pre-v20", "unknown_secret", "请取消订单");
        }
    }

    private Flyway flyway(MigrationVersion target) {
        var configuration = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private static long id(java.sql.ResultSet result) throws Exception {
        try (result) {
            assertThat(result.next()).isTrue();
            return result.getLong(1);
        }
    }

    private static String single(java.sql.ResultSet result) throws Exception {
        try (result) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }
}
