package cn.zimu.fulfillment.fulfillment;

import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.connector.jd.JDWarehouseService;
import cn.zimu.fulfillment.connector.jd.JdResult;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 在 addSoOrder 写意图落盘前，按所选履约方的 pin 与 ownerNo 在同一京东事业部查询候选号。
 *
 * <p>只有官方 {@code 2342=订单不存在} 或成功空响应能够证明候选号空闲；超时、权限错误、
 * 异常响应都 fail closed。命中历史单时只替换尚未产生不确定外部效果的本地保留号，再查新号。
 */
@Service
class ShipmentJdErpDeliveryNoPreflight {

    private static final int MAX_EXTERNAL_COLLISIONS = 16;
    private static final String JD_ORDER_NOT_FOUND = "2342";

    private final JDWarehouseService jdWarehouse;
    private final JdErpDeliveryNoAllocator allocator;
    private final ShipmentJdOutboundPreparer preparer;

    ShipmentJdErpDeliveryNoPreflight(
            JDWarehouseService jdWarehouse,
            JdErpDeliveryNoAllocator allocator,
            ShipmentJdOutboundPreparer preparer) {
        this.jdWarehouse = jdWarehouse;
        this.allocator = allocator;
        this.preparer = preparer;
    }

    JdShipmentSubmissionPlan prepare(JdShipmentSubmissionPlan initial) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("JD erpDeliveryNo preflight must run outside a database transaction");
        }
        JdShipmentSubmissionPlan current = initial;
        if (!ShipmentJdOutboundPreparer.JD_WAREHOUSE.equals(current.providerType())) {
            return current;
        }
        if (requiresReconciliation(current)) {
            return current;
        }
        if (!current.submittable() || alreadySubmitted(current)) {
            // Local blockers must win before any remote lookup (missing pin included).
            return current;
        }
        if (!retryFactsMatch(current)) {
            // Keep the original reference long enough for persistSubmitIntent to return the
            // established REQUEST_CHANGED diagnosis. A read-only preflight must not erase it.
            return current;
        }

        for (int collision = 0; collision < MAX_EXTERNAL_COLLISIONS; collision++) {
            if (!JdErpDeliveryNoAllocator.belongsToOwnedNamespace(current.erpDeliveryNo())) {
                current = replaceAndReload(initial, current);
                if (requiresReconciliation(current) || alreadySubmitted(current)) {
                    return current;
                }
                if (!JdErpDeliveryNoAllocator.belongsToOwnedNamespace(current.erpDeliveryNo())) {
                    throw BusinessException.conflict(
                            "JD_ERP_DELIVERY_NO_NAMESPACE_REQUIRED",
                            "京东外部单号不在 ZIMU-SO 独占命名空间，禁止创建出库单");
                }
            }

            Availability availability = queryAvailability(current);
            if (availability == Availability.AVAILABLE) {
                return current;
            }
            current = replaceAndReload(initial, current);
            if (requiresReconciliation(current) || alreadySubmitted(current)) {
                return current;
            }
        }
        throw BusinessException.conflict(
                "JD_ERP_DELIVERY_NO_COLLISION_EXHAUSTED",
                "京东外部单号连续碰撞，未调用 addSoOrder，请联系运维检查命名空间");
    }

    private Availability queryAvailability(JdShipmentSubmissionPlan plan) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("erpDeliveryNo", plan.erpDeliveryNo());
        request.put("ownerNo", plan.ownerNo());
        request.put("pin", plan.pin());
        request.put("deliveryItemFlag", 0);
        request.put("deliveryPackageFlag", 0);
        request.put("deliveryStatusFlag", 0);

        JdResult result;
        try {
            result = jdWarehouse.queryOutboundOrder(request);
        } catch (RuntimeException exception) {
            throw unavailable("SDK_CALL_FAILED");
        }
        if (result == null) {
            throw unavailable("EMPTY_RESPONSE");
        }
        String businessCode = ShipmentJdOutboundPreparer.text(result.businessCode());
        if (!result.success()) {
            if (JD_ORDER_NOT_FOUND.equals(businessCode)) {
                return Availability.AVAILABLE;
            }
            throw unavailable(businessCode == null ? "UNKNOWN" : businessCode);
        }

        String returnedErpDeliveryNo = ShipmentJdOutboundExecutor.extractErpDeliveryNo(result);
        String returnedDeliveryNo = ShipmentJdOutboundExecutor.extractDeliveryNo(result);
        if (returnedErpDeliveryNo == null && returnedDeliveryNo == null) {
            if (emptyResponseData(result.data())) {
                return Availability.AVAILABLE;
            }
            throw new BusinessException(
                    502,
                    "JD_ERP_DELIVERY_NO_PREFLIGHT_INCONSISTENT",
                    "京东候选号查询成功但响应结构无法证明号码空闲，禁止调用 addSoOrder");
        }
        if (returnedErpDeliveryNo != null
                && !Objects.equals(plan.erpDeliveryNo(), returnedErpDeliveryNo)) {
            throw new BusinessException(
                    502,
                    "JD_ERP_DELIVERY_NO_PREFLIGHT_INCONSISTENT",
                    "京东候选号查询返回了另一商家单号，禁止调用 addSoOrder");
        }
        return Availability.COLLISION;
    }

    private JdShipmentSubmissionPlan replaceAndReload(
            JdShipmentSubmissionPlan initial,
            JdShipmentSubmissionPlan current) {
        allocator.replaceSafeCandidate(current.shipmentId(), current.erpDeliveryNo());
        JdShipmentSubmissionPlan revised = preparer.planInNewTransaction(current.shipmentId());
        if (revised.shipmentVersion() != initial.shipmentVersion()
                || !Objects.equals(revised.businessFactsHash(), initial.businessFactsHash())) {
            throw BusinessException.conflict(
                    "JD_SHIPMENT_OUTBOUND_PREVIEW_CHANGED",
                    "京东外部单号查重期间发货事实已变化，请刷新预览后重试");
        }
        return revised;
    }

    private boolean requiresReconciliation(JdShipmentSubmissionPlan plan) {
        return plan.priorSubmission() != null && plan.priorSubmission().requiresReconciliation();
    }

    private boolean alreadySubmitted(JdShipmentSubmissionPlan plan) {
        return plan.priorSubmission() != null
                && ShipmentJdOutboundPreparer.SYNC_STATUS_SUBMITTED.equals(
                        plan.priorSubmission().syncStatus());
    }

    private boolean retryFactsMatch(JdShipmentSubmissionPlan plan) {
        JdShipmentSubmissionPlan.PriorSubmission previous = plan.priorSubmission();
        if (previous == null) {
            return true;
        }
        if (previous.businessFactsHash() != null) {
            return Objects.equals(previous.businessFactsHash(), plan.businessFactsHash());
        }
        if (previous.requestHash() == null) {
            return true;
        }
        return Objects.equals(previous.requestHash(), plan.requestHash());
    }

    private BusinessException unavailable(String code) {
        return new BusinessException(
                502,
                "JD_ERP_DELIVERY_NO_PREFLIGHT_UNAVAILABLE",
                "京东外部单号查重失败（" + code + "），未调用 addSoOrder");
    }

    private boolean emptyResponseData(Object data) {
        if (data == null) {
            return true;
        }
        if (!(data instanceof Map<?, ?> values)) {
            return false;
        }
        if (values.containsKey("response")) {
            Object response = values.get("response");
            return response == null || response instanceof Map<?, ?> map && map.isEmpty();
        }
        return values.isEmpty();
    }

    private enum Availability {
        AVAILABLE,
        COLLISION
    }
}
