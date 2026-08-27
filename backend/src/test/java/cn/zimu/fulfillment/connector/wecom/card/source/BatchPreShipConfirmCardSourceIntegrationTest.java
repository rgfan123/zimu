package cn.zimu.fulfillment.connector.wecom.card.source;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zimu.fulfillment.connector.wecom.WecomMediaType;
import cn.zimu.fulfillment.connector.wecom.card.WecomBusinessCardSource;
import cn.zimu.fulfillment.connector.wecom.card.WecomTaskId;
import java.io.ByteArrayInputStream;
import java.time.OffsetDateTime;
import java.util.List;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 整批发货前确认卡（一批一卡）的事实链：
 * 扫描按「批内订单全部就绪」出卡，版本 = lock_version 之和，任何一单变动即作废旧卡；
 * 明细附件小批出图、大批出 Excel，全部快照口径；导入批次的订单不再走单卡。
 */
@org.testcontainers.junit.jupiter.Testcontainers
@org.springframework.boot.test.context.SpringBootTest
class BatchPreShipConfirmCardSourceIntegrationTest {

    @org.testcontainers.junit.jupiter.Container
    @org.springframework.boot.testcontainers.service.connection.ServiceConnection
    static final org.testcontainers.containers.PostgreSQLContainer<?> postgres =
            new org.testcontainers.containers.PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired JdbcTemplate jdbc;
    @Autowired BatchPreShipConfirmCardSource source;
    @Autowired PreShipConfirmCardSource perOrderSource;

    private long seedBatch(String suffix, int orderCount) {
        Long customerId = jdbc.queryForObject(
                "SELECT id FROM app.customers ORDER BY id LIMIT 1", Long.class);
        Long skuId = jdbc.queryForObject(
                "SELECT id FROM app.skus WHERE active AND fulfillment_provider_id IS NOT NULL ORDER BY id LIMIT 1",
                Long.class);
        Long providerId = jdbc.queryForObject(
                "SELECT fulfillment_provider_id FROM app.skus WHERE id = ?", Long.class, skuId);
        Long batchId = jdbc.queryForObject(
                """
                INSERT INTO app.import_batches
                    (batch_no, batch_type, import_mode, revision_no, source_channel,
                     template_family, template_version, template_fingerprint, original_file_name,
                     content_sha256, file_ref, status, uploaded_by)
                VALUES ('IMP-BATCHCARD-' || ?, 'SOURCE_ORDER', 'NEW', 1, 'DAZHE',
                        'DAZHE_SOURCE_ORDER', 'v1', 'DAZHE-batchcard-' || ?, 'orders.xlsx',
                        md5(?) || md5(? || '-2'), 'file://batchcard-' || ?, 'COMPLETED', 'batch-card-test')
                RETURNING id
                """, Long.class, suffix, suffix, suffix, suffix, suffix);
        for (int i = 1; i <= orderCount; i++) {
            Long orderId = jdbc.queryForObject(
                    """
                    INSERT INTO app.orders
                        (order_no, data_scope, source_channel, source_ref, source_ref_kind, source_version,
                         source_import_batch_id, customer_id, order_status, settlement_method, settlement_time,
                         receiver_name, receiver_phone, receiver_address)
                    VALUES ('ORD-BATCHCARD-' || ? || '-' || ?, 'BUSINESS', 'DAZHE', 'spr01-' || ? || '-' || ?,
                            'PROVIDED', 'v1', ?, ?, 'SKU_MAPPED', 'OTHER', now(),
                            '收件人' || ?, '1380000' || lpad(?::text, 4, '0'), '北京市朝阳区批量测试路 ' || ? || ' 号')
                    RETURNING id
                    """,
                    Long.class, suffix, i, suffix, i, batchId, customerId, i, i, i);
            jdbc.update(
                    """
                    INSERT INTO app.order_lines
                        (order_id, line_no, line_type, sku_id, fulfillment_provider_id,
                         product_name_snapshot, specification_snapshot, unit_snapshot,
                         requested_quantity, processing_stage)
                    VALUES (?, 1, 'SINGLE', ?, ?, '渠道品名' || ?, '来源未提供', '件', 2.000, 'READY_TO_EXPORT')
                    """,
                    orderId, skuId, providerId, i);
        }
        return batchId;
    }

    private long batchVersion(long batchId) {
        Long sum = jdbc.queryForObject(
                "SELECT COALESCE(sum(lock_version), 0) FROM app.orders WHERE source_import_batch_id = ?",
                Long.class, batchId);
        return sum == null ? 0 : sum;
    }

    @Test
    void 全批就绪出一张卡_版本为锁版本之和_小批附件是图片() throws Exception {
        long batchId = seedBatch("S1", 2);
        long version = batchVersion(batchId);

        List<WecomTaskId> pending = source.pending(OffsetDateTime.now().minusDays(1), 50);
        assertThat(pending)
                .extracting(WecomTaskId::value)
                .contains("preship-batch_" + batchId + "_v" + version);

        var card = source.render(batchId, version);
        assertThat(card).isPresent();
        assertThat(card.get().path("main_title").path("desc").asText()).isEqualTo("2 单 · 共 4 件");

        List<WecomBusinessCardSource.Attachment> attachments = source.attachments(batchId, version);
        assertThat(attachments).hasSize(1);
        assertThat(attachments.getFirst().filename()).endsWith("待确认清单.png");
        assertThat(attachments.getFirst().mediaType()).isEqualTo(WecomMediaType.IMAGE);
        assertThat(javax.imageio.ImageIO.read(
                        new ByteArrayInputStream(attachments.getFirst().content())))
                .isNotNull();

        // 版本不符（旧卡）：卡与附件一起作废
        assertThat(source.render(batchId, version + 1)).isEmpty();
        assertThat(source.attachments(batchId, version + 1)).isEmpty();
    }

    @Test
    void 大批附件是Excel_行数与订单数一致() throws Exception {
        long batchId = seedBatch("S2", 11);
        long version = batchVersion(batchId);

        List<WecomBusinessCardSource.Attachment> attachments = source.attachments(batchId, version);
        assertThat(attachments).hasSize(1);
        assertThat(attachments.getFirst().filename()).endsWith("待确认清单.xlsx");
        assertThat(attachments.getFirst().mediaType()).isEqualTo(WecomMediaType.FILE);
        try (XSSFWorkbook wb = new XSSFWorkbook(
                new ByteArrayInputStream(attachments.getFirst().content()))) {
            assertThat(wb.getSheet("待确认清单").getLastRowNum()).isEqualTo(11);
            assertThat(wb.getSheet("待确认清单").getRow(1).getCell(4).getStringCellValue())
                    .contains("北京市朝阳区批量测试路");
        }
    }

    @Test
    void 任何一单变动_旧卡作废_新版本重新出卡() {
        long batchId = seedBatch("S3", 2);
        long oldVersion = batchVersion(batchId);

        jdbc.update(
                """
                UPDATE app.orders SET lock_version = lock_version + 1, updated_at = now()
                WHERE id = (SELECT min(id) FROM app.orders WHERE source_import_batch_id = ?)
                """,
                batchId);

        assertThat(source.render(batchId, oldVersion)).isEmpty();
        assertThat(source.pending(OffsetDateTime.now().minusDays(1), 50))
                .extracting(WecomTaskId::value)
                .contains("preship-batch_" + batchId + "_v" + (oldVersion + 1));
    }

    @Test
    void 导入批次的订单不再走单卡_一批一卡() {
        long batchId = seedBatch("S4", 2);

        List<Long> orderIds = jdbc.query(
                "SELECT id FROM app.orders WHERE source_import_batch_id = ?",
                (rs, n) -> rs.getLong(1), batchId);
        assertThat(perOrderSource.pending(OffsetDateTime.now().minusDays(1), 500))
                .extracting(WecomTaskId::entityId)
                .doesNotContainAnyElementsOf(orderIds);
    }
}
