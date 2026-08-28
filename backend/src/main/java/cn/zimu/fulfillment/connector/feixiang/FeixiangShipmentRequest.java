package cn.zimu.fulfillment.connector.feixiang;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * {@code POST /order/ajaxSendOrderProduct} 的<b>纯</b>请求报文（零 I/O）。
 *
 * <p>这是 dry-run 的核心 seam：真写与演练共用同一个构造与同一份 {@link #formBody()}，
 * 唯一的分叉在网关最后一步是否打开 socket。因此「人核对过的报文」与「真正发出的报文」
 * 逐字节相同，不存在两套代码走形。</p>
 *
 * <p><b>标识符与字符集是硬门闩，绝不静默清洗。</b>飞象有五种互不可代入的 ID
 * （见 {@link FeixiangOrderDetail}），发货提交只认<b>数字</b> {@code order_product_id}；
 * 订单号 {@code D…}、子订单号 {@code S…} 传进来会在这里被拒，而不是被「清洗」成某个数字。
 * 承运商只接受平台 {@code express_code}（小写英数），中文显示名「京东物流」同样在这里被拒——
 * 拒绝比发出一个平台看不懂的请求安全得多。</p>
 *
 * @param orderProductIds 选中的商品行 ID，按传入顺序去重；每个 ID 在报文里<b>各占一个</b>
 *                        {@code order_product_ids[]} 键（平台按 PHP 数组语义解析）
 * @param trackingNumber  运单号（{@code sn}）
 * @param expressCode     平台物流公司代码（{@code express_code}），不是显示名
 * @param deliveryRemark  备注；HAR 实测为空串，允许留空
 */
public record FeixiangShipmentRequest(
        List<String> orderProductIds,
        String trackingNumber,
        String expressCode,
        String deliveryRemark) {

    /** 商品行 ID：纯数字。订单号（D…）/子订单号（S…）在此被拒。 */
    private static final String ORDER_PRODUCT_ID_PATTERN = "^[0-9]{1,32}$";
    /** 运单号：只许英数、下划线与连字符；含空格、中文或百分号一律拒绝。 */
    private static final String TRACKING_PATTERN = "^[0-9A-Za-z_-]{1,64}$";
    /** 平台物流公司代码：小写英数与下划线（HAR 实测 {@code jingdong}）。 */
    private static final String EXPRESS_CODE_PATTERN = "^[0-9a-z_]{1,32}$";
    private static final int MAX_REMARK_LENGTH = 200;
    private static final int MAX_LINES = 200;

    public FeixiangShipmentRequest {
        if (orderProductIds == null || orderProductIds.isEmpty()) {
            throw new IllegalArgumentException("order_product_ids 不能为空");
        }
        LinkedHashSet<String> distinct = new LinkedHashSet<>();
        for (String raw : orderProductIds) {
            String id = raw == null ? "" : raw.trim();
            if (!id.matches(ORDER_PRODUCT_ID_PATTERN)) {
                // 不回显原值：可能是订单号或子订单号，属于业务标识，日志里没必要重复。
                throw new IllegalArgumentException("order_product_id 必须是数字商品行 ID");
            }
            distinct.add(id);
        }
        if (distinct.size() > MAX_LINES) {
            throw new IllegalArgumentException("单次发货提交的商品行过多");
        }
        orderProductIds = List.copyOf(distinct);

        trackingNumber = trackingNumber == null ? "" : trackingNumber.trim();
        if (!trackingNumber.matches(TRACKING_PATTERN)) {
            throw new IllegalArgumentException("运单号只允许英数、下划线与连字符");
        }
        expressCode = expressCode == null ? "" : expressCode.trim();
        if (!expressCode.matches(EXPRESS_CODE_PATTERN)) {
            throw new IllegalArgumentException("express_code 必须是平台物流公司代码（小写英数），不接受中文显示名");
        }
        deliveryRemark = deliveryRemark == null ? "" : deliveryRemark.trim();
        if (deliveryRemark.length() > MAX_REMARK_LENGTH) {
            throw new IllegalArgumentException("发货备注过长");
        }
        for (int index = 0; index < deliveryRemark.length(); index++) {
            if (Character.isISOControl(deliveryRemark.charAt(index))) {
                throw new IllegalArgumentException("发货备注不得含控制字符");
            }
        }
    }

    /**
     * {@code application/x-www-form-urlencoded} 报文，与 2026-08-28 HAR 实测逐字对齐：
     * {@code order_product_ids%5B%5D=43231540&sn=…&express_code=jingdong&delivery_remark=}。
     */
    public String formBody() {
        StringBuilder body = new StringBuilder();
        for (String id : orderProductIds) {
            if (!body.isEmpty()) {
                body.append('&');
            }
            body.append("order_product_ids%5B%5D=").append(id);
        }
        body.append("&sn=").append(trackingNumber)
                .append("&express_code=").append(expressCode)
                .append("&delivery_remark=").append(encode(deliveryRemark));
        return body.toString();
    }

    /** 审计用摘要：只含标识符与承运商代码，不含收货人任何字段。 */
    public java.util.Map<String, Object> auditPayload() {
        return java.util.Map.of(
                "order_product_ids", orderProductIds,
                "sn", trackingNumber,
                "express_code", expressCode,
                "delivery_remark_present", !deliveryRemark.isEmpty(),
                "form_body", formBody());
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
