package cn.zimu.fulfillment.fulfillment;

import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.idempotency.IdempotentResult;
import cn.zimu.fulfillment.common.web.CommandContext;
import cn.zimu.fulfillment.connector.jd.JDWarehouseService;
import cn.zimu.fulfillment.connector.jd.JdResult;
import cn.zimu.fulfillment.connector.jd.write.JdWriteOpsService;
import cn.zimu.fulfillment.fulfillment.JdShipmentSubmissionPlan.Blocker;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 京东出库外部调用单元：在业务事务之外执行实时 SKU/库存门禁、addSoOrder 与未决写对账查询，
 * 并把外部结果规整为 {@link SubmitExternalResult}，供编排单元在本地事务中原子归档。
 *
 * <p>本单元不落任何本地业务事实（对账审计除外，由审计单元独立事务记录），不持有业务事务；
 * 一切本地写与事务边界都属于编排单元。出库编排（{@link ShipmentJdOutboundService}）与本单元
 * 均单向依赖构造单元与审计单元，不再互相依赖。
 */
@Service
public class ShipmentJdOutboundExecutor {

    private static final Logger log = LoggerFactory.getLogger(ShipmentJdOutboundExecutor.class);

    private final ShipmentJdStockCheckService stockChecks;
    private final JdWriteOpsService jdWrite;
    private final JDWarehouseService jdWarehouse;
    private final ShipmentJdOutboundPreparer preparer;
    private final ShipmentJdOutboundAuditService audits;
    private final ObjectMapper objectMapper;

    public ShipmentJdOutboundExecutor(
            ShipmentJdStockCheckService stockChecks,
            JdWriteOpsService jdWrite,
            JDWarehouseService jdWarehouse,
            ShipmentJdOutboundPreparer preparer,
            ShipmentJdOutboundAuditService audits,
            ObjectMapper objectMapper) {
        this.stockChecks = stockChecks;
        this.jdWrite = jdWrite;
        this.jdWarehouse = jdWarehouse;
        this.preparer = preparer;
        this.audits = audits;
        this.objectMapper = objectMapper;
    }

    /** 第二阶段：实时 SKU/库存门禁与 addSoOrder 全部发生在业务事务之外。 */
    public SubmitExternalResult executeSubmit(
            SubmitIntent intent, String idempotencyKey, CommandContext context) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("JD outbound external phase must run outside a database transaction");
        }
        try {
            // A prior write with an indeterminate result is a monotonic recovery fact: reconcile it
            // before any fresh stock/preview validation can fail and overwrite the unresolved intent.
            // The original create may already have succeeded, so no other failure may make a second
            // addSoOrder attempt reachable.
            if (intent.requiresReconciliation()) {
                return reconcileUncertainSubmit(intent, context);
            }
            IdempotentResult<Map<String, Object>> checked = stockChecks.check(
                    intent.plan().shipmentId(),
                    "jd-submit-stock-" + ShipmentJdOutboundPreparer.sha256(idempotencyKey + ":" + intent.retryCount()
                            + ":" + intent.plan().requestHash()),
                    context);
            Map<String, Object> stock = checked.replayed()
                    ? objectMapper.convertValue(checked.replayedBody(), new TypeReference<>() {})
                    : checked.result();
            if (!"PASSED".equals(ShipmentJdOutboundPreparer.text(stock.get("stock_status")))) {
                return SubmitExternalResult.validationFailure(
                        "JD_STOCK_CHECK_BLOCKED", "京东实时库存判定未通过，出库单未创建");
            }
            if (!Objects.equals(intent.plan().requestHash(), ShipmentJdOutboundPreparer.text(stock.get("preview_hash")))) {
                return SubmitExternalResult.validationFailure(
                        "JD_STOCK_PREVIEW_CHANGED", "库存判定使用的预览已变化，出库单未创建");
            }
            JdShipmentSubmissionPlan immediate =
                    preparer.planInNewTransaction(intent.plan().shipmentId());
            if (immediate.shipmentVersion() != intent.plan().shipmentVersion()
                    || !Objects.equals(immediate.requestHash(), intent.plan().requestHash())) {
                return SubmitExternalResult.validationFailure(
                        "JD_SHIPMENT_OUTBOUND_PREVIEW_CHANGED", "发货批次在库存复查后已变化，出库单未创建");
            }
            if (!immediate.submittable()) {
                Blocker first = immediate.blockers().getFirst();
                return SubmitExternalResult.validationFailure(first.code(), first.message());
            }
            JdResult result;
            try {
                result = jdWrite.orderSoCreate(intent.plan().request());
            } catch (RuntimeException exception) {
                log.warn("JD orderSoCreate adapter failed for shipment {}", intent.plan().shipmentId());
                result = new JdResult(false, "SDK_CALL_FAILED", "京东出库单提交调用失败", null, null);
            }
            return SubmitExternalResult.submitted(result);
        } catch (BusinessException exception) {
            return SubmitExternalResult.validationFailure(exception.getBusinessCode(), exception.getMessage());
        } catch (RuntimeException exception) {
            log.warn("JD outbound pre-submit validation failed for shipment {}", intent.plan().shipmentId());
            return SubmitExternalResult.validationFailure(
                    "PRE_SUBMIT_CHECK_FAILED", "京东提交前复查失败，出库单未创建");
        }
    }

    /**
     * 未决外部写只能沿对账路径收敛；查询、解析或审计任一异常都保持单调的
     * {@code RECONCILIATION_REQUIRED}，绝不降级为可重新建单的普通预检失败。
     */
    private SubmitExternalResult reconcileUncertainSubmit(SubmitIntent intent, CommandContext context) {
        try {
            Map<String, Object> reconciliationRequest = Map.of(
                    "erpDeliveryNo", intent.plan().erpDeliveryNo(),
                    "deliveryItemFlag", 1,
                    "deliveryPackageFlag", 0,
                    "deliveryStatusFlag", 1);
            JdResult reconciliation;
            try {
                reconciliation = jdWarehouse.queryOutboundOrder(reconciliationRequest);
            } catch (RuntimeException exception) {
                reconciliation = null;
            }
            audits.recordReconciliationQuery(
                    intent.plan().shipmentId(),
                    intent.plan().orderId(),
                    context,
                    reconciliationRequest,
                    reconciliation,
                    reconciliation != null && extractDeliveryNo(reconciliation) != null,
                    reconciliation != null && Objects.equals(
                            intent.plan().erpDeliveryNo(), extractErpDeliveryNo(reconciliation)));
            if (reconciliation != null
                    && reconciliation.success()
                    && extractDeliveryNo(reconciliation) != null
                    && Objects.equals(
                            intent.plan().erpDeliveryNo(), extractErpDeliveryNo(reconciliation))) {
                return SubmitExternalResult.submitted(reconciliation);
            }
            return SubmitExternalResult.validationFailure(
                    "RECONCILIATION_REQUIRED",
                    "上次京东写结果不确定，按原 erpDeliveryNo 查询未确认结果，禁止再次创建");
        } catch (RuntimeException exception) {
            log.warn("JD outbound reconciliation failed for shipment {}", intent.plan().shipmentId());
            return SubmitExternalResult.validationFailure(
                    "RECONCILIATION_REQUIRED",
                    "上次京东写结果不确定，对账查询或审计未完成，禁止再次创建");
        }
    }

    /** 兼容 Mock（data.response.deliveryNo）与 REAL（data.deliveryNo）两种响应形状提取京东出库单号。 */
    public static String extractDeliveryNo(JdResult result) {
        if (result.data() instanceof Map<?, ?> data) {
            String direct = ShipmentJdOutboundPreparer.text(data.get("deliveryNo"));
            if (direct != null) {
                return direct;
            }
            Object nested = data.get("response");
            if (nested instanceof Map<?, ?> envelope) {
                return ShipmentJdOutboundPreparer.text(envelope.get("deliveryNo"));
            }
        }
        return null;
    }

    /** 成功建单必须同时回传并匹配商户侧稳定出库号。 */
    public static String extractErpDeliveryNo(JdResult result) {
        if (result.data() instanceof Map<?, ?> data) {
            String direct = ShipmentJdOutboundPreparer.text(data.get("erpDeliveryNo"));
            if (direct != null) {
                return direct;
            }
            Object nested = data.get("response");
            if (nested instanceof Map<?, ?> envelope) {
                return ShipmentJdOutboundPreparer.text(envelope.get("erpDeliveryNo"));
            }
        }
        return null;
    }

    /** 第二阶段的外部写意图：稳定提交计划 + 重试计数 + 客户端模式，单调不可变。 */
    public record SubmitIntent(
            JdShipmentSubmissionPlan plan,
            int retryCount,
            String clientMode) {

        public boolean requiresReconciliation() {
            return plan.priorSubmission() != null && plan.priorSubmission().requiresReconciliation();
        }
    }

    /** 外部阶段结果：accepted=true 表示外部写入已被接受（本地需原子归档成功事实）。 */
    public record SubmitExternalResult(
            boolean accepted,
            String failurePhase,
            String businessCode,
            String message,
            JdResult jdResult) {

        public static SubmitExternalResult validationFailure(String businessCode, String message) {
            return new SubmitExternalResult(false, "VALIDATION", businessCode, message, null);
        }

        public static SubmitExternalResult submitted(JdResult result) {
            return new SubmitExternalResult(true, null, null, null, result);
        }
    }
}
