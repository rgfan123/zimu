package cn.zimu.fulfillment.connector.feixiang;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zimu.fulfillment.connector.PullCursor;
import cn.zimu.fulfillment.connector.PullResult;
import cn.zimu.fulfillment.file.SourceImportService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 飞象 JSON 拉取端到端（真 Postgres）：<b>跨日期窗口真的生效</b>、来源下单时间落库、
 * 混批幂等不整批回滚。
 *
 * <p>被测的是本票要修的生产事故：旧 Excel 链路传 {@code start_time}/{@code end_time}，
 * 平台不认、静默回落成「只返回当天下单的订单」，导致任何没在下单当天被拉到的单永久丢失
 * （已确认丢失实例：D2026826346818550490，2026-08-26 16:58 下单，从未进入系统）。</p>
 *
 * <p>HTTP 层用桩（{@link StubPullClient}），不打真实平台；导入链路是<b>真的</b>——
 * {@code SourceImportService#importStructured} → {@code OrderCreateService} → Postgres，
 * 断言直接查 {@code app.orders}。</p>
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "app.message-worker.enabled=false",
            "app.file-store.root=${java.io.tmpdir}/zimu-feixiang-json-pull-test"
        })
class FeixiangJsonPullIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired SourceImportService sourceImportService;
    @Autowired JdbcTemplate jdbc;

    @MockitoBean cn.zimu.fulfillment.connector.wecom.WecomConnectionManager ignoredWecomConnectionManager;

    /**
     * 同一容器内各用例共用一套业务表（导入走真事务，不回滚），所以每个用例用自己的来源单号
     * 前缀隔离，断言也只统计本前缀下的订单——与仓库既有集成测试用唯一单号的做法一致。
     */
    private static final java.util.concurrent.atomic.AtomicInteger TEST_SEQUENCE =
            new java.util.concurrent.atomic.AtomicInteger();

    private StubPullClient platform;
    private FeixiangConnector connector;
    private String prefix;

    @BeforeEach
    void setUp() {
        prefix = "FXIT" + TEST_SEQUENCE.incrementAndGet() + "-";
        platform = new StubPullClient(prefix);
        connector = FeixiangShipmentTestSupport.pullOnlyConnector(
                sourceImportService, platform, new FeixiangOrderTransform());
        mapSku("FX-SKU-0001");
        mapSku("FX-SKU-0002");
        mapSku("FX-SKU-0003");
    }

    /** 本用例专属的来源单号（加前缀后与其他用例互不干扰）。 */
    private String ref(String name) {
        return prefix + name;
    }

    /** 让飞象商品 ID 命中已有 SKU，订单行不因映射缺失进复核（本测试关心的是窗口，不是映射）。 */
    private void mapSku(String sourceSkuRef) {
        Long skuId = jdbc.queryForObject(
                "SELECT sku_id FROM app.provider_skus WHERE provider_sku_code='JD-SKU-000001'", Long.class);
        jdbc.update(
                "INSERT INTO app.source_channel_skus(source_channel,source_sku_ref,source_product_name,"
                        + "quantity_multiplier,sku_id,active) VALUES ('FEIXIANG',?,'子牧原切牛腱子500g*2',1,?,true) "
                        + "ON CONFLICT DO NOTHING",
                sourceSkuRef, skuId);
    }

    // ================================================================ 窗口真的生效

    /**
     * <b>本票的主验收</b>：窗口含 8-24 / 8-25 / 8-26 三天，每天各有订单，一次拉取必须把
     * 三天的单全部拿到——而不是只拿到「拉取当天」那一天的。
     */
    @Test
    void crossDayWindowImportsOrdersFromEveryDayInsideIt() {
        platform.order("1001", "D-FX-0824", "2026-08-24 09:00:00", "FX-SKU-0001", "2");
        platform.order("1002", "D-FX-0825", "2026-08-25 12:30:00", "FX-SKU-0002", "1");
        platform.order("1003", "D-FX-0826", "2026-08-26 16:58:00", "FX-SKU-0003", "3");

        PullResult result = connector.pullOrders(window("2026-08-24", "2026-08-26"));

        assertThat(result.status()).isEqualTo(PullResult.PullStatus.OK);
        assertThat(result.pulledCount()).isEqualTo(3);
        // 平台确实收到了真窗口参数（而不是旧的、会被忽略的 start_time/end_time）
        assertThat(platform.requestedWindows()).containsExactly(List.of("2026-08-24", "2026-08-26"));
        // 三天的单都真的落库了
        assertThat(sourceRefsInDb()).contains(ref("D-FX-0824"), ref("D-FX-0825"), ref("D-FX-0826"));
    }

    /**
     * 反证：同样三天的数据，若平台只按「当天」返回（旧实现的实际行为），拉取只会拿到 1 单。
     * 这条用例把「窗口生效」与「窗口失效」两种世界区分开——否则上一条测试拿到 3 单可能
     * 只是因为桩返回了 3 单，而不是因为窗口起了作用。
     */
    @Test
    void provesTheAssertionWouldFailUnderTheOldSingleDayBehaviour() {
        platform.order("1001", "D-FX-0824", "2026-08-24 09:00:00", "FX-SKU-0001", "2");
        platform.order("1002", "D-FX-0825", "2026-08-25 12:30:00", "FX-SKU-0002", "1");
        platform.order("1003", "D-FX-0826", "2026-08-26 16:58:00", "FX-SKU-0003", "3");
        // 模拟平台忽略窗口、只返回「拉取当天」下单的订单
        platform.ignoreWindowAndOnlyReturnDay("2026-08-26");

        PullResult result = connector.pullOrders(window("2026-08-24", "2026-08-26"));

        assertThat(result.pulledCount()).isEqualTo(1);
        assertThat(sourceRefsInDb()).containsOnly(ref("D-FX-0826"));
    }

    /** 窗口边界含首尾两天（8-24 与 8-26 都在窗口内）。 */
    @Test
    void windowIncludesBothBoundaryDays() {
        platform.order("1001", "D-FX-0824", "2026-08-24 00:00:01", "FX-SKU-0001", "1");
        platform.order("1002", "D-FX-0826", "2026-08-26 23:59:59", "FX-SKU-0002", "1");

        connector.pullOrders(window("2026-08-24", "2026-08-26"));

        assertThat(sourceRefsInDb()).contains(ref("D-FX-0824"), ref("D-FX-0826"));
    }

    // ================================================================ source_ordered_at

    /** {@code receive_info.create_time} 必须落到 {@code app.orders.source_ordered_at}（V64 列）。 */
    @Test
    void fillsSourceOrderedAtFromReceiveInfoCreateTime() {
        platform.order("1003", "D-FX-0826", "2026-08-26 16:58:00", "FX-SKU-0001", "2");

        connector.pullOrders(window("2026-08-24", "2026-08-26"));

        Instant orderedAt = jdbc.queryForObject(
                "SELECT source_ordered_at FROM app.orders WHERE source_channel='FEIXIANG' AND source_ref=?",
                Instant.class,
                ref("D-FX-0826"));
        assertThat(orderedAt)
                .isEqualTo(LocalDateTime.of(2026, 8, 26, 16, 58, 0).atZone(SHANGHAI).toInstant());
    }

    /** 每一单各自的下单时间，不是同一个导入时刻。 */
    @Test
    void keepsEachOrdersOwnOrderedAtInsteadOfOneImportTimestamp() {
        platform.order("1001", "D-FX-0824", "2026-08-24 09:00:00", "FX-SKU-0001", "1");
        platform.order("1002", "D-FX-0825", "2026-08-25 12:30:00", "FX-SKU-0002", "1");

        connector.pullOrders(window("2026-08-24", "2026-08-26"));

        assertThat(orderedAtOf("D-FX-0824"))
                .isEqualTo(LocalDateTime.of(2026, 8, 24, 9, 0, 0).atZone(SHANGHAI).toInstant());
        assertThat(orderedAtOf("D-FX-0825"))
                .isEqualTo(LocalDateTime.of(2026, 8, 25, 12, 30, 0).atZone(SHANGHAI).toInstant());
    }

    // ================================================================ 混批幂等（ea8fbb2 回归）

    /**
     * 混批幂等回归（对应 commit {@code ea8fbb2} 修复的生产事故）：一批里混有「上次已拉过的
     * 旧单」和「真正的新单」时，旧单跳过、新单必须正常入库，<b>整批不回滚</b>。
     *
     * <p>事故形态：只要「待发货」列表里还有任意一个此前导入过的旧单（还没发货就必然如此），
     * 每次刷新都会整批回滚，新单永远进不来，界面上却一直显示「OK，0 条新数据」。</p>
     */
    @Test
    void mixedBatchAcceptsNewOrdersAndSkipsDuplicatesWithoutRollingBackTheWholeBatch() {
        // 第一次拉取：旧单进库
        platform.order("1001", "D-FX-OLD", "2026-08-24 09:00:00", "FX-SKU-0001", "1");
        PullResult first = connector.pullOrders(window("2026-08-24", "2026-08-26"));
        assertThat(first.pulledCount()).isEqualTo(1);

        // 第二次拉取：列表里旧单还在（尚未发货），同时来了两张新单
        platform.order("1002", "D-FX-NEW-1", "2026-08-25 10:00:00", "FX-SKU-0002", "2");
        platform.order("1003", "D-FX-NEW-2", "2026-08-26 16:58:00", "FX-SKU-0003", "3");
        PullResult second = connector.pullOrders(window("2026-08-24", "2026-08-26"));

        assertThat(second.status()).isEqualTo(PullResult.PullStatus.OK);
        // 只有两张新单被接受，旧单跳过——不是整批 0
        assertThat(second.pulledCount()).isEqualTo(2);
        assertThat(sourceRefsInDb())
                .containsExactlyInAnyOrder(ref("D-FX-OLD"), ref("D-FX-NEW-1"), ref("D-FX-NEW-2"));
        // 旧单没有被重复建单
        assertThat(countOf("D-FX-OLD")).isEqualTo(1);
    }

    /**
     * 原样重拉同一批订单不会重复建单。
     *
     * <p>此时 {@code importStructured} 的内容哈希幂等会命中，直接返回<b>原批次</b>，因此
     * pulledCount 复述的是原批次的 accepted 数（1）而非「本次新增 0」——这是既有共享行为
     * （聚福宝同链路），不属本票范围。这里断言的是真正要紧的事实：库里仍然只有一张订单，
     * 没有重复建单，也没有报错。</p>
     */
    @Test
    void replayingTheExactSamePullDoesNotDuplicateTheOrder() {
        platform.order("1001", "D-FX-OLD", "2026-08-24 09:00:00", "FX-SKU-0001", "1");
        connector.pullOrders(window("2026-08-24", "2026-08-26"));

        PullResult again = connector.pullOrders(window("2026-08-24", "2026-08-26"));

        assertThat(again.ok()).isTrue();
        assertThat(countOf("D-FX-OLD")).isEqualTo(1);
        assertThat(sourceRefsInDb()).containsExactly(ref("D-FX-OLD"));
    }

    // ================================================================ 分页取全

    /** 超过单页容量（20）的窗口必须翻页取全，不静默截断在第 20 单。 */
    @Test
    void importsEveryOrderWhenTheWindowExceedsOnePage() {
        for (int index = 1; index <= 25; index++) {
            platform.order(
                    String.valueOf(2000 + index),
                    "D-FX-PAGE-" + index,
                    "2026-08-25 10:00:00",
                    "FX-SKU-0001",
                    "1");
        }

        PullResult result = connector.pullOrders(window("2026-08-24", "2026-08-26"));

        assertThat(result.pulledCount()).isEqualTo(25);
        assertThat(sourceRefsInDb()).hasSize(25);
    }

    // ================================================================ 标识符隔离

    /**
     * 五类 ID 各就各位地落库，没有一个被代入到别人的位置。
     *
     * <p>业务单号在 {@code app.orders.source_ref}；行标识与原始证据在
     * {@code app.raw_import_rows.raw_cells}（结构化导入的血缘落点）。</p>
     */
    @Test
    void storesEachIdentifierInItsOwnPlaceWithoutCrossover() {
        platform.order("88881", "D2026826346818550490", "2026-08-26 16:58:00", "FX-SKU-0001", "2");

        connector.pullOrders(window("2026-08-24", "2026-08-26"));

        // 来源单号只能是订单号（D…）
        assertThat(sourceRefsInDb()).containsExactly(ref("D2026826346818550490"));

        Map<String, Object> lineage = jdbc.queryForMap(
                """
                SELECT raw_cells->>'source_line_ref'                        AS line_ref,
                       raw_cells->'snapshot'->>'order_son_id'               AS order_son_id,
                       raw_cells->'snapshot'->>'order_id'                   AS order_id,
                       raw_cells->'snapshot'->>'order_sn'                   AS order_sn,
                       raw_cells->'snapshot'->'order_product'->0->>'order_product_id' AS order_product_id,
                       raw_cells->'snapshot'->'order_product'->0->>'product_id'       AS product_id
                FROM app.raw_import_rows WHERE source_order_ref=?
                """,
                ref("D2026826346818550490"));

        // 行标识是子订单号（S…），不是 order_son_id、不是订单号
        assertThat(lineage.get("line_ref")).isEqualTo("S88881");
        // 详情接口专用的数字 ID 只出现在自己的字段里
        assertThat(lineage.get("order_son_id")).isEqualTo("88881");
        assertThat(lineage.get("order_id")).isEqualTo("70001");
        assertThat(lineage.get("order_sn")).isEqualTo(ref("D2026826346818550490"));
        // 商品行 ID 与商品 ID 互不代入
        assertThat(lineage.get("order_product_id")).isEqualTo("688881");
        assertThat(lineage.get("product_id")).isEqualTo("FX-SKU-0001");
        assertThat(lineage.values()).doesNotHaveDuplicates();
    }

    // ================================================================ 工具

    private PullCursor window(String beginDay, String endDay) {
        return PullCursor.initial(
                OffsetDateTime.parse(beginDay + "T00:00:00+08:00").withOffsetSameInstant(ZoneOffset.UTC),
                OffsetDateTime.parse(endDay + "T23:59:59+08:00").withOffsetSameInstant(ZoneOffset.UTC));
    }

    private List<String> sourceRefsInDb() {
        return jdbc.queryForList(
                "SELECT source_ref FROM app.orders "
                        + "WHERE data_scope='BUSINESS' AND source_channel='FEIXIANG' AND source_ref LIKE ?",
                String.class,
                prefix + "%");
    }

    private Instant orderedAtOf(String name) {
        return jdbc.queryForObject(
                "SELECT source_ordered_at FROM app.orders WHERE source_channel='FEIXIANG' AND source_ref=?",
                Instant.class,
                ref(name));
    }

    private int countOf(String name) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM app.orders WHERE source_channel='FEIXIANG' AND source_ref=?",
                Integer.class,
                ref(name));
        return count == null ? 0 : count;
    }

    /**
     * 飞象平台桩：按下单日期存订单，{@link #listPendingOrders} 只返回窗口内的单
     * ——这正是真实平台<b>应该</b>有的行为，也是本票要让它真正生效的语义。
     */
    private static final class StubPullClient implements FeixiangPullClient {

        private final Map<String, StubOrder> orders = new LinkedHashMap<>();
        private final List<List<String>> requestedWindows = new ArrayList<>();
        private final String prefix;
        private String forcedSingleDay;

        StubPullClient(String prefix) {
            this.prefix = prefix;
        }

        /** orderSn 自动加本用例前缀；order_son_id 保持原样（桩内部键，天然按用例隔离）。 */
        void order(String orderSonId, String orderSn, String createTime, String productId, String pronum) {
            orders.put(orderSonId, new StubOrder(orderSonId, prefix + orderSn, createTime, productId, pronum));
        }

        /** 模拟旧实现下平台的实际行为：忽略窗口，只返回某一天下单的订单。 */
        void ignoreWindowAndOnlyReturnDay(String day) {
            this.forcedSingleDay = day;
        }

        List<List<String>> requestedWindows() {
            return List.copyOf(requestedWindows);
        }

        @Override
        public LoginResult login() {
            return new LoginResult(true, "OK", "登录成功");
        }

        @Override
        public PendingOrderList listPendingOrders(String startCreateTime, String endCreateTime) {
            requestedWindows.add(List.of(startCreateTime, endCreateTime));
            LocalDate begin = LocalDate.parse(startCreateTime);
            LocalDate end = LocalDate.parse(endCreateTime);
            List<String> matched = orders.values().stream()
                    .filter(order -> forcedSingleDay == null
                            ? !order.day().isBefore(begin) && !order.day().isAfter(end)
                            : order.day().equals(LocalDate.parse(forcedSingleDay)))
                    .map(StubOrder::orderSonId)
                    .toList();
            return new PendingOrderList(matched, matched.size(), false);
        }

        @Override
        public FeixiangOrderDetail fetchOrderDetail(String orderSonId) {
            StubOrder order = orders.get(orderSonId);
            if (order == null) {
                throw new PullTransportException("未知 order_son_id");
            }
            try {
                return FeixiangOrderDetail.from(MAPPER.readTree(order.detailJson()).path("data"));
            } catch (Exception exception) {
                throw new PullTransportException("桩数据构造失败", exception);
            }
        }
    }

    private record StubOrder(
            String orderSonId, String orderSn, String createTime, String productId, String pronum) {

        LocalDate day() {
            return LocalDate.parse(createTime.substring(0, 10));
        }

        String detailJson() {
            return """
                    {"status":1,"msg":"ok","data":{
                      "order_product":[{
                        "order_id":"70001","order_son_id":"%s","order_product_id":"6%s",
                        "product_id":"%s","title":"子牧原切牛腱子500g*2","product_spec_name":"500g*2",
                        "pronum":"%s","member_price":"106.00","express_code":"","sn":"",
                        "express_state":"0","prostate":"2","pro_state_name":"待发货",
                        "pro_status_name":"正常","delivery_remark":"","supplier_id":"1",
                        "supplier_name":"子牧食品"}],
                      "receive_info":{
                        "order_id":"70001","order_son_id":"%s","order_sn":"%s","order_son_sn":"S%s",
                        "state":"2","num":"%s","send_num":"0",
                        "create_time":"%s","pay_time":"%s","send_time":"",
                        "name":"张三","phone":"13800000001",
                        "area_name":"上海市","address":"某某路 1 号"}}}
                    """.formatted(
                    orderSonId, orderSonId, productId, pronum,
                    orderSonId, orderSn, orderSonId, pronum, createTime, createTime);
        }
    }
}
