package cn.zimu.fulfillment.file;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zimu.fulfillment.common.domain.SourceChannel;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SourceFileCountQuantityContractTest {

    private final SourceFileParser parser = new SourceFileParser();

    @ParameterizedTest
    @ValueSource(strings = {"3", "3.000"})
    void everySourceFileAdapterNormalizesMathematicalIntegers(String rawQuantity) throws Exception {
        assertThat(parseEveryChannel(rawQuantity))
                .allSatisfy((channel, row) -> {
                    assertThat(row.valid()).as(channel.name()).isTrue();
                    assertThat(row.quantity()).as(channel.name()).isEqualTo(3);
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"3.5", "-1", "2147483648"})
    void everySourceFileAdapterRejectsFractionalNegativeAndOverflowCounts(String rawQuantity)
            throws Exception {
        assertThat(parseEveryChannel(rawQuantity))
                .allSatisfy((channel, row) -> {
                    assertThat(row.valid()).as(channel.name()).isFalse();
                    assertThat(row.errorCode()).as(channel.name())
                            .isIn("QUANTITY_SCALE", "IMPORT_VALIDATION");
                });
    }

    @Test
    void numericExcelCellCannotUseDisplayFormatToRoundAFractionIntoAnInteger() throws Exception {
        ParsedSourceRow row = onlyRow(caishixianNumericQuantity(2.5, "0"));

        assertThat(row.valid()).isFalse();
        assertThat(row.errorCode()).isEqualTo("QUANTITY_SCALE");
    }

    private Map<SourceChannel, ParsedSourceRow> parseEveryChannel(String quantity) throws Exception {
        Map<SourceChannel, ParsedSourceRow> result = new LinkedHashMap<>();
        result.put(SourceChannel.CAISHIXIAN, onlyRow(caishixian(quantity)));
        result.put(SourceChannel.JUFUBAO, onlyRow(jufubao(quantity)));
        result.put(SourceChannel.FEIXIANG, onlyRow(feixiang(quantity)));
        result.put(SourceChannel.ZHONGHUI, onlyRow(zhonghui(quantity)));
        result.put(SourceChannel.DAZHE, onlyRow(dazhe(quantity)));
        result.put(SourceChannel.WANQI, onlyRow(wanqi(quantity)));
        return result;
    }

    private ParsedSourceRow onlyRow(byte[] file) {
        ParsedSourceFile parsed = parser.parse(file);
        assertThat(parsed.rows()).singleElement();
        return parsed.rows().getFirst();
    }

    private byte[] caishixian(String quantity) throws Exception {
        return workbook(
                "待发货订单",
                List.of(
                        "主订单编号", "子订单编号", "供应商编码", "站点编码", "商品编号", "商品名称",
                        "下单数量", "收货人", "联系电话", "省", "市", "区", "详细地址", "规格", "单位"),
                List.of(
                        "CSX-ORDER-1", "CSX-LINE-1", "SUP-1", "SITE-1", "CSX-SKU-1", "羊棒骨",
                        quantity, "张三", "13800000000", "河南省", "郑州市", "金水区", "测试路1号", "500g", "份"));
    }

    private byte[] caishixianNumericQuantity(double quantity, String format) throws Exception {
        byte[] file = caishixian("1");
        try (var workbook = new XSSFWorkbook(new java.io.ByteArrayInputStream(file));
                var output = new ByteArrayOutputStream()) {
            var cell = workbook.getSheetAt(0).getRow(1).getCell(6);
            cell.setCellValue(quantity);
            var style = workbook.createCellStyle();
            style.setDataFormat(workbook.createDataFormat().getFormat(format));
            cell.setCellStyle(style);
            assertThat(new org.apache.poi.ss.usermodel.DataFormatter().formatCellValue(cell)).isEqualTo("3");
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private byte[] jufubao(String quantity) throws Exception {
        return workbook(
                "sheet1",
                List.of(
                        "主单号", "拆单号", "供货商", "渠道订单号", "结算方式", "需结算总额",
                        "收货人姓名", "收货人电话", "收货地址", "商品ID", "商品名称", "规格", "单位", "数量", "下单时间"),
                List.of(
                        "JFB-ORDER-1", "JFB-LINE-1", "子牧", "JFB-CHANNEL-1", "月结", "0",
                        "张三", "13800000000", "河南省郑州市测试路1号", "JFB-SKU-1", "羊棒骨", "500g", "份", quantity,
                        "2026-08-31 10:00:00"));
    }

    private byte[] feixiang(String quantity) {
        List<String> headers = List.of(
                "订单号", "订单商品ID", "可发货数量", "物流状态", "物流公司", "物流单号",
                "会员名称", "商品ID", "商品名称", "规格", "单位", "收货人姓名", "收货人手机号", "收货人地址", "下单时间");
        List<String> values = List.of(
                "FX-ORDER-1", "FX-LINE-1", quantity, "", "", "",
                "会员1", "FX-SKU-1", "羊棒骨", "500g", "份", "张三", "13800000000", "河南省郑州市测试路1号",
                "2026-08-31 10:00:00");
        return (String.join(",", headers) + "\r\n" + String.join(",", values) + "\r\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    private byte[] zhonghui(String quantity) throws Exception {
        return workbook(
                "Sheet1",
                List.of(
                        "订单号", "商品编号", "商品名称", "件数", "收件人", "收件电话", "收件地址", "包装规格", "单位", "下单时间"),
                List.of(
                        "ZH-ORDER-1", "ZH-SKU-1", "羊棒骨", quantity, "张三", "13800000000", "河南省郑州市测试路1号", "500g", "份",
                        "2026-08-31 10:00:00"));
    }

    private byte[] dazhe(String quantity) throws Exception {
        return workbook(
                "Sheet1",
                List.of(
                        "编号", "主订单号", "商品名称", "数量", "收件人", "收件人电话", "收件人地址",
                        "物流公司", "物流单号"),
                List.of(
                        "1", "DZ-ORDER-1", "羊棒骨", quantity, "张三", "13800000000", "河南省郑州市测试路1号",
                        "", ""));
    }

    private byte[] wanqi(String quantity) throws Exception {
        List<String> headers = List.of(
                "收货人姓名", "收货人手机号", "详细地址", "商品名称", "规格信息", "商品类型", "品牌",
                "一级分类", "二级分类", "三级分类", "一级逻辑分类", "二级逻辑分类", "三级逻辑分类",
                "售价", "购买数量", "成本价", "结算价", "优惠类型", "优惠金额", "供应商", "商品来源",
                "子订单状态", "售后状态", "退款类型", "供应商发货时间", "确认收货时间", "申请退款时间",
                "售后完成时间", "用户备注", "商家/客服备注", "订单处理形式", "订单ID", "聚合ID", "子订单ID",
                "供应商单号", "商品id", "供应商商品id", "门店id", "供应商sku id", "服务时效", "期望时间",
                "物流信息", "crm 单号", "订单总金额", "skuid", "sku名称", "不含运毛利额", "不含运毛利率",
                "含运毛利额", "含运毛利率", "订单类型", "实物售后");
        Map<String, String> cells = new LinkedHashMap<>();
        headers.forEach(header -> cells.put(header, ""));
        cells.put("收货人姓名", "张三");
        cells.put("收货人手机号", "13800000000");
        cells.put("详细地址", "河南省郑州市测试路1号");
        cells.put("商品名称", "羊棒骨");
        cells.put("规格信息", "500g");
        cells.put("商品类型", "实体商品");
        cells.put("购买数量", quantity);
        cells.put("子订单状态", "待发货");
        cells.put("订单ID", "WQ-ORDER-1");
        cells.put("子订单ID", "WQ-LINE-1");
        cells.put("skuid", "WQ-SKU-1");
        cells.put("订单类型", "销售订单");
        return workbook("Sheet1", headers, headers.stream().map(cells::get).toList());
    }

    private byte[] workbook(String sheetName, List<String> headers, List<String> values) throws Exception {
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet(sheetName);
            var header = sheet.createRow(0);
            var row = sheet.createRow(1);
            for (int index = 0; index < headers.size(); index++) {
                header.createCell(index).setCellValue(headers.get(index));
                row.createCell(index).setCellValue(values.get(index));
            }
            workbook.write(output);
            return output.toByteArray();
        }
    }
}
