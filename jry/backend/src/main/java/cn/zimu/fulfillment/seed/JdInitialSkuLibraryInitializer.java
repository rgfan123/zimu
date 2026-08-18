package cn.zimu.fulfillment.seed;

import cn.zimu.fulfillment.catalog.CatalogMasterDataLock;
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
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 仅在显式开关下将京东编号参考资料初始化为内部 SKU 与履约编码；默认只走 preview，不落库。 */
@Component
@Order(1)
@ConditionalOnProperty(prefix = "app.seed.jd-initial-sku-library", name = "enabled", havingValue = "true")
public class JdInitialSkuLibraryInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(JdInitialSkuLibraryInitializer.class);
    private static final String RESOURCE = "data/jd-initial-sku-library.tsv";
    private static final String CATEGORY_CODE = "CAT-JD-INITIAL";
    private static final String PROVIDER_CODE = "JD";
    private static final String UNKNOWN_NAME_CODE = "EMG4418861038167";

    private final CatalogMasterDataLock catalogLock;
    private final CategoryRepository categories;
    private final ProductRepository products;
    private final FulfillmentProviderRepository providers;
    private final SkuRepository skus;
    private final ProviderSkuRepository providerSkus;

    public JdInitialSkuLibraryInitializer(
            CatalogMasterDataLock catalogLock,
            CategoryRepository categories,
            ProductRepository products,
            FulfillmentProviderRepository providers,
            SkuRepository skus,
            ProviderSkuRepository providerSkus) {
        this.catalogLock = catalogLock;
        this.categories = categories;
        this.products = products;
        this.providers = providers;
        this.skus = skus;
        this.providerSkus = providerSkus;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) throws IOException {
        catalogLock.lockForMasterDataWrite();
        Category category = category();
        FulfillmentProvider provider = providers.findByProviderCode(PROVIDER_CODE)
                .orElseThrow(() -> new IllegalStateException("JD fulfillment provider must be seeded first"));
        int imported = 0;
        for (LibraryRow row : rows()) {
            if (providerSkus.findByFulfillmentProviderIdAndProviderSkuCode(provider.getId(), row.code()).isPresent()) {
                imported++;
                continue;
            }
            Product product = product(category, row);
            Sku sku = sku(product, provider, row.code());
            providerSku(provider, sku, row);
            imported++;
        }
        log.info("JD initial SKU library ready: {} provider SKU mappings", imported);
    }

    private Category category() {
        return categories.findByCategoryCode(CATEGORY_CODE).orElseGet(() -> {
            Category category = new Category();
            category.setCategoryCode(CATEGORY_CODE);
            category.setCategoryName("京东初版商品库");
            return categories.save(category);
        });
    }

    private Product product(Category category, LibraryRow row) {
        String productCode = "PROD-JD-" + row.code();
        return products.findByProductCode(productCode).orElseGet(() -> {
            Product product = new Product();
            product.setProductCode(productCode);
            product.setProductName(row.name());
            product.setCategoryId(category.getId());
            product.setDescription(UNKNOWN_NAME_CODE.equals(row.code())
                    ? "来自《京东商品编号.xlsx》；商品名称待维护"
                    : "来自《京东商品编号.xlsx》初版 SKU 库");
            return products.save(product);
        });
    }

    private Sku sku(Product product, FulfillmentProvider provider, String providerSkuCode) {
        String specification = "京东商品编号 " + providerSkuCode;
        return skus.findByProductIdAndFulfillmentProviderIdAndSpecificationAndUnit(
                        product.getId(), provider.getId(), specification, "件")
                .orElseGet(() -> {
                    Sku sku = new Sku();
                    sku.setProductId(product.getId());
                    sku.setFulfillmentProviderId(provider.getId());
                    sku.setSpecification(specification);
                    sku.setUnit("件");
                    return skus.save(sku);
                });
    }

    private void providerSku(FulfillmentProvider provider, Sku sku, LibraryRow row) {
        ProviderSku mapping = new ProviderSku();
        mapping.setFulfillmentProviderId(provider.getId());
        mapping.setSkuId(sku.getId());
        mapping.setProviderSkuCode(row.code());
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("provider_sku_name", row.name());
        metadata.put("aliases", row.aliases());
        metadata.put("catalog_source", "京东商品编号.xlsx");
        metadata.put("name_status", UNKNOWN_NAME_CODE.equals(row.code()) ? "NEED_REVIEW" : "CONFIRMED");
        mapping.setExternalCodes(metadata);
        mapping.setActive(!UNKNOWN_NAME_CODE.equals(row.code()));
        providerSkus.save(mapping);
    }

    private List<LibraryRow> rows() throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource(RESOURCE).getInputStream(), StandardCharsets.UTF_8))) {
            return reader.lines().skip(1).filter(line -> !line.isBlank()).map(this::row).toList();
        }
    }

    private LibraryRow row(String line) {
        String[] columns = line.split("\\t", -1);
        if (columns.length != 3 || !columns[0].matches("EMG\\d+") || columns[1].isBlank()) {
            throw new IllegalStateException("Invalid JD initial SKU library row: " + line);
        }
        List<String> aliases = columns[2].isBlank()
                ? List.of()
                : Arrays.stream(columns[2].split("\\|"))
                        .map(String::trim)
                        .filter(alias -> !alias.isBlank() && !alias.equals(columns[1]))
                        .distinct()
                        .toList();
        return new LibraryRow(columns[0], columns[1], aliases);
    }

    private record LibraryRow(String code, String name, List<String> aliases) {}
}
