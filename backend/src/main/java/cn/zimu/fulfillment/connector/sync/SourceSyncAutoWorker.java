package cn.zimu.fulfillment.connector.sync;

import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.web.CommandContext;
import cn.zimu.fulfillment.connector.ConnectorCapabilities;
import cn.zimu.fulfillment.connector.PlatformConnector;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
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
    private static final Set<String> TRANSIENT_UNAVAILABLE_CODES = Set.of(
            "SOURCE_SYNC_CONNECTOR_DISABLED",
            "SOURCE_SYNC_ONLINE_TRANSPORT_REQUIRED",
            "SOURCE_SYNC_CONNECTOR_CAPABILITY_UNAVAILABLE",
            "SOURCE_PLATFORM_CHECK_UNAVAILABLE",
            "SOURCE_PLATFORM_CHECK_FAILED");

    private final SourceShipmentSyncService service;
    private final PlatformConnectorRegistry connectors;
    private final SourceSyncAutoStateStore states;
    private final boolean enabled;
    private final String operator;
    private final int batchSize;
    private final Duration retryInitial;
    private final Duration retryMax;
    private final Duration blockedRecheck;
    private final Duration claimLease;
    private final String leaseOwner = "source-sync-auto-" + UUID.randomUUID();

    public SourceSyncAutoWorker(
            SourceShipmentSyncService service,
            PlatformConnectorRegistry connectors,
            SourceSyncAutoStateStore states,
            @Value("${app.source-sync.auto.enabled:false}") boolean enabled,
            @Value("${app.source-sync.auto.operator:}") String operator,
            @Value("${app.source-sync.auto.batch-size:20}") int batchSize,
            @Value("${app.source-sync.auto.retry-initial:PT2M}") Duration retryInitial,
            @Value("${app.source-sync.auto.retry-max:PT1H}") Duration retryMax,
            @Value("${app.source-sync.auto.blocked-recheck:PT10M}") Duration blockedRecheck,
            @Value("${app.source-sync.auto.claim-lease:PT10M}") Duration claimLease) {
        this.service = service;
        this.connectors = connectors;
        this.states = states;
        this.operator = operator == null ? "" : operator.trim();
        // 开关为真但没配服务账号 → 不启动。宁可不跑，也不拿一个猜来的身份往客户平台写。
        this.enabled = enabled && !this.operator.isBlank();
        this.batchSize = Math.max(1, Math.min(batchSize, 200));
        this.retryInitial = retryInitial;
        this.retryMax = retryMax;
        this.blockedRecheck = blockedRecheck;
        this.claimLease = claimLease;
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
        List<SourceSyncAutoStateStore.Claim> candidates =
                states.claimCandidates(leaseOwner, claimLease, batchSize);
        for (SourceSyncAutoStateStore.Claim candidate : candidates) {
            try {
                CapabilityDecision capability = runtimeCapability(candidate);
                if (!capability.onlinePush()) {
                    states.recordNotApplicable(candidate, capability.reasonCode());
                    continue;
                }
                syncOne(candidate);
            } catch (BusinessException business) {
                if (isTransientUnavailable(business.getBusinessCode())) {
                    retryLater(candidate, business.getBusinessCode(), null);
                } else {
                    states.defer(candidate, business.getBusinessCode(), blockedRecheck);
                }
            } catch (RuntimeException exception) {
                retryLater(candidate, "SOURCE_SYNC_AUTO_UNEXPECTED", exception);
            }
        }
    }

    private void syncOne(SourceSyncAutoStateStore.Claim candidate) {
        CommandContext context = context();
        // 审计主体记 SYSTEM 而不是 HUMAN：自动执行确实不是人点的，写成 HUMAN 会污染
        // 「谁做的」这一事实。actorType 传 null 会在审计写入处炸成非业务异常。
        SourceSyncCheck check = service.check(candidate.shipmentId(), context, AuditActorType.SYSTEM);
        if (!check.ready()) {
            Optional<String> unavailable = transientUnavailable(check);
            if (unavailable.isPresent()) {
                retryLater(candidate, unavailable.get(), null);
            } else {
                // 其他业务门禁仍由既有复核闭环处理，不把它伪装成 connector 故障。
                String reason = check.blockers().stream()
                        .map(SourceSyncBlocker::code)
                        .findFirst()
                        .orElse("SOURCE_SYNC_CHECK_BLOCKED");
                states.defer(candidate, reason, blockedRecheck);
            }
            return;
        }
        service.execute(
                candidate.shipmentId(),
                new SourceSyncExecuteCommand(check.checkHash()),
                // 幂等键绑定 check-hash：事实变化就是新的一次意图，事实不变则天然去重
                "source-sync-auto-" + candidate.shipmentId() + "-" + check.checkHash(),
                context);
        states.complete(candidate);
        log.info("自动回传已执行 shipment={}", candidate.shipmentId());
    }

    /** 与平台拉取的 runtimeCapability 同类：能力判定先于 service.check 与任何外呼。 */
    private CapabilityDecision runtimeCapability(SourceSyncAutoStateStore.Claim candidate) {
        Optional<PlatformConnector> connector = connectors.find(candidate.sourceChannel());
        if (connector.isEmpty()) {
            return new CapabilityDecision(
                    false,
                    "EXCEL".equals(candidate.transportMode())
                            ? SourceSyncAutoStateStore.FILE_RETURN_ONLY
                            : SourceSyncAutoStateStore.ONLINE_PUSH_NOT_APPLICABLE);
        }
        ConnectorCapabilities capabilities = connector.get().capabilities();
        if (capabilities != null && capabilities.onlinePush()) {
            return new CapabilityDecision(true, null);
        }
        return new CapabilityDecision(
                false,
                capabilities != null && capabilities.fileExport()
                        ? SourceSyncAutoStateStore.FILE_RETURN_ONLY
                        : SourceSyncAutoStateStore.ONLINE_PUSH_NOT_APPLICABLE);
    }

    private Optional<String> transientUnavailable(SourceSyncCheck check) {
        return check.blockers().stream()
                .map(SourceSyncBlocker::code)
                .filter(SourceSyncAutoWorker::isTransientUnavailable)
                .findFirst();
    }

    private static boolean isTransientUnavailable(String code) {
        return code != null && TRANSIENT_UNAVAILABLE_CODES.contains(code);
    }

    private void retryLater(
            SourceSyncAutoStateStore.Claim candidate,
            String reasonCode,
            RuntimeException unexpected) {
        SourceSyncAutoStateStore.State state = states.scheduleRetry(
                candidate,
                reasonCode,
                retryInitial,
                retryMax);
        if (unexpected != null) {
            log.warn(
                    "自动回传暂不可用 shipment={} code={} attempt={} next_attempt_at={} type={} message={}",
                    candidate.shipmentId(),
                    state.reasonCode(),
                    state.attemptCount(),
                    state.nextAttemptAt(),
                    unexpected.getClass().getSimpleName(),
                    unexpected.getMessage(),
                    unexpected);
        } else {
            log.info(
                    "自动回传暂不可用 shipment={} code={} attempt={} next_attempt_at={}",
                    candidate.shipmentId(),
                    state.reasonCode(),
                    state.attemptCount(),
                    state.nextAttemptAt());
        }
    }

    /** authenticatedOperator 与 operator 同值，满足服务端身份一致校验（见类注释的取舍说明）。 */
    private CommandContext context() {
        String requestId = "auto-source-sync-" + UUID.randomUUID();
        return new CommandContext(requestId, requestId, operator, operator);
    }

    private record CapabilityDecision(boolean onlinePush, String reasonCode) {}
}
