package cn.zimu.fulfillment.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.zimu.fulfillment.message.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * wecom-message-intake 07 核心验收：图文消息经解释任务下载解密后，受控媒体引用进入
 * {@code InterpretationInput.mediaContentRefs}；下载失败走 3 次重试并终态 NEED_REVIEW。
 * 纯文字消息保持空媒体引用（回归）。
 */
@Testcontainers
@SpringBootTest
class InterpretationMediaIntegrationTest {

    private static final String AES_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY";
    private static final String IMAGE_CONTENT_TYPE = "image/png";
    private static final Path MEDIA_DIR = createMediaDir();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void mediaConfiguration(DynamicPropertyRegistry registry) {
        registry.add("app.message-worker.enabled", () -> "false");
        registry.add("app.media.dir", () -> MEDIA_DIR.toString());
    }

    @Autowired private MessageSubmissionService submissionService;
    @Autowired private InterpretationService interpretationService;
    @Autowired private AsyncTaskStore taskStore;
    @Autowired private JdbcTemplate jdbc;

    @MockitoBean private MessageInterpreter interpreter;

    private HttpServer server;
    private String baseUrl;
    private final AtomicInteger downloadHits = new AtomicInteger();
    private long messageCounter;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("DELETE FROM app.review_cases");
        jdbc.update("DELETE FROM app.message_interpretations");
        jdbc.update("DELETE FROM app.message_media");
        jdbc.update("DELETE FROM app.message_submissions");
        jdbc.update("DELETE FROM app.channel_messages");
        jdbc.update("DELETE FROM app.async_tasks");
        cleanMediaDir();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/media/ok",
                exchange -> {
                    downloadHits.incrementAndGet();
                    byte[] body = encrypt("原文图片内容-ok".getBytes(StandardCharsets.UTF_8));
                    respond(exchange, 200, IMAGE_CONTENT_TYPE, body);
                });
        server.createContext(
                "/media/missing",
                exchange -> {
                    downloadHits.incrementAndGet();
                    respond(exchange, 404, "text/plain", "not found".getBytes(StandardCharsets.UTF_8));
                });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        when(interpreter.interpret(any()))
                .thenReturn(new InterpretationResult(
                        MessageIntent.NON_BUSINESS,
                        Map.of("note", "media-test"),
                        "test-provider",
                        "test-model",
                        "v1",
                        null));
    }

    @AfterEach
    void tearDown() throws IOException {
        server.stop(0);
    }

    @Test
    void imageMessageDownloadsIntoMediaContentRefsBeforeInterpretation() throws Exception {
        long submissionId = submitImageMessage("MEDIA-IMG-001", baseUrl + "/media/ok");

        pollWorker();

        ArgumentCaptor<InterpretationInput> input = ArgumentCaptor.forClass(InterpretationInput.class);
        verify(interpreter).interpret(input.capture());
        assertThat(input.getValue().submissionId()).isEqualTo(submissionId);
        assertThat(input.getValue().mediaContentRefs()).hasSize(1);
        String ref = input.getValue().mediaContentRefs().getFirst();
        assertThat(Files.exists(MEDIA_DIR.resolve(ref))).as("受控媒体文件必须存在").isTrue();

        Long available = jdbc.queryForObject(
                "SELECT count(*) FROM app.message_media WHERE submission_id = ? AND download_status = 'AVAILABLE'",
                Long.class,
                submissionId);
        assertThat(available).isEqualTo(1);
        Long versions = jdbc.queryForObject(
                "SELECT count(*) FROM app.message_interpretations WHERE submission_id = ?", Long.class, submissionId);
        assertThat(versions).isEqualTo(1);
        assertThat(downloadHits.get()).isEqualTo(1);
    }

    @Test
    void textMessageKeepsMediaContentRefsEmpty() {
        long submissionId = submitTextMessage("MEDIA-TEXT-001", "纯文字需求");

        pollWorker();

        ArgumentCaptor<InterpretationInput> input = ArgumentCaptor.forClass(InterpretationInput.class);
        verify(interpreter).interpret(input.capture());
        assertThat(input.getValue().submissionId()).isEqualTo(submissionId);
        assertThat(input.getValue().mediaContentRefs()).isEmpty();
        assertThat(downloadHits.get()).isZero();
    }

    @Test
    void downloadFailureRetriesThenTerminalNeedReview() {
        long submissionId = submitImageMessage("MEDIA-FAIL-001", baseUrl + "/media/missing");

        pollWorker(); // 第一次尝试：失败，attempts=1
        pollWorker(); // 第二次尝试：失败，attempts=2
        pollWorker(); // 第三次尝试：失败，attempts=3 → 任务转 FINALIZING（等待租约过期恢复）
        // FINALIZING 任务需租约过期才可被 claim 恢复收口（防并发设计），测试直接强制过期
        jdbc.update(
                "UPDATE app.async_tasks SET lease_until = CURRENT_TIMESTAMP - interval '1 second' "
                        + "WHERE status = 'FINALIZING'");
        pollWorker(); // 终态收口：resumeFinalization → routeFinalFailure

        // 模型绝不在证据缺失时被调用
        verify(interpreter, never()).interpret(any());
        java.util.List<java.util.Map<String, Object>> mediaRows = jdbc.queryForList(
                "SELECT channel_media_id, download_status, attempts, failure_reason "
                        + "FROM app.message_media WHERE submission_id = ?",
                submissionId);
        assertThat(mediaRows).as("media rows: %s", mediaRows).hasSize(1);
        Long openCases = jdbc.queryForObject(
                """
                SELECT count(*) FROM app.review_cases
                WHERE message_submission_id = ? AND reason_code = 'WECOM_NEED_REVIEW' AND status = 'OPEN'
                """,
                Long.class,
                submissionId);
        assertThat(openCases).isEqualTo(1);
    }

    private void pollWorker() {
        new InterpretationWorker(taskStore, interpretationService, true, 30, 0).poll();
    }

    private long submitImageMessage(String messageId, String url) {
        String frame = "{\"cmd\":\"aibot_msg_callback\",\"headers\":{\"req_id\":\"req-media\"},\"body\":{"
                + "\"msgid\":\"" + messageId + "\",\"aibotid\":\"bot-1\",\"chattype\":\"single\","
                + "\"from\":{\"userid\":\"user-media\"},\"msgtype\":\"image\","
                + "\"image\":{\"url\":\"" + url + "\",\"aeskey\":\"" + AES_KEY + "\"}}}";
        return submissionService.submit(new ChannelMessageCommand(
                "bot-1",
                "wecom-long-connection",
                "bot-1",
                messageId,
                "single:user-media",
                "single",
                "user-media",
                "image",
                "",
                null,
                null,
                json(frame)));
    }

    private long submitTextMessage(String messageId, String content) {
        String frame = "{\"cmd\":\"aibot_msg_callback\",\"headers\":{\"req_id\":\"req-text\"},\"body\":{"
                + "\"msgid\":\"" + messageId + "\",\"aibotid\":\"bot-1\",\"chattype\":\"single\","
                + "\"from\":{\"userid\":\"user-text\"},\"msgtype\":\"text\","
                + "\"text\":{\"content\":\"" + content + "\"}}}";
        return submissionService.submit(new ChannelMessageCommand(
                "bot-1",
                "wecom-long-connection",
                "bot-1",
                messageId,
                "single:user-text",
                "single",
                "user-text",
                "text",
                content,
                null,
                null,
                json(frame)));
    }

    private static com.fasterxml.jackson.databind.JsonNode json(String raw) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(raw);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("invalid test frame", ex);
        }
    }

    /** 按长连接规范构造媒体密文：AES-256-CBC、IV=aeskey 前 16 字节、PKCS#7 填充至 32 字节倍数。 */
    private static byte[] encrypt(byte[] plain) {
        try {
            byte[] key = Base64.getDecoder().decode(AES_KEY);
            byte[] padded = pkcs7Pad(plain);
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(key, 0, 16));
            return cipher.doFinal(padded);
        } catch (Exception ex) {
            throw new IllegalStateException("媒体密文样本构造失败", ex);
        }
    }

    private static byte[] pkcs7Pad(byte[] data) {
        int block = 32;
        int padding = block - (data.length % block);
        byte[] padded = new byte[data.length + padding];
        System.arraycopy(data, 0, padded, 0, data.length);
        for (int i = data.length; i < padded.length; i++) {
            padded[i] = (byte) padding;
        }
        return padded;
    }

    private static void respond(HttpExchange exchange, int status, String contentType, byte[] body)
            throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static Path createMediaDir() {
        try {
            return Files.createTempDirectory("wecom-media-test");
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static void cleanMediaDir() throws IOException {
        try (var stream = Files.list(MEDIA_DIR)) {
            for (Path file : stream.toList()) {
                Files.deleteIfExists(file);
            }
        }
    }
}
