package cn.zimu.fulfillment.connector.jd;

import java.util.Map;

/** 京东 ISC 七项能力的稳定应用边界；SDK DTO 不泄漏到业务用例。 */
public interface JDWarehouseService {

    JdResult queryOwners(Map<String, Object> request);

    JdResult queryWarehouses(Map<String, Object> request);

    JdResult queryProducts(Map<String, Object> request);

    JdResult queryStock(Map<String, Object> request);

    /** Legacy read-client surface; implementations fail closed. Use the controlled Shipment workflow. */
    JdResult createOutboundOrder(Map<String, Object> request);

    JdResult queryOutboundOrder(Map<String, Object> request);

    JdResult cancelOutboundOrder(Map<String, Object> request);

    JdResult queryTracking(Map<String, Object> request);
}
