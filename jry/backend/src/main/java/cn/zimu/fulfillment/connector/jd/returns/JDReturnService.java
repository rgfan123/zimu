package cn.zimu.fulfillment.connector.jd.returns;

import cn.zimu.fulfillment.connector.jd.JdResult;
import java.util.Map;

/** 京东 ISC 退货退供查询域（只读）的稳定应用边界；SDK DTO 不泄漏到业务用例。 */
public interface JDReturnService {

    /** 退货入库单列表。 */
    JdResult queryRtwOrderList(Map<String, Object> request);

    /** 退货入库单详情。 */
    JdResult queryRtwOrderDetail(Map<String, Object> request);

    /** 退供单查询。 */
    JdResult queryReturnToSupplier(Map<String, Object> request);
}
