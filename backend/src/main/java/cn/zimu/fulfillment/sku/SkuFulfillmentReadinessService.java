package cn.zimu.fulfillment.sku;

import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.error.FieldErrorItem;
import cn.zimu.fulfillment.fulfillment.JdStockUnitConverter;
import cn.zimu.fulfillment.product.Product;
import cn.zimu.fulfillment.product.ProductRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Product/SKU、履约映射与显式数据质量证据的唯一 readiness 派生接缝。 */
@Service
public class SkuFulfillmentReadinessService {

    private static final Set<String> PLACEHOLDERS = Set.of("未知", "待维护", "待确认", "-");

    private final ProductRepository products;
    private final FulfillmentProviderRepository providers;
    private final ProviderSkuRepository providerSkus;
    private final SkuRepository skus;
    private final SkuDataQualityFlagRepository flags;

    public SkuFulfillmentReadinessService(
            ProductRepository products,
            FulfillmentProviderRepository providers,
            ProviderSkuRepository providerSkus,
            SkuRepository skus,
            SkuDataQualityFlagRepository flags) {
        this.products = products;
        this.providers = providers;
        this.providerSkus = providerSkus;
        this.skus = skus;
        this.flags = flags;
    }

    @Transactional(readOnly = true)
    public SkuFulfillmentReadiness evaluate(Sku sku) {
        return evaluateAll(List.of(sku)).get(sku.getId());
    }

    @Transactional(readOnly = true)
    public Map<Long, SkuFulfillmentReadiness> evaluateAll(Collection<Sku> values) {
        if (values.isEmpty()) return Map.of();
        Set<Long> skuIds = values.stream().map(Sku::getId).collect(Collectors.toSet());
        Map<Long, Product> productById = products.findAllById(
                        values.stream().map(Sku::getProductId).collect(Collectors.toSet()))
                .stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        Map<Long, FulfillmentProvider> providerById = providers.findAllById(
                        values.stream().map(Sku::getFulfillmentProviderId).collect(Collectors.toSet()))
                .stream()
                .collect(Collectors.toMap(FulfillmentProvider::getId, Function.identity()));
        Map<Long, ProviderSku> mappingBySkuId = providerSkus.findBySkuIdIn(skuIds).stream()
                .collect(Collectors.toMap(ProviderSku::getSkuId, Function.identity()));
        Map<Long, List<SkuDataQualityFlag>> flagsBySkuId = flags
                .findBySkuIdInAndActiveTrueOrderBySkuIdAscFlagCodeAsc(skuIds)
                .stream()
                .collect(Collectors.groupingBy(
                        SkuDataQualityFlag::getSkuId,
                        HashMap::new,
                        Collectors.toList()));
        Set<String> candidateBarcodes = values.stream()
                .map(Sku::getBarcode)
                .map(SkuFulfillmentReadinessService::normalizeBarcode)
                .filter(value -> value != null)
                .collect(Collectors.toSet());
        Set<String> duplicateBarcodes = candidateBarcodes.isEmpty()
                ? Set.of()
                : new HashSet<>(skus.findDuplicateActiveBarcodesIn(candidateBarcodes));

        Map<Long, SkuFulfillmentReadiness> result = new HashMap<>();
        for (Sku sku : values) {
            EnumSet<SkuReadinessReason> reasons = EnumSet.noneOf(SkuReadinessReason.class);
            Map<SkuReadinessReason, SkuDataQualityFlag> explicitIssueByReason =
                    new EnumMap<>(SkuReadinessReason.class);
            Product product = productById.get(sku.getProductId());
            FulfillmentProvider provider = providerById.get(sku.getFulfillmentProviderId());
            ProviderSku mapping = mappingBySkuId.get(sku.getId());

            if (product == null || !product.isActive()) reasons.add(SkuReadinessReason.PRODUCT_INACTIVE);
            if (!sku.isActive()) reasons.add(SkuReadinessReason.SKU_INACTIVE);
            if (provider == null || !provider.isActive()) reasons.add(SkuReadinessReason.PROVIDER_INACTIVE);
            if (missingIdentityText(sku.getSpecification()) || missingPackagingIdentity(sku)) {
                reasons.add(SkuReadinessReason.SPECIFICATION_REQUIRED);
            }
            if (missingIdentityText(sku.getUnit())) reasons.add(SkuReadinessReason.UNIT_REQUIRED);

            if (mapping == null) {
                reasons.add(SkuReadinessReason.PROVIDER_MAPPING_REQUIRED);
            } else if (!mapping.isActive()) {
                reasons.add(SkuReadinessReason.PROVIDER_MAPPING_INACTIVE);
            } else if (provider != null && provider.getProviderType() == ProviderType.JD_WAREHOUSE
                    && !JdStockUnitConverter.validateOutboundFactor(
                                    sku.getUnit(), mapping.getExternalCodes())
                            .valid()) {
                reasons.add(SkuReadinessReason.UNIT_CONVERSION_REQUIRED);
            }

            String barcode = normalizeBarcode(sku.getBarcode());
            if (barcode != null && duplicateBarcodes.contains(barcode)) {
                reasons.add(SkuReadinessReason.BARCODE_CONFLICT);
            }
            for (SkuDataQualityFlag flag : flagsBySkuId.getOrDefault(sku.getId(), List.of())) {
                if (flag.getBlockingReason() != null) {
                    SkuReadinessReason reason = SkuReadinessReason.parse(flag.getBlockingReason());
                    reasons.add(reason);
                    explicitIssueByReason.putIfAbsent(reason, flag);
                }
            }

            List<SkuFulfillmentReadiness.SkuReadinessIssue> issues = reasons.stream()
                    .map(reason -> {
                        SkuDataQualityFlag explicit = explicitIssueByReason.get(reason);
                        return new SkuFulfillmentReadiness.SkuReadinessIssue(
                                reason.name(),
                                explicit == null ? reason.message() : explicit.getMessage(),
                                explicit == null ? reason.action() : explicit.getAction());
                    })
                    .toList();
            result.put(sku.getId(), new SkuFulfillmentReadiness(issues));
        }
        return Map.copyOf(result);
    }

    public void validateActiveIdentity(boolean active, String specification, String unit) {
        if (!active) return;
        List<FieldErrorItem> fieldErrors = new ArrayList<>();
        if (missingIdentityText(specification)) {
            fieldErrors.add(new FieldErrorItem(
                    "specification", "Pattern", "启用的 SKU 必须维护真实规格，不能使用占位值"));
        }
        if (missingIdentityText(unit)) {
            fieldErrors.add(new FieldErrorItem(
                    "unit", "Pattern", "启用的 SKU 必须维护真实库存计数单位，不能使用占位值"));
        }
        if (!fieldErrors.isEmpty()) {
            throw new BusinessException(
                    400,
                    "INVALID_SKU_IDENTITY",
                    "启用的 SKU 身份无效",
                    fieldErrors,
                    Map.of());
        }
    }

    private static boolean missingIdentityText(String value) {
        return value == null || value.isBlank() || PLACEHOLDERS.contains(value.trim());
    }

    private static boolean missingPackagingIdentity(Sku sku) {
        return sku.getNetContentValue() == null
                || missingIdentityText(sku.getNetContentUnit())
                || sku.getPackageCount() == null
                || missingIdentityText(sku.getPackageUnit());
    }

    private static String normalizeBarcode(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
    }
}
