package cn.zimu.fulfillment.connector.jd;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.zimu.fulfillment.common.audit.AuditLogRepository;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.connector.jd.basicinfo.JDBasicInfoService;
import cn.zimu.fulfillment.connector.jd.basicinfo.MockJdBasicInfoClient;
import cn.zimu.fulfillment.connector.jd.order.JdOrderService;
import cn.zimu.fulfillment.connector.jd.order.MockJdOrderClient;
import cn.zimu.fulfillment.connector.jd.returns.JDReturnService;
import cn.zimu.fulfillment.connector.jd.returns.MockJdReturnClient;
import cn.zimu.fulfillment.connector.jd.serial.JdSerialService;
import cn.zimu.fulfillment.connector.jd.serial.MockJdSerialClient;
import cn.zimu.fulfillment.connector.jd.stock.JDStockService;
import cn.zimu.fulfillment.connector.jd.stock.MockJdStockClient;
import cn.zimu.fulfillment.connector.jd.write.JdWriteOpsService;
import cn.zimu.fulfillment.connector.jd.write.MockJdWriteOpsClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Mock 客户端形状契约：所有 Mock 的输出键必须与 REAL 客户端一致使用 camelCase。
 *
 * <p>背景：Mock 曾混用 snake_case（basicinfo/stock/order/returns）与 camelCase（write/serial），
 * 业务解析器按 Mock 形状写、REAL 上就挂。本测试递归断言每个 Mock 响应 data 的键均为
 * camelCase，防止回归。
 */
class JdMockShapeContractTest {

    private static final Pattern CAMEL_CASE = Pattern.compile("^[a-z][a-zA-Z0-9]*$");

    private static final List<Function<JDBasicInfoService, JdResult>> BASIC_INFO_CALLS = List.of(
            service -> service.queryCustomers(Map.of()),
            service -> service.querySellers(Map.of()),
            service -> service.queryShops(Map.of()),
            service -> service.queryShopGoods(Map.of()),
            service -> service.querySuppliers(Map.of()),
            service -> service.queryGoodsCategories(Map.of()),
            service -> service.queryWarehouseCoverages(Map.of()),
            // queryGoodsInfo 形状特殊：data 不套 operation/request/response 壳，直接是商品列表。
            service -> service.queryGoodsInfo(Map.of("goodsNo", "MOCK-SKU-001")));

    private static final List<Function<JDStockService, JdResult>> STOCK_CALLS = List.of(
            service -> service.queryStockSnapshot(Map.of()),
            service -> service.queryStockSummary(Map.of()),
            service -> service.queryBatchChange(Map.of()),
            service -> service.queryGoodsLevelChange(Map.of()),
            service -> service.queryShelfLifeGoods(Map.of()),
            service -> service.queryShelfLifeInventory(Map.of()),
            service -> service.searchShopStockFlow(Map.of()));

    private static final List<Function<JdOrderService, JdResult>> ORDER_CALLS = List.of(
            service -> service.queryOrderNosByPage(Map.of()),
            service -> service.queryAdjustment(Map.of()),
            service -> service.queryDestroy(Map.of()),
            service -> service.queryException(Map.of()),
            service -> service.queryPurchase(Map.of()),
            service -> service.queryProcessed(Map.of()),
            service -> service.queryOperateRelation(Map.of()),
            service -> service.queryDeliveryTime(Map.of()),
            service -> service.queryCityTrack(Map.of()));

    private static final List<Function<JdSerialService, JdResult>> SERIAL_CALLS = List.of(
            service -> service.queryJdMallSerial(Map.of()),
            service -> service.querySerialByCondition(Map.of()),
            service -> service.querySerialFlow(Map.of()),
            service -> service.querySerialInside(Map.of()));

    private static final List<Function<JDReturnService, JdResult>> RETURN_CALLS = List.of(
            service -> service.queryRtwOrderList(Map.of()),
            service -> service.queryRtwOrderDetail(Map.of()),
            service -> service.queryReturnToSupplier(Map.of()));

    @Test
    void basicInfoMockOutputsAreCamelCase() {
        JDBasicInfoService client = new MockJdBasicInfoClient();
        BASIC_INFO_CALLS.forEach(call -> assertCamelCaseData("basicinfo", call.apply(client)));
    }

    @Test
    void stockMockOutputsAreCamelCase() {
        JDStockService client = new MockJdStockClient();
        STOCK_CALLS.forEach(call -> assertCamelCaseData("stock", call.apply(client)));
    }

    @Test
    void orderMockOutputsAreCamelCase() {
        JdOrderService client = new MockJdOrderClient();
        ORDER_CALLS.forEach(call -> assertCamelCaseData("order", call.apply(client)));
    }

    @Test
    void serialMockOutputsAreCamelCase() {
        JdSerialService client = new MockJdSerialClient();
        SERIAL_CALLS.forEach(call -> assertCamelCaseData("serial", call.apply(client)));
    }

    @Test
    void returnMockOutputsAreCamelCase() {
        JDReturnService client = new MockJdReturnClient();
        RETURN_CALLS.forEach(call -> assertCamelCaseData("return", call.apply(client)));
    }

    private static final List<Function<JdWriteOpsService, JdResult>> WRITE_CALLS = List.of(
            client -> client.customerCreate(Map.of("customerName", "某客户")),
            client -> client.goodsCreate(Map.of()),
            client -> client.goodsUpdateBySellerGoodsSign(Map.of()),
            client -> client.supplierCreate(Map.of()),
            client -> client.shopCreate(Map.of()),
            client -> client.shopGoodsCreate(Map.of()),
            client -> client.serialnumberCreate(Map.of()),
            client -> client.processedCreate(Map.of()),
            client -> client.logicalinventoryfactorCreate(Map.of()),
            client -> client.boxandserialnumberTransport(Map.of()),
            client -> client.orderAdjustmentCreate(Map.of()),
            client -> client.orderDestroyCreate(Map.of()),
            client -> client.orderOperateCommandModify(Map.of()),
            client -> client.orderProcessedCreate(Map.of()),
            client -> client.orderPurchaseCreate(Map.of("erpPurchaseNo", "PO-001")),
            client -> client.orderPurchaseClose(Map.of()),
            client -> client.orderReturntosupplierCreate(Map.of()),
            client -> client.orderReturntowarehouseCreate(Map.of()),
            client -> client.stockShopstockfixedSet(Map.of("shopNo", "S-001")));

    @Test
    void writeMockOutputsAreCamelCase() {
        JdWriteOpsService client = new MockJdWriteOpsClient(auditLogService(), "on");
        WRITE_CALLS.forEach(call -> assertCamelCaseData("write", call.apply(client)));
    }

    private void assertCamelCaseData(String domain, JdResult result) {
        assertThat(result.success()).as("mock %s call should succeed", domain).isTrue();
        Object data = result.data();
        assertThat(data).as("mock %s response should carry data", domain).isNotNull();
        // 多数 Mock 套 operation/request/response 壳；queryGoodsInfo 例外，data 直接是商品列表。
        if (data instanceof Map<?, ?> map && map.containsKey("response")) {
            Object response = map.get("response");
            assertThat(response).as("mock %s response should carry data", domain).isNotNull();
            assertCamelCaseKeys(domain, response);
        } else {
            assertCamelCaseKeys(domain, data);
        }
    }

    private void assertCamelCaseKeys(String domain, Object value) {
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = String.valueOf(entry.getKey());
                assertThat(CAMEL_CASE.matcher(key).matches())
                        .as("mock %s key '%s' must be camelCase (REAL 客户端契约)", domain, key)
                        .isTrue();
                assertCamelCaseKeys(domain, entry.getValue());
            }
        } else if (value instanceof List<?> list) {
            list.forEach(item -> assertCamelCaseKeys(domain, item));
        }
    }

    private AuditLogService auditLogService() {
        AuditLogRepository repository = mock(AuditLogRepository.class);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        return new AuditLogService(repository, new ObjectMapper(), mock(EntityManager.class));
    }
}
