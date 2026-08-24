package cn.zimu.fulfillment.message;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 媒体证据行的幂等落库与状态机（对齐 {@link ChannelMessageIntakeService} 的 ON CONFLICT 骨架）。
 *
 * <p>幂等键为 (channel_message_id, channel_media_id)：并发或重试不会产生重复行。下载/解密由
 * 适配层编排，本类只负责持久化：建行（PENDING）、成功后置 AVAILABLE、失败后 attempts+1 并记录
 * 原因，达到重试上限时转终态 FAILED。
 */
@Service
public class MessageMediaStore {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public MessageMediaStore(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /** 媒体行的轻量投影，供编排服务做幂等重入与终态判断。 */
    public record MediaState(
            long id,
            String downloadStatus,
            String contentRef,
            String contentHash,
            String contentType,
            Long sizeBytes) {}

    /**
     * 独立事务（REQUIRES_NEW）：媒体落库必须不随解释任务事务回滚——解释失败重试时媒体 attempts
     * 需要跨尝试累积，最终 FAILED 才能驱动任务终态（wecom-message-intake 07）。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long ensurePending(
            Long channelMessageId,
            Long submissionId,
            String channelMediaId,
            String mediaType,
            String sourceUrl) {
        List<Long> inserted = jdbc.query(
                """
                INSERT INTO app.message_media (
                    channel_message_id, submission_id, channel_media_id, media_type,
                    download_status, source_url, attempts
                ) VALUES (?, ?, ?, ?, 'PENDING', ?, 0)
                ON CONFLICT (channel_message_id, channel_media_id) DO NOTHING
                RETURNING id
                """,
                (resultSet, rowNumber) -> resultSet.getLong(1),
                channelMessageId,
                submissionId,
                channelMediaId,
                mediaType,
                sourceUrl);
        if (!inserted.isEmpty()) {
            return inserted.getFirst();
        }
        Long existing = jdbc.queryForObject(
                """
                SELECT id FROM app.message_media
                WHERE channel_message_id = ? AND channel_media_id = ?
                """,
                Long.class,
                channelMessageId,
                channelMediaId);
        if (existing == null) {
            throw new IllegalStateException("idempotent message_media row was not visible after conflict");
        }
        return existing;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<MediaState> find(long channelMessageId, String channelMediaId) {
        List<MediaState> rows = jdbc.query(
                """
                SELECT id, download_status, content_ref, content_hash, content_type, size_bytes
                FROM app.message_media
                WHERE channel_message_id = ? AND channel_media_id = ?
                """,
                (resultSet, rowNumber) -> new MediaState(
                        resultSet.getLong("id"),
                        resultSet.getString("download_status"),
                        resultSet.getString("content_ref"),
                        resultSet.getString("content_hash"),
                        resultSet.getString("content_type"),
                        resultSet.getLong("size_bytes")),
                channelMessageId,
                channelMediaId);
        return rows.stream().findFirst();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markAvailable(
            long id,
            String contentRef,
            String contentHash,
            String contentType,
            long sizeBytes,
            Map<String, Object> decryptInfo) {
        jdbc.update(
                """
                UPDATE app.message_media
                SET download_status = 'AVAILABLE',
                    content_ref = ?,
                    content_hash = ?,
                    content_type = ?,
                    size_bytes = ?,
                    decrypt_info = ?::jsonb,
                    failure_reason = NULL,
                    updated_at = CURRENT_TIMESTAMP
                    WHERE id = ?
                    """,
                    contentRef,
                    contentHash,
                    contentType,
                    sizeBytes,
                    toJson(decryptInfo),
                    id);
        }

    /**
     * 记录一次失败尝试：attempts + 1；达到 {@code maxAttempts} 时转终态 FAILED，否则回到
     * PENDING 等待调用方（任务框架）安排重试。返回更新后的 download_status。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public String recordFailure(long id, String error, int maxAttempts) {
        List<String> states = jdbc.query(
                """
                UPDATE app.message_media
                SET attempts = attempts + 1,
                    failure_reason = ?,
                    download_status = CASE
                                          WHEN attempts + 1 >= ? THEN 'FAILED'
                                          ELSE 'PENDING'
                                      END,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND download_status <> 'AVAILABLE'
                RETURNING download_status
                """,
                (resultSet, rowNum) -> resultSet.getString(1),
                error,
                maxAttempts,
                id);
        if (!states.isEmpty()) {
            return states.getFirst();
        }
        // 并发的新 owner 可能已成功留存证据；失败结果不得把 AVAILABLE
        // 降级回 PENDING/FAILED。重读终态供编排层返回已有成功证据。
        return jdbc.queryForObject(
                "SELECT download_status FROM app.message_media WHERE id=?",
                String.class,
                id);
    }

    private String toJson(Map<String, Object> value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法序列化媒体解密信息", exception);
        }
    }
}
