package cn.zimu.fulfillment.message;

import cn.zimu.fulfillment.common.dto.PageResponse;
import cn.zimu.fulfillment.common.error.BusinessException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ChannelMessageQueryService {

    private static final int PREVIEW_LIMIT = 240;

    private final JdbcTemplate jdbcTemplate;

    public ChannelMessageQueryService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(readOnly = true)
    public PageResponse<ChannelMessageSummaryDto> list(int page, int size) {
        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM app.channel_messages", Long.class);
        long total = count == null ? 0 : count;
        List<ChannelMessageSummaryDto> items = jdbcTemplate.query(
                """
                SELECT id, corp_id, connection_id, bot_id, message_id, chat_id, chat_type,
                       sender_user_id, message_type, content, received_at
                FROM app.channel_messages
                ORDER BY received_at DESC, id DESC
                LIMIT ? OFFSET ?
                """,
                ChannelMessageQueryService::summary,
                size,
                (long) page * size);
        return new PageResponse<>(items, page, size, total, (int) Math.ceil((double) total / size));
    }

    @Transactional(readOnly = true)
    public ChannelMessageDetailDto detail(long id) {
        List<ChannelMessageDetailDto> matches = jdbcTemplate.query(
                """
                SELECT id, corp_id, connection_id, bot_id, message_id, chat_id, chat_type,
                       sender_user_id, message_type, content, quote_type, quote_content,
                       received_at
                FROM app.channel_messages
                WHERE id = ?
                """,
                ChannelMessageQueryService::detail,
                id);
        if (matches.isEmpty()) {
            throw BusinessException.notFound("消息记录不存在: " + id);
        }
        return matches.getFirst();
    }

    private static ChannelMessageSummaryDto summary(ResultSet rs, int rowNumber) throws SQLException {
        String content = rs.getString("content");
        String preview = content.length() <= PREVIEW_LIMIT ? content : content.substring(0, PREVIEW_LIMIT) + "…";
        return new ChannelMessageSummaryDto(
                String.valueOf(rs.getLong("id")),
                rs.getString("corp_id"),
                rs.getString("connection_id"),
                rs.getString("bot_id"),
                rs.getString("message_id"),
                rs.getString("chat_id"),
                rs.getString("chat_type"),
                rs.getString("sender_user_id"),
                rs.getString("message_type"),
                preview,
                instant(rs, "received_at"));
    }

    private static ChannelMessageDetailDto detail(ResultSet rs, int rowNumber) throws SQLException {
        String id = String.valueOf(rs.getLong("id"));
        return new ChannelMessageDetailDto(
                id,
                rs.getString("corp_id"),
                rs.getString("connection_id"),
                rs.getString("bot_id"),
                rs.getString("message_id"),
                rs.getString("chat_id"),
                rs.getString("chat_type"),
                rs.getString("sender_user_id"),
                rs.getString("message_type"),
                rs.getString("content"),
                rs.getString("quote_type"),
                rs.getString("quote_content"),
                "channel-message-payload:" + id,
                instant(rs, "received_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }
}
