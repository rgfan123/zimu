package cn.zimu.fulfillment.rawmaterial;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 原料库存（yuanliaokc）只读网关的部署配置（票 07/08，spec unified-business-frontend D7）。
 *
 * <p>形状刻意与 {@code KehuzxMcpProperties} 同构：enabled + 端点 + 主机/端口白名单钉死 +
 * 专用只读凭据；{@link #isReady()} 是「raw-material-inventory 业务模块是否开放」的唯一判据
 * （见 {@code BusinessModuleAvailabilityService}），菜单可见与点进去能用不允许分叉。
 *
 * <p>与 kehuzx 的差异只有传输面：上游是普通 REST（FastAPI + OAuth2 password 登录），
 * 不是 MCP，因此凭据是用户名/口令而非静态 token；端点钉的是服务根（路径必须为空或 /），
 * 具体路径由客户端追加，防止把网关指向上游任意深层路径。
 */
@Component
@ConfigurationProperties(prefix = "app.raw-material.yuanliaokc")
public class YuanliaokcGatewayProperties {

    private boolean enabled;
    private URI endpoint;
    private String username;
    private String password;
    // 写通道（出入库 MCP）：独立开关 + 独立写账号。写账号绝不复用只读账号——
    // 上游按角色区分 require_writer/require_reviewer，读凭据天然无写权限，
    // 两套凭据分开配置才能保证「读通道永远无法升格成写」。默认关。
    private boolean writeEnabled;
    private String writeUsername;
    private String writePassword;
    private String allowedHost = "yuanliaokc-api";
    private int allowedPort = 9200;
    private Duration connectTimeout = Duration.ofSeconds(3);
    private Duration readTimeout = Duration.ofSeconds(10);

    public boolean isReady() {
        return enabled
                && endpoint != null
                && ("http".equals(endpoint.getScheme()) || "https".equals(endpoint.getScheme()))
                && endpoint.getUserInfo() == null
                && endpoint.getFragment() == null
                && endpoint.getQuery() == null
                && endpoint.getHost() != null
                && endpoint.getHost().equalsIgnoreCase(allowedHost)
                && effectivePort(endpoint) == allowedPort
                && (endpoint.getPath() == null || endpoint.getPath().isEmpty() || "/".equals(endpoint.getPath()))
                && username != null
                && !username.isBlank()
                && password != null
                && !password.isBlank();
    }

    /**
     * 写通道是否就绪：在 {@link #isReady()}（端点/主机/端口钉死 + 读凭据齐全）的基础上，
     * 还要求显式打开写开关且写凭据齐全。任何一项缺失都视为「本部署未开放写」，
     * 写客户端 fail-closed 且不发包——与读通道的 NOT_CONFIGURED 同一哲学。
     */
    public boolean isWriteReady() {
        return isReady()
                && writeEnabled
                && writeUsername != null
                && !writeUsername.isBlank()
                && writePassword != null
                && !writePassword.isBlank();
    }

    private static int effectivePort(URI endpoint) {
        if (endpoint.getPort() != -1) {
            return endpoint.getPort();
        }
        return "https".equals(endpoint.getScheme()) ? 443 : 80;
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public URI getEndpoint() { return endpoint; }
    public void setEndpoint(URI endpoint) { this.endpoint = endpoint; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public boolean isWriteEnabled() { return writeEnabled; }
    public void setWriteEnabled(boolean writeEnabled) { this.writeEnabled = writeEnabled; }
    public String getWriteUsername() { return writeUsername; }
    public void setWriteUsername(String writeUsername) { this.writeUsername = writeUsername; }
    public String getWritePassword() { return writePassword; }
    public void setWritePassword(String writePassword) { this.writePassword = writePassword; }
    public String getAllowedHost() { return allowedHost; }
    public void setAllowedHost(String value) { allowedHost = normalize(value, "yuanliaokc-api"); }
    public int getAllowedPort() { return allowedPort; }
    public void setAllowedPort(int value) { allowedPort = value > 0 && value <= 65535 ? value : 9200; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration value) { connectTimeout = positiveOr(value, Duration.ofSeconds(3)); }
    public Duration getReadTimeout() { return readTimeout; }
    public void setReadTimeout(Duration value) { readTimeout = positiveOr(value, Duration.ofSeconds(10)); }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static Duration positiveOr(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }
}
