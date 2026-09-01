package cn.zimu.fulfillment.connector.feixiang;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 列表页 HTML → order_son_id 抽取。
 *
 * <p>2026-09-01 前被测对象没有抓包实据（HAR 只给了路径与查询参数，没给页面 HTML），四种
 * {@code order_son_id=} 承载写法全是推断——生产连续两天「自报 7/8 单、解析 0 单」证明推断
 * 落空。2026-09-01 只读重放拿到真实页面：ID 实际藏在发货按钮 {@code idata="…"} 与继续下单
 * 按钮 {@code iddata="…"} 属性里（平台页内 JS {@code $(this).attr('idata')} 为证）。
 * 本类现在同时钉死三件事：新版结构解析得出、旧版写法回滚可兜、别的 ID 一个不许误抓。</p>
 */
class FeixiangOrderListParserTest {

    /** 新版结构 fixture：按 2026-09-01 生产真实页面结构复刻（已脱敏），见文件头注释。 */
    private static final String NEW_STRUCTURE_FIXTURE = "/feixiang/esorder-list-v20260901.html";

    // ------------------------------------------------------------ 新版结构（2026-09-01 实据）

    @Test
    void extractsIdFromSendButtonIdataAttribute() {
        String html = "<button class=\"btn btn-primary btn-82 send_btns sendProduct\" idata=\"24150997\">发货</button>";

        assertThat(FeixiangOrderListParser.extractOrderSonIds(html)).containsExactly("24150997");
    }

    @Test
    void extractsIdFromContinueOrderIddataAttribute() {
        String html = "<button class=\"xiadan\" iddata='24151003'>继续下单</button>";

        assertThat(FeixiangOrderListParser.extractOrderSonIds(html)).containsExactly("24151003");
    }

    /** 以 idata 结尾的普通单词（validata/candidata 之类）不许当成属性误抓。 */
    @Test
    void ignoresWordsThatMerelyEndWithIdata() {
        String html = "<div class=\"validata\">candidata=123</div><span>lidata=456</span>";

        assertThat(FeixiangOrderListParser.extractOrderSonIds(html)).isEmpty();
    }

    /** idata 值不是纯数字（或为空）时不产出 ID——宁可空转触发 fail-loud，不吞垃圾值。 */
    @Test
    void ignoresIdataWithNonNumericValue() {
        String html = "<button idata=\"\">发货</button><button idata=\"S2026900001\">发货</button>";

        assertThat(FeixiangOrderListParser.extractOrderSonIds(html)).isEmpty();
    }

    /**
     * 整页 fixture：解析出且只解析出四个 order_son_id（三个发货按钮 + 一个继续下单按钮），
     * 订单头行 {@code /order/orderDetail/99999001} 这类<b>订单级</b> ID 绝不允许混进来——
     * 一单多商品时它与子单 ID 是否同值未经验证，抓它就是标识符混用。
     */
    @Test
    void extractsExactlyTheSubOrderIdsFromRealPageStructureFixture() {
        List<String> ids = FeixiangOrderListParser.extractOrderSonIds(fixture(NEW_STRUCTURE_FIXTURE));

        assertThat(ids).containsExactly("24150997", "24151001", "24151002", "24151003");
        assertThat(ids).doesNotContain("99999001", "99999002");
    }

    private static String fixture(String resource) {
        try (InputStream in = FeixiangOrderListParserTest.class.getResourceAsStream(resource)) {
            assertThat(in).as("fixture 资源必须存在: " + resource).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    // ------------------------------------------------------------ 旧版写法（回滚兜底，保持通过）

    @Test
    void extractsIdFromHtmlAttribute() {
        String html = "<tr data-order_son_id=\"1001\"><td>D2026827930036623895</td></tr>";

        assertThat(FeixiangOrderListParser.extractOrderSonIds(html)).containsExactly("1001");
    }

    @Test
    void extractsIdFromKebabCaseDataAttribute() {
        String html = "<a class=\"send\" data-order-son-id='2002'>发货</a>";

        assertThat(FeixiangOrderListParser.extractOrderSonIds(html)).containsExactly("2002");
    }

    @Test
    void extractsIdFromLinkQueryString() {
        String html = "<a href=\"/order/detail?order_son_id=3003&from=list\">详情</a>";

        assertThat(FeixiangOrderListParser.extractOrderSonIds(html)).containsExactly("3003");
    }

    @Test
    void extractsIdFromInlineJson() {
        String html = "<script>var rows=[{\"order_son_id\":4004,\"order_sn\":\"D2026\"}];</script>";

        assertThat(FeixiangOrderListParser.extractOrderSonIds(html)).containsExactly("4004");
    }

    /** 表单控件写法：字段名与值分处两个属性（ThinkPHP 后台给每行套提交表单的常见形状）。 */
    @Test
    void extractsIdFromHiddenInputWhereNameAndValueAreSeparateAttributes() {
        String html = "<form><input type=\"hidden\" name=\"order_son_id\" value=\"5005\"></form>";

        assertThat(FeixiangOrderListParser.extractOrderSonIds(html)).containsExactly("5005");
    }

    /** 表单写法不得跨出标签去捞下一个不相干的数字。 */
    @Test
    void doesNotReachAcrossTagBoundaryToGrabAnUnrelatedNumber() {
        String html = "<input name=\"order_son_id\"><td>999</td><input name=\"other\" value=\"888\">";

        assertThat(FeixiangOrderListParser.extractOrderSonIds(html)).isEmpty();
    }

    @Test
    void dedupesRepeatedIdsButKeepsDocumentOrder() {
        // 同一行常常同时以属性和内联 JS 出现同一个 ID
        String html = """
                <tr data-order_son_id="30"><td><a href="?order_son_id=30">详情</a></td></tr>
                <tr data-order_son_id="10"><td><a href="?order_son_id=10">详情</a></td></tr>
                <tr data-order_son_id="20"></tr>
                """;

        assertThat(FeixiangOrderListParser.extractOrderSonIds(html))
                .containsExactly("30", "10", "20");
    }

    @Test
    void treatsLeadingZeroVariantAsSameOrder() {
        String html = "<tr data-order_son_id=\"0077\"></tr><tr data-order_son_id=\"77\"></tr>";

        assertThat(FeixiangOrderListParser.extractOrderSonIds(html)).containsExactly("77");
    }

    /** 标识符隔离：order_son_sn（S…）、order_sn（D…）、order_id、product_id 都不是 order_son_id。 */
    @Test
    void neverConfusesOtherIdentifiersWithOrderSonId() {
        String html = """
                <tr data-order_sn="D2026826346818550490"
                    data-order_son_sn="S2026826346818550490"
                    data-order_id="99"
                    data-product_id="88"
                    data-order_product_id="77">
                </tr>
                """;

        assertThat(FeixiangOrderListParser.extractOrderSonIds(html)).isEmpty();
    }

    /** 前缀不同的字段（如 parent_order_son_id）不得被当成目标字段。 */
    @Test
    void ignoresFieldsThatMerelyEndWithOrderSonId() {
        String html = "<tr data-parent_order_son_id=\"555\"></tr>";

        assertThat(FeixiangOrderListParser.extractOrderSonIds(html)).isEmpty();
    }

    @Test
    void returnsEmptyForBlankOrNullHtml() {
        assertThat(FeixiangOrderListParser.extractOrderSonIds(null)).isEmpty();
        assertThat(FeixiangOrderListParser.extractOrderSonIds("")).isEmpty();
        assertThat(FeixiangOrderListParser.extractOrderSonIds("<html><body>无订单</body></html>")).isEmpty();
    }

    @Test
    void extractsEveryRowOnAFullPage() {
        StringBuilder html = new StringBuilder();
        for (int index = 1; index <= 20; index++) {
            html.append("<tr data-order_son_id=\"").append(index).append("\"></tr>");
        }

        List<String> ids = FeixiangOrderListParser.extractOrderSonIds(html.toString());

        assertThat(ids).hasSize(20).startsWith("1").endsWith("20");
    }
}
