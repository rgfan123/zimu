package cn.zimu.fulfillment.followup;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Deployment-only configuration for the isolated, read-token Kehuzx MCP boundary. */
@Component
@ConfigurationProperties(prefix = "app.kehuzx.mcp")
public class KehuzxMcpProperties {

    private boolean enabled;
    private URI endpoint;
    private String readToken;
    private String allowedHost = "kehuzx-mcp";
    private int allowedPort = 9100;
    private Duration connectTimeout = Duration.ofSeconds(3);
    private Duration readTimeout = Duration.ofSeconds(10);
    private String contractVersion = "kehuzx-mcp-v1";
    private String upstreamCommit;

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
                && readToken != null
                && !readToken.isBlank();
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public URI getEndpoint() { return endpoint; }
    public void setEndpoint(URI endpoint) { this.endpoint = endpoint; }
    public String getReadToken() { return readToken; }
    public void setReadToken(String readToken) { this.readToken = readToken; }
    public String getAllowedHost() { return allowedHost; }
    public void setAllowedHost(String value) { allowedHost = normalize(value, "kehuzx-mcp"); }
    public int getAllowedPort() { return allowedPort; }
    public void setAllowedPort(int value) { allowedPort = value > 0 && value <= 65535 ? value : 9100; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration value) { connectTimeout = positive(value, Duration.ofSeconds(3)); }
    public Duration getReadTimeout() { return readTimeout; }
    public void setReadTimeout(Duration value) { readTimeout = positive(value, Duration.ofSeconds(10)); }
    public String getContractVersion() { return contractVersion; }
    public void setContractVersion(String value) { contractVersion = normalize(value, "kehuzx-mcp-v1"); }
    public String getUpstreamCommit() { return upstreamCommit; }
    public void setUpstreamCommit(String value) { upstreamCommit = normalize(value, null); }

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
}
