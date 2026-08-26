package cn.zimu.fulfillment.followup;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Deployment-only credentials and network policy for the deterministic Kehuzx writer. */
@Component
@ConfigurationProperties(prefix = "app.kehuzx.mcp-write")
public class KehuzxMcpWriteProperties {

    private boolean enabled;
    private URI endpoint;
    private String writeToken;
    private String approvalSigningKey;
    private String allowedHost = "kehuzx-mcp";
    private int allowedPort = 9100;
    private Duration connectTimeout = Duration.ofSeconds(3);
    private Duration readTimeout = Duration.ofSeconds(10);
    private int maxResponseBytes = 1_048_576;
    private int approvalTtlSeconds = 120;

    public boolean isReady() {
        return enabled
                && endpoint != null
                && ("http".equals(endpoint.getScheme()) || "https".equals(endpoint.getScheme()))
                && endpoint.getUserInfo() == null
                && endpoint.getFragment() == null
                && endpoint.getHost() != null
                && endpoint.getHost().equalsIgnoreCase(allowedHost)
                && effectivePort(endpoint) == allowedPort
                && "/mcp".equals(endpoint.getPath())
                && endpoint.getQuery() == null
                && strong(writeToken)
                && strong(approvalSigningKey)
                && !MessageDigest.isEqual(
                        writeToken.getBytes(StandardCharsets.UTF_8),
                        approvalSigningKey.getBytes(StandardCharsets.UTF_8));
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public URI getEndpoint() { return endpoint; }
    public void setEndpoint(URI endpoint) { this.endpoint = endpoint; }
    public String getWriteToken() { return writeToken; }
    public void setWriteToken(String writeToken) { this.writeToken = writeToken; }
    public String getApprovalSigningKey() { return approvalSigningKey; }
    public void setApprovalSigningKey(String approvalSigningKey) { this.approvalSigningKey = approvalSigningKey; }
    public String getAllowedHost() { return allowedHost; }
    public void setAllowedHost(String value) { allowedHost = normalize(value, "kehuzx-mcp"); }
    public int getAllowedPort() { return allowedPort; }
    public void setAllowedPort(int value) { allowedPort = value > 0 && value <= 65535 ? value : 9100; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration value) { connectTimeout = positive(value, Duration.ofSeconds(3)); }
    public Duration getReadTimeout() { return readTimeout; }
    public void setReadTimeout(Duration value) { readTimeout = positive(value, Duration.ofSeconds(10)); }
    public int getMaxResponseBytes() { return maxResponseBytes; }
    public void setMaxResponseBytes(int value) {
        maxResponseBytes = value > 0 && value <= 4_194_304 ? value : 1_048_576;
    }
    public int getApprovalTtlSeconds() { return approvalTtlSeconds; }
    public void setApprovalTtlSeconds(int value) {
        approvalTtlSeconds = value > 0 && value <= 300 ? value : 120;
    }

    private static Duration positive(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() > 0) {
            return uri.getPort();
        }
        return "https".equals(uri.getScheme()) ? 443 : 80;
    }

    private static boolean strong(String value) {
        return value != null
                && !value.isBlank()
                && value.getBytes(StandardCharsets.UTF_8).length >= 32;
    }
}
