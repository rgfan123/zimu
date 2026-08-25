package cn.zimu.fulfillment.connector.jd.order;

import cn.zimu.fulfillment.connector.jd.JdIscGateway;
import cn.zimu.fulfillment.connector.jd.JdResult;
import com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformExceptionService.queryExceptionOrderList.ExceptionOrderQueryRequest;
import com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformInsideService.queryInsideOrder.QueryAdjustmentRequest;
import com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformPoService.queryPoOrderDetail.PoOpenQueryRequest;
import com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformProcessService.queryProcessOrder.ProcessOrderQueryRequest;
import com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformService.getEclpNoByOutNo.EclpOrderNoRequest;
import com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformService.queryOrderNosByPage.OrderNosRequest;
import com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformTrajectoryService.queryCityTrack.CityTrackRequest;
import com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformUlService.ulQuery.UlOrderQueryRequest;
import com.lop.open.api.sdk.domain.IntegratedSupplyChain.WaybillDeliveryTimeQueryService.queryDeliveryTime.WaybillDeliveryTimeRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainOrderAdjustmentQueryV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainOrderCitytrackQueryV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainOrderDeliverytimeQueryV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainOrderDestroyQueryV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainOrderExceptionQueryV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainOrderOperateRelationQueryV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainOrderProcessedQueryV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainOrderPurchaseQueryV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainOrderQueryordernosbypageV1LopRequest;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** 真实京东 ISC 订单查询客户端：领域 DTO 仅存在于这一防腐层。 */
@Service
@ConditionalOnProperty(name = "app.jd.client-mode", havingValue = "REAL")
public class JdOrderClient implements JdOrderService {

    private final JdIscGateway gateway;

    public JdOrderClient(JdIscGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public JdResult queryOrderNosByPage(Map<String, Object> command) {
        var request = new IntegratedsupplychainOrderQueryordernosbypageV1LopRequest();
        request.setRequest(gateway.body(command, OrderNosRequest.class));
        return gateway.execute("queryOrderNosByPage", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult queryAdjustment(Map<String, Object> command) {
        var request = new IntegratedsupplychainOrderAdjustmentQueryV1LopRequest();
        request.setRequest(gateway.body(command, QueryAdjustmentRequest.class));
        return gateway.execute("queryAdjustment", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult queryDestroy(Map<String, Object> command) {
        var request = new IntegratedsupplychainOrderDestroyQueryV1LopRequest();
        request.setRequest(gateway.body(command, UlOrderQueryRequest.class));
        return gateway.execute("queryDestroy", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult queryException(Map<String, Object> command) {
        var request = new IntegratedsupplychainOrderExceptionQueryV1LopRequest();
        request.setRequest(gateway.body(command, ExceptionOrderQueryRequest.class));
        return gateway.execute("queryException", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult queryPurchase(Map<String, Object> command) {
        var request = new IntegratedsupplychainOrderPurchaseQueryV1LopRequest();
        request.setRequest(gateway.body(command, PoOpenQueryRequest.class));
        return gateway.execute("queryPurchase", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult queryProcessed(Map<String, Object> command) {
        var request = new IntegratedsupplychainOrderProcessedQueryV1LopRequest();
        request.setRequest(gateway.body(command, ProcessOrderQueryRequest.class));
        return gateway.execute("queryProcessed", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult queryOperateRelation(Map<String, Object> command) {
        var request = new IntegratedsupplychainOrderOperateRelationQueryV1LopRequest();
        request.setRequest(gateway.body(command, EclpOrderNoRequest.class));
        return gateway.execute("queryOperateRelation", command, request, response -> response.getResult());
    }

    @Override
    public JdResult queryDeliveryTime(Map<String, Object> command) {
        var request = new IntegratedsupplychainOrderDeliverytimeQueryV1LopRequest();
        request.setRequest(gateway.body(command, WaybillDeliveryTimeRequest.class));
        return gateway.execute("queryDeliveryTime", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult queryCityTrack(Map<String, Object> command) {
        var request = new IntegratedsupplychainOrderCitytrackQueryV1LopRequest();
        request.setRequest(gateway.body(command, CityTrackRequest.class));
        return gateway.execute("queryCityTrack", command, request, response -> response.getResponse());
    }
}
