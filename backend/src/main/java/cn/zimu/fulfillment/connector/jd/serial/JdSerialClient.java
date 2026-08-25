package cn.zimu.fulfillment.connector.jd.serial;

import cn.zimu.fulfillment.connector.jd.JdIscGateway;
import cn.zimu.fulfillment.connector.jd.JdResult;
import com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformSerialService.queryInStockSidBySku.GoodsSerialQueryRequest;
import com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformSerialService.queryJDMallSerialByPage.JDMallSerialQueryRequest;
import com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformSerialService.queryPageSerialByOwnerNoAndCondition.BusSerialQueryRequest;
import com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformSerialService.querySerialBySkuAndSerial.GoodsSIDQueryRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainOrderSNQueryV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainOrderSerialConditionQueryV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainOrderSerialFlowQueryV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainOrderSerialInsideQueryV1LopRequest;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** 真实京东 ISC 序列号查询客户端：领域 DTO 仅存在于这一防腐层，四个查询全部只读。 */
@Service
@ConditionalOnProperty(name = "app.jd.client-mode", havingValue = "REAL")
public class JdSerialClient implements JdSerialService {

    private final JdIscGateway gateway;

    public JdSerialClient(JdIscGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public JdResult queryJdMallSerial(Map<String, Object> command) {
        var request = new IntegratedsupplychainOrderSNQueryV1LopRequest();
        request.setRequest(gateway.body(command, JDMallSerialQueryRequest.class));
        return gateway.execute("queryJdMallSerial", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult querySerialByCondition(Map<String, Object> command) {
        var request = new IntegratedsupplychainOrderSerialConditionQueryV1LopRequest();
        request.setRequest(gateway.body(command, BusSerialQueryRequest.class));
        return gateway.execute("querySerialByCondition", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult querySerialFlow(Map<String, Object> command) {
        var request = new IntegratedsupplychainOrderSerialFlowQueryV1LopRequest();
        request.setRequest(gateway.body(command, GoodsSIDQueryRequest.class));
        return gateway.execute("querySerialFlow", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult querySerialInside(Map<String, Object> command) {
        var request = new IntegratedsupplychainOrderSerialInsideQueryV1LopRequest();
        request.setRequest(gateway.body(command, GoodsSerialQueryRequest.class));
        return gateway.execute("querySerialInside", command, request, response -> response.getResponse());
    }
}
