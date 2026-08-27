package cn.zimu.fulfillment.file;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zimu.fulfillment.common.domain.SourceChannel;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Optional;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

/**
 * 大者 v2：11 列「订单往返表」——渠道发订单给我们，我们发完货把后两列填回去还它。
 *
 * <p>表头取自 2026-08-26 用户经企微单聊实发的真实文件（生产 message_media#13）。
 * 数据是合成的：真实文件里是客户姓名/手机号/详细地址，不进仓库。
 */
class DazheV2TemplateTest {

    private static final List<String> DAZHE_V2_HEADERS = List.of(
            "编号", "主订单号", "商品名称", "数量", "收件人", "收件人电话",
            "收件人地址", "价格", "合计", "物流公司", "物流单号");

    /** 大者 v1：15 列，与 v2 是两份不同的导出。 */
    private static final List<String> DAZHE_V1_HEADERS = List.of(
            "渠道订单号", "主商品编码", "供应商商品名称", "商品名称", "订单商品状态",
            "采购单价(元)", "商品数量", "收货人", "收货人手机", "收货人详细地址",
            "预计到货时间", "渠道下单时间", "渠道支付时间", "快递单号", "快递公司");

    private static final List<String> ZHONGHUI_HEADERS = List.of(
            "订单号", "商品编号", "商品名称", "件数", "收件人",
            "收件电话", "收件地址", "包装规格", "单位");

    private static byte[] workbook(List<String> headers, List<List<String>> rows) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Sheet1");
            Row header = sheet.createRow(0);
            for (int i = 0; i < headers.size(); i++) {
                header.createCell(i).setCellValue(headers.get(i));
            }
            for (int r = 0; r < rows.size(); r++) {
                Row row = sheet.createRow(r + 1);
                List<String> values = rows.get(r);
                for (int c = 0; c < values.size(); c++) {
                    row.createCell(c).setCellValue(values.get(c));
                }
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    /** 物流公司/物流单号留空——它们是留给我们发完货填回去的，空是正常状态。 */
    private static List<String> dazheV2Row() {
        return List.of(
                "1", "ZXYPM209901010000000000000001", "子牧牛肉豪华大礼包6000g（BJ）", "1",
                "测试甲", "13000000001", "北京北京市朝阳区示例路1号101", "397.7", "", "", "");
    }

    @Test
    void 十一列订单往返表被认成大者() throws Exception {
        byte[] bytes = workbook(DAZHE_V2_HEADERS, List.of(dazheV2Row()));

        assertThat(new SourceFileParser().detectChannel(bytes)).contains(SourceChannel.DAZHE);
    }

    @Test
    void 十二列全角括号变体也认成大者_生产实证模板() throws Exception {
        // 2026-08-27 用户实发：12 列、采购单价（元）为全角括号、无三个日期列
        byte[] bytes = workbook(List.of(
                "渠道订单号", "主商品编码", "供应商商品名称", "商品名称", "订单商品状态",
                "采购单价（元）", "商品数量", "收货人", "收货人手机", "收货人详细地址",
                "快递单号", "快递公司"), List.of(List.of(
                "spr01-LPC26342186000001", "P26020400005", "北京大者国风科技有限公司",
                "子牧精品羊肉礼包4900g（BJ）", "待发货", "291", "1",
                "测试丙", "13000000003", "河北省石家庄市新华区示例街 3 号", "", "")));

        ParsedSourceFile parsed = new SourceFileParser().parse(bytes);

        assertThat(parsed.sourceChannel()).isEqualTo(SourceChannel.DAZHE);
        ParsedSourceRow row = parsed.rows().getFirst();
        assertThat(row.valid()).isTrue();
        assertThat(row.sourceOrderRef()).isEqualTo("spr01-LPC26342186000001");
        assertThat(row.sourceSkuRef()).isEqualTo("P26020400005");
    }

    @Test
    void 十五列的大者v1不受影响() throws Exception {
        byte[] bytes = workbook(DAZHE_V1_HEADERS, List.of());

        assertThat(new SourceFileParser().detectChannel(bytes)).contains(SourceChannel.DAZHE);
    }

    @Test
    void 中汇不会被误判成大者v2_两者表头有重叠词() throws Exception {
        // 重叠的是「商品名称」「收件人」；中汇的必填集里有 商品编号/件数/收件电话/包装规格/单位，
        // 大者 v2 一个都没有，所以不会互相命中
        byte[] bytes = workbook(ZHONGHUI_HEADERS, List.of());

        assertThat(new SourceFileParser().detectChannel(bytes)).contains(SourceChannel.ZHONGHUI);
    }

    @Test
    void 认不出的表头返回empty而不是抛异常() throws Exception {
        byte[] bytes = workbook(List.of("甲", "乙", "丙"), List.of());

        assertThat(new SourceFileParser().detectChannel(bytes)).isEqualTo(Optional.empty());
    }

    @Test
    void 空字节与null都当作认不出() {
        assertThat(new SourceFileParser().detectChannel(null)).isEmpty();
        assertThat(new SourceFileParser().detectChannel(new byte[0])).isEmpty();
    }

    @Test
    void 表尾合计行被跳过_不产生NEED_REVIEW行() throws Exception {
        // 生产实证（批次 28 第 8 行）：只有「合计」带 SUM 公式、身份列全空的表尾行
        // 被解析成一行落 NEED_REVIEW，一行合计把六张真订单的批次确认全部拖住
        List<String> summaryRow = List.of("", "", "", "", "", "", "", "", "SUM(I2:I7)", "", "");
        byte[] bytes = workbook(DAZHE_V2_HEADERS, List.of(dazheV2Row(), summaryRow));

        ParsedSourceFile parsed = new SourceFileParser().parse(bytes);

        assertThat(parsed.rows()).hasSize(1);
        assertThat(parsed.rows().getFirst().sourceOrderRef())
                .isEqualTo("ZXYPM209901010000000000000001");
    }

    @Test
    void 行解析用主订单号当订单标识_商品名称当商品标识() throws Exception {
        byte[] bytes = workbook(DAZHE_V2_HEADERS, List.of(dazheV2Row()));

        ParsedSourceFile parsed = new SourceFileParser().parse(bytes);

        assertThat(parsed.sourceChannel()).isEqualTo(SourceChannel.DAZHE);
        assertThat(parsed.templateVersion()).isEqualTo("v2-11-columns");
        assertThat(parsed.rows()).hasSize(1);
        ParsedSourceRow row = parsed.rows().getFirst();
        assertThat(row.valid()).as("物流两列为空不该让整行失败").isTrue();
        assertThat(row.sourceOrderRef()).isEqualTo("ZXYPM209901010000000000000001");
        // 这份导出没有商品编码列，名称是唯一稳定标识
        assertThat(row.sourceSkuRef()).isEqualTo("子牧牛肉豪华大礼包6000g（BJ）");
        assertThat(row.productName()).isEqualTo("子牧牛肉豪华大礼包6000g（BJ）");
        assertThat(row.receiverName()).isEqualTo("测试甲");
        assertThat(row.receiverPhone()).isEqualTo("13000000001");
        assertThat(row.quantity()).isEqualTo("1");
    }
}
