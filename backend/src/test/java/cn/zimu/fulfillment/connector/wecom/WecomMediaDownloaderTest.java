package cn.zimu.fulfillment.connector.wecom;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
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

    @Test
    void undeclaredLengthOverflowIsStillReportedAsASizeLimitNotAGenericFailure() {
        // 契约见 docs/agents/wecom-tracking-file.md §2：Content-Length 未声明或不可信时，
        // 仍由 subscriber 的硬上限取消。这条以前没有测试覆盖，而它和
        // FailedBodySubscriber 一样是在 BodySubscriber 里抛 MediaDownloadException，
        // 同样会被 JDK 26 包进 IOException 后降级成笼统错误。
        byte[] oversized = new byte[4096];
        server.createContext("/chunked", exchange -> {
            // 响应长度传 0 = 分块传输，不发 Content-Length，逼下载方只能靠硬上限兜。
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(oversized);
            exchange.close();
        });
        WecomMediaDownloader downloader = WecomMediaDownloader.forTest(2000, serverOrigin());

        assertThatThrownBy(() -> downloader.download(serverOrigin().resolve("/chunked").toString(), 1024))
                .isInstanceOf(WecomMediaDownloader.MediaDownloadException.class)
                .hasMessageContaining("超过大小上限");
    }

    // ---------------------------------------------------------------------------------------
    // 成因链回归（#JDK26）：`HttpClient::sendAsync` 的规范要求 future 只以 IOException 异常完成。
    // JDK 26 起 HttpClientImpl#translateSendAsyncExecFailure 真的开始照做，会把我们在
    // BodySubscriber 里抛出的 MediaDownloadException 包进一层 IOException。只认第一层成因，
    // 「HTTP 302 / HTTP 404 / 超过大小上限」这些精确判据就会被降级成笼统的网络错误，
    // 上游 WecomTrackingFileProcessor 靠消息文本分流的 WECOM_TRACKING_FILE_TOO_LARGE
    // 也会跟着退化成 WECOM_TRACKING_FILE_DOWNLOAD_FAILED。
    // 这几条直接打分类函数，不依赖运行时 JDK 版本，任何 JDK 上都能守住这个判据。
    // ---------------------------------------------------------------------------------------

    @Test
    void specificFailureSurvivesEvenWhenTheJdkWrapsItInAnIoException() {
        ExecutionException wrapped = new ExecutionException(new IOException(
                new WecomMediaDownloader.MediaDownloadException("媒体下载超过大小上限 20971520 字节")));

        assertThatThrownBy(() -> {
                    throw WecomMediaDownloader.classifyExecutionFailure(wrapped);
                })
                .isInstanceOf(WecomMediaDownloader.MediaDownloadException.class)
                .hasMessageContaining("超过大小上限")
                .hasMessageNotContaining("网络错误");
    }

    @Test
    void wrappedHttpStatusRejectionKeepsItsStatusCode() {
        ExecutionException wrapped = new ExecutionException(
                new IOException(new WecomMediaDownloader.MediaDownloadException("媒体下载失败 HTTP 302")));

        assertThatThrownBy(() -> {
                    throw WecomMediaDownloader.classifyExecutionFailure(wrapped);
                })
                .isInstanceOf(WecomMediaDownloader.MediaDownloadException.class)
                .hasMessageContaining("HTTP 302");
    }

    @Test
    void wrappedTimeoutIsStillReportedAsTimeoutNotAsGenericNetworkError() {
        ExecutionException wrapped = new ExecutionException(
                new IOException(new HttpTimeoutException("request timed out")));

        assertThatThrownBy(() -> {
                    throw WecomMediaDownloader.classifyExecutionFailure(wrapped);
                })
                .isInstanceOf(WecomMediaDownloader.MediaDownloadException.class)
                .hasMessageContaining("超时");
    }

    @Test
    void unrelatedTransportFailureStillFallsBackToTheGenericNetworkError() {
        ExecutionException wrapped = new ExecutionException(new ConnectException("connection refused"));

        assertThatThrownBy(() -> {
                    throw WecomMediaDownloader.classifyExecutionFailure(wrapped);
                })
                .isInstanceOf(WecomMediaDownloader.MediaDownloadException.class)
                .hasMessageContaining("媒体下载网络错误: ConnectException");
    }

    @Test
    void cyclicCauseChainTerminatesInsteadOfHangingTheDownloadThread() {
        // 成因链成环是 JDK 之外的库偶尔会造出来的东西；遍历必须限深，绝不能把下载线程转死。
        IOException outer = new IOException("outer");
        IOException inner = new IOException("inner");
        outer.initCause(inner);
        inner.initCause(outer);
        ExecutionException wrapped = new ExecutionException(outer);

        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertThatThrownBy(() -> {
                    throw WecomMediaDownloader.classifyExecutionFailure(wrapped);
                })
                .isInstanceOf(WecomMediaDownloader.MediaDownloadException.class)
                .hasMessageContaining("媒体下载网络错误: IOException"));
    }

    private URI serverOrigin() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }
}
