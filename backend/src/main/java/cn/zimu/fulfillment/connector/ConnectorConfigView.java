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
        /** 历史明文密码残留（不使用、不迁移）：界面提示重新输入，下一次保存会清除。 */
        boolean passwordNeedsReentry,
        long version) {}
