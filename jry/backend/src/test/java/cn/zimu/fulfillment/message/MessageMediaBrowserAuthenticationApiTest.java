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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 生产认证策略下复核页原图受权接口：浏览器 &lt;img&gt; 无法携带 X-Operator 头，
 * 因此该路径只要求可验证的 Basic 凭据（BUSINESS_CREDENTIALS_ONLY），无凭据一律 401。
 */
@Testcontainers
@ActiveProfiles("production-auth-test")
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "app.gateway.basic-auth.username=media-browser-admin",
            "app.gateway.basic-auth.password=media-browser-password"
        })
class MessageMediaBrowserAuthenticationApiTest {

    private static final Path MEDIA_DIR = createMediaDir();

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void mediaConfiguration(DynamicPropertyRegistry registry) {
        registry.add("app.media.dir", () -> MEDIA_DIR.toString());
        registry.add("app.message-worker.enabled", () -> "false");
    }

    @Autowired private TestRestTemplate http;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("DELETE FROM app.message_media");
        jdbc.update("DELETE FROM app.message_submissions");
        jdbc.update("DELETE FROM app.channel_messages");
        try (var stream = Files.list(MEDIA_DIR)) {
            for (Path file : stream.toList()) {
                Files.deleteIfExists(file);
            }
        }
        seedAvailableMedia();
    }

    @Test
    void browserImageWithoutOperatorHeaderIsServedWhenBasicCredentialsPass() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Request-Id", "req-media-browser-img-001");

        ResponseEntity<byte[]> response = http
                .withBasicAuth("media-browser-admin", "media-browser-password")
                .exchange("/api/v1/message-media/1/content", HttpMethod.GET,
                        new HttpEntity<>(headers), byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("原图字节".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void browserImageWithMismatchedOperatorHeaderIsStillServed() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Operator", "forged-browser-operator");

        ResponseEntity<byte[]> response = http
                .withBasicAuth("media-browser-admin", "media-browser-password")
                .exchange("/api/v1/message-media/1/content", HttpMethod.GET,
                        new HttpEntity<>(headers), byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void browserImageWithoutCredentialsIsRejected() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Request-Id", "req-media-browser-anon-001");

        ResponseEntity<byte[]> response = http.exchange(
                "/api/v1/message-media/1/content", HttpMethod.GET,
                new HttpEntity<>(headers), byte[].class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private void seedAvailableMedia() throws Exception {
        Files.writeString(MEDIA_DIR.resolve("sha-ref"), "原图字节", StandardCharsets.UTF_8);
        jdbc.update(
                """
                INSERT INTO app.channel_messages (
                    id, corp_id, connection_id, bot_id, message_id, chat_id, chat_type,
                    sender_user_id, message_type, content, raw_payload
                ) VALUES (1, 'bot-1', 'wecom-long-connection', 'bot-1', 'MSG-1', 'chat-1', 'group',
                          'user-1', 'text', 'seed', '{}'::jsonb)
                """);
        jdbc.update(
                "INSERT INTO app.message_submissions (id, submission_no, source_message_id) VALUES (1, 'SUB-1', 1)");
        jdbc.update(
                """
                INSERT INTO app.message_media (
                    id, channel_message_id, submission_id, channel_media_id, media_type,
                    download_status, content_ref, content_hash, content_type, size_bytes
                ) VALUES (1, 1, 1, 'img-0', 'image', 'AVAILABLE', 'sha-ref', 'sha', 'image/png', 12)
                """);
    }

    private static Path createMediaDir() {
        try {
            return Files.createTempDirectory("wecom-media-browser-auth-test");
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
