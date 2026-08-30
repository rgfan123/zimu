package cn.zimu.fulfillment.file;

import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.order.ProviderExportReadinessRechecker;
import cn.zimu.fulfillment.sku.Sku;
import cn.zimu.fulfillment.sku.SkuFulfillmentReadiness;
import cn.zimu.fulfillment.sku.SkuFulfillmentReadinessService;
import cn.zimu.fulfillment.sku.SkuReadinessCatalogLock;
import cn.zimu.fulfillment.sku.SkuRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 履约导出分片的共享 SKU readiness 门禁；普通行与礼包组件使用同一派生规则。 */
@Service
class ProviderExportSkuReadinessGate implements ProviderExportReadinessRechecker {

    private final JdbcTemplate jdbc;
    private final SkuRepository skus;
    private final SkuFulfillmentReadinessService readiness;
    private final SkuReadinessCatalogLock catalogLock;

    ProviderExportSkuReadinessGate(
            JdbcTemplate jdbc,
            SkuRepository skus,
            SkuFulfillmentReadinessService readiness,
            SkuReadinessCatalogLock catalogLock) {
        this.jdbc = jdbc;
        this.skus = skus;
        this.readiness = readiness;
        this.catalogLock = catalogLock;
    }

    /** 返回当前仍待导出的来源批次分片中不就绪的部分；调用方可只暂停对应分片。 */
    List<BlockedPartition> blockedForSourceBatch(long sourceBatchId) {
        return blocked(
                "o.source_import_batch_id=? AND ol.processing_stage='READY_TO_EXPORT'",
                sourceBatchId);
    }

    /** 企业微信人工路由必须在创建任何 Shipment 前通过全部订单行 readiness。 */
    void requireOrderReady(long orderId) {
        throwIfBlocked(blocked("o.id=?", orderId));
    }

    /** 续发文件必须在创建 Shipment/文件前重新核对该 Fulfillment 的 SKU readiness。 */
    void requireFulfillmentReady(long fulfillmentId) {
        throwIfBlocked(blocked("f.id=?", fulfillmentId));
    }

    @Override
    public void requireReady(long orderLineId, long fulfillmentId) {
        throwIfBlocked(blocked("ol.id=? AND f.id=?", orderLineId, fulfillmentId));
    }

    private List<BlockedPartition> blocked(String scope, Object... args) {
        catalogLock.acquireShared();
        List<PartitionSkuFact> facts = jdbc.query(
                """
                SELECT o.id order_id, ol.id order_line_id, f.id fulfillment_id,
                       f.fulfillment_provider_id provider_id, fp.provider_type,
                       CASE WHEN ol.line_type='SINGLE' THEN ol.sku_id ELSE olc.sku_id END sku_id,
                       s.sku_code
                FROM app.orders o
                JOIN app.order_lines ol ON ol.order_id=o.id
                JOIN app.fulfillments f ON f.order_line_id=ol.id
                JOIN app.fulfillment_providers fp ON fp.id=f.fulfillment_provider_id
                LEFT JOIN app.order_line_components olc
                  ON olc.order_line_id=ol.id AND ol.line_type='CUSTOM_BUNDLE'
                LEFT JOIN app.skus s
                  ON s.id=CASE WHEN ol.line_type='SINGLE' THEN ol.sku_id ELSE olc.sku_id END
                WHERE o.data_scope='BUSINESS' AND
                """ + scope + " ORDER BY f.fulfillment_provider_id, ol.id, olc.component_no NULLS FIRST",
                (resultSet, rowNum) -> new PartitionSkuFact(
                        resultSet.getLong("order_id"),
                        resultSet.getLong("order_line_id"),
                        resultSet.getLong("fulfillment_id"),
                        resultSet.getLong("provider_id"),
                        resultSet.getString("provider_type"),
                        resultSet.getObject("sku_id", Long.class),
                        resultSet.getString("sku_code")),
                args);
        if (facts.isEmpty()) {
            return List.of();
        }

        Set<Long> skuIds = new LinkedHashSet<>();
        facts.stream().map(PartitionSkuFact::skuId).filter(java.util.Objects::nonNull).forEach(skuIds::add);
        Map<Long, Sku> skuById = new LinkedHashMap<>();
        skus.findAllById(skuIds).forEach(sku -> skuById.put(sku.getId(), sku));
        Map<Long, SkuFulfillmentReadiness> readinessBySku = readiness.evaluateAll(skuById.values());

        Map<PartitionKey, List<PartitionSkuFact>> factsByPartition = new LinkedHashMap<>();
        for (PartitionSkuFact fact : facts) {
            factsByPartition.computeIfAbsent(fact.key(), ignored -> new ArrayList<>()).add(fact);
        }
        List<BlockedPartition> result = new ArrayList<>();
        for (Map.Entry<PartitionKey, List<PartitionSkuFact>> entry : factsByPartition.entrySet()) {
            Map<String, BlockingSku> blockedSkus = new LinkedHashMap<>();
            int missingIndex = 0;
            for (PartitionSkuFact fact : entry.getValue()) {
                List<Issue> issues = new ArrayList<>();
                Sku sku = fact.skuId() == null ? null : skuById.get(fact.skuId());
                if (sku == null) {
                    issues.add(new Issue(
                            "SKU_REFERENCE_INVALID",
                            "履约分片缺少有效的内部 SKU 组件",
                            "修复订单行或礼包组件 SKU 后重新生成履约指令"));
                } else {
                    SkuFulfillmentReadiness evaluated = readinessBySku.get(sku.getId());
                    if (evaluated == null) {
                        issues.add(new Issue(
                                "SKU_REFERENCE_INVALID",
                                "内部 SKU 无法完成履约就绪判定",
                                "修复内部 SKU 主数据后重新生成履约指令"));
                    } else {
                        evaluated.issues().stream()
                                .map(issue -> new Issue(issue.code(), issue.message(), issue.action()))
                                .forEach(issues::add);
                    }
                    if (!Long.valueOf(entry.getKey().providerId()).equals(sku.getFulfillmentProviderId())) {
                        issues.add(new Issue(
                                "PROVIDER_ASSIGNMENT_CONFLICT",
                                "订单分片履约方与 SKU 归属履约方不一致",
                                "修复订单分片或 SKU 履约方归属后重新生成履约指令"));
                    }
                }
                if (!issues.isEmpty()) {
                    String key = fact.skuId() == null
                            ? "missing-" + missingIndex++
                            : Long.toString(fact.skuId());
                    blockedSkus.putIfAbsent(key, new BlockingSku(
                            fact.skuId(),
                            fact.skuCode(),
                            List.copyOf(issues)));
                }
            }
            if (!blockedSkus.isEmpty()) {
                PartitionKey key = entry.getKey();
                result.add(new BlockedPartition(
                        key.orderId(),
                        key.orderLineId(),
                        key.fulfillmentId(),
                        key.providerId(),
                        key.providerType(),
                        List.copyOf(blockedSkus.values())));
            }
        }
        return List.copyOf(result);
    }

    private void throwIfBlocked(List<BlockedPartition> blocked) {
        if (blocked.isEmpty()) {
            return;
        }
        throw new BusinessException(
                409,
                "PROVIDER_EXPORT_SKU_NOT_READY",
                "履约分片包含尚未达到履约就绪条件的 SKU",
                List.of(),
                Map.of(
                        "blocking_type", "SKU_READINESS",
                        "partitions", blocked.stream().map(BlockedPartition::asMap).toList()));
    }

    record BlockedPartition(
            long orderId,
            long orderLineId,
            long fulfillmentId,
            long providerId,
            String providerType,
            List<BlockingSku> skus) {

        List<String> reasonCodes() {
            LinkedHashSet<String> result = new LinkedHashSet<>();
            skus.forEach(sku -> sku.issues().forEach(issue -> result.add(issue.code())));
            return List.copyOf(result);
        }

        String primaryReasonCode() {
            return skus.getFirst().issues().getFirst().code();
        }

        String primaryMessage() {
            return skus.getFirst().issues().getFirst().message();
        }

        Map<String, Object> asMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("order_id", Long.toString(orderId));
            result.put("order_line_id", Long.toString(orderLineId));
            result.put("fulfillment_id", Long.toString(fulfillmentId));
            result.put("provider_id", Long.toString(providerId));
            result.put("provider_type", providerType);
            result.put("ready", false);
            result.put("reason_codes", reasonCodes());
            result.put("skus", skus.stream().map(BlockingSku::asMap).toList());
            return Map.copyOf(result);
        }
    }

    private record BlockingSku(Long skuId, String skuCode, List<Issue> issues) {
        Map<String, Object> asMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("sku_id", skuId == null ? null : Long.toString(skuId));
            result.put("sku_code", skuCode);
            result.put("reason_codes", issues.stream().map(Issue::code).distinct().toList());
            result.put("issues", issues.stream().map(Issue::asMap).toList());
            return result;
        }
    }

    private record Issue(String code, String message, String action) {
        Map<String, String> asMap() {
            return Map.of("code", code, "message", message, "action", action);
        }
    }

    private record PartitionSkuFact(
            long orderId,
            long orderLineId,
            long fulfillmentId,
            long providerId,
            String providerType,
            Long skuId,
            String skuCode) {

        PartitionKey key() {
            return new PartitionKey(orderId, orderLineId, fulfillmentId, providerId, providerType);
        }
    }

    private record PartitionKey(
            long orderId,
            long orderLineId,
            long fulfillmentId,
            long providerId,
            String providerType) {}
}
