package cn.zimu.fulfillment.connector.feixiang;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 飞象待发货列表页 HTML → {@code order_son_id} 清单。
 *
 * <p><b>诚实声明：本类是全链路里唯一没有抓包实据的环节。</b>2026-08-28 的 HAR 线索给出了
 * {@code GET /esOrder/index/{page}} 的<b>路径与查询参数</b>，以及详情接口的<b>响应字段</b>，
 * 但<b>没有</b>给出列表页 HTML 的标记结构。因此「order_son_id 长什么样地出现在 HTML 里」
 * 是推断，不是事实，必须拿真实页面验证后再收紧。</p>
 *
 * <p>面对这个不确定性的设计取舍：
 * <ol>
 *   <li><b>宽进</b>——用一条容忍多种写法的正则去捞 {@code order_son_id} 后面的数字，覆盖
 *       ThinkPHP 后台常见的四种承载方式：HTML 属性 {@code order_son_id="123"}、
 *       kebab 变体 {@code data-order-son-id="123"}、链接查询串 {@code ?order_son_id=123}、
 *       内联 JS/JSON {@code "order_son_id":123}；</li>
 *   <li><b>绝不静默返回空</b>——解析不到任何 ID 时返回空列表，由调用方结合平台自己的订单
 *       计数判断这是「本区间真的没单」还是「选择器失效」，并显式报错。<b>这正是本票要修的
 *       故障模式</b>：拉不到单却报「成功，0 条新数据」，订单就此永久丢失。</li>
 * </ol>
 *
 * <p>若真实页面把 ID 只藏在 {@code onclick="sendBefore(123)"} 这类<b>不含字段名</b>的调用里，
 * 本正则会捞不到——那时必须补抓一次页面 HTML 并在这里补一条针对性的模式，而不是放宽成
 * 「抓页面上所有数字」（那会把商品 ID、金额、订单号一起当成 order_son_id，正是标识符混用
 * 事故的温床）。</p>
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
