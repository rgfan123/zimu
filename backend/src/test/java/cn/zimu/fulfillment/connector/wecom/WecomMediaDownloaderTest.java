package cn.zimu.fulfillment.connector.wecom;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
        WecomMediaDownloader downloader = new WecomMediaDownloader(150);
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/stalled";

        try {
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertThatThrownBy(() -> downloader.download(url, 2048))
                    .isInstanceOf(WecomMediaDownloader.MediaDownloadException.class)
                    .hasMessageContaining("超时"));
        } finally {
            releaseBody.countDown();
        }
    }
}
