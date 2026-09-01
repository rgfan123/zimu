package cn.zimu.fulfillment.connector.feixiang;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 飞象待发货列表页 HTML → {@code order_son_id} 清单。
 *
 * <p><b>2026-09-01 起本类有了抓包实据。</b>此前（2026-08-28 交付时）HAR 线索只给了
 * {@code GET /esOrder/index/{page}} 的路径与查询参数，「order_son_id 长什么样地出现在 HTML 里」
 * 全靠推断——生产随即连续两天证明推断落空：平台自报窗口内 7/8 单，四种
 * {@code order_son_id=} 形状一个都没命中，解析出 0 单（fail-loud 生效，未静默丢单）。
 * 2026-09-01 在生产容器内只读重放登录 + 列表请求拿到真实页面，事实是：</p>
 *
 * <ul>
 *   <li>列表页是<b>服务端渲染</b>的表格（此前指纹一度怀疑是 Vue 空壳，已证伪——只有分页器
 *       {@code el-pagination} 是 Vue 组件，订单行就在 HTML 里）；</li>
 *   <li>页面上<b>不存在</b>任何 {@code order_son_id=} 字样；ID 由平台自己的 jQuery 处理器从
 *       按钮属性里取：{@code <button class="… sendProduct" idata="24150997">发货</button>} 配
 *       {@code var order_son_id = $(this).attr('idata')}，以及继续下单按钮
 *       {@code class="xiadan" iddata="…"} 配 {@code $(this).attr('iddata')}。
 *       即 {@code idata}/{@code iddata} 属性值<b>就是</b> order_son_id（平台 JS 为证）。</li>
 * </ul>
 *
 * <p>设计取舍（原则不变，只是证据升级了）：
 * <ol>
 *   <li><b>双版本并存</b>——新结构 {@code idata}/{@code iddata} 模式与旧的四种
 *       {@code order_son_id=} 形状同时启用取并集：新页面上旧模式必然空转（页面里没有该字样），
 *       旧结构若回滚也仍被兜住；两边都捞不到才返回空。</li>
 *   <li><b>绝不静默返回空</b>——解析不到任何 ID 时返回空列表，由调用方结合平台自己的订单
 *       计数判断这是「本区间真的没单」还是「选择器失效」，并显式报错（连同
 *       {@code FeixiangListPageFingerprint} 结构指纹）。<b>这正是本票要修的故障模式</b>：
 *       拉不到单却报「成功，0 条新数据」，订单就此永久丢失。</li>
 *   <li><b>刻意不抓</b> {@code /order/orderDetail/{id}} 链接里的数字：详情链接挂在订单头行，
 *       一单多商品时它与各商品行的子单 ID 是否同值<b>未经验证</b>，抓它就是在赌标识符不混用；
 *       {@code idata}/{@code iddata} 已覆盖每一条可发货的商品行，不需要这份风险。</li>
 * </ol>
 */
public final class FeixiangOrderListParser {

    /**
     * 只认「字段名 + 分隔符 + 数字」这一种形状，且字段名必须<b>完整</b>匹配 order_son_id。
     *
     * <p>字段名前只允许出现真正的边界：行首、空白（HTML 属性）、引号（JSON 键）、
     * {@code ?}/{@code &}（查询串）、{@code {}/{@code ,}（内联 JS 对象），外加可选的
     * {@code data-} 前缀（HTML data 属性）。这样 {@code data-order_son_id} 能命中，而
     * {@code parent_order_son_id} 这类<b>别的字段</b>不会被误当成目标——标识符混用正是
     * HAR 分析明确警告过的坑。</p>
     *
     * <p>分隔符允许 {@code =} 或 {@code :}，值允许被单/双引号包裹。刻意<b>不</b>匹配
     * {@code order_son_sn}（S 开头的子订单号）——两者不可混用。</p>
     */
    private static final Pattern ORDER_SON_ID = Pattern.compile(
            "(?:^|[\\s\"'{,?&])(?:data-)?order[_-]son[_-]id[\"']?\\s*[=:]\\s*[\"']?(\\d{1,20})",
            Pattern.CASE_INSENSITIVE);

    /**
     * 表单控件写法：{@code <input name="order_son_id" value="123">}——字段名与值分处两个属性。
     *
     * <p>ThinkPHP 后台列表页给每行套一个提交表单是很常见的做法，而这种写法里 {@code order_son_id}
     * 后面跟的是 {@code " value=}，主正则的「字段名紧跟 = 或 :」形状匹配不到。这里只允许两个属性
     * 之间隔着有限的空白与引号（{@code [^>]{0,40}?} 且不跨出标签），避免把整段 HTML 里毫不相干的
     * 下一个数字捞进来。</p>
     */
    private static final Pattern ORDER_SON_ID_INPUT = Pattern.compile(
            "name\\s*=\\s*[\"']order[_-]son[_-]id[\"'][^>]{0,40}?value\\s*=\\s*[\"']?(\\d{1,20})",
            Pattern.CASE_INSENSITIVE);

    /**
     * 2026-09-01 生产实据的新版结构：{@code idata="123"}（发货按钮）与 {@code iddata="123"}
     * （继续下单按钮），属性值即 order_son_id——平台自己的页内 JS
     * {@code $(this).attr('idata')} / {@code $(this).attr('iddata')} 就是这么取的。
     *
     * <p>形状收紧到「属性写法」：属性名前必须是词边界（{@code validata}、{@code candidata}
     * 这类以 idata 结尾的单词不命中），后面必须紧跟 {@code =} 与可选引号的纯数字值。
     * 不匹配 {@code data-idata} 之外的变体拼法——真实页面就这两种写法，宽了才是风险。</p>
     */
    private static final Pattern SEND_BUTTON_IDATA = Pattern.compile(
            "\\bid{1,2}ata\\s*=\\s*[\"']?(\\d{1,20})",
            Pattern.CASE_INSENSITIVE);

    private FeixiangOrderListParser() {}

    /**
     * 抽取本页出现的 order_son_id，按文档顺序去重。
     *
     * @param html 列表页 HTML 原文（null/空返回空列表）
     * @return 去重后的 order_son_id 列表；解析不到返回空列表（调用方必须显式处理，不得当作「无单」）
     */
    public static List<String> extractOrderSonIds(String html) {
        if (html == null || html.isBlank()) {
            return List.of();
        }
        // LinkedHashSet：同一行可能同时以属性、内联 JS 和隐藏表单域出现同一个 ID，
        // 去重但保留页面顺序。
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        // 新结构在前（当前生产页面），旧结构在后（回滚兜底）；LinkedHashSet 并集去重。
        collectInto(ids, SEND_BUTTON_IDATA, html);
        collectInto(ids, ORDER_SON_ID, html);
        collectInto(ids, ORDER_SON_ID_INPUT, html);
        return List.copyOf(new ArrayList<>(ids));
    }

    private static void collectInto(LinkedHashSet<String> ids, Pattern pattern, String html) {
        Matcher matcher = pattern.matcher(html);
        while (matcher.find()) {
            String id = stripLeadingZeros(matcher.group(1));
            if (!id.isEmpty()) {
                ids.add(id);
            }
        }
    }

    /**
     * 去掉前导零后仍是同一个平台 ID（{@code 0123} 与 {@code 123} 指同一单）；
     * 不去重会让同一订单被拉两遍。全零串归一到 {@code "0"} 之外一律视为无效并丢弃。
     */
    private static String stripLeadingZeros(String raw) {
        int index = 0;
        while (index < raw.length() - 1 && raw.charAt(index) == '0') {
            index++;
        }
        String trimmed = raw.substring(index);
        return "0".equals(trimmed) ? "" : trimmed;
    }
}
