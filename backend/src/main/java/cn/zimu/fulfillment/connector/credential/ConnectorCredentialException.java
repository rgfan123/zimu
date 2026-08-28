package cn.zimu.fulfillment.connector.credential;

/**
 * 渠道凭据解析/加解密失败。
 *
 * <p>businessCode 面向 connector_configs.last_error 与「测试连接」结果；消息只描述失败环节，
 * 绝不携带凭据值、密文或密钥。无堆栈、不可抑制，形状与各渠道 SessionException 一致。</p>
 */
public final class ConnectorCredentialException extends RuntimeException {

    private final String businessCode;

    public ConnectorCredentialException(String businessCode, String message) {
        super(message, null, false, false);
        this.businessCode = businessCode;
    }

    public String businessCode() {
        return businessCode;
    }
}
