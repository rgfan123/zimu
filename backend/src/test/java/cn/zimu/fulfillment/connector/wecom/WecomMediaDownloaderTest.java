package cn.zimu.fulfillment.connector.wecom;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WecomMediaDownloaderTest {

    private HttpServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void responseBodyMustFinishWithinTheRequestDeadline() {
        CountDownLatch headersSent = new CountDownLatch(1);
        CountDownLatch releaseBody = new CountDownLatch(1);
        server.createContext("/stalled", exchange -> {
            try {
                exchange.sendResponseHeaders(200, 1024);
                exchange.getResponseBody().write(1);
                exchange.getResponseBody().flush();
                headersSent.countDown();
                releaseBody.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        WecomMediaDownloader downloader = WecomMediaDownloader.forTest(150, serverOrigin());
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/stalled";

        try {
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertThatThrownBy(() -> downloader.download(url, 2048))
                    .isInstanceOf(WecomMediaDownloader.MediaDownloadException.class)
                    .hasMessageContaining("超时"));
        } finally {
            releaseBody.countDown();
        }
    }

    @Test
    void productionDownloaderRejectsLoopbackBeforeAnyRequestIsSent() {
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/media", exchange -> {
            requests.incrementAndGet();
            byte[] body = "should-not-be-read".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        WecomMediaDownloader downloader = new WecomMediaDownloader(500);

        assertThatThrownBy(() -> downloader.download(serverOrigin().resolve("/media").toString()))
                .isInstanceOf(WecomMediaDownloader.MediaDownloadException.class)
                .hasMessageContaining("来源不受信任");
        org.assertj.core.api.Assertions.assertThat(requests).hasValue(0);
    }

    @Test
    void productionDownloaderRejectsPrivateNetworkAddressBeforeConnecting() {
        WecomMediaDownloader downloader = new WecomMediaDownloader(500);

        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> assertThatThrownBy(
                        () -> downloader.download("https://10.0.0.1/private-media"))
                .isInstanceOf(WecomMediaDownloader.MediaDownloadException.class)
                .hasMessageContaining("来源不受信任"));
    }

    @Test
    void officialSignedMediaOriginIsAcceptedButDeceptiveSubdomainIsRejected() {
        String official = "https://ww-aibot-img-1258476243.cos.ap-guangzhou.myqcloud.com/"
                + "encrypted-object?sign=q-signature";

        org.assertj.core.api.Assertions.assertThat(
                        WecomExternalOriginPolicy.requireOfficialMediaUri(official))
                .isEqualTo(URI.create(official));
        assertThatThrownBy(() -> WecomExternalOriginPolicy.requireOfficialMediaUri(
                        "https://ww-aibot-img-1258476243.cos.ap-guangzhou.myqcloud.com.attacker.example/object"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("来源不受信任");
    }

    @Test
    void malformedSignedUrlIsRejectedWithoutEchoingCredentialMaterial() {
        WecomMediaDownloader downloader = new WecomMediaDownloader(500);
        String malformed = "https://ww-aibot-img-1258476243.cos.ap-guangzhou.myqcloud.com/%"
                + "?sign=do-not-leak-this";

        assertThatThrownBy(() -> downloader.download(malformed))
                .isInstanceOf(WecomMediaDownloader.MediaDownloadException.class)
                .hasMessageContaining("地址非法")
                .hasMessageNotContaining("do-not-leak-this");
    }

    @Test
    void packageTestPoliciesAcceptCanonicalIpv6LoopbackOrigins() {
        org.assertj.core.api.Assertions.assertThat(WecomExternalOriginPolicy.requireLoopbackHttpOrigin(
                        URI.create("http://[::1]:8080")))
                .isEqualTo(URI.create("http://[::1]:8080"));
        org.assertj.core.api.Assertions.assertThat(
                        WecomExternalOriginPolicy.requireLoopbackWebSocketUri("ws://[::1]:8081/"))
                .isEqualTo(URI.create("ws://[::1]:8081/"));
    }

    @Test
    void explicitTestOriginCanDownloadWithoutWeakeningProductionPolicy() {
        byte[] body = "encrypted-media".getBytes(StandardCharsets.UTF_8);
        server.createContext("/media", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/octet-stream; charset=binary");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        WecomMediaDownloader downloader = WecomMediaDownloader.forTest(500, serverOrigin());

        WecomMediaDownloader.DownloadedMedia downloaded =
                downloader.download(serverOrigin().resolve("/media").toString());

        org.assertj.core.api.Assertions.assertThat(downloaded.bytes()).isEqualTo(body);
        org.assertj.core.api.Assertions.assertThat(downloaded.contentType()).isEqualTo("application/octet-stream");
    }

    @Test
    void redirectsAreNeverFollowedEvenWhenTheInitialOriginIsExplicitlyAllowed() throws IOException {
        HttpServer redirectTarget = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger targetRequests = new AtomicInteger();
        redirectTarget.createContext("/stolen", exchange -> {
            targetRequests.incrementAndGet();
            exchange.sendResponseHeaders(200, 0);
            exchange.close();
        });
        redirectTarget.start();
        try {
            server.createContext("/redirect", exchange -> {
                exchange.getResponseHeaders()
                        .set(
                                "Location",
                                "http://127.0.0.1:" + redirectTarget.getAddress().getPort() + "/stolen");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
            });
            WecomMediaDownloader downloader = WecomMediaDownloader.forTest(500, serverOrigin());

            assertThatThrownBy(() -> downloader.download(serverOrigin().resolve("/redirect").toString()))
                    .isInstanceOf(WecomMediaDownloader.MediaDownloadException.class)
                    .hasMessageContaining("HTTP 302");
            org.assertj.core.api.Assertions.assertThat(targetRequests).hasValue(0);
        } finally {
            redirectTarget.stop(0);
        }
    }

    private URI serverOrigin() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }
}
