package cn.zimu.fulfillment.seed;

import cn.zimu.fulfillment.catalog.CatalogMasterDataLock;
import cn.zimu.fulfillment.common.domain.DataScope;
import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.customer.Customer;
import cn.zimu.fulfillment.customer.CustomerRepository;
import cn.zimu.fulfillment.customer.CustomerSourceRef;
import cn.zimu.fulfillment.customer.CustomerSourceRefRepository;
import cn.zimu.fulfillment.customer.CustomerStatus;
import cn.zimu.fulfillment.product.Category;
import cn.zimu.fulfillment.product.CategoryRepository;
import cn.zimu.fulfillment.product.Product;
import cn.zimu.fulfillment.product.ProductRepository;
import cn.zimu.fulfillment.sku.FulfillmentProvider;
import cn.zimu.fulfillment.sku.FulfillmentProviderRepository;
import cn.zimu.fulfillment.sku.ProviderSku;
import cn.zimu.fulfillment.sku.ProviderSkuRepository;
import cn.zimu.fulfillment.sku.ProviderType;
import cn.zimu.fulfillment.sku.Sku;
import cn.zimu.fulfillment.sku.SkuRepository;
import cn.zimu.fulfillment.sku.SourceChannelSku;
import cn.zimu.fulfillment.sku.SourceChannelSkuRepository;
import cn.zimu.fulfillment.sku.SourceSkuRefPolicy;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 最小、确定性的主数据种子：客户、品类、商品、京东/第三方履约方与 SKU、来源渠道 SKU 映射。
 *
 * <p>仅在对应编码不存在时写入，可安全重复启动。SKU 编码由数据库触发器按
 * {@code SKU-<provider_code>-<6位序号>} 生成，种子通过商品与履约方的稳定业务组合幂等查找。
 */
@Component
@Order(0)
public class SeedDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedDataInitializer.class);

    private static final String CUSTOMER_CODE = "CUST-WECOM-0001";
    private static final String CATEGORY_CODE = "CAT-MEAT";
    private static final String JD_PROVIDER_CODE = "JD";
    private static final String TP_PROVIDER_CODE = "TP";

    private final CatalogMasterDataLock catalogLock;
    private final CategoryRepository categoryRepository;
    private final ProductRepository productRepository;
    private final FulfillmentProviderRepository providerRepository;
    private final SkuRepository skuRepository;
    private final SourceChannelSkuRepository sourceChannelSkuRepository;
    private final ProviderSkuRepository providerSkuRepository;
    private final CustomerRepository customerRepository;
    private final CustomerSourceRefRepository customerSourceRefRepository;
    private final boolean sampleMasterDataEnabled;

    public SeedDataInitializer(
            CatalogMasterDataLock catalogLock,
            CategoryRepository categoryRepository,
            ProductRepository productRepository,
            FulfillmentProviderRepository providerRepository,
            SkuRepository skuRepository,
            SourceChannelSkuRepository sourceChannelSkuRepository,
            ProviderSkuRepository providerSkuRepository,
            CustomerRepository customerRepository,
            CustomerSourceRefRepository customerSourceRefRepository,
            @Value("${app.seed.sample-master-data-enabled:true}") boolean sampleMasterDataEnabled) {
        this.catalogLock = catalogLock;
        this.categoryRepository = categoryRepository;
        this.productRepository = productRepository;
        this.providerRepository = providerRepository;
        this.skuRepository = skuRepository;
        this.sourceChannelSkuRepository = sourceChannelSkuRepository;
        this.providerSkuRepository = providerSkuRepository;
        this.customerRepository = customerRepository;
        this.customerSourceRefRepository = customerSourceRefRepository;
        this.sampleMasterDataEnabled = sampleMasterDataEnabled;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        catalogLock.lockForMasterDataWrite();
        FulfillmentProvider jd = provider(JD_PROVIDER_CODE, "京东云仓", ProviderType.JD_WAREHOUSE, true);
        FulfillmentProvider tp = provider(TP_PROVIDER_CODE, "第三方履约", ProviderType.THIRD_PARTY, false);
        Customer customer = customer();
        customerSourceRef(customer);
        if (sampleMasterDataEnabled) {
            Category category = category();
            Product product = product(category, jd);
            Sku jdSku = sku(product, jd, "500g/盒", "盒", "500", "g", 1, "盒");
            Sku tpSku = sku(product, tp, "标准箱", "箱", "1", "箱", 1, "箱");
            sourceChannelSku(jdSku, "WECOM-SKU-JD-001", "子牧羊小腿", 1);
            sourceChannelSku(tpSku, "WECOM-SKU-TP-001", "子牧羊小腿（第三方）", null);
            providerSku(jd, jdSku, "JD-SKU-000001");
            providerSku(tp, tpSku, "TP-SKU-000001");
            log.info("sample master data ready: product={}, jdSku={}, tpSku={}",
                    product.getProductCode(), jdSku.getId(), tpSku.getId());
        }
        log.info("base master data ready: customer={}, sampleMasterDataEnabled={}",
                customer.getCustomerCode(), sampleMasterDataEnabled);
    }

    private Category category() {
        var existing = categoryRepository.findByCategoryCode(CATEGORY_CODE);
        if (existing.isPresent()) {
            return existing.get();
        }
        Category category = new Category();
        category.setCategoryCode(CATEGORY_CODE);
        category.setCategoryName("肉类");
        return categoryRepository.save(category);
    }

    private Product product(Category category, FulfillmentProvider jdProvider) {
        var existingMapping = providerSkuRepository.findByFulfillmentProviderIdAndProviderSkuCode(
                jdProvider.getId(), "JD-SKU-000001");
        if (existingMapping.isPresent()) {
            Sku existingSku = skuRepository.findById(existingMapping.get().getSkuId()).orElseThrow();
            return productRepository.findById(existingSku.getProductId()).orElseThrow();
        }
        Product product = new Product();
        product.setProductCode(productRepository.nextProductCode());
        product.setProductName("子牧羊小腿");
        product.setCategoryId(category == null ? null : category.getId());
        product.setDescription("子牧羊小腿 500g/盒");
        return productRepository.save(product);
    }

    private FulfillmentProvider provider(String code, String name, ProviderType type, boolean inventoryManagedByUs) {
        if (providerRepository.existsByProviderCode(code)) {
            return providerRepository.findByProviderCode(code).orElseThrow();
        }
        FulfillmentProvider provider = new FulfillmentProvider();
        provider.setProviderCode(code);
        provider.setProviderName(name);
        provider.setProviderType(type);
        provider.setInventoryManagedByUs(inventoryManagedByUs);
        provider.setTrackingSlaMinutes(type == ProviderType.JD_WAREHOUSE ? 60 : 1440);
        provider.setConfig(new LinkedHashMap<>());
        return providerRepository.save(provider);
    }

    private Sku sku(
            Product product,
            FulfillmentProvider provider,
            String specification,
            String unit,
            String netContentValue,
            String netContentUnit,
            int packageCount,
            String packageUnit) {
        var existing = skuRepository.findByProductIdAndFulfillmentProviderIdAndSpecificationAndUnit(
                product.getId(), provider.getId(), specification, unit);
        if (existing.isPresent()) {
            Sku sku = existing.get();
            boolean changed = false;
            if (sku.getNetContentValue() == null) {
                sku.setNetContentValue(new BigDecimal(netContentValue));
                changed = true;
            }
            if (sku.getNetContentUnit() == null || sku.getNetContentUnit().isBlank()) {
                sku.setNetContentUnit(netContentUnit);
                changed = true;
            }
            if (sku.getPackageCount() == null) {
                sku.setPackageCount(packageCount);
                changed = true;
            }
            if (sku.getPackageUnit() == null || sku.getPackageUnit().isBlank()) {
                sku.setPackageUnit(packageUnit);
                changed = true;
            }
            return changed ? skuRepository.save(sku) : sku;
        }
        Sku sku = new Sku();
        sku.setProductId(product.getId());
        sku.setFulfillmentProviderId(provider.getId());
        sku.setSpecification(specification);
        sku.setUnit(unit);
        sku.setNetContentValue(new BigDecimal(netContentValue));
        sku.setNetContentUnit(netContentUnit);
        sku.setPackageCount(packageCount);
        sku.setPackageUnit(packageUnit);
        return skuRepository.save(sku);
    }

    private void sourceChannelSku(
            Sku sku, String sourceSkuRef, String sourceProductName, Integer quantityMultiplier) {
        if (sku == null
                || sourceChannelSkuRepository.existsBySourceChannelAndSourceSkuRef(SourceChannel.WECOM, sourceSkuRef)) {
            return;
        }
        SourceSkuRefPolicy.requireReusable(sourceSkuRef);
        SourceChannelSku mapping = new SourceChannelSku();
        mapping.setSourceChannel(SourceChannel.WECOM);
        mapping.setSourceSkuRef(sourceSkuRef);
        mapping.setSourceProductName(sourceProductName);
        mapping.setQuantityMultiplier(quantityMultiplier);
        mapping.setSkuId(sku.getId());
        sourceChannelSkuRepository.save(mapping);
    }

    private void providerSku(FulfillmentProvider provider, Sku sku, String providerSkuCode) {
        if (sku == null) {
            return;
        }
        var existing = providerSkuRepository.findByFulfillmentProviderIdAndProviderSkuCode(
                provider.getId(), providerSkuCode);
        if (existing.isPresent()) {
            ProviderSku mapping = existing.get();
            if (provider.getProviderType() == ProviderType.JD_WAREHOUSE
                    && mapping.getSkuId().equals(sku.getId())
                    && !mapping.getExternalCodes().containsKey("jd_pieces_per_unit")) {
                Map<String, Object> externalCodes = new LinkedHashMap<>(mapping.getExternalCodes());
                externalCodes.put("jd_pieces_per_unit", 1);
                mapping.setExternalCodes(externalCodes);
                providerSkuRepository.save(mapping);
            }
            return;
        }
        ProviderSku providerSku = new ProviderSku();
        providerSku.setFulfillmentProviderId(provider.getId());
        providerSku.setSkuId(sku.getId());
        providerSku.setProviderSkuCode(providerSkuCode);
        Map<String, Object> externalCodes = new LinkedHashMap<>();
        if (provider.getProviderType() == ProviderType.JD_WAREHOUSE) {
            externalCodes.put("jd_pieces_per_unit", 1);
        }
        providerSku.setExternalCodes(externalCodes);
        providerSkuRepository.save(providerSku);
    }

    private Customer customer() {
        var existing = customerRepository.findByCustomerCode(CUSTOMER_CODE);
        if (existing.isPresent()) {
            return existing.get();
        }
        Customer customer = new Customer();
        customer.setCustomerCode(CUSTOMER_CODE);
        customer.setCustomerName("子牧测试客户");
        customer.setDataScope(DataScope.BUSINESS);
        customer.setStatus(CustomerStatus.ACTIVE);
        customer.setProfile(new LinkedHashMap<>());
        return customerRepository.save(customer);
    }

    private void customerSourceRef(Customer customer) {
        if (customer == null
                || customerSourceRefRepository
                        .findBySourceChannelAndSourceCustomerRef(SourceChannel.WECOM, "WECOM-CUSTOMER-001")
                        .isPresent()) {
            return;
        }
        CustomerSourceRef sourceRef = new CustomerSourceRef();
        sourceRef.setCustomerId(customer.getId());
        sourceRef.setSourceChannel(SourceChannel.WECOM);
        sourceRef.setSourceCustomerRef("WECOM-CUSTOMER-001");
        customerSourceRefRepository.save(sourceRef);
    }

}
