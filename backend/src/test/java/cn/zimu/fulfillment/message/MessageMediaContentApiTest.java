package cn.zimu.fulfillment.message;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * wecom-message-intake 07 复核页原图受权接口：AVAILABLE 媒体返回解密后的原件字节与内容类型；
 * 未就绪（PENDING/FAILED/不存在）一律 404，不暴露路径或凭据。
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MessageMediaContentApiTest {

    private static final Path MEDIA_DIR = createMediaDir();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void mediaConfiguration(DynamicPropertyRegistry registry) {
        registry.add("app.media.dir", () -> MEDIA_DIR.toString());
        registry.add("app.message-worker.enabled", () -> "false");
        registry.add("app.gateway.basic-auth.username", () -> "media-test-admin");
        registry.add("app.gateway.basic-auth.password", () -> "media-test-password");
    }

    @Autowired private TestRestTemplate http;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("DELETE FROM app.message_media");
        cleanMediaDir();
    }

    @Test
    void availableMediaServesDecryptedOriginalBytes() throws Exception {
        seedMessage(1L);
        Files.writeString(MEDIA_DIR.resolve("sha-ref"), "原图字节", StandardCharsets.UTF_8);
        jdbc.update(
                """
                INSERT INTO app.message_media (
                    channel_message_id, submission_id, channel_media_id, media_type,
                    download_status, content_ref, content_hash, content_type, size_bytes
                ) VALUES (1, 1, 'img-0', 'image', 'AVAILABLE', 'sha-ref', 'sha', 'image/png', 12)
                """);

        ResponseEntity<byte[]> response =
                http.getForEntity("/api/v1/message-media/1/content", byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("原图字节".getBytes(StandardCharsets.UTF_8));
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().toString()).contains("image/png");
    }

    @Test
    void pendingMediaIsNotFound() {
        seedMessage(2L);
        jdbc.update(
                """
                INSERT INTO app.message_media (
                    channel_message_id, submission_id, channel_media_id, media_type,
                    download_status
                ) VALUES (2, 2, 'img-0', 'image', 'PENDING')
                """);

        ResponseEntity<byte[]> response =
                http.getForEntity("/api/v1/message-media/2/content", byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void missingMediaIsNotFound() {
        ResponseEntity<byte[]> response =
                http.getForEntity("/api/v1/message-media/999/content", byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void missingFileIsNotFound() {
        seedMessage(3L);
        jdbc.update(
                """
                INSERT INTO app.message_media (
                    channel_message_id, submission_id, channel_media_id, media_type,
                    download_status, content_ref
                ) VALUES (3, 3, 'img-0', 'image', 'AVAILABLE', 'no-such-file')
                """);

        ResponseEntity<byte[]> response =
                http.getForEntity("/api/v1/message-media/3/content", byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void channelMessageDetailExposesWhitelistedMediaRefs() {
        seedMessage(4L);
        jdbc.update(
                """
                INSERT INTO app.message_media (
                    channel_message_id, submission_id, channel_media_id, media_type,
                    download_status, content_ref, content_hash, content_type, size_bytes, decrypt_info
                ) VALUES (4, 4, 'img-0', 'image', 'AVAILABLE', 'sha-ref', 'sha', 'image/png', 12,
                          '{"key":"top-secret-aes"}'::jsonb)
                """);

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Operator", "media-test-admin");
        ResponseEntity<String> response = http
                .withBasicAuth("media-test-admin", "media-test-password")
                .exchange(
                        "/api/v1/channel-messages/4",
                        HttpMethod.GET,
                        new HttpEntity<>(headers),
                        String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = response.getBody();
        assertThat(body).contains("\"media_refs\"");
        assertThat(body).contains("\"media_type\":\"image\"");
        assertThat(body).contains("\"content_type\":\"image/png\"");
        // 白名单投影：存储引用、哈希、解密信息与原始 URL 不得出现在复核证据投影中
        assertThat(body).doesNotContain("content_ref");
        assertThat(body).doesNotContain("content_hash");
        assertThat(body).doesNotContain("decrypt_info");
        assertThat(body).doesNotContain("top-secret-aes");
    }

    private void seedMessage(long id) {
        jdbc.update(
                """
                INSERT INTO app.channel_messages (
                    id, corp_id, connection_id, bot_id, message_id, chat_id, chat_type,
                    sender_user_id, message_type, content, raw_payload
                ) VALUES (?, 'bot-1', 'wecom-long-connection', 'bot-1', 'MSG-' || ?, 'chat-1', 'group',
                          'user-1', 'text', 'seed', '{}'::jsonb)
                """,
                id,
                id);
        jdbc.update(
                "INSERT INTO app.message_submissions (id, submission_no, source_message_id) VALUES (?, ?, ?)",
                id,
                "SUB-" + id,
                id);
    }

    private static Path createMediaDir() {
        try {
            return Files.createTempDirectory("wecom-media-content-test");
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static void cleanMediaDir() throws Exception {
        try (var stream = Files.list(MEDIA_DIR)) {
            for (Path file : stream.toList()) {
                Files.deleteIfExists(file);
            }
        }
    }
}
