package cn.zimu.fulfillment.connector.credential;

import cn.zimu.fulfillment.common.domain.SourceChannel;

/**
 * 渠道凭据解析 seam：连接器在<b>每次认证时</b>调用（绝不在构造期缓存结果），
 * 界面「渠道接入」保存的凭据优先，缺失时回退连接器自带的环境变量链。
 *
 * <p>渠道无关：任何 Connector 都可以带着自己的环境变量回退值接入；当前只有聚福宝接通。</p>
 */
@FunctionalInterface
public interface ConnectorCredentialsResolver {

    /** 解析后的凭据（不可变；null 归一为空串并去除首尾空白）。 */
    record ResolvedCredentials(String username, String password) {
        public ResolvedCredentials {
            username = username == null ? "" : username.trim();
            password = password == null ? "" : password.trim();
        }
    }

    /**
     * 解析渠道凭据；username/password 各自独立取「界面配置 → environmentFallback」。
     *
     * @throws ConnectorCredentialException 凭据存储不可读、加密密钥缺失/无效、密文损坏时
     */
    ResolvedCredentials resolve(SourceChannel channel, ResolvedCredentials environmentFallback);
}
