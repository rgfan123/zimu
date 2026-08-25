package cn.zimu.fulfillment.connector.jd;

import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainBasicinfoOwnerQueryV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainBasicinfoGoodsQueryV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainBasicinfoWarehouseQueryV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainOrderCancelV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainOrderDeliveryCreateV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainOrderDeliveryQueryV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainOrderTraceQueryV2LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainStockQueryV1LopRequest;
import java.util.Collection;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** 真实京东 ISC LOP 客户端：领域 DTO 仅存在于这一防腐层。 */
@Service
@ConditionalOnProperty(name = "app.jd.client-mode", havingValue = "REAL")
public class JdWarehouseClient implements JDWarehouseService {

    private final JdIscGateway gateway;

    public JdWarehouseClient(JdIscGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public JdResult queryOwners(Map<String, Object> command) {
        var request = new IntegratedsupplychainBasicinfoOwnerQueryV1LopRequest();
        request.setRequest(gateway.body(command, com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformSellerService.queryOwnerInfo.OwnerQueryRequest.class));
        return gateway.execute("queryOwners", command, request, response -> response.getResponse(),
                JdWarehouseAuditProjection.INSTANCE);
    }

    @Override
    public JdResult queryWarehouses(Map<String, Object> command) {
        var request = new IntegratedsupplychainBasicinfoWarehouseQueryV1LopRequest();
        request.setRequest(gateway.body(command, com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformSellerService.queryWarehouseInfo.WarehouseQueryRequest.class));
        return gateway.execute("queryWarehouses", command, request, response -> response.getResponse(),
                JdWarehouseAuditProjection.INSTANCE);
    }

    @Override
    public JdResult queryProducts(Map<String, Object> command) {
        var request = new IntegratedsupplychainBasicinfoGoodsQueryV1LopRequest();
        request.setRequest(gateway.body(command, com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformGoodsService.queryGoodsInfo.GoodsInfoQueryRequest.class));
        return gateway.execute("queryProducts", command, request, response -> response.getResponse(),
                JdWarehouseAuditProjection.INSTANCE);
    }

    @Override
    public JdResult queryStock(Map<String, Object> command) {
        var request = new IntegratedsupplychainStockQueryV1LopRequest();
        request.setRequest(gateway.body(command, com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformStockService.queryStock.StockQueryRequest.class));
        return gateway.execute("queryStock", command, request, response -> response.getResponse(),
                JdWarehouseAuditProjection.INSTANCE);
    }

    @Override
    public JdResult createOutboundOrder(Map<String, Object> command) {
        var request = new IntegratedsupplychainOrderDeliveryCreateV1LopRequest();
        request.setRequest(gateway.body(command, com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformSoService.addSoOrder.SoCreateOrderRequest.class));
        return gateway.execute("createOutboundOrder", command, request, response -> response.getResponse(),
                JdWarehouseAuditProjection.INSTANCE);
    }

    @Override
    public JdResult queryOutboundOrder(Map<String, Object> command) {
        var request = new IntegratedsupplychainOrderDeliveryQueryV1LopRequest();
        request.setRequest(gateway.body(command, com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformSoService.querySoOrder.SoQueryRequest.class));
        return gateway.execute("queryOutboundOrder", command, request, response -> response.getResponse(),
                JdWarehouseAuditProjection.INSTANCE);
    }

    @Override
    public JdResult cancelOutboundOrder(Map<String, Object> command) {
        var request = new IntegratedsupplychainOrderCancelV1LopRequest();
        request.setRequest(gateway.body(command, com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformService.cancelOrder.OrderCancelRequest.class));
        return gateway.execute("cancelOutboundOrder", command, request, response -> response.getResponse(),
                JdWarehouseAuditProjection.INSTANCE);
    }

    @Override
    public JdResult queryTracking(Map<String, Object> command) {
        var request = new IntegratedsupplychainOrderTraceQueryV2LopRequest();
        request.setRequest(gateway.body(command, com.lop.open.api.sdk.domain.IntegratedSupplyChain.OpenOrderTraceService.commonQueryOrderTrace.CommonOrderTraceRequest.class));
        return gateway.execute("queryTracking", command, request, response -> response.getResult());
    }
}
