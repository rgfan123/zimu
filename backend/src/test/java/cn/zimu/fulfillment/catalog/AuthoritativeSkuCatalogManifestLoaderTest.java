package cn.zimu.fulfillment.catalog;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.zimu.fulfillment.common.error.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class AuthoritativeSkuCatalogManifestLoaderTest {

    private static final String LEGACY_PRICED_JD_CODE = "EMG4418727174451";

    private final ObjectMapper objectMapper = new ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    private final AuthoritativeSkuCatalogManifestLoader loader =
            new AuthoritativeSkuCatalogManifestLoader(objectMapper);

    @Test
    void rejectsManifestItemsThatCarryLegacyPriceFields() throws IOException {
        AuthoritativeSkuCatalogManifest manifest;
        try (InputStream input = new ClassPathResource(AuthoritativeSkuCatalogManifestLoader.RESOURCE)
                .getInputStream()) {
            manifest = objectMapper.readValue(input, AuthoritativeSkuCatalogManifest.class);
        }
        var items = new ArrayList<>(manifest.items());
        for (int index = 0; index < items.size(); index++) {
            var item = items.get(index);
            if (!LEGACY_PRICED_JD_CODE.equals(item.jdCode())) continue;
            items.set(index, new AuthoritativeSkuCatalogManifest.Item(
                    item.jdCode(),
                    item.canonicalName(),
                    item.aliases(),
                    item.sourceRows(),
                    "子牧澳洲谷饲上脑牛肉片1KG*1",
                    2,
                    "106.50",
                    "158.00",
                    item.mappingDifferenceCodes()));
        }
        var pricedManifest = new AuthoritativeSkuCatalogManifest(
                manifest.schemaVersion(),
                manifest.jdSource(),
                manifest.priceSource(),
                manifest.expected(),
                manifest.excludedSheets(),
                items);

        assertThatThrownBy(() -> loader.validate(pricedManifest))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("权威京东商品 manifest 不得携带价格");
    }
}
