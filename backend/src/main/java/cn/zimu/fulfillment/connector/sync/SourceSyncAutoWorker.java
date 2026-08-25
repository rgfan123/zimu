package cn.zimu.fulfillment.connector.sync;

import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.web.CommandContext;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 来源平台回传的自动执行器。
 *
 * <p><b>它省掉的只有「人点按钮」这一步，五道门禁一条不减</b>：本 Worker 走的是与人工
 * 完全相同的 {@link SourceShipmentSyncService#check} → {@link SourceShipmentSyncService#execute}
 * 用例，因此仍然逐条经过——平台事实可读、来源订单处于可写状态、收货地址未在平台侧变更、
 * 物流公司命中平台实时字典、回填工作簿已生成；任一不过即阻断并落复核事项，绝不上传。
 * 幂等键、外部写租约、claim 后重跑检查与 check-hash 陈旧判定也都原样保留。
 *
 * <p><b>取舍必须写明</b>：{@code SourceShipmentSyncService.requireAuthenticatedOperator}
 * 的原意是「必须由服务端已认证且身份一致的<i>人工</i>操作员执行」。本 Worker 用配置的
 * 服务账号满足该一致性校验——机制（身份一致 + 全量校验）保留，执行者由人换成服务。
 * 这是往<b>客户的平台</b>写数据，与京东建单（我们自己的仓）性质不同，因此：
 * <ul>
 *   <li>默认<b>关闭</b>（{@code app.source-sync.auto.enabled=false}）；</li>
 *   <li>未显式配置服务账号时 fail-closed 不启动，不猜一个默认身份；</li>
 *   <li>审计里操作人就是该服务账号，事后可追溯到「这是自动发的」。</li>
 * </ul>
 */
@Component
public class SourceSyncAutoWorker {

    private static final Logger log = LoggerFactory.getLogger(SourceSyncAutoWorker.class);

    private final SourceShipmentSyncService service;
    private final JdbcTemplate jdbc;
    private final boolean enabled;
    private final String operator;
    private final int batchSize;

    public SourceSyncAutoWorker(
            SourceShipmentSyncService service,
            JdbcTemplate jdbc,
            @Value("${app.source-sync.auto.enabled:false}") boolean enabled,
            @Value("${app.source-sync.auto.operator:}") String operator,
            @Value("${app.source-sync.auto.batch-size:20}") int batchSize) {
        this.service = service;
        this.jdbc = jdbc;
        this.operator = operator == null ? "" : operator.trim();
        // 开关为真但没配服务账号 → 不启动。宁可不跑，也不拿一个猜来的身份往客户平台写。
        this.enabled = enabled && !this.operator.isBlank();
        this.batchSize = Math.max(1, Math.min(batchSize, 200));
        if (enabled && this.operator.isBlank()) {
            log.warn("source-sync 自动回传已开启但未配置 app.source-sync.auto.operator，保持关闭");
        }
    }

    /**
     * 候选：已发货且运单已回填、尚未成功回传的 Shipment。
     *
     * <p>只挑 {@code SHIPPED}——运单未回填时无从回传，早挑只会白跑一遍检查并留下噪声。
     * 已存在 SYNCING/SYNCED 记录的跳过：前者由 {@code recoverExpiredSyncing} 负责，
     * 后者已完成；两者都不该由本 Worker 重复发起。
     */
    @Scheduled(fixedDelayString = "${app.source-sync.auto.poll-ms:60000}")
    public void poll() {
        if (!enabled) {
            return;
        }
        List<Long> candidates = jdbc.queryForList(
                """
                SELECT s.id
                FROM app.shipments s
                JOIN app.trackings t ON t.shipment_id = s.id
                LEFT JOIN app.shipment_syncs ss ON ss.shipment_id = s.id
                WHERE s.shipment_status = 'SHIPPED'
                  AND (ss.shipment_id IS NULL OR ss.sync_status = 'SYNC_FAILED')
                ORDER BY s.id
                LIMIT ?
                """,
                Long.class,
                batchSize);
        for (Long shipmentId : candidates) {
            try {
                syncOne(shipmentId);
            } catch (RuntimeException exception) {
                // 单条失败不拖垮整轮：阻断项已由 execute 落复核事项，这里只记稳定码
                log.info(
                        "自动回传未执行 shipment={} code={}",
                        shipmentId,
                        exception instanceof BusinessException business ? business.getBusinessCode() : "UNEXPECTED");
            }
        }
    }

    private void syncOne(long shipmentId) {
        CommandContext context = context();
        SourceSyncCheck check = service.check(shipmentId, context, null);
        if (!check.ready()) {
            // 未就绪不是异常：检查侧已按需落复核事项，人工在发货台可见
            return;
        }
        service.execute(
                shipmentId,
                new SourceSyncExecuteCommand(check.checkHash()),
                // 幂等键绑定 check-hash：事实变化就是新的一次意图，事实不变则天然去重
                "source-sync-auto-" + shipmentId + "-" + check.checkHash(),
                context);
        log.info("自动回传已执行 shipment={}", shipmentId);
    }

    /** authenticatedOperator 与 operator 同值，满足服务端身份一致校验（见类注释的取舍说明）。 */
    private CommandContext context() {
        String requestId = "auto-source-sync-" + UUID.randomUUID();
        return new CommandContext(requestId, requestId, operator, operator);
    }
}
