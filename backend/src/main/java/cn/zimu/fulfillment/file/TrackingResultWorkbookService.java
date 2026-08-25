package cn.zimu.fulfillment.file;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 回填结果工作簿：把发货清单模板的结果列用**已回填的真实事实**填满，供回发给来件人。
 *
 * <p>与 {@code ProviderFileService} 生成的发货清单是同一套表头（{@code THIRD_PARTY_HEADERS}），
 * 区别只在后六列：发出时是空的（留给履约方填），回来时由本服务用
 * {@code trackings} / {@code shipment_items} 的落库事实填上。
 * 同表头是刻意的——对方拿到的是自己那张表被填好，而不是另一张需要重新对齐的表。
 *
 * <p><b>只读已落库事实，不做任何推断</b>：没有运单就写空，不猜"应该已发"；
 * 实发数量取 {@code shipped_quantity} 的真实值，与请求数不符时如实呈现差异，
 * 这正是对账要看的东西。
 */
@Service
public class TrackingResultWorkbookService {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbc;

    public TrackingResultWorkbookService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 一张发货批次的回填结果行。 */
    public record ResultRow(
            String shipmentNo,
            String outboundOrderNo,
            String orderNo,
            String sourceChannel,
            String sourceRef,
            int lineNo,
            String receiverName,
            String providerSkuCode,
            String productName,
            String specification,
            String unit,
            BigDecimal requestedQuantity,
            BigDecimal shippedQuantity,
            String carrierName,
            String trackingNumber,
            String shippedAt,
            String exceptionReason) {}

    /** 某个发货批次是否已有可回发的回填结果（无运单即无结果，不生成空表骗人）。 */
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public List<ResultRow> rows(long shipmentId) {
        return jdbc.query(
                """
                SELECT s.shipment_no, s.outbound_order_no, o.order_no, o.source_channel, o.source_ref,
                       ol.line_no, s.receiver_name_snapshot,
                       ps.provider_sku_code, ol.product_name_snapshot, ol.specification_snapshot,
                       ol.unit_snapshot, si.instructed_quantity, si.shipped_quantity,
                       t.logistics_company_name, t.tracking_number, t.received_at,
                       s.failure_reason
                FROM app.shipments s
                JOIN app.orders o ON o.id = s.order_id
                JOIN app.shipment_items si ON si.shipment_id = s.id
                JOIN app.fulfillments f ON f.id = si.fulfillment_id
                JOIN app.order_lines ol ON ol.id = f.order_line_id
                LEFT JOIN app.provider_skus ps
                       ON ps.sku_id = ol.sku_id AND ps.fulfillment_provider_id = s.fulfillment_provider_id
                LEFT JOIN app.trackings t ON t.shipment_id = s.id
                WHERE s.id = ?
                ORDER BY ol.line_no
                """,
                (rs, row) -> new ResultRow(
                        rs.getString("shipment_no"),
                        rs.getString("outbound_order_no"),
                        rs.getString("order_no"),
                        rs.getString("source_channel"),
                        rs.getString("source_ref"),
                        rs.getInt("line_no"),
                        rs.getString("receiver_name_snapshot"),
                        rs.getString("provider_sku_code"),
                        rs.getString("product_name_snapshot"),
                        rs.getString("specification_snapshot"),
                        rs.getString("unit_snapshot"),
                        rs.getBigDecimal("instructed_quantity"),
                        rs.getBigDecimal("shipped_quantity"),
                        rs.getString("logistics_company_name"),
                        rs.getString("tracking_number"),
                        rs.getTimestamp("received_at") == null
                                ? null
                                : STAMP.format(rs.getTimestamp("received_at").toInstant().atZone(SHANGHAI)),
                        rs.getString("failure_reason")),
                shipmentId);
    }

    /**
     * 生成回填结果 xlsx。表头与发货清单一致；后六列为回填事实。
     *
     * @throws IllegalArgumentException 行集为空时——没有事实就不产出文件，
     *         发一张空表比不发更容易被误读成「已发货但没运单」。
     */
    public byte[] workbook(List<ResultRow> rows) {
        if (rows == null || rows.isEmpty()) {
            throw new IllegalArgumentException("没有可回发的回填结果");
        }
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("发货回填结果");
            var header = sheet.createRow(0);
            List<String> headers = ProviderFileService.THIRD_PARTY_HEADERS;
            for (int i = 0; i < headers.size(); i++) {
                header.createCell(i).setCellValue(headers.get(i));
            }
            int lineNo = 1;
            for (ResultRow row : rows) {
                var xlsxRow = sheet.createRow(lineNo++);
                List<String> values = values(row);
                for (int i = 0; i < headers.size() && i < values.size(); i++) {
                    xlsxRow.createCell(i).setCellValue(values.get(i));
                }
            }
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("无法生成回填结果文件", exception);
        }
    }

    /** 与 THIRD_PARTY_HEADERS 顺序严格对齐；缺失事实写空串而不是占位文案。 */
    private List<String> values(ResultRow row) {
        List<String> values = new ArrayList<>();
        values.add(text(row.outboundOrderNo()));          // 导出批次号（回填态用出库单号标识）
        values.add(text(row.outboundOrderNo()));          // 出库单号
        values.add(String.valueOf(row.lineNo()));         // 导出明细号
        values.add("");                                   // 履约方编码
        values.add("");                                   // 履约方名称
        values.add(text(row.orderNo()));                  // 内部订单号
        values.add(text(row.sourceChannel()));            // 来源渠道
        values.add(text(row.sourceRef()));                // 来源订单号
        values.add(String.valueOf(row.lineNo()));         // 订单行号
        values.add("");                                   // 礼包分组标识
        values.add(text(row.receiverName()));             // 收件人
        values.add("");                                   // 电话（PII：回填结果表不带联系方式）
        values.add("");                                   // 地址（同上）
        values.add(text(row.providerSkuCode()));          // 履约方SKU编码
        values.add(text(row.productName()));              // 品名
        values.add(text(row.specification()));            // 规格
        values.add(text(row.unit()));                     // 单位
        values.add(number(row.requestedQuantity()));      // 请求发货数量
        values.add(row.trackingNumber() == null ? "" : "已发货");  // 结果
        values.add(number(row.shippedQuantity()));        // 实际发货数量
        values.add(text(row.carrierName()));              // 快递公司
        values.add(text(row.trackingNumber()));           // 物流单号
        values.add(text(row.shippedAt()));                // 发货时间
        values.add(text(row.exceptionReason()));          // 异常原因
        return values;
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static String number(BigDecimal value) {
        return value == null ? "" : value.stripTrailingZeros().toPlainString();
    }
}
