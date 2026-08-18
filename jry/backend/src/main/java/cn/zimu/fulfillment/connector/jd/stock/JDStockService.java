package cn.zimu.fulfillment.connector.jd.stock;

import cn.zimu.fulfillment.connector.jd.JdResult;
import java.util.Map;

/** 京东 ISC 库存查询域（快照/汇总/异动/效期/店铺流水）的稳定应用边界；SDK DTO 不泄漏到业务用例。 */
public interface JDStockService {

    /** 库存快照：按事业部 + 商品 + 库存类型查当前库存快照（分页游标）。 */
    JdResult queryStockSnapshot(Map<String, Object> request);

    /** 库存汇总：按事业部聚合各仓库存汇总。 */
    JdResult queryStockSummary(Map<String, Object> request);

    /** 批次异动：按仓库 + 时间段查批次库存异动流水。 */
    JdResult queryBatchChange(Map<String, Object> request);

    /** 级别异动：按时间段查商品级别（正品/残次等）异动记录。 */
    JdResult queryGoodsLevelChange(Map<String, Object> request);

    /** 效期商品：按效期盘点单查商品效期信息。 */
    JdResult queryShelfLifeGoods(Map<String, Object> request);

    /** 效期库存：按仓库 + 商品查批次效期库存明细。 */
    JdResult queryShelfLifeInventory(Map<String, Object> request);

    /** 店铺库存流水：按店铺 + 仓库 + 商品查库存变动流水。 */
    JdResult searchShopStockFlow(Map<String, Object> request);
}
