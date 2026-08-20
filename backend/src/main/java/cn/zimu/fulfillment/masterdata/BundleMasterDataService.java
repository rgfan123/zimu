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
import cn.zimu.fulfillment.product.BundleWrite;
import cn.zimu.fulfillment.product.ProductBundle;
import cn.zimu.fulfillment.product.ProductBundleRepository;
import cn.zimu.fulfillment.product.ProductRepository;
import cn.zimu.fulfillment.product.SourceBundleMappingWrite;
import cn.zimu.fulfillment.product.SourceChannelBundle;
import cn.zimu.fulfillment.product.SourceChannelBundleRepository;
import cn.zimu.fulfillment.sku.SkuRepository;
import jakarta.persistence.EntityManager;
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

/** 静态礼包与来源礼包映射的最小主数据用例。 */
@Service
public class BundleMasterDataService {

    private static final int CREATED = 201;
    private static final Sort ID_ASC = Sort.by("id").ascending();

    private final IdempotencyService idempotency;
    private final AuditLogService audit;
    private final CatalogMasterDataLock catalogLock;
    private final ProductBundleRepository bundles;
    private final BundleItemRepository bundleItems;
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
            SkuRepository skus,
            ProductRepository products,
            SourceChannelBundleRepository sourceBundleMappings,
            EntityManager entityManager) {
        this.idempotency = idempotency;
        this.audit = audit;
        this.catalogLock = catalogLock;
        this.bundles = bundles;
        this.bundleItems = bundleItems;
        this.skus = skus;
        this.products = products;
        this.sourceBundleMappings = sourceBundleMappings;
        this.entityManager = entityManager;
    }

    @Transactional(readOnly = true)
    public PageResponse<MasterDataRecord> productBundles(int page, int size) {
        Page<ProductBundle> result = bundles.findAll(PageRequest.of(page, size, ID_ASC));
        return PageResponse.of(result.stream().map(this::bundle).toList(), result);
    }

    @Transactional(readOnly = true)
    public MasterDataRecord productBundle(long id) {
        return bundle(requireBundle(id));
    }

    @Transactional
    public IdempotentResult<MasterDataRecord> createProductBundle(
            BundleWrite input, String key, CommandContext context) {
        return write("product_bundle.create", key, input, context, () -> {
            catalogLock.lockForMasterDataWrite();
            if (bundles.existsByBundleCode(input.bundleCode())) {
                throw BusinessException.conflict("BUNDLE_CODE_EXISTS", "礼包编码已存在");
            }
            String barcode = blankToNull(input.barcode());
            if (barcode != null && bundles.existsByBarcode(barcode)) {
                throw BusinessException.conflict("BUNDLE_BARCODE_EXISTS", "礼包条码已存在");
            }
            List<BundleItem> parsedItems = parseItems(input.items());

            ProductBundle bundle = new ProductBundle();
            bundle.setBundleCode(input.bundleCode());
            bundle.setBundleName(input.bundleName());
            bundle.setBarcode(barcode);
            bundle.setDescription(blankToNull(input.description()));
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
        return write("source_bundle_mapping.create", key, input, context, () -> {
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
            mapping.setQuantityMultiplier(BigDecimal.ONE);
            mapping.setBundleId(bundleId);
            mapping.setActive(!Boolean.FALSE.equals(input.active()));
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
            item.setQuantityPerBundle(new BigDecimal(input.quantityPerBundle()));
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
        attributes.put("description", value.getDescription());
        attributes.put("status", value.getStatus());
        attributes.put("fulfillment_provider_id", id(value.getFulfillmentProviderId()));
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
        attributes.put("quantity_multiplier", decimal(value.getQuantityMultiplier()));
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
        value.put("quantity_per_bundle", decimal(item.getQuantityPerBundle()));
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

    private IdempotentResult<MasterDataRecord> write(
            String operation,
            String key,
            Object payload,
            CommandContext context,
            Supplier<MasterDataRecord> work) {
        return idempotency.execute(operation, key, payload, CREATED, () -> {
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
                    .httpStatus(CREATED)
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
