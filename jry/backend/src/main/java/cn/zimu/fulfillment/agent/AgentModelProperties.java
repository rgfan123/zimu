package cn.zimu.fulfillment.agent;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * {@code app.agent.*} 模型传输配置（agent-decision-layer 01）。
 *
 * <p>与 {@code app.message-interpreter.*} 同构：api-key 只经环境变量注入，绝不在 yml/日志/DTO
 * 中出现明文密钥。{@link #configured()} 为 false 时运行时 fail-closed（不连接任何模型）。
 * 基础运行时的提示词版本使用固定值（{@code LangChain4jAgentRuntime.PROMPT_VERSION}），
 * 业务 Agent 的提示词版本由 02 票 Agent 注册表定义，不在此配置。
 */
@Component
@ConfigurationProperties(prefix = "app.agent")
public class AgentModelProperties {

    private String baseUrl = "";
    private String apiKey = "";
    private String provider = "";
    private String model = "";
    private long requestTimeoutMs = 30_000;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.strip();
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey == null ? "" : apiKey;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider == null ? "" : provider.strip();
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model == null ? "" : model.strip();
    }

    public long getRequestTimeoutMs() {
        return requestTimeoutMs;
    }

    public void setRequestTimeoutMs(long requestTimeoutMs) {
        this.requestTimeoutMs = requestTimeoutMs;
    }

    /** 传输四元组（base-url/api-key/provider/model）齐全才视为已配置。 */
    public boolean configured() {
        return !baseUrl.isBlank()
                && !apiKey.isBlank()
                && !provider.isBlank()
                && !model.isBlank();
    }
}
