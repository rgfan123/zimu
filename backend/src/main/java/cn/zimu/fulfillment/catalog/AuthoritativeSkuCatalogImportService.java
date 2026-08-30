package cn.zimu.fulfillment.catalog;

import cn.zimu.fulfillment.catalog.AuthoritativeSkuCatalogManifest.Item;
import cn.zimu.fulfillment.catalog.AuthoritativeSkuCatalogManifestLoader.LoadedManifest;
import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.domain.DataScope;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.idempotency.IdempotencyService;
import cn.zimu.fulfillment.common.idempotency.IdempotentResult;
import cn.zimu.fulfillment.common.web.CommandContext;
import cn.zimu.fulfillment.product.Category;
import cn.zimu.fulfillment.product.CategoryRepository;
import cn.zimu.fulfillment.product.Product;
import cn.zimu.fulfillment.product.ProductRepository;
import cn.zimu.fulfillment.sku.FulfillmentProvider;
import cn.zimu.fulfillment.sku.FulfillmentProviderRepository;
import cn.zimu.fulfillment.sku.ProviderSku;
import cn.zimu.fulfillment.sku.ProviderSkuRepository;
import cn.zimu.fulfillment.sku.Sku;
import cn.zimu.fulfillment.sku.SkuRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 将冻结的 61 个京东 goodsNo 原子、幂等地导入商品主数据。 */
@Service
public class AuthoritativeSkuCatalogImportService {

    private static final String IDEMPOTENCY_SCOPE = "authoritative_jd_sku_catalog.import";
    private static final String CATEGORY_CODE = "CAT-JD-INITIAL";
    private static final String CATEGORY_NAME = "京东初版商品库";
    private static final List<CatalogCategory> BUSINESS_CATEGORIES = List.of(
            new CatalogCategory("CAT-BEEF", "牛肉"),
            new CatalogCategory("CAT-LAMB", "羊肉"),
            new CatalogCategory("CAT-PORK", "猪肉"),
            new CatalogCategory("CAT-POULTRY", "禽肉"),
            new CatalogCategory("CAT-OTHER-MEAT", "其他肉类"),
            new CatalogCategory("CAT-MIXED", "混合组合"),
            new CatalogCategory("CAT-EQUIPMENT-MATERIAL", "设备物料"),
            new CatalogCategory("CAT-UNCLASSIFIED", "待分类"));
    private static final String PROVIDER_CODE = "JD";
    private static final String UNIT = "件";
    private static final Pattern EXPLICIT_MEASURE =
            Pattern.compile("(?i)(\\d+(?:\\.\\d+)?)\\s*(kg|g|ml|l)(?![a-z])");

    private final AuthoritativeSkuCatalogManifestLoader manifestLoader;
    private final IdempotencyService idempotency;
    private final AuditLogService audit;
    private final CatalogMasterDataLock catalogLock;
    private final CategoryRepository categories;
    private final ProductRepository products;
    private final FulfillmentProviderRepository providers;
    private final SkuRepository skus;
    private final ProviderSkuRepository providerSkus;

    public AuthoritativeSkuCatalogImportService(
            AuthoritativeSkuCatalogManifestLoader manifestLoader,
            IdempotencyService idempotency,
            AuditLogService audit,
            CatalogMasterDataLock catalogLock,
            CategoryRepository categories,
            ProductRepository products,
            FulfillmentProviderRepository providers,
            SkuRepository skus,
            ProviderSkuRepository providerSkus) {
        this.manifestLoader = manifestLoader;
        this.idempotency = idempotency;
        this.audit = audit;
        this.catalogLock = catalogLock;
        this.categories = categories;
        this.products = products;
        this.providers = providers;
        this.skus = skus;
        this.providerSkus = providerSkus;
    }

    @Transactional
    public IdempotentResult<AuthoritativeSkuCatalogImportReport> importCatalog(
            String idempotencyKey, CommandContext context) {
        LoadedManifest loaded = manifestLoader.load();
        ImportRequest request = new ImportRequest(
                loaded.contentSha256(),
                loaded.manifest().jdSource().sha256(),
                loaded.manifest().priceSource().sha256());
        return idempotency.execute(IDEMPOTENCY_SCOPE, idempotencyKey, request, 200, () -> {
            catalogLock.lockForAuthoritativeImport();
            ImportPlan plan = preflight(loaded);
            AuthoritativeSkuCatalogImportReport report = apply(plan, loaded);
            audit.record(new AuditLogService.AuditCommand()
                    .dataScope(DataScope.BUSINESS)
                    .requestId(context.requestId())
                    .traceId(context.traceId())
                    .operator(context.operator())
                    .actorType(AuditActorType.HUMAN)
                    .service("AuthoritativeSkuCatalogImportService")
                    .operation(IDEMPOTENCY_SCOPE)
                    .requestPayload(request)
                    .responsePayload(auditSummary(report))
                    .httpStatus(200)
                    .businessCode("SUCCESS"));
            return report;
        });
    }

    private ImportPlan preflight(LoadedManifest loaded) {
        AuthoritativeSkuCatalogManifest manifest = loaded.manifest();
        Category legacyCategory = categories.findByCategoryCode(CATEGORY_CODE).orElse(null);
        List<Drift> drift = new ArrayList<>();
        Map<String, Category> businessCategories = new LinkedHashMap<>();
        for (CatalogCategory definition : BUSINESS_CATEGORIES) {
            Category category = categories.findByCategoryCode(definition.code()).orElse(null);
            businessCategories.put(definition.code(), category);
            if (category != null) {
                compare(drift, definition.code(), "category.name", definition.name(), category.getCategoryName());
                compare(drift, definition.code(), "category.active", true, category.isActive());
                compare(drift, definition.code(), "category.parent_id", null, category.getParentId());
            }
        }
        FulfillmentProvider provider = providers.findByProviderCode(PROVIDER_CODE)
                .orElseThrow(() -> BusinessException.conflict(
                        "AUTHORITATIVE_CATALOG_PREREQUISITE_MISSING", "JD 履约方不存在，禁止导入"));
        if (!provider.isActive()) {
            drift.add(new Drift("JD", "fulfillment_provider.active", true, false));
        }
        if (legacyCategory != null) {
            compare(drift, CATEGORY_CODE, "category.name", CATEGORY_NAME, legacyCategory.getCategoryName());
            compare(drift, CATEGORY_CODE, "category.active", true, legacyCategory.isActive());
            compare(drift, CATEGORY_CODE, "category.parent_id", null, legacyCategory.getParentId());
        }
        Set<String> authoritativeCodes = manifest.items().stream()
                .map(Item::jdCode)
                .collect(Collectors.toSet());
        providerSkus.findByFulfillmentProviderIdOrderByProviderSkuCodeAsc(provider.getId()).stream()
                .filter(mapping -> mapping.getProviderSkuCode().matches("EMG\\d+"))
                .filter(mapping -> !authoritativeCodes.contains(mapping.getProviderSkuCode()))
                .forEach(mapping -> drift.add(new Drift(
                        mapping.getProviderSkuCode(),
                        "provider_sku.authoritative_membership",
                        "present in authoritative manifest",
                        "legacy mapping outside manifest")));

        List<ItemPlan> itemPlans = new ArrayList<>(manifest.items().size());
        for (Item item : manifest.items()) {
            itemPlans.add(preflightItem(item, businessCategories, legacyCategory, provider, loaded, drift));
        }
        if (!drift.isEmpty()) {
            LinkedHashMap<String, Object> details = new LinkedHashMap<>();
            details.put("manifest_sha256", loaded.contentSha256());
            details.put("conflict_count", drift.size());
            details.put("conflicts", drift);
            throw new BusinessException(
                    409,
                    "AUTHORITATIVE_CATALOG_DRIFT",
                    "现有商品主数据与权威京东目录冲突，整批未写入",
                    List.of(),
                    details);
        }
        return new ImportPlan(businessCategories, provider, itemPlans);
    }

    private ItemPlan preflightItem(
            Item item,
            Map<String, Category> businessCategories,
            Category legacyCategory,
            FulfillmentProvider provider,
            LoadedManifest loaded,
            List<Drift> drift) {
        String productCode = productCode(item);
        String specification = specification(item);
        CatalogCategory categoryDefinition = categoryFor(item);
        Category category = businessCategories.get(categoryDefinition.code());
        Product product = products.findByProductCode(productCode).orElse(null);
        boolean updateProductCategory = false;
        if (product != null) {
            compare(drift, item.jdCode(), "product.name", item.canonicalName(), product.getProductName());
            compare(drift, item.jdCode(), "product.active", true, product.isActive());
            boolean stillInLegacyCategory = legacyCategory != null
                    && Objects.equals(legacyCategory.getId(), product.getCategoryId());
            if (stillInLegacyCategory) {
                updateProductCategory = true;
            } else if (category == null || !Objects.equals(category.getId(), product.getCategoryId())) {
                compare(drift, item.jdCode(), "product.category_code", categoryDefinition.code(),
                        product.getCategoryId());
            }
        }

        ProviderSku mapping = providerSkus
                .findByFulfillmentProviderIdAndProviderSkuCode(provider.getId(), item.jdCode())
                .orElse(null);
        Sku sku = null;
        if (mapping != null) {
            sku = skus.findById(mapping.getSkuId()).orElse(null);
            compare(drift, item.jdCode(), "provider_sku.active", true, mapping.isActive());
            if (sku == null) {
                drift.add(new Drift(item.jdCode(), "provider_sku.sku_id", "existing SKU", mapping.getSkuId()));
            }
        } else if (product != null) {
            sku = skus.findByProductIdAndFulfillmentProviderIdAndSpecificationAndUnit(
                            product.getId(), provider.getId(), specification, UNIT)
                    .orElse(null);
            if (sku != null) {
                providerSkus.findByFulfillmentProviderIdAndSkuId(provider.getId(), sku.getId())
                        .ifPresent(existing -> drift.add(new Drift(
                                item.jdCode(),
                                "provider_sku.provider_sku_code_for_sku",
                                item.jdCode(),
                                existing.getProviderSkuCode())));
            }
        }

        boolean updateSpecification = false;
        if (sku != null) {
            compare(
                    drift,
                    item.jdCode(),
                    "sku.product_id",
                    product == null ? null : product.getId(),
                    sku.getProductId());
            compare(drift, item.jdCode(), "sku.provider_id", provider.getId(), sku.getFulfillmentProviderId());
            if (!Objects.equals(specification, sku.getSpecification())) {
                if (isLegacySpecification(item, sku.getSpecification())) {
                    updateSpecification = true;
                } else {
                    compare(drift, item.jdCode(), "sku.specification", specification, sku.getSpecification());
                }
            }
            compare(drift, item.jdCode(), "sku.unit", UNIT, sku.getUnit());
            compare(drift, item.jdCode(), "sku.active", true, sku.isActive());
        } else if (mapping != null) {
            drift.add(new Drift(item.jdCode(), "provider_sku.sku", "valid SKU", null));
        }

        Map<String, Object> mergedMetadata = mapping == null
                ? metadata(item, loaded, Map.of(), drift)
                : metadata(item, loaded, mapping.getExternalCodes(), drift);
        boolean updateMapping = mapping != null && !Objects.equals(mergedMetadata, mapping.getExternalCodes());
        return new ItemPlan(
                item,
                product,
                sku,
                mapping,
                categoryDefinition.code(),
                updateProductCategory,
                updateSpecification,
                mergedMetadata,
                updateMapping);
    }

    private AuthoritativeSkuCatalogImportReport apply(ImportPlan plan, LoadedManifest loaded) {
        Map<String, Category> materializedCategories = new LinkedHashMap<>(plan.categories());
        for (CatalogCategory definition : BUSINESS_CATEGORIES) {
            if (materializedCategories.get(definition.code()) != null) continue;
            Category category = new Category();
            category.setCategoryCode(definition.code());
            category.setCategoryName(definition.name());
            materializedCategories.put(definition.code(), categories.saveAndFlush(category));
        }

        Counters counters = new Counters();
        for (ItemPlan itemPlan : plan.items()) {
            Category category = materializedCategories.get(itemPlan.categoryCode());
            Product product = itemPlan.product();
            if (product == null) {
                product = new Product();
                product.setProductCode(productCode(itemPlan.item()));
                product.setProductName(itemPlan.item().canonicalName());
                product.setCategoryId(category.getId());
                product.setDescription("来自《京东商品编号.xlsx》Sheet1；外部唯一键为 JD 编码");
                product = products.saveAndFlush(product);
                counters.createdProducts++;
            } else {
                counters.reusedProducts++;
                if (itemPlan.updateProductCategory()) {
                    product.setCategoryId(category.getId());
                    products.save(product);
                }
            }

            Sku sku = itemPlan.sku();
            if (sku == null) {
                sku = new Sku();
                sku.setProductId(product.getId());
                sku.setFulfillmentProviderId(plan.provider().getId());
                sku.setSpecification(specification(itemPlan.item()));
                sku.setUnit(UNIT);
                sku = skus.saveAndFlush(sku);
                counters.createdSkus++;
            } else {
                counters.reusedSkus++;
                if (itemPlan.updateSpecification()) {
                    sku.setSpecification(specification(itemPlan.item()));
                    skus.save(sku);
                    counters.updatedSkus++;
                }
            }

            ProviderSku mapping = itemPlan.mapping();
            if (mapping == null) {
                mapping = new ProviderSku();
                mapping.setFulfillmentProviderId(plan.provider().getId());
                mapping.setSkuId(sku.getId());
                mapping.setProviderSkuCode(itemPlan.item().jdCode());
                mapping.setExternalCodes(itemPlan.mergedMetadata());
                providerSkus.save(mapping);
                counters.createdProviderSkus++;
            } else {
                counters.reusedProviderSkus++;
                if (itemPlan.updateMapping()) {
                    mapping.setExternalCodes(itemPlan.mergedMetadata());
                    providerSkus.save(mapping);
                    counters.updatedProviderSkus++;
                }
            }
        }
        skus.flush();
        providerSkus.flush();
        return report(loaded, counters);
    }

    private AuthoritativeSkuCatalogImportReport report(LoadedManifest loaded, Counters counters) {
        AuthoritativeSkuCatalogManifest manifest = loaded.manifest();
        List<AuthoritativeSkuCatalogImportReport.DuplicateCode> duplicates = manifest.items().stream()
                .filter(item -> item.sourceRows().size() > 1)
                .map(item -> new AuthoritativeSkuCatalogImportReport.DuplicateCode(
                        item.jdCode(),
                        sourceRows(item),
                        item.sourceRows().stream().map(row -> row.jdName()).toList()))
                .toList();
        List<AuthoritativeSkuCatalogImportReport.UnpricedItem> unpriced = manifest.items().stream()
                .filter(item -> item.purchasePrice() == null)
                .map(item -> new AuthoritativeSkuCatalogImportReport.UnpricedItem(
                        item.jdCode(), item.canonicalName(), sourceRows(item)))
                .toList();
        List<AuthoritativeSkuCatalogImportReport.PricedItem> priced = manifest.items().stream()
                .filter(item -> item.purchasePrice() != null)
                .map(item -> new AuthoritativeSkuCatalogImportReport.PricedItem(
                        item.jdCode(),
                        item.canonicalName(),
                        sourceRows(item),
                        item.priceMatchName(),
                        item.priceSourceRow()))
                .toList();
        List<AuthoritativeSkuCatalogImportReport.MappingDifference> mappingDifferences = manifest.items().stream()
                .filter(item -> !item.mappingDifferenceCodes().isEmpty())
                .map(item -> new AuthoritativeSkuCatalogImportReport.MappingDifference(
                        item.jdCode(), item.canonicalName(), sourceRows(item), item.mappingDifferenceCodes()))
                .toList();
        List<AuthoritativeSkuCatalogImportReport.ExcludedSheet> excluded = manifest.excludedSheets().stream()
                .map(sheet -> new AuthoritativeSkuCatalogImportReport.ExcludedSheet(
                        sheet.sheetName(), sheet.nonemptyRows(), sheet.reason()))
                .toList();
        return new AuthoritativeSkuCatalogImportReport(
                loaded.contentSha256(),
                manifest.jdSource().sha256(),
                manifest.priceSource().sha256(),
                manifest.jdSource().dataRows(),
                manifest.expected().uniqueJdCodes(),
                manifest.expected().duplicateCodeCount(),
                manifest.expected().priceMatchedCount(),
                manifest.expected().unpricedCount(),
                counters.createdProducts,
                counters.reusedProducts,
                counters.createdSkus,
                counters.reusedSkus,
                counters.updatedSkus,
                counters.createdProviderSkus,
                counters.reusedProviderSkus,
                counters.updatedProviderSkus,
                duplicates,
                priced,
                unpriced,
                mappingDifferences,
                excluded);
    }

    private static Map<String, Object> auditSummary(AuthoritativeSkuCatalogImportReport report) {
        LinkedHashMap<String, Object> summary = new LinkedHashMap<>();
        summary.put("manifest_sha256", report.manifestSha256());
        summary.put("jd_source_sha256", report.jdSourceSha256());
        summary.put("price_source_sha256", report.priceSourceSha256());
        summary.put("catalog_row_count", report.catalogRowCount());
        summary.put("unique_jd_code_count", report.uniqueJdCodeCount());
        summary.put("duplicate_code_count", report.duplicateCodeCount());
        summary.put("price_matched_count", report.priceMatchedCount());
        summary.put("unpriced_count", report.unpricedCount());
        summary.put("created_products", report.createdProducts());
        summary.put("reused_products", report.reusedProducts());
        summary.put("created_skus", report.createdSkus());
        summary.put("reused_skus", report.reusedSkus());
        summary.put("updated_skus", report.updatedSkus());
        summary.put("created_provider_skus", report.createdProviderSkus());
        summary.put("reused_provider_skus", report.reusedProviderSkus());
        summary.put("updated_provider_skus", report.updatedProviderSkus());
        return summary;
    }

    private Map<String, Object> metadata(
            Item item, LoadedManifest loaded, Map<String, Object> existing, List<Drift> drift) {
        LinkedHashMap<String, Object> result = new LinkedHashMap<>(existing == null ? Map.of() : existing);
        requireMetadataValue(drift, item.jdCode(), result, "provider_sku_name", item.canonicalName());
        requireMetadataValue(
                drift, item.jdCode(), result, "catalog_source", loaded.manifest().jdSource().fileName());
        requireMetadataValue(
                drift, item.jdCode(), result, "catalog_source_sha256", loaded.manifest().jdSource().sha256());
        requireMetadataValue(
                drift, item.jdCode(), result, "price_source_sha256", loaded.manifest().priceSource().sha256());
        requireMetadataValue(drift, item.jdCode(), result, "catalog_manifest_sha256", loaded.contentSha256());
        requireMetadataValue(drift, item.jdCode(), result, "source_rows", sourceRows(item));
        requireMetadataValue(
                drift, item.jdCode(), result, "mapping_difference_codes", item.mappingDifferenceCodes());

        LinkedHashSet<String> aliases = new LinkedHashSet<>();
        Object existingAliases = result.get("aliases");
        if (existingAliases != null) {
            if (existingAliases instanceof List<?> values
                    && values.stream().allMatch(String.class::isInstance)) {
                values.forEach(value -> aliases.add((String) value));
            } else {
                drift.add(new Drift(item.jdCode(), "provider_sku.external_codes.aliases", "string array", existingAliases));
            }
        }
        aliases.addAll(item.aliases());
        result.put("aliases", List.copyOf(aliases));
        result.put("provider_sku_name", item.canonicalName());
        result.put("catalog_source", loaded.manifest().jdSource().fileName());
        result.put("catalog_source_sha256", loaded.manifest().jdSource().sha256());
        result.put("price_source_sha256", loaded.manifest().priceSource().sha256());
        result.put("catalog_manifest_sha256", loaded.contentSha256());
        result.put("source_rows", sourceRows(item));
        result.put("mapping_difference_codes", item.mappingDifferenceCodes());
        return result;
    }

    private static void requireMetadataValue(
            List<Drift> drift,
            String jdCode,
            Map<String, Object> existing,
            String field,
            Object expected) {
        if (existing.containsKey(field) && !Objects.equals(expected, existing.get(field))) {
            drift.add(new Drift(
                    jdCode, "provider_sku.external_codes." + field, expected, existing.get(field)));
        }
    }

    private static void compare(
            List<Drift> drift, String jdCode, String field, Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) {
            drift.add(new Drift(jdCode, field, expected, actual));
        }
    }

    private static String productCode(Item item) {
        return "PROD-JD-" + item.jdCode();
    }

    private static CatalogCategory categoryFor(Item item) {
        if (Set.of("A5", "板健").contains(item.canonicalName())) {
            return category("CAT-BEEF");
        }
        if ("黄金六两120g".equals(item.canonicalName())) {
            return category("CAT-PORK");
        }
        String evidence = classificationEvidence(item);
        if (evidence.contains("海报") || evidence.contains("烤炉")) {
            return category("CAT-EQUIPMENT-MATERIAL");
        }
        if (evidence.contains("牛羊") || evidence.contains("拼盘")) {
            return category("CAT-MIXED");
        }
        if (evidence.contains("羊")) return category("CAT-LAMB");
        if (evidence.contains("牛")
                || evidence.contains("眼肉")
                || evidence.contains("西冷")
                || evidence.contains("横膈膜")
                || evidence.contains("肩胛烤")) {
            return category("CAT-BEEF");
        }
        if (evidence.contains("五花肉")) return category("CAT-PORK");
        if (evidence.contains("鸡")) return category("CAT-POULTRY");
        if (evidence.contains("鸵鸟")) return category("CAT-OTHER-MEAT");
        return category("CAT-UNCLASSIFIED");
    }

    private static String classificationEvidence(Item item) {
        StringBuilder evidence = new StringBuilder(item.canonicalName());
        item.aliases().forEach(value -> evidence.append(' ').append(value));
        item.sourceRows().forEach(row -> evidence
                .append(' ').append(Objects.toString(row.caishixianName(), ""))
                .append(' ').append(Objects.toString(row.jufubaoName(), ""))
                .append(' ').append(Objects.toString(row.jdName(), "")));
        return evidence.toString();
    }

    private static CatalogCategory category(String code) {
        return BUSINESS_CATEGORIES.stream()
                .filter(candidate -> candidate.code().equals(code))
                .findFirst()
                .orElseThrow();
    }

    private static String specification(Item item) {
        List<String> evidence = new ArrayList<>();
        item.sourceRows().forEach(row -> {
            evidence.add(row.caishixianName());
            evidence.add(row.jufubaoName());
            evidence.add(row.jdName());
        });
        evidence.addAll(item.aliases());
        evidence.add(item.canonicalName());
        for (String text : evidence) {
            if (text == null || text.isBlank()) continue;
            Matcher matcher = EXPLICIT_MEASURE.matcher(text);
            if (matcher.find()) {
                String quantity = new BigDecimal(matcher.group(1)).stripTrailingZeros().toPlainString();
                return quantity + matcher.group(2).toLowerCase(java.util.Locale.ROOT);
            }
        }
        return "待维护";
    }

    private static boolean isLegacySpecification(Item item, String current) {
        return Objects.equals(current, "京东商品编号 " + item.jdCode());
    }

    private static List<Integer> sourceRows(Item item) {
        return item.sourceRows().stream().map(row -> row.row()).toList();
    }

    private record ImportRequest(String manifestSha256, String jdSourceSha256, String priceSourceSha256) {}

    private record ImportPlan(
            Map<String, Category> categories, FulfillmentProvider provider, List<ItemPlan> items) {}

    private record ItemPlan(
            Item item,
            Product product,
            Sku sku,
            ProviderSku mapping,
            String categoryCode,
            boolean updateProductCategory,
            boolean updateSpecification,
            Map<String, Object> mergedMetadata,
            boolean updateMapping) {}

    private record CatalogCategory(String code, String name) {}

    private record Drift(String jdCode, String field, Object expected, Object actual) {}

    private static final class Counters {
        private int createdProducts;
        private int reusedProducts;
        private int createdSkus;
        private int reusedSkus;
        private int updatedSkus;
        private int createdProviderSkus;
        private int reusedProviderSkus;
        private int updatedProviderSkus;
    }
}
