package cn.zimu.fulfillment.fulfillment;

import cn.zimu.fulfillment.common.error.BusinessException;
import java.util.Map;
import java.util.Objects;

/**
 * 只能由 Shipment 京东出库编排从已预检计划生成的适配器 capability。
 *
 * <p>构造入口故意保持 package-private，并且接收同包私有的
 * {@link JdShipmentSubmissionPlan}；connector 适配器只能消费该对象，不能从任意 Map
 * 伪造一次 addSoOrder 调用。
 */
public final class PreparedJdSalesOutbound {

    private final Map<String, Object> request;

    private PreparedJdSalesOutbound(Map<String, Object> request) {
        this.request = Objects.requireNonNull(request, "request");
    }

    static PreparedJdSalesOutbound from(JdShipmentSubmissionPlan plan) {
        Objects.requireNonNull(plan, "plan");
        if (!JdErpDeliveryNoAllocator.belongsToOwnedNamespace(plan.erpDeliveryNo())) {
            throw BusinessException.conflict(
                    "JD_ERP_DELIVERY_NO_NAMESPACE_REQUIRED",
                    "京东外部单号不在 ZIMU-SO 独占命名空间，禁止创建出库单");
        }
        return new PreparedJdSalesOutbound(plan.request());
    }

    public Map<String, Object> request() {
        return request;
    }
}
