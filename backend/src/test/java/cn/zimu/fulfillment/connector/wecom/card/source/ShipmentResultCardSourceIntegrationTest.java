package cn.zimu.fulfillment.connector.wecom.card.source;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zimu.fulfillment.connector.wecom.WecomMediaType;
import cn.zimu.fulfillment.connector.wecom.card.WecomBusinessCardSource;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 发货结果卡的两张核对图：原始文件事实与系统实际发货口径。 */
@Testcontainers
@SpringBootTest(properties = {
        "app.wecom-business-card.routes.preship.type=SINGLE",
        "app.wecom-business-card.routes.preship.chat-id=shipment-image-reviewer"
})
class ShipmentResultCardSourceIntegrationTest {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Container
    @org.springframework.boot.testcontainers.service.connection.ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired JdbcTemplate jdbc;
    @Autowired ShipmentResultCardSource source;

    @Test
    void 发货结果随附原始文件与系统整合后两张PNG() throws Exception {
        ShipmentFact fact = seedShipment(true);

        List<WecomBusinessCardSource.Attachment> attachments =
                source.attachments(fact.orderId(), fact.version());

        assertThat(attachments)
                .extracting(WecomBusinessCardSource.Attachment::filename)
                .containsExactly("原始文件订单.png", "系统整合后.png");
        assertThat(attachments)
                .extracting(WecomBusinessCardSource.Attachment::mediaType)
                .containsOnly(WecomMediaType.IMAGE);
        BufferedImage rawImage = ImageIO.read(new ByteArrayInputStream(attachments.get(0).content()));
        BufferedImage integratedImage = ImageIO.read(new ByteArrayInputStream(attachments.get(1).content()));
        assertThat(rawImage.getWidth()).isEqualTo(1744);
        assertThat(rawImage.getHeight()).isEqualTo(576);
        assertThat(integratedImage.getWidth()).isEqualTo(3104);
        for (WecomBusinessCardSource.Attachment attachment : attachments) {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(attachment.content()));
            assertThat(image).isNotNull();
            assertThat(attachment.content().length).isLessThan((int) WecomMediaType.IMAGE.maxSizeBytes());
        }
    }

    @Test
    void 无原始行时只出系统整合后_且事实版本变化后附件作废() {
        ShipmentFact fact = seedShipment(false);

        assertThat(source.attachments(fact.orderId(), fact.version()))
                .extracting(WecomBusinessCardSource.Attachment::filename)
                .containsExactly("系统整合后.png");
        assertThat(source.attachments(fact.orderId(), fact.version() + 1)).isEmpty();
    }

    @Test
    void 原始行超限时图片高度被截住_且体积小于10MB() throws Exception {
        ShipmentFact fact = seedShipment(true);
        Long batchId = jdbc.queryForObject(
                "SELECT source_import_batch_id FROM app.orders WHERE id=?", Long.class, fact.orderId());
        Long orderLineId = jdbc.queryForObject(
                "SELECT min(id) FROM app.order_lines WHERE order_id=?", Long.class, fact.orderId());
        jdbc.update(
                """
                INSERT INTO app.raw_import_rows
                    (import_batch_id, sheet_name, sheet_index, row_index, raw_cells, source_order_ref,
                     status, order_id, order_line_id)
                VALUES (?, '订单明细', 0, 4,
                        (SELECT jsonb_object_agg('字段' || n, '值' || n)
                           FROM generate_series(1, 80) AS n),
                        'TRUNCATED', 'ACCEPTED', ?, ?)
                """,
                batchId,
                fact.orderId(),
                orderLineId);

        WecomBusinessCardSource.Attachment raw = source.attachments(fact.orderId(), fact.version()).getFirst();
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(raw.content()));

        assertThat(image.getHeight()).isEqualTo(4032);
        assertThat(raw.content().length).isLessThan((int) WecomMediaType.IMAGE.maxSizeBytes());
    }

    private ShipmentFact seedShipment(boolean withRawRows) {
        int suffix = SEQUENCE.incrementAndGet();
        Long customerId = jdbc.queryForObject(
                "SELECT id FROM app.customers ORDER BY id LIMIT 1", Long.class);
        Long skuId = jdbc.queryForObject(
                "SELECT id FROM app.skus WHERE active AND fulfillment_provider_id IS NOT NULL ORDER BY id LIMIT 1",
                Long.class);
        Long providerId = jdbc.queryForObject(
                "SELECT fulfillment_provider_id FROM app.skus WHERE id=?", Long.class, skuId);
        Long batchId = jdbc.queryForObject(
                """
                INSERT INTO app.import_batches
                    (batch_no, batch_type, import_mode, revision_no, source_channel,
                     template_family, template_version, template_fingerprint, original_file_name,
                     content_sha256, file_ref, status, uploaded_by)
                VALUES ('IMP-SHIPIMG-' || ?, 'SOURCE_ORDER', 'NEW', 1, 'CAISHIXIAN',
                        'CAISHIXIAN_SHIPMENT_IMAGE', 'v1', 'shipimg-' || ?, '彩食鲜订单.xlsx',
                        md5(?::text) || md5(?::text || '-2'), 'file://shipimg-' || ?, 'COMPLETED', 'shipimg-test')
                RETURNING id
                """,
                Long.class,
                suffix,
                suffix,
                suffix,
                suffix,
                suffix);
        long version = 7L;
        Long orderId = jdbc.queryForObject(
                """
                INSERT INTO app.orders
                    (order_no, data_scope, source_channel, source_ref, source_ref_kind, source_version,
                     source_import_batch_id, customer_id, order_status, settlement_method, settlement_time,
                     receiver_name, receiver_phone, receiver_address, lock_version)
                VALUES ('ORD-SHIPIMG-' || ?, 'BUSINESS', 'CAISHIXIAN', 'CSX-SHIPIMG-' || ?, 'PROVIDED', 'v1',
                        ?, ?, 'SHIPPED', 'OTHER', now(), '张三', '13800000000',
                        '北京市朝阳区核对路 8 号', ?)
                RETURNING id
                """,
                Long.class,
                suffix,
                suffix,
                batchId,
                customerId,
                version);
        jdbc.update(
                """
                INSERT INTO app.order_lines
                    (order_id, line_no, line_type, sku_id, fulfillment_provider_id, product_name_snapshot,
                     sku_code_snapshot, specification_snapshot, unit_snapshot, requested_quantity, processing_stage)
                VALUES (?, 1, 'SINGLE', ?, ?, '子牧原切羊排', 'SKU-CSX-000001', '500g/袋', '袋', 2, 'COMPLETED'),
                       (?, 2, 'CUSTOM_BUNDLE', NULL, ?, '子牧羊肉礼盒', NULL, '6件/盒', '盒', 1, 'COMPLETED')
                """,
                orderId,
                skuId,
                providerId,
                orderId,
                providerId);
        if (withRawRows) {
            List<Long> orderLineIds = jdbc.query(
                    "SELECT id FROM app.order_lines WHERE order_id=? ORDER BY line_no",
                    (rs, rowNum) -> rs.getLong("id"),
                    orderId);
            jdbc.update(
                    """
                    INSERT INTO app.raw_import_rows
                        (import_batch_id, sheet_name, sheet_index, row_index, raw_cells, source_order_ref,
                         status, order_id, order_line_id)
                    VALUES (?, '订单明细', 0, 2,
                            '{"商品名称":"原切羊排","下单数量":"2","物流单号":"","错误原因":null}'::jsonb,
                            'CSX-SHIPIMG-' || ?, 'ACCEPTED', ?, ?),
                           (?, '订单明细', 0, 3,
                            '{"商品名称":"羊肉礼盒","下单数量":"1","采购单号":""}'::jsonb,
                            'CSX-SHIPIMG-' || ?, 'ACCEPTED', ?, ?)
                    """,
                    batchId,
                    suffix,
                    orderId,
                    orderLineIds.get(0),
                    batchId,
                    suffix,
                    orderId,
                    orderLineIds.get(1));
        }
        Long shipmentId = jdbc.queryForObject(
                """
                INSERT INTO app.shipments
                    (shipment_no, order_id, fulfillment_provider_id, shipment_sequence,
                     receiver_name_snapshot, receiver_phone_snapshot, receiver_address_snapshot,
                     shipment_status, shipped_at)
                VALUES ('SHP-SHIPIMG-' || ?, ?, ?, 1, '张三', '13800000000',
                        '北京市朝阳区核对路 8 号', 'SHIPPED', now())
                RETURNING id
                """,
                Long.class,
                suffix,
                orderId,
                providerId);
        String outboundOrderNo = jdbc.queryForObject(
                "SELECT outbound_order_no FROM app.shipments WHERE id=?", String.class, shipmentId);
        jdbc.update(
                """
                INSERT INTO app.shipment_jd_outbounds
                    (shipment_id, erp_delivery_no, jd_delivery_no, sync_status, submitted_at)
                VALUES (?, ?, 'JD-SHIPIMG-' || ?, 'SUBMITTED', now())
                """,
                shipmentId,
                outboundOrderNo,
                suffix);
        jdbc.update(
                """
                INSERT INTO app.trackings
                    (shipment_id, logistics_company_code, logistics_company_name, tracking_number)
                VALUES (?, 'SF', '顺丰速运', 'SF-SHIPIMG-' || ?)
                """,
                shipmentId,
                suffix);
        return new ShipmentFact(orderId, version);
    }

    private record ShipmentFact(long orderId, long version) {}
}
