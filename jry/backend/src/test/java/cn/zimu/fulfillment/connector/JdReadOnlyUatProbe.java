package cn.zimu.fulfillment.connector;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zimu.fulfillment.connector.jd.JDWarehouseService;
import cn.zimu.fulfillment.connector.jd.JdResult;
import cn.zimu.fulfillment.connector.jd.basicinfo.JDBasicInfoService;
import cn.zimu.fulfillment.connector.jd.order.JdOrderService;
import cn.zimu.fulfillment.connector.jd.returns.JDReturnService;
import cn.zimu.fulfillment.connector.jd.serial.JdSerialService;
import cn.zimu.fulfillment.connector.jd.stock.JDStockService;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 显式运行的京东环境只读探针；文件名刻意不以 Test 结尾，避免普通测试误触外部系统。
 *
 * <p>覆盖仓库域（queryOwners/queryWarehouses 与旧 seam 的 queryProducts/queryStock/queryOutboundOrder/queryTracking）与
 * 01–06 票的全部只读接口：基础信息、库存、订单杂项、序列号、退货退供。
 * 每个接口一个探针方法：校验 JD_LOP_* 环境变量 → 调用 → 输出
 * JD_READONLY_PROBE 分类行（只含成功/业务码/requestId/数据存在性标记，不输出密钥与 PII）。
 * 参数固定传 Map.of()，只验证权限与连通性，不猜测单号；缺参数类业务码同样是待记录的分类结论。
 *
 * <p>运行方式（不以 Test 结尾，普通测试套件不触发，必须显式指定）：
 * scripts/jd-readonly-uat.sh，或 mvn -q -f backend/pom.xml -Dtest=JdReadOnlyUatProbe test。
 */
@Testcontainers
@SpringBootTest(properties = "app.jd.client-mode=REAL")
class JdReadOnlyUatProbe {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    /** 探针所需的全部 JD_LOP_* 键（凭据 + pin + ownerNo）；缺失时一次性全部列出，而不是只报第一个。 */
    private static final String[] REQUIRED_ENV_KEYS = {
        "JD_LOP_PIN",
        "JD_LOP_OWNER_NO",
        "JD_LOP_SERVER_URL",
        "JD_LOP_APP_KEY",
        "JD_LOP_APP_SECRET",
        "JD_LOP_ACCESS_TOKEN"
    };

    @Autowired
    private JDWarehouseService service;

    @Autowired
    private JDBasicInfoService basicInfoService;

    @Autowired
    private JDStockService stockService;

    @Autowired
    private JdOrderService orderService;

    @Autowired
    private JdSerialService serialService;

    @Autowired
    private JDReturnService returnService;

    // ---- 仓库域（02 票之前的既有探针 + 旧 seam 只读接口补充） ----

    @Test
    void credentialsCanDiscoverAuthorizedOwnersThroughTheOfficialSdk() {
        requiredEnvironment("JD_LOP_PIN");
        var result = service.queryOwners(Map.of());
        System.out.printf(
                "JD_READONLY_PROBE operation=queryOwners success=%s business_code=%s request_id_present=%s data_present=%s%n",
                result.success(),
                result.businessCode(),
                result.requestId() != null,
                result.data() != null);
        assertThat(result.success())
                .as("JD SDK read-only call failed: code=%s message=%s", result.businessCode(), result.message())
                .isTrue();
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "JD_PROBE_WAREHOUSES", matches = "true")
    void configuredOwnerCanQueryAuthorizedWarehousesThroughTheOfficialSdk() {
        requiredEnvironment("JD_LOP_PIN");
        String ownerNo = requiredEnvironment("JD_LOP_OWNER_NO");
        var result = service.queryWarehouses(Map.of("ownerNo", ownerNo));
        System.out.printf(
                "JD_READONLY_PROBE operation=queryWarehouses success=%s business_code=%s request_id_present=%s data_present=%s%n",
                result.success(),
                result.businessCode(),
                result.requestId() != null,
                result.data() != null);
        assertThat(result.success())
                .as("JD SDK warehouse read-only call failed: code=%s message=%s", result.businessCode(), result.message())
                .isTrue();
    }

    @Test
    void queryProductsThroughOfficialSdk() {
        requiredEnvironments(REQUIRED_ENV_KEYS);
        probe("queryProducts", service.queryProducts(Map.of()));
    }

    @Test
    void queryStockThroughOfficialSdk() {
        requiredEnvironments(REQUIRED_ENV_KEYS);
        probe("queryStock", service.queryStock(Map.of()));
    }

    @Test
    void queryOutboundOrderThroughOfficialSdk() {
        requiredEnvironments(REQUIRED_ENV_KEYS);
        probe("queryOutboundOrder", service.queryOutboundOrder(Map.of()));
    }

    @Test
    void queryTrackingThroughOfficialSdk() {
        requiredEnvironments(REQUIRED_ENV_KEYS);
        probe("queryTracking", service.queryTracking(Map.of()));
    }

    // ---- 基础信息域（02 票） ----

    @Test
    void queryCustomersThroughOfficialSdk() {
        requiredEnvironments(REQUIRED_ENV_KEYS);
        probe("queryCustomers", basicInfoService.queryCustomers(Map.of()));
    }

    @Test
    void querySellersThroughOfficialSdk() {
        requiredEnvironments(REQUIRED_ENV_KEYS);
        probe("querySellers", basicInfoService.querySellers(Map.of()));
    }

    @Test
    void queryShopsThroughOfficialSdk() {
        requiredEnvironments(REQUIRED_ENV_KEYS);
        probe("queryShops", basicInfoService.queryShops(Map.of()));
    }

    @Test
    void queryShopGoodsThroughOfficialSdk() {
        requiredEnvironments(REQUIRED_ENV_KEYS);
        probe("queryShopGoods", basicInfoService.queryShopGoods(Map.of()));
    }

    @Test
    void querySuppliersThroughOfficialSdk() {
        requiredEnvironments(REQUIRED_ENV_KEYS);
        probe("querySuppliers", basicInfoService.querySuppliers(Map.of()));
    }

    @Test
    void queryGoodsCategoriesThroughOfficialSdk() {
        requiredEnvironments(REQUIRED_ENV_KEYS);
        probe("queryGoodsCategories", basicInfoService.queryGoodsCategories(Map.of()));
    }

    @Test
    void queryWarehouseCoveragesThroughOfficialSdk() {
        requiredEnvironments(REQUIRED_ENV_KEYS);
        probe("queryWarehouseCoverages", basicInfoService.queryWarehouseCoverages(Map.of()));
    }

    @Test
    void queryGoodsInfoThroughOfficialSdk() {
        requiredEnvironments(REQUIRED_ENV_KEYS);
        probe("queryGoodsInfo", basicInfoService.queryGoodsInfo(Map.of()));
    }

    // ---- 库存域（03 票） ----

    @Test
    void queryStockSnapshotThroughOfficialSdk() {
        requiredEnvironments(REQUIRED_ENV_KEYS);
        probe("queryStockSnapshot", stockService.queryStockSnapshot(Map.of()));
    }

    @Test
    void queryStockSummaryThroughOfficialSdk() {
        requiredEnvironments(REQUIRED_ENV_KEYS);
        probe("queryStockSummary", stockService.queryStockSummary(Map.of()));
    }

    @Test
    void queryBatchChangeThroughOfficialSdk() {
        requiredEnvironments(REQUIRED_ENV_KEYS);
        probe("queryBatchChange", stockService.queryBatchChange(Map.of()));
    }

    @Test
    void queryGoodsLevelChangeThroughOfficialSdk() {
        requiredEnvironments(REQUIRED_ENV_KEYS);
        probe("queryGoodsLevelChange", stockService.queryGoodsLevelChange(Map.of()));
    }

    @Test
    void queryShelfLifeGoodsThroughOfficialSdk() {
        requiredEnvironments(REQUIRED_ENV_KEYS);
        probe("queryShelfLifeGoods", stockService.queryShelfLifeGoods(Map.of()));
    }

    @Test
    void queryShelfLifeInventoryThroughOfficialSdk() {
        requiredEnvironments(REQUIRED_ENV_KEYS);
        probe("queryShelfLifeInventory", stockService.queryShelfLifeInventory(Map.of()));
    }

    @Test
    void searchShopStockFlowThroughOfficialSdk() {
        requiredEnvironments(REQUIRED_ENV_KEYS);
        probe("searchShopStockFlow", stockService.searchShopStockFlow(Map.of()));
    }

    // ---- 订单杂项域（04 票 + 01 票 queryOrderNosByPage） ----

    @Test
    void queryOrderNosByPageThroughOfficialSdk() {
        requiredEnvironments(REQUIRED_ENV_KEYS);
        probe("queryOrderNosByPage", orderService.queryOrderNosByPage(Map.of()));
    }

    @Test
    void queryAdjustmentThroughOfficialSdk() {
        requiredEnvironments(REQUIRED_ENV_KEYS);
        probe("queryAdjustment", orderService.queryAdjustment(Map.of()));
    }

    @Test
    void queryDestroyThroughOfficialSdk() {
        requiredEnvironments(REQUIRED_ENV_KEYS);
        probe("queryDestroy", orderService.queryDestroy(Map.of()));
    }

    @Test
    void queryExceptionThroughOfficialSdk() {
        requiredEnvironments(REQUIRED_ENV_KEYS);
        probe("queryException", orderService.queryException(Map.of()));
    }

    @Test
    void queryPurchaseThroughOfficialSdk() {
        requiredEnvironments(REQUIRED_ENV_KEYS);
        probe("queryPurchase", orderService.queryPurchase(Map.of()));
    }

    @Test
    void queryProcessedThroughOfficialSdk() {
        requiredEnvironments(REQUIRED_ENV_KEYS);
        probe("queryProcessed", orderService.queryProcessed(Map.of()));
    }

    @Test
    void queryOperateRelationThroughOfficialSdk() {
        requiredEnvironments(REQUIRED_ENV_KEYS);
        probe("queryOperateRelation", orderService.queryOperateRelation(Map.of()));
    }

    @Test
    void queryDeliveryTimeThroughOfficialSdk() {
        requiredEnvironments(REQUIRED_ENV_KEYS);
        probe("queryDeliveryTime", orderService.queryDeliveryTime(Map.of()));
    }

    @Test
    void queryCityTrackThroughOfficialSdk() {
        requiredEnvironments(REQUIRED_ENV_KEYS);
        probe("queryCityTrack", orderService.queryCityTrack(Map.of()));
    }

    // ---- 序列号域（05 票） ----

    @Test
    void queryJdMallSerialThroughOfficialSdk() {
        requiredEnvironments(REQUIRED_ENV_KEYS);
        probe("queryJdMallSerial", serialService.queryJdMallSerial(Map.of()));
    }

    @Test
    void querySerialByConditionThroughOfficialSdk() {
        requiredEnvironments(REQUIRED_ENV_KEYS);
        probe("querySerialByCondition", serialService.querySerialByCondition(Map.of()));
    }

    @Test
    void querySerialFlowThroughOfficialSdk() {
        requiredEnvironments(REQUIRED_ENV_KEYS);
        probe("querySerialFlow", serialService.querySerialFlow(Map.of()));
    }

    @Test
    void querySerialInsideThroughOfficialSdk() {
        requiredEnvironments(REQUIRED_ENV_KEYS);
        probe("querySerialInside", serialService.querySerialInside(Map.of()));
    }

    // ---- 退货退供域（06 票） ----

    @Test
    void queryRtwOrderListThroughOfficialSdk() {
        requiredEnvironments(REQUIRED_ENV_KEYS);
        probe("queryRtwOrderList", returnService.queryRtwOrderList(Map.of()));
    }

    @Test
    void queryRtwOrderDetailThroughOfficialSdk() {
        requiredEnvironments(REQUIRED_ENV_KEYS);
        probe("queryRtwOrderDetail", returnService.queryRtwOrderDetail(Map.of()));
    }

    @Test
    void queryReturnToSupplierThroughOfficialSdk() {
        requiredEnvironments(REQUIRED_ENV_KEYS);
        probe("queryReturnToSupplier", returnService.queryReturnToSupplier(Map.of()));
    }

    /** 调用一个只读接口并输出分类行；只打印存在性标记，不打印业务数据或凭据。 */
    private void probe(String operation, JdResult result) {
        System.out.printf(
                "JD_READONLY_PROBE operation=%s success=%s business_code=%s request_id_present=%s data_present=%s%n",
                operation,
                result.success(),
                result.businessCode(),
                result.requestId() != null,
                result.data() != null);
        assertThat(result.success())
                .as("JD SDK read-only call failed: operation=%s code=%s message=%s",
                        operation, result.businessCode(), result.message())
                .isTrue();
    }

    private String requiredEnvironment(String name) {
        String value = System.getenv(name);
        assertThat(value).as("%s must be configured", name).isNotBlank();
        return value;
    }

    /** 一次性收集缺失的 JD_LOP_* 键并全部列出；与单键版 requiredEnvironment 不同，不短路在第一个缺失键上。 */
    private void requiredEnvironments(String... names) {
        List<String> missing = Arrays.stream(names)
                .filter(name -> System.getenv(name) == null || System.getenv(name).isBlank())
                .toList();
        assertThat(missing)
                .as("Missing JD_LOP_* environment variables: %s (probe refuses to run to avoid misreading as permission result)", missing)
                .isEmpty();
    }
}
