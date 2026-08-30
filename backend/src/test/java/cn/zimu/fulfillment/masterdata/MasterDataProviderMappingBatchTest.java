package cn.zimu.fulfillment.masterdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.zimu.fulfillment.catalog.CatalogMasterDataLock;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.idempotency.IdempotencyService;
import cn.zimu.fulfillment.customer.CustomerRepository;
import cn.zimu.fulfillment.product.CategoryRepository;
import cn.zimu.fulfillment.product.ProductRepository;
import cn.zimu.fulfillment.sku.FulfillmentProvider;
import cn.zimu.fulfillment.sku.FulfillmentProviderRepository;
import cn.zimu.fulfillment.sku.ProviderSku;
import cn.zimu.fulfillment.sku.ProviderSkuRepository;
import cn.zimu.fulfillment.sku.ProviderType;
import cn.zimu.fulfillment.sku.Sku;
import cn.zimu.fulfillment.sku.SkuFulfillmentReadinessService;
import cn.zimu.fulfillment.sku.SkuRepository;
import cn.zimu.fulfillment.sku.SourceChannelSkuRepository;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class MasterDataProviderMappingBatchTest {

    @Mock IdempotencyService idempotency;
    @Mock AuditLogService audit;
    @Mock CatalogMasterDataLock catalogLock;
    @Mock CustomerRepository customers;
    @Mock CategoryRepository categories;
    @Mock ProductRepository products;
    @Mock SkuRepository skus;
    @Mock SourceChannelSkuRepository sourceMappings;
    @Mock ProviderSkuRepository providerMappings;
    @Mock FulfillmentProviderRepository providers;
    @Mock SkuFulfillmentReadinessService skuReadiness;
    @Mock EntityManager entityManager;

    private MasterDataService service;

    @BeforeEach
    void setUp() {
        service = new MasterDataService(
                idempotency,
                audit,
                catalogLock,
                customers,
                categories,
                products,
                skus,
                sourceMappings,
                providerMappings,
                providers,
                skuReadiness,
                entityManager);
    }

    @Test
    void providerMappingPageLoadsSkuAndProviderDimensionsInTwoBatches() {
        ProviderSku tpRoute = mapping(1L, 10L, 100L, "SKU-TP-000010");
        ProviderSku jdMapping = mapping(2L, 20L, 200L, "EMG-200");
        Sku tpSku = sku(10L, "SKU-TP-000010");
        Sku jdSku = sku(20L, "SKU-JD-000020");
        FulfillmentProvider tp = provider(100L, ProviderType.THIRD_PARTY);
        FulfillmentProvider jd = provider(200L, ProviderType.JD_WAREHOUSE);

        when(providerMappings.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(tpRoute, jdMapping)));
        when(skus.findAllById(any())).thenReturn(List.of(tpSku, jdSku));
        when(providers.findAllById(any())).thenReturn(List.of(tp, jd));

        var result = service.providerMappings(0, 200);

        assertThat(result.items())
                .extracting(record -> record.attributes().get("provider_sku_code_scope"))
                .containsExactly("INTERNAL_ROUTING", "PROVIDER_EXTERNAL");
        verify(skus).findAllById(any());
        verify(providers).findAllById(any());
        verify(skus, never()).findById(any());
        verify(providers, never()).findById(any());
    }

    private static ProviderSku mapping(long id, long skuId, long providerId, String code) {
        ProviderSku value = mock(ProviderSku.class);
        when(value.getId()).thenReturn(id);
        when(value.getSkuId()).thenReturn(skuId);
        when(value.getFulfillmentProviderId()).thenReturn(providerId);
        when(value.getProviderSkuCode()).thenReturn(code);
        when(value.getExternalCodes()).thenReturn(Map.of());
        when(value.isActive()).thenReturn(true);
        when(value.getLockVersion()).thenReturn(0L);
        return value;
    }

    private static Sku sku(long id, String code) {
        Sku value = mock(Sku.class);
        when(value.getId()).thenReturn(id);
        when(value.getSkuCode()).thenReturn(code);
        return value;
    }

    private static FulfillmentProvider provider(long id, ProviderType type) {
        FulfillmentProvider value = mock(FulfillmentProvider.class);
        when(value.getId()).thenReturn(id);
        when(value.getProviderType()).thenReturn(type);
        return value;
    }
}
