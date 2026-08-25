package cn.zimu.fulfillment.file;

import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.connector.PlatformConnector;
import cn.zimu.fulfillment.connector.sync.PlatformConnectorRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 来源回填文件的企微投递扫描器。
 *
 * <p>只投递**没有在线回传能力**的渠道（{@code ConnectorCapabilities.onlinePush=false}：
 * 飞象 / 大者 / 中汇）。有在线回传的（彩食鲜 / 聚福宝）应当走
 * {@link SourceReturnPushService} 直接回传平台——把文件发给人再让人手动转交，
 * 是把一条已自动化的路退回人工，属于倒退。
 *
 * <p>目标会话来自履约方配置的企微群（{@code fulfillment_providers.config.wecomGroupChatId}）。
 * 未配置即不发并按 INFO 记录——没配群是部署选择不是故障，与业务卡片同一纪律。
 */
@Component
public class SourceReturnWecomScanner {

    private static final Logger log = LoggerFactory.getLogger(SourceReturnWecomScanner.class);

    private final SourceReturnWecomDeliveryService delivery;
    private final PlatformConnectorRegistry connectors;
    private final SourceReturnWecomRouteResolver routes;
    private final boolean enabled;
    private final int batchSize;

    public SourceReturnWecomScanner(
            SourceReturnWecomDeliveryService delivery,
            PlatformConnectorRegistry connectors,
            SourceReturnWecomRouteResolver routes,
            @Value("${app.source-return-wecom.enabled:${WECOM_ENABLED:false}}") boolean enabled,
            @Value("${app.source-return-wecom.batch-size:10}") int batchSize) {
        this.delivery = delivery;
        this.connectors = connectors;
        this.routes = routes;
        this.enabled = enabled;
        this.batchSize = Math.max(1, Math.min(batchSize, 50));
    }

    @Scheduled(fixedDelayString = "${app.source-return-wecom.scan-ms:30000}")
    public void scan() {
        if (!enabled) {
            return;
        }
        for (SourceReturnWecomDeliveryService.Candidate candidate : delivery.pending(batchSize)) {
            try {
                if (hasOnlinePush(candidate.sourceChannel())) {
                    // 有在线回传的渠道不该走企微；标记跳过避免每轮重扫
                    delivery.fail(candidate.exportId(), "CHANNEL_HAS_ONLINE_PUSH", false);
                    continue;
                }
                String chatId = routes.chatIdFor(candidate.importBatchId());
                if (chatId == null || chatId.isBlank()) {
                    log.info("未配置企微会话路由，跳过来源回填投递 export={}", candidate.exportId());
                    continue;
                }
                if (delivery.claim(candidate.exportId())) {
                    delivery.deliver(candidate, chatId);
                }
            } catch (RuntimeException exception) {
                log.warn(
                        "来源回填投递扫描异常 export={} type={}",
                        candidate.exportId(),
                        exception.getClass().getSimpleName());
            }
        }
    }

    /** 渠道枚举可能是历史技术值；解析不出来时按「不投递」处理,不猜。 */
    private boolean hasOnlinePush(String sourceChannel) {
        try {
            PlatformConnector connector = connectors.require(SourceChannel.valueOf(sourceChannel));
            return connector.capabilities().onlinePush();
        } catch (RuntimeException exception) {
            // 含 IllegalArgumentException(未知枚举值)与注册表缺失
            return false;
        }
    }
}
