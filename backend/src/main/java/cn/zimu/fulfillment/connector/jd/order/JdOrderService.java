package cn.zimu.fulfillment.connector.jd.order;

import cn.zimu.fulfillment.connector.jd.JdResult;
import java.util.Map;

/**
 * 京东 ISC 订单查询域的稳定应用边界；SDK DTO 不泄漏到业务用例。
 * 覆盖出库单号分页、调整单、销毁单、异常单、采购单、加工单、作业关联、配送时效与同城轨迹九项只读查询。
 */
public interface JdOrderService {

    /** 出库单号分页查询。 */
    JdResult queryOrderNosByPage(Map<String, Object> request);

    /** 调整单查询。 */
    JdResult queryAdjustment(Map<String, Object> request);

    /** 销毁单查询。 */
    JdResult queryDestroy(Map<String, Object> request);

    /** 异常单查询。 */
    JdResult queryException(Map<String, Object> request);

    /** 采购单查询。 */
    JdResult queryPurchase(Map<String, Object> request);

    /** 加工单查询。 */
    JdResult queryProcessed(Map<String, Object> request);

    /** 作业关联查询（外部单号换京东单号）。 */
    JdResult queryOperateRelation(Map<String, Object> request);

    /** 配送时效查询。 */
    JdResult queryDeliveryTime(Map<String, Object> request);

    /** 同城轨迹查询。 */
    JdResult queryCityTrack(Map<String, Object> request);
}
