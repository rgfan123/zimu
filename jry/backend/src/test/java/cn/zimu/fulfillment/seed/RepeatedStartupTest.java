package cn.zimu.fulfillment.seed;

import static org.assertj.core.api.Assertions.assertThat;

import cn.zimu.fulfillment.FulfillmentHubApplication;
import cn.zimu.fulfillment.product.ProductRepository;
import cn.zimu.fulfillment.sku.FulfillmentProviderRepository;
import cn.zimu.fulfillment.sku.Sku;
import cn.zimu.fulfillment.sku.SkuRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class RepeatedStartupTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Test
    void applicationCanRestartAgainstItsSeededDatabase() {
        String[] properties = {
            "--spring.datasource.url=" + postgres.getJdbcUrl(),
            "--spring.datasource.username=" + postgres.getUsername(),
            "--spring.datasource.password=" + postgres.getPassword(),
            "--spring.data.redis.repositories.enabled=false",
            "--spring.main.banner-mode=off"
        };

        long seededSkuCount;
        try (ConfigurableApplicationContext first = start(properties)) {
            assertThat(first.isActive()).isTrue();
            seededSkuCount = first.getBean(SkuRepository.class).count();
            assertThat(seededSkuCount).isPositive();
            addAnotherSpecificationForTheSeededProduct(first);
            assertThat(first.getBean(SkuRepository.class).count()).isEqualTo(seededSkuCount + 1);
        }
        try (ConfigurableApplicationContext second = start(properties)) {
            assertThat(second.isActive()).isTrue();
            assertThat(second.getBean(SkuRepository.class).count()).isEqualTo(seededSkuCount + 1);
        }
    }

    private static ConfigurableApplicationContext start(String[] properties) {
        return new SpringApplicationBuilder(FulfillmentHubApplication.class)
                .web(WebApplicationType.NONE)
                .run(properties);
    }

    private static void addAnotherSpecificationForTheSeededProduct(ConfigurableApplicationContext context) {
        Long productId = context.getBean(ProductRepository.class)
                .findByProductCode("PROD-LAMBLEG")
                .orElseThrow()
                .getId();
        Long providerId = context.getBean(FulfillmentProviderRepository.class)
                .findByProviderCode("JD")
                .orElseThrow()
                .getId();
        Sku additional = new Sku();
        additional.setProductId(productId);
        additional.setFulfillmentProviderId(providerId);
        additional.setSpecification("1kg/盒");
        additional.setUnit("盒");
        context.getBean(SkuRepository.class).saveAndFlush(additional);
    }
}
