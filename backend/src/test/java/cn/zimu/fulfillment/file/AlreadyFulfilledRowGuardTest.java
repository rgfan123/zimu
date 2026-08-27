package cn.zimu.fulfillment.file;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zimu.fulfillment.common.domain.SourceChannel;
import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

/**
 * 已发货的来源行不得被当成待发新单建出来。
 *
 * <p>2026-08-27 生产实证：用户经企微转发的中汇表里混着两行历史已发货单
 * （物流单号 SF1220303588771 / JDVA46735986612），解析器全当新单建了出来并推了确认卡。
 * 点下去就是给已发出的货再建一张真实京东出库单——重复发货，客户收两次。
 *
 * <p>数据取自真实文件的列结构，收件人/电话/地址已改为合成值（真实 PII 不进仓库）。
 */
class AlreadyFulfilledRowGuardTest {

    private static byte[] workbook(List<String> headers, List<List<String>> rows) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Sheet1");
            Row header = sheet.createRow(0);
            for (int i = 0; i < headers.size(); i++) header.createCell(i).setCellValue(headers.get(i));
            for (int r = 0; r < rows.size(); r++) {
                Row data = sheet.createRow(r + 1);
                List<String> values = rows.get(r);
                for (int c = 0; c < values.size(); c++) data.createCell(c).setCellValue(values.get(c));
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    // ---------------- 中汇 ----------------

    private static final List<String> ZH_HEADERS = List.of(
            "订单号", "商品编号", "商品名称", "件数", "收件人", "收件电话", "收件地址",
            "包装规格", "单位", "发货状态", "订单状态", "物流公司", "物流单号", "下单时间");

    private static List<String> zhonghuiRow(String shipState, String orderState, String carrier, String waybill) {
        Map<String, String> cells = new LinkedHashMap<>();
        cells.put("订单号", "S260826043042-3");
        cells.put("商品编号", "60043831");
        cells.put("商品名称", "子牧 原切牛肋条 500g*2");
        cells.put("件数", "1");
        cells.put("收件人", "测试甲");
        cells.put("收件电话", "13000000001");
        cells.put("收件地址", "北京朝阳区示例路 1 号 101");
        cells.put("包装规格", "500g*2");
        cells.put("单位", "份");
        cells.put("发货状态", shipState);
        cells.put("订单状态", orderState);
        cells.put("物流公司", carrier);
        cells.put("物流单号", waybill);
        cells.put("下单时间", "2026-08-26 15:56:25");
        return ZH_HEADERS.stream().map(cells::get).toList();
    }

    @Test
    void 中汇待发货行正常建单() throws Exception {
        byte[] bytes = workbook(ZH_HEADERS, List.of(zhonghuiRow("未发货", "待发货", "", "")));

        ParsedSourceFile parsed = new SourceFileParser().parse(bytes);

        assertThat(parsed.sourceChannel()).isEqualTo(SourceChannel.ZHONGHUI);
        assertThat(parsed.rows()).singleElement().matches(ParsedSourceRow::valid, "待发货行必须可建单");
    }

    @Test
    void 中汇已发货行被拦下_不重复建单() throws Exception {
        byte[] bytes = workbook(
                ZH_HEADERS, List.of(zhonghuiRow("已发货", "已发货", "顺丰速运", "SF1220303588771")));

        ParsedSourceRow row = new SourceFileParser().parse(bytes).rows().getFirst();

        assertThat(row.valid()).isFalse();
        assertThat(row.errorCode()).isEqualTo("SOURCE_ORDER_ALREADY_FULFILLED");
    }

    @Test
    void 中汇只有物流单号没标发货状态也算已履约() throws Exception {
        // 来源系统的状态列不总是可靠；有物流单号就是已经发过了
        byte[] bytes = workbook(
                ZH_HEADERS, List.of(zhonghuiRow("未发货", "待发货", "", "JDVA46735986612")));

        ParsedSourceRow row = new SourceFileParser().parse(bytes).rows().getFirst();

        assertThat(row.valid()).isFalse();
        assertThat(row.errorCode()).isEqualTo("SOURCE_ORDER_ALREADY_FULFILLED");
    }

    // ---------------- 飞象 ----------------

    private static final List<String> FX_HEADERS = List.of(
            "订单号", "订单商品ID", "商品名称", "可发货数量", "收货人姓名", "收货人手机号",
            "收货人地址", "物流状态", "物流公司", "物流单号", "下单时间");

    private static List<String> feixiangRow(String logisticsState, String carrier, String waybill) {
        return List.of(
                "D2026825436038809722", "43221162", "子牧原切筋头巴脑500g*2", "1",
                "测试乙", "13000000002", "北京海淀区示例街 2 号 202",
                logisticsState, carrier, waybill, "2026-08-25 15:40:03");
    }

    @Test
    void 飞象待发货行正常建单() throws Exception {
        byte[] bytes = workbook(FX_HEADERS, List.of(feixiangRow("待发货", "", "")));

        ParsedSourceFile parsed = new SourceFileParser().parse(bytes);

        assertThat(parsed.sourceChannel()).isEqualTo(SourceChannel.FEIXIANG);
        assertThat(parsed.rows()).singleElement().matches(ParsedSourceRow::valid, "待发货行必须可建单");
    }

    @Test
    void 飞象已发货行被拦下_同型缺陷一并堵上() throws Exception {
        byte[] bytes = workbook(FX_HEADERS, List.of(feixiangRow("已发货", "京东快递", "JDVA46735986612")));

        ParsedSourceRow row = new SourceFileParser().parse(bytes).rows().getFirst();

        assertThat(row.valid()).isFalse();
        assertThat(row.errorCode()).isEqualTo("SOURCE_ORDER_ALREADY_FULFILLED");
    }
}
