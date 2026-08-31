package cn.zimu.fulfillment.fulfillment;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 京东 cargoInfos 货品规划的纯函数裁决单元（无数据库/事务依赖）：SINGLE 行/礼包组件的
 * {@linkplain #expand(LineCandidate) 展开}、provider SKU 映射解析、单位/换算系数政策、
 * 精确正整数 planQuantity 与「规划成功/失败」语义统一由本单元裁决。
 *
 * <p>建单预览/提交（{@link ShipmentJdOutboundPreparer}）与来源行投影
 * （ImportRowJdCargoProjectionService）都必须消费本单元的唯一裁决结果，不得另建展开/映射/
 * 换算实现：成功返回 {@link Cargo}（goodsNo/merchantSkuCode/planQuantity 等），失败返回携带
 * 稳定阻断码与消息的 {@link Failure}，由调用方决定阻断建单或跳过行投影。系统不四舍五入
 * 也不向上取整，planQuantity 必须是精确正整数件数。
 */
public final class JdCargoPlanner {

    private JdCargoPlanner() {}

    /** provider_skus 映射事实；仅 active 映射可产出货品（active 门禁由本单元统一执行）。 */
    public record Goods(
            String goodsNo,
            String merchantSkuCode,
            Map<String, Object> externalCodes,
            boolean active) {
    }

    /** 货品规划裁决结果：成功为 {@link Cargo}，失败为 {@link Failure}。 */
    public sealed interface Result permits Cargo, Failure {
    }

    /** 规划成功的货品行：与建单 cargoInfos、行投影 jd_cargos 完全同源。 */
    public record Cargo(
            String orderLine,
            String goodsName,
            String unit,
            String goodsNo,
            String merchantSkuCode,
            Long skuId,
            int planQuantity) implements Result {
    }

    /** 规划失败：与建单 blocker 完全一致的稳定阻断码/消息；投影侧仅用于跳过该货品。 */
    public record Failure(
            int httpStatus,
            String code,
            String path,
            String source,
            String correctionTarget,
            String message) implements Result {
    }

    /** 数量来源描述（仅进入失败消息/校验轨迹）：SINGLE 行的建单换算口径。 */
    private static final String QUANTITY_SOURCE_SINGLE =
            "shipment_items.instructed_quantity × provider_skus.external_codes.jd_pieces_per_unit";
    /** 数量来源描述（仅进入失败消息/校验轨迹）：礼包组件的建单换算口径。 */
    private static final String QUANTITY_SOURCE_BUNDLE =
            "shipment_items.instructed_quantity × order_line_components.quantity_per_bundle "
                    + "× provider_skus.external_codes.jd_pieces_per_unit";

    /** 礼包组件事实（非 SINGLE 行展开使用；列表顺序即建单 cargoInfos 顺序）。 */
    public record ComponentCandidate(
            int componentNo,
            Long skuId,
            String goodsName,
            String unit,
            BigDecimal quantityPerBundle) {
    }

    /** 展开输入：一条订单行（SINGLE 行或礼包行）的事实及其有序组件。 */
    public record LineCandidate(
            String lineType,
            int lineNo,
            Long skuId,
            String goodsName,
            String unit,
            BigDecimal systemQuantity,
            List<ComponentCandidate> components) {
    }

    /** 展开输出：一条待规划货品的输入（orderLine 键、SKU、名称、单位、系统数量、数量来源）。 */
    public record CargoCandidate(
            String orderLine,
            Long skuId,
            String goodsName,
            String unit,
            BigDecimal systemQuantity,
            String quantitySource) {
    }

    /**
     * 把一条订单行展开为有序货品候选（与建单 cargoInfos 顺序一致，调用方不得重排）：
     * SINGLE 行产出单条候选（orderLine = 行号，系统数量不变，来源为指令数量换算）；其余行
     * （CUSTOM_BUNDLE）按组件顺序逐条产出（orderLine = 行号-组件号，系统数量 = 行系统数量 ×
     * quantity_per_bundle）。组件为空时无候选（建单侧由此触发 cargo 为空阻断，投影侧为空数组）。
     */
    public static List<CargoCandidate> expand(LineCandidate line) {
        if ("SINGLE".equals(line.lineType())) {
            return List.of(new CargoCandidate(
                    String.valueOf(line.lineNo()),
                    line.skuId(),
                    line.goodsName(),
                    line.unit(),
                    line.systemQuantity(),
                    QUANTITY_SOURCE_SINGLE));
        }
        List<CargoCandidate> candidates = new ArrayList<>(line.components().size());
        for (ComponentCandidate component : line.components()) {
            candidates.add(new CargoCandidate(
                    line.lineNo() + "-" + component.componentNo(),
                    component.skuId(),
                    component.goodsName(),
                    component.unit(),
                    line.systemQuantity().multiply(component.quantityPerBundle()),
                    QUANTITY_SOURCE_BUNDLE));
        }
        return List.copyOf(candidates);
    }

    /**
     * 规划一条货品：映射缺失/停用、单位缺换算、系数非法、数量不可精确换算或超出整数范围
     * 时返回 {@link Failure}（阻断码/消息与建单 blocker 一致）；否则返回精确正整数
     * planQuantity 的 {@link Cargo}。
     *
     * @param pathPrefix 请求路径前缀（如 "cargoInfos[0]"），投影侧可传 {@code null}
     * @param quantitySource 数量来源描述（仅进入失败消息/校验轨迹）
     */
    public static Result plan(
            Long skuId,
            String orderLine,
            String goodsName,
            String unit,
            BigDecimal quantity,
            String quantitySource,
            String pathPrefix,
            Goods goods) {
        String base = pathPrefix == null ? "" : pathPrefix;
        if (skuId == null || goods == null || !goods.active()) {
            return new Failure(
                    422, "JD_SHIPMENT_OUTBOUND_SKU_MAPPING_MISSING",
                    base + ".goodsNo", "provider_skus.provider_sku_code", "provider SKU mapping",
                    "SKU " + skuId + " 未配置有效京东商品编码，无法建出库单");
        }
        JdStockUnitConverter.OutboundFactorValidation conversion =
                JdStockUnitConverter.validateOutboundFactor(unit, goods.externalCodes());
        if (conversion.status() == JdStockUnitConverter.OutboundFactorStatus.MISSING) {
            return new Failure(
                    422, "JD_SHIPMENT_OUTBOUND_UNIT_CONVERSION_MISSING",
                    base + ".planQuantity", quantitySource, "provider SKU unit conversion",
                    "非‘件’单位必须配置显式京东件数换算；系统不默认为 1");
        }
        if (conversion.status() == JdStockUnitConverter.OutboundFactorStatus.INVALID) {
            return new Failure(
                    422, "JD_SHIPMENT_OUTBOUND_UNIT_CONFIG_INVALID",
                    base + ".planQuantity", quantitySource, "provider SKU unit conversion",
                    "SKU " + skuId + " 的京东单位换算必须是正数");
        }
        if (conversion.status() == JdStockUnitConverter.OutboundFactorStatus.NON_INTEGER) {
            return new Failure(
                    422, "JD_SHIPMENT_OUTBOUND_UNIT_CONFIG_INVALID",
                    base + ".planQuantity", quantitySource, "provider SKU unit conversion",
                    "SKU " + skuId + " 的京东件数换算必须是正整数件数（当前 "
                            + conversion.factor().toPlainString() + "）");
        }
        BigDecimal factor = conversion.factor();
        BigDecimal exact = JdStockUnitConverter.exactPiecesOrNull(quantity, factor);
        if (exact == null) {
            return new Failure(
                    422, "JD_SHIPMENT_OUTBOUND_NON_INTEGRAL_QUANTITY",
                    base + ".planQuantity", quantitySource,
                    "shipment quantity or provider SKU unit conversion",
                    "数量与换算系数无法得到精确正整数件数（" + quantity + " × " + factor
                            + "）；系统不四舍五入也不向上取整");
        }
        try {
            return new Cargo(
                    orderLine, goodsName, unit, goods.goodsNo(), goods.merchantSkuCode(),
                    skuId, exact.intValueExact());
        } catch (ArithmeticException exception) {
            return new Failure(
                    422, "JD_SHIPMENT_OUTBOUND_QUANTITY_OUT_OF_RANGE",
                    base + ".planQuantity", quantitySource, "shipment quantity",
                    "换算后件数超出京东 planQuantity 整数范围");
        }
    }
}
