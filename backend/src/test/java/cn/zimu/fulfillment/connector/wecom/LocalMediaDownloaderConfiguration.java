package cn.zimu.fulfillment.connector.wecom;

import java.net.URI;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Test-only loopback adapter; production construction never exposes this origin override. */
@TestConfiguration(proxyBeanMethods = false)
public class LocalMediaDownloaderConfiguration {

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
