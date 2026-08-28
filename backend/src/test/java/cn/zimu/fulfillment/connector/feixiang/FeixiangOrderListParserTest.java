package cn.zimu.fulfillment.connector.feixiang;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 列表页 HTML → order_son_id 抽取。
 *
 * <p>被测对象是全链路里唯一没有抓包实据的环节（HAR 只给了路径与查询参数，没给页面 HTML），
 * 所以这里覆盖 ThinkPHP 后台常见的四种承载写法，并把「不许误抓别的 ID」钉死——标识符
 * 混用正是 HAR 分析明确警告过的坑。</p>
 */
class FeixiangOrderListParserTest {

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
