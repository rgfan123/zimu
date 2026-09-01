package cn.zimu.fulfillment.masterdata;

import cn.zimu.fulfillment.catalog.CatalogMasterDataLock;
import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.domain.DataScope;
import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.common.dto.MasterDataRecord;
import cn.zimu.fulfillment.common.dto.PageResponse;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.idempotency.IdempotencyService;
import cn.zimu.fulfillment.common.idempotency.IdempotentResult;
import cn.zimu.fulfillment.common.web.CommandContext;
import cn.zimu.fulfillment.common.web.WriteCommands;
import cn.zimu.fulfillment.product.BundleItem;
import cn.zimu.fulfillment.product.BundleItemInput;
import cn.zimu.fulfillment.product.BundleItemRepository;
import cn.zimu.fulfillment.product.BundlePatch;
import cn.zimu.fulfillment.product.BundleWrite;
import cn.zimu.fulfillment.product.CategoryRepository;
import cn.zimu.fulfillment.product.ProductBundle;
import cn.zimu.fulfillment.product.ProductBundleRepository;
import cn.zimu.fulfillment.product.ProductRepository;
import cn.zimu.fulfillment.product.SourceBundleMappingPatch;
import cn.zimu.fulfillment.product.SourceBundleMappingWrite;
import cn.zimu.fulfillment.product.SourceChannelBundle;
import cn.zimu.fulfillment.product.SourceChannelBundleRepository;
import cn.zimu.fulfillment.sku.SkuCommercialPrice;
import cn.zimu.fulfillment.sku.SkuRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 静态礼包与来源礼包映射主数据用例。 */
@Service
public class BundleMasterDataService {

    private static final int CREATED = 201;
    private static final int OK = 200;
    private static final Sort ID_ASC = Sort.by("id").ascending();

    private final IdempotencyService idempotency;
    private final AuditLogService audit;
    private final CatalogMasterDataLock catalogLock;
    private final ProductBundleRepository bundles;
    private final BundleItemRepository bundleItems;
    private final CategoryRepository categories;
    private final SkuRepository skus;
    private final ProductRepository products;
    private final SourceChannelBundleRepository sourceBundleMappings;
    private final EntityManager entityManager;

    public BundleMasterDataService(
            IdempotencyService idempotency,
            AuditLogService audit,
            CatalogMasterDataLock catalogLock,
            ProductBundleRepository bundles,
            BundleItemRepository bundleItems,
            CategoryRepository categories,
            SkuRepository skus,
            ProductRepository products,
            SourceChannelBundleRepository sourceBundleMappings,
            EntityManager entityManager) {
        this.idempotency = idempotency;
        this.audit = audit;
        this.catalogLock = catalogLock;
        this.bundles = bundles;
        this.bundleItems = bundleItems;
        this.categories = categories;
        this.skus = skus;
        this.products = products;
        this.sourceBundleMappings = sourceBundleMappings;
        this.entityManager = entityManager;
    }

    @Transactional(readOnly = true)
    public PageResponse<MasterDataRecord> productBundles(int page, int size, String query) {
        PageRequest pageRequest = PageRequest.of(page, size, ID_ASC);
        Page<ProductBundle> result = query == null || query.isBlank()
                ? bundles.findAll(pageRequest)
                : bundles.search("%" + query.strip() + "%", pageRequest);
        return PageResponse.of(result.stream().map(this::bundle).toList(), result);
    }

    @Transactional(readOnly = true)
    public MasterDataRecord productBundle(long id) {
        return bundle(requireBundle(id));
    }

    @Transactional
    public IdempotentResult<MasterDataRecord> createProductBundle(
            BundleWrite input, String key, CommandContext context) {
        return write("product_bundle.create", key, input, CREATED, context, () -> {
            catalogLock.lockForMasterDataWrite();
            if (bundles.existsByBundleCode(input.bundleCode())) {
                throw BusinessException.conflict("BUNDLE_CODE_EXISTS", "礼包编码已存在");
            }
            String barcode = blankToNull(input.barcode());
            if (barcode != null && bundles.existsByBarcode(barcode)) {
                throw BusinessException.conflict("BUNDLE_BARCODE_EXISTS", "礼包条码已存在");
            }
            List<BundleItem> parsedItems = parseItems(input.items());
            Long categoryId = input.categoryId() == null
                    ? null
                    : WriteCommands.parseIdentifier(input.categoryId());
            requireCategory(categoryId);

            ProductBundle bundle = new ProductBundle();
            bundle.setBundleCode(input.bundleCode());
            bundle.setBundleName(input.bundleName());
            bundle.setCategoryId(categoryId);
            bundle.setBarcode(barcode);
            bundle.setDescription(blankToNull(input.description()));
            bundle.setTaxRate(parseTaxRate(input.taxRate()));
            bundle.setSettlementCost(SkuCommercialPrice.parse(input.settlementCost(), "settlement_cost"));
            bundle.setStatus("DRAFT");
            ProductBundle saved = bundles.saveAndFlush(bundle);

            for (BundleItem item : parsedItems) {
                item.setBundleId(saved.getId());
                bundleItems.save(item);
            }
            bundleItems.flush();
            entityManager.refresh(saved);

            String requestedStatus = input.status() == null ? "DRAFT" : input.status();
            if (!"DRAFT".equals(requestedStatus)) {
                saved.setStatus(requestedStatus);
                saved = bundles.saveAndFlush(saved);
            }
            entityManager.refresh(saved);
            return bundle(saved);
        });
    }

    @Transactional
    public IdempotentResult<MasterDataRecord> patchProductBundle(
            long id, BundlePatch input, String key, CommandContext context) {
        requireAny(input.bundleName(), input.categoryId(), input.barcode(), input.description(),
                input.taxRate(), input.settlementCost(), input.status(), input.items());
        return write("product_bundle.update", key, Map.of("id", id, "body", input), OK, context, () -> {
            catalogLock.lockForMasterDataWrite();
            ProductBundle bundle = requireBundle(id);
            requireVersion(bundle.getLockVersion(), input.expectedVersion());

            if (input.bundleName() != null) {
                bundle.setBundleName(input.bundleName());
            }
            if (input.categoryId() != null) {
                long categoryId = WriteCommands.parseIdentifier(input.categoryId());
                requireCategory(categoryId);
                bundle.setCategoryId(categoryId);
            }
            if (input.barcode() != null) {
                String barcode = blankToNull(input.barcode());
                if (barcode != null && bundles.existsByBarcodeAndIdNot(barcode, id)) {
                    throw BusinessException.conflict("BUNDLE_BARCODE_EXISTS", "礼包条码已存在");
                }
                bundle.setBarcode(barcode);
            }
            if (input.description() != null) {
                bundle.setDescription(blankToNull(input.description()));
            }
            if (input.taxRate() != null) {
                bundle.setTaxRate(parseTaxRate(input.taxRate()));
            }
            if (input.settlementCost() != null) {
                bundle.setSettlementCost(SkuCommercialPrice.parse(input.settlementCost(), "settlement_cost"));
            }

            String targetStatus = input.status() == null ? bundle.getStatus() : input.status();
            if (input.items() != null) {
                List<BundleItem> replacement = parseItems(input.items());
                entityManager.lock(bundle, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
                entityManager.flush();
                if ("ACTIVE".equals(bundle.getStatus())) {
                    bundle.setStatus("INACTIVE");
                }
                bundles.saveAndFlush(bundle);
                bundleItems.deleteByBundleId(id);
                bundleItems.flush();
                for (BundleItem item : replacement) {
                    item.setBundleId(id);
                    bundleItems.save(item);
                }
                bundleItems.flush();
                entityManager.refresh(bundle);
            }

            bundle.setStatus(targetStatus);
            ProductBundle saved = bundles.saveAndFlush(bundle);
            entityManager.refresh(saved);
            return bundle(saved);
        });
    }

    @Transactional(readOnly = true)
    public PageResponse<MasterDataRecord> sourceBundleMappings(
            int page, int size, SourceChannel sourceChannel) {
        PageRequest pageRequest = PageRequest.of(page, size, ID_ASC);
        Page<SourceChannelBundle> result = sourceChannel == null
                ? sourceBundleMappings.findAll(pageRequest)
                : sourceBundleMappings.findBySourceChannel(sourceChannel, pageRequest);
        return PageResponse.of(result.stream().map(this::sourceBundleMapping).toList(), result);
    }

    @Transactional(readOnly = true)
    public MasterDataRecord sourceBundleMapping(long id) {
        return sourceBundleMapping(sourceBundleMappings.findById(id)
                .orElseThrow(() -> BusinessException.notFound("来源礼包映射不存在")));
    }

    @Transactional
    public IdempotentResult<MasterDataRecord> createSourceBundleMapping(
            SourceBundleMappingWrite input, String key, CommandContext context) {
        return write("source_bundle_mapping.create", key, input, CREATED, context, () -> {
            if (sourceBundleMappings.existsBySourceChannelAndSourceBundleRef(
                    input.sourceChannel(), input.sourceBundleRef())) {
                throw BusinessException.conflict("SOURCE_BUNDLE_MAPPING_EXISTS", "来源礼包映射已存在");
            }
            String sourceBarcode = blankToNull(input.sourceBarcode());
            if (sourceBarcode != null && sourceBundleMappings.existsBySourceChannelAndSourceBarcode(
                    input.sourceChannel(), sourceBarcode)) {
                throw BusinessException.conflict("SOURCE_BUNDLE_BARCODE_EXISTS", "来源礼包条码映射已存在");
            }
            long bundleId = WriteCommands.parseIdentifier(input.bundleId());
            ProductBundle bundle = requireBundle(bundleId);
            if (!"ACTIVE".equals(bundle.getStatus())) {
                throw BusinessException.unprocessable("BUNDLE_NOT_ACTIVE", "来源礼包映射只能绑定 ACTIVE 礼包");
            }
            SourceChannelBundle mapping = new SourceChannelBundle();
            mapping.setSourceChannel(input.sourceChannel());
            mapping.setSourceBundleRef(input.sourceBundleRef());
            mapping.setSourceBundleName(blankToNull(input.sourceBundleName()));
            mapping.setSourceBarcode(sourceBarcode);
            mapping.setQuantityMultiplier(1);
            mapping.setBundleId(bundleId);
            mapping.setActive(!Boolean.FALSE.equals(input.active()));
            SourceChannelBundle saved = sourceBundleMappings.saveAndFlush(mapping);
            entityManager.refresh(saved);
            return sourceBundleMapping(saved);
        });
    }

    @Transactional
    public IdempotentResult<MasterDataRecord> patchSourceBundleMapping(
            long id, SourceBundleMappingPatch input, String key, CommandContext context) {
        requireAny(input.bundleId(), input.sourceBundleName(), input.active());
        return write("source_bundle_mapping.update", key, Map.of("id", id, "body", input), OK, context, () -> {
            SourceChannelBundle mapping = sourceBundleMappings.findById(id)
                    .orElseThrow(() -> BusinessException.notFound("来源礼包映射不存在"));
            requireVersion(mapping.getLockVersion(), input.expectedVersion());

            if (input.bundleId() != null) {
                long bundleId = WriteCommands.parseIdentifier(input.bundleId());
                ProductBundle bundle = requireBundle(bundleId);
                // 与创建口径一致：映射只能指向 ACTIVE 礼包，改绑同样不放行草稿/停用礼包。
                if (!"ACTIVE".equals(bundle.getStatus())) {
                    throw BusinessException.unprocessable("BUNDLE_NOT_ACTIVE", "来源礼包映射只能绑定 ACTIVE 礼包");
                }
                mapping.setBundleId(bundleId);
            }
            if (input.sourceBundleName() != null) {
                // 传空串表示清掉自定义名称，读取时回落到礼包名（见 sourceBundleMapping 投影）。
                mapping.setSourceBundleName(blankToNull(input.sourceBundleName()));
            }
            if (input.active() != null) {
                mapping.setActive(input.active());
            }
            SourceChannelBundle saved = sourceBundleMappings.saveAndFlush(mapping);
            entityManager.refresh(saved);
            return sourceBundleMapping(saved);
        });
    }

    private List<BundleItem> parseItems(List<BundleItemInput> inputs) {
        List<BundleItem> result = new ArrayList<>();
        Set<Long> seenSkuIds = new HashSet<>();
        int sortNo = 1;
        for (BundleItemInput input : inputs) {
            long skuId = WriteCommands.parseIdentifier(input.skuId());
            if (!seenSkuIds.add(skuId)) {
                throw BusinessException.badRequest(
                        "BUNDLE_DUPLICATE_SKU", "同一礼包内组件 SKU 重复: " + input.skuId());
            }
            skus.findById(skuId).orElseThrow(() -> BusinessException.notFound("SKU 不存在"));
            BundleItem item = new BundleItem();
            item.setSortNo(sortNo++);
            item.setSkuId(skuId);
            item.setQuantityPerBundle(input.quantityPerBundle());
            item.setEmgCodeSnapshot(blankToNull(input.emgCodeSnapshot()));
            item.setSourceTextSnapshot(blankToNull(input.sourceTextSnapshot()));
            result.add(item);
        }
        return result;
    }

    private MasterDataRecord bundle(ProductBundle value) {
        List<Map<String, Object>> items = bundleItems.findByBundleIdOrderBySortNo(value.getId())
                .stream()
                .map(this::item)
                .toList();
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("barcode", value.getBarcode());
        attributes.put("category_id", id(value.getCategoryId()));
        attributes.put("description", value.getDescription());
        attributes.put("status", value.getStatus());
        attributes.put("fulfillment_provider_id", id(value.getFulfillmentProviderId()));
        attributes.put("tax_rate", SkuCommercialPrice.text(value.getTaxRate()));
        attributes.put("settlement_cost", SkuCommercialPrice.text(value.getSettlementCost()));
        attributes.put("items", items);
        return new MasterDataRecord(
                id(value.getId()),
                value.getBundleCode(),
                value.getBundleName(),
                "ACTIVE".equals(value.getStatus()),
                value.getLockVersion(),
                attributes,
                value.getCreatedAt(),
                value.getUpdatedAt());
    }

    private MasterDataRecord sourceBundleMapping(SourceChannelBundle value) {
        ProductBundle bundle = requireBundle(value.getBundleId());
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("source_channel", value.getSourceChannel().name());
        attributes.put("source_barcode", value.getSourceBarcode());
        attributes.put("bundle_id", id(value.getBundleId()));
        attributes.put("quantity_multiplier", value.getQuantityMultiplier());
        return new MasterDataRecord(
                id(value.getId()),
                value.getSourceBundleRef(),
                value.getSourceBundleName() == null ? bundle.getBundleName() : value.getSourceBundleName(),
                value.isActive(),
                value.getLockVersion(),
                attributes,
                value.getCreatedAt(),
                value.getUpdatedAt());
    }

    private Map<String, Object> item(BundleItem item) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("sku_id", id(item.getSkuId()));
        value.put("quantity_per_bundle", item.getQuantityPerBundle());
        value.put("emg_code_snapshot", item.getEmgCodeSnapshot());
        value.put("source_text_snapshot", item.getSourceTextSnapshot());
        skus.findById(item.getSkuId()).ifPresent(sku -> {
            value.put("sku_code", sku.getSkuCode());
            value.put("specification", sku.getSpecification());
            value.put("unit", sku.getUnit());
            products.findById(sku.getProductId())
                    .ifPresent(product -> value.put("product_name", product.getProductName()));
        });
        return value;
    }

    private ProductBundle requireBundle(long id) {
        return bundles.findById(id).orElseThrow(() -> BusinessException.notFound("礼包不存在"));
    }

    private void requireCategory(Long id) {
        if (id != null && !categories.existsById(id)) {
            throw BusinessException.notFound("品类不存在");
        }
    }

    private static void requireVersion(Long actual, Long expected) {
        if (!actual.equals(expected)) {
            throw BusinessException.conflict("VERSION_CONFLICT", "数据已被其他请求修改，请刷新后重试");
        }
    }

    private static void requireAny(Object... values) {
        for (Object value : values) {
            if (value != null) {
                return;
            }
        }
        throw BusinessException.badRequest("PATCH_EMPTY", "至少需要修改一个业务字段");
    }

    private static BigDecimal parseTaxRate(Object raw) {
        BigDecimal value = SkuCommercialPrice.parse(raw, "tax_rate");
        if (value != null && value.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw BusinessException.badRequest("INVALID_BUNDLE_FIELD", "税率必须在 0-100 之间");
        }
        return value;
    }

    private IdempotentResult<MasterDataRecord> write(
            String operation,
            String key,
            Object payload,
            int httpStatus,
            CommandContext context,
            Supplier<MasterDataRecord> work) {
        return idempotency.execute(operation, key, payload, httpStatus, () -> {
            MasterDataRecord result = work.get();
            audit.record(new AuditLogService.AuditCommand()
                    .dataScope(DataScope.BUSINESS)
                    .requestId(context.requestId())
                    .traceId(context.traceId())
                    .operator(context.operator())
                    .actorType(AuditActorType.HUMAN)
                    .service("BundleMasterDataService")
                    .operation(operation)
                    .requestPayload(payload)
                    .responsePayload(result)
                    .httpStatus(httpStatus)
                    .businessCode("SUCCESS"));
            return result;
        });
    }

    private static String id(Long value) {
        return value == null ? null : value.toString();
    }

    private static String decimal(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros().toPlainString();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
