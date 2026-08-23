package cn.zimu.fulfillment.connector.wecom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 企业微信智能机器人长连接客户端（基于 JDK 内置 {@code java.net.http.WebSocket}，零新依赖）。
 *
 * <p>协议基线（官方文档 path/101463）：连接建立后发送一次 {@code aibot_subscribe}
 * （携带 bot_id / secret，errcode=0 视为成功，连接存活期内不重复）；心跳为业务 JSON 帧
 * {@code ping}（默认 30s，非 WS 控制帧）；入站看门狗在超过阈值无入站帧时判定僵死并主动重连；
 * 断线指数退避重连（1s 起步、翻倍、30s 封顶 + 抖动）；收到 {@code disconnected_event} 被踢后
 * 停止自动重连并标记 KICKED；订阅失败连续 3 次停止并标记 FAILED；应用关闭时优雅断开。
 *
 * <p>凭据纪律：secret 只出现在订阅帧体内，绝不进入日志、错误摘要或 readiness 投影。
 */
public final class WecomLongConnectionClient implements AutoCloseable, WecomOutboundTransport {

    private static final Logger log = LoggerFactory.getLogger(WecomLongConnectionClient.class);

    static final long DEFAULT_INITIAL_BACKOFF_MILLIS = 1_000L;
    static final long DEFAULT_MAX_BACKOFF_MILLIS = 30_000L;
    static final long DEFAULT_WATCHDOG_MIN_MILLIS = 60_000L;
    static final long DEFAULT_WATCHDOG_MAX_MILLIS = 75_000L;
    static final int SUBSCRIBE_FAILURE_LIMIT = 3;
    private static final long CONNECT_TIMEOUT_MILLIS = 10_000L;
    private static final long SEND_TIMEOUT_MILLIS = 3_000L;
    private static final long ACK_TIMEOUT_MILLIS = 5_000L;
    private static final int MAX_QUEUED_FRAMES = 64;
    private static final int MAX_QUEUED_HEARTBEATS = 4;
    private static final int MAX_QUEUED_CALLBACKS = 64;

    private final WecomProperties properties;
    private final ObjectMapper objectMapper;
    private final WecomConnectionStateHolder stateHolder;
    private final HttpClient httpClient;
    private final ScheduledExecutorService scheduler;
    private final long initialBackoffMillis;
    private final long maxBackoffMillis;
    private final boolean jitterEnabled;
    private final long watchdogMillis;
    private final long heartbeatMillis;
    private final long ackTimeoutMillis;
    private final FrameWriter frameWriter;
    private final WecomMediaUploader uploader;

    private final AtomicReference<WebSocket> socket = new AtomicReference<>();
    private final AtomicLong attemptCounter = new AtomicLong();
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean();
    private final AtomicInteger subscribeFailures = new AtomicInteger();
    private final AtomicInteger backoffAttempt = new AtomicInteger();
    private final AtomicInteger queuedBusinessFrames = new AtomicInteger();
    private final AtomicInteger queuedHeartbeatFrames = new AtomicInteger();
    private final AtomicLong frameSequence = new AtomicLong();
    private final ConcurrentMap<String, CompletableFuture<ReceivedAck>> pendingAcks = new ConcurrentHashMap<>();
    private final PriorityBlockingQueue<FrameSubmission> outboundFrames = new PriorityBlockingQueue<>();
    private final AtomicBoolean frameSenderRunning = new AtomicBoolean(true);
    private final Thread frameSenderThread;
    private final ThreadPoolExecutor frameHandlerExecutor;

    private volatile WecomFrameHandler frameHandler = WecomFrameHandler.EMPTY;
    private volatile boolean running;
    private volatile boolean closeHandled;
    private volatile Instant attemptStartedAt;
    private ScheduledFuture<?> heartbeatTask;
    private ScheduledFuture<?> watchdogTask;

    /** 生产入口：自建 HttpClient 与调度线程（daemon）。 */
    public WecomLongConnectionClient(
            WecomProperties properties, ObjectMapper objectMapper, WecomConnectionStateHolder stateHolder) {
        this(
                properties,
                objectMapper,
                stateHolder,
                HttpClient.newBuilder().connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MILLIS)).build(),
                Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "wecom-long-connection");
                    thread.setDaemon(true);
                    return thread;
                }),
                DEFAULT_INITIAL_BACKOFF_MILLIS,
                DEFAULT_MAX_BACKOFF_MILLIS,
                true,
                -1L,
                ACK_TIMEOUT_MILLIS,
                (socket, payload) -> socket.sendText(payload, true));
    }

    /**
     * 测试入口：注入 HttpClient / 调度器 / 退避与看门狗参数。watchdogMillisOverride 为 -1 时
     * 按心跳间隔推导（2.5 倍，夹在 60–75s）。
     */
    WecomLongConnectionClient(
            WecomProperties properties,
            ObjectMapper objectMapper,
            WecomConnectionStateHolder stateHolder,
            HttpClient httpClient,
            ScheduledExecutorService scheduler,
            long initialBackoffMillis,
            long maxBackoffMillis,
            boolean jitterEnabled,
            long watchdogMillisOverride) {
        this(
                properties,
                objectMapper,
                stateHolder,
                httpClient,
                scheduler,
                initialBackoffMillis,
                maxBackoffMillis,
                jitterEnabled,
                watchdogMillisOverride,
                ACK_TIMEOUT_MILLIS,
                (socket, payload) -> socket.sendText(payload, true));
    }

    WecomLongConnectionClient(
            WecomProperties properties,
            ObjectMapper objectMapper,
            WecomConnectionStateHolder stateHolder,
            HttpClient httpClient,
            ScheduledExecutorService scheduler,
            long initialBackoffMillis,
            long maxBackoffMillis,
            boolean jitterEnabled,
            long watchdogMillisOverride,
            long ackTimeoutMillis) {
        this(
                properties,
                objectMapper,
                stateHolder,
                httpClient,
                scheduler,
                initialBackoffMillis,
                maxBackoffMillis,
                jitterEnabled,
                watchdogMillisOverride,
                ackTimeoutMillis,
                (socket, payload) -> socket.sendText(payload, true));
    }

    WecomLongConnectionClient(
            WecomProperties properties,
            ObjectMapper objectMapper,
            WecomConnectionStateHolder stateHolder,
            HttpClient httpClient,
            ScheduledExecutorService scheduler,
            long initialBackoffMillis,
            long maxBackoffMillis,
            boolean jitterEnabled,
            long watchdogMillisOverride,
            long ackTimeoutMillis,
            FrameWriter frameWriter) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.stateHolder = stateHolder;
        this.httpClient = httpClient;
        this.scheduler = scheduler;
        this.initialBackoffMillis = Math.max(1, initialBackoffMillis);
        this.maxBackoffMillis = Math.max(this.initialBackoffMillis, maxBackoffMillis);
        this.jitterEnabled = jitterEnabled;
        this.heartbeatMillis = properties.heartbeatInterval().toMillis();
        long derivedWatchdog = Math.min(
                DEFAULT_WATCHDOG_MAX_MILLIS, Math.max(DEFAULT_WATCHDOG_MIN_MILLIS, heartbeatMillis * 5 / 2));
        // 看门狗阈值必须大于心跳间隔，保证 pong 应答能刷新入站基准；超长心跳时同步放大。
        this.watchdogMillis = watchdogMillisOverride > 0
                ? watchdogMillisOverride
                : Math.max(derivedWatchdog, heartbeatMillis * 3 / 2);
        this.ackTimeoutMillis = Math.max(1, ackTimeoutMillis);
        this.frameWriter = frameWriter;
        this.uploader = new WecomMediaUploader(this, objectMapper);
        this.frameHandlerExecutor = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(MAX_QUEUED_CALLBACKS),
                runnable -> {
                    Thread thread = new Thread(runnable, "wecom-frame-handler");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
        this.frameSenderThread = new Thread(this::frameSendLoop, "wecom-frame-sender");
        this.frameSenderThread.setDaemon(true);
        this.frameSenderThread.start();
    }

    /** 启动连接（幂等）。配置不完整时不建连，状态保持 DISCONNECTED，由 readiness 标记不可用。 */
    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        stateHolder.transitionTo(WecomConnectionState.DISCONNECTED);
        if (!properties.isConfigured()) {
            log.info("企业微信长连接未启用或配置不完整，不建立连接（请查看 readiness 诊断）");
            return;
        }
        log.info("企业微信长连接启动，心跳间隔 {}ms，看门狗阈值 {}ms", heartbeatMillis, watchdogMillis);
        watchdogTask = scheduler.scheduleWithFixedDelay(
                this::watchdogCheck, watchdogMillis / 3, watchdogMillis / 3, TimeUnit.MILLISECONDS);
        connect();
    }

    /**
     * 被动回复：透传回调 req_id 发送 {@code aibot_respond_msg}（供后续接收链路回执「已接收」）。
     *
     * @return 帧是否已提交发送；未订阅或发送失败返回 false
     */
    public boolean respond(String reqId, JsonNode body) {
        ObjectNode frame = objectMapper.createObjectNode();
        frame.put("cmd", "aibot_respond_msg");
        frame.putObject("headers").put("req_id", reqId);
        frame.set("body", body == null ? objectMapper.createObjectNode() : body);
        return awaitSubmission(enqueueRaw(frame, FramePriority.INTERACTIVE)) == FrameSendStatus.SENT;
    }

    /**
     * 模板卡片事件 5 秒快路径：透传事件帧 req_id，使用官方
     * {@code aibot_respond_update_msg} 命令，并在普通业务帧之前提交。
     */
    public WecomSendResult respondUpdate(String reqId, JsonNode body) {
        return respondUpdateUntil(reqId, body, deadlineAfterMillis(ackTimeoutMillis));
    }

    /**
     * Same update-card response with a caller-supplied absolute {@link System#nanoTime()} deadline.
     * The one deadline covers local queueing, socket submission and the platform ACK.
     */
    public WecomSendResult respondUpdateUntil(String reqId, JsonNode body, long deadlineNanos) {
        ObjectNode frame = objectMapper.createObjectNode();
        frame.put("cmd", "aibot_respond_update_msg");
        frame.putObject("headers").put("req_id", reqId);
        frame.set("body", body == null ? objectMapper.createObjectNode() : body);
        AckOutcome outcome = awaitAckUntil(frame, reqId, deadlineNanos, FramePriority.INTERACTIVE);
        return sendResult(reqId, outcome);
    }

    /** 发送任意业务帧（内部/扩展用）：req_id 由客户端自生成。 */
    public boolean sendFrame(String cmd, JsonNode body) {
        ObjectNode frame = objectMapper.createObjectNode();
        frame.put("cmd", cmd);
        frame.putObject("headers").put("req_id", newReqId());
        frame.set("body", body == null ? objectMapper.createObjectNode() : body);
        return sendRaw(frame);
    }

    @Override
    public WecomSendResult send(WecomOutboundMessage message) {
        if (!outboundReady()) {
            return WecomSendResult.failed(null, null, "CONNECTION_NOT_READY", true);
        }

        String requestId = "aibot_send_msg-" + newReqId();
        ObjectNode frame = objectMapper.createObjectNode();
        frame.put("cmd", "aibot_send_msg");
        frame.putObject("headers").put("req_id", requestId);
        ObjectNode body = frame.putObject("body");
        body.put("chatid", message.chatId());
        body.put("msgtype", message.type().protocolValue());
        // 文件消息（#84）、模板卡片（#87）与 text/markdown 使用各自官方 body 形状。
        switch (message.type()) {
            case FILE -> body.putObject("file").put("media_id", message.mediaId());
            case TEMPLATE_CARD -> body.set("template_card", message.templateCard());
            case TEXT, MARKDOWN -> body.putObject(message.type().protocolValue()).put("content", message.content());
        }

        return sendResult(requestId, awaitAck(frame, requestId, ackTimeoutMillis));
    }

    /**
     * 三步分片素材上传（委托内部 {@link WecomMediaUploader} 执行 init/chunk/finish 状态机；
     * 上传帧走本客户端的有界发送队列与心跳优先级，ack 复用 {@link #awaitAck} 关联）。
     */
    @Override
    public WecomUploadResult upload(Path file, String filename, WecomMediaType type) {
        return uploader.upload(file, filename, type);
    }

    /**
     * 当前是否可提交出站帧（运行中、已订阅且有 socket）。
     *
     * @return 可提交返回 true
     */
    boolean outboundReady() {
        return running && stateHolder.state() == WecomConnectionState.SUBSCRIBED && socket.get() != null;
    }

    /**
     * 测试观察 seam（只读，package-private）：当前排队中（已入队未写出）的心跳帧数。
     * 发送线程被单个 send 阻塞时该计数只增不减，供事件驱动测试替代固定睡眠。
     */
    int queuedHeartbeatFrameCount() {
        return (int) outboundFrames.stream()
                .filter(frame -> frame.priority() == FramePriority.HEARTBEAT)
                .count();
    }

    /** 测试观察 seam（只读）：当前排队中的业务帧数。 */
    int queuedBusinessFrameCount() {
        return (int) outboundFrames.stream()
                .filter(frame -> frame.priority() != FramePriority.HEARTBEAT)
                .count();
    }

    /** 测试观察 seam（只读）：当前已实际进入队列的交互快路径帧数。 */
    int queuedInteractiveFrameCount() {
        return (int) outboundFrames.stream()
                .filter(frame -> frame.priority() == FramePriority.INTERACTIVE)
                .count();
    }

    /** 测试观察 seam：已由 listener 接收、正在等待有序业务分发的回调数。 */
    int queuedCallbackCount() {
        return frameHandlerExecutor.getQueue().size();
    }

    /** 测试触发 seam：立即排入一次真实心跳，避免优先级测试依赖墙钟调度。 */
    void enqueueHeartbeatNowForTest() {
        sendPing();
    }

    /**
     * 通用 request/ack seam（#81 抽取）：注册 pending、入队发送、等待按 req_id 关联的应答。
     * 断线/关闭/发送超时导致的 pending 由 {@link #failPendingAcks} 确定性失败为 LOST；
     * ack 超时返回 TIMEOUT。send 与 upload 共用同一套竞态逻辑。
     */
    AckOutcome awaitAck(ObjectNode frame, String requestId, long timeoutMillis) {
        CompletableFuture<ReceivedAck> pending = new CompletableFuture<>();
        if (pendingAcks.putIfAbsent(requestId, pending) != null) {
            return AckOutcome.backpressure();
        }
        FrameSendStatus submission = awaitSubmission(enqueueRaw(frame, FramePriority.BUSINESS));
        if (submission != FrameSendStatus.SENT) {
            pendingAcks.remove(requestId, pending);
            return switch (submission) {
                case NOT_READY -> AckOutcome.notReady();
                case BACKPRESSURE -> AckOutcome.backpressure();
                case FAILED -> AckOutcome.sendFailed();
                case EXPIRED -> AckOutcome.timeout();
                case SENT -> throw new IllegalStateException("handled above");
            };
        }
        try {
            return AckOutcome.acked(pending.get(Math.max(1L, timeoutMillis), TimeUnit.MILLISECONDS));
        } catch (java.util.concurrent.TimeoutException ex) {
            return AckOutcome.timeout();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return AckOutcome.lost("ACK_WAIT_INTERRUPTED");
        } catch (java.util.concurrent.ExecutionException ex) {
            if (ex.getCause() instanceof PendingAckFailure failure) {
                return AckOutcome.lost(failure.getMessage());
            }
            return AckOutcome.lost("ACK_WAIT_FAILED");
        } catch (Exception ex) {
            return AckOutcome.lost("ACK_WAIT_FAILED");
        } finally {
            pendingAcks.remove(requestId, pending);
        }
    }

    private AckOutcome awaitAckUntil(
            ObjectNode frame, String requestId, long deadlineNanos, FramePriority priority) {
        CompletableFuture<ReceivedAck> pending = new CompletableFuture<>();
        if (pendingAcks.putIfAbsent(requestId, pending) != null) {
            return AckOutcome.backpressure();
        }
        FrameSendStatus submission = awaitSubmission(
                enqueueRaw(frame, priority, deadlineNanos), deadlineNanos);
        if (submission != FrameSendStatus.SENT) {
            pendingAcks.remove(requestId, pending);
            return switch (submission) {
                case NOT_READY -> AckOutcome.notReady();
                case BACKPRESSURE -> AckOutcome.backpressure();
                case FAILED -> AckOutcome.sendFailed();
                case EXPIRED -> AckOutcome.timeout();
                case SENT -> throw new IllegalStateException("handled above");
            };
        }
        try {
            long remainingNanos = remainingNanos(deadlineNanos);
            if (remainingNanos <= 0) {
                return AckOutcome.timeout();
            }
            ReceivedAck ack = pending.get(remainingNanos, TimeUnit.NANOSECONDS);
            return AckOutcome.acked(ack);
        } catch (java.util.concurrent.TimeoutException ex) {
            return AckOutcome.timeout();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return AckOutcome.lost("ACK_WAIT_INTERRUPTED");
        } catch (java.util.concurrent.ExecutionException ex) {
            if (ex.getCause() instanceof PendingAckFailure failure) {
                return AckOutcome.lost(failure.getMessage());
            }
            return AckOutcome.lost("ACK_WAIT_FAILED");
        } catch (Exception ex) {
            return AckOutcome.lost("ACK_WAIT_FAILED");
        } finally {
            pendingAcks.remove(requestId, pending);
        }
    }

    private static WecomSendResult sendResult(String requestId, AckOutcome outcome) {
        return switch (outcome.kind()) {
            case ACKED -> {
                int errorCode = outcome.errcode();
                if (errorCode == 0) {
                    yield WecomSendResult.success(requestId, outcome.ack().receivedAt());
                }
                if (errorCode == Integer.MIN_VALUE) {
                    // An ACK without a numeric errcode is not an explicit platform rejection; its
                    // external effect remains unknown to card-delivery fencing.
                    yield WecomSendResult.failed(requestId, null, "WECOM_ACK_INVALID", false);
                }
                String errorMessage = text(outcome.ack().frame(), "errmsg");
                yield WecomSendResult.failed(
                        requestId,
                        errorCode,
                        errorMessage == null || errorMessage.isBlank() ? "WECOM_REJECTED" : errorMessage,
                        false);
            }
            case TIMEOUT -> WecomSendResult.timeout(requestId);
            case LOST -> WecomSendResult.failed(requestId, null, outcome.reason(), false);
            case NOT_READY -> WecomSendResult.failed(null, null, "CONNECTION_NOT_READY", true);
            case BACKPRESSURE -> WecomSendResult.failed(null, null, "OUTBOUND_BACKPRESSURE", true);
            case SEND_FAILED -> WecomSendResult.failed(requestId, null, "TRANSPORT_SEND_FAILED", false);
        };
    }

    /** 注入业务帧分发钩子（接收链路实现）；可随时替换。 */
    public void setFrameHandler(WecomFrameHandler handler) {
        this.frameHandler = handler == null ? WecomFrameHandler.EMPTY : handler;
    }

    public WecomConnectionStateHolder stateHolder() {
        return stateHolder;
    }

    /** 应用关闭时优雅断开：不触发重连，也不构成服务端踢线告警。 */
    public synchronized void shutdown() {
        if (!running) {
            stopFrameSender();
            stopFrameHandlerExecutor();
            return;
        }
        running = false;
        stopFrameSender();
        stopFrameHandlerExecutor();
        failPendingAcks("CONNECTION_LOST_AFTER_SUBMIT");
        cancelHeartbeatTask();
        cancelWatchdogTask();
        scheduler.shutdownNow();
        WebSocket ws = socket.getAndSet(null);
        if (ws != null && !ws.isOutputClosed()) {
            try {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "app shutdown");
            } catch (RuntimeException ignored) {
                ws.abort();
            }
        }
        stateHolder.transitionTo(WecomConnectionState.DISCONNECTED);
        log.info("企业微信长连接已优雅关闭");
    }

    @Override
    public void close() {
        shutdown();
    }

    private synchronized void connect() {
        if (!running) {
            return;
        }
        WecomConnectionState state = stateHolder.state();
        if (state == WecomConnectionState.KICKED || state == WecomConnectionState.FAILED) {
            return;
        }
        long id = attemptCounter.incrementAndGet();
        closeHandled = false;
        reconnectScheduled.set(false);
        attemptStartedAt = Instant.now();
        stateHolder.transitionTo(WecomConnectionState.CONNECTING);
        URI uri;
        try {
            uri = URI.create(properties.getWsUrl().trim());
        } catch (RuntimeException ex) {
            stateHolder.recordError("连接失败: 无效的 WS 地址");
            log.warn("企业微信长连接 WS 地址无效，等待重试");
            scheduleReconnect();
            return;
        }
        log.info("企业微信长连接建立中 (attempt {})", backoffAttempt.get() + 1);
        httpClient
                .newWebSocketBuilder()
                .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MILLIS))
                .buildAsync(uri, new FrameListener(id))
                .whenComplete((ws, error) -> {
                    if (id != attemptCounter.get()) {
                        // 已被更新的连接尝试取代
                        if (ws != null) {
                            ws.abort();
                        }
                        return;
                    }
                    if (error != null) {
                        if (!running) {
                            return;
                        }
                        stateHolder.recordError("连接失败: " + rootCauseSimpleName(error));
                        log.warn("企业微信长连接建立失败: {}", rootCauseSimpleName(error));
                        scheduleReconnect();
                        return;
                    }
                    if (!running) {
                        ws.abort();
                        return;
                    }
                    socket.set(ws);
                    sendSubscribe(ws);
                });
    }

    private void sendSubscribe(WebSocket ws) {
        ObjectNode frame = objectMapper.createObjectNode();
        frame.put("cmd", "aibot_subscribe");
        frame.putObject("headers").put("req_id", newReqId());
        ObjectNode body = frame.putObject("body");
        body.put("bot_id", properties.getBotId());
        body.put("secret", properties.getSecret());
        // 订阅帧含 secret，绝不打印帧内容；只以 req_id 关联日志。
        log.info("企业微信长连接发送订阅帧 req_id={}", requestId(frame));
        ws.sendText(frame.toString(), true)
                .exceptionally(ex -> {
                    log.warn("企业微信长连接订阅帧发送失败: {}", rootCauseSimpleName(ex));
                    return null;
                });
    }

    private void handleSubscribeResponse(JsonNode frame) {
        int errcode = errcode(frame);
        if (errcode == 0) {
            subscribeFailures.set(0);
            backoffAttempt.set(0);
            stateHolder.transitionTo(WecomConnectionState.SUBSCRIBED);
            stateHolder.resetHeartbeatCount();
            stateHolder.recordError(null);
            log.info("企业微信长连接订阅成功");
            startHeartbeatTask();
            return;
        }
        int failures = subscribeFailures.incrementAndGet();
        stateHolder.recordError("订阅失败: errcode=" + errcode);
        log.warn(
                "企业微信长连接订阅失败: errcode={}（第 {}/{} 次）",
                errcode,
                failures,
                SUBSCRIBE_FAILURE_LIMIT);
        if (failures >= SUBSCRIBE_FAILURE_LIMIT) {
            closeHandled = true;
            cancelHeartbeatTask();
            stateHolder.recordError("订阅连续失败 " + failures + " 次，已停止重试");
            stateHolder.transitionTo(WecomConnectionState.FAILED);
            WebSocket ws = socket.getAndSet(null);
            if (ws != null) {
                ws.abort();
            }
            log.error("企业微信长连接订阅连续失败 {} 次，已停止重试，请检查凭据配置", failures);
        } else {
            closeAndReconnect();
        }
    }

    private void handleKicked(WebSocket ws) {
        closeHandled = true;
        cancelHeartbeatTask();
        stateHolder.recordError("被新连接抢占（disconnected_event），停止自动重连");
        stateHolder.transitionTo(WecomConnectionState.KICKED);
        failPendingAcks("CONNECTION_LOST_AFTER_SUBMIT");
        log.error("企业微信长连接被新连接抢占，已停止自动重连，需人工介入");
        ws.abort();
    }

    private void handleText(WebSocket ws, String text, long receivedNanos) {
        stateHolder.recordInbound();
        JsonNode frame;
        try {
            frame = objectMapper.readTree(text);
        } catch (Exception ex) {
            log.warn("企业微信长连接收到非 JSON 帧，忽略");
            return;
        }
        String requestId = requestId(frame);
        CompletableFuture<ReceivedAck> pending = pendingAcks.get(requestId);
        if (pending != null && (text(frame, "cmd") == null || text(frame, "cmd").isBlank())) {
            pending.complete(new ReceivedAck(frame, Instant.now()));
            return;
        }

        String cmd = text(frame, "cmd");
        if (cmd == null || cmd.isBlank()) {
            // 企微响应帧（订阅应答 / 心跳 pong 应答）不带 cmd，只含 errcode/headers：按当前状态路由。
            if (stateHolder.state() == WecomConnectionState.CONNECTING) {
                handleSubscribeResponse(frame);
            }
            // SUBSCRIBED 后的无 cmd 帧视为心跳应答，入站时间已在 recordInbound 刷新。
            return;
        }
        switch (cmd) {
            case "aibot_subscribe" -> handleSubscribeResponse(frame);
            case "pong" -> {
                // 入站已刷新看门狗；心跳应答无需额外处理
            }
            case "aibot_msg_callback" -> {
                stateHolder.recordEvent("aibot_msg_callback");
                dispatchToHandler("aibot_msg_callback", frame, receivedNanos);
            }
            case "aibot_event_callback" -> {
                String eventType = eventType(frame);
                stateHolder.recordEvent(eventType == null ? "aibot_event_callback" : eventType);
                dispatchToHandler("aibot_event_callback", frame, receivedNanos);
                if ("disconnected_event".equals(eventType)) {
                    handleKicked(ws);
                }
            }
            default -> log.debug("企业微信长连接收到未知帧类型: {}", cmd);
        }
    }

    private void dispatchToHandler(String cmd, JsonNode frame, long receivedNanos) {
        try {
            frameHandlerExecutor.execute(() -> {
                try {
                    frameHandler.onFrame(cmd, frame, receivedNanos);
                } catch (Exception ex) {
                    // 业务分发异常不得拖垮连接
                    log.warn("企业微信长连接帧分发异常: cmd={}", cmd, ex);
                }
            });
        } catch (RejectedExecutionException ex) {
            // 绝不在 WebSocket listener 线程回退执行业务逻辑。运行期队列饱和时主动断线，
            // 让平台按未完成回调重投；应用关闭期则不再重连。
            log.warn("企业微信长连接业务回调队列已满或已关闭，中止连接等待重投: cmd={}", cmd);
            if (running) {
                stateHolder.recordError("业务回调积压，主动重连等待通道重投");
                closeAndReconnect();
            }
        }
    }

    private void stopFrameHandlerExecutor() {
        frameHandlerExecutor.shutdownNow();
    }

    private void watchdogCheck() {
        if (!running) {
            return;
        }
        WecomConnectionState state = stateHolder.state();
        if (state == WecomConnectionState.SUBSCRIBED) {
            Instant lastInbound = stateHolder.lastInboundAt();
            if (lastInbound != null && elapsedMillis(lastInbound) > watchdogMillis) {
                log.warn("企业微信长连接超过 {}ms 无入站帧，判定僵死，主动重连", watchdogMillis);
                stateHolder.recordError("连接僵死（无入站帧），主动重连");
                closeAndReconnect();
            }
        } else if (state == WecomConnectionState.CONNECTING) {
            Instant started = attemptStartedAt;
            if (started != null && elapsedMillis(started) > watchdogMillis) {
                log.warn("企业微信长连接连接/订阅超过 {}ms 无响应，主动重连", watchdogMillis);
                stateHolder.recordError("连接超时（无订阅响应），主动重连");
                closeAndReconnect();
            }
        }
    }

    private void startHeartbeatTask() {
        cancelHeartbeatTask();
        heartbeatTask = scheduler.scheduleWithFixedDelay(
                this::sendPing, heartbeatMillis, heartbeatMillis, TimeUnit.MILLISECONDS);
    }

    private void sendPing() {
        if (!running || stateHolder.state() != WecomConnectionState.SUBSCRIBED) {
            return;
        }
        ObjectNode frame = objectMapper.createObjectNode();
        frame.put("cmd", "ping");
        frame.putObject("headers").put("req_id", newReqId());
        frame.set("body", objectMapper.createObjectNode());
        enqueueRaw(frame, FramePriority.HEARTBEAT).thenAccept(status -> {
            if (status == FrameSendStatus.SENT) {
                stateHolder.recordHeartbeat();
            }
        });
    }

    private void cancelHeartbeatTask() {
        ScheduledFuture<?> task = heartbeatTask;
        heartbeatTask = null;
        if (task != null) {
            task.cancel(false);
        }
    }

    private void cancelWatchdogTask() {
        ScheduledFuture<?> task = watchdogTask;
        watchdogTask = null;
        if (task != null) {
            task.cancel(false);
        }
    }

    /** 主动关闭当前连接并进入退避重连；被踢/封顶路径不经过这里。 */
    private void closeAndReconnect() {
        closeHandled = true;
        cancelHeartbeatTask();
        WebSocket ws = socket.getAndSet(null);
        stateHolder.transitionTo(WecomConnectionState.DISCONNECTED);
        failPendingAcks("CONNECTION_LOST_AFTER_SUBMIT");
        if (ws != null && !ws.isOutputClosed()) {
            ws.abort();
        }
        scheduleReconnect();
    }

    private void scheduleReconnect() {
        if (!running) {
            return;
        }
        WecomConnectionState state = stateHolder.state();
        if (state == WecomConnectionState.KICKED || state == WecomConnectionState.FAILED) {
            return;
        }
        if (!reconnectScheduled.compareAndSet(false, true)) {
            return;
        }
        long delay = nextBackoffMillis();
        log.info("企业微信长连接将于 {}ms 后重连", delay);
        scheduler.schedule(this::connect, delay, TimeUnit.MILLISECONDS);
    }

    /** 指数退避（1s 起步、翻倍、封顶）+ 可选全抖动。attempt 在订阅成功后清零。 */
    private long nextBackoffMillis() {
        int steps = Math.min(backoffAttempt.getAndIncrement(), 20);
        long exponential = initialBackoffMillis;
        for (int i = 0; i < steps && exponential < maxBackoffMillis; i++) {
            exponential = Math.min(maxBackoffMillis, exponential * 2);
        }
        if (!jitterEnabled) {
            return exponential;
        }
        return ThreadLocalRandom.current().nextLong(exponential + 1);
    }

    private boolean sendRaw(ObjectNode frame) {
        return awaitSubmission(enqueueRaw(frame)) == FrameSendStatus.SENT;
    }

    /**
     * 业务帧进入有界队列；心跳使用独立保留容量并具有更高优先级，避免业务背压饿死连接保活。
     */
    private CompletableFuture<FrameSendStatus> enqueueRaw(ObjectNode frame) {
        return enqueueRaw(frame, FramePriority.BUSINESS);
    }

    private CompletableFuture<FrameSendStatus> enqueueRaw(ObjectNode frame, FramePriority priority) {
        return enqueueRaw(frame, priority, Long.MAX_VALUE);
    }

    private CompletableFuture<FrameSendStatus> enqueueRaw(
            ObjectNode frame, FramePriority priority, long deadlineNanos) {
        WebSocket ws = socket.get();
        if (!running || ws == null || stateHolder.state() != WecomConnectionState.SUBSCRIBED) {
            return CompletableFuture.completedFuture(FrameSendStatus.NOT_READY);
        }
        if (deadlineNanos != Long.MAX_VALUE && remainingNanos(deadlineNanos) <= 0) {
            return CompletableFuture.completedFuture(FrameSendStatus.EXPIRED);
        }
        AtomicInteger counter = priority == FramePriority.HEARTBEAT
                ? queuedHeartbeatFrames
                : queuedBusinessFrames;
        int limit = priority == FramePriority.HEARTBEAT ? MAX_QUEUED_HEARTBEATS : MAX_QUEUED_FRAMES;
        if (counter.incrementAndGet() > limit) {
            counter.decrementAndGet();
            return CompletableFuture.completedFuture(FrameSendStatus.BACKPRESSURE);
        }

        FrameSubmission submission = new FrameSubmission(
                priority,
                frameSequence.incrementAndGet(),
                ws,
                frame.toString(),
                deadlineNanos,
                new CompletableFuture<>());
        if (!outboundFrames.offer(submission)) {
            counter.decrementAndGet();
            return CompletableFuture.completedFuture(FrameSendStatus.BACKPRESSURE);
        }
        return submission.result();
    }

    private void frameSendLoop() {
        while (frameSenderRunning.get()) {
            try {
                FrameSubmission submission = outboundFrames.take();
                decrementQueueCount(submission.priority());
                if (submission.expired()) {
                    submission.result().complete(FrameSendStatus.EXPIRED);
                } else {
                    submission.result().complete(submitToSocket(
                            submission.expectedSocket(), submission.payload(), submission.deadlineNanos()));
                }
            } catch (InterruptedException ex) {
                if (!frameSenderRunning.get()) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private FrameSendStatus submitToSocket(WebSocket expectedSocket, String payload, long deadlineNanos) {
        if (!running
                || socket.get() != expectedSocket
                || stateHolder.state() != WecomConnectionState.SUBSCRIBED) {
            return FrameSendStatus.NOT_READY;
        }
        long remainingNanos = deadlineNanos == Long.MAX_VALUE
                ? TimeUnit.MILLISECONDS.toNanos(SEND_TIMEOUT_MILLIS)
                : Math.min(
                        TimeUnit.MILLISECONDS.toNanos(SEND_TIMEOUT_MILLIS),
                        remainingNanos(deadlineNanos));
        if (remainingNanos <= 0) {
            return FrameSendStatus.EXPIRED;
        }
        try {
            frameWriter.send(expectedSocket, payload).get(remainingNanos, TimeUnit.NANOSECONDS);
            return FrameSendStatus.SENT;
        } catch (java.util.concurrent.TimeoutException ex) {
            boolean deadlineExpired = deadlineNanos != Long.MAX_VALUE && remainingNanos(deadlineNanos) <= 0;
            log.warn(
                    deadlineExpired
                            ? "企业微信长连接发送帧超过调用截止时间，中止当前连接"
                            : "企业微信长连接发送帧超时，中止当前连接以恢复发送队列");
            recoverFromSendTimeout(expectedSocket);
            return deadlineExpired ? FrameSendStatus.EXPIRED : FrameSendStatus.FAILED;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return FrameSendStatus.FAILED;
        } catch (Exception ex) {
            log.warn("企业微信长连接发送帧失败: {}", rootCauseSimpleName(ex));
            return FrameSendStatus.FAILED;
        }
    }

    private void stopFrameSender() {
        if (!frameSenderRunning.compareAndSet(true, false)) {
            return;
        }
        frameSenderThread.interrupt();
        FrameSubmission submission;
        while ((submission = outboundFrames.poll()) != null) {
            decrementQueueCount(submission.priority());
            submission.result().complete(FrameSendStatus.NOT_READY);
        }
    }

    private void recoverFromSendTimeout(WebSocket timedOutSocket) {
        if (!socket.compareAndSet(timedOutSocket, null)) {
            timedOutSocket.abort();
            return;
        }
        closeHandled = true;
        cancelHeartbeatTask();
        stateHolder.transitionTo(WecomConnectionState.DISCONNECTED);
        stateHolder.recordError("发送帧超时，主动重连");
        failPendingAcks("CONNECTION_LOST_AFTER_SUBMIT");
        timedOutSocket.abort();
        scheduleReconnect();
    }

    private void decrementQueueCount(FramePriority priority) {
        (priority == FramePriority.HEARTBEAT ? queuedHeartbeatFrames : queuedBusinessFrames)
                .decrementAndGet();
    }

    private static FrameSendStatus awaitSubmission(CompletableFuture<FrameSendStatus> submission) {
        try {
            return submission.get();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return FrameSendStatus.FAILED;
        } catch (java.util.concurrent.ExecutionException ex) {
            return FrameSendStatus.FAILED;
        }
    }

    private static FrameSendStatus awaitSubmission(
            CompletableFuture<FrameSendStatus> submission, long deadlineNanos) {
        long remainingNanos = remainingNanos(deadlineNanos);
        if (remainingNanos <= 0) {
            return FrameSendStatus.EXPIRED;
        }
        try {
            return submission.get(remainingNanos, TimeUnit.NANOSECONDS);
        } catch (java.util.concurrent.TimeoutException ex) {
            return FrameSendStatus.EXPIRED;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return FrameSendStatus.FAILED;
        } catch (java.util.concurrent.ExecutionException ex) {
            return FrameSendStatus.FAILED;
        }
    }

    private void failPendingAcks(String reason) {
        pendingAcks.forEach((requestId, pending) -> {
            if (pendingAcks.remove(requestId, pending)) {
                pending.completeExceptionally(new PendingAckFailure(reason));
            }
        });
    }

    private static String newReqId() {
        return UUID.randomUUID().toString();
    }

    /** errcode 判定：优先 body.errcode，其次顶层 errcode；缺失视为失败（fail closed）。 */
    static int errcode(JsonNode frame) {
        JsonNode body = frame.path("body");
        JsonNode value = body.isObject() && !body.path("errcode").isMissingNode()
                ? body.path("errcode")
                : frame.path("errcode");
        return value.isMissingNode() || value.isNull() || !value.isNumber()
                ? Integer.MIN_VALUE
                : value.asInt(Integer.MIN_VALUE);
    }

    /** 事件类型判定：兼容 body.event_type / body.event / body.type 三种形状。 */
    private static String eventType(JsonNode frame) {
        JsonNode body = frame.path("body");
        String type = text(body, "event_type");
        if (type == null) {
            type = text(body, "event");
        }
        if (type == null) {
            type = text(body, "type");
        }
        return type;
    }

    static String text(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() || !value.isValueNode() ? null : value.asText();
    }

    /** 官方帧使用 headers.req_id；兼容早期测试/历史帧的顶层 req_id。 */
    private static String requestId(JsonNode frame) {
        String requestId = text(frame.path("headers"), "req_id");
        return requestId == null ? text(frame, "req_id") : requestId;
    }

    /** 异常根因类名（稳定、不泄密）。 */
    private static String rootCauseSimpleName(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getClass().getSimpleName();
    }

    private static long elapsedMillis(Instant since) {
        return Duration.between(since, Instant.now()).toMillis();
    }

    static long deadlineAfterMillis(long timeoutMillis) {
        return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(1L, timeoutMillis));
    }

    private static long remainingNanos(long deadlineNanos) {
        return deadlineNanos - System.nanoTime();
    }

    private static final class PendingAckFailure extends RuntimeException {

        PendingAckFailure(String message) {
            super(message);
        }
    }

    record ReceivedAck(JsonNode frame, Instant receivedAt) {}

    /**
     * request/ack 结局：ACKED 携带按 req_id 关联的应答；TIMEOUT 为 ack 超时（发送已提交，
     * 结局未知）；LOST 为断线/关闭导致的确定性失败（pending 被 {@link #failPendingAcks} 完成）；
     * NOT_READY/BACKPRESSURE 表示帧未进入发送队列（可安全重试）；SEND_FAILED 表示 socket 发送失败。
     */
    record AckOutcome(Kind kind, ReceivedAck ack, String reason) {

        enum Kind {
            ACKED,
            TIMEOUT,
            LOST,
            NOT_READY,
            BACKPRESSURE,
            SEND_FAILED
        }

        AckOutcome {
            if ((kind == Kind.ACKED) != (ack != null)) {
                throw new IllegalArgumentException("ACKED requires an ack and only ACKED carries one");
            }
        }

        static AckOutcome acked(ReceivedAck ack) {
            return new AckOutcome(Kind.ACKED, ack, null);
        }

        static AckOutcome timeout() {
            return new AckOutcome(Kind.TIMEOUT, null, null);
        }

        static AckOutcome lost(String reason) {
            return new AckOutcome(Kind.LOST, null, reason);
        }

        static AckOutcome notReady() {
            return new AckOutcome(Kind.NOT_READY, null, null);
        }

        static AckOutcome backpressure() {
            return new AckOutcome(Kind.BACKPRESSURE, null, null);
        }

        static AckOutcome sendFailed() {
            return new AckOutcome(Kind.SEND_FAILED, null, null);
        }

        /** ACKED 应答的 errcode（fail closed：缺失视为失败）。 */
        int errcode() {
            return WecomLongConnectionClient.errcode(ack.frame());
        }

        /** ACKED 应答 body 字段文本。 */
        String bodyText(String field) {
            return WecomLongConnectionClient.text(ack.frame().path("body"), field);
        }
    }

    @FunctionalInterface
    interface FrameWriter {
        CompletableFuture<WebSocket> send(WebSocket socket, String payload);
    }

    private record FrameSubmission(
            FramePriority priority,
            long sequence,
            WebSocket expectedSocket,
            String payload,
            long deadlineNanos,
            CompletableFuture<FrameSendStatus> result)
            implements Comparable<FrameSubmission> {

        boolean expired() {
            return deadlineNanos != Long.MAX_VALUE && remainingNanos(deadlineNanos) <= 0;
        }

        @Override
        public int compareTo(FrameSubmission other) {
            int byPriority = Integer.compare(priority.order, other.priority.order);
            return byPriority != 0 ? byPriority : Long.compare(sequence, other.sequence);
        }
    }

    private enum FramePriority {
        HEARTBEAT(0),
        INTERACTIVE(1),
        BUSINESS(2);

        private final int order;

        FramePriority(int order) {
            this.order = order;
        }
    }

    private enum FrameSendStatus {
        SENT,
        NOT_READY,
        BACKPRESSURE,
        EXPIRED,
        FAILED
    }

    /** 单连接监听器：以代际 id（每次 connect 递增）判定过期，防止旧连接事件干扰新连接。 */
    private final class FrameListener implements WebSocket.Listener {

        private final long id;
        private final StringBuilder textFragments = new StringBuilder();

        FrameListener(long id) {
            this.id = id;
        }

        private boolean isStale() {
            return id != attemptCounter.get();
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            if (isStale()) {
                return null;
            }
            textFragments.append(data);
            if (last) {
                String completeMessage = textFragments.toString();
                textFragments.setLength(0);
                handleText(ws, completeMessage, System.nanoTime());
            }
            // demand 按 onText 回调计数；分片消息也必须逐片续订，否则永远收不到 last=true。
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            if (isStale() || closeHandled || !running) {
                return null;
            }
            socket.compareAndSet(ws, null);
            stateHolder.transitionTo(WecomConnectionState.DISCONNECTED);
            failPendingAcks("CONNECTION_LOST_AFTER_SUBMIT");
            cancelHeartbeatTask();
            log.info("企业微信长连接关闭: code={}", statusCode);
            scheduleReconnect();
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            if (isStale() || closeHandled || !running) {
                return;
            }
            socket.compareAndSet(ws, null);
            stateHolder.transitionTo(WecomConnectionState.DISCONNECTED);
            failPendingAcks("CONNECTION_LOST_AFTER_SUBMIT");
            cancelHeartbeatTask();
            stateHolder.recordError("连接异常: " + rootCauseSimpleName(error));
            log.warn("企业微信长连接异常: {}", rootCauseSimpleName(error));
            scheduleReconnect();
        }
    }
}
