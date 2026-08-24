package cn.zimu.fulfillment.connector.jufubao;

import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.file.StructuredOrderRow;
import cn.zimu.fulfillment.order.domain.LineType;
import cn.zimu.fulfillment.order.domain.SettlementMethod;
import cn.zimu.fulfillment.order.dto.CanonicalOrderInput;
import cn.zimu.fulfillment.order.dto.CustomerInput;
import cn.zimu.fulfillment.order.dto.OrderItemInput;
import cn.zimu.fulfillment.order.dto.Receiver;
import cn.zimu.fulfillment.order.dto.Settlement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 聚福宝订单 JSON → {@link StructuredOrderRow} 转换（ticket 09）。
 *
 * <p>字段映射（契约见 {@code docs/research/jufubao-supplier-export-api.md}，样例为真实抓包）：
 * <ul>
 *   <li>{@code main_order_id} → sourceRef（对应 Excel 闭环的「主单号」）；</li>
 *   <li>{@code sub_order_id} → sourceLineRef（「拆单号」）；</li>
 *   <li>{@code product_list[]} → 订单行（product_id → sourceSkuRef，product_name → 商品名，
 *       product_num → 数量）；</li>
 *   <li>{@code supplier_name} → 客户引用（聚福宝侧无独立客户档案，以供应商名为客户身份）；</li>
 *   <li>{@code created_time}（epoch 秒）→ 结账时间。</li>
 * </ul>
 *
 * <p><b>收货人边界</b>：orders/query 的 list 不提供完整姓名/电话/地址；拉单 Connector 必须按
 * 子单读取已捕获的 sub-order-info，并把其完整 receiver 传入本转换器。详情缺失或字段不完整时
 * 不构造空 Receiver、不创建可履约订单，而是通过 {@link StructuredOrderRow#reviewRequired}
 * 保留脱敏原始血缘并进入人工复核。</p>
 *
 * <p><b>脱敏</b>：rawSnapshot 只保留已捕获契约中的允许字段；供应商名掩码，商品列表逐字段
 * allowlist，不让未知嵌套字段、Cookie 或令牌进入导入血缘。</p>
 */
@Component
public final class JufubaoOrderTransform {

    static final String RECEIVER_REVIEW_CODE = "JUFUBAO_RECEIVER_REQUIRED";

    /** 缺省规格占位（平台未下发规格字段时使用）。 */
    static final String SPEC_MISSING = "—";
    /** 缺省单位（平台未下发单位字段时使用）。 */
    static final String UNIT_DEFAULT = "件";

    /**
     * 数量诚实化标记（第二轮评审 F3）：product_num 缺失/非正整数时，该商品行<b>不产生
     * quantity</b>（不再静默造数 "1"），订单级 rawSnapshot 写入 {@code quantity_invalid: true}，
     * 原始 product_num 保留在 product_list 可追溯。
     *
     * <p>取舍说明：CanonicalOrderInput 下游（OrderCreateService.createSingleLine →
     * {@code new BigDecimal(quantity)}）对空串直接抛 NumberFormatException，会把整批导入打挂；
     * 而 "0" 能通过 BigDecimal 解析、会产生 0 数量的履约（静默坏数据）。两者都不能接受，
     * 因此 transform 侧对非法数量行直接跳过（不装配进 canonical items），由人工在复核环节
     * 依据 rawSnapshot 的 quantity_invalid 标记修正——诚实标记，不静默造数。
     * 整单所有商品行数量均非法时 items 为空，reviewRequired 导入仍会保留一条 NEED_REVIEW
     * 原始血缘；不会造出数量，也不会生成订单。</p>
     */
    static final String QUANTITY_INVALID_MARKER = "quantity_invalid";

    public List<StructuredOrderRow> toRows(List<Map<String, Object>> orders) {
        List<StructuredOrderRow> rows = new ArrayList<>();
        if (orders != null) {
            for (Map<String, Object> order : orders) {
                rows.add(toRow(order));
            }
        }
        return rows;
    }

    public StructuredOrderRow toRow(Map<String, Object> order) {
        return toRow(order, null);
    }

    public StructuredOrderRow toRow(
            Map<String, Object> order,
            JufubaoShipmentGateway.ShipmentDetail shipmentDetail) {
        Map<String, Object> source = stringKeys(order);
        String mainOrderId = text(source, "main_order_id");
        String subOrderId = text(source, "sub_order_id");
        String sourceRef = mainOrderId.isBlank() ? subOrderId : mainOrderId;

        String supplierName = text(source, "supplier_name");
        String customerRef = supplierName.isBlank() ? (sourceRef.isBlank() ? "JUFUBAO" : sourceRef) : supplierName;
        long createdEpoch = epochOf(source.get("created_time"), 0L);

        JufubaoShipmentGateway.ReceiverSnapshot currentReceiver =
                shipmentDetail == null ? null : shipmentDetail.receiver();
        Receiver receiver = completeReceiver(currentReceiver)
                ? new Receiver(
                        currentReceiver.name().trim(),
                        currentReceiver.phone().trim(),
                        null, null, null, null,
                        currentReceiver.address().trim())
                : null;
        List<OrderItemInput> items = itemsOf(source, subOrderId);
        CanonicalOrderInput canonical = new CanonicalOrderInput(
                SourceChannel.JUFUBAO,
                sourceRef,
                null,
                new CustomerInput(null, customerRef, customerRef),
                receiver,
                items,
                new Settlement(SettlementMethod.OTHER, Instant.ofEpochSecond(createdEpoch)),
                null,
                null);

        Map<String, Object> snapshot = sanitize(source);
        // F3：任一商品行数量缺失/非正整数 → 订单级标记，供复核定位（原始值保留在 product_list）。
        if (hasInvalidQuantity(source)) {
            snapshot.put(QUANTITY_INVALID_MARKER, true);
        }
        if (!source.containsKey("created_time")) {
            snapshot.put("created_time_missing", true);
        }
        snapshot.put("receiver_source", "sub-order-info");
        snapshot.put("receiver_missing", receiver == null);
        if (receiver == null) {
            return StructuredOrderRow.reviewRequired(
                    sourceRef, subOrderId, canonical, snapshot, RECEIVER_REVIEW_CODE,
                    "聚福宝订单详情缺少完整收货信息，禁止生成可履约订单");
        }
        if (hasInvalidQuantity(source) || items.isEmpty()) {
            return StructuredOrderRow.reviewRequired(
                    sourceRef, subOrderId, canonical, snapshot, "JUFUBAO_QUANTITY_INVALID",
                    "聚福宝订单商品数量缺失或不是正整数，禁止生成可履约订单");
        }
        if (createdEpoch <= 0) {
            return StructuredOrderRow.reviewRequired(
                    sourceRef, subOrderId, canonical, snapshot, "JUFUBAO_CREATED_TIME_REQUIRED",
                    "聚福宝订单缺少有效创建时间，禁止生成可履约订单");
        }
        return new StructuredOrderRow(sourceRef, subOrderId, canonical, snapshot);
    }

    // ---------------------------------------------------------------- 商品行

    private List<OrderItemInput> itemsOf(Map<String, Object> order, String subOrderId) {
        Object list = order.get("product_list");
        if (!(list instanceof List<?> raw)) {
            return List.of();
        }
        List<OrderItemInput> items = new ArrayList<>();
        for (Object element : raw) {
            if (!(element instanceof Map<?, ?> map)) {
                continue;
            }
            Map<String, Object> product = stringKeys(map);
            String productId = text(product, "product_id");
            String skuId = text(product, "product_sku_id");
            String productName = text(product, "product_name");
            if (productName.isBlank()) {
                productName = text(product, "product_sku_name");
            }
            String quantity = quantityOf(product);
            if (quantity.isBlank()) {
                // F3：数量缺失/非正整数 → 不产生该行（见 QUANTITY_INVALID_MARKER 注释的取舍说明）。
                continue;
            }
            items.add(new OrderItemInput(
                    subOrderId,
                    LineType.SINGLE,
                    null,
                    productId,
                    productName,
                    specificationOf(product, skuId),
                    UNIT_DEFAULT,
                    quantity,
                    null));
        }
        return items;
    }

    private String specificationOf(Map<String, Object> product, String skuId) {
        String skuName = text(product, "product_sku_name");
        if (!skuName.isBlank()) {
            return skuName;
        }
        // 平台默认 sku_id="0"（无 SKU 维度），视为未提供规格
        return skuId.isBlank() || "0".equals(skuId) ? SPEC_MISSING : skuId;
    }

    /**
     * F3 严格化：数量只接受正整数（product_num 为件数语义）。缺失/0/负数/小数/非数字一律返回
     * 空串——不静默造数；原始值保留在 rawSnapshot 的 product_list 可追溯。
     */
    private String quantityOf(Map<String, Object> product) {
        Long quantity = positiveInteger(product.get("product_num"));
        return quantity == null ? "" : String.valueOf(quantity);
    }

    /** 订单是否含数量非法的商品行（任一 product_num 非正整数即 true）。 */
    private static boolean hasInvalidQuantity(Map<String, Object> order) {
        Object list = order.get("product_list");
        if (!(list instanceof List<?> raw)) {
            return false;
        }
        for (Object element : raw) {
            if (element instanceof Map<?, ?> map && positiveInteger(extractProductNum(map)) == null) {
                return true;
            }
        }
        return false;
    }

    private static Object extractProductNum(Map<?, ?> product) {
        for (Map.Entry<?, ?> entry : product.entrySet()) {
            if ("product_num".equals(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** product_num 合法值：正整数（Number 或数字字符串）；其余返回 null。 */
    private static Long positiveInteger(Object value) {
        if (value instanceof Number number) {
            double raw = number.doubleValue();
            if (raw > 0 && !Double.isInfinite(raw) && raw <= Long.MAX_VALUE && raw == Math.floor(raw)) {
                return number.longValue();
            }
            return null;
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                long parsed = Long.parseLong(text.trim());
                return parsed > 0 ? parsed : null;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- 脱敏

    /** 严格字段允许表：只保留拉单与人工复核所需证据，未知字段一律不入库。 */
    Map<String, Object> sanitize(Map<String, Object> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        copy(raw, out, "main_order_id");
        copy(raw, out, "sub_order_id");
        copy(raw, out, "created_time");
        String supplierName = text(raw, "supplier_name");
        if (!supplierName.isBlank()) {
            out.put("supplier_name", mask(supplierName));
        }
        Object products = raw.get("product_list");
        if (products instanceof List<?> list) {
            List<Map<String, Object>> sanitizedProducts = new ArrayList<>();
            for (Object element : list) {
                if (!(element instanceof Map<?, ?> map)) {
                    continue;
                }
                Map<String, Object> product = stringKeys(map);
                Map<String, Object> sanitized = new LinkedHashMap<>();
                copy(product, sanitized, "product_id");
                copy(product, sanitized, "product_sku_id");
                copy(product, sanitized, "product_name");
                copy(product, sanitized, "product_sku_name");
                copy(product, sanitized, "product_num");
                sanitizedProducts.add(sanitized);
            }
            out.put("product_list", sanitizedProducts);
        }
        return out;
    }

    private static boolean completeReceiver(JufubaoShipmentGateway.ReceiverSnapshot receiver) {
        return receiver != null
                && receiver.name() != null && !receiver.name().isBlank()
                && receiver.phone() != null && !receiver.phone().isBlank()
                && receiver.address() != null && !receiver.address().isBlank();
    }

    private static void copy(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source.containsKey(key)) {
            target.put(key, source.get(key));
        }
    }

    private static String mask(String text) {
        return text.length() <= 3 ? "***" : text.substring(0, 3) + "***";
    }

    // ---------------------------------------------------------------- 工具

    private static String text(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static long epochOf(Object value, long fallback) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return fallback;
    }

    private static Map<String, Object> stringKeys(Map<?, ?> map) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() instanceof String key) {
                out.put(key, entry.getValue());
            }
        }
        return out;
    }
}
