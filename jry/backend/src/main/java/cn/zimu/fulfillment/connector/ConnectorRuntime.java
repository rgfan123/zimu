package cn.zimu.fulfillment.connector;

/** 测试连接所需的非密文运行配置。 */
public record ConnectorRuntime(
        String clientMode,
        String transportMode,
        boolean enabled,
        String endpoint,
        boolean credentialConfigured) {}
