package cn.zimu.fulfillment.connector.wecom;

import cn.zimu.fulfillment.message.MediaResult;
import cn.zimu.fulfillment.message.MediaResultStatus;
import cn.zimu.fulfillment.message.MessageMediaStore;
import cn.zimu.fulfillment.message.MessageMediaStore.MediaState;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 媒体证据链路编排：下载 → 解密 → 校验 → 受控存储 → 落库。
 *
 * <p>对外接线 API（供接收链路调用）：{@link #storeMedia(MediaEvidenceCommand)}。语义：
 * <ul>
 *   <li>幂等：同一 (channelMessageId, channelMediaId) 已 AVAILABLE 时直接返回既有结果，不重复下载；</li>
 *   <li>失败：attempts + 1 并记录原因；达到 {@link #MAX_ATTEMPTS} 次后终态 FAILED（重试调度由任务框架负责）；</li>
 *   <li>受控存储按明文 SHA-256 内容寻址，同内容跨消息复用同一文件，原件不可变。</li>
 * </ul>
 * 下载/解密在事务外执行，只有落库与状态更新在各自事务内。
 */
@Service
public class WecomMediaEvidenceService {

    /** 与 AsyncTaskStore 默认 max_attempts 对齐；达到后终态失败，交由任务框架转人工待办。 */
    public static final int MAX_ATTEMPTS = 3;

    /** 媒体明文大小上限（与下载限量一致；PKCS#7 填充使密文 ≥ 明文，下载上限即明文上限）。 */
    public static final int MAX_MEDIA_BYTES = 20 * 1024 * 1024;

    private final WecomMediaDownloader downloader;
    private final WecomMediaCrypto crypto;
    private final WecomMediaFileStore fileStore;
    private final MessageMediaStore mediaStore;

    public WecomMediaEvidenceService(
            WecomMediaDownloader downloader,
            WecomMediaCrypto crypto,
            WecomMediaFileStore fileStore,
            MessageMediaStore mediaStore) {
        this.downloader = downloader;
        this.crypto = crypto;
        this.fileStore = fileStore;
        this.mediaStore = mediaStore;
    }

    public MediaResult storeMedia(MediaEvidenceCommand command) {
        long mediaId = mediaStore.ensurePending(
                command.channelMessageId(),
                command.submissionId(),
                command.channelMediaId(),
                command.mediaType(),
                command.sourceUrl());
        try {
            MediaState existing = mediaStore.find(command.channelMessageId(), command.channelMediaId())
                    .orElseThrow(() -> new IllegalStateException("media row vanished after ensurePending"));
            if ("AVAILABLE".equals(existing.downloadStatus())) {
                return MediaResult.succeeded(
                        existing.id(),
                        existing.contentRef(),
                        existing.contentHash(),
                        existing.contentType(),
                        existing.sizeBytes());
            }
            return process(command, mediaId);
        } catch (RuntimeException exception) {
            if (Thread.currentThread().isInterrupted()) {
                // 计划关闭/线程取消不是媒体业务失败；保留 PENDING 证据行，
                // 由任务 owner 无损回队后重新下载，不能消耗媒体 attempts。
                throw exception;
            }
            String reason = errorMessage(exception);
            String status = mediaStore.recordFailure(mediaId, reason, MAX_ATTEMPTS);
            if ("AVAILABLE".equals(status)) {
                MediaState available = mediaStore.find(command.channelMessageId(), command.channelMediaId())
                        .filter(state -> "AVAILABLE".equals(state.downloadStatus()))
                        .orElseThrow(() -> new IllegalStateException("available media row vanished after concurrent success"));
                return MediaResult.succeeded(
                        available.id(),
                        available.contentRef(),
                        available.contentHash(),
                        available.contentType(),
                        available.sizeBytes());
            }
            return MediaResult.failed(
                    "FAILED".equals(status) ? MediaResultStatus.FAILED : MediaResultStatus.PENDING,
                    mediaId,
                    reason);
        }
    }

    private MediaResult process(MediaEvidenceCommand command, long mediaId) {
        WecomMediaDownloader.DownloadedMedia downloaded = downloader.download(command.sourceUrl(), MAX_MEDIA_BYTES);
        byte[] plain = crypto.decrypt(downloaded.bytes(), command.aeskeyBase64());
        if (plain.length == 0) {
            throw new IllegalStateException("媒体解密结果为空");
        }
        if (plain.length > MAX_MEDIA_BYTES) {
            throw new IllegalStateException("媒体明文超过大小上限 " + MAX_MEDIA_BYTES + " 字节");
        }
        String sha256 = fileStore.sha256(plain);
        String storageRef = fileStore.put(plain);
        String contentType = downloaded.contentType();
        mediaStore.markAvailable(mediaId, storageRef, sha256, contentType, plain.length, decryptSpec());
        return MediaResult.succeeded(mediaId, storageRef, sha256, contentType, (long) plain.length);
    }

    /** 解密规格元数据（不含密钥材料：aeskey 是一次性下载凭据，不持久化）。 */
    private static Map<String, Object> decryptSpec() {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("algorithm", "AES-256-CBC");
        spec.put("block_padding", "PKCS7-32");
        spec.put("iv_source", "aeskey-prefix-16");
        return spec;
    }

    private static String errorMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
    }

    /** 企微消息帧里的一条待下载媒体（url 5 分钟有效 + 每 URL 独立 aeskey）。 */
    public record MediaRef(String url, String aeskey) {}

    /**
     * 从企微消息帧（aibot_msg_callback 的 body）提取 image/mixed 的媒体项列表（顺序稳定）。
     * 供接收链路与解释任务共用；voice/file/video 不在此列。
     */
    public static List<MediaRef> extractMediaRefs(JsonNode body) {
        List<MediaRef> refs = new ArrayList<>();
        if (body == null || body.isMissingNode() || !body.isObject()) {
            return refs;
        }
        if ("image".equals(body.path("msgtype").asText())) {
            JsonNode image = body.path("image");
            String url = image.path("url").asText(null);
            if (url != null && !url.isBlank()) {
                refs.add(new MediaRef(url, image.path("aeskey").asText(null)));
            }
            return refs;
        }
        if ("mixed".equals(body.path("msgtype").asText())) {
            JsonNode mixed = body.path("mixed");
            JsonNode items = mixed.path("items");
            if (items.isMissingNode() || items.isNull()) {
                items = mixed.path("msg_item");
            }
            if (items != null && items.isArray()) {
                for (JsonNode item : items) {
                    if ("image".equals(item.path("msgtype").asText())) {
                        String url = item.path("url").asText(null);
                        if (url != null && !url.isBlank()) {
                            refs.add(new MediaRef(url, item.path("aeskey").asText(null)));
                        }
                    }
                }
            }
        }
        return refs;
    }

    /** 单聊 file 专用提取；文件由确定性运单任务处理，绝不并入模型媒体引用。 */
    public static FileRef extractFileRef(JsonNode body) {
        if (body == null || !body.isObject() || !"file".equals(body.path("msgtype").asText())) {
            return null;
        }
        JsonNode file = body.path("file");
        String url = file.path("url").asText(null);
        String aeskey = file.path("aeskey").asText(null);
        if (url == null || url.isBlank() || aeskey == null || aeskey.isBlank()) {
            return null;
        }
        String filename = file.path("filename").asText(null);
        if (filename == null || filename.isBlank()) {
            filename = file.path("name").asText(null);
        }
        return new FileRef(url, aeskey, filename);
    }

    public record FileRef(String url, String aeskey, String filename) {}
}
