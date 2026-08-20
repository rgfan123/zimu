package cn.zimu.fulfillment.connector.wecom;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 长连接客户端帧级测试：手写 RFC6455 echo 服务器（无 testcontainers / 外部网络）。
 * 覆盖：订阅帧与成功判定、业务 ping 心跳、respond 回执透传 req_id、帧分发钩子、
 * 断线重连退避、被踢停止重连、订阅失败 3 次封顶、僵死看门狗、优雅关闭、readiness 状态流转与非密投影。
 */
class WecomLongConnectionClientTest {

    private static final Logger log = LoggerFactory.getLogger(WecomLongConnectionClientTest.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BOT_ID = "bot-123";
    private static final String SECRET = "secret-abc";

    private Rfc6455TestServer server;
    private WecomConnectionStateHolder stateHolder;
    private ScheduledExecutorService scheduler;
    private WecomLongConnectionClient client;
    private final List<JsonNode> dispatchedFrames = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        server = new Rfc6455TestServer();
        stateHolder = new WecomConnectionStateHolder();
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "wecom-test-scheduler");
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

    /** 默认：订阅成功、自动 pong、1s 心跳、50ms→200ms 退避、无抖动、看门狗 3s（必须大于心跳）。 */
    private void startClient() {
        startClient(1, 3_000, 50, 200, false);
    }

    private void startClient(
            long heartbeatSeconds, long watchdogMillis, long initialBackoffMillis, long maxBackoffMillis, boolean jitter) {
        WecomProperties properties = new WecomProperties();
        properties.setEnabled(true);
        properties.setBotId(BOT_ID);
        properties.setSecret(SECRET);
        properties.setWsUrl(server.wsUrl());
        properties.setHeartbeatIntervalSeconds(heartbeatSeconds);
        client = new WecomLongConnectionClient(
                properties,
                MAPPER,
                stateHolder,
                HttpClient.newHttpClient(),
                scheduler,
                initialBackoffMillis,
                maxBackoffMillis,
                jitter,
                watchdogMillis);
        client.setFrameHandler((cmd, frame) -> {
            dispatchedFrames.add(frame.deepCopy());
            log.info("test handler received cmd={} req_id={}", cmd, frame.path("req_id").asText());
        });
        client.start();
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

    // ---- 订阅与心跳 ----

    @Test
    void subscribeFrameIsSentOncePerConnectionAndCarriesCredentials() throws Exception {
        startClient();
        awaitState(WecomConnectionState.SUBSCRIBED);

        String subscribe = server.awaitFrame("aibot_subscribe", 2_000);
        assertThat(subscribe).isNotNull();
        assertThat(subscribe).contains("\"bot_id\":\"" + BOT_ID + "\"").contains("\"secret\":\"" + SECRET + "\"");
        JsonNode subscribeFrame = MAPPER.readTree(subscribe);
        assertThat(subscribeFrame.path("headers").path("req_id").asText()).isNotBlank();
        assertThat(subscribeFrame.has("req_id")).isFalse();

        // 心跳持续期间不重复订阅（连接存活期内只发一次）
        awaitTrue(() -> stateHolder.heartbeatCount() >= 2);
        long subscribeCount = server.textFrames().stream()
                .filter(frame -> frame.contains("\"cmd\":\"aibot_subscribe\""))
                .count();
        assertThat(subscribeCount).isEqualTo(1);
        assertThat(stateHolder.lastError()).isNull();
    }

    @Test
    void heartbeatIsBusinessJsonPingFrameAtConfiguredInterval() throws Exception {
        startClient();
        awaitState(WecomConnectionState.SUBSCRIBED);

        awaitTrue(() -> stateHolder.heartbeatCount() >= 2);
        List<String> pings = server.textFrames().stream()
                .filter(frame -> frame.contains("\"cmd\":\"ping\""))
                .toList();
        assertThat(pings.size()).isGreaterThanOrEqualTo(2);
        for (String ping : pings) {
            JsonNode frame = MAPPER.readTree(ping);
            assertThat(frame.path("cmd").asText()).isEqualTo("ping");
            assertThat(frame.path("headers").path("req_id").asText()).isNotBlank();
            assertThat(frame.has("req_id")).isFalse();
            assertThat(frame.path("body").isMissingNode() || frame.path("body").isObject()).isTrue();
        }
    }

    @Test
    void respondEchoesCallbackReqIdForReceiptChain() throws Exception {
        startClient();
        awaitState(WecomConnectionState.SUBSCRIBED);

        boolean sent = client.respond("req-001", MAPPER.createObjectNode().put("msgtype", "text"));
        assertThat(sent).isTrue();
        awaitTrue(() -> server.textFrames().stream().anyMatch(f -> f.contains("\"req_id\":\"req-001\"")));

        JsonNode frame = server.textFrames().stream()
                .map(f -> {
                    try {
                        return MAPPER.readTree(f);
                    } catch (Exception ex) {
                        return null;
                    }
                })
                .filter(f -> f != null && "aibot_respond_msg".equals(f.path("cmd").asText()))
                .findFirst()
                .orElseThrow();
        assertThat(frame.path("headers").path("req_id").asText()).isEqualTo("req-001");
        assertThat(frame.has("req_id")).isFalse();
        assertThat(frame.path("body").path("msgtype").asText()).isEqualTo("text");
    }

    @Test
    void messageAndEventCallbacksAreDispatchedToFrameHandler() throws Exception {
        startClient();
        awaitState(WecomConnectionState.SUBSCRIBED);

        server.sendText(
                "{\"cmd\":\"aibot_msg_callback\",\"headers\":{\"req_id\":\"cb-1\"},\"body\":{\"msgtype\":\"text\"}}");
        server.sendText(
                "{\"cmd\":\"aibot_event_callback\",\"req_id\":\"ev-1\",\"body\":{\"event_type\":\"enter_chat\"}}");

        awaitTrue(() -> dispatchedFrames.size() >= 2);
        assertThat(dispatchedFrames.get(0).path("cmd").asText()).isEqualTo("aibot_msg_callback");
        assertThat(dispatchedFrames.get(0).path("headers").path("req_id").asText()).isEqualTo("cb-1");
        assertThat(dispatchedFrames.get(1).path("cmd").asText()).isEqualTo("aibot_event_callback");
        assertThat(dispatchedFrames.get(1).path("req_id").asText()).isEqualTo("ev-1");
        assertThat(stateHolder.lastEventType()).isEqualTo("enter_chat");
        assertThat(stateHolder.lastEventTime()).isNotNull();
    }

    @Test
    void pendingBusinessAckDoesNotBlockHeartbeat() throws Exception {
        startClient();
        awaitState(WecomConnectionState.SUBSCRIBED);
        server.autoSendMessageAck(false);
        long heartbeatBefore = stateHolder.heartbeatCount();

        try (var sender = Executors.newSingleThreadExecutor()) {
            Future<WecomSendResult> pending =
                    sender.submit(() -> client.send(WecomOutboundMessage.markdown("group-001", "**待确认**")));
            JsonNode frame = MAPPER.readTree(server.awaitFrame("aibot_send_msg", 2_000));

            org.awaitility.Awaitility.await()
                    .atMost(Duration.ofSeconds(2))
                    .untilAsserted(() -> assertThat(stateHolder.heartbeatCount()).isGreaterThan(heartbeatBefore));

            server.sendAck(frame.path("headers").path("req_id").asText(), 0);
            assertThat(pending.get(2, TimeUnit.SECONDS).status()).isEqualTo(WecomSendStatus.SUCCESS);
        }
    }

    @Test
    void heartbeatJumpsAheadOfBusinessFramesQueuedBehindSocketBackpressure() throws Exception {
        WecomProperties properties = configuredProperties();
        List<String> submittedCommands = new CopyOnWriteArrayList<>();
        AtomicBoolean blockFirstBusinessFrame = new AtomicBoolean(true);
        AtomicReference<CompletableFuture<java.net.http.WebSocket>> blockedSend = new AtomicReference<>();
        AtomicReference<java.net.http.WebSocket> blockedSocket = new AtomicReference<>();
        WecomLongConnectionClient.FrameWriter writer = (webSocket, payload) -> {
            try {
                String cmd = MAPPER.readTree(payload).path("cmd").asText();
                submittedCommands.add(cmd);
                if ("aibot_send_msg".equals(cmd) && blockFirstBusinessFrame.compareAndSet(true, false)) {
                    CompletableFuture<java.net.http.WebSocket> blocked = new CompletableFuture<>();
                    blockedSend.set(blocked);
                    blockedSocket.set(webSocket);
                    return blocked;
                }
                return webSocket.sendText(payload, true);
            } catch (Exception ex) {
                return CompletableFuture.failedFuture(ex);
            }
        };
        client = new WecomLongConnectionClient(
                properties,
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

        try (var senders = Executors.newFixedThreadPool(2)) {
            Future<WecomSendResult> first =
                    senders.submit(() -> client.send(WecomOutboundMessage.text("user-a", "消息 A")));
            awaitTrue(() -> blockedSend.get() != null);
            Future<WecomSendResult> second =
                    senders.submit(() -> client.send(WecomOutboundMessage.text("user-b", "消息 B")));

            Thread.sleep(1_200);
            blockedSend.get().complete(blockedSocket.get());

            awaitTrue(() -> submittedCommands.stream().filter("aibot_send_msg"::equals).count() >= 2
                    && submittedCommands.contains("ping"));
            int firstPing = submittedCommands.indexOf("ping");
            int secondBusiness = submittedCommands.lastIndexOf("aibot_send_msg");
            assertThat(firstPing).isPositive().isLessThan(secondBusiness);
            assertThat(first.get(4, TimeUnit.SECONDS).status()).isEqualTo(WecomSendStatus.TIMEOUT);
            assertThat(second.get(4, TimeUnit.SECONDS).status()).isEqualTo(WecomSendStatus.SUCCESS);
        }
    }

    @Test
    void sendFutureTimeoutReconnectsBeforeNextBusinessMessage() {
        WecomProperties properties = configuredProperties();
        AtomicBoolean timeOutFirstBusinessFrame = new AtomicBoolean(true);
        WecomLongConnectionClient.FrameWriter writer = (webSocket, payload) -> {
            try {
                String cmd = MAPPER.readTree(payload).path("cmd").asText();
                if ("aibot_send_msg".equals(cmd) && timeOutFirstBusinessFrame.compareAndSet(true, false)) {
                    return new CompletableFuture<>();
                }
                return webSocket.sendText(payload, true);
            } catch (Exception ex) {
                return CompletableFuture.failedFuture(ex);
            }
        };
        client = new WecomLongConnectionClient(
                properties,
                MAPPER,
                stateHolder,
                HttpClient.newHttpClient(),
                scheduler,
                50,
                200,
                false,
                5_000,
                500,
                writer);
        client.start();
        awaitState(WecomConnectionState.SUBSCRIBED);

        WecomSendResult timedOutSend = client.send(WecomOutboundMessage.text("user-a", "消息 A"));
        assertThat(timedOutSend.status()).isEqualTo(WecomSendStatus.FAILED);
        assertThat(timedOutSend.errorMessage()).isEqualTo("TRANSPORT_SEND_FAILED");
        assertThat(timedOutSend.retryable()).isFalse();

        awaitTrue(() -> server.connectionCount() >= 2);
        awaitState(WecomConnectionState.SUBSCRIBED);
        WecomSendResult recoveredSend = client.send(WecomOutboundMessage.text("user-b", "消息 B"));
        assertThat(recoveredSend.status()).isEqualTo(WecomSendStatus.SUCCESS);
    }

    // ---- 断线重连 ----

    @Test
    void abruptCloseTriggersBackoffReconnectAndResubscribes() {
        startClient();
        awaitState(WecomConnectionState.SUBSCRIBED);

        try {
            server.abortConnection();
        } catch (Exception ex) {
            throw new AssertionError("abort failed", ex);
        }
        awaitTrue(() -> server.connectionCount() >= 2);
        awaitState(WecomConnectionState.SUBSCRIBED);

        long subscribeCount = server.textFrames().stream()
                .filter(frame -> frame.contains("\"cmd\":\"aibot_subscribe\""))
                .count();
        assertThat(subscribeCount).isEqualTo(2);
    }

    @Test
    void staleConnectionIsClosedByWatchdogAndReconnected() {
        server.autoPong(false); // 订阅应答后服务器保持静默 → 入站看门狗判定僵死
        startClient(1, 500, 50, 200, false); // 看门狗 500ms：无 pong 场景下远小于心跳，僵死判定快速生效
        awaitState(WecomConnectionState.SUBSCRIBED);

        // 本测试中服务器从不主动断开，唯一会关闭连接的机制就是入站看门狗：
        // 连接数持续增长证明僵死判定在反复触发（订阅成功后错误摘要被清空属预期行为）。
        awaitTrue(() -> server.connectionCount() >= 3);
        awaitState(WecomConnectionState.SUBSCRIBED);
    }

    // ---- 被踢与订阅封顶 ----

    @Test
    void disconnectedEventStopsReconnectionAndMarksKicked() throws Exception {
        startClient();
        awaitState(WecomConnectionState.SUBSCRIBED);

        server.sendText(
                "{\"cmd\":\"aibot_event_callback\",\"req_id\":\"kick-1\",\"body\":{\"event_type\":\"disconnected_event\"}}");

        awaitState(WecomConnectionState.KICKED);
        assertThat(stateHolder.lastError()).contains("被新连接抢占");

        // 等待一个超过退避上限的窗口，确认不再发起新连接
        Thread.sleep(600);
        assertThat(server.connectionCount()).isEqualTo(1);
        assertThat(stateHolder.state()).isEqualTo(WecomConnectionState.KICKED);
        assertThat(stateHolder.lastEventType()).isEqualTo("disconnected_event");
    }

    @Test
    void subscribeRejectionStopsAfterThreeAttemptsAndMarksFailed() {
        server.subscribeErrcode(40001);
        startClient();

        awaitState(WecomConnectionState.FAILED);
        assertThat(server.connectionCount()).isEqualTo(3);
        assertThat(stateHolder.lastError()).contains("订阅连续失败 3 次");

        // 确认封顶后不再重试
        try {
            Thread.sleep(600);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        assertThat(server.connectionCount()).isEqualTo(3);
    }

    @Test
    void readinessProjectionNeverLeaksSecretOrBotId() throws Exception {
        server.subscribeErrcode(40001);
        startClient();
        awaitState(WecomConnectionState.FAILED);

        WecomProperties properties = configuredProperties();
        WecomReadinessService service = new WecomReadinessService(properties, stateHolder);
        WecomConnectionReadiness readiness = service.inspect();
        assertThat(readiness.configurationReady()).isTrue();
        assertThat(readiness.connectionState()).isEqualTo("FAILED");
        assertThat(readiness.subscribed()).isFalse();

        String json = MAPPER.writeValueAsString(readiness);
        assertThat(json).doesNotContain(SECRET).doesNotContain(BOT_ID);
        // 错误摘要同样非密
        assertThat(readiness.lastError()).doesNotContain(SECRET).doesNotContain(BOT_ID);
    }

    @Test
    void gracefulShutdownClosesWithoutReconnect() throws Exception {
        startClient();
        awaitState(WecomConnectionState.SUBSCRIBED);

        client.shutdown();
        assertThat(stateHolder.state()).isEqualTo(WecomConnectionState.DISCONNECTED);

        try {
            Thread.sleep(400);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        assertThat(server.connectionCount()).isEqualTo(1);
        // 关闭后不再有心跳
        long pingsBeforeWait = server.textFrames().stream()
                .filter(f -> f.contains("\"cmd\":\"ping\""))
                .count();
        Thread.sleep(1_100);
        assertThat(server.textFrames().stream()
                        .filter(f -> f.contains("\"cmd\":\"ping\""))
                        .count())
                .isEqualTo(pingsBeforeWait);
    }

    // ---- 未配置 ----

    @Test
    void missingConfigurationStaysDisconnectedWithoutConnecting() {
        WecomProperties properties = new WecomProperties(); // enabled=false、无凭据
        client = new WecomLongConnectionClient(
                properties,
                MAPPER,
                stateHolder,
                HttpClient.newHttpClient(),
                scheduler,
                50,
                200,
                false,
                500);
        client.start();

        assertThat(stateHolder.state()).isEqualTo(WecomConnectionState.DISCONNECTED);

        WecomReadinessService service = new WecomReadinessService(properties, stateHolder);
        WecomConnectionReadiness readiness = service.inspect();
        assertThat(readiness.configurationReady()).isFalse();
        assertThat(readiness.connectionState()).isEqualTo("DISCONNECTED");
        assertThat(readiness.checks())
                .containsEntry("connection_enabled", false)
                .containsEntry("bot_id_configured", false)
                .containsEntry("secret_configured", false);
        assertThat(readiness.missingRequirements())
                .containsExactlyInAnyOrder("CONNECTION_ENABLED", "BOT_ID", "SECRET");
        assertThat(readiness.missingRequirements()).doesNotContain("WS_URL"); // 默认 wsUrl 已配置
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
}
