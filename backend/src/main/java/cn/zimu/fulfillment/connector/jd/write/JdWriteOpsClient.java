package cn.zimu.fulfillment.connector.jd.write;

import cn.zimu.fulfillment.connector.jd.JdIscGateway;
import cn.zimu.fulfillment.connector.jd.JdResult;
import com.lop.open.api.sdk.request.DomainAbstractRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainBasicinfoBoxandserialnumberTransportV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainBasicinfoCustomerCreateV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainBasicinfoGoodsCreateV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainBasicinfoGoodsUpdateBySellerGoodsSignV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainBasicinfoLogicalinventoryfactorCreateV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainBasicinfoProcessedCreateV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainBasicinfoSerialnumberCreateV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainBasicinfoShopCreateV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainBasicinfoShopGoodsCreateV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainBasicinfoSupplierCreateV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainOrderAdjustmentCreateV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainOrderDeliveryCreateV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainOrderDestroyCreateV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainOrderOperateCommandModifyV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainOrderProcessedCreateV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainOrderPurchaseCloseV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainOrderPurchaseCreateV2LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainOrderReturntosupplierCreateV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainOrderReturntowarehouseCreateV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainStockShopstockfixedSetV1LopRequest;
import com.lop.open.api.sdk.response.AbstractResponse;
import java.util.Map;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * 京东 ISC 写接口真实客户端。领域 DTO 仅存在于这一防腐层；
 * 写模式门闩在本客户端内部（构造时注入），HTTP 层门闩为双保险。
 */
@Service
@ConditionalOnProperty(name = "app.jd.client-mode", havingValue = "REAL")
public class JdWriteOpsClient implements JdWriteOpsService {

    private final JdIscGateway gateway;
    private final String writeMode;

    public JdWriteOpsClient(
            JdIscGateway gateway,
            @Value("${app.jd.write-mode:OFF}") String writeMode) {
        this.gateway = gateway;
        this.writeMode = writeMode;
    }

    @Override
    public JdResult customerCreate(Map<String, Object> command) {
        var request = new IntegratedsupplychainBasicinfoCustomerCreateV1LopRequest();
        request.setRequest(gateway.body(command, com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformCustomerService.addOrUpdateCustomerInfo.CustomerInfoAddOrUpdateRequest.class));
        return execute("customerCreate", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult goodsCreate(Map<String, Object> command) {
        var request = new IntegratedsupplychainBasicinfoGoodsCreateV1LopRequest();
        request.setRequest(gateway.body(command, com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformGoodsService.saveGoodsInfo.GoodsInfoSaveRequest.class));
        return execute("goodsCreate", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult goodsUpdateBySellerGoodsSign(Map<String, Object> command) {
        var request = new IntegratedsupplychainBasicinfoGoodsUpdateBySellerGoodsSignV1LopRequest();
        request.setRequest(gateway.body(command, com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformGoodsService.updateGoodsInfoBySellerGoodsSign.GoodsInfoSaveRequest.class));
        return execute("goodsUpdateBySellerGoodsSign", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult supplierCreate(Map<String, Object> command) {
        var request = new IntegratedsupplychainBasicinfoSupplierCreateV1LopRequest();
        request.setRequest(gateway.body(command, com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformSupplierService.upsert.SupplierModelRequest.class));
        return execute("supplierCreate", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult shopCreate(Map<String, Object> command) {
        var request = new IntegratedsupplychainBasicinfoShopCreateV1LopRequest();
        request.setRequest(gateway.body(command, com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformShopService.saveShopInfo.ShopInfoSaveRequest.class));
        return execute("shopCreate", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult shopGoodsCreate(Map<String, Object> command) {
        var request = new IntegratedsupplychainBasicinfoShopGoodsCreateV1LopRequest();
        request.setRequest(gateway.body(command, com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformGoodsService.saveShopGoodsInfo.ShopGoodsInfoSaveRequest.class));
        return execute("shopGoodsCreate", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult serialnumberCreate(Map<String, Object> command) {
        var request = new IntegratedsupplychainBasicinfoSerialnumberCreateV1LopRequest();
        request.setRequest(gateway.body(command, com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformSerialService.transportGoodsSerialNumberRule.GoodsSerialAddRequest.class));
        return execute("serialnumberCreate", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult processedCreate(Map<String, Object> command) {
        var request = new IntegratedsupplychainBasicinfoProcessedCreateV1LopRequest();
        request.setRequest(gateway.body(command, com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformGoodsService.addGoodsFormula.GoodsFormulaSaveRequest.class));
        return execute("processedCreate", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult logicalinventoryfactorCreate(Map<String, Object> command) {
        var request = new IntegratedsupplychainBasicinfoLogicalinventoryfactorCreateV1LopRequest();
        request.setRequest(gateway.body(command, com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformCustomerService.insertLogicalStockConfig.CustomerLogicalStockConfigRequest.class));
        return execute("logicalinventoryfactorCreate", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult boxandserialnumberTransport(Map<String, Object> command) {
        var request = new IntegratedsupplychainBasicinfoBoxandserialnumberTransportV1LopRequest();
        request.setRequest(gateway.body(command, com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformSerialService.transportBoxAndSerialInfo.BoxAndSerialInfoTransportRequest.class));
        return execute("boxandserialnumberTransport", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult orderAdjustmentCreate(Map<String, Object> command) {
        var request = new IntegratedsupplychainOrderAdjustmentCreateV1LopRequest();
        request.setRequest(gateway.body(command, com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformInsideService.transportInsideOrder.AdjustmentMainRequest.class));
        return execute("orderAdjustmentCreate", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult orderDestroyCreate(Map<String, Object> command) {
        var request = new IntegratedsupplychainOrderDestroyCreateV1LopRequest();
        request.setRequest(gateway.body(command, com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformUlService.addUlOrder.UlOrderCreateRequest.class));
        return execute("orderDestroyCreate", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult orderOperateCommandModify(Map<String, Object> command) {
        var request = new IntegratedsupplychainOrderOperateCommandModifyV1LopRequest();
        request.setRequest(gateway.body(command, com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformSoService.updateDeliveryCommand.DeliveryCommandUpdateRequest.class));
        return execute("orderOperateCommandModify", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult orderProcessedCreate(Map<String, Object> command) {
        var request = new IntegratedsupplychainOrderProcessedCreateV1LopRequest();
        request.setRequest(gateway.body(command, com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformProcessService.addProcessOrder.ProcessOrderCreateRequest.class));
        return execute("orderProcessedCreate", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult orderPurchaseCreate(Map<String, Object> command) {
        var request = new IntegratedsupplychainOrderPurchaseCreateV2LopRequest();
        request.setRequest(gateway.body(command, com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformPoService.addPoOrder.PoCreateRequest.class));
        return execute("orderPurchaseCreate", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult orderPurchaseClose(Map<String, Object> command) {
        var request = new IntegratedsupplychainOrderPurchaseCloseV1LopRequest();
        request.setRequest(gateway.body(command, com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformPoService.closePoOrder.PoCloseRequest.class));
        return execute("orderPurchaseClose", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult orderReturntosupplierCreate(Map<String, Object> command) {
        var request = new IntegratedsupplychainOrderReturntosupplierCreateV1LopRequest();
        request.setRequest(gateway.body(command, com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformRtsService.addRtsOrder.RtsAddRequest.class));
        return execute("orderReturntosupplierCreate", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult orderReturntowarehouseCreate(Map<String, Object> command) {
        var request = new IntegratedsupplychainOrderReturntowarehouseCreateV1LopRequest();
        request.setRequest(gateway.body(command, com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformRtwService.addRtwOrder.RtwCreateRequest.class));
        return execute("orderReturntowarehouseCreate", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult orderSoCreate(Map<String, Object> command) {
        var request = new IntegratedsupplychainOrderDeliveryCreateV1LopRequest();
        request.setRequest(gateway.body(command, com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformSoService.addSoOrder.SoCreateOrderRequest.class));
        return execute("orderSoCreate", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult stockShopstockfixedSet(Map<String, Object> command) {
        var request = new IntegratedsupplychainStockShopstockfixedSetV1LopRequest();
        request.setRequest(gateway.body(command, com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformStockService.setShopStockFixed.ShopStockRequest.class));
        return execute("stockShopstockfixedSet", command, request, response -> response.getResponse());
    }

    /**
     * 写模式门闩留在写客户端本身（写专属政策，不属于传输内核）：未启用时不接触京东，
     * 经 {@link JdIscGateway#refuse} 走同一审计口径留痕。HTTP 层门闩为双保险。
     */
    private <T extends AbstractResponse> JdResult execute(
            String operation,
            Map<String, Object> command,
            DomainAbstractRequest<T> request,
            Function<T, Object> envelopeExtractor) {
        if (!writeEnabled()) {
            return gateway.refuse(operation, command, "WRITE_MODE_DISABLED", "写模式未启用");
        }
        return gateway.execute(operation, command, request, envelopeExtractor);
    }

    private boolean writeEnabled() {
        return "ON".equalsIgnoreCase(writeMode == null ? "" : writeMode.trim());
    }
}
