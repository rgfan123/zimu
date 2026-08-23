package cn.zimu.fulfillment.connector.wecom;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/**
 * Fail-closed origin rules for URLs received from, or carrying credentials to, Enterprise WeChat.
 *
 * <p>The media hostname is the signed COS endpoint shown in the official message payload examples
 * (developer document path/100719). Keep this set explicit: accepting all {@code myqcloud.com}
 * buckets would turn an untrusted callback field into an SSRF primitive.
 */
final class WecomExternalOriginPolicy {

    static final URI OFFICIAL_WEBSOCKET_URI = URI.create("wss://openws.work.weixin.qq.com");

    private static final Set<String> OFFICIAL_MEDIA_HOSTS =
            Set.of("ww-aibot-img-1258476243.cos.ap-guangzhou.myqcloud.com");

    private WecomExternalOriginPolicy() {}

    static URI requireOfficialMediaUri(String rawUrl) {
        URI uri = parse(rawUrl, "媒体下载地址非法");
        String host = normalizedHost(uri);
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || !OFFICIAL_MEDIA_HOSTS.contains(host)
                || (uri.getPort() != -1 && uri.getPort() != 443)
                || uri.getUserInfo() != null
                || uri.getFragment() != null) {
            throw new IllegalArgumentException("企业微信媒体来源不受信任");
        }
        return uri;
    }

    /** Package-only loopback seam used by deterministic HTTP tests; it is not Spring-bindable. */
    static URI requireLoopbackHttpOrigin(URI origin) {
        if (origin == null
                || origin.isOpaque()
                || origin.getUserInfo() != null
                || origin.getQuery() != null
                || origin.getFragment() != null
                || !Set.of("http", "https").contains(normalizedScheme(origin))
                || !isLoopbackLiteral(origin.getHost())
                || origin.getPort() < 1
                || !(origin.getPath() == null || origin.getPath().isEmpty() || "/".equals(origin.getPath()))) {
            throw new IllegalArgumentException("测试媒体来源必须是带端口的 loopback HTTP(S) origin");
        }
        return URI.create(normalizedScheme(origin) + "://" + authorityHost(origin) + ":" + origin.getPort());
    }

    static URI requireUriAtOrigin(String rawUrl, URI allowedOrigin) {
        URI uri = parse(rawUrl, "媒体下载地址非法");
        if (uri.getUserInfo() != null
                || uri.getFragment() != null
                || !normalizedScheme(uri).equals(normalizedScheme(allowedOrigin))
                || !normalizedHost(uri).equals(normalizedHost(allowedOrigin))
                || effectivePort(uri) != effectivePort(allowedOrigin)) {
            throw new IllegalArgumentException("企业微信媒体来源不受信任");
        }
        return uri;
    }

    static URI requireOfficialWebSocketUri(String rawUrl) {
        URI uri = parse(rawUrl, "企业微信长连接地址非法");
        if (!sameCanonicalUri(uri, OFFICIAL_WEBSOCKET_URI)) {
            throw new IllegalArgumentException("企业微信长连接仅允许官方 WSS 地址");
        }
        return OFFICIAL_WEBSOCKET_URI;
    }

    /** Package-only loopback seam used by RFC6455 tests; it is not Spring-bindable. */
    static URI requireLoopbackWebSocketUri(String rawUrl) {
        URI uri = parse(rawUrl, "测试 WebSocket 地址非法");
        if (!"ws".equalsIgnoreCase(uri.getScheme())
                || !isLoopbackLiteral(uri.getHost())
                || uri.getPort() < 1
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null) {
            throw new IllegalArgumentException("测试 WebSocket 地址必须是带端口的 ws://loopback 地址");
        }
        return uri;
    }

    private static URI parse(String rawUrl, String message) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        try {
            URI uri = URI.create(rawUrl.trim());
            if (!uri.isAbsolute() || uri.isOpaque() || uri.getHost() == null) {
                throw new IllegalArgumentException(message);
            }
            return uri;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(message);
        }
    }

    private static boolean sameCanonicalUri(URI actual, URI expected) {
        return normalizedScheme(actual).equals(normalizedScheme(expected))
                && normalizedHost(actual).equals(normalizedHost(expected))
                && effectivePort(actual) == effectivePort(expected)
                && actual.getUserInfo() == null
                && actual.getQuery() == null
                && actual.getFragment() == null
                && normalizedPath(actual).equals(normalizedPath(expected));
    }

    private static String normalizedScheme(URI uri) {
        return uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    }

    private static String normalizedHost(URI uri) {
        return normalizedHost(uri.getHost());
    }

    private static String normalizedHost(String host) {
        if (host == null) {
            return "";
        }
        String normalized = host.toLowerCase(Locale.ROOT);
        if (normalized.length() > 1 && normalized.startsWith("[") && normalized.endsWith("]")) {
            return normalized.substring(1, normalized.length() - 1);
        }
        return normalized;
    }

    private static String normalizedPath(URI uri) {
        String path = uri.getPath();
        return path == null || path.isEmpty() ? "/" : path;
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return switch (normalizedScheme(uri)) {
            case "https", "wss" -> 443;
            case "http", "ws" -> 80;
            default -> -1;
        };
    }

    private static boolean isLoopbackLiteral(String host) {
        String normalized = normalizedHost(host);
        return "localhost".equals(normalized) || "127.0.0.1".equals(normalized) || "::1".equals(normalized);
    }

    private static String authorityHost(URI uri) {
        String host = normalizedHost(uri);
        return host.contains(":") ? "[" + host + "]" : host;
    }
}
