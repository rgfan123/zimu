package cn.zimu.fulfillment.connector.wecom;

import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.sku.FulfillmentProvider;
import cn.zimu.fulfillment.sku.FulfillmentProviderRepository;
import cn.zimu.fulfillment.sku.FulfillmentProviderWecomConfig;
import org.springframework.stereotype.Component;

/**
 * 履约方 → 企微群 chatid 解析 seam（Issue #83，供 #84 发送消费）。
 *
 * <p>每次调用实时读库（无缓存），配置修改后下一次解析立即生效，无需重启。未登记/已清除时
 * 抛出明确可操作的业务错误（包含「请在履约方配置登记企微群」），不静默返回空；错误信息
 * 不携带其他 config 或密钥。
 */
@Component
public class WecomGroupChatResolver {

    private final FulfillmentProviderRepository providers;

    WecomGroupChatResolver(FulfillmentProviderRepository providers) {
        this.providers = providers;
    }

    /** 按履约方 ID 解析企微群 chatid；未登记/已清除时抛 {@link BusinessException}。 */
    public String resolve(long providerId) {
        FulfillmentProvider provider = providers.findById(providerId)
                .orElseThrow(() -> BusinessException.notFound("履约方不存在"));
        return FulfillmentProviderWecomConfig.requireGroupChatId(provider.getConfig(), provider.getProviderCode());
    }
}
