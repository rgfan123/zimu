package cn.zimu.fulfillment.connector.wecom;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 下载企业微信媒体 URL（5 分钟有效）的原始密文字节。
 *
 * <p>使用 JDK 内置 {@link HttpClient}，无外部依赖；单次下载不重试（重试由任务框架负责，URL
 * 过期后需从最新消息载荷重新取）。响应体限量读取，超过 {@code maxBytes} 立即失败，避免大文件
 * 占满内存。仅接受 200 响应；Content-Type 仅作提示，不作为信任依据。
 */
@Service
public class WecomMediaDownloader {

    private static final int MAX_RESPONSE_BYTES = 20 * 1024 * 1024;

    private final HttpClient client;
    private final Duration timeout;

    public WecomMediaDownloader(@Value("${app.media.download-timeout-ms:15000}") long timeoutMillis) {
        this.timeout = Duration.ofMillis(timeoutMillis);
        this.client = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** 下载结果：密文字节 + 服务端声明的内容类型（可能为空）。 */
    public record DownloadedMedia(byte[] bytes, String contentType) {}

    public DownloadedMedia download(String url) {
        return download(url, MAX_RESPONSE_BYTES);
    }

    public DownloadedMedia download(String url, int maxBytes) {
        if (maxBytes < 1) {
            throw new IllegalArgumentException("maxBytes 必须为正数");
        }
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(url)).timeout(timeout).GET().build();
        } catch (IllegalArgumentException exception) {
            throw new MediaDownloadException("媒体下载地址非法: " + exception.getMessage(), exception);
        }
        HttpResponse<InputStream> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new MediaDownloadException("媒体下载被中断", exception);
        } catch (IOException exception) {
            throw new MediaDownloadException("媒体下载网络错误: " + exception.getMessage(), exception);
        }
        if (response.statusCode() != 200) {
            throw new MediaDownloadException("媒体下载失败 HTTP " + response.statusCode());
        }
        try (InputStream body = response.body()) {
            byte[] bytes = body.readNBytes(maxBytes + 1);
            if (bytes.length > maxBytes) {
                throw new MediaDownloadException("媒体下载超过大小上限 " + maxBytes + " 字节");
            }
            String contentType = response
                    .headers()
                    .firstValue("Content-Type")
                    .map(WecomMediaDownloader::stripParameters)
                    .filter(value -> !value.isBlank())
                    .orElse(null);
            return new DownloadedMedia(bytes, contentType);
        } catch (IOException exception) {
            throw new MediaDownloadException("媒体下载读取失败: " + exception.getMessage(), exception);
        }
    }

    /** 媒体下载失败；URL 或响应内容可能已过期/缺失。 */
    public static final class MediaDownloadException extends RuntimeException {
        public MediaDownloadException(String message) {
            super(message);
        }

        public MediaDownloadException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static String stripParameters(String contentType) {
        int separator = contentType.indexOf(';');
        return separator >= 0 ? contentType.substring(0, separator).trim() : contentType.trim();
    }
}
