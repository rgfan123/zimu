package cn.zimu.fulfillment.connector;

public record ConnectorConfigView(
        String sourceChannel,
        String clientMode,
        String transportMode,
        boolean enabled,
        String endpoint,
        String username,
        boolean credentialConfigured,
        boolean passwordConfigured,
        long version) {}
