package cn.zimu.fulfillment.connector.jd.write;

import cn.zimu.fulfillment.connector.jd.JdResult;
import java.util.Map;

/**
 * 京东 ISC 写接口（创建/取消/修改/关闭/设置/绑定类）的稳定应用边界；SDK DTO 不泄漏到业务用例。
 *
 * <p>写模式门闩位于 HTTP 层（{@link JdWriteOpsController}，配置 {@code app.jd.write-mode}，
 * 默认 OFF 锁死），本 seam 只负责受审计的执行。领域词汇遵循 CONTEXT.md：出库单、采购、退货、履约方。
 */
public interface JdWriteOpsService {

    /** 基础信息：客户新增/更新（addOrUpdateCustomerInfo）。 */
    JdResult customerCreate(Map<String, Object> request);

    /** 基础信息：商品新增（saveGoodsInfo）。 */
    JdResult goodsCreate(Map<String, Object> request);

    /** 基础信息：按商家商品标识更新商品（updateGoodsInfoBySellerGoodsSign）。 */
    JdResult goodsUpdateBySellerGoodsSign(Map<String, Object> request);

    /** 基础信息：供应商新增/更新（upsert）。 */
    JdResult supplierCreate(Map<String, Object> request);

    /** 基础信息：店铺新增（saveShopInfo）。 */
    JdResult shopCreate(Map<String, Object> request);

    /** 基础信息：店铺商品新增（saveShopGoodsInfo）。 */
    JdResult shopGoodsCreate(Map<String, Object> request);

    /** 基础信息：串码规则新增（transportGoodsSerialNumberRule）。 */
    JdResult serialnumberCreate(Map<String, Object> request);

    /** 基础信息：加工配方新增（addGoodsFormula）。 */
    JdResult processedCreate(Map<String, Object> request);

    /** 基础信息：逻辑库存配置新增（insertLogicalStockConfig）。 */
    JdResult logicalinventoryfactorCreate(Map<String, Object> request);

    /** 基础信息：箱码与串码流转（transportBoxAndSerialInfo）。 */
    JdResult boxandserialnumberTransport(Map<String, Object> request);

    /** 订单：调整单新增（transportInsideOrder）。 */
    JdResult orderAdjustmentCreate(Map<String, Object> request);

    /** 订单：销毁单新增（addUlOrder）。 */
    JdResult orderDestroyCreate(Map<String, Object> request);

    /** 订单：配送指令修改（updateDeliveryCommand）。 */
    JdResult orderOperateCommandModify(Map<String, Object> request);

    /** 订单：加工单新增（addProcessOrder）。 */
    JdResult orderProcessedCreate(Map<String, Object> request);

    /** 订单：采购单新增（addPoOrder）。 */
    JdResult orderPurchaseCreate(Map<String, Object> request);

    /** 订单：采购单关闭（closePoOrder）。 */
    JdResult orderPurchaseClose(Map<String, Object> request);

    /** 订单：退货供应商单新增（addRtsOrder）。 */
    JdResult orderReturntosupplierCreate(Map<String, Object> request);

    /** 订单：退货入库单新增（addRtwOrder）。 */
    JdResult orderReturntowarehouseCreate(Map<String, Object> request);

    /** 订单：出库单新增（addSoOrder，LOP 路径 /integratedsupplychain/order/delivery/create/v1）。 */
    JdResult orderSoCreate(Map<String, Object> request);

    /** 库存：店铺库存固定值设置（setShopStockFixed）。 */
    JdResult stockShopstockfixedSet(Map<String, Object> request);
}
