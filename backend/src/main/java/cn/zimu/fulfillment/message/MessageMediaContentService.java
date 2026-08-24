package cn.zimu.fulfillment.message;

import cn.zimu.fulfillment.common.error.BusinessException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 复核页原图受权读取（wecom-message-intake 07）：按 {@code message_media} 受控引用读取解密后的
 * 原件字节。只暴露文件内容与内容类型，绝不暴露磁盘路径、下载凭据或 aeskey；未就绪（PENDING /
 * FAILED / 不存在）一律 404，不区分原因。
 */
@Service
public class MessageMediaContentService {

    private final JdbcTemplate jdbc;
    private final Path mediaRoot;

    public MessageMediaContentService(
            JdbcTemplate jdbc, @Value("${app.media.dir:./data/media}") String mediaRoot) {
        this.jdbc = jdbc;
        // WecomMediaFileStore 持久化绝对 content_ref；受控根也统一绝对化，默认相对配置下
        // startsWith 校验仍能成立，且不会放宽越界读取。
        this.mediaRoot = Path.of(mediaRoot).toAbsolutePath().normalize();
    }

    public MediaContent load(long mediaId) {
        List<MediaRow> rows = jdbc.query(
                """
                SELECT content_ref, content_type
                FROM app.message_media
                WHERE id = ? AND download_status = 'AVAILABLE'
                """,
                (resultSet, rowNumber) -> new MediaRow(
                        resultSet.getString("content_ref"),
                        resultSet.getString("content_type")),
                mediaId);
        if (rows.isEmpty() || rows.getFirst().contentRef() == null || rows.getFirst().contentRef().isBlank()) {
            throw BusinessException.notFound("媒体证据不存在或尚未就绪: " + mediaId);
        }
        Path file = mediaRoot.resolve(rows.getFirst().contentRef()).normalize();
        if (!file.startsWith(mediaRoot)) {
            throw BusinessException.notFound("媒体证据引用无效: " + mediaId);
        }
        try {
            byte[] bytes = Files.readAllBytes(file);
            return new MediaContent(bytes, rows.getFirst().contentType());
        } catch (IOException ex) {
            throw BusinessException.notFound("媒体证据文件不可读: " + mediaId);
        }
    }

    public record MediaContent(byte[] bytes, String contentType) {}

    private record MediaRow(String contentRef, String contentType) {}
}
