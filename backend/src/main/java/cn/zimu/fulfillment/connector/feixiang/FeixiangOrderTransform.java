package cn.zimu.fulfillment.connector.feixiang;

import cn.zimu.fulfillment.common.domain.CountQuantity;
import cn.zimu.fulfillment.common.domain.CountQuantity.InvalidCountQuantityException;
import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.common.text.ReceiverFactsNormalizer;
import cn.zimu.fulfillment.customer.ImportedCustomerIdentity;
import cn.zimu.fulfillment.file.StructuredOrderRow;
import cn.zimu.fulfillment.order.domain.LineType;
import cn.zimu.fulfillment.order.domain.SettlementMethod;
import cn.zimu.fulfillment.order.dto.CanonicalOrderInput;
import cn.zimu.fulfillment.order.dto.CustomerInput;
import cn.zimu.fulfillment.order.dto.OrderItemInput;
import cn.zimu.fulfillment.order.dto.Receiver;
import cn.zimu.fulfillment.order.dto.Settlement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 飞象订单详情 JSON → {@link StructuredOrderRow} 转换。
 *
 * <p><b>字段映射与既有 Excel 链路严格同口径</b>（见 {@code SourceFileParser#feixiangRow}），
 * 这不是风格选择而是硬要求：生产库里已导入的飞象订单，其 {@code source_ref} 来自 Excel 的
 * 「订单号」列。JSON 链路若改用别的 ID 作来源单号，判重会全部失效、历史订单会被重复建单。
 *
 * <table border="1">
 *   <caption>映射对照</caption>
 *   <tr><th>Excel 列</th><th>JSON 字段</th><th>去向</th></tr>
 *   <tr><td>订单号</td><td>{@code receive_info.order_sn}（D…）</td><td>source_ref</td></tr>
 *   <tr><td>订单商品ID</td><td>{@code order_product[].order_product_id}</td><td>订单行 source_line_ref</td></tr>
 *   <tr><td>商品ID</td><td>{@code order_product[].product_id}</td><td>source_sku_ref</td></tr>
 *   <tr><td>商品名称</td><td>{@code order_product[].title}</td><td>商品名</td></tr>
 *   <tr><td>商品规格</td><td>{@code order_product[].product_spec_name}</td><td>规格</td></tr>
 *   <tr><td>可发货数量</td><td>{@code order_product[].pronum}</td><td>数量</td></tr>
 *   <tr><td>下单时间</td><td>{@code receive_info.create_time}</td><td><b>source_ordered_at</b></td></tr>
 *   <tr><td>收货人姓名/手机号/地址</td><td>{@code receive_info.name/phone/area_name+address}</td><td>Receiver</td></tr>
 * </table>
 *
 * <p><b>按 order_sn 分组</b>：一个 {@code order_sn} 可能对应多个 {@code order_son}（平台按供应商
 * 拆单）。Excel 链路是「一行一个订单商品ID、按订单号归并成一单」，这里保持一致：同一
 * {@code order_sn} 的所有子单商品行合并进同一个 StructuredOrderRow。否则第二个子单会因
 * source_ref 重复被判重跳过，商品行静默丢失。</p>
 *
 * <p><b>不静默造数</b>：收货信息不全、数量非正整数、下单时间无法解析、商品行已有物流事实
 * ——四种情况一律走 {@link StructuredOrderRow#reviewRequired}，保留脱敏血缘进人工复核，
 * 绝不猜、不兜底、不生成可履约订单。</p>
 */
@Component
public final class FeixiangOrderTransform {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final String UNIT_DEFAULT = "件";
    private static final String SPEC_MISSING = "—";

    /** 复核原因码（与 Excel 链路的既有码复用，复核队列口径一致）。 */
    static final String RECEIVER_REVIEW_CODE = "FEIXIANG_RECEIVER_REQUIRED";
    static final String RECEIVER_CONFLICT_REVIEW_CODE = "FEIXIANG_RECEIVER_CONFLICT";
    static final String QUANTITY_REVIEW_CODE = "FEIXIANG_QUANTITY_INVALID";
    static final String ORDERED_AT_REVIEW_CODE = "FEIXIANG_CREATE_TIME_REQUIRED";
    static final String ORDER_SN_REVIEW_CODE = "FEIXIANG_ORDER_SN_REQUIRED";
    static final String ALREADY_SHIPPED_REVIEW_CODE = "SOURCE_ORDER_ALREADY_FULFILLED";

    /** {@code create_time} 可能的字符串形态（平台未抓包确认，逐个试；epoch 数字另行处理）。 */
    private static final List<DateTimeFormatter> DATETIME_FORMATS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"));

    /**
     * 把逐单详情转成导入行；同一 {@code order_sn} 的多个子单合并为一单。
     *
     * @param details 逐个 order_son_id 取回的详情（顺序即枚举顺序）
     */
    public List<StructuredOrderRow> toRows(List<FeixiangOrderDetail> details) {
        if (details == null || details.isEmpty()) {
            return List.of();
        }
        Map<String, List<FeixiangOrderDetail>> grouped = new LinkedHashMap<>();
        for (FeixiangOrderDetail detail : details) {
            if (detail == null || detail.receiveInfo() == null) {
                continue;
            }
            grouped.computeIfAbsent(groupKey(detail.receiveInfo()), key -> new ArrayList<>()).add(detail);
        }
        List<StructuredOrderRow> rows = new ArrayList<>();
        grouped.forEach((key, group) -> rows.add(toRow(key, group)));
        return List.copyOf(rows);
    }

    /**
     * 分组键：优先 {@code order_sn}。
     *
     * <p>{@code order_sn} 缺失时用显式合成键兜底，只为让这条记录能带着血缘进复核队列；
     * 前缀让它一眼看出<b>不是</b>平台真实订单号，不会被误当作可判重的来源单号
     * （该分组必然走 {@link #ORDER_SN_REVIEW_CODE} 复核，永不建单）。</p>
     */
    private String groupKey(FeixiangOrderDetail.ReceiveInfo info) {
        if (notBlank(info.orderSn())) {
            return info.orderSn().trim();
        }
        String fallback = notBlank(info.orderSonSn()) ? info.orderSonSn().trim() : info.orderSonId();
        return "FEIXIANG-NO-ORDER-SN:" + (notBlank(fallback) ? fallback.trim() : "unknown");
    }

    private StructuredOrderRow toRow(String groupKey, List<FeixiangOrderDetail> group) {
        FeixiangOrderDetail.ReceiveInfo head = group.getFirst().receiveInfo();
        // 子订单号（S…）作为整行的来源行标识，与 source_ref（D… 订单号）严格分开保存。
        String sourceLineRef = head.orderSonSn();

        List<FeixiangOrderDetail.ProductLine> lines = group.stream()
                .flatMap(detail -> detail.products().stream())
                .toList();

        Instant orderedAt = parseInstant(head.createTime());
        Receiver receiver = head.completeReceiver()
                ? new Receiver(
                        head.name().trim(),
                        head.phone().trim(),
                        null, null, null, null,
                        head.joinedAddress())
                : null;
        ImportedCustomerIdentity identity = ImportedCustomerIdentity.from(
                receiver == null ? null : receiver.name(),
                receiver == null ? null : receiver.phone());

        List<OrderItemInput> items = itemsOf(lines);
        CanonicalOrderInput canonical = new CanonicalOrderInput(
                SourceChannel.FEIXIANG,
                groupKey,
                null,
                new CustomerInput(
                        null,
                        identity.sourceCustomerRef(),
                        identity.complete() ? identity.normalizedName() : "待匹配客户"),
                receiver,
                items,
                // 结算时间沿用 Excel 链路口径（下单时间即结算时间）；来源没给就如实为
                // UNSPECIFIED，不拿导入时刻顶替。source_ordered_at 单独持有同一事实。
                orderedAt == null
                        ? Settlement.unspecifiedSourceFact()
                        : new Settlement(SettlementMethod.OTHER, orderedAt),
                orderedAt,
                null,
                null);

        Map<String, Object> snapshot = snapshotOf(group, head, lines);

        // 复核判定顺序 = 严重性顺序：先「不该建单」，再「建不出单」。
        if (lines.stream().anyMatch(FeixiangOrderDetail.ProductLine::alreadyShipped)) {
            return StructuredOrderRow.reviewRequired(
                    groupKey, sourceLineRef, canonical, snapshot, ALREADY_SHIPPED_REVIEW_CODE,
                    "飞象订单已有物流事实（物流单号或物流公司非空），不重复建单");
        }
        if (!notBlank(head.orderSn())) {
            return StructuredOrderRow.reviewRequired(
                    groupKey, sourceLineRef, canonical, snapshot, ORDER_SN_REVIEW_CODE,
                    "飞象订单详情缺少订单号（order_sn），无法作为来源单号判重");
        }
        if (receiver == null) {
            return StructuredOrderRow.reviewRequired(
                    groupKey, sourceLineRef, canonical, snapshot, RECEIVER_REVIEW_CODE,
                    "飞象订单详情缺少完整收货信息，禁止生成可履约订单");
        }
        // 同一订单号下的多个子单收货信息不一致时，绝不「取第一个」蒙混过去：那会把货发到一个
        // 没人确认过的地址上，且只有翻原始快照才看得见。与文件导入链路对同型问题的既有处置
        // 一致（SourceImportService#upload 的收货人快照不一致 → 整组进复核）。
        if (group.size() > 1 && !sameReceiverAcross(group)) {
            return StructuredOrderRow.reviewRequired(
                    groupKey, sourceLineRef, canonical, snapshot, RECEIVER_CONFLICT_REVIEW_CODE,
                    "飞象同一订单号下各子单的收货信息不一致，禁止按其中之一生成可履约订单");
        }
        if (items.isEmpty() || items.size() != lines.size()) {
            return StructuredOrderRow.reviewRequired(
                    groupKey, sourceLineRef, canonical, snapshot, QUANTITY_REVIEW_CODE,
                    "飞象订单商品行数量缺失/非正整数，或商品名为空，禁止生成可履约订单");
        }
        if (orderedAt == null) {
            return StructuredOrderRow.reviewRequired(
                    groupKey, sourceLineRef, canonical, snapshot, ORDERED_AT_REVIEW_CODE,
                    "飞象订单缺少可解析的下单时间（create_time），禁止生成可履约订单");
        }
        return new StructuredOrderRow(groupKey, sourceLineRef, canonical, snapshot);
    }

    // ---------------------------------------------------------------- 商品行

    private List<OrderItemInput> itemsOf(List<FeixiangOrderDetail.ProductLine> lines) {
        List<OrderItemInput> items = new ArrayList<>();
        for (FeixiangOrderDetail.ProductLine line : lines) {
            Integer quantity = positiveInteger(line.pronum());
            if (quantity == null) {
                // 不静默造数：数量非法的行不产生 item，整单转复核（见 toRow 的 items.size() 判据）。
                continue;
            }
            String title = notBlank(line.title()) ? line.title().trim() : "";
            if (title.isEmpty()) {
                continue;
            }
            items.add(new OrderItemInput(
                    // 订单行的来源标识是商品行 ID（order_product_id），与订单号、子订单号、
                    // order_son_id 全部不同——四者绝不互相代入。
                    line.orderProductId(),
                    LineType.SINGLE,
                    null,
                    line.productId(),
                    title,
                    notBlank(line.productSpecName()) ? line.productSpecName().trim() : SPEC_MISSING,
                    UNIT_DEFAULT,
                    quantity,
                    null));
        }
        return List.copyOf(items);
    }

    /** {@code pronum} 接受正 int32 数学整数（兼容 3.000）；其余返回 null。 */
    private static Integer positiveInteger(String raw) {
        try {
            return CountQuantity.fromPositiveFileValue(raw);
        } catch (InvalidCountQuantityException ignored) {
            return null;
        }
    }

    // ---------------------------------------------------------------- 时间

    /**
     * {@code create_time} 解析：平台格式未经抓包确认，兼容 epoch 秒/毫秒与常见日期时间串。
     *
     * <p>解析不出来就返回 null——<b>不猜、不用导入时刻顶替</b>。null 会让整单进复核，
     * 而不是带着一个编造的下单时间进业务库。</p>
     */
    static Instant parseInstant(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (value.chars().allMatch(Character::isDigit)) {
            try {
                long number = Long.parseLong(value);
                if (number <= 0) {
                    return null;
                }
                // 13 位视作毫秒，10 位视作秒；其余长度语义不明，拒绝。
                if (value.length() == 13) {
                    return Instant.ofEpochMilli(number);
                }
                return value.length() == 10 ? Instant.ofEpochSecond(number) : null;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        for (DateTimeFormatter format : DATETIME_FORMATS) {
            try {
                return LocalDateTime.parse(value, format).atZone(SHANGHAI).toInstant();
            } catch (RuntimeException ignored) {
                // 继续试下一种
            }
        }
        try {
            return LocalDate.parse(value).atStartOfDay(SHANGHAI).toInstant();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    // ---------------------------------------------------------------- 脱敏快照

    /**
     * 原始证据快照：严格字段允许表。
     *
     * <p><b>刻意不含收货人姓名/电话/地址</b>——这些事实已经作为业务字段进了 Receiver，
     * 没有理由在导入血缘里再存一份明文 PII。供应商名掩码；未知嵌套字段一律不入库。</p>
     *
     * <p>五类 ID 分字段保存，正是为了让复核的人能一眼分清哪个是订单号、哪个是详情用的
     * 数字 ID——HAR 分析里已经出现过一次混用导致的平台拒绝。</p>
     */
    private Map<String, Object> snapshotOf(
            List<FeixiangOrderDetail> group,
            FeixiangOrderDetail.ReceiveInfo head,
            List<FeixiangOrderDetail.ProductLine> lines) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("order_sn", head.orderSn());
        out.put("order_son_sn", head.orderSonSn());
        out.put("order_son_id", head.orderSonId());
        out.put("order_id", head.orderId());
        out.put("state", head.state());
        out.put("num", head.num());
        out.put("send_num", head.sendNum());
        out.put("create_time", head.createTime());
        out.put("pay_time", head.payTime());
        if (group.size() > 1) {
            // 同一订单号下的全部子单 ID，供复核核对合并是否正确。
            out.put("merged_order_son_ids", group.stream()
                    .map(detail -> detail.receiveInfo().orderSonId())
                    .toList());
        }
        LinkedHashSet<String> suppliers = new LinkedHashSet<>();
        List<Map<String, Object>> products = new ArrayList<>();
        for (FeixiangOrderDetail.ProductLine line : lines) {
            Map<String, Object> product = new LinkedHashMap<>();
            product.put("order_product_id", line.orderProductId());
            product.put("order_son_id", line.orderSonId());
            product.put("product_id", line.productId());
            product.put("title", line.title());
            product.put("product_spec_name", line.productSpecName());
            product.put("pronum", line.pronum());
            product.put("express_code", line.expressCode());
            product.put("sn", line.sn());
            product.put("express_state", line.expressState());
            product.put("pro_state_name", line.proStateName());
            products.add(product);
            if (notBlank(line.supplierName())) {
                suppliers.add(mask(line.supplierName().trim()));
            }
        }
        out.put("order_product", products);
        if (!suppliers.isEmpty()) {
            out.put("supplier_names", List.copyOf(suppliers));
        }
        // 收货人不进快照，但「是否齐备」这一事实要留痕，复核时才知道为什么被拦。
        out.put("receiver_complete", head.completeReceiver());
        // 收货地址一致性：同一订单号的多个子单收货信息不一致时显式标记（不静默取第一个）。
        if (group.size() > 1 && !sameReceiverAcross(group)) {
            out.put("receiver_conflict_across_sub_orders", true);
        }
        return Map.copyOf(out);
    }

    /** 同一订单号下各子单的收货三要素是否一致（规范化后比较）。 */
    private static boolean sameReceiverAcross(List<FeixiangOrderDetail> group) {
        FeixiangOrderDetail.ReceiveInfo head = group.getFirst().receiveInfo();
        String name = ReceiverFactsNormalizer.normalizeName(head.name());
        String phone = ReceiverFactsNormalizer.normalizePhone(head.phone());
        String address = ReceiverFactsNormalizer.normalizeAddress(head.joinedAddress());
        for (FeixiangOrderDetail detail : group) {
            FeixiangOrderDetail.ReceiveInfo info = detail.receiveInfo();
            if (!name.equals(ReceiverFactsNormalizer.normalizeName(info.name()))
                    || !phone.equals(ReceiverFactsNormalizer.normalizePhone(info.phone()))
                    || !address.equals(ReceiverFactsNormalizer.normalizeAddress(info.joinedAddress()))) {
                return false;
            }
        }
        return true;
    }

    private static String mask(String text) {
        return text.length() <= 3 ? "***" : text.substring(0, 3) + "***";
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
