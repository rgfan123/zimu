package cn.zimu.fulfillment.connector.jd.basicinfo;

import cn.zimu.fulfillment.connector.jd.JdResult;
import java.util.Map;

/** 京东 ISC 基础信息查询域（只读）的稳定应用边界；SDK DTO 不泄漏到业务用例。 */
public interface JDBasicInfoService {

    /** 客户查询（IntegratedsupplychainBasicinfoCustomerQueryV1）。 */
    JdResult queryCustomers(Map<String, Object> request);

    /** 商家查询（IntegratedsupplychainBasicinfoSellerQueryV1，仅 pin，无业务参数）。 */
    JdResult querySellers(Map<String, Object> request);

    /** 店铺查询（IntegratedsupplychainBasicinfoShopQueryV1）。 */
    JdResult queryShops(Map<String, Object> request);

    /** 店铺商品查询（IntegratedsupplychainBasicinfoShopgoodsQueryV1）。 */
    JdResult queryShopGoods(Map<String, Object> request);

    /** 供应商查询（IntegratedsupplychainBasicinfoSupplierQueryV1）。 */
    JdResult querySuppliers(Map<String, Object> request);

    /** 商品类目查询（IntegratedsupplychainBasicinfoGoodscategoryQueryV1）。 */
    JdResult queryGoodsCategories(Map<String, Object> request);

    /** 仓库覆盖范围查询（IntegratedsupplychainOrderWarehousecoveragesQueryV1）。 */
    JdResult queryWarehouseCoverages(Map<String, Object> request);

    /** 商品信息查询（IntegratedsupplychainBasicinfoGoodsQueryV1；按京东商品编码 goodsNo 查商品）。 */
    JdResult queryGoodsInfo(Map<String, Object> request);
}
