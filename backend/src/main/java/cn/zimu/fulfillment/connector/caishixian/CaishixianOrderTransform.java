package cn.zimu.fulfillment.connector.caishixian;

import cn.zimu.fulfillment.common.domain.CountQuantity;
import cn.zimu.fulfillment.common.domain.CountQuantity.InvalidCountQuantityException;
import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.customer.ImportedCustomerIdentity;
import cn.zimu.fulfillment.file.StructuredOrderRow;
import cn.zimu.fulfillment.order.domain.LineType;
import cn.zimu.fulfillment.order.domain.SettlementMethod;
import cn.zimu.fulfillment.order.dto.CanonicalOrderInput;
import cn.zimu.fulfillment.order.dto.CustomerInput;
import cn.zimu.fulfillment.order.dto.OrderItemInput;
import cn.zimu.fulfillment.order.dto.Receiver;
import cn.zimu.fulfillment.order.dto.Settlement;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 彩食鲜订单 JSON（orderList + orderDetail）→ {@link StructuredOrderRow} 转换。
 *
 * <p>字段映射（契约见 {@code docs/research/caishixian-scc-wapi-export-api.md} §4.4/§4.5，实测）：
 * <ul>
 *   <li><b>ID 纪律（三个身份绝不混用）</b>：{@code orderCode}（主订单编号，业务身份）→ sourceRef；
 *       {@code orderKey}（订单键，拆单带子单后缀）→ sourceLineRef；orderList 行的 {@code id}
 *       （平台内部主键，detail 接口用）→ 快照 {@code platform_order_id}；</li>
 *   <li>{@code orderTime} → {@link CanonicalOrderInput#sourceOrderedAt}（orders 表 V64
 *       {@code source_ordered_at}，语义就是「来源订单创建时间」）；{@code payTime} 只作结算时间
 *       与快照证据，不顶替下单时间；两者都缺时如实为 null（V64 语义：不借用导入时刻）；</li>
 *   <li>detail {@code supplierOrderGoodsVo[]} → 订单行（goodsCode → sourceSkuRef，与 Excel 路径
 *       「商品编号」同源，既有 SKU 映射引用继续生效）；</li>
 *   <li>收货人：姓名/电话取 orderList（detail 缺省兜底），省/市/区/详细地址取 detail；
 *       canonical 地址 = 省+市+区+详细地址无分隔拼接——与 Excel 解析器 join 及
 *       {@code CaishixianHttpShipmentGateway#joinAddress} 逐字一致，保证发货前收货人核对不误伤。</li>
 * </ul>
 *
 * <p><b>证据边界（不造数）</b>：detail 拉取失败、收货信息不完整、商品数量非法、orderTime 缺失
 * 都不猜——保留脱敏原始血缘并转 {@link StructuredOrderRow#reviewRequired}，绝不造出可履约订单。
 * 拉回行的 {@code orderStatus} 若不是「3=待发货」，在快照打 {@code order_status_unexpected}
 * 标记（研究文档自认 orderStatus 语义基于单次观测——这里让生产数据自动交叉验证）。</p>
 *
 * <p><b>已知缺失</b>：JSON 契约没有 Excel 22 列中的「站点编码」（快照打
 * {@code site_code_missing} 标记）。Excel 路径拿它当客户身份（customerRef）；JSON 路径客户身份
 * 改用收货人姓名+电话二元组（{@link ImportedCustomerIdentity}，与聚福宝结构化拉取同规），
 * 订单幂等不受影响（orderExists 按渠道+sourceRef 判重）。</p>
 *
 * <p><b>脱敏</b>：快照走白名单；收货人姓名/电话以 {@code receiver_name}/{@code receiver_phone}
 * 键存放，由 SourceImportService.sanitizeSnapshot 统一掩码；省/市/区/详细地址保持明文
 * ——与 Excel 路径 raw_cells 的既有口径一致，且是回填工作簿重建（在线发货）所必需。</p>
 */
@Component
public final class CaishixianOrderTransform {

    private static final Logger log = LoggerFactory.getLogger(CaishixianOrderTransform.class);

    static final String DETAIL_REVIEW_CODE = "CAISHIXIAN_DETAIL_REQUIRED";
    static final String RECEIVER_REVIEW_CODE = "CAISHIXIAN_RECEIVER_REQUIRED";
    static final String QUANTITY_REVIEW_CODE = "CAISHIXIAN_QUANTITY_INVALID";

    /** 平台待发货状态码（"3"，单次观测语义——拉回行不等于它时打交叉验证标记）。 */
    static final int ORDER_STATUS_WAIT_DEPOT = 3;

    /** 缺省规格/单位口径与 Excel 解析器 build() 的 fallback 一致。 */
    static final String SPEC_MISSING = "来源未提供";
    static final String UNIT_MISSING = "来源数量单位";

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE_TIME_MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    /**
     * @param listItem orderList 的订单对象（必有）
     * @param detail   orderDetail 的 data 节点；拉取失败时传 null → 整单转人工复核
     */
    public StructuredOrderRow toRow(JsonNode listItem, JsonNode detail) {
        String orderCode = text(listItem, "orderCode");
        String orderKey = text(listItem, "orderKey");
        String sourceRef = orderCode.isBlank() ? orderKey : orderCode;
        String sourceLineRef = orderKey.isBlank() ? sourceRef : orderKey;

        Instant sourceOrderedAt = parseTime(text(listItem, "orderTime"));
        Instant payTime = parseTime(text(listItem, "payTime"));
        if (sourceOrderedAt == null) {
            // V64 语义：来源没给下单时间就如实为 null，不借用结算/导入时刻；只诚实记录一次，
            // 供事后排查是哪个来源单号缺了 orderTime（这是本票要修的「彩食鲜全空」主字段）。
            log.warn("彩食鲜订单缺少可解析的 orderTime，source_ordered_at 落 null：sourceRef={}", sourceRef);
        }
        // 结算口径取 payTime（真实支付时刻）；缺失退 orderTime；两者都缺时退导入时刻——
        // 与 Excel 路径「源数据无时间列则结算时间兜底导入时刻」的既有口径一致（V64 注释）。
        Instant settlementTime = payTime != null ? payTime
                : sourceOrderedAt != null ? sourceOrderedAt : Instant.now();

        Receiver receiver = receiverOf(listItem, detail);
        ImportedCustomerIdentity customerIdentity = ImportedCustomerIdentity.from(
                receiver == null ? null : receiver.name(),
                receiver == null ? null : receiver.phone());
        List<OrderItemInput> items = itemsOf(detail, sourceLineRef);
        CanonicalOrderInput canonical = new CanonicalOrderInput(
                SourceChannel.CAISHIXIAN,
                sourceRef,
                null,
                new CustomerInput(
                        null,
                        customerIdentity.sourceCustomerRef(),
                        customerIdentity.complete() ? customerIdentity.normalizedName() : "待匹配客户"),
                receiver,
                items,
                new Settlement(SettlementMethod.OTHER, settlementTime),
                sourceOrderedAt,
                text(detail, "remark").isBlank() ? null : text(detail, "remark"),
                null);

        Map<String, Object> snapshot = snapshot(listItem, detail, receiver);
        if (detail == null) {
            return StructuredOrderRow.reviewRequired(
                    sourceRef, sourceLineRef, canonical, snapshot, DETAIL_REVIEW_CODE,
                    "彩食鲜订单详情拉取失败，缺少收货地址与商品明细，禁止生成可履约订单");
        }
        if (receiver == null) {
            return StructuredOrderRow.reviewRequired(
                    sourceRef, sourceLineRef, canonical, snapshot, RECEIVER_REVIEW_CODE,
                    "彩食鲜订单缺少完整收货信息，禁止生成可履约订单");
        }
        if (items.isEmpty() || hasInvalidQuantity(detail)) {
            return StructuredOrderRow.reviewRequired(
                    sourceRef, sourceLineRef, canonical, snapshot, QUANTITY_REVIEW_CODE,
                    "彩食鲜订单商品行缺失或数量非法，禁止生成可履约订单");
        }
        return new StructuredOrderRow(sourceRef, sourceLineRef, canonical, snapshot);
    }

    // ---------------------------------------------------------------- 收货人

    /** 姓名/电话优先 orderList（detail 兜底）；省/市/区/详细地址只有 detail 有。 */
    private Receiver receiverOf(JsonNode listItem, JsonNode detail) {
        String name = firstText(listItem, detail, "receiverName");
        String phone = firstText(listItem, detail, "receiverTelephone");
        String province = text(detail, "receiverProvince");
        String city = text(detail, "receiverCity");
        String district = text(detail, "receiverDistrict");
        String detailAddress = text(detail, "receiverAddress");
        // 与 SourceFileParser.join / CaishixianHttpShipmentGateway.joinAddress 完全一致：
        // 逐段 trim、跳过空段、无分隔符拼接。发货前 sameAddress 核对靠这份一致性。
        String address = joinAddress(province, city, district, detailAddress);
        if (name.isBlank() || phone.isBlank() || address.isBlank()) {
            return null;
        }
        return new Receiver(
                name,
                phone,
                blankToNull(province),
                blankToNull(city),
                blankToNull(district),
                null,
                address);
    }

    static String joinAddress(String... parts) {
        StringBuilder value = new StringBuilder();
        for (String part : parts) {
            if (part != null && !part.isBlank()) {
                value.append(part.trim());
            }
        }
        return value.toString();
    }

    // ---------------------------------------------------------------- 商品行

    private List<OrderItemInput> itemsOf(JsonNode detail, String sourceLineRef) {
        if (detail == null || !detail.path("supplierOrderGoodsVo").isArray()) {
            return List.of();
        }
        List<OrderItemInput> items = new ArrayList<>();
        for (JsonNode goods : detail.path("supplierOrderGoodsVo")) {
            String goodsCode = text(goods, "goodsCode");
            String goodsName = text(goods, "goodsName");
            Integer quantity = quantityOf(goods);
            if (goodsCode.isBlank() || goodsName.isBlank() || quantity == null) {
                // 数量/编号/名称缺失的行不造数、不产生该行；订单级 reviewRequired 由
                // hasInvalidQuantity 统一判定，原始值保留在快照 goods 里可追溯。
                continue;
            }
            String spec = text(goods, "spec");
            String unit = text(goods, "unit");
            items.add(new OrderItemInput(
                    sourceLineRef,
                    LineType.SINGLE,
                    null,
                    goodsCode,
                    goodsName,
                    spec.isBlank() ? SPEC_MISSING : spec,
                    unit.isBlank() ? UNIT_MISSING : unit,
                    quantity,
                    null));
        }
        return List.copyOf(items);
    }

    /** count 接受数学整数（兼容 3.000）并在边界归一为 int32；其余一律不造数。 */
    private Integer quantityOf(JsonNode goods) {
        JsonNode count = goods.path("count");
        try {
            return CountQuantity.fromPositiveFileValue(count.asText(""));
        } catch (InvalidCountQuantityException exception) {
            return null;
        }
    }

    private boolean hasInvalidQuantity(JsonNode detail) {
        if (detail == null || !detail.path("supplierOrderGoodsVo").isArray()) {
            return false;
        }
        for (JsonNode goods : detail.path("supplierOrderGoodsVo")) {
            if (quantityOf(goods) == null
                    || text(goods, "goodsCode").isBlank()
                    || text(goods, "goodsName").isBlank()) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- 快照（脱敏白名单）

    /**
     * raw_import_rows.raw_cells 的 snapshot 白名单。键名尽量沿用 Excel 22 列的中文列名——
     * 回填工作簿重建（CaishixianShipmentArtifactFactory 结构化分支）按这些键取值。
     */
    private Map<String, Object> snapshot(JsonNode listItem, JsonNode detail, Receiver receiver) {
        Map<String, Object> out = new LinkedHashMap<>();
        // 三个身份分别保存，绝不混用
        out.put("主订单编号", text(listItem, "orderCode"));
        out.put("子订单编号", text(listItem, "orderKey"));
        out.put("platform_order_id", text(listItem, "id"));
        out.put("采购单号", firstText(listItem, detail, "purchaseCode"));
        out.put("供应商编码", text(listItem, "supplierCode"));
        // JSON 契约没有站点编码（Excel 22 列已知缺失项），显式打标记而不是造空列
        out.put("site_code_missing", true);
        // 收货人姓名/电话交 sanitizeSnapshot 统一掩码；省/市/区/详细地址与 Excel raw_cells
        // 口径一致保持明文（回填工作簿重建必需）
        out.put("receiver_name", receiver == null ? "" : receiver.name());
        out.put("receiver_phone", receiver == null ? "" : receiver.phone());
        out.put("省", text(detail, "receiverProvince"));
        out.put("市", text(detail, "receiverCity"));
        out.put("区", text(detail, "receiverDistrict"));
        out.put("详细地址", text(detail, "receiverAddress"));
        out.put("物流要求编码", text(detail, "expressRequirementCode"));
        out.put("物流要求名称", text(detail, "expressRequirementName"));
        out.put("订单备注", text(detail, "remark"));
        out.put("vip订单标识", text(listItem, "vip"));
        // 平台证据（窗口/状态语义交叉验证靠它们）
        out.put("orderStatus", listItem.path("orderStatus").asText(""));
        out.put("orderStatusEnumName", text(listItem, "orderStatusEnumName"));
        out.put("payTime", text(listItem, "payTime"));
        out.put("orderTime", text(listItem, "orderTime"));
        out.put("snCode", text(listItem, "snCode"));
        int status = listItem.path("orderStatus").asInt(ORDER_STATUS_WAIT_DEPOT);
        if (status != ORDER_STATUS_WAIT_DEPOT) {
            // 研究文档自认 orderStatus=3 语义基于单次观测：拉回行不是 3 时打标，
            // 生产数据自动交叉验证「状态筛选是否如预期」。
            out.put("order_status_unexpected", true);
        }
        if (parseTime(text(listItem, "orderTime")) == null) {
            out.put("order_time_missing", true);
        }
        if (detail == null) {
            out.put("detail_missing", true);
        } else {
            out.put("goods", goodsSnapshot(detail));
        }
        return out;
    }

    /** 商品行白名单：回填工作簿需要 商品编号/商品名称/下单数量；spec/unit/outCount 为核对证据。 */
    private List<Map<String, Object>> goodsSnapshot(JsonNode detail) {
        List<Map<String, Object>> goodsList = new ArrayList<>();
        if (detail.path("supplierOrderGoodsVo").isArray()) {
            for (JsonNode goods : detail.path("supplierOrderGoodsVo")) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("商品编号", text(goods, "goodsCode"));
                entry.put("商品名称", text(goods, "goodsName"));
                entry.put("下单数量", goods.path("count").asText(""));
                entry.put("spec", text(goods, "spec"));
                entry.put("unit", text(goods, "unit"));
                entry.put("outCount", goods.path("outCount").asText(""));
                goodsList.add(entry);
            }
        }
        return List.copyOf(goodsList);
    }

    // ---------------------------------------------------------------- 时间与文本工具

    /**
     * 平台时间字符串 → Instant（Asia/Shanghai 口径，与 SourceFileParser.parseTime 一致）。
     * 兼容 {@code yyyy-MM-dd HH:mm:ss} / {@code yyyy-MM-dd HH:mm} / {@code yyyy-MM-dd} /
     * epoch 秒或毫秒；解析不出如实返回 null（不造时间）。
     */
    static Instant parseTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String text = value.trim();
        if (text.matches("\\d{10}")) {
            return Instant.ofEpochSecond(Long.parseLong(text));
        }
        if (text.matches("\\d{13}")) {
            return Instant.ofEpochMilli(Long.parseLong(text));
        }
        try {
            return LocalDateTime.parse(text, DATE_TIME).atZone(SHANGHAI).toInstant();
        } catch (RuntimeException ignored) {
            // 继续尝试下一格式
        }
        try {
            return LocalDateTime.parse(text, DATE_TIME_MINUTE).atZone(SHANGHAI).toInstant();
        } catch (RuntimeException ignored) {
            // 继续尝试下一格式
        }
        try {
            return LocalDate.parse(text).atStartOfDay(SHANGHAI).toInstant();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /** listItem 优先、detail 兜底的文本读取（两边都可能缺）。 */
    private static String firstText(JsonNode primary, JsonNode fallback, String field) {
        String value = text(primary, field);
        return value.isBlank() ? text(fallback, field) : value;
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return "";
        }
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("").trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
