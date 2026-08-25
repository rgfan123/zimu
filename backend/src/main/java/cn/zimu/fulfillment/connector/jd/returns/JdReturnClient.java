package cn.zimu.fulfillment.connector.jd.returns;

import cn.zimu.fulfillment.connector.jd.JdIscGateway;
import cn.zimu.fulfillment.connector.jd.JdResult;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainOrderReturntosupplierQueryV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainOrderReturntowarehouseQueryV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainOrderReturntowarehouseQueryorderlistV1LopRequest;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** 真实京东 ISC 退货退供查询客户端：领域 DTO 仅存在于这一防腐层。 */
@Service
@ConditionalOnProperty(name = "app.jd.client-mode", havingValue = "REAL")
public class JdReturnClient implements JDReturnService {

    private final JdIscGateway gateway;

    public JdReturnClient(JdIscGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public JdResult queryRtwOrderList(Map<String, Object> command) {
        var request = new IntegratedsupplychainOrderReturntowarehouseQueryorderlistV1LopRequest();
        request.setRequest(gateway.body(command,
                com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformRtwService
                        .queryRtwOrderList.RtwOpenQueryRequest.class));
        return gateway.execute("queryRtwOrderList", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult queryRtwOrderDetail(Map<String, Object> command) {
        var request = new IntegratedsupplychainOrderReturntowarehouseQueryV1LopRequest();
        request.setRequest(gateway.body(command,
                com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformRtwService
                        .queryRtwOrderDetail.RtwOpenQueryRequest.class));
        return gateway.execute("queryRtwOrderDetail", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult queryReturnToSupplier(Map<String, Object> command) {
        var request = new IntegratedsupplychainOrderReturntosupplierQueryV1LopRequest();
        request.setRequest(gateway.body(command,
                com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformRtsService
                        .queryReturnToSupplier.RtsOpenQueryRequest.class));
        return gateway.execute("queryReturnToSupplier", command, request, response -> response.getResponse());
    }
}
