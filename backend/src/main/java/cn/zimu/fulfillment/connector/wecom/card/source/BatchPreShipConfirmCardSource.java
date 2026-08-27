package cn.zimu.fulfillment.connector.wecom.card.source;

import cn.zimu.fulfillment.connector.wecom.WecomMediaType;
import cn.zimu.fulfillment.connector.wecom.card.BatchPreShipConfirmCard;
import cn.zimu.fulfillment.connector.wecom.card.PreShipConfirmCard;
import cn.zimu.fulfillment.connector.wecom.card.WecomBusinessCardRouteProperties;
import cn.zimu.fulfillment.connector.wecom.card.WecomBusinessCardSource;
import cn.zimu.fulfillment.connector.wecom.card.WecomTaskId;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 整批发货前确认卡来源（一批一卡）。
 *
 * <p><b>快照口径</b>：清单与卡面全部取订单行快照（product_name_snapshot /
 * order_line_components 快照），不回读主数据——确认的必须是建单那一刻定格的事实。
 * 事后主数据变了不影响已发出的清单；订单本身变了由版本和（lock_version 之和）作废旧卡。
 *
 * <p><b>明细载体</b>：≤{@value IMAGE_ROW_LIMIT} 单渲成 PNG 表（手机上点开即看），
 * 更多落 Excel。两者都在发卡前作为独立消息投递，PII（电话/地址）只进单聊。
 *
 * <p><b>只进单聊</b>：与单卡同一道 PII 门闩；群聊配置一律拒绝。路由沿用
 * preship 的会话配置——两张卡给同一个人看，不该要求重复配置。
 */
@Service
public class BatchPreShipConfirmCardSource implements WecomBusinessCardSource {

    private static final Logger log = LoggerFactory.getLogger(BatchPreShipConfirmCardSource.class);

    /** 小批切图片的上限：超过这个行数的表格图在手机上比 Excel 更难读。 */
    static final int IMAGE_ROW_LIMIT = 10;

    private static final String RENDERABLE_STATUSES = "('SKU_MAPPED', 'FULFILLING')";

    private final JdbcTemplate jdbc;
    private final WecomBusinessCardRouteProperties routes;
    private final CardDeepLinks links;
    private final PendingListImageRenderer imageRenderer;

    public BatchPreShipConfirmCardSource(
            JdbcTemplate jdbc,
            WecomBusinessCardRouteProperties routes,
            CardDeepLinks links,
            PendingListImageRenderer imageRenderer) {
        this.jdbc = jdbc;
        this.routes = routes;
        this.links = links;
        this.imageRenderer = imageRenderer;
    }

    @Override
    public String domain() {
        return BatchPreShipConfirmCard.DOMAIN;
    }

    /** PII 门闩与单卡同源；路由复用 preship 的配置，配了群聊一律不发。 */
    @Override
    public Optional<Route> route(long entityId) {
        Optional<Route> configured = routes.resolve(domain())
                .or(() -> routes.resolve(PreShipConfirmCard.DOMAIN));
        if (configured.isPresent() && configured.get().type() != RouteType.SINGLE) {
            log.warn("整批发货前确认卡只能发单聊（随卡清单含收货人手机号与详细地址），本张卡不发");
            return Optional.empty();
        }
        return configured;
    }

    @Override
    public Optional<ObjectNode> render(long entityId, long entityVersion) {
        Optional<BatchFacts> facts = loadFacts(entityId);
        if (facts.isEmpty() || facts.get().version() != entityVersion) {
            // 批次已推进（有单发货/被改动/被取消）：旧卡作废
            return Optional.empty();
        }
        BatchFacts batch = facts.get();
        return Optional.of(BatchPreShipConfirmCard.render(new BatchPreShipConfirmCard.View(
                entityId,
                batch.version(),
                batch.sourceChannel(),
                batch.orderCount(),
                batch.totalQuantity(),
                batch.receiverBrief(),
                links.of("/operations?batch_no=" + batch.batchNo()))));
    }

    /**
     * 随卡清单：从订单行快照渲染，小批图片、大批 Excel。
     * 与 {@link #render} 同一套版本断言——事实变了连清单一起不发。
     */
    @Override
    public List<Attachment> attachments(long entityId, long entityVersion) {
        Optional<BatchFacts> facts = loadFacts(entityId);
        if (facts.isEmpty() || facts.get().version() != entityVersion) {
            return List.of();
        }
        BatchFacts batch = facts.get();
        List<PendingRow> rows = pendingRows(entityId);
        if (rows.isEmpty()) {
            return List.of();
        }
        String channelLabel = BatchPreShipConfirmCard.channelLabel(batch.sourceChannel());
        String date = LocalDate.now(ZoneId.of("Asia/Shanghai"))
                .format(java.time.format.DateTimeFormatter.ofPattern("M.d"));
        String baseName = "子牧" + channelLabel + date + "待确认清单";
        if (rows.size() <= IMAGE_ROW_LIMIT) {
            byte[] png = imageRenderer.render(
                    channelLabel + "整批待确认 · " + rows.size() + " 单",
                    PendingRow.HEADERS,
                    rows.stream().map(PendingRow::cells).toList());
            return List.of(new Attachment(baseName + ".png", png, WecomMediaType.IMAGE));
        }
        return List.of(new Attachment(baseName + ".xlsx", workbook(rows), WecomMediaType.FILE));
    }

    @Override
    public List<WecomTaskId> pending(OffsetDateTime since, int limit) {
        return jdbc.query(
                """
                SELECT b.id, sum(o.lock_version) AS batch_version
                FROM app.import_batches b
                JOIN app.orders o ON o.source_import_batch_id = b.id AND o.data_scope = 'BUSINESS'
                WHERE b.batch_type = 'SOURCE_ORDER'
                GROUP BY b.id
                HAVING bool_and(o.order_status IN """ + RENDERABLE_STATUSES + """
                       )
                   AND bool_or(o.order_status = 'SKU_MAPPED')
                   AND max(o.updated_at) >= ?
                   AND NOT EXISTS (
                       SELECT 1 FROM app.wecom_business_cards c
                       WHERE c.card_domain = 'preship-batch'
                         AND c.entity_id = b.id
                         AND c.entity_version = sum(o.lock_version)
                   )
                ORDER BY max(o.updated_at)
                LIMIT ?
                """,
                (rs, rowNum) -> WecomTaskId.ofVersion(
                        BatchPreShipConfirmCard.DOMAIN, rs.getLong("id"), rs.getLong("batch_version")),
                since,
                limit);
    }

    /** 批级事实：版本和、单数、件数、收件人预览。窗口外（有单已发/取消）返回 empty。 */
    private Optional<BatchFacts> loadFacts(long batchId) {
        List<BatchFacts> rows = jdbc.query(
                """
                SELECT b.batch_no, b.source_channel,
                       sum(o.lock_version)  AS batch_version,
                       count(*)             AS order_count,
                       (SELECT COALESCE(sum(CASE WHEN l.sku_id IS NULL
                                                 THEN (SELECT COALESCE(sum(c.total_quantity), 0)
                                                         FROM app.order_line_components c
                                                        WHERE c.order_line_id = l.id)
                                                 ELSE l.requested_quantity END), 0)
                          FROM app.order_lines l
                          JOIN app.orders o2 ON o2.id = l.order_id
                         WHERE o2.source_import_batch_id = b.id) AS total_quantity,
                       string_agg(o.receiver_name, '、' ORDER BY o.id) AS receivers
                FROM app.import_batches b
                JOIN app.orders o ON o.source_import_batch_id = b.id AND o.data_scope = 'BUSINESS'
                WHERE b.id = ?
                GROUP BY b.id, b.batch_no, b.source_channel
                HAVING bool_and(o.order_status IN """ + RENDERABLE_STATUSES + """
                       )
                """,
                (rs, rowNum) -> new BatchFacts(
                        rs.getString("batch_no"),
                        rs.getString("source_channel"),
                        rs.getLong("batch_version"),
                        rs.getInt("order_count"),
                        PreShipConfirmCardSource.trimQuantity(rs.getBigDecimal("total_quantity")),
                        receiverBrief(rs.getString("receivers"), rs.getInt("order_count"))),
                batchId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
    }

    /** 收件人预览：前 3 位 + 等 N 人；字段行 26 字上限由构建器截断兜底。 */
    static String receiverBrief(String joined, int orderCount) {
        if (joined == null || joined.isBlank()) {
            return "-";
        }
        String[] names = joined.split("、");
        if (names.length <= 3) {
            return joined;
        }
        return names[0] + "、" + names[1] + "、" + names[2] + " 等" + orderCount + "人";
    }

    /** 逐单明细行，全部快照字段；礼包行取组件快照（与单卡渲染的 UNION 同构）。 */
    private List<PendingRow> pendingRows(long batchId) {
        return jdbc.query(
                """
                SELECT o.id, o.source_ref, o.receiver_name, o.receiver_phone, o.receiver_address,
                       g.jd_goods, g.total_quantity
                FROM app.orders o
                JOIN LATERAL (
                    SELECT COALESCE(sum(x.qty), 0) AS total_quantity,
                           string_agg(x.jd_name || ' ×'
                               || trim(to_char(x.qty, 'FM999999990')), '、') AS jd_goods
                    FROM (
                        SELECT p.product_name AS jd_name, l.requested_quantity AS qty
                        FROM app.order_lines l
                        JOIN app.skus s     ON s.id = l.sku_id
                        JOIN app.products p ON p.id = s.product_id
                        WHERE l.order_id = o.id
                        UNION ALL
                        SELECT c.product_name_snapshot, c.total_quantity
                        FROM app.order_lines l
                        JOIN app.order_line_components c ON c.order_line_id = l.id
                        WHERE l.order_id = o.id AND l.sku_id IS NULL
                    ) x
                ) g ON TRUE
                WHERE o.source_import_batch_id = ? AND o.data_scope = 'BUSINESS'
                  AND o.order_status IN """ + RENDERABLE_STATUSES + """
                ORDER BY o.id
                """,
                (rs, rowNum) -> new PendingRow(
                        rowNum + 1,
                        rs.getString("source_ref"),
                        rs.getString("receiver_name"),
                        rs.getString("receiver_phone"),
                        rs.getString("receiver_address"),
                        rs.getString("jd_goods"),
                        PreShipConfirmCardSource.trimQuantity(rs.getBigDecimal("total_quantity"))),
                batchId);
    }

    private byte[] workbook(List<PendingRow> rows) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSSFSheet sheet = wb.createSheet("待确认清单");
            int[] widths = {6, 22, 10, 14, 46, 42, 7};
            for (int i = 0; i < widths.length; i++) {
                sheet.setColumnWidth(i, widths[i] * 256);
            }
            XSSFFont bold = wb.createFont();
            bold.setBold(true);
            CellStyle headerStyle = wb.createCellStyle();
            headerStyle.setFont(bold);
            Row header = sheet.createRow(0);
            for (int i = 0; i < PendingRow.HEADERS.length; i++) {
                Cell cell = header.createCell(i);
                cell.setCellValue(PendingRow.HEADERS[i]);
                cell.setCellStyle(headerStyle);
            }
            int rowNo = 1;
            for (PendingRow row : rows) {
                Row sheetRow = sheet.createRow(rowNo++);
                String[] cells = row.cells();
                for (int i = 0; i < cells.length; i++) {
                    sheetRow.createCell(i).setCellValue(cells[i] == null ? "" : cells[i]);
                }
            }
            wb.write(out);
            return out.toByteArray();
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("待确认清单生成失败", ex);
        }
    }

    private record BatchFacts(
            String batchNo,
            String sourceChannel,
            long version,
            int orderCount,
            String totalQuantity,
            String receiverBrief) {}

    record PendingRow(
            int seq,
            String sourceRef,
            String receiverName,
            String receiverPhone,
            String receiverAddress,
            String goods,
            String quantity) {

        static final String[] HEADERS = {"序号", "渠道单号", "收件人", "电话", "收货地址", "发货明细", "件数"};

        String[] cells() {
            return new String[] {
                String.valueOf(seq), sourceRef, receiverName, receiverPhone, receiverAddress, goods, quantity
            };
        }
    }
}
