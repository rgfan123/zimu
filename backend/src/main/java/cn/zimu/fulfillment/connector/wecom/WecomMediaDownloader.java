package cn.zimu.fulfillment.connector.wecom;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.beans.factory.annotation.Autowired;
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

    /** 成因链遍历上限；只为防成环，正常链路远不到这个深度。 */
    private static final int MAX_CAUSE_DEPTH = 16;

    private final HttpClient client;
    private final Duration timeout;
    private final URI testOrigin;

    @Autowired
    public WecomMediaDownloader(@Value("${app.media.download-timeout-ms:15000}") long timeoutMillis) {
        this(timeoutMillis, null);
    }

    private WecomMediaDownloader(long timeoutMillis, URI testOrigin) {
        this.timeout = Duration.ofMillis(timeoutMillis);
        this.testOrigin = testOrigin;
        this.client = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** Package-only local HTTP seam; production construction always enforces the official host set. */
    static WecomMediaDownloader forTest(long timeoutMillis, URI loopbackOrigin) {
        return new WecomMediaDownloader(
                timeoutMillis, WecomExternalOriginPolicy.requireLoopbackHttpOrigin(loopbackOrigin));
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
            URI uri = testOrigin == null
                    ? WecomExternalOriginPolicy.requireOfficialMediaUri(url)
                    : WecomExternalOriginPolicy.requireUriAtOrigin(url, testOrigin);
            request = HttpRequest.newBuilder(uri).timeout(timeout).GET().build();
        } catch (IllegalArgumentException exception) {
            String message = exception.getMessage();
            throw new MediaDownloadException(
                    message == null || message.isBlank() ? "媒体下载地址非法" : message, exception);
        }
        CompletableFuture<HttpResponse<byte[]>> pending;
        try {
            pending = client.sendAsync(request, responseInfo -> {
                if (responseInfo.statusCode() != 200) {
                    return new FailedBodySubscriber("媒体下载失败 HTTP " + responseInfo.statusCode());
                }
                long declaredLength = responseInfo.headers()
                        .firstValueAsLong("Content-Length")
                        .orElse(-1L);
                if (declaredLength > maxBytes) {
                    return new FailedBodySubscriber("媒体下载超过大小上限 " + maxBytes + " 字节");
                }
                return new BoundedBodySubscriber(maxBytes);
            });
        } catch (IllegalArgumentException exception) {
            // Do not surface the signed source URL from JDK exception messages.
            throw new MediaDownloadException("媒体下载请求无法提交", exception);
        }
        HttpResponse<byte[]> response;
        try {
            // HttpRequest.timeout 与 future deadline 双重覆盖 connect + headers + 整个 body。
            // BodySubscriber 只累积 maxBytes，超限立即 cancel，不依赖 Content-Length 诚信。
            response = pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new MediaDownloadException("媒体下载被中断", exception);
        } catch (TimeoutException exception) {
            pending.cancel(true);
            throw new MediaDownloadException("媒体下载超时", exception);
        } catch (ExecutionException exception) {
            throw classifyExecutionFailure(exception);
        }
        String contentType = response
                .headers()
                .firstValue("Content-Type")
                .map(WecomMediaDownloader::stripParameters)
                .filter(value -> !value.isBlank())
                .orElse(null);
        return new DownloadedMedia(response.body(), contentType);
    }

    /**
     * 把 {@code sendAsync} 的执行失败翻译成本类的失败原因。
     *
     * <p>为什么遍历整条成因链而不是只看第一层：{@code HttpClient::sendAsync} 的规范要求返回的
     * future 只以 {@link java.io.IOException} 异常完成。JDK 26 起
     * {@code HttpClientImpl#translateSendAsyncExecFailure} 真的开始照做，会把我们在
     * {@code BodySubscriber} 里抛出的 {@link MediaDownloadException}（一个 RuntimeException）
     * 包进一层 IOException。只认第一层成因，「媒体下载失败 HTTP 302 / 404」和
     * 「媒体下载超过大小上限」这些精确判据就会被降级成笼统的「媒体下载网络错误」，
     * 上游 {@code WecomTrackingFileProcessor} 靠消息文本分流出的
     * {@code WECOM_TRACKING_FILE_TOO_LARGE} 也会跟着退化成
     * {@code WECOM_TRACKING_FILE_DOWNLOAD_FAILED}，运营看到的中文文案随之从
     * 「超过 20MB 上限，请拆分」变成没有行动指引的「下载或解密失败」。
     *
     * <p>遍历限深并防自环：成因链成环时绝不能把下载线程转死。
     */
    static MediaDownloadException classifyExecutionFailure(ExecutionException exception) {
        Throwable cause = exception.getCause();
        if (cause == null) {
            return new MediaDownloadException("媒体下载网络错误: unknown", exception);
        }
        Throwable current = cause;
        for (int depth = 0; current != null && depth < MAX_CAUSE_DEPTH; depth++) {
            if (current instanceof MediaDownloadException mediaDownloadException) {
                return mediaDownloadException;
            }
            if (current instanceof java.net.http.HttpTimeoutException) {
                return new MediaDownloadException("媒体下载超时", cause);
            }
            Throwable next = current.getCause();
            current = next == current ? null : next;
        }
        // 认不出来的传输故障：只报异常类名，绝不把签名 URL 或响应内容带进消息。
        return new MediaDownloadException(
                "媒体下载网络错误: " + cause.getClass().getSimpleName(), cause);
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

    /** Reactive body consumer with a hard byte cap; no intermediate unbounded byte array is created. */
    private static final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {

        private final int maxBytes;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream(8192);
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private Flow.Subscription subscription;

        private BoundedBodySubscriber(int maxBytes) {
            this.maxBytes = maxBytes;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            if (body.isDone()) {
                return;
            }
            for (ByteBuffer buffer : buffers) {
                int incoming = buffer.remaining();
                if (incoming > maxBytes - output.size()) {
                    subscription.cancel();
                    body.completeExceptionally(new MediaDownloadException(
                            "媒体下载超过大小上限 " + maxBytes + " 字节"));
                    return;
                }
                byte[] chunk = new byte[incoming];
                buffer.get(chunk);
                output.writeBytes(chunk);
            }
            subscription.request(1);
        }

        @Override
        public void onError(Throwable throwable) {
            body.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            body.complete(output.toByteArray());
        }
    }

    /** Rejects from response headers/status and cancels the network body immediately. */
    private static final class FailedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {

        private final CompletableFuture<byte[]> body = new CompletableFuture<>();

        private FailedBodySubscriber(String message) {
            body.completeExceptionally(new MediaDownloadException(message));
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.cancel();
        }

        @Override public void onNext(List<ByteBuffer> item) {}
        @Override public void onError(Throwable throwable) {}
        @Override public void onComplete() {}
    }
}
