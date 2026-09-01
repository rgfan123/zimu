package cn.zimu.fulfillment.connector.feixiang;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 列表页解析失效时的结构指纹：**够写正则，不够认出人**。
 *
 * <p><b>为什么需要它</b>：{@link FeixiangOrderListParser} 的类注释自己声明了
 * 「本类是全链路里唯一没有抓包实据的环节，order_son_id 长什么样地出现在 HTML 里是推断」，
 * 并明确要求「必须补抓一次页面 HTML 并补一条针对性的模式，而不是放宽成抓页面上所有数字」。
 *
 * <p>2026-08-29 生产实证：飞象平台自报窗口内 9 单、解析出 0 单，
 * {@code connector_configs.last_error} 只留下一句「HTML 结构与解析规则不匹配」——
 * <b>没有任何能据以写出正确正则的线索</b>，等于每次失败都要重新抓一次包。
 *
 * <h2>怎么做到「不带出 PII」</h2>
 *
 * <p>列表页上的收货人姓名、地址、商品名<b>都是中日韩字符</b>；而 HTML 标签名、属性名、
 * JS 函数名、以及 {@code order_son_id} 的数字值<b>都是 ASCII</b>。所以只要
 * <b>整段剥掉 CJK 码位</b>，剩下的就是纯结构 —— 既能看出 ID 藏在哪个属性/哪个 onclick 里，
 * 又不可能把某位客户的姓名地址带进错误信息和日志。
 *
 * <p>刻意<b>不</b>做的事：不截取原始 HTML 片段（片段里随时可能夹着中文之外的 PII，
 * 比如手机号）；不保留 11 位及以上的连续数字（手机号长度），只留 1–10 位的数字，
 * 足够看出 order id 的形状而不会把电话原样搬出来。
 */
public final class FeixiangListPageFingerprint {

    /** 错误信息要进 last_error 和日志，必须有硬上限。 */
    private static final int MAX_LENGTH = 1200;

    /** 「order」附近的上下文窗口；ID 就藏在这一带。 */
    private static final int CONTEXT_RADIUS = 60;

    private static final Pattern ORDER_CONTEXT = Pattern.compile("order", Pattern.CASE_INSENSITIVE);

    /** JS 事件处理器名：ID 若只藏在 onclick="sendBefore(123)" 里，这里能看见。 */
    private static final Pattern EVENT_HANDLER = Pattern.compile(
            "\\bon[a-z]{3,15}\\s*=\\s*[\"']([^\"']{0,80})", Pattern.CASE_INSENSITIVE);

    /** 属性名清单：看得出平台用的是 data-xxx 还是别的承载方式。 */
    private static final Pattern ATTRIBUTE_NAME = Pattern.compile("\\b([a-z][a-z0-9_-]{2,30})\\s*=\\s*[\"']");

    /**
     * 平台自己的 AJAX 接口路径。
     *
     * <p>2026-08-30 曾据指纹推断「列表页是 Vue 壳子、订单行走 JSON 接口」——
     * <b>2026-09-01 生产容器内只读重放已证伪</b>：订单行就是服务端渲染在 HTML 里的
     * {@code <table>}，只有分页器 {@code el-pagination} 是 Vue 组件（所以属性名里有
     * {@code current-change / page-size / layout / total}）。真正的失配原因是 ID 承载方式：
     * 页面上没有任何 {@code order_son_id=} 字样，ID 在发货按钮 {@code idata="…"} /
     * 继续下单按钮 {@code iddata="…"} 属性里（平台页内 JS {@code $(this).attr('idata')} 为证），
     * 已在 {@link FeixiangOrderListParser} 补上针对性模式。
     *
     * <p>仍保留 AJAX 路径采集：平台接口命名有固定法（已知 {@code /order/ajaxOrderNum}、
     * {@code /order/ajaxGetSendBeforePro}），若日后真的改成 JSON 渲染，把页面里所有
     * {@code /xxx/ajaxYyy} 捞出来就能定位数据接口，不必再让人手工抓一次包。
     */
    private static final Pattern AJAX_PATH = Pattern.compile(
            "(/[A-Za-z][A-Za-z0-9_]{0,20}/ajax[A-Za-z0-9_]{1,40})");

    /** CJK 与全角标点：姓名、地址、商品名都在这些区里，整段剥掉。 */
    private static final Pattern CJK = Pattern.compile("[\\u2E80-\\u9FFF\\u3000-\\u303F\\uFF00-\\uFFEF]+");

    /** 手机号长度的连续数字：结构上用不到，留着只会把 PII 搬出来。 */
    private static final Pattern LONG_DIGITS = Pattern.compile("\\d{11,}");

    private FeixiangListPageFingerprint() {}

    /**
     * 把一页 HTML 压成可以贴进错误信息的结构指纹。
     *
     * @param html 原始列表页；null / 空返回明确的说明而不是空串——「拿到了空页面」
     *             和「没采集到指纹」是两回事，不能长得一样
     */
    public static String of(String html) {
        if (html == null || html.isBlank()) {
            return "[列表页响应为空]";
        }
        String safe = LONG_DIGITS.matcher(CJK.matcher(html).replaceAll("·")).replaceAll("#");

        StringBuilder out = new StringBuilder();
        out.append("[len=").append(html.length()).append(']');

        // AJAX 路径放最前：列表页是 Vue 壳子时，这是唯一能定位真实数据接口的线索。
        Set<String> ajaxPaths = collect(AJAX_PATH, safe, 20);
        if (!ajaxPaths.isEmpty()) {
            out.append(" AJAX接口=").append(ajaxPaths);
        }
        Set<String> handlers = collect(EVENT_HANDLER, safe, 12);
        if (!handlers.isEmpty()) {
            out.append(" 事件处理器=").append(handlers);
        }
        Set<String> attributes = collect(ATTRIBUTE_NAME, safe, 30);
        if (!attributes.isEmpty()) {
            out.append(" 属性名=").append(attributes);
        }
        Set<String> contexts = orderContexts(safe);
        if (!contexts.isEmpty()) {
            out.append(" order 上下文=").append(contexts);
        }
        String text = out.toString();
        return text.length() <= MAX_LENGTH ? text : text.substring(0, MAX_LENGTH) + "…[截断]";
    }

    private static Set<String> collect(Pattern pattern, String safe, int limit) {
        Set<String> found = new LinkedHashSet<>();
        Matcher matcher = pattern.matcher(safe);
        while (matcher.find() && found.size() < limit) {
            String value = matcher.group(1).trim();
            if (!value.isEmpty()) {
                found.add(value);
            }
        }
        return found;
    }

    /** 「order」前后各取一段——ID 到底跟在什么形状后面，只能从这里看出来。 */
    private static Set<String> orderContexts(String safe) {
        Set<String> found = new LinkedHashSet<>();
        Matcher matcher = ORDER_CONTEXT.matcher(safe);
        while (matcher.find() && found.size() < 6) {
            int from = Math.max(0, matcher.start() - CONTEXT_RADIUS);
            int to = Math.min(safe.length(), matcher.end() + CONTEXT_RADIUS);
            found.add(safe.substring(from, to).replaceAll("\\s+", " ").trim());
        }
        return found;
    }
}
