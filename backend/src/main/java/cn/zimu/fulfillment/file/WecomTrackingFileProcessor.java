package cn.zimu.fulfillment.file;

import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.web.CommandContext;
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
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 单聊 file → 下载/解密证据 → **先认模板再分岔**：
 * 认得出来源渠道就当订单表导入，认不出才走 24 列运单回传解析 → 草稿应用。
 *
 * <p><b>为什么要分岔</b>：此前这条链路硬接在运单回传上，渠道（中汇/大者）通过企微发来的
 * 订单表一律报「不符合精确 24 列模板」。而这两个渠道没有平台 API，企微就是它们唯一的入口——
 * 等于说「用企微发订单」这件事从来就做不到。
 *
 * <p><b>顺序是先订单后运单，不是反过来</b>：来源模板指纹是精确的必填集匹配（认错的概率极低），
 * 而 24 列运单解析对不上时只会抛一个笼统的 INVALID。让精确的先判，模糊的兜底。
 */
@Service
public class WecomTrackingFileProcessor {

    private static final String MEDIA_KEY = "file-0";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final WecomMediaEvidenceService mediaEvidence;
    private final MessageMediaContentService mediaContent;
    private final TrackingFileService trackingFiles;
    private final WecomTrackingFileDraftService draftService;
    private final SourceImportService sourceImport;
    private final SourceFileParser sourceFileParser = new SourceFileParser();

    public WecomTrackingFileProcessor(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            WecomMediaEvidenceService mediaEvidence,
            MessageMediaContentService mediaContent,
            TrackingFileService trackingFiles,
            WecomTrackingFileDraftService draftService,
            SourceImportService sourceImport) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.mediaEvidence = mediaEvidence;
        this.mediaContent = mediaContent;
        this.trackingFiles = trackingFiles;
        this.draftService = draftService;
        this.sourceImport = sourceImport;
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
        // 先问一句「这是不是来源订单表」。只读表头，无副作用。
        Optional<SourceChannel> channel = sourceFileParser.detectChannel(bytes);
        if (channel.isPresent()) {
            importSourceOrders(task, bytes, channel.get());
            return;
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

    /**
     * 企微来的订单表：走与后台上传完全同一条导入路径。
     *
     * <p>不另开导入捷径——去重、修订链、SKU 映射、复核事项全在 {@code upload} 里，
     * 绕过去等于让「企微发的」和「后台传的」走两套规则。
     *
     * <p>幂等键绑住 submission：企微重推同一条消息不会导入出第二个批次。
     */
    private void importSourceOrders(AsyncTaskStore.AsyncTask task, byte[] bytes, SourceChannel channel) {
        String operator = "wecom-file";
        CommandContext context = new CommandContext(
                "wecom-file-" + task.submissionId(), null, operator, operator);
        try {
            sourceImport.upload(
                    bytes,
                    channel.name().toLowerCase(java.util.Locale.ROOT) + "-wecom.xlsx",
                    "NEW",
                    null,
                    "wecom-source-import-" + task.submissionId(),
                    context);
        } catch (BusinessException exception) {
            // 认出了渠道却导不进去，是模板对了内容不对（缺必填、数量非法等）。
            // 沿用既有的复核事项通道让人看见，而不是把异常吞掉。
            throw new WecomTrackingFileException(
                    WecomTrackingFileFailureCode.WECOM_TRACKING_FILE_INVALID);
        }
        // 导入成功必须当场收口任务（置 SUCCEEDED），否则租约到期被重领、attempts 耗尽后
        // 会被兜底判成 WECOM_TRACKING_FILE_PROCESSING_FAILED——2026-08-31 中汇生产事故根因。
        draftService.succeedSourceImport(task);
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
