package cn.zimu.fulfillment.file;

import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.connector.wecom.MediaEvidenceCommand;
import cn.zimu.fulfillment.connector.wecom.WecomMediaEvidenceService;
import cn.zimu.fulfillment.message.AsyncTaskStore;
import cn.zimu.fulfillment.message.MediaResult;
import cn.zimu.fulfillment.message.MediaResultStatus;
import cn.zimu.fulfillment.message.MessageMediaContentService;
import cn.zimu.fulfillment.message.WecomTrackingFileFailureCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 单聊 file → 下载/解密证据 → 只读 24 列解析 → 草稿应用。 */
@Service
public class WecomTrackingFileProcessor {

    private static final String MEDIA_KEY = "file-0";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final WecomMediaEvidenceService mediaEvidence;
    private final MessageMediaContentService mediaContent;
    private final TrackingFileService trackingFiles;
    private final WecomTrackingFileDraftService draftService;

    public WecomTrackingFileProcessor(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            WecomMediaEvidenceService mediaEvidence,
            MessageMediaContentService mediaContent,
            TrackingFileService trackingFiles,
            WecomTrackingFileDraftService draftService) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.mediaEvidence = mediaEvidence;
        this.mediaContent = mediaContent;
        this.trackingFiles = trackingFiles;
        this.draftService = draftService;
    }

    public void process(AsyncTaskStore.AsyncTask task) {
        SourceMessage source = source(task.submissionId());
        if (!"file".equals(source.messageType())) {
            throw new WecomTrackingFileException(
                    WecomTrackingFileFailureCode.WECOM_TRACKING_FILE_PAYLOAD_INVALID);
        }
        if (!"single".equals(source.chatType())) {
            throw new WecomTrackingFileException(
                    WecomTrackingFileFailureCode.WECOM_TRACKING_FILE_CHAT_UNSUPPORTED);
        }
        WecomMediaEvidenceService.FileRef ref = WecomMediaEvidenceService.extractFileRef(source.body());
        if (ref == null) {
            throw new WecomTrackingFileException(
                    WecomTrackingFileFailureCode.WECOM_TRACKING_FILE_PAYLOAD_INVALID);
        }

        MediaResult media = mediaEvidence.storeMedia(new MediaEvidenceCommand(
                source.channelMessageId(),
                task.submissionId(),
                MEDIA_KEY,
                "file",
                ref.url(),
                ref.aeskey()));
        if (media.status() != MediaResultStatus.SUCCEEDED) {
            WecomTrackingFileFailureCode code = media.failureReason() != null
                            && media.failureReason().contains("超过大小上限")
                    ? WecomTrackingFileFailureCode.WECOM_TRACKING_FILE_TOO_LARGE
                    : WecomTrackingFileFailureCode.WECOM_TRACKING_FILE_DOWNLOAD_FAILED;
            throw new WecomTrackingFileException(code);
        }

        byte[] bytes;
        try {
            bytes = mediaContent.load(media.mediaId()).bytes();
        } catch (BusinessException exception) {
            throw new WecomTrackingFileException(
                    WecomTrackingFileFailureCode.WECOM_TRACKING_FILE_PROCESSING_FAILED);
        }
        TrackingFileService.ParsedTrackingFile parsed;
        try {
            parsed = trackingFiles.parseForDraft(bytes);
        } catch (BusinessException exception) {
            throw new WecomTrackingFileException(
                    WecomTrackingFileFailureCode.WECOM_TRACKING_FILE_INVALID);
        }
        draftService.apply(task, media.mediaId(), parsed);
    }

    private SourceMessage source(long submissionId) {
        List<SourceMessage> rows = jdbc.query(
                """
                SELECT cm.id, cm.chat_type, cm.message_type, cm.raw_payload::text
                FROM app.message_submissions ms
                JOIN app.channel_messages cm ON cm.id=ms.source_message_id
                WHERE ms.id=?
                """,
                (resultSet, rowNum) -> {
                    try {
                        JsonNode raw = objectMapper.readTree(resultSet.getString("raw_payload"));
                        return new SourceMessage(
                                resultSet.getLong("id"),
                                resultSet.getString("chat_type"),
                                resultSet.getString("message_type"),
                                raw.path("body"));
                    } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
                        throw new WecomTrackingFileException(
                                WecomTrackingFileFailureCode.WECOM_TRACKING_FILE_PAYLOAD_INVALID);
                    }
                },
                submissionId);
        if (rows.isEmpty()) {
            throw new WecomTrackingFileException(
                    WecomTrackingFileFailureCode.WECOM_TRACKING_FILE_PAYLOAD_INVALID);
        }
        return rows.getFirst();
    }

    private record SourceMessage(long channelMessageId, String chatType, String messageType, JsonNode body) {}
}
