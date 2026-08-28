package cn.zimu.fulfillment.connector.feixiang;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;

/**
 * 飞象单笔订单详情（{@code POST /order/ajaxGetSendBeforePro} 的 {@code data} 段）。
 *
 * <p><b>标识符隔离是本记录存在的首要理由。</b>飞象同时下发五种互不相同、互不可代入的 ID，
 * HAR 分析里已经出现过一次混用事故（{@code get_myt_order_express} 把 order_son_id 当
 * order_id 提交，平台回「供应商不正确」）。因此这里<b>不</b>用 {@code Map<String,Object>}
 * 透传，而是把每一种 ID 落到独立命名的字段上，让「拿错 ID」变成编译期就看得见的事：
 *
 * <ul>
 *   <li>{@link ReceiveInfo#orderSn()} —— 页面显示的 {@code D...} 订单号，<b>唯一</b>用作
 *       来源单号（source_ref），与既有 Excel 链路的「订单号」列同口径，保证与生产库里
 *       已导入的飞象订单能正确判重；</li>
 *   <li>{@link ReceiveInfo#orderSonSn()} —— {@code S...} 子订单号，仅作证据留痕；</li>
 *   <li>{@link ReceiveInfo#orderSonId()} —— 详情/校验接口专用的<b>数字</b> ID，只在
 *       HTTP 请求参数里出现，绝不写进业务单号字段；</li>
 *   <li>{@link ReceiveInfo#orderId()} —— 平台内部父订单 ID，只留痕；</li>
 *   <li>{@link ProductLine#orderProductId()} —— 商品行 ID，作为订单行的 source_line_ref，
 *       与既有 Excel 链路的「订单商品ID」列同口径。</li>
 * </ul>
 *
 * <p>所有字段一律按<b>字符串原文</b>保存：平台对数字 ID 时而下发 JSON number、时而下发
 * 字符串，转成 long 再转回来会引入前导零丢失等静默改写。需要数字的地方（仅详情请求参数）
 * 由调用方自行校验。</p>
 */
public record FeixiangOrderDetail(ReceiveInfo receiveInfo, List<ProductLine> products) {

    public FeixiangOrderDetail {
        products = products == null ? List.of() : List.copyOf(products);
    }

    /** {@code data.receive_info}：订单级事实（收货信息、下单时间、各类单号）。 */
    public record ReceiveInfo(
            String orderId,
            String orderSonId,
            String orderSn,
            String orderSonSn,
            String state,
            String num,
            String sendNum,
            String createTime,
            String payTime,
            String sendTime,
            String name,
            String phone,
            String areaName,
            String address) {

        /** 收货三要素齐备才允许生成可履约订单；缺一即进人工复核（不造空收货人）。 */
        public boolean completeReceiver() {
            return notBlank(name) && notBlank(phone) && notBlank(joinedAddress());
        }

        /** 完整收货地址 = 行政区 + 详细地址（平台把两段分开下发）。 */
        public String joinedAddress() {
            String area = areaName == null ? "" : areaName.trim();
            String detail = address == null ? "" : address.trim();
            if (area.isEmpty()) {
                return detail;
            }
            if (detail.isEmpty()) {
                return area;
            }
            return detail.startsWith(area) ? detail : area + detail;
        }
    }

    /** {@code data.order_product[]}：商品行事实（含物流回填字段，用于「已发货不重复建单」判据）。 */
    public record ProductLine(
            String orderId,
            String orderSonId,
            String orderProductId,
            String productId,
            String title,
            String productSpecName,
            String pronum,
            String memberPrice,
            String expressCode,
            String sn,
            String expressState,
            String proStateName,
            String proStatusName,
            String deliveryRemark,
            String supplierName) {

        /**
         * 该商品行是否已有物流事实。
         *
         * <p>与 {@code SourceFileParser#feixiang} 的既有拦截同源（2026-08-27 补的安全属性）：
         * Excel 链路按「物流状态=已发货 / 物流公司非空 / 物流单号非空」拦下已发货行，防止给
         * 已发出的货重复建单、重复推发货卡。JSON 链路必须保留同一属性，判据取平台自己给的
         * 事实：{@code sn}（物流单号）或 {@code express_code}（物流公司）已有值，
         * 或状态名 {@code pro_state_name}/{@code pro_status_name} 显式写着「已发货」。</p>
         *
         * <p><b>刻意不看 {@code express_state}</b>：它是数字码，语义没有抓包确认。猜一个
         * 「非 0 即已发货」的映射，猜错的两个方向都很糟——猜松了会把待发货订单当成已发货丢进
         * 复核（丢单），猜紧了等于没判。真要用它，先抓一次带物流的订单确认码表；在那之前，
         * 单号与物流公司这两个<b>无歧义</b>的事实已经足够。</p>
         */
        public boolean alreadyShipped() {
            return notBlank(sn)
                    || notBlank(expressCode)
                    || "已发货".equals(trimmed(proStateName))
                    || "已发货".equals(trimmed(proStatusName));
        }
    }

    /** 从 {@code ajaxGetSendBeforePro} 的 {@code data} 节点构造；缺字段落空串而非 null。 */
    public static FeixiangOrderDetail from(JsonNode data) {
        if (data == null || data.isMissingNode() || data.isNull()) {
            return new FeixiangOrderDetail(null, List.of());
        }
        JsonNode info = data.path("receive_info");
        ReceiveInfo receiveInfo = info.isObject()
                ? new ReceiveInfo(
                        text(info, "order_id"),
                        text(info, "order_son_id"),
                        text(info, "order_sn"),
                        text(info, "order_son_sn"),
                        text(info, "state"),
                        text(info, "num"),
                        text(info, "send_num"),
                        text(info, "create_time"),
                        text(info, "pay_time"),
                        text(info, "send_time"),
                        text(info, "name"),
                        text(info, "phone"),
                        text(info, "area_name"),
                        text(info, "address"))
                : null;
        List<ProductLine> products = new ArrayList<>();
        JsonNode array = data.path("order_product");
        if (array.isArray()) {
            for (JsonNode node : array) {
                if (!node.isObject()) {
                    continue;
                }
                products.add(new ProductLine(
                        text(node, "order_id"),
                        text(node, "order_son_id"),
                        text(node, "order_product_id"),
                        text(node, "product_id"),
                        text(node, "title"),
                        text(node, "product_spec_name"),
                        text(node, "pronum"),
                        text(node, "member_price"),
                        text(node, "express_code"),
                        text(node, "sn"),
                        text(node, "express_state"),
                        text(node, "pro_state_name"),
                        text(node, "pro_status_name"),
                        text(node, "delivery_remark"),
                        text(node, "supplier_name")));
            }
        }
        return new FeixiangOrderDetail(receiveInfo, products);
    }

    /** JSON 值统一取字符串原文：number/string 都原样保留，null/缺失落空串。 */
    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return "";
        }
        return value.isValueNode() ? value.asText().trim() : "";
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }
}
