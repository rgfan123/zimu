package cn.zimu.fulfillment.order;

import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.order.dto.BundleComponentInput;
import cn.zimu.fulfillment.sku.SourceChannelSku;
import cn.zimu.fulfillment.sku.SourceChannelSkuRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 来源礼包解析接缝：文件导入、API 拉单（结构化）、人工 {@code resolve-bundle} 三条路径
 * <b>唯一</b>的 {@code app.source_channel_bundles} 查法。
 *
 * <p><b>为什么要有这个类</b>：改造前同一个字段 {@code source_bundle_ref} 被三条路径用三种值去查——
 * 文件导入用 {@code sourceSkuRef}（聚福宝＝商品ID）、人工补救用
 * {@code COALESCE(sku_code_snapshot, raw_cells->>'主商品编码', product_name_snapshot)}（聚福宝实际落到商品名）、
 * 拉单根本不查。结果是运营配一次礼包映射，只有其中一条路能命中：生产 JUFUBAO 映射 id=70
 * 存的是名称，文件导入按商品ID查不到，每来一单都要人工点一次 {@code resolve-bundle}
 * （取证：{@code docs/research/jufubao-catalog-onboarding-2026-08-28.md} §2.2–§2.4）。
 *
 * <p><b>统一后的键</b>：主键与 SKU 映射同源，就是 {@code sourceSkuRef}（渠道商品ID）；
 * 商品名称作为<b>显式的第二把键</b>兜底，理由见 {@link #candidateKeys}。
 *
 * <p><b>统一后的判定顺序</b>（{@link #decide}）：礼包映射 → SKU 映射 → 名字启发式。
 * SKU 映射排在名字启发式之前，是为了堵住「名字带礼包/礼盒/组合的<i>单品</i>被文件链路劫持、
 * 绕过已配好的 SKU 映射」这个缺陷——同一个商品在两条链路上必须给出同一个结果。
 *
 * <p><b>渠道白名单已废除</b>（承接 2026-08-28 的判断）：礼包解析此前只对大者/万旗/万齐开放，
 * 因为只有它们的导出表被观察到含礼包行；但聚福宝与企微同日各来了一张「子牧牛肉惠选礼包1400g」，
 * 被判成普通 SKU 行后卡在 SKU_MAPPING_REQUIRED，而 {@code resolve-bundle} 只受理 CUSTOM_BUNDLE 行，
 * 白名单本身成了死路。所以这里对所有渠道一视同仁，不再有 {@code bundleSourceChannel} 这种开关。
 *
 * <p><b>名字启发式仍然有代价，但代价已被 SKU 映射这一档挡住大半</b>：名字含礼包/礼盒/组合、
 * 既无礼包映射也无 SKU 映射的行，会落成待解析礼包行，只能走 {@code resolve-bundle}（走不了
 * {@code resolve-sku}，后者只受理 SINGLE 行）。改造前这个前提靠「生产里没有这类活跃 SKU 映射」
 * 这条数据事实兜着，现在由代码顺序保证：先给它配上 SKU 映射，它就永远不会再被名字劫持。
 */
@Service
public class SourceBundleResolver {

    /** 名称启发式词表：只在礼包映射与 SKU 映射都没命中时才轮到它，是三档判定里最弱的一档。 */
    private static final List<String> BUNDLE_NAME_HINTS = List.of("礼包", "礼盒", "组合");

    private final JdbcTemplate jdbc;
    private final SourceChannelSkuRepository sourceChannelSkuRepository;

    public SourceBundleResolver(JdbcTemplate jdbc, SourceChannelSkuRepository sourceChannelSkuRepository) {
        this.jdbc = jdbc;
        this.sourceChannelSkuRepository = sourceChannelSkuRepository;
    }

    /** 一条来源行的礼包判定结果。 */
    public enum Kind {
        /** 命中权威礼包映射，可直接展开成组件行。 */
        STATIC_BUNDLE,
        /** 名字明确是礼包但没有映射：造一条必然未映射的礼包行，走人工 {@code resolve-bundle}。 */
        UNRESOLVED_BUNDLE,
        /** 普通单品行，交给 SKU 映射。 */
        SINGLE
    }

    /**
     * @param bundleId 仅 {@link Kind#STATIC_BUNDLE} 有值
     * @param componentGroups 按履约方稳定分组的组件清单；一组＝一条订单行（同履约方门禁的既有语义）
     */
    public record Decision(Kind kind, Long bundleId, List<List<BundleComponentInput>> componentGroups) {

        private static final Decision SINGLE_DECISION = new Decision(Kind.SINGLE, null, List.of());
        private static final Decision UNRESOLVED_DECISION = new Decision(Kind.UNRESOLVED_BUNDLE, null, List.of());

        static Decision single() {
            return SINGLE_DECISION;
        }

        static Decision unresolvedBundle() {
            return UNRESOLVED_DECISION;
        }
    }

    /**
     * 三条路径共用的判定。文件导入与 API 拉单都调它，因此同一个商品在两条链路上必然同结果。
     *
     * @param sourceSkuRef 与 SKU 映射同源的来源商品标识（聚福宝＝商品ID；大者 v2 导出无编码列时就是商品名）
     * @param productName 来源商品名称，既是第二把礼包键，也是名字启发式的输入
     */
    public Decision decide(SourceChannel channel, String sourceSkuRef, String productName) {
        if (channel == null) {
            return Decision.single();
        }
        // 数量不参与身份判定：命中礼包映射就返回 STATIC_BUNDLE。OrderCreateService 统一校验
        // 礼包数量必须为整数，绝不能在这里因数量形状降级成 SINGLE 后沿 SKU 映射静默放行。
        Long bundleId = activeBundleId(channel, sourceSkuRef, productName);
        if (bundleId != null) {
            return new Decision(Kind.STATIC_BUNDLE, bundleId, componentGroups(bundleId));
        }
        // SKU 映射优先于名字猜测：已经配了活跃 SKU 映射的商品，无论名字里有没有「组合」，
        // 都必须当单品走 SKU 映射，否则文件链路会把它劫持成待解析礼包行、而拉单链路正常映射，
        // 同一个商品两条链路结果不一致。
        if (activeSkuMapping(channel, sourceSkuRef) != null) {
            return Decision.single();
        }
        if (looksLikeBundle(productName)) {
            return Decision.unresolvedBundle();
        }
        return Decision.single();
    }

    /**
     * 自动展开用：映射必须启用、乘数 1，且指向的礼包档案 ACTIVE——自动链路不许把未发布的礼包展开。
     *
     * @return 命中的礼包档案 id，未命中返回 null
     */
    public Long activeBundleId(SourceChannel channel, String sourceSkuRef, String productName) {
        for (String key : candidateKeys(sourceSkuRef, productName)) {
            List<Long> matches = jdbc.query(
                    """
                    SELECT scb.bundle_id
                    FROM app.source_channel_bundles scb
                    JOIN app.product_bundles pb ON pb.id=scb.bundle_id AND pb.status='ACTIVE'
                    WHERE scb.source_channel=? AND scb.source_bundle_ref=?
                      AND scb.active AND scb.quantity_multiplier=1
                    """,
                    (resultSet, rowNum) -> resultSet.getLong("bundle_id"),
                    channel.name(),
                    key);
            if (!matches.isEmpty()) {
                return matches.getFirst();
            }
        }
        return null;
    }

    /**
     * 人工 {@code resolve-bundle} 的主数据一致性门禁用：只看映射本身（启用、乘数 1），
     * 不筛礼包档案状态——档案未启用/BOM 为空要报 {@code BUNDLE_BOM_EMPTY} 这个更准的错，
     * 而不是含糊地说「映射不存在」。这是与 {@link #activeBundleId} 唯一的、刻意的差别；
     * <b>取键的逻辑两者共用</b>（{@link #candidateKeys}），所以三条路径的键仍然是同一套。
     *
     * @return 该来源键当前映射到的礼包档案 id 列表（正常是 0 或 1 条）
     */
    public List<Long> mappedBundleIds(SourceChannel channel, String sourceSkuRef, String productName) {
        for (String key : candidateKeys(sourceSkuRef, productName)) {
            List<Long> matches = jdbc.query(
                    """
                    SELECT scb.bundle_id FROM app.source_channel_bundles scb
                    WHERE scb.source_channel = ? AND scb.source_bundle_ref = ?
                      AND scb.active AND scb.quantity_multiplier = 1
                    """,
                    (rs, n) -> rs.getLong(1),
                    channel.name(),
                    key);
            if (!matches.isEmpty()) {
                return matches;
            }
        }
        return List.of();
    }

    /**
     * 查找键的<b>唯一</b>定义：先 {@code sourceSkuRef}（与 SKU 映射同源的商品ID），再商品名称。
     *
     * <p><b>为什么保留名称这把第二键，而不是写迁移把名称键规范化成 ID 键</b>：
     * 2026-08-29 只读核对生产 {@code app.source_channel_bundles} 共 39 条，两种键并存且都在用——
     * DAZHE bundle 1 同时挂 {@code P26011900044}（id=3）与「子牧原切羊肉礼包6300g（BJ）」（id=19），
     * bundle 2（id=4/20）、bundle 21（id=38/26）、JUFUBAO bundle 33（id=71/70）同理；
     * 而 32 条 DAZHE 名称键<b>根本没有对应的商品ID可迁</b>（大者 v2 导出表没有编码列，
     * {@code SourceFileParser} 把商品名同时当 sourceSkuRef 和 productName，名称就是它的天然键）。
     * 写迁移把名称键改掉，等于让这 32 条运营已配好的礼包当场失配——「不允许任何一条存量映射静默失配」
     * 这条硬约束下，显式双键查找是唯一安全的选择。
     *
     * <p><b>为什么 ID 键必须排在前面</b>：万一同一行的两把键指向不同礼包（存量里没有，但结构上允许），
     * 有序查找让结果是确定的，且优先采信与 SKU 映射同源的那把键；先命中即返回，不做跨键合并，
     * 因此不会凭空造出「一个来源行映射到两个礼包」的冲突。
     */
    private List<String> candidateKeys(String sourceSkuRef, String productName) {
        List<String> keys = new ArrayList<>(2);
        if (sourceSkuRef != null && !sourceSkuRef.isBlank()) {
            keys.add(sourceSkuRef);
        }
        if (productName != null && !productName.isBlank() && !keys.contains(productName)) {
            keys.add(productName);
        }
        return keys;
    }

    /** 礼包组件按履约方稳定分组；组件身份用内部 sku_code 快照（EMG 是京东履约编码，不能冒充来源渠道 SKU）。 */
    public List<List<BundleComponentInput>> componentGroups(long bundleId) {
        List<StaticBundleComponent> components = jdbc.query(
                """
                SELECT s.fulfillment_provider_id, s.sku_code, p.product_name, s.specification, s.unit,
                       bi.quantity_per_bundle
                FROM app.bundle_items bi
                JOIN app.skus s ON s.id=bi.sku_id
                JOIN app.products p ON p.id=s.product_id
                WHERE bi.bundle_id=?
                ORDER BY bi.sort_no
                """,
                (resultSet, rowNum) -> new StaticBundleComponent(
                        resultSet.getLong("fulfillment_provider_id"),
                        new BundleComponentInput(
                                resultSet.getString("sku_code"),
                                null,
                                resultSet.getString("product_name"),
                                resultSet.getString("specification"),
                                resultSet.getString("unit"),
                                resultSet.getBigDecimal("quantity_per_bundle").toPlainString())),
                bundleId);
        Map<Long, List<BundleComponentInput>> byProvider = new LinkedHashMap<>();
        for (StaticBundleComponent component : components) {
            byProvider.computeIfAbsent(component.providerId(), ignored -> new ArrayList<>()).add(component.input());
        }
        return byProvider.values().stream().map(List::copyOf).toList();
    }

    private record StaticBundleComponent(long providerId, BundleComponentInput input) {}

    /**
     * 「活跃来源 SKU 映射」的唯一定义：启用、乘数非空且为正。
     *
     * <p>{@code OrderCreateService#findMapping} 与本类的判定顺序都走这里，避免两处口径漂移——
     * 判定说「有 SKU 映射所以是单品」，建单却说「找不到映射」，那种不一致比缺陷本身更难查。
     */
    public SourceChannelSku activeSkuMapping(SourceChannel channel, String sourceSkuRef) {
        if (channel == null || sourceSkuRef == null || sourceSkuRef.isBlank()) {
            return null;
        }
        return sourceChannelSkuRepository
                .findBySourceChannelAndSourceSkuRef(channel, sourceSkuRef)
                .filter(SourceChannelSku::isActive)
                .filter(mapping -> mapping.getQuantityMultiplier() != null)
                .filter(mapping -> mapping.getQuantityMultiplier().signum() > 0)
                .orElse(null);
    }

    /** 名字启发式：只看商品名里有没有礼包/礼盒/组合，是最后一档兜底。 */
    public boolean looksLikeBundle(String productName) {
        return productName != null && BUNDLE_NAME_HINTS.stream().anyMatch(productName::contains);
    }
}
