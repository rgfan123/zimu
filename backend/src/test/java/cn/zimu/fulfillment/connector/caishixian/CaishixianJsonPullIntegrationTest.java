package cn.zimu.fulfillment.connector.caishixian;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.connector.PullCursor;
import cn.zimu.fulfillment.connector.PullResult;
import cn.zimu.fulfillment.connector.SourceShipmentArtifact;
import cn.zimu.fulfillment.connector.sync.SourceSyncFacts;
import cn.zimu.fulfillment.file.TrackingFileService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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
 * 彩食鲜 JSON 直连拉取集成测试（真库 Testcontainers；HTTP 全桩，绝不触真实平台）：
 * <ul>
 *   <li>orderList 按 totalNum 翻页取完 → importStructured 真落库，source_ordered_at 从
 *       orderTime 填充，orderCode/orderKey/platform_order_id 三个身份分别落血缘；</li>
 *   <li>单单 detail 失败只转人工复核，不废整批；</li>
 *   <li>重复拉取幂等：已存在订单逐单跳过、新订单照常入库（ea8fbb2 混批回归，结构化路径）；</li>
 *   <li>结构化批次的单 Shipment 回填工作簿重建（CaishixianShipmentArtifactFactory 结构化分支）
 *       与京东回填收口闸门（generateSourceReturn 对 structured:// 批次返回 null 不再抛异常）。</li>
 * </ul>
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "app.file-store.root=${java.io.tmpdir}/zimu-caishixian-json-pull-test")
class CaishixianJsonPullIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    @Autowired CaishixianConnector connector;
    @Autowired CaishixianShipmentArtifactFactory artifactFactory;
    @Autowired TrackingFileService trackingFileService;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean CaishixianPullClient pullClient;

    private static JsonNode listItem(String prefix, int sequence) {
        return json("""
                {"id": "%s-ID-%d", "orderCode": "%s-%d", "orderKey": "%s-%d-01", "orderStatus": 3,
                 "orderStatusEnumName": "待发货", "supplierCode": "20075684",
                 "receiverName": "收货人%d", "receiverTelephone": "1380000%04d",
                 "payTime": "2026-08-26 16:20:31", "orderTime": "2026-08-26 16:12:05",
                 "purchaseCode": "CG-%d", "vip": "0", "snCode": "SN-%d"}
                """.formatted(prefix, sequence, prefix, sequence, prefix, sequence,
                sequence, sequence, sequence, sequence));
    }

    private static JsonNode detail(int sequence) {
        return json("""
                {"receiverProvince": "河南省", "receiverCity": "郑州市",
                 "receiverDistrict": "金水区", "receiverAddress": "测试路 %d 号",
                 "expressRequirementCode": "ER1", "expressRequirementName": "常温",
                 "remark": "尽快发",
                 "supplierOrderGoodsVo": [
                   {"goodsCode": "G-%d", "goodsName": "羊小腿", "count": 2, "outCount": 0,
                    "spec": "2kg/箱", "unit": "箱"}
                 ]}
                """.formatted(sequence, sequence));
    }

    private static JsonNode json(String text) {
        try {
            return JSON.readTree(text);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static CaishixianPullClient.OrderPage page(
            int pageNum, int totalNum, Integer waitDepotNum, List<JsonNode> orders) {
        return new CaishixianPullClient.OrderPage(pageNum, totalNum, orders, waitDepotNum, Map.of());
    }

    private static List<JsonNode> items(String prefix, int fromInclusive, int toInclusive) {
        List<JsonNode> list = new ArrayList<>();
        for (int sequence = fromInclusive; sequence <= toInclusive; sequence++) {
            list.add(listItem(prefix, sequence));
        }
        return list;
    }

    private void loginOk() {
        when(pullClient.login())
                .thenReturn(new CaishixianPullClient.LoginResult(true, "OK", "登录成功", "token-1"));
    }

    private PullCursor windowCursor() {
        OffsetDateTime since = OffsetDateTime.now().minusDays(30);
        return PullCursor.initial(since, OffsetDateTime.now());
    }

    @Test
    void paginatedPullPersistsAllOrdersWithSourceOrderedAtAndSeparatedIds() {
        String prefix = "PAGE" + System.nanoTime() % 100000;
        loginOk();
        // totalNum=12 > 单页 10：两页取完（验收「orderList 翻页取完」的落库证明）
        when(pullClient.pullOrderPage(eq("token-1"), anyString(), anyString(), eq(1), anyInt()))
                .thenReturn(page(1, 12, 12, items(prefix, 1, 10)));
        when(pullClient.pullOrderPage(eq("token-1"), anyString(), anyString(), eq(2), anyInt()))
                .thenReturn(page(2, 12, 12, items(prefix, 11, 12)));
        for (int sequence = 1; sequence <= 12; sequence++) {
            if (sequence == 12) {
                // 第 12 单 detail 失败：只该单转人工复核，其余照常入库
                when(pullClient.pullOrderDetail(eq("token-1"), eq(prefix + "-ID-" + sequence)))
                        .thenThrow(new CaishixianPullClient.PullTransportException("网络抖动"));
            } else {
                when(pullClient.pullOrderDetail(eq("token-1"), eq(prefix + "-ID-" + sequence)))
                        .thenReturn(detail(sequence));
            }
        }

        PullResult result = connector.pullOrders(windowCursor());

        assertThat(result.status()).isEqualTo(PullResult.PullStatus.OK);
        assertThat(result.pulledCount()).isEqualTo(11);
        assertThat(result.message()).contains("实取 12").contains("totalNum=12").contains("waitDepotNum=12");

        // 11 单真实落库；detail 失败的第 12 单没有订单、只有 NEED_REVIEW 血缘
        Integer orders = jdbc.queryForObject(
                "SELECT COUNT(*) FROM app.orders WHERE source_channel='CAISHIXIAN' AND source_ref LIKE ?",
                Integer.class, prefix + "-%");
        assertThat(orders).isEqualTo(11);
        Map<String, Object> review = jdbc.queryForMap(
                """
                SELECT status, error_code FROM app.raw_import_rows
                WHERE source_order_ref=? ORDER BY id LIMIT 1
                """,
                prefix + "-12");
        assertThat(review.get("status")).isEqualTo("NEED_REVIEW");
        assertThat(review.get("error_code")).isEqualTo(CaishixianOrderTransform.DETAIL_REVIEW_CODE);

        // source_ordered_at 从 orderTime（2026-08-26 16:12:05 Asia/Shanghai）填充——本票主修字段
        java.sql.Timestamp orderedAt = jdbc.queryForObject(
                "SELECT source_ordered_at FROM app.orders WHERE source_channel='CAISHIXIAN' AND source_ref=?",
                java.sql.Timestamp.class, prefix + "-1");
        assertThat(orderedAt.toInstant())
                .isEqualTo(LocalDateTime.parse("2026-08-26T16:12:05").atZone(SHANGHAI).toInstant());

        // ID 纪律落血缘：source_ref=orderCode；raw_cells.source_line_ref=orderKey；
        // snapshot.platform_order_id=平台内部 id——三者各就各位，绝不混用
        Map<String, Object> lineage = jdbc.queryForMap(
                """
                SELECT raw_cells->>'source_line_ref' line_ref,
                       raw_cells->'snapshot'->>'platform_order_id' platform_id,
                       raw_cells->'snapshot'->>'主订单编号' order_code,
                       raw_cells->'snapshot'->>'省' province
                FROM app.raw_import_rows WHERE source_order_ref=? LIMIT 1
                """,
                prefix + "-1");
        assertThat(lineage.get("line_ref")).isEqualTo(prefix + "-1-01");
        assertThat(lineage.get("platform_id")).isEqualTo(prefix + "-ID-1");
        assertThat(lineage.get("order_code")).isEqualTo(prefix + "-1");
        assertThat(lineage.get("province")).isEqualTo("河南省");
    }

    @Test
    void repeatedPullSkipsExistingOrdersWithoutRollingBackNewOnes() {
        String prefix = "IDEM" + System.nanoTime() % 100000;
        loginOk();
        when(pullClient.pullOrderDetail(eq("token-1"), anyString()))
                .thenAnswer(invocation -> detail(1));

        // 第一次拉取：只有 1 单
        when(pullClient.pullOrderPage(eq("token-1"), anyString(), anyString(), eq(1), anyInt()))
                .thenReturn(page(1, 1, 1, items(prefix, 1, 1)));
        PullResult first = connector.pullOrders(windowCursor());
        assertThat(first.status()).isEqualTo(PullResult.PullStatus.OK);
        assertThat(first.pulledCount()).isEqualTo(1);

        // 第二次拉取：旧单 + 新单混批（ea8fbb2 生产事故形态）——旧单逐单跳过，新单必须进来
        when(pullClient.pullOrderPage(eq("token-1"), anyString(), anyString(), eq(1), anyInt()))
                .thenReturn(page(1, 2, 2, items(prefix, 1, 2)));
        PullResult second = connector.pullOrders(windowCursor());

        assertThat(second.status()).isEqualTo(PullResult.PullStatus.OK);
        assertThat(second.pulledCount()).isEqualTo(1);
        Integer orders = jdbc.queryForObject(
                "SELECT COUNT(*) FROM app.orders WHERE source_channel='CAISHIXIAN' AND source_ref LIKE ?",
                Integer.class, prefix + "-%");
        assertThat(orders).isEqualTo(2);
        Integer newOrder = jdbc.queryForObject(
                "SELECT COUNT(*) FROM app.orders WHERE source_channel='CAISHIXIAN' AND source_ref=?",
                Integer.class, prefix + "-2");
        assertThat(newOrder).isEqualTo(1);

        // 第三次拉取与第二次内容完全相同：内容哈希幂等命中既有批次，不重复建单
        PullResult third = connector.pullOrders(windowCursor());
        assertThat(third.status()).isEqualTo(PullResult.PullStatus.OK);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM app.orders WHERE source_channel='CAISHIXIAN' AND source_ref LIKE ?",
                Integer.class, prefix + "-%")).isEqualTo(2);
        assertThat(third.importBatch().id()).isEqualTo(second.importBatch().id());
    }

    @Test
    void structuredShipmentArtifactRebuildsTemplateAndJdFinalizeGuardHolds() {
        String prefix = "ART" + System.nanoTime() % 100000;
        loginOk();
        when(pullClient.pullOrderPage(eq("token-1"), anyString(), anyString(), eq(1), anyInt()))
                .thenReturn(page(1, 1, 1, items(prefix, 1, 1)));
        when(pullClient.pullOrderDetail(eq("token-1"), anyString())).thenReturn(detail(1));
        PullResult pulled = connector.pullOrders(windowCursor());
        assertThat(pulled.pulledCount()).isEqualTo(1);

        String sourceRef = prefix + "-1";
        String sourceLineRef = prefix + "-1-01";
        long orderId = jdbc.queryForObject(
                "SELECT id FROM app.orders WHERE source_channel='CAISHIXIAN' AND source_ref=?",
                Long.class, sourceRef);
        long orderLineId = jdbc.queryForObject(
                "SELECT id FROM app.order_lines WHERE order_id=? ORDER BY line_no LIMIT 1",
                Long.class, orderId);
        long providerId = jdbc.queryForObject(
                """
                INSERT INTO app.fulfillment_providers(provider_code, provider_name, provider_type)
                VALUES (?, '彩食鲜集成履约方', 'THIRD_PARTY') RETURNING id
                """,
                Long.class, "CSX" + (System.nanoTime() % 100000));
        // 来源数量换算快照与履约方（正常由 SKU 映射/履约路由流程写入；此处按 1:1 直连补齐，
        // validate_fulfillment 触发器要求 fulfillments 与 order_lines 的履约方一致）
        jdbc.update(
                """
                UPDATE app.order_lines
                SET mapping_multiplier_snapshot=1, source_quantity_snapshot=2, fulfillment_provider_id=?
                WHERE id=?
                """,
                providerId, orderLineId);
        // 与 SourceShipmentSyncServiceIntegrationTest 同配方：先建 NOT_SHIPPED 再置 SHIPPED，
        // 避免与 fulfillments 的状态检查约束冲突
        long fulfillmentId = jdbc.queryForObject(
                """
                INSERT INTO app.fulfillments
                    (fulfillment_no, order_line_id, fulfillment_provider_id, requested_quantity,
                     cumulative_shipped_quantity, cancelled_quantity, shipping_progress, outcome)
                VALUES (?, ?, ?, 2, 0, 0, 'NOT_SHIPPED', 'IN_PROGRESS')
                RETURNING id
                """,
                Long.class, "FUL-" + prefix, orderLineId, providerId);
        long shipmentId = jdbc.queryForObject(
                """
                INSERT INTO app.shipments
                    (shipment_no, order_id, fulfillment_provider_id, shipment_sequence,
                     receiver_name_snapshot, receiver_phone_snapshot, receiver_address_snapshot,
                     shipment_status, shipped_at)
                VALUES (?, ?, ?, 1, '收货人1', '13800000001', '河南省郑州市金水区测试路 1 号',
                        'SHIPPED', CURRENT_TIMESTAMP)
                RETURNING id
                """,
                Long.class, "SHP-" + prefix, orderId, providerId);
        jdbc.update(
                """
                INSERT INTO app.shipment_items(shipment_id, fulfillment_id, instructed_quantity, shipped_quantity)
                VALUES (?, ?, 2, 2)
                """,
                shipmentId, fulfillmentId);
        jdbc.update(
                """
                UPDATE app.fulfillments
                SET cumulative_shipped_quantity=2, shipping_progress='SHIPPED',
                    outcome='FULLY_FULFILLED', updated_at=CURRENT_TIMESTAMP
                WHERE id=?
                """,
                fulfillmentId);

        SourceSyncFacts facts = new SourceSyncFacts(
                shipmentId,
                orderId,
                SourceChannel.CAISHIXIAN,
                sourceRef,
                sourceLineRef,
                "收货人1",
                "13800000001",
                "河南省郑州市金水区测试路 1 号",
                new BigDecimal("2"),
                new BigDecimal("2"),
                new BigDecimal("2"),
                "FULLY_FULFILLED",
                "JD",
                "京东物流",
                "JD",
                "JDVA-" + prefix);

        SourceShipmentArtifact artifact = artifactFactory.prepare(facts);

        assertThat(artifact.present()).isTrue();
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(artifact.content()))) {
            var sheet = workbook.getSheetAt(0);
            Row header = sheet.getRow(0);
            assertThat(header.getCell(0).getStringCellValue()).isEqualTo("主订单编号");
            assertThat(header.getCell(4).getStringCellValue()).isEqualTo("站点编码");
            assertThat(header.getLastCellNum()).isEqualTo((short) 22);
            Row line = sheet.getRow(1);
            assertThat(line.getCell(0).getStringCellValue()).isEqualTo(sourceRef);
            assertThat(line.getCell(1).getStringCellValue()).isEqualTo(sourceLineRef);
            assertThat(line.getCell(4).getStringCellValue()).isEmpty(); // 站点编码：JSON 已知缺失
            assertThat(line.getCell(5).getStringCellValue()).isEqualTo("收货人1");
            assertThat(line.getCell(7).getStringCellValue()).isEqualTo("河南省");
            assertThat(line.getCell(10).getStringCellValue()).isEqualTo("测试路 1 号");
            assertThat(line.getCell(13).getStringCellValue()).isEqualTo("G-1");
            assertThat(line.getCell(15).getStringCellValue()).isEqualTo("2");
            assertThat(line.getCell(17).getStringCellValue()).isEqualTo("2"); // 发货数量（实发/映射倍数）
            assertThat(line.getCell(18).getStringCellValue()).isEqualTo("JD");
            assertThat(line.getCell(19).getStringCellValue()).isEqualTo("JDVA-" + prefix);
            assertThat(line.getCell(21).getStringCellValue()).isEmpty(); // 错误原因：上传时留空
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(exception);
        }

        // 字节级确定性：同一 Shipment 事实两次生成产物完全一致（上传幂等哈希依赖它）
        SourceShipmentArtifact again = artifactFactory.prepare(facts);
        assertThat(again.sha256()).isEqualTo(artifact.sha256());

        // 京东回填收口闸门：结构化批次 generateSourceReturn 返回 null（不生成批次回填文件），
        // 绝不因 fileStore.read("structured://…") 抛异常波及发货主流程
        List<Long> finalized = trackingFileService.finalizeReadySourceReturnsForShipment(shipmentId, "test-op");
        assertThat(finalized).isEmpty();
    }
}
