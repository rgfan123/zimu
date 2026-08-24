package cn.zimu.fulfillment.order.card;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** V52 preserves closed draft history and adds settlement-time gating only to open legacy drafts. */
@Testcontainers
class OrderDraftCardMigrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void backfillsConfirmedSettlementTimeAndMarksOnlyOpenLegacyDraftsIncomplete() throws Exception {
        flyway(MigrationVersion.fromVersion("49")).migrate();

        long confirmedDraftId;
        long openDraftId;
        try (var connection = DriverManager.getConnection(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                var statement = connection.createStatement()) {
            long messageId = id(statement.executeQuery(
                    """
                    INSERT INTO app.channel_messages (
                        corp_id, connection_id, bot_id, message_id, chat_id, chat_type,
                        sender_user_id, message_type, content, raw_payload
                    ) VALUES ('corp-v52', 'connection-v52', 'bot-v52', 'message-v52',
                              'chat-v52', 'group', 'operator-v52', 'text', '历史订单', '{}'::jsonb)
                    RETURNING id
                    """));
            long submissionId = id(statement.executeQuery(
                    "INSERT INTO app.message_submissions (submission_no, source_message_id, status) "
                            + "VALUES ('SUB-V52', " + messageId + ", 'DRAFTED') RETURNING id"));
            confirmedDraftId = id(statement.executeQuery(
                    """
                    INSERT INTO app.order_drafts (
                        draft_no, submission_id, source_order_no, missing_fields,
                        status, confirmed_by, confirmed_at
                    ) VALUES ('OD-V52-CONFIRMED', %d, 'WECOM-V52-CONFIRMED', '[]'::jsonb,
                              'CONFIRMED', 'legacy-operator', CURRENT_TIMESTAMP)
                    RETURNING id
                    """.formatted(submissionId)));
            openDraftId = id(statement.executeQuery(
                    """
                    INSERT INTO app.order_drafts (
                        draft_no, submission_id, source_order_no, missing_fields, status
                    ) VALUES ('OD-V52-OPEN', %d, 'WECOM-V52-OPEN', '[]'::jsonb, 'OPEN')
                    RETURNING id
                    """.formatted(submissionId)));
            long orderId = id(statement.executeQuery(
                    """
                    INSERT INTO app.orders (
                        order_no, data_scope, source_channel, source_ref, source_ref_kind,
                        order_status, settlement_method, settlement_time,
                        receiver_name, receiver_phone, receiver_address
                    ) VALUES ('ORD-V52', 'BUSINESS', 'WECOM', 'WECOM-V52-CONFIRMED', 'PROVIDED',
                              'RECEIVED', 'MONTHLY', '2026-08-31T16:00:00Z',
                              '历史收货人', '13800000000', '历史地址')
                    RETURNING id
                    """));
            statement.executeUpdate(
                    """
                    INSERT INTO app.review_cases (
                        case_no, case_type, status, responsible_team, reason_code,
                        order_draft_id, detail, resolution, resolved_by, resolved_at
                    ) VALUES ('RC-V52', 'WECOM_DRAFT', 'RESOLVED', 'ORDER_OPS',
                              'WECOM_ORDER_DRAFT', %d, '{}'::jsonb,
                              jsonb_build_object(
                                  'resolution_type', 'ORDER_DRAFT_CONFIRMED',
                                  'order_id', '%d'),
                              'legacy-operator', CURRENT_TIMESTAMP)
                    """.formatted(confirmedDraftId, orderId));
        }

        flyway(null).migrate();

        try (var connection = DriverManager.getConnection(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                var statement = connection.createStatement()) {
            assertThat(single(statement.executeQuery(
                            "SELECT (settlement_time = '2026-08-31T16:00:00Z'::timestamptz)::text "
                                    + "FROM app.order_drafts WHERE id=" + confirmedDraftId)))
                    .isEqualTo("true");
            assertThat(single(statement.executeQuery(
                            "SELECT missing_fields::text FROM app.order_drafts WHERE id=" + confirmedDraftId)))
                    .isEqualTo("[]");
            assertThat(single(statement.executeQuery(
                            "SELECT missing_fields::text FROM app.order_drafts WHERE id=" + openDraftId)))
                    .contains("settlement_time");
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
