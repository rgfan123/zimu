package cn.zimu.fulfillment.connector.wecom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 企业微信长连接临时素材三步分片上传（官方文档 path/101463，2026-08-21 核对）。
 *
 * <p>协议：init 获取 upload_id（30 分钟会话）→ 0 起逐片 chunk（单片 ≤ 512KiB、≤ 100 片、
 * 重复同片幂等、可乱序）→ finish 合并并返回 media_id（3 天有效）。每一步都通过
 * {@link AckRequester} seam 按 req_id 等待应答（生产唯一实现是
 * {@link WecomLongConnectionClient#awaitAck}），errcode 非 0、ack 超时或响应字段缺失
 * 一律 fail closed。
 *
 * <p>断线结局：断线重连后在同一 upload_id 会话内，未获 ack 的当前分片以相同 chunk_index
 * 重发（服务端幂等）后继续。NOT_READY（帧未入队、未提交：连接在提交瞬间断线）同样安全——
 * 预算内等待重连 SUBSCRIBED 后重做该步：INIT 以新 req_id 重做（旧帧无任何协议状态）、
 * CHUNK 以相同 upload_id + chunk_index 重发、FINISH 安全重提交一次（该次 finish 帧未提交，
 * 不存在重复提交风险）。每次 NOT_READY 恢复消耗同一 {@link ResumeBudget}，且 CHUNK/FINISH
 * 等待后须复查 30 分钟会话期限，不得越过截止时间发送。BACKPRESSURE 同样帧未入队，保持
 * 快速失败（OUTBOUND_BACKPRESSURE，retryable=true），不消耗预算。
 *
 * <p>全部等待/重试有界（{@code maxResumeAttempts} + 30 分钟会话期限）。超期、预算耗尽或
 * 重连等待超时 → FAILED 且 retryable=true（可安全从头重试，原 upload_id 成为 30 分钟后
 * 自动清理的孤儿会话）。
 *
 * <p>finish 提交后未获 ack（TIMEOUT/LOST/SEND_FAILED），或应答 errcode=0 但证据矛盾
 * （缺 media_id/type/created_at、body.type 与请求类型不一致、created_at 非正的 Unix 秒）
 * 时结局未知——服务端可能已生成 media_id——返回 {@link WecomUploadStatus#UNKNOWN}，
 * 禁止盲目重发 finish 或标记可重试，必须人工对账。
 *
 * <p>内存纪律：MD5 与分片均流式读取（≤ 512KiB 缓冲），从不整文件载入内存；文件内容、base64
 * 与 media_id 绝不写入日志或审计。
 */
final class WecomMediaUploader {

    private static final Logger log = LoggerFactory.getLogger(WecomMediaUploader.class);

    /** 单片上限（Base64 编码前）。 */
    static final int CHUNK_SIZE = 512 * 1024;
    /** 协议分片上限。 */
    static final int MAX_CHUNKS = 100;
    /** 官方 total_size 下限。 */
    static final long MIN_TOTAL_SIZE = 5;
    /** 官方 filename / req_id 上限（UTF-8 字节）。 */
    static final long MAX_FILENAME_BYTES = 256;
    /** 上传会话有效期（官方 30 分钟）。 */
    static final long DEFAULT_SESSION_TTL_MILLIS = 30 * 60 * 1000L;
    /**
     * 单步 ack 等待上限（默认 15s）。官方 SDK 的 5s 超时在真实多片上传负载下会把 6–8s 才
     * 到达的分片 ACK 误判超时，导致已受理分片被重试/拒绝（WeComTeam/aibot-node-sdk#27，
     * 官方验证的安全默认 15s）。仅影响上传 init/chunk/finish 的 ack 等待；普通出站消息
     * 发送 ACK 仍由 {@link WecomLongConnectionClient} 使用 5s 语义，二者互不影响。
     */
    static final long DEFAULT_STEP_ACK_TIMEOUT_MILLIS = 15_000;
    /** 断线恢复/未获 ack 重发的有界预算（次）。 */
    static final int DEFAULT_MAX_RESUME_ATTEMPTS = 5;
    /** 每次等待重连 SUBSCRIBED 的有界等待上限。 */
    static final long DEFAULT_WAIT_FOR_SUBSCRIBED_MILLIS = 60_000;
    private static final long STATE_POLL_MILLIS = 50;

    private final WecomLongConnectionClient client;
    private final ObjectMapper objectMapper;
    private final AckRequester ackRequester;
    private final long sessionTtlMillis;
    private final long stepAckTimeoutMillis;
    private final int maxResumeAttempts;
    private final long waitForSubscribedMillis;

    WecomMediaUploader(WecomLongConnectionClient client, ObjectMapper objectMapper) {
        this(
                client,
                objectMapper,
                client::awaitAck,
                DEFAULT_SESSION_TTL_MILLIS,
                DEFAULT_STEP_ACK_TIMEOUT_MILLIS,
                DEFAULT_MAX_RESUME_ATTEMPTS,
                DEFAULT_WAIT_FOR_SUBSCRIBED_MILLIS);
    }

    WecomMediaUploader(
            WecomLongConnectionClient client,
            ObjectMapper objectMapper,
            long sessionTtlMillis,
            long stepAckTimeoutMillis,
            int maxResumeAttempts,
            long waitForSubscribedMillis) {
        this(
                client,
                objectMapper,
                client::awaitAck,
                sessionTtlMillis,
                stepAckTimeoutMillis,
                maxResumeAttempts,
                waitForSubscribedMillis);
    }

    /**
     * 测试入口：可注入 request/ack 请求方（默认 {@code client::awaitAck}），用于确定性模拟
     * NOT_READY 等「帧未入队」结局，不复制任何协议逻辑。
     */
    WecomMediaUploader(
            WecomLongConnectionClient client,
            ObjectMapper objectMapper,
            AckRequester ackRequester,
            long sessionTtlMillis,
            long stepAckTimeoutMillis,
            int maxResumeAttempts,
            long waitForSubscribedMillis) {
        this.client = client;
        this.objectMapper = objectMapper;
        this.ackRequester = ackRequester;
        this.sessionTtlMillis = Math.max(1, sessionTtlMillis);
        this.stepAckTimeoutMillis = Math.max(1, stepAckTimeoutMillis);
        this.maxResumeAttempts = Math.max(0, maxResumeAttempts);
        this.waitForSubscribedMillis = Math.max(1, waitForSubscribedMillis);
    }

    /** 测试观察 seam：默认构造实际安装的单步 ACK 等待上限（毫秒）。 */
    long stepAckTimeoutMillis() {
        return stepAckTimeoutMillis;
    }

    /**
     * 上传本地文件为企微临时素材。
     *
     * @throws WecomUploadValidationException 前置校验失败（不创建 upload_id，中文可读）
     */
    WecomUploadResult upload(Path file, String filename, WecomMediaType type) {
        long totalSize = validate(file, filename, type);
        if (!client.outboundReady()) {
            return WecomUploadResult.failed(null, "INIT", "CONNECTION_NOT_READY", true, null, null);
        }
        return uploadFlow(file, filename, type, totalSize);
    }

    private WecomUploadResult uploadFlow(Path file, String filename, WecomMediaType type, long totalSize) {
        int totalChunks = chunkCount(totalSize);
        String fileMd5;
        try {
            fileMd5 = md5Hex(file);
        } catch (IOException ex) {
            log.warn("企微素材上传读取文件失败（MD5 阶段）: {}", ex.getClass().getSimpleName());
            return WecomUploadResult.failed(null, "INIT", "UPLOAD_FILE_READ_FAILED", true, null, null);
        }
        ResumeBudget budget = new ResumeBudget();

        // ---- INIT：持有 upload_id 与 30 分钟会话期限；未获 ack 视为未开始，预算内重发 init ----
        String uploadId = null;
        Instant sessionDeadline = null;
        String initRequestId = null;
        while (uploadId == null) {
            WecomUploadResult readyFailure = awaitReadyOrFail(budget, "INIT", null);
            if (readyFailure != null) {
                return readyFailure;
            }
            initRequestId = requestId("aibot_upload_media_init");
            ObjectNode frame = objectMapper.createObjectNode();
            frame.put("cmd", "aibot_upload_media_init");
            frame.putObject("headers").put("req_id", initRequestId);
            ObjectNode body = frame.putObject("body");
            body.put("type", type.protocolValue());
            body.put("filename", filename);
            body.put("total_size", totalSize);
            body.put("total_chunks", totalChunks);
            body.put("md5", fileMd5);

            WecomLongConnectionClient.AckOutcome outcome =
                    ackRequester.requestAck(frame, initRequestId, stepAckTimeoutMillis);
            switch (outcome.kind()) {
                case ACKED -> {
                    int errorCode = outcome.errcode();
                    if (errorCode != 0) {
                        log.warn("企微素材上传 init 被拒绝: errcode={} req_id={}", errorCode, initRequestId);
                        return WecomUploadResult.failed(
                                errorCode, "INIT", "UPLOAD_INIT_REJECTED", true, null, initRequestId);
                    }
                    String id = outcome.bodyText("upload_id");
                    if (id == null || id.isBlank()) {
                        log.warn("企微素材上传 init 应答缺少 upload_id，fail closed: req_id={}", initRequestId);
                        return WecomUploadResult.failed(
                                null, "INIT", "INIT_MISSING_UPLOAD_ID", true, null, initRequestId);
                    }
                    uploadId = id;
                    sessionDeadline = outcome.ack().receivedAt().plusMillis(sessionTtlMillis);
                    log.info("企微素材上传已初始化: upload_id={} type={} 分片数={}", uploadId, type.protocolValue(), totalChunks);
                }
                case NOT_READY -> {
                    // 帧未入队、未提交（连接在提交瞬间断线）：预算内回到循环顶部等待 SUBSCRIBED
                    // 后以新 req_id 重做 init（旧帧没有任何协议状态，孤儿会话风险为零）
                    if (budget.exhausted()) {
                        return WecomUploadResult.failed(
                                null, "INIT", "UPLOAD_RETRY_BUDGET_EXHAUSTED", true, null, initRequestId);
                    }
                    budget.consume();
                    log.debug(
                            "企微素材上传 init 提交瞬间连接不可用（NOT_READY），等待后重做: req_id={}",
                            initRequestId);
                }
                case BACKPRESSURE -> {
                    // 帧未入队（发送队列满）：保持快速失败，不消耗预算；retryable=true 表示可安全从头重试
                    return WecomUploadResult.failed(
                            null, "INIT", "OUTBOUND_BACKPRESSURE", true, null, initRequestId);
                }
                case TIMEOUT, LOST, SEND_FAILED -> {
                    if (budget.exhausted()) {
                        return WecomUploadResult.failed(
                                null, "INIT", finalFailureCode(outcome), true, null, initRequestId);
                    }
                    budget.consume();
                    if (outcome.kind() != WecomLongConnectionClient.AckOutcome.Kind.TIMEOUT
                            && !waitForSubscribed()) {
                        return WecomUploadResult.failed(
                                null, "INIT", "UPLOAD_RECONNECT_TIMEOUT", true, null, initRequestId);
                    }
                    // 预算内重发 init：即使上次 init 已生效也只是孤儿会话，30 分钟后自动清理
                }
            }
        }

        // ---- CHUNK：0 起逐片；未获 ack 的当前片以相同 chunk_index 幂等重发 ----
        for (int index = 0; index < totalChunks; ) {
            WecomUploadResult deadlineFailure = sessionDeadlineFailure("CHUNK", uploadId, sessionDeadline);
            if (deadlineFailure != null) {
                return deadlineFailure;
            }
            WecomUploadResult readyFailure = awaitReadyOrFail(budget, "CHUNK", uploadId);
            if (readyFailure != null) {
                return readyFailure;
            }
            // 等待 SUBSCRIBED 后复查会话期限：不得越过截止时间发送
            deadlineFailure = sessionDeadlineFailure("CHUNK", uploadId, sessionDeadline);
            if (deadlineFailure != null) {
                return deadlineFailure;
            }
            String chunkRequestId = requestId("aibot_upload_media_chunk");
            WecomLongConnectionClient.AckOutcome outcome;
            try {
                outcome = sendChunk(uploadId, index, file, totalSize, chunkRequestId);
            } catch (UploadFileReadFailure failure) {
                return WecomUploadResult.failed(
                        null, "CHUNK", "UPLOAD_FILE_READ_FAILED", true, failure.uploadId(), failure.requestId());
            }
            switch (outcome.kind()) {
                case ACKED -> {
                    int errorCode = outcome.errcode();
                    if (errorCode != 0) {
                        log.warn(
                                "企微素材上传分片被拒绝: errcode={} upload_id={} chunk_index={} req_id={}",
                                errorCode,
                                uploadId,
                                index,
                                chunkRequestId);
                        return WecomUploadResult.failed(
                                errorCode, "CHUNK", "UPLOAD_CHUNK_REJECTED", true, uploadId, chunkRequestId);
                    }
                    log.debug("企微素材上传分片已确认: upload_id={} chunk_index={} req_id={}", uploadId, index, chunkRequestId);
                    index++;
                }
                case NOT_READY -> {
                    // 帧未入队、未提交：预算内回到循环顶部（复查会话期限、等待 SUBSCRIBED）后
                    // 以相同 upload_id + chunk_index 重发（index 未前进，服务端幂等）
                    if (budget.exhausted()) {
                        return WecomUploadResult.failed(
                                null, "CHUNK", "UPLOAD_RETRY_BUDGET_EXHAUSTED", true, uploadId, chunkRequestId);
                    }
                    budget.consume();
                    log.debug(
                            "企微素材上传分片提交瞬间连接不可用（NOT_READY），等待后重发: upload_id={} chunk_index={}",
                            uploadId,
                            index);
                }
                case BACKPRESSURE -> {
                    // 帧未入队（发送队列满）：保持快速失败，不消耗预算；retryable=true 表示可安全从头重试
                    return WecomUploadResult.failed(
                            null, "CHUNK", "OUTBOUND_BACKPRESSURE", true, uploadId, chunkRequestId);
                }
                case TIMEOUT, LOST, SEND_FAILED -> {
                    if (budget.exhausted()) {
                        return WecomUploadResult.failed(
                                null, "CHUNK", finalFailureCode(outcome), true, uploadId, chunkRequestId);
                    }
                    budget.consume();
                    if (outcome.kind() != WecomLongConnectionClient.AckOutcome.Kind.TIMEOUT
                            && !waitForSubscribed()) {
                        return WecomUploadResult.failed(
                                null, "CHUNK", "UPLOAD_RECONNECT_TIMEOUT", true, uploadId, chunkRequestId);
                    }
                    log.debug(
                            "企微素材上传分片未获 ack，以相同 chunk_index 重发: upload_id={} chunk_index={}",
                            uploadId,
                            index);
                    // 循环回到同一 index：服务端幂等，重复同片自动忽略
                }
            }
        }

        // ---- FINISH：未提交（NOT_READY）可安全重试；提交后未获 ack 结局未知，禁止盲重发 ----
        while (true) {
            WecomUploadResult deadlineFailure = sessionDeadlineFailure("FINISH", uploadId, sessionDeadline);
            if (deadlineFailure != null) {
                return deadlineFailure;
            }
            WecomUploadResult readyFailure = awaitReadyOrFail(budget, "FINISH", uploadId);
            if (readyFailure != null) {
                return readyFailure;
            }
            // 等待 SUBSCRIBED 后复查会话期限：不得越过截止时间提交 finish
            deadlineFailure = sessionDeadlineFailure("FINISH", uploadId, sessionDeadline);
            if (deadlineFailure != null) {
                return deadlineFailure;
            }
            String finishRequestId = requestId("aibot_upload_media_finish");
            ObjectNode finishFrame = objectMapper.createObjectNode();
            finishFrame.put("cmd", "aibot_upload_media_finish");
            finishFrame.putObject("headers").put("req_id", finishRequestId);
            finishFrame.putObject("body").put("upload_id", uploadId);

            WecomLongConnectionClient.AckOutcome outcome =
                    ackRequester.requestAck(finishFrame, finishRequestId, stepAckTimeoutMillis);
            switch (outcome.kind()) {
                case ACKED -> {
                    int errorCode = outcome.errcode();
                    if (errorCode != 0) {
                        log.warn("企微素材上传 finish 被拒绝: errcode={} upload_id={} req_id={}", errorCode, uploadId, finishRequestId);
                        return WecomUploadResult.failed(
                                errorCode, "FINISH", "UPLOAD_FINISH_REJECTED", true, uploadId, finishRequestId);
                    }
                    String mediaId = outcome.bodyText("media_id");
                    if (mediaId == null || mediaId.isBlank()) {
                        // errcode=0 但缺 media_id：服务端很可能已生成媒体但不可引用，与 finish 未获 ack 同属未知态
                        log.warn("企微素材上传 finish 应答缺少 media_id，结局未知需对账: upload_id={} req_id={}", uploadId, finishRequestId);
                        return WecomUploadResult.unknown(uploadId, finishRequestId, "FINISH_MISSING_MEDIA_ID");
                    }
                    String mediaType = outcome.bodyText("type");
                    if (mediaType == null) {
                        log.warn("企微素材上传 finish 应答缺少 type，结局未知需对账: upload_id={} req_id={}", uploadId, finishRequestId);
                        return WecomUploadResult.unknown(uploadId, finishRequestId, "FINISH_RESPONSE_INVALID");
                    }
                    if (!type.protocolValue().equals(mediaType)) {
                        // errcode=0 但应答类型与请求类型矛盾：finish 已成功而证据不一致，
                        // 不能当作 SUCCESS（可能已生成素材），人工对账、不可重试
                        log.warn(
                                "企微素材上传 finish 应答 type 与请求不一致: 请求={} 应答={} upload_id={} req_id={}",
                                type.protocolValue(),
                                mediaType,
                                uploadId,
                                finishRequestId);
                        return WecomUploadResult.unknown(uploadId, finishRequestId, "FINISH_MEDIA_TYPE_MISMATCH");
                    }
                    Long createdAtSeconds = longValue(outcome.ack().frame().path("body"), "created_at");
                    if (createdAtSeconds == null || createdAtSeconds <= 0) {
                        // created_at 必须是正的 Unix 秒；0/负数/不可转换都是证据矛盾，不能构造假成功
                        log.warn("企微素材上传 finish 应答 created_at 非法，结局未知需对账: upload_id={} req_id={}", uploadId, finishRequestId);
                        return WecomUploadResult.unknown(uploadId, finishRequestId, "FINISH_RESPONSE_INVALID");
                    }
                    Instant createdAt;
                    try {
                        createdAt = Instant.ofEpochSecond(createdAtSeconds);
                    } catch (java.time.DateTimeException ex) {
                        log.warn("企微素材上传 finish 应答 created_at 不可转换，结局未知需对账: upload_id={} req_id={}", uploadId, finishRequestId);
                        return WecomUploadResult.unknown(uploadId, finishRequestId, "FINISH_RESPONSE_INVALID");
                    }
                    log.info("企微素材上传完成（media_id 不落日志）: upload_id={} req_id={}", uploadId, finishRequestId);
                    return WecomUploadResult.success(
                            mediaId,
                            mediaType,
                            createdAt,
                            uploadId,
                            finishRequestId,
                            outcome.ack().receivedAt());
                }
                case NOT_READY -> {
                    // 该次 finish 帧未入队、未提交：预算内等待 SUBSCRIBED 后安全重提交一次
                    // （服务端从未收到过该帧，不存在重复提交风险）
                    if (budget.exhausted()) {
                        return WecomUploadResult.failed(
                                null, "FINISH", "UPLOAD_RETRY_BUDGET_EXHAUSTED", true, uploadId, finishRequestId);
                    }
                    budget.consume();
                    log.debug("企微素材上传 finish 提交瞬间连接不可用（NOT_READY），等待后重试: upload_id={}", uploadId);
                }
                case BACKPRESSURE -> {
                    // 帧未入队（发送队列满）：保持快速失败，不消耗预算；retryable=true 表示可安全从头重试
                    return WecomUploadResult.failed(
                            null, "FINISH", "OUTBOUND_BACKPRESSURE", true, uploadId, finishRequestId);
                }
                case TIMEOUT, LOST, SEND_FAILED -> {
                    // finish 已提交未获 ack：服务端可能已生成 media_id，禁止盲目重发 finish、禁止标记可重试
                    log.warn(
                            "企微素材上传 finish 结局未知，需人工对账: upload_id={} req_id={} kind={}",
                            uploadId,
                            finishRequestId,
                            outcome.kind());
                    return WecomUploadResult.unknown(uploadId, finishRequestId, "FINISH_ACK_UNKNOWN");
                }
                default -> throw new IllegalStateException("unhandled ack outcome: " + outcome.kind());
            }
        }
    }

    private WecomLongConnectionClient.AckOutcome sendChunk(
            String uploadId, int index, Path file, long totalSize, String requestId) {
        byte[] chunk;
        try {
            chunk = readChunk(file, index, totalSize);
        } catch (IOException ex) {
            log.warn(
                    "企微素材上传读取文件失败（分片阶段）: upload_id={} chunk_index={}",
                    uploadId,
                    index);
            throw new UploadFileReadFailure(uploadId, requestId);
        }
        ObjectNode frame = objectMapper.createObjectNode();
        frame.put("cmd", "aibot_upload_media_chunk");
        frame.putObject("headers").put("req_id", requestId);
        ObjectNode body = frame.putObject("body");
        body.put("upload_id", uploadId);
        body.put("chunk_index", index);
        body.put("base64_data", Base64.getEncoder().encodeToString(chunk));
        return ackRequester.requestAck(frame, requestId, stepAckTimeoutMillis);
    }

    /** 有界重试预算：每次「未获 ack 重发 / 断线恢复 / NOT_READY 恢复」消耗 1；耗尽即失败（可从头重试）。 */
    private final class ResumeBudget {

        private int remaining = maxResumeAttempts;

        boolean exhausted() {
            return remaining <= 0;
        }

        void consume() {
            remaining--;
        }
    }

    /**
     * 连接未就绪（断线恢复/重连窗口）时按预算等待 SUBSCRIBED；就绪返回 null，
     * 预算耗尽或等待超时返回明确失败（可安全从头重试）。INIT/CHUNK/FINISH 的
     * NOT_READY（帧未入队、未提交）分支在消耗预算后回到各自的循环顶部，经本方法
     * 等待重连 SUBSCRIBED 后再重做未提交的那一步；BACKPRESSURE 保持快速失败，不经过这里。
     */
    private WecomUploadResult awaitReadyOrFail(ResumeBudget budget, String step, String uploadId) {
        if (client.outboundReady()) {
            return null;
        }
        if (budget.exhausted()) {
            return WecomUploadResult.failed(
                    null, step, "UPLOAD_RETRY_BUDGET_EXHAUSTED", true, uploadId, null);
        }
        if (!waitForSubscribed()) {
            return WecomUploadResult.failed(
                    null, step, "UPLOAD_RECONNECT_TIMEOUT", true, uploadId, null);
        }
        return null;
    }

    private boolean waitForSubscribed() {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitForSubscribedMillis);
        while (System.nanoTime() < deadline) {
            if (client.outboundReady()) {
                return true;
            }
            try {
                Thread.sleep(STATE_POLL_MILLIS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /**
     * 会话期限（30 分钟，自 init ack 起）复查：超期返回明确失败，未超期返回 null。
     * 每次等待 SUBSCRIBED 之后都必须复查——等待可能越过截止时间，不得在超期后发送/提交。
     */
    private WecomUploadResult sessionDeadlineFailure(String step, String uploadId, Instant sessionDeadline) {
        if (!Instant.now().isAfter(sessionDeadline)) {
            return null;
        }
        log.warn("企微素材上传会话超期（30 分钟）: upload_id={} step={}", uploadId, step);
        return WecomUploadResult.failed(null, step, "UPLOAD_SESSION_EXPIRED", true, uploadId, null);
    }

    /** 前置校验：全部通过才返回文件大小；任一失败抛中文可读异常且不创建 upload_id。 */
    private static long validate(Path file, String filename, WecomMediaType type) {
        if (type == null) {
            throw new WecomUploadValidationException("UPLOAD_TYPE_REQUIRED", "上传类型不能为空");
        }
        if (filename == null || filename.isBlank()) {
            throw new WecomUploadValidationException("UPLOAD_FILENAME_REQUIRED", "文件名不能为空");
        }
        if (filename.getBytes(StandardCharsets.UTF_8).length > MAX_FILENAME_BYTES) {
            throw new WecomUploadValidationException(
                    "UPLOAD_FILENAME_TOO_LONG",
                    "文件名过长：UTF-8 编码后超过 " + MAX_FILENAME_BYTES + " 字节");
        }
        if (file == null) {
            throw new WecomUploadValidationException("UPLOAD_FILE_REQUIRED", "上传文件不能为空");
        }
        if (!Files.exists(file)) {
            // 绝对 Path 绝不出现在异常消息里（防御 path 泄露：消息可能被上层落库/告警）
            throw new WecomUploadValidationException("UPLOAD_FILE_NOT_FOUND", "上传文件不存在");
        }
        if (!Files.isRegularFile(file)) {
            throw new WecomUploadValidationException("UPLOAD_FILE_NOT_REGULAR", "上传路径不是普通文件");
        }
        if (!Files.isReadable(file)) {
            throw new WecomUploadValidationException("UPLOAD_FILE_NOT_READABLE", "上传文件不可读");
        }
        long size;
        try {
            size = Files.size(file);
        } catch (IOException ex) {
            throw new WecomUploadValidationException("UPLOAD_FILE_SIZE_UNREADABLE", "无法读取文件大小");
        }
        if (size < MIN_TOTAL_SIZE) {
            throw new WecomUploadValidationException(
                    "UPLOAD_FILE_TOO_SMALL", "文件大小不能小于 " + MIN_TOTAL_SIZE + " 字节");
        }
        if (size > type.maxSizeBytes()) {
            throw new WecomUploadValidationException(
                    "UPLOAD_FILE_TOO_LARGE",
                    "文件大小 " + size + " 字节超过 " + type.protocolValue() + " 类型上限 "
                            + type.maxSizeBytes() + " 字节");
        }
        String extension = extensionOf(filename);
        if (extension == null) {
            throw new WecomUploadValidationException(
                    "UPLOAD_EXTENSION_MISSING", "无法从文件名判断扩展名: " + filename);
        }
        if (!type.allowedExtensions().contains(extension)) {
            if (type.allowedExtensions().isEmpty()) {
                throw new WecomUploadValidationException(
                        "UPLOAD_TYPE_UNSUPPORTED",
                        type.protocolValue() + " 类型一期暂不支持上传（本票范围：file/image）");
            }
            throw new WecomUploadValidationException(
                    "UPLOAD_EXTENSION_NOT_ALLOWED",
                    "扩展名 ." + extension + " 不是 " + type.protocolValue() + " 类型支持的扩展名（仅支持 "
                            + String.join("/", type.allowedExtensions()) + "）");
        }
        int totalChunks = chunkCount(size);
        if (totalChunks > MAX_CHUNKS) {
            throw new WecomUploadValidationException(
                    "UPLOAD_TOO_MANY_CHUNKS", "分片数 " + totalChunks + " 超过协议上限 " + MAX_CHUNKS + "（单文件过大）");
        }
        return size;
    }

    private static String extensionOf(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return null;
        }
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static int chunkCount(long totalSize) {
        return (int) ((totalSize + CHUNK_SIZE - 1) / CHUNK_SIZE);
    }

    /** 流式 MD5：分片缓冲，不整文件载入内存。 */
    private static String md5Hex(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("MD5");
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException("MD5 unavailable", ex);
        }
        byte[] buffer = new byte[CHUNK_SIZE];
        try (InputStream in = Files.newInputStream(file)) {
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** 流式读取单个分片（≤ 512KiB，FileChannel 定位 O(1)）；文件中途变小视为失败。 */
    private static byte[] readChunk(Path file, int index, long totalSize) throws IOException {
        long offset = (long) index * CHUNK_SIZE;
        int length = (int) Math.min(CHUNK_SIZE, totalSize - offset);
        byte[] buffer = new byte[length];
        try (java.nio.channels.FileChannel channel =
                java.nio.channels.FileChannel.open(file, java.nio.file.StandardOpenOption.READ)) {
            channel.position(offset);
            java.nio.ByteBuffer byteBuffer = java.nio.ByteBuffer.wrap(buffer);
            while (byteBuffer.hasRemaining()) {
                int read = channel.read(byteBuffer);
                if (read < 0) {
                    throw new IOException("file truncated during upload (changed on disk)");
                }
            }
        }
        return buffer;
    }

    /** 分片读取失败（文件中途被改/删）：携带 upload_id 与 req_id 供稳定错误元数据。 */
    private static final class UploadFileReadFailure extends RuntimeException {

        private final String uploadId;
        private final String requestId;

        private UploadFileReadFailure(String uploadId, String requestId) {
            this.uploadId = uploadId;
            this.requestId = requestId;
        }

        private String uploadId() {
            return uploadId;
        }

        private String requestId() {
            return requestId;
        }
    }

    /** 稳定结局码：预算耗尽时按最后一次失败的形态区分。 */
    private static String finalFailureCode(WecomLongConnectionClient.AckOutcome outcome) {
        return switch (outcome.kind()) {
            case TIMEOUT -> "UPLOAD_ACK_TIMEOUT";
            case LOST -> "UPLOAD_RETRY_BUDGET_EXHAUSTED";
            case SEND_FAILED -> "UPLOAD_SEND_FAILED";
            default -> "UPLOAD_FAILED";
        };
    }

    private static Long longValue(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        JsonNode value = node.get(field);
        return value != null && value.canConvertToLong() ? value.asLong() : null;
    }

    private static String requestId(String cmd) {
        return cmd + "-" + UUID.randomUUID();
    }

    /**
     * request/ack 请求方 seam（测试可注入确定性结局，如 NOT_READY）；生产唯一实现是
     * {@link WecomLongConnectionClient#awaitAck}，不复制任何协议逻辑。
     */
    @FunctionalInterface
    interface AckRequester {
        WecomLongConnectionClient.AckOutcome requestAck(ObjectNode frame, String requestId, long timeoutMillis);
    }
}
