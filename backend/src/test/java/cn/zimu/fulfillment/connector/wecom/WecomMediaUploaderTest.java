package cn.zimu.fulfillment.connector.wecom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * 三步分片素材上传（Issue #82）帧级测试：手写 RFC6455 服务器自动应答 init/chunk/finish。
 * 覆盖：成功重组与元数据/MD5、逐类型校验 fail-fast、errcode/缺字段/ack 超时 fail closed、
 * NOT_READY 三阶段确定性恢复（同 index 重发、finish 只提交一次、预算消耗）、断线幂等续传
 * 与有界预算、会话 30 分钟超期、finish 证据矛盾（type/created_at）→ UNKNOWN、
 * 心跳优先级与发送串行化（事件驱动、无固定睡眠）。
 */
class WecomMediaUploaderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BOT_ID = "bot-123";
    private static final String SECRET = "secret-abc";
    private static final long DEFAULT_SESSION_TTL_MILLIS = Duration.ofMinutes(30).toMillis();
    private static final long DEFAULT_ACK_TIMEOUT_MILLIS = 10_000;
    private static final int DEFAULT_MAX_RESUME_ATTEMPTS = 5;
    private static final long DEFAULT_WAIT_SUBSCRIBED_MILLIS = 60_000;

    @TempDir
    Path tempDir;

    private Rfc6455TestServer server;
    private WecomConnectionStateHolder stateHolder;
    private ScheduledExecutorService scheduler;
    private WecomLongConnectionClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new Rfc6455TestServer();
        stateHolder = new WecomConnectionStateHolder();
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "wecom-upload-test-scheduler");
            thread.setDaemon(true);
            return thread;
        });
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.shutdown();
            client = null;
        }
        scheduler.shutdownNow();
        server.close();
    }

    // ---- 成功路径与协议元数据 ----

    @Test
    void fileUploadSucceedsWithMd5MetadataAndReassembledChunks() throws Exception {
        startClient();
        byte[] content = seededBytes(1_400_000);
        Path file = writeTempFile(content, ".xlsx");

        WecomUploadResult result = newUploader().upload(file, "order-export.xlsx", WecomMediaType.FILE);

        assertThat(result.status()).isEqualTo(WecomUploadStatus.SUCCESS);
        assertThat(result.mediaId()).isNotBlank();
        assertThat(result.mediaType()).isEqualTo("file");
        assertThat(result.createdAt()).isEqualTo(Instant.ofEpochSecond(1_380_000_000L));
        assertThat(result.acknowledgedAt()).isNotNull();
        assertThat(result.uploadId()).isNotBlank();
        assertThat(result.requestId()).isNotBlank();
        assertThat(result.retryable()).isFalse();
        assertThat(result.errorCode()).isNull();
        assertThat(result.errorMessage()).isNull();

        JsonNode init = MAPPER.readTree(server.uploadFrames("aibot_upload_media_init").getFirst());
        assertThat(init.path("headers").path("req_id").asText()).isNotBlank();
        assertThat(init.has("req_id")).isFalse();
        JsonNode initBody = init.path("body");
        assertThat(initBody.path("type").asText()).isEqualTo("file");
        assertThat(initBody.path("filename").asText()).isEqualTo("order-export.xlsx");
        assertThat(initBody.path("total_size").asLong()).isEqualTo(content.length);
        assertThat(initBody.path("total_chunks").asInt()).isEqualTo(3);
        assertThat(initBody.path("md5").asText()).isEqualTo(md5Hex(content));

        List<String> chunkFrames = server.uploadFrames("aibot_upload_media_chunk");
        assertThat(chunkFrames).hasSize(3);
        assertThat(chunkIndexes(chunkFrames)).containsExactly(0, 1, 2);
        for (JsonNode chunk : parseAll(chunkFrames)) {
            assertThat(chunk.path("headers").path("req_id").asText()).isNotBlank();
            assertThat(chunk.path("body").path("upload_id").asText()).isEqualTo(result.uploadId());
        }
        assertThat(clientReassembledContent(chunkFrames, content.length)).isEqualTo(content);

        assertThat(server.assembledBytes(result.uploadId())).isEqualTo(content);
        assertThat(server.chunkIndexOrder(result.uploadId())).containsExactly(0, 1, 2);

        List<String> finishes = server.uploadFrames("aibot_upload_media_finish");
        assertThat(finishes).hasSize(1);
        JsonNode finish = MAPPER.readTree(finishes.getFirst());
        assertThat(finish.path("headers").path("req_id").asText()).isNotBlank();
        assertThat(finish.path("body").path("upload_id").asText()).isEqualTo(result.uploadId());
    }

    // ---- 上传前 fail-fast 校验 ----

    @Test
    void rejectsFileBelowFiveBytesBeforeInit() throws Exception {
        startClient();
        Path file = writeTempFile(new byte[] {1, 2, 3, 4}, ".xlsx");

        assertThatThrownBy(() -> newUploader().upload(file, "tiny.xlsx", WecomMediaType.FILE))
                .isInstanceOf(WecomUploadValidationException.class)
                .hasMessageContaining("5")
                .satisfies(ex -> assertThat(((WecomUploadValidationException) ex).code())
                        .isEqualTo("UPLOAD_FILE_TOO_SMALL"));
        assertThat(server.uploadFrames("aibot_upload_media_init")).isEmpty();
    }

    @Test
    void fiveByteFileIsAccepted() throws Exception {
        startClient();
        Path file = writeTempFile(new byte[] {1, 2, 3, 4, 5}, ".xlsx");

        WecomUploadResult result = newUploader().upload(file, "min.xlsx", WecomMediaType.FILE);

        assertThat(result.status()).isEqualTo(WecomUploadStatus.SUCCESS);
        assertThat(server.assembledBytes(result.uploadId())).isEqualTo(new byte[] {1, 2, 3, 4, 5});
    }

    @Test
    void rejectsFileAboveTypeLimitBeforeInit() throws Exception {
        startClient();
        Path file = writeTempFile(new byte[20 * 1024 * 1024 + 1], ".xlsx");

        assertThatThrownBy(() -> newUploader().upload(file, "huge.xlsx", WecomMediaType.FILE))
                .isInstanceOf(WecomUploadValidationException.class)
                .hasMessageContaining("上限")
                .satisfies(ex -> assertThat(((WecomUploadValidationException) ex).code())
                        .isEqualTo("UPLOAD_FILE_TOO_LARGE"));
        assertThat(server.uploadFrames("aibot_upload_media_init")).isEmpty();
    }

    @Test
    void rejectsImageAboveImageLimitBeforeInit() throws Exception {
        startClient();
        Path file = writeTempFile(new byte[10 * 1024 * 1024 + 1], ".png");

        assertThatThrownBy(() -> newUploader().upload(file, "huge.png", WecomMediaType.IMAGE))
                .isInstanceOf(WecomUploadValidationException.class)
                .hasMessageContaining("上限");
        assertThat(server.uploadFrames("aibot_upload_media_init")).isEmpty();
    }

    @Test
    void rejectsInvalidImageExtensionBeforeInit() throws Exception {
        startClient();
        Path file = writeTempFile(new byte[] {1, 2, 3, 4, 5, 6, 7, 8}, ".txt");

        assertThatThrownBy(() -> newUploader().upload(file, "note.txt", WecomMediaType.IMAGE))
                .isInstanceOf(WecomUploadValidationException.class)
                .hasMessageContaining("png")
                .satisfies(ex -> assertThat(((WecomUploadValidationException) ex).code())
                        .isEqualTo("UPLOAD_EXTENSION_NOT_ALLOWED"));
        assertThat(server.uploadFrames("aibot_upload_media_init")).isEmpty();
    }

    @Test
    void rejectsExtensionMismatchWithDeclaredType() throws Exception {
        startClient();
        Path png = writeTempFile(new byte[] {1, 2, 3, 4, 5, 6, 7, 8}, ".png");
        Path xlsx = writeTempFile(new byte[] {1, 2, 3, 4, 5, 6, 7, 8}, ".xlsx");

        assertThatThrownBy(() -> newUploader().upload(png, "pic.png", WecomMediaType.FILE))
                .isInstanceOf(WecomUploadValidationException.class);
        assertThatThrownBy(() -> newUploader().upload(xlsx, "book.xlsx", WecomMediaType.IMAGE))
                .isInstanceOf(WecomUploadValidationException.class);
        assertThat(server.uploadFrames("aibot_upload_media_init")).isEmpty();
    }

    @Test
    void rejectsFilenameOver256Utf8BytesBeforeInit() throws Exception {
        startClient();
        Path file = writeTempFile(new byte[] {1, 2, 3, 4, 5, 6, 7, 8}, ".xlsx");
        String longName = "中".repeat(86) + ".xlsx"; // 258 UTF-8 字节

        assertThatThrownBy(() -> newUploader().upload(file, longName, WecomMediaType.FILE))
                .isInstanceOf(WecomUploadValidationException.class)
                .hasMessageContaining("256")
                .satisfies(ex -> assertThat(((WecomUploadValidationException) ex).code())
                        .isEqualTo("UPLOAD_FILENAME_TOO_LONG"));
        assertThat(server.uploadFrames("aibot_upload_media_init")).isEmpty();
    }

    @Test
    void acceptsFilenameAtExactly256Utf8Bytes() throws Exception {
        startClient();
        Path file = writeTempFile(new byte[] {1, 2, 3, 4, 5, 6, 7, 8}, ".xlsx");
        String boundaryName = "中".repeat(84) + ".xls"; // 84*3 + 4 = 256 字节整

        WecomUploadResult result = newUploader().upload(file, boundaryName, WecomMediaType.FILE);

        assertThat(result.status()).isEqualTo(WecomUploadStatus.SUCCESS);
        JsonNode init = MAPPER.readTree(server.uploadFrames("aibot_upload_media_init").getFirst());
        assertThat(init.path("body").path("filename").asText()).isEqualTo(boundaryName);
    }

    @Test
    void rejectsMissingOrNonRegularFileBeforeInit() throws Exception {
        startClient();
        Path missing = tempDir.resolve("missing.xlsx");
        Path directory = Files.createDirectory(tempDir.resolve("a-directory"));

        assertThatThrownBy(() -> newUploader().upload(missing, "missing.xlsx", WecomMediaType.FILE))
                .isInstanceOf(WecomUploadValidationException.class)
                .satisfies(ex -> assertThat(((WecomUploadValidationException) ex).code())
                        .isEqualTo("UPLOAD_FILE_NOT_FOUND"));
        assertThatThrownBy(() -> newUploader().upload(directory, "dir.xlsx", WecomMediaType.FILE))
                .isInstanceOf(WecomUploadValidationException.class)
                .satisfies(ex -> assertThat(((WecomUploadValidationException) ex).code())
                        .isEqualTo("UPLOAD_FILE_NOT_REGULAR"));
        assertThat(server.uploadFrames("aibot_upload_media_init")).isEmpty();
    }

    @Test
    void rejectsVoiceAndVideoTypesAsUnsupportedThisTicket() throws Exception {
        startClient();
        Path file = writeTempFile(new byte[] {1, 2, 3, 4, 5, 6, 7, 8}, ".amr");

        assertThatThrownBy(() -> newUploader().upload(file, "voice.amr", WecomMediaType.VOICE))
                .isInstanceOf(WecomUploadValidationException.class)
                .hasMessageContaining("暂不支持");
        assertThat(server.uploadFrames("aibot_upload_media_init")).isEmpty();
    }

    // ---- 图片成功 ----

    @Test
    void imageUploadSucceeds() throws Exception {
        startClient();
        Path file = writeTempFile(new byte[] {(byte) 0x89, 'P', 'N', 'G', 5, 6, 7, 8, 9, 10}, ".png");

        WecomUploadResult result = newUploader().upload(file, "photo.png", WecomMediaType.IMAGE);

        assertThat(result.status()).isEqualTo(WecomUploadStatus.SUCCESS);
        assertThat(result.mediaType()).isEqualTo("image");
        assertThat(result.mediaId()).isNotBlank();
        JsonNode init = MAPPER.readTree(server.uploadFrames("aibot_upload_media_init").getFirst());
        assertThat(init.path("body").path("type").asText()).isEqualTo("image");
        assertThat(server.assembledBytes(result.uploadId())).isEqualTo(
                new byte[] {(byte) 0x89, 'P', 'N', 'G', 5, 6, 7, 8, 9, 10});
    }


    // ---- fail closed：errcode / ack 超时 / 缺字段 ----

    @Test
    void initRejectionFailsClosedWithoutChunks() throws Exception {
        startClient();
        server.uploadInitErrcode(45009);
        Path file = writeTempFile(new byte[] {1, 2, 3, 4, 5, 6, 7, 8}, ".xlsx");

        WecomUploadResult result = newUploader().upload(file, "order.xlsx", WecomMediaType.FILE);

        assertThat(result.status()).isEqualTo(WecomUploadStatus.FAILED);
        assertThat(result.step()).isEqualTo("INIT");
        assertThat(result.errorCode()).isEqualTo(45009);
        assertThat(result.errorMessage()).isEqualTo("UPLOAD_INIT_REJECTED");
        assertThat(result.retryable()).isTrue();
        assertThat(result.uploadId()).isNull();
        assertThat(server.uploadFrames("aibot_upload_media_chunk")).isEmpty();
        assertThat(server.uploadFrames("aibot_upload_media_finish")).isEmpty();
    }

    @Test
    void chunkRejectionFailsClosedWithoutFinish() throws Exception {
        startClient();
        server.uploadChunkErrcode(45009);
        Path file = writeTempFile(new byte[] {1, 2, 3, 4, 5, 6, 7, 8}, ".xlsx");

        WecomUploadResult result = newUploader().upload(file, "order.xlsx", WecomMediaType.FILE);

        assertThat(result.status()).isEqualTo(WecomUploadStatus.FAILED);
        assertThat(result.step()).isEqualTo("CHUNK");
        assertThat(result.errorCode()).isEqualTo(45009);
        assertThat(result.errorMessage()).isEqualTo("UPLOAD_CHUNK_REJECTED");
        assertThat(result.retryable()).isTrue();
        assertThat(result.uploadId()).isNotBlank();
        assertThat(result.mediaId()).isNull();
        assertThat(server.uploadFrames("aibot_upload_media_finish")).isEmpty();
    }

    @Test
    void finishRejectionFailsClosedWithoutMediaId() throws Exception {
        startClient();
        server.uploadFinishErrcode(45009);
        Path file = writeTempFile(new byte[] {1, 2, 3, 4, 5, 6, 7, 8}, ".xlsx");

        WecomUploadResult result = newUploader().upload(file, "order.xlsx", WecomMediaType.FILE);

        assertThat(result.status()).isEqualTo(WecomUploadStatus.FAILED);
        assertThat(result.step()).isEqualTo("FINISH");
        assertThat(result.errorCode()).isEqualTo(45009);
        assertThat(result.errorMessage()).isEqualTo("UPLOAD_FINISH_REJECTED");
        assertThat(result.retryable()).isTrue();
        assertThat(result.mediaId()).isNull();
    }

    @Test
    void missingUploadIdInInitAckFailsClosed() throws Exception {
        startClient();
        server.omitInitUploadId(true);
        Path file = writeTempFile(new byte[] {1, 2, 3, 4, 5, 6, 7, 8}, ".xlsx");

        WecomUploadResult result = newUploader().upload(file, "order.xlsx", WecomMediaType.FILE);

        assertThat(result.status()).isEqualTo(WecomUploadStatus.FAILED);
        assertThat(result.step()).isEqualTo("INIT");
        assertThat(result.errorMessage()).isEqualTo("INIT_MISSING_UPLOAD_ID");
        assertThat(result.retryable()).isTrue();
        assertThat(server.uploadFrames("aibot_upload_media_chunk")).isEmpty();
    }

    @Test
    void missingMediaIdInFinishAckFailsClosedAsUnknown() throws Exception {
        startClient();
        server.omitFinishMediaId(true);
        Path file = writeTempFile(new byte[] {1, 2, 3, 4, 5, 6, 7, 8}, ".xlsx");

        WecomUploadResult result = newUploader().upload(file, "order.xlsx", WecomMediaType.FILE);

        // errcode=0 但缺 media_id：服务端很可能已生成媒体但不可引用 → 与 finish 未获 ack 同属未知态
        assertThat(result.status()).isEqualTo(WecomUploadStatus.UNKNOWN);
        assertThat(result.step()).isEqualTo("FINISH");
        assertThat(result.errorMessage()).isEqualTo("FINISH_MISSING_MEDIA_ID");
        assertThat(result.retryable()).isFalse();
        assertThat(result.mediaId()).isNull();
        assertThat(result.uploadId()).isNotBlank();
        assertThat(server.uploadFrames("aibot_upload_media_finish")).hasSize(1);
    }

    @Test
    void finishTypeMismatchReturnsUnknownWithoutMediaIdOrRetryable() throws Exception {
        startClient();
        server.uploadFinishType("image"); // 请求 file，应答 image：证据矛盾
        Path file = writeTempFile(new byte[] {1, 2, 3, 4, 5, 6, 7, 8}, ".xlsx");

        WecomUploadResult result = newUploader().upload(file, "order.xlsx", WecomMediaType.FILE);

        // errcode=0 且字段齐全，但 body.type 与请求类型不一致：finish 已成功而证据矛盾，
        // 可能已生成素材 → UNKNOWN，不返回 media_id、不标 retryable、只提交一次 finish
        assertThat(result.status()).isEqualTo(WecomUploadStatus.UNKNOWN);
        assertThat(result.step()).isEqualTo("FINISH");
        assertThat(result.errorMessage()).isEqualTo("FINISH_MEDIA_TYPE_MISMATCH");
        assertThat(result.retryable()).isFalse();
        assertThat(result.mediaId()).isNull();
        assertThat(result.mediaType()).isNull();
        assertThat(result.createdAt()).isNull();
        assertThat(result.uploadId()).isNotBlank();
        assertThat(result.requestId()).isNotBlank();
        assertThat(server.uploadFrames("aibot_upload_media_finish")).hasSize(1);
    }

    @Test
    void finishNonPositiveOrUnconvertibleCreatedAtReturnsUnknown() throws Exception {
        startClient();
        Path file = writeTempFile(new byte[] {1, 2, 3, 4, 5, 6, 7, 8}, ".xlsx");

        // 0
        server.uploadFinishCreatedAt(MAPPER.getNodeFactory().numberNode(0));
        assertInvalidCreatedAt(newUploader().upload(file, "order.xlsx", WecomMediaType.FILE));
        // 负数
        server.uploadFinishCreatedAt(MAPPER.getNodeFactory().numberNode(-7L));
        assertInvalidCreatedAt(newUploader().upload(file, "order.xlsx", WecomMediaType.FILE));
        // 不可转换（文本节点）
        server.uploadFinishCreatedAt(MAPPER.getNodeFactory().textNode("2026-08-21"));
        assertInvalidCreatedAt(newUploader().upload(file, "order.xlsx", WecomMediaType.FILE));

        // 每次 finish 都只真正提交一次
        assertThat(server.uploadFrames("aibot_upload_media_finish")).hasSize(3);
    }

    private static void assertInvalidCreatedAt(WecomUploadResult result) {
        // created_at 非正的 Unix 秒/不可转换：证据矛盾，不返回 media_id、不标 retryable
        assertThat(result.status()).isEqualTo(WecomUploadStatus.UNKNOWN);
        assertThat(result.step()).isEqualTo("FINISH");
        assertThat(result.errorMessage()).isEqualTo("FINISH_RESPONSE_INVALID");
        assertThat(result.retryable()).isFalse();
        assertThat(result.mediaId()).isNull();
        assertThat(result.mediaType()).isNull();
        assertThat(result.createdAt()).isNull();
        assertThat(result.uploadId()).isNotBlank();
        assertThat(result.requestId()).isNotBlank();
    }

    @Test
    void chunkAckTimeoutFailsClosedWhenBudgetExhausted() throws Exception {
        startClient();
        server.dropNextChunkAck();
        Path file = writeTempFile(new byte[] {1, 2, 3, 4, 5, 6, 7, 8}, ".xlsx");

        WecomUploadResult result = newUploader(30 * 60_000L, 400, 0, 60_000)
                .upload(file, "order.xlsx", WecomMediaType.FILE);

        assertThat(result.status()).isEqualTo(WecomUploadStatus.FAILED);
        assertThat(result.step()).isEqualTo("CHUNK");
        assertThat(result.errorMessage()).isEqualTo("UPLOAD_ACK_TIMEOUT");
        assertThat(result.retryable()).isTrue();
        assertThat(result.mediaId()).isNull();
        assertThat(server.uploadFrames("aibot_upload_media_finish")).isEmpty();
    }

    @Test
    void finishAckTimeoutReturnsUnknownWithoutBlindFinishResend() throws Exception {
        startClient();
        server.dropNextFinishAck();
        Path file = writeTempFile(new byte[] {1, 2, 3, 4, 5, 6, 7, 8}, ".xlsx");

        WecomUploadResult result = newUploader(30 * 60_000L, 400, 5, 60_000)
                .upload(file, "order.xlsx", WecomMediaType.FILE);

        assertThat(result.status()).isEqualTo(WecomUploadStatus.UNKNOWN);
        assertThat(result.step()).isEqualTo("FINISH");
        assertThat(result.errorMessage()).isEqualTo("FINISH_ACK_UNKNOWN");
        assertThat(result.retryable()).isFalse();
        assertThat(result.mediaId()).isNull();
        assertThat(result.uploadId()).isNotBlank();
        assertThat(result.requestId()).isNotBlank();
        // 禁止盲目重发 finish：只允许提交一次
        assertThat(server.uploadFrames("aibot_upload_media_finish")).hasSize(1);
    }

    // ---- 断线续传、会话超期与有界预算 ----

    @Test
    void chunkDisconnectResumesWithSameUploadIdAndIndexIdempotently() throws Exception {
        startClient();
        server.disconnectAfterChunkIndex(1);
        byte[] content = seededBytes(1_400_000); // 3 片
        Path file = writeTempFile(content, ".xlsx");

        WecomUploadResult result = newUploader().upload(file, "order.xlsx", WecomMediaType.FILE);

        assertThat(result.status()).isEqualTo(WecomUploadStatus.SUCCESS);
        assertThat(result.mediaId()).isNotBlank();
        // 断线片以相同 chunk_index 重发一次（服务端幂等，只合并一次），finish 只完成一次
        assertThat(server.chunkIndexOrder(result.uploadId())).containsExactly(0, 1, 1, 2);
        assertThat(server.assembledBytes(result.uploadId())).isEqualTo(content);
        assertThat(server.uploadFrames("aibot_upload_media_finish")).hasSize(1);
    }

    @Test
    void sessionExpiryFailsWithRetryableFromScratch() throws Exception {
        startClient();
        server.disconnectEveryChunkIndex(0);
        Path file = writeTempFile(new byte[] {1, 2, 3, 4, 5, 6, 7, 8}, ".xlsx");

        // TTL=100ms：重连退避固定 50ms（startClient 关闭抖动），每个 LOST→SUBSCRIBED 周期
        // ≥ ~55ms，第 2 个周期复查必过期限 → SESSION_EXPIRED 确定性胜出；预算耗尽需 6 个
        // 周期（≥ ~330ms），不可能先到（确定性，不依赖机器快慢）。
        WecomUploadResult result = newUploader(100, 10_000, 5, 5_000)
                .upload(file, "order.xlsx", WecomMediaType.FILE);

        assertThat(result.status()).isEqualTo(WecomUploadStatus.FAILED);
        assertThat(result.step()).isEqualTo("CHUNK");
        assertThat(result.errorMessage()).isEqualTo("UPLOAD_SESSION_EXPIRED");
        assertThat(result.retryable()).isTrue();
        assertThat(result.uploadId()).isNotBlank();
        assertThat(result.mediaId()).isNull();
        assertThat(server.uploadFrames("aibot_upload_media_finish")).isEmpty();
    }

    @Test
    void resumeBudgetExhaustionFailsClosed() throws Exception {
        startClient();
        server.disconnectEveryChunkIndex(0);
        Path file = writeTempFile(new byte[] {1, 2, 3, 4, 5, 6, 7, 8}, ".xlsx");

        WecomUploadResult result = newUploader(30 * 60_000L, 10_000, 0, 5_000)
                .upload(file, "order.xlsx", WecomMediaType.FILE);

        assertThat(result.status()).isEqualTo(WecomUploadStatus.FAILED);
        assertThat(result.step()).isEqualTo("CHUNK");
        assertThat(result.errorMessage()).isEqualTo("UPLOAD_RETRY_BUDGET_EXHAUSTED");
        assertThat(result.retryable()).isTrue();
        assertThat(result.mediaId()).isNull();
        assertThat(server.uploadFrames("aibot_upload_media_finish")).isEmpty();
    }

    // ---- NOT_READY（帧未入队、未提交）恢复：确定性 seam，不依赖概率竞态 ----

    @Test
    void initNotReadyRecoversWithFreshRequestIdAndSucceeds() throws Exception {
        startClient();
        Path file = writeTempFile(new byte[] {1, 2, 3, 4, 5, 6, 7, 8}, ".xlsx");
        AtomicInteger notReadyInits = new AtomicInteger(1);
        WecomMediaUploader uploader = newUploader((frame, requestId, timeoutMillis) -> {
            if ("aibot_upload_media_init".equals(frame.path("cmd").asText()) && notReadyInits.getAndDecrement() > 0) {
                return WecomLongConnectionClient.AckOutcome.notReady();
            }
            return client.awaitAck(frame, requestId, timeoutMillis);
        });

        WecomUploadResult result = uploader.upload(file, "order.xlsx", WecomMediaType.FILE);

        assertThat(result.status()).isEqualTo(WecomUploadStatus.SUCCESS);
        assertThat(result.mediaId()).isNotBlank();
        // 首次 init 未入队未提交：服务端只收到一次 init、只登记一个会话；重做使用新 req_id
        assertThat(server.uploadFrames("aibot_upload_media_init")).hasSize(1);
        assertThat(server.uploadSessionIds()).hasSize(1);
        assertThat(server.assembledBytes(result.uploadId()))
                .isEqualTo(new byte[] {1, 2, 3, 4, 5, 6, 7, 8});
    }

    @Test
    void chunkNotReadyResendsSameIndexAndSucceeds() throws Exception {
        startClient();
        Path file = writeTempFile(new byte[] {1, 2, 3, 4, 5, 6, 7, 8}, ".xlsx");
        AtomicInteger notReadyChunks = new AtomicInteger(1);
        WecomMediaUploader uploader = newUploader((frame, requestId, timeoutMillis) -> {
            if ("aibot_upload_media_chunk".equals(frame.path("cmd").asText()) && notReadyChunks.getAndDecrement() > 0) {
                return WecomLongConnectionClient.AckOutcome.notReady();
            }
            return client.awaitAck(frame, requestId, timeoutMillis);
        });

        WecomUploadResult result = uploader.upload(file, "order.xlsx", WecomMediaType.FILE);

        assertThat(result.status()).isEqualTo(WecomUploadStatus.SUCCESS);
        assertThat(result.mediaId()).isNotBlank();
        // 首次 chunk 未入队未提交：服务端只收到一次分片；恢复后重发仍为 chunk_index=0（同 index）
        assertThat(server.uploadFrames("aibot_upload_media_chunk")).hasSize(1);
        assertThat(server.chunkIndexOrder(result.uploadId())).containsExactly(0);
        assertThat(server.assembledBytes(result.uploadId()))
                .isEqualTo(new byte[] {1, 2, 3, 4, 5, 6, 7, 8});
        assertThat(server.uploadFrames("aibot_upload_media_finish")).hasSize(1);
    }

    @Test
    void finishNotReadyCommitsOnlyOnceAndSucceeds() throws Exception {
        startClient();
        Path file = writeTempFile(new byte[] {1, 2, 3, 4, 5, 6, 7, 8}, ".xlsx");
        AtomicInteger notReadyFinishes = new AtomicInteger(1);
        WecomMediaUploader uploader = newUploader((frame, requestId, timeoutMillis) -> {
            if ("aibot_upload_media_finish".equals(frame.path("cmd").asText()) && notReadyFinishes.getAndDecrement() > 0) {
                return WecomLongConnectionClient.AckOutcome.notReady();
            }
            return client.awaitAck(frame, requestId, timeoutMillis);
        });

        WecomUploadResult result = uploader.upload(file, "order.xlsx", WecomMediaType.FILE);

        assertThat(result.status()).isEqualTo(WecomUploadStatus.SUCCESS);
        assertThat(result.mediaId()).isNotBlank();
        // 首次 finish 未入队未提交（服务端从未收到）：真正提交只发生一次
        assertThat(server.uploadFrames("aibot_upload_media_finish")).hasSize(1);
        assertThat(server.assembledBytes(result.uploadId()))
                .isEqualTo(new byte[] {1, 2, 3, 4, 5, 6, 7, 8});
    }

    @Test
    void notReadyRecoveryConsumesResumeBudget() throws Exception {
        startClient();
        Path file = writeTempFile(new byte[] {1, 2, 3, 4, 5, 6, 7, 8}, ".xlsx");
        AtomicInteger notReadyInits = new AtomicInteger(2);
        WecomMediaUploader uploader = newUploader(
                (frame, requestId, timeoutMillis) -> {
                    if ("aibot_upload_media_init".equals(frame.path("cmd").asText())
                            && notReadyInits.getAndDecrement() > 0) {
                        return WecomLongConnectionClient.AckOutcome.notReady();
                    }
                    return client.awaitAck(frame, requestId, timeoutMillis);
                },
                30 * 60_000L,
                10_000,
                1,
                60_000);

        WecomUploadResult result = uploader.upload(file, "order.xlsx", WecomMediaType.FILE);

        // 预算 1 次：首次 NOT_READY 消耗后恢复，第二次 NOT_READY 预算耗尽即失败（可从头重试）
        assertThat(result.status()).isEqualTo(WecomUploadStatus.FAILED);
        assertThat(result.step()).isEqualTo("INIT");
        assertThat(result.errorMessage()).isEqualTo("UPLOAD_RETRY_BUDGET_EXHAUSTED");
        assertThat(result.retryable()).isTrue();
        assertThat(result.mediaId()).isNull();
        assertThat(result.uploadId()).isNull();
        // 两次 init 都未入队未提交：服务端从未收到任何 init
        assertThat(server.uploadFrames("aibot_upload_media_init")).isEmpty();
        assertThat(server.uploadSessionIds()).isEmpty();
    }

    // ---- 心跳优先级与发送串行化 ----

    @Test
    void heartbeatJumpsAheadOfQueuedChunkResendAndSendsAreSerialized() throws Exception {
        List<String> submittedCommands = new CopyOnWriteArrayList<>();
        AtomicBoolean blockFirstChunkSend = new AtomicBoolean(true);
        AtomicReference<CompletableFuture<java.net.http.WebSocket>> blockedSend = new AtomicReference<>();
        WecomLongConnectionClient.FrameWriter writer = (webSocket, payload) -> {
            try {
                String cmd = MAPPER.readTree(payload).path("cmd").asText();
                submittedCommands.add(cmd);
                if ("aibot_upload_media_chunk".equals(cmd) && blockFirstChunkSend.compareAndSet(true, false)) {
                    CompletableFuture<java.net.http.WebSocket> blocked = new CompletableFuture<>();
                    blockedSend.set(blocked);
                    return blocked;
                }
                return webSocket.sendText(payload, true);
            } catch (Exception ex) {
                return CompletableFuture.failedFuture(ex);
            }
        };
        client = new WecomLongConnectionClient(
                configuredProperties(),
                MAPPER,
                stateHolder,
                HttpClient.newHttpClient(),
                scheduler,
                50,
                200,
                false,
                10_000,
                2_000,
                writer);
        client.start();
        awaitState(WecomConnectionState.SUBSCRIBED);
        byte[] content = seededBytes(1_400_000); // 3 片
        Path file = writeTempFile(content, ".xlsx");
        WecomMediaUploader uploader = newUploader(30 * 60_000L, 1_500, 5, 60_000);

        try (var sender = Executors.newSingleThreadExecutor()) {
            Future<WecomUploadResult> pending =
                    sender.submit(() -> uploader.upload(file, "order.xlsx", WecomMediaType.FILE));
            // 首个分片 send 被阻塞（FrameWriter seam 挂起），发送线程无法写出任何其他帧
            awaitTrue(() -> blockedSend.get() != null);
            int commandsAtBlock = submittedCommands.size();
            // 事件驱动（替代固定睡眠）：至少一个 heartbeat 已排队（发送线程被阻塞，
            // 排队计数只增不减）后才释放阻塞的 send——该心跳必然先于「ack 超时后入队的
            // 同片重发」被写出，顺序断言因此确定。
            awaitTrue(() -> client.queuedHeartbeatFrameCount() >= 1);
            // 阻塞期间 FrameWriter 只被调用一次：心跳只排队、不写出，无并发乱写
            assertThat(submittedCommands).hasSize(commandsAtBlock);
            assertThat(submittedCommands.stream().filter("aibot_upload_media_chunk"::equals).count())
                    .isEqualTo(1);
            // 释放首个阻塞 send。同片重发要等 ack 超时后才会入队（上传线程须先拿到首个 send
            // 的提交结果），因此「重发已排队」只能在释放后观测——服务端收到重发帧即为其可观测点。
            blockedSend.get().complete(null);
            awaitTrue(() -> server.uploadFrames("aibot_upload_media_chunk").size() >= 1);
            WecomUploadResult result = pending.get(15, TimeUnit.SECONDS);

            assertThat(result.status()).isEqualTo(WecomUploadStatus.SUCCESS);
            // ping 越过排队中的业务分片（同片重发），而非越过正在发送中的分片
            int firstChunk = submittedCommands.indexOf("aibot_upload_media_chunk");
            // 只找首个 chunk 之后的第一个 ping：SUBSCRIBED 到首个 chunk 的准备时长不受限，
            // 极慢 CI 上该窗口内也可能出现正常心跳（无业务异常，不应使其成为断言前提）
            int pingOffset = submittedCommands.subList(firstChunk + 1, submittedCommands.size())
                    .indexOf("ping");
            int pingAfterFirstChunk = firstChunk + 1 + pingOffset;
            int chunkResend = firstChunk
                    + submittedCommands.subList(firstChunk + 1, submittedCommands.size())
                            .indexOf("aibot_upload_media_chunk")
                    + 1;
            assertThat(firstChunk).isPositive();
            // 该下标必须存在（subList 内找不到时 indexOf 返回 -1，换算后等于 firstChunk）
            assertThat(pingOffset).isNotNegative();
            // ping 严格位于首个 chunk 与同片重发之间
            assertThat(pingAfterFirstChunk).isGreaterThan(firstChunk).isLessThan(chunkResend);
            // 服务端对重复同片幂等：重组结果仍与源文件一致
            assertThat(server.assembledBytes(result.uploadId())).isEqualTo(content);
            assertThat(stateHolder.heartbeatCount()).isPositive();
        }
    }

    @Test
    void heartbeatAndAcksFlowWhileUploaderReadsAndEncodesChunks() throws Exception {
        startClient();
        server.chunkAckDelayMillis(300); // 拉长上传窗口，让心跳与应答在处理期间持续流动
        byte[] content = seededBytes(2_000_000); // 4 片
        Path file = writeTempFile(content, ".xlsx");
        long heartbeatBefore = stateHolder.heartbeatCount();

        try (var sender = Executors.newSingleThreadExecutor()) {
            Future<WecomUploadResult> pending =
                    sender.submit(() -> newUploader().upload(file, "order.xlsx", WecomMediaType.FILE));
            // 接收线程在处理 ack/pong 的同时，上传线程仍在读文件/算 MD5/Base64（不互相阻塞）
            awaitTrue(() -> stateHolder.heartbeatCount() > heartbeatBefore);
            WecomUploadResult result = pending.get(15, TimeUnit.SECONDS);
            assertThat(result.status()).isEqualTo(WecomUploadStatus.SUCCESS);
            assertThat(server.assembledBytes(result.uploadId())).isEqualTo(content);
        }
    }

    // ---- 日志纪律 ----

    @ExtendWith(OutputCaptureExtension.class)
    @Test
    void uploadLogsNeverContainFileContentBase64FilenameOrMediaId(CapturedOutput output) throws Exception {
        startClient();
        String marker = "SECRET-FILE-CONTENT-MARKER-" + "x".repeat(500);
        byte[] content = marker.getBytes(StandardCharsets.UTF_8);
        Path file = writeTempFile(content, ".xlsx");

        WecomUploadResult result = newUploader().upload(file, "order.xlsx", WecomMediaType.FILE);

        assertThat(result.status()).isEqualTo(WecomUploadStatus.SUCCESS);
        String logs = output.getAll();
        assertThat(logs).doesNotContain(marker);
        assertThat(logs).doesNotContain(Base64.getEncoder().encodeToString(content));
        assertThat(logs).doesNotContain(result.mediaId());
        assertThat(logs).doesNotContain("base64_data");
        assertThat(logs).doesNotContain("order.xlsx");
    }

    // ---- 测试脚手架 ----

    private void startClient() {
        client = new WecomLongConnectionClient(
                configuredProperties(),
                MAPPER,
                stateHolder,
                HttpClient.newHttpClient(),
                scheduler,
                50,
                200,
                false,
                3_000);
        client.start();
        awaitState(WecomConnectionState.SUBSCRIBED);
    }

    private WecomProperties configuredProperties() {
        WecomProperties properties = new WecomProperties();
        properties.setEnabled(true);
        properties.setBotId(BOT_ID);
        properties.setSecret(SECRET);
        properties.setWsUrl(server.wsUrl());
        properties.setHeartbeatIntervalSeconds(1);
        return properties;
    }

    private void awaitState(WecomConnectionState expected) {
        org.awaitility.Awaitility.await()
                .atMost(Duration.ofSeconds(8))
                .untilAsserted(() -> assertThat(stateHolder.state()).isEqualTo(expected));
    }

    private void awaitTrue(java.util.function.BooleanSupplier condition) {
        org.awaitility.Awaitility.await()
                .atMost(Duration.ofSeconds(8))
                .until(condition::getAsBoolean);
    }

    private WecomMediaUploader newUploader() {
        return newUploader(DEFAULT_SESSION_TTL_MILLIS, DEFAULT_ACK_TIMEOUT_MILLIS, DEFAULT_MAX_RESUME_ATTEMPTS, DEFAULT_WAIT_SUBSCRIBED_MILLIS);
    }

    private WecomMediaUploader newUploader(
            long sessionTtlMillis, long stepAckTimeoutMillis, int maxResumeAttempts, long waitForSubscribedMillis) {
        return new WecomMediaUploader(
                client, MAPPER, sessionTtlMillis, stepAckTimeoutMillis, maxResumeAttempts, waitForSubscribedMillis);
    }

    /** 注入 AckRequester 的确定性测试入口（生产路径仍走 client.awaitAck）。 */
    private WecomMediaUploader newUploader(WecomMediaUploader.AckRequester ackRequester) {
        return newUploader(
                ackRequester, DEFAULT_SESSION_TTL_MILLIS, DEFAULT_ACK_TIMEOUT_MILLIS, DEFAULT_MAX_RESUME_ATTEMPTS, DEFAULT_WAIT_SUBSCRIBED_MILLIS);
    }

    private WecomMediaUploader newUploader(
            WecomMediaUploader.AckRequester ackRequester,
            long sessionTtlMillis,
            long stepAckTimeoutMillis,
            int maxResumeAttempts,
            long waitForSubscribedMillis) {
        return new WecomMediaUploader(
                client, MAPPER, ackRequester, sessionTtlMillis, stepAckTimeoutMillis, maxResumeAttempts, waitForSubscribedMillis);
    }

    private Path writeTempFile(byte[] content, String suffix) throws IOException {
        Path file = Files.createTempFile(tempDir, "upload", suffix);
        Files.write(file, content);
        return file;
    }

    private static byte[] seededBytes(int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) ((i * 31 + 7) & 0xFF);
        }
        return bytes;
    }

    private static String md5Hex(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(content));
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }

    private static List<Integer> chunkIndexes(List<String> frames) {
        return parseAll(frames).stream()
                .map(frame -> frame.path("body").path("chunk_index").asInt(-1))
                .toList();
    }

    private static List<JsonNode> parseAll(List<String> frames) {
        return frames.stream().map(frame -> {
            try {
                return MAPPER.readTree(frame);
            } catch (Exception ex) {
                throw new AssertionError(ex);
            }
        }).toList();
    }

    private static byte[] clientReassembledContent(List<String> chunkFrames, int totalSize) {
        byte[] content = new byte[totalSize];
        int offset = 0;
        for (JsonNode chunk : parseAll(chunkFrames)) {
            byte[] decoded = Base64.getDecoder().decode(chunk.path("body").path("base64_data").asText());
            System.arraycopy(decoded, 0, content, offset, decoded.length);
            offset += decoded.length;
        }
        return content;
    }
}
