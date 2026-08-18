package cn.zimu.fulfillment.message;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Durable, idempotent channel evidence intake. It intentionally performs no order interpretation. */
@Service
public class ChannelMessageIntakeService {

    private final JdbcTemplate jdbcTemplate;

    public ChannelMessageIntakeService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public long store(ChannelMessageCommand command) {
        List<Long> inserted = jdbcTemplate.query(
                """
                INSERT INTO app.channel_messages (
                    corp_id, connection_id, bot_id, message_id, chat_id, chat_type,
                    sender_user_id, message_type, content, quote_type, quote_content,
                    raw_payload, sender_identity_type, sender_access_type
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, COALESCE(?, 'EMPLOYEE'), ?)
                ON CONFLICT (corp_id, connection_id, message_id) DO NOTHING
                RETURNING id
                """,
                (resultSet, rowNumber) -> resultSet.getLong(1),
                command.corpId(),
                command.connectionId(),
                command.botId(),
                command.messageId(),
                command.chatId(),
                command.chatType(),
                command.senderUserId(),
                command.messageType(),
                command.content(),
                command.quoteType(),
                command.quoteContent(),
                command.rawPayload().toString(),
                command.senderIdentityType(),
                command.senderAccessType());
        if (!inserted.isEmpty()) {
            return inserted.getFirst();
        }
        Long existing = jdbcTemplate.queryForObject(
                """
                SELECT id FROM app.channel_messages
                WHERE corp_id = ? AND connection_id = ? AND message_id = ?
                """,
                Long.class,
                command.corpId(),
                command.connectionId(),
                command.messageId());
        if (existing == null) {
            throw new IllegalStateException("idempotent channel message was not visible after conflict");
        }
        return existing;
    }
}
