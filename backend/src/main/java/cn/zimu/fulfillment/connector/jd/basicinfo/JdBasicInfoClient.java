package cn.zimu.fulfillment.connector.jd.basicinfo;

import cn.zimu.fulfillment.connector.jd.JdIscGateway;
import cn.zimu.fulfillment.connector.jd.JdResult;
import com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformCustomerService.getSellerInfo.JdlOpenRequest;
import com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformCustomerService.queryCustomer.CustomerQueryRequest;
import com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformGISService.queryWarehouseCoverages.WarehouseQueryRequest;
import com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformGoodsService.queryGoodsInfo.GoodsInfoQueryRequest;
import com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformGoodsService.queryGoodsLevelCategories.GoodsCategoriesRequest;
import com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformGoodsService.queryShopGoodsInfo.ShopGoodsInfoQueryRequest;
import com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformShopService.queryShopInfo.ShopInfoQueryRequest;
import com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformSupplierService.query.SupplierQueryRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainBasicinfoCustomerQueryV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainBasicinfoGoodscategoryQueryV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainBasicinfoGoodsQueryV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainBasicinfoSellerQueryV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainBasicinfoShopQueryV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainBasicinfoShopgoodsQueryV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainBasicinfoSupplierQueryV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainOrderWarehousecoveragesQueryV1LopRequest;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** 真实京东 ISC LOP 基础信息查询客户端：领域 DTO 仅存在于这一防腐层。 */
@Service
@ConditionalOnProperty(name = "app.jd.client-mode", havingValue = "REAL")
public class JdBasicInfoClient implements JDBasicInfoService {

    private final JdIscGateway gateway;

    public JdBasicInfoClient(JdIscGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public JdResult queryCustomers(Map<String, Object> command) {
        var request = new IntegratedsupplychainBasicinfoCustomerQueryV1LopRequest();
        request.setRequest(gateway.body(command, CustomerQueryRequest.class));
        return gateway.execute("queryCustomers", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult querySellers(Map<String, Object> command) {
        var request = new IntegratedsupplychainBasicinfoSellerQueryV1LopRequest();
        request.setRequest(gateway.body(command, JdlOpenRequest.class));
        return gateway.execute("querySellers", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult queryShops(Map<String, Object> command) {
        var request = new IntegratedsupplychainBasicinfoShopQueryV1LopRequest();
        request.setRequest(gateway.body(command, ShopInfoQueryRequest.class));
        return gateway.execute("queryShops", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult queryShopGoods(Map<String, Object> command) {
        var request = new IntegratedsupplychainBasicinfoShopgoodsQueryV1LopRequest();
        request.setRequest(gateway.body(command, ShopGoodsInfoQueryRequest.class));
        return gateway.execute("queryShopGoods", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult querySuppliers(Map<String, Object> command) {
        var request = new IntegratedsupplychainBasicinfoSupplierQueryV1LopRequest();
        request.setRequest(gateway.body(command, SupplierQueryRequest.class));
        return gateway.execute("querySuppliers", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult queryGoodsCategories(Map<String, Object> command) {
        var request = new IntegratedsupplychainBasicinfoGoodscategoryQueryV1LopRequest();
        request.setRequest(gateway.body(command, GoodsCategoriesRequest.class));
        return gateway.execute("queryGoodsCategories", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult queryWarehouseCoverages(Map<String, Object> command) {
        var request = new IntegratedsupplychainOrderWarehousecoveragesQueryV1LopRequest();
        request.setRequest(gateway.body(command, WarehouseQueryRequest.class));
        return gateway.execute("queryWarehouseCoverages", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult queryGoodsInfo(Map<String, Object> command) {
        var request = new IntegratedsupplychainBasicinfoGoodsQueryV1LopRequest();
        request.setRequest(gateway.body(command, GoodsInfoQueryRequest.class));
        return gateway.execute("queryGoodsInfo", command, request, response -> response.getResponse());
    }
}
