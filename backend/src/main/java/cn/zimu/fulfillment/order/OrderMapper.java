package cn.zimu.fulfillment.order;

import cn.zimu.fulfillment.common.event.OrderEvent;
import cn.zimu.fulfillment.common.version.OrderVersion;
import cn.zimu.fulfillment.message.MessageModelMetadataRegistry;
import cn.zimu.fulfillment.message.MessagePublicProjectionSanitizer;
import cn.zimu.fulfillment.order.domain.Order;
import cn.zimu.fulfillment.order.domain.OrderLine;
import cn.zimu.fulfillment.order.domain.OrderLineComponent;
import cn.zimu.fulfillment.order.domain.ReviewCase;
import cn.zimu.fulfillment.order.domain.ReviewCaseStatus;
import cn.zimu.fulfillment.order.dto.OrderDetailDto;
import cn.zimu.fulfillment.order.dto.OrderEventDto;
import cn.zimu.fulfillment.order.dto.OrderLineComponentDto;
import cn.zimu.fulfillment.order.dto.OrderLineDto;
import cn.zimu.fulfillment.order.dto.OrderSummaryDto;
import cn.zimu.fulfillment.order.dto.OrderVersionDto;
import cn.zimu.fulfillment.order.dto.ReviewCaseDto;
import cn.zimu.fulfillment.order.dto.Receiver;
import cn.zimu.fulfillment.order.dto.Settlement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 订单实体与 DTO / JSONB 快照之间的映射。 */
@Component
public class OrderMapper {

    private final MessageModelMetadataRegistry metadataRegistry;

    public OrderMapper(MessageModelMetadataRegistry metadataRegistry) {
        this.metadataRegistry = metadataRegistry;
    }

    public OrderSummaryDto toSummary(Order order, String customerName, OrderQueryService.ViewProjection projection) {
        return new OrderSummaryDto(
                String.valueOf(order.getId()),
                order.getOrderNo(),
                order.getSourceChannel().name(),
                order.getSourceRef(),
                order.getCustomerId() == null ? null : String.valueOf(order.getCustomerId()),
                customerName,
                order.getReceiverName(),
                order.getOrderStatus().name(),
                projection.stage(),
                projection.health(),
                projection.completedCount(),
                projection.totalCount(),
                projection.attentionReason(),
                order.getCreatedAt(),
                order.getUpdatedAt(),
                order.getLockVersion());
    }

    public OrderDetailDto toDetail(
            Order order,
            List<OrderLine> lines,
            List<OrderLineComponent> components,
            List<ReviewCase> reviewCases,
            String customerName,
            Map<Long, String> skuCodes,
            OrderQueryService.ViewProjection projection) {
        Map<Long, List<OrderLineComponent>> componentsByLine = new LinkedHashMap<>();
        for (OrderLineComponent component : components) {
            componentsByLine
                    .computeIfAbsent(component.getOrderLineId(), key -> new java.util.ArrayList<>())
                    .add(component);
        }
        List<OrderLineDto> lineDtos = lines.stream()
                .map(line -> toLine(
                        line,
                        skuCodes,
                        componentsByLine.getOrDefault(line.getId(), List.of()).stream()
                                .map(this::toComponent)
                                .toList()))
                .toList();
        return new OrderDetailDto(
                String.valueOf(order.getId()),
                order.getOrderNo(),
                order.getSourceChannel().name(),
                order.getSourceRef(),
                order.getCustomerId() == null ? null : String.valueOf(order.getCustomerId()),
                customerName,
                order.getReceiverName(),
                order.getOrderStatus().name(),
                projection.stage(),
                projection.health(),
                projection.completedCount(),
                projection.totalCount(),
                projection.attentionReason(),
                order.getCreatedAt(),
                order.getUpdatedAt(),
                order.getLockVersion(),
                new Receiver(
                        order.getReceiverName(),
                        order.getReceiverPhone(),
                        null,
                        null,
                        null,
                        null,
                        order.getReceiverAddress()),
                new Settlement(order.getSettlementMethod(), order.getSettlementTime()),
                order.getRemark(),
                lineDtos,
                reviewCases.stream().map(this::toReviewCase).toList());
    }

    public OrderLineDto toLine(OrderLine line, Map<Long, String> skuCodes, List<OrderLineComponentDto> components) {
        return new OrderLineDto(
                String.valueOf(line.getId()),
                line.getLineNo(),
                line.getLineType().name(),
                line.getSkuId() == null ? null : String.valueOf(line.getSkuId()),
                skuCodes.get(line.getSkuId()),
                line.getFulfillmentProviderId() == null ? null : String.valueOf(line.getFulfillmentProviderId()),
                line.getProductNameSnapshot(),
                line.getSpecificationSnapshot(),
                line.getUnitSnapshot(),
                line.getSourceQuantitySnapshot() == null ? null : line.getSourceQuantitySnapshot().toPlainString(),
                line.getMappingMultiplierSnapshot() == null ? null : line.getMappingMultiplierSnapshot().toPlainString(),
                line.getRequestedQuantity().toPlainString(),
                line.getProcessingStage().name(),
                line.getExceptionCode(),
                components);
    }

    public OrderLineComponentDto toComponent(OrderLineComponent component) {
        return new OrderLineComponentDto(
                String.valueOf(component.getId()),
                String.valueOf(component.getSkuId()),
                component.getProductNameSnapshot(),
                component.getSpecificationSnapshot(),
                component.getUnitSnapshot(),
                component.getQuantityPerBundle().toPlainString(),
                component.getTotalQuantity().toPlainString());
    }

    public ReviewCaseDto toReviewCase(ReviewCase reviewCase) {
        String subjectType;
        String subjectId;
        if (reviewCase.getOrderLineId() != null) {
            subjectType = "ORDER_LINE";
            subjectId = String.valueOf(reviewCase.getOrderLineId());
        } else if (reviewCase.getShipmentId() != null) {
            subjectType = "SHIPMENT";
            subjectId = String.valueOf(reviewCase.getShipmentId());
        } else if (reviewCase.getOrderId() != null) {
            subjectType = "ORDER";
            subjectId = String.valueOf(reviewCase.getOrderId());
        } else if (reviewCase.getMessageSubmissionId() != null) {
            subjectType = "MESSAGE_SUBMISSION";
            subjectId = String.valueOf(reviewCase.getMessageSubmissionId());
        } else if (reviewCase.getOrderDraftId() != null) {
            subjectType = "ORDER_DRAFT";
            subjectId = String.valueOf(reviewCase.getOrderDraftId());
        } else if (reviewCase.getProviderTrackingDraftId() != null) {
            subjectType = "TRACKING_DRAFT";
            subjectId = String.valueOf(reviewCase.getProviderTrackingDraftId());
        } else {
            subjectType = "UNKNOWN";
            subjectId = null;
        }
        return new ReviewCaseDto(
                String.valueOf(reviewCase.getId()),
                reviewCase.getCaseNo(),
                reviewCase.getCaseType(),
                reviewCase.getResponsibleTeam(),
                reviewCase.getReasonCode(),
                reviewCase.getStatus().name(),
                reviewCase.getOrderId() == null ? null : String.valueOf(reviewCase.getOrderId()),
                reviewCase.getOrderLineId() == null ? null : String.valueOf(reviewCase.getOrderLineId()),
                subjectType,
                subjectId,
                MessagePublicProjectionSanitizer.reviewCaseDetail(
                        reviewCase.getMessageSubmissionId(),
                        reviewCase.getReasonCode(),
                        reviewCase.getDetail(),
                        metadataRegistry),
                List.of(),
                allowedActions(reviewCase),
                reviewCase.getResolution(),
                reviewCase.getResolvedBy(),
                reviewCase.getResolvedAt(),
                reviewCase.getResolutionVersion(),
                reviewCase.getCreatedAt());
    }

    public OrderEventDto toEvent(OrderEvent event) {
        return new OrderEventDto(
                String.valueOf(event.getId()),
                event.getSequenceNo(),
                event.getEventTypeCode(),
                event.getOrderLineId() == null ? null : String.valueOf(event.getOrderLineId()),
                event.getFulfillmentId() == null ? null : String.valueOf(event.getFulfillmentId()),
                event.getShipmentId() == null ? null : String.valueOf(event.getShipmentId()),
                event.getProcurementTicketId() == null ? null : String.valueOf(event.getProcurementTicketId()),
                event.getOperator(),
                event.getPayload(),
                event.getCreatedAt());
    }

    public OrderVersionDto toVersion(OrderVersion version) {
        return new OrderVersionDto(
                version.getVersionNo(),
                version.getSourceVersion(),
                version.getChangeReason(),
                version.getTriggeredBy(),
                version.getSnapshot(),
                version.getCreatedAt());
    }

    /** 构建 OrderVersion JSONB 快照：订单头、行、子状态与关联 id。 */
    public Map<String, Object> snapshot(
            Order order,
            List<OrderLine> lines,
            List<OrderLineComponent> components,
            List<ReviewCase> reviewCases,
            Map<Long, String> skuCodes,
            List<Map<String, Object>> fulfillments) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("order_no", order.getOrderNo());
        snapshot.put("source_channel", order.getSourceChannel().name());
        snapshot.put("source_ref", order.getSourceRef());
        snapshot.put("source_version", order.getSourceVersion());
        snapshot.put("order_status", order.getOrderStatus().name());
        snapshot.put("customer_id", order.getCustomerId());
        snapshot.put("correction_of_order_id", order.getCorrectionOfOrderId());
        snapshot.put("settlement_method", order.getSettlementMethod().name());
        snapshot.put("settlement_time", order.getSettlementTime().toString());
        snapshot.put(
                "receiver",
                Map.of(
                        "name", order.getReceiverName(),
                        "phone", order.getReceiverPhone(),
                        "address", order.getReceiverAddress()));
        snapshot.put("remark", order.getRemark());
        Map<Long, List<OrderLineComponent>> componentsByLine = new LinkedHashMap<>();
        for (OrderLineComponent component : components) {
            componentsByLine
                    .computeIfAbsent(component.getOrderLineId(), key -> new java.util.ArrayList<>())
                    .add(component);
        }
        List<Map<String, Object>> lineSnapshots = lines.stream()
                .map(line -> lineSnapshot(line, skuCodes, componentsByLine.getOrDefault(line.getId(), List.of())))
                .toList();
        snapshot.put("lines", lineSnapshots);
        snapshot.put(
                "review_cases",
                reviewCases.stream()
                        .map(caseItem -> {
                            Map<String, Object> caseSnapshot = new LinkedHashMap<>();
                            caseSnapshot.put("id", caseItem.getId());
                            caseSnapshot.put("reason_code", caseItem.getReasonCode());
                            caseSnapshot.put("status", caseItem.getStatus().name());
                            return caseSnapshot;
                        })
                        .toList());
        snapshot.put("fulfillments", fulfillments);
        return snapshot;
    }

    private Map<String, Object> lineSnapshot(
            OrderLine line, Map<Long, String> skuCodes, List<OrderLineComponent> components) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("line_no", line.getLineNo());
        snapshot.put("line_type", line.getLineType().name());
        snapshot.put("sku_id", line.getSkuId());
        snapshot.put("sku_code", skuCodes.get(line.getSkuId()));
        snapshot.put("provider_id", line.getFulfillmentProviderId());
        snapshot.put("product_name", line.getProductNameSnapshot());
        snapshot.put("specification", line.getSpecificationSnapshot());
        snapshot.put("unit", line.getUnitSnapshot());
        snapshot.put(
                "source_quantity",
                line.getSourceQuantitySnapshot() == null ? null : line.getSourceQuantitySnapshot().toPlainString());
        snapshot.put(
                "mapping_multiplier",
                line.getMappingMultiplierSnapshot() == null
                        ? null
                        : line.getMappingMultiplierSnapshot().toPlainString());
        snapshot.put("requested_quantity", line.getRequestedQuantity().toPlainString());
        snapshot.put("processing_stage", line.getProcessingStage().name());
        snapshot.put("fulfillment_committed_at", line.getFulfillmentCommittedAt());
        snapshot.put("components", components.stream().map(this::componentSnapshot).toList());
        return snapshot;
    }

    private Map<String, Object> componentSnapshot(OrderLineComponent component) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("component_no", component.getComponentNo());
        snapshot.put("sku_id", component.getSkuId());
        snapshot.put("quantity_per_bundle", component.getQuantityPerBundle().toPlainString());
        snapshot.put("total_quantity", component.getTotalQuantity().toPlainString());
        snapshot.put("product_name", component.getProductNameSnapshot());
        snapshot.put("specification", component.getSpecificationSnapshot());
        snapshot.put("unit", component.getUnitSnapshot());
        return snapshot;
    }

    private List<String> allowedActions(ReviewCase reviewCase) {
        if (reviewCase.getStatus() != ReviewCaseStatus.OPEN) return List.of();
        return switch (reviewCase.getReasonCode()) {
            case "CUSTOMER_MATCH_REQUIRED" -> List.of("RESOLVE_CUSTOMER", "DISMISS");
            case "SKU_MAPPING_REQUIRED", "MAPPING_MULTIPLIER" -> List.of("RESOLVE_SKU", "DISMISS");
            case "JD_SKU_MAPPING_BLOCKED" ->
                List.of("OPEN_SKU_MAPPING", "RERUN_JD_SKU_MAPPING_CHECK", "DISMISS");
            case "JD_STOCK_BLOCKED" -> List.of("RERUN_JD_STOCK_CHECK", "RESOLVE_MANUALLY", "DISMISS");
            case "MULTIPLE_TRACKINGS_FOR_OUTBOUND", "JD_TRACKING_CARRIER_MAPPING_REQUIRED",
                    "JD_TRACKING_TERMINAL_EXCEPTION" ->
                List.of("RESOLVE_JD_TRACKING_CONFLICT", "DISMISS");
            case "MULTI_SHIPMENT_SOURCE_FOLLOWUP" -> List.of("COMPLETE_SOURCE_FOLLOWUP", "DISMISS");
            case "WECOM_ORDER_DRAFT" -> List.of("CONFIRM_ORDER_DRAFT", "REJECT_ORDER_DRAFT");
            case "WECOM_TRACKING_DRAFT" -> List.of("CONFIRM_TRACKING_DRAFT", "REJECT_TRACKING_DRAFT");
            case "WECOM_NEED_REVIEW", "WECOM_ORDER_CHANGE", "WECOM_ORDER_CANCEL" ->
                List.of("REINTERPRET", "REJECT", "RESOLVE_MANUALLY");
            case "SKU_MAPPING_CONFLICT", "REVISION_AFTER_EXPORT", "QUANTITY_SCALE",
                    "FULFILLMENT_EXCEPTION", "SYNC_FAILED", "IMPORT_DATA", "CARRIER_MAPPING",
                    "SOURCE_SKU_MAPPING_REQUIRED", "PROVIDER_SKU_MAPPING_REQUIRED" ->
                List.of("RESOLVE_MANUALLY", "DISMISS");
            default -> List.of("DISMISS");
        };
    }
}
