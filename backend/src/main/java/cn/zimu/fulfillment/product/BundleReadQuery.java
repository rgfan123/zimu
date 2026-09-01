package cn.zimu.fulfillment.product;

import cn.zimu.fulfillment.common.dto.PageResponse;
import java.time.Instant;
import java.util.List;

/**
 * 静态礼包读取用例的稳定边界。
 *
 * <p>MCP 等调用方只依赖业务投影，不依赖 JDBC 查询实现或礼包表的内部存储形状。
 */
public interface BundleReadQuery {

    PageResponse<BundleSummary> searchBundles(
            String status, Long providerId, String query, int page, int size);

    BundleDetail getBundle(long bundleId);

    PageResponse<BundleCandidate> findCandidates(
            String query, Long providerId, String mappingStatus, int page, int size);

    /**
     * 组包经济核算所需的组件 SKU 事实（票：组包师 Agent）。按入参顺序返回；
     * 未命中的 id 不补空行——调用方据缺失项 fail-fast，绝不带缺口硬算。
     */
    List<ComponentSkuFact> componentSkuFacts(List<Long> skuIds);

    /** purchasePrice 为 decimal-string（分位两位），无价时 null——缺价必须显式呈现。 */
    record ComponentSkuFact(
            String id,
            String skuCode,
            String productName,
            String specification,
            boolean active,
            String purchasePrice) {}

    record ProviderSummary(String id, String code, String name, String type) {}

    record BundleSummary(
            String id,
            String bundleCode,
            String bundleName,
            String status,
            int componentCount,
            boolean allComponentsActive,
            List<ProviderSummary> fulfillmentProviders) {

        public BundleSummary {
            fulfillmentProviders = List.copyOf(fulfillmentProviders);
        }
    }

    record BundleDetail(
            String id,
            String bundleCode,
            String bundleName,
            String categoryId,
            String barcode,
            String description,
            String settlementCost,
            String status,
            List<BundleComponent> components,
            boolean allComponentsActive,
            List<ProviderSummary> fulfillmentProviders) {

        public BundleDetail {
            components = List.copyOf(components);
            fulfillmentProviders = List.copyOf(fulfillmentProviders);
        }
    }

    record BundleComponent(
            int sortNo,
            String skuId,
            String skuCode,
            String productId,
            String productCode,
            String productName,
            String specification,
            String unit,
            int quantityPerBundle,
            String purchasePrice,
            boolean active,
            ProviderSummary provider) {}

    record BundleCandidate(
            String skuId,
            String skuCode,
            String productId,
            String productCode,
            String productName,
            String specification,
            String unit,
            String purchasePrice,
            ProviderSummary provider,
            String providerSkuCode,
            List<InventoryObservation> inventoryObservations) {

        public BundleCandidate {
            inventoryObservations = List.copyOf(inventoryObservations);
        }
    }

    record InventoryObservation(
            String warehouseCode,
            Integer totalQuantity,
            Integer availableQuantity,
            String quantityUnit,
            Instant observedAt,
            String sourceType) {}
}
