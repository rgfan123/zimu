package cn.zimu.fulfillment.connector.jd.serial;

import cn.zimu.fulfillment.connector.jd.JdResult;
import java.util.Map;

/**
 * 京东 ISC 序列号查询域四类只读查询的稳定应用边界；SDK DTO 不泄漏到业务用例。
 *
 * <p>对应 LOP 接口：queryJDMallSerialByPage / queryPageSerialByOwnerNoAndCondition /
 * querySerialBySkuAndSerial / queryInStockSidBySku，全部只读，无副作用。
 */
public interface JdSerialService {

    /** 序列号查询：按订单/时间范围分页查询京东商城序列号（queryJDMallSerialByPage）。 */
    JdResult queryJdMallSerial(Map<String, Object> request);

    /** 序列号条件查询：按事业部、仓库、业务类型等条件分页查询序列号（queryPageSerialByOwnerNoAndCondition）。 */
    JdResult querySerialByCondition(Map<String, Object> request);

    /** 序列号流向查询：按商品编码 + 序列号查询出入库流向（querySerialBySkuAndSerial）。 */
    JdResult querySerialFlow(Map<String, Object> request);

    /** 序列号内部查询：按商品编码分页查询在库序列号（queryInStockSidBySku）。 */
    JdResult querySerialInside(Map<String, Object> request);
}
