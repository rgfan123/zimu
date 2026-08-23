package cn.zimu.fulfillment.connector.wecom;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zimu.fulfillment.message.MediaDownloadStatus;
import cn.zimu.fulfillment.message.MediaResult;
import cn.zimu.fulfillment.message.MediaResultStatus;
import cn.zimu.fulfillment.message.MessageMedia;
import cn.zimu.fulfillment.message.MessageMediaRepository;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 媒体证据链路集成：本地 mock 下载端点 + 按规范构造的媒体级密文样本，覆盖下载→解密→受控存储→
 * 落库、幂等重入、内容寻址复用与失败路径（404、解密失败、终态 FAILED）。不依赖外部网络。
 */
@Testcontainers
@SpringBootTest
@Import(WecomMediaEvidenceServiceTest.LocalMediaDownloaderConfiguration.class)
class WecomMediaEvidenceServiceTest {

    private static final String AES_KEY = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY";
    private static final Path MEDIA_DIR = createMediaDir();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void mediaConfiguration(DynamicPropertyRegistry registry) {
        registry.add("app.media.dir", () -> MEDIA_DIR.toString());
        registry.add("app.message-worker.enabled", () -> "false");
        registry.add("app.wecom-tracking-file-worker.enabled", () -> "false");
        registry.add("app.wecom-export-worker.enabled", () -> "false");
        registry.add("app.agent-worker.enabled", () -> "false");
    }

    @Autowired
    private WecomMediaEvidenceService evidenceService;

    @Autowired
    private MessageMediaRepository mediaRepository;

    @Autowired
    private JdbcTemplate jdbc;

    private HttpServer server;
    private String baseUrl;
    private final AtomicInteger downloadHits = new AtomicInteger();

    /** Test-only loopback adapter; production construction never exposes this origin override. */
    @TestConfiguration(proxyBeanMethods = false)
    static class LocalMediaDownloaderConfiguration {

        @Bean
        @Primary
        WecomMediaDownloader loopbackMediaDownloader() {
            return new WecomMediaDownloader(15_000) {
                @Override
                public WecomMediaDownloader.DownloadedMedia download(String url, int maxBytes) {
                    URI mediaUri = URI.create(url);
                    URI origin = URI.create(
                            mediaUri.getScheme() + "://" + mediaUri.getHost() + ":" + mediaUri.getPort());
                    return WecomMediaDownloader.forTest(15_000, origin).download(url, maxBytes);
                }
            };
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        jdbc.update("DELETE FROM app.message_media");
        jdbc.update("DELETE FROM app.channel_messages");
        cleanMediaDir();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        downloadHits.set(0);
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void downloadsDecryptsAndPersistsMediaEvidence() throws IOException {
        byte[] plaintext = jpegSample();
        byte[] ciphertext = encrypt(plaintext, AES_KEY);
        installMedia("/media/1", ciphertext, "image/jpeg");
        long channelMessageId = insertChannelMessage("msg-1");

        MediaResult result = evidenceService.storeMedia(command(channelMessageId, "media-1", "/media/1"));

        assertThat(result.status()).isEqualTo(MediaResultStatus.SUCCEEDED);
        assertThat(result.mediaId()).isNotNull();
        assertThat(result.sha256()).isEqualTo(sha256Hex(plaintext));
        assertThat(result.contentType()).isEqualTo("image/jpeg");
        assertThat(result.sizeBytes()).isEqualTo((long) plaintext.length);
        // 受控存储：文件存在且内容为解密后的明文原件
        assertThat(Files.readAllBytes(Path.of(result.storageRef()))).isEqualTo(plaintext);

        MessageMedia row = mediaRepository
                .findByChannelMessageIdAndChannelMediaId(channelMessageId, "media-1")
                .orElseThrow();
        assertThat(row.getDownloadStatus()).isEqualTo(MediaDownloadStatus.AVAILABLE);
        assertThat(row.getContentHash()).isEqualTo(result.sha256());
        assertThat(row.getContentRef()).isEqualTo(result.storageRef());
        assertThat(row.getContentType()).isEqualTo("image/jpeg");
        assertThat(row.getSizeBytes()).isEqualTo((long) plaintext.length);
        assertThat(row.getSourceUrl()).isEqualTo(baseUrl + "/media/1");
        assertThat(row.getAttempts()).isZero();
        assertThat(row.getFailureReason()).isNull();
        assertThat(row.getDecryptInfo()).containsEntry("algorithm", "AES-256-CBC");
    }

    @Test
    void storeMediaIsIdempotentForSameChannelMediaKey() throws IOException {
        byte[] plaintext = jpegSample();
        byte[] ciphertext = encrypt(plaintext, AES_KEY);
        installMedia("/media/1", ciphertext, "image/jpeg");
        long channelMessageId = insertChannelMessage("msg-idem");
        MediaEvidenceCommand command = command(channelMessageId, "media-1", "/media/1");

        MediaResult first = evidenceService.storeMedia(command);
        MediaResult second = evidenceService.storeMedia(command);

        assertThat(first.status()).isEqualTo(MediaResultStatus.SUCCEEDED);
        assertThat(second.status()).isEqualTo(MediaResultStatus.SUCCEEDED);
        assertThat(second.mediaId()).isEqualTo(first.mediaId());
        assertThat(mediaRepository.count()).isEqualTo(1);
        // 幂等重入不再重新下载（URL 5 分钟有效，不能浪费）
        assertThat(downloadHits.get()).isEqualTo(1);
    }

    @Test
    void lateConcurrentFailureCannotDowngradeAvailableEvidence() throws Exception {
        byte[] plaintext = jpegSample();
        byte[] ciphertext = encrypt(plaintext, AES_KEY);
        CountDownLatch lateRequestEntered = new CountDownLatch(1);
        CountDownLatch releaseLateFailure = new CountDownLatch(1);
        server.createContext("/media/late-failure", exchange -> {
            downloadHits.incrementAndGet();
            lateRequestEntered.countDown();
            try {
                if (!releaseLateFailure.await(5, TimeUnit.SECONDS)) {
                    throw new IOException("test did not release late failure");
                }
                respond(exchange, 503, "unavailable".getBytes(StandardCharsets.UTF_8), "text/plain");
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                exchange.close();
            }
        });
        installMedia("/media/concurrent-success", ciphertext, "image/jpeg");
        long channelMessageId = insertChannelMessage("msg-concurrent-monotonic");
        String mediaKey = "media-concurrent";

        CompletableFuture<MediaResult> lateFailure = CompletableFuture.supplyAsync(() -> evidenceService.storeMedia(
                command(channelMessageId, mediaKey, "/media/late-failure")));
        assertThat(lateRequestEntered.await(5, TimeUnit.SECONDS)).isTrue();

        MediaResult success = evidenceService.storeMedia(
                command(channelMessageId, mediaKey, "/media/concurrent-success"));
        releaseLateFailure.countDown();
        MediaResult staleResult = lateFailure.get(5, TimeUnit.SECONDS);

        assertThat(success.status()).isEqualTo(MediaResultStatus.SUCCEEDED);
        assertThat(staleResult.status()).isEqualTo(MediaResultStatus.SUCCEEDED);
        MessageMedia row = mediaRepository
                .findByChannelMessageIdAndChannelMediaId(channelMessageId, mediaKey)
                .orElseThrow();
        assertThat(row.getDownloadStatus()).isEqualTo(MediaDownloadStatus.AVAILABLE);
        assertThat(row.getContentHash()).isEqualTo(sha256Hex(plaintext));
        assertThat(row.getAttempts()).isZero();
    }

    @Test
    void interruptedDownloadLeavesMediaPendingWithoutFailureAttempt() throws Exception {
        CountDownLatch bodyStarted = new CountDownLatch(1);
        CountDownLatch releaseBody = new CountDownLatch(1);
        server.createContext("/media/interrupted", exchange -> {
            try {
                exchange.sendResponseHeaders(200, 1024);
                exchange.getResponseBody().write(1);
                exchange.getResponseBody().flush();
                bodyStarted.countDown();
                releaseBody.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        long channelMessageId = insertChannelMessage("msg-interrupted-download");
        AtomicReference<Throwable> observed = new AtomicReference<>();
        CountDownLatch finished = new CountDownLatch(1);
        Thread downloadThread = new Thread(() -> {
            try {
                evidenceService.storeMedia(command(
                        channelMessageId,
                        "media-interrupted",
                        "/media/interrupted"));
            } catch (Throwable throwable) {
                observed.set(throwable);
            } finally {
                finished.countDown();
            }
        }, "test-wecom-media-interrupt");

        try {
            downloadThread.start();
            assertThat(bodyStarted.await(5, TimeUnit.SECONDS)).isTrue();
            downloadThread.interrupt();
            assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            releaseBody.countDown();
            downloadThread.join(5_000);
        }

        assertThat(observed.get()).isInstanceOf(WecomMediaDownloader.MediaDownloadException.class);
        MessageMedia row = mediaRepository
                .findByChannelMessageIdAndChannelMediaId(channelMessageId, "media-interrupted")
                .orElseThrow();
        assertThat(row.getDownloadStatus()).isEqualTo(MediaDownloadStatus.PENDING);
        assertThat(row.getAttempts()).isZero();
        assertThat(row.getFailureReason()).isNull();
        assertThat(countMediaDirFiles()).isZero();
    }

    @Test
    void samePlaintextReusesContentAddressedFileAcrossMessages() throws IOException {
        byte[] shared = jpegSample();
        byte[] ciphertext = encrypt(shared, AES_KEY);
        installMedia("/media/1", ciphertext, "image/jpeg");
        installMedia("/media/2", ciphertext, "image/jpeg");
        long firstMessage = insertChannelMessage("msg-a");
        long secondMessage = insertChannelMessage("msg-b");

        MediaResult first = evidenceService.storeMedia(command(firstMessage, "media-a", "/media/1"));
        MediaResult second = evidenceService.storeMedia(command(secondMessage, "media-b", "/media/2"));

        assertThat(first.status()).isEqualTo(MediaResultStatus.SUCCEEDED);
        assertThat(second.status()).isEqualTo(MediaResultStatus.SUCCEEDED);
        assertThat(second.storageRef()).isEqualTo(first.storageRef());
        assertThat(second.sha256()).isEqualTo(first.sha256());
        assertThat(mediaRepository.count()).isEqualTo(2);
        assertThat(countMediaDirFiles()).isEqualTo(1);
    }

    @Test
    void downloadFailureTracksAttemptsAndTerminalFailureAfterMaxAttempts() throws IOException {
        long channelMessageId = insertChannelMessage("msg-404");
        MediaEvidenceCommand command = command(channelMessageId, "media-404", "/missing");

        MediaResult first = evidenceService.storeMedia(command);
        assertThat(first.status()).isEqualTo(MediaResultStatus.PENDING);
        assertThat(first.failureReason()).contains("404");

        MediaResult second = evidenceService.storeMedia(command);
        assertThat(second.status()).isEqualTo(MediaResultStatus.PENDING);
        assertThat(second.mediaId()).isEqualTo(first.mediaId());

        MediaResult third = evidenceService.storeMedia(command);
        assertThat(third.status()).isEqualTo(MediaResultStatus.FAILED);

        MessageMedia row = mediaRepository
                .findByChannelMessageIdAndChannelMediaId(channelMessageId, "media-404")
                .orElseThrow();
        assertThat(row.getDownloadStatus()).isEqualTo(MediaDownloadStatus.FAILED);
        assertThat(row.getAttempts()).isEqualTo(3);
        assertThat(row.getFailureReason()).contains("404");
        assertThat(row.getContentRef()).isNull();
        assertThat(countMediaDirFiles()).isZero();
    }

    @Test
    void decryptionFailureRecordsErrorAndKeepsPending() throws IOException {
        // 下载成功但密文非法（非 32 字节倍数）：解密失败路径
        installMedia("/media/1", new byte[] {1, 2, 3, 4, 5}, "application/octet-stream");
        long channelMessageId = insertChannelMessage("msg-bad-cipher");

        MediaResult result = evidenceService.storeMedia(command(channelMessageId, "media-1", "/media/1"));

        assertThat(result.status()).isEqualTo(MediaResultStatus.PENDING);
        assertThat(result.failureReason()).contains("32 字节倍数");
        MessageMedia row = mediaRepository
                .findByChannelMessageIdAndChannelMediaId(channelMessageId, "media-1")
                .orElseThrow();
        assertThat(row.getDownloadStatus()).isEqualTo(MediaDownloadStatus.PENDING);
        assertThat(row.getAttempts()).isEqualTo(1);
        assertThat(row.getContentRef()).isNull();
        assertThat(countMediaDirFiles()).isZero();
    }

    // ------------------------------------------------------------------
    // 测试基建
    // ------------------------------------------------------------------

    private MediaEvidenceCommand command(long channelMessageId, String channelMediaId, String path) {
        return new MediaEvidenceCommand(
                channelMessageId,
                null,
                channelMediaId,
                "image",
                baseUrl + path,
                AES_KEY);
    }

    private long insertChannelMessage(String messageId) {
        return jdbc.queryForObject(
                """
                INSERT INTO app.channel_messages (
                    corp_id, connection_id, bot_id, message_id, chat_id, chat_type,
                    sender_user_id, message_type, content, raw_payload
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                RETURNING id
                """,
                Long.class,
                "media-test-corp",
                "media-test-conn",
                "media-test-bot",
                messageId,
                "media-test-chat",
                "group",
                "media-test-user",
                "image",
                "image",
                "{\"evidence\":\"test\"}");
    }

    private void installMedia(String path, byte[] body, String contentType) {
        server.createContext(path, exchange -> {
            downloadHits.incrementAndGet();
            respond(exchange, 200, body, contentType);
        });
    }

    private static void respond(HttpExchange exchange, int status, byte[] body, String contentType)
            throws IOException {
        if (contentType != null) {
            exchange.getResponseHeaders().set("Content-Type", contentType);
        }
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        } finally {
            exchange.close();
        }
    }

    private static byte[] jpegSample() {
        byte[] sample = new byte[333];
        // JPEG 魔数 + 噪声体（解密样本不依赖真实图片解码）
        sample[0] = (byte) 0xFF;
        sample[1] = (byte) 0xD8;
        sample[2] = (byte) 0xFF;
        sample[3] = (byte) 0xE0;
        for (int i = 4; i < sample.length; i++) {
            sample[i] = (byte) (i * 7 % 251);
        }
        return sample;
    }

    // 按规范构造媒体级密文样本：AES/CBC/NoPadding + PKCS#7 32 字节块 + IV=aeskey 前 16 字节
    private static byte[] encrypt(byte[] plaintext, String aeskeyBase64) {
        byte[] key = Base64.getDecoder().decode(aeskeyBase64 + "=");
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(key, 0, 16));
            int padding = 32 - plaintext.length % 32;
            byte[] padded = Arrays.copyOf(plaintext, plaintext.length + padding);
            Arrays.fill(padded, plaintext.length, padded.length, (byte) padding);
            return cipher.doFinal(padded);
        } catch (Exception exception) {
            throw new IllegalStateException("样本加密失败", exception);
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return java.util.HexFormat.of()
                    .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Path createMediaDir() {
        try {
            return Files.createTempDirectory("wecom-media-evidence-test-");
        } catch (IOException exception) {
            throw new IllegalStateException("无法创建媒体测试目录", exception);
        }
    }

    private static void cleanMediaDir() throws IOException {
        if (Files.exists(MEDIA_DIR)) {
            try (Stream<Path> walk = Files.walk(MEDIA_DIR)) {
                walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                        // 测试清理尽力而为
                    }
                });
            }
        }
        Files.createDirectories(MEDIA_DIR);
    }

    private static long countMediaDirFiles() throws IOException {
        try (Stream<Path> walk = Files.list(MEDIA_DIR)) {
            return walk.filter(Files::isRegularFile).count();
        }
    }
}
