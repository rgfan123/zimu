package cn.zimu.fulfillment.connector.feixiang;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FeixiangListPageFingerprintTest {

    @Test
    void 收货人与地址不得出现在指纹里() {
        // 列表页真实形态：ID 藏在 onclick 里（正是解析器注释警告过的那种），
        // 同一行还挂着收货人、地址、商品名。
        String html = """
                <tr>
                  <td>张三丰</td>
                  <td>新疆乌鲁木齐市新市区石油新村街道洞庭路商运司旧九号楼</td>
                  <td>子牧原切羊腿肉500g*2袋</td>
                  <td><a href="#" onclick="sendBefore(88123)">发货</a></td>
                </tr>
                """;

        String fingerprint = FeixiangListPageFingerprint.of(html);

        assertThat(fingerprint).doesNotContain("张三丰");
        assertThat(fingerprint).doesNotContain("乌鲁木齐");
        assertThat(fingerprint).doesNotContain("羊腿肉");
        // 但结构必须留下——不然这份指纹没用
        assertThat(fingerprint).contains("onclick");
        assertThat(fingerprint).contains("sendBefore(88123)");
    }

    @Test
    void 手机号长度的连续数字不带出去() {
        // 电话是 ASCII，剥 CJK 剥不掉它，必须单独挡。
        String fingerprint = FeixiangListPageFingerprint.of(
                "<td data-phone=\"13669967668\">·</td><td order_son_id=\"77001\">·</td>");

        assertThat(fingerprint).doesNotContain("13669967668");
        // 订单 ID 是 5 位，不受影响
        assertThat(fingerprint).contains("77001");
    }

    @Test
    void 属性承载与查询串承载都能看出来() {
        String fingerprint = FeixiangListPageFingerprint.of(
                "<a href=\"/esOrder/detail?order_son_id=4210\" data-order-son-id=\"4210\">·</a>");

        assertThat(fingerprint).contains("order 上下文=");
        assertThat(fingerprint).contains("4210");
    }

    @Test
    void 空响应与采不到指纹要长得不一样() {
        // 「拿到了一个空页面」和「页面有内容但没提取到东西」是两种故障，混在一起没法排查。
        assertThat(FeixiangListPageFingerprint.of(null)).isEqualTo("[列表页响应为空]");
        assertThat(FeixiangListPageFingerprint.of("   ")).isEqualTo("[列表页响应为空]");
        assertThat(FeixiangListPageFingerprint.of("<html><body>·</body></html>")).contains("len=");
    }

    @Test
    void 指纹有硬上限_不会把整页塞进错误信息() {
        String huge = "<div class=\"order-row\" data-order-son-id=\"1\">x</div>".repeat(5000);

        assertThat(FeixiangListPageFingerprint.of(huge).length()).isLessThanOrEqualTo(1300);
    }

    @Test
    void 抓得到平台的AJAX接口路径() {
        // 2026-08-30 实测：这个列表页是 Vue 壳子，订单行根本不在 HTML 里，
        // 唯一能定位真实数据接口的线索就是页面 JS 里的 ajax 路径。
        String html = """
                <script>
                  $.post('/order/ajaxOrderNum', p, function(r){});
                  axios.post('/esOrder/ajaxOrderList', {page: 1});
                </script>
                """;

        String fingerprint = FeixiangListPageFingerprint.of(html);

        assertThat(fingerprint).contains("AJAX接口=");
        assertThat(fingerprint).contains("/esOrder/ajaxOrderList");
        assertThat(fingerprint).contains("/order/ajaxOrderNum");
    }
}
