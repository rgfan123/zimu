package cn.zimu.fulfillment.connector.jd.stock;

import cn.zimu.fulfillment.connector.jd.JdIscGateway;
import cn.zimu.fulfillment.connector.jd.JdResult;
import com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformStockService.queryBatchChange.BatchChangeQueryRequest;
import com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformStockService.queryCheckStock.CheckStockQueryRequest;
import com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformStockService.queryGoodsLevelChange.LevelChangeQueryRequest;
import com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformStockService.queryShelfLifeGoodsList.ShelfLifeGoodsStockQueryRequest;
import com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformStockService.queryWarehouseStockMergeByWarehouse.StockSummaryRequest;
import com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformStockService.queryWarehouseStockSnapshot.StockSnapshotRequest;
import com.lop.open.api.sdk.domain.IntegratedSupplyChain.JdlOpenPlatformStockService.searchShopStockFlow.ShopStockFlowQueryRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainStockBatchchangeQueryV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainStockFlowShopstockQueryV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainStockLevelchangeQueryV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainStockShelflifegoodsQueryV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainStockShelflifeinventoryQueryV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainStockmergeQueryV1LopRequest;
import com.lop.open.api.sdk.request.IntegratedSupplyChain.IntegratedsupplychainStocksnapshotQueryV1LopRequest;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** 真实京东 ISC 库存查询客户端：领域 DTO 仅存在于这一防腐层。 */
@Service
@ConditionalOnProperty(name = "app.jd.client-mode", havingValue = "REAL")
public class JdStockClient implements JDStockService {

    private final JdIscGateway gateway;

    public JdStockClient(JdIscGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public JdResult queryStockSnapshot(Map<String, Object> command) {
        var request = new IntegratedsupplychainStocksnapshotQueryV1LopRequest();
        request.setRequest(gateway.body(command, StockSnapshotRequest.class));
        return gateway.execute("queryStockSnapshot", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult queryStockSummary(Map<String, Object> command) {
        var request = new IntegratedsupplychainStockmergeQueryV1LopRequest();
        request.setRequest(gateway.body(command, StockSummaryRequest.class));
        return gateway.execute("queryStockSummary", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult queryBatchChange(Map<String, Object> command) {
        var request = new IntegratedsupplychainStockBatchchangeQueryV1LopRequest();
        request.setRequest(gateway.body(command, BatchChangeQueryRequest.class));
        return gateway.execute("queryBatchChange", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult queryGoodsLevelChange(Map<String, Object> command) {
        var request = new IntegratedsupplychainStockLevelchangeQueryV1LopRequest();
        request.setRequest(gateway.body(command, LevelChangeQueryRequest.class));
        return gateway.execute("queryGoodsLevelChange", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult queryShelfLifeGoods(Map<String, Object> command) {
        // 官方 SDK 中「效期商品」请求体复用的是盘点（CheckStock）DTO。
        var request = new IntegratedsupplychainStockShelflifegoodsQueryV1LopRequest();
        request.setRequest(gateway.body(command, CheckStockQueryRequest.class));
        return gateway.execute("queryShelfLifeGoods", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult queryShelfLifeInventory(Map<String, Object> command) {
        var request = new IntegratedsupplychainStockShelflifeinventoryQueryV1LopRequest();
        request.setRequest(gateway.body(command, ShelfLifeGoodsStockQueryRequest.class));
        return gateway.execute("queryShelfLifeInventory", command, request, response -> response.getResponse());
    }

    @Override
    public JdResult searchShopStockFlow(Map<String, Object> command) {
        var request = new IntegratedsupplychainStockFlowShopstockQueryV1LopRequest();
        request.setRequest(gateway.body(command, ShopStockFlowQueryRequest.class));
        return gateway.execute("searchShopStockFlow", command, request, response -> response.getResponse());
    }
}
