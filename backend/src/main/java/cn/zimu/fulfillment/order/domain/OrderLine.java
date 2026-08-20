package cn.zimu.fulfillment.order.domain;

import cn.zimu.fulfillment.common.jpa.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

/** CanonicalOrder 商品行；权威 processing_stage 在行级。 */
@Entity
@Table(name = "order_lines")
public class OrderLine extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "line_no", nullable = false)
    private Integer lineNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "line_type", nullable = false)
    private LineType lineType;

    @Column(name = "sku_id")
    private Long skuId;

    /** 静态礼包主数据引用；当单定制礼包与普通 SKU 行为 null。 */
    @Column(name = "bundle_id")
    private Long bundleId;

    @Column(name = "fulfillment_provider_id")
    private Long fulfillmentProviderId;

    @Column(name = "product_name_snapshot", nullable = false)
    private String productNameSnapshot;

    @Column(name = "sku_code_snapshot")
    private String skuCodeSnapshot;

    @Column(name = "specification_snapshot", nullable = false)
    private String specificationSnapshot;

    @Column(name = "unit_snapshot", nullable = false)
    private String unitSnapshot;

    @Column(name = "source_quantity_snapshot")
    private BigDecimal sourceQuantitySnapshot;

    @Column(name = "mapping_multiplier_snapshot")
    private BigDecimal mappingMultiplierSnapshot;

    @Column(name = "requested_quantity", nullable = false)
    private BigDecimal requestedQuantity;

    @Enumerated(EnumType.STRING)
    @Column(name = "processing_stage", nullable = false)
    private ProcessingStage processingStage;

    @Column(name = "fulfillment_committed_at")
    private Instant fulfillmentCommittedAt;

    @Column(name = "exception_code")
    private String exceptionCode;

    @Column(name = "exception_reason")
    private String exceptionReason;

    public Long getId() {
        return id;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public Integer getLineNo() {
        return lineNo;
    }

    public void setLineNo(Integer lineNo) {
        this.lineNo = lineNo;
    }

    public LineType getLineType() {
        return lineType;
    }

    public void setLineType(LineType lineType) {
        this.lineType = lineType;
    }

    public Long getSkuId() {
        return skuId;
    }

    public void setSkuId(Long skuId) {
        this.skuId = skuId;
    }

    public Long getBundleId() {
        return bundleId;
    }

    public void setBundleId(Long bundleId) {
        this.bundleId = bundleId;
    }

    public Long getFulfillmentProviderId() {
        return fulfillmentProviderId;
    }

    public void setFulfillmentProviderId(Long fulfillmentProviderId) {
        this.fulfillmentProviderId = fulfillmentProviderId;
    }

    public String getProductNameSnapshot() {
        return productNameSnapshot;
    }

    public void setProductNameSnapshot(String productNameSnapshot) {
        this.productNameSnapshot = productNameSnapshot;
    }

    public String getSkuCodeSnapshot() {
        return skuCodeSnapshot;
    }

    public void setSkuCodeSnapshot(String skuCodeSnapshot) {
        this.skuCodeSnapshot = skuCodeSnapshot;
    }

    public String getSpecificationSnapshot() {
        return specificationSnapshot;
    }

    public void setSpecificationSnapshot(String specificationSnapshot) {
        this.specificationSnapshot = specificationSnapshot;
    }

    public String getUnitSnapshot() {
        return unitSnapshot;
    }

    public void setUnitSnapshot(String unitSnapshot) {
        this.unitSnapshot = unitSnapshot;
    }

    public BigDecimal getSourceQuantitySnapshot() {
        return sourceQuantitySnapshot;
    }

    public void setSourceQuantitySnapshot(BigDecimal sourceQuantitySnapshot) {
        this.sourceQuantitySnapshot = sourceQuantitySnapshot;
    }

    public BigDecimal getMappingMultiplierSnapshot() {
        return mappingMultiplierSnapshot;
    }

    public void setMappingMultiplierSnapshot(BigDecimal mappingMultiplierSnapshot) {
        this.mappingMultiplierSnapshot = mappingMultiplierSnapshot;
    }

    public BigDecimal getRequestedQuantity() {
        return requestedQuantity;
    }

    public void setRequestedQuantity(BigDecimal requestedQuantity) {
        this.requestedQuantity = requestedQuantity;
    }

    public ProcessingStage getProcessingStage() {
        return processingStage;
    }

    public void setProcessingStage(ProcessingStage processingStage) {
        this.processingStage = processingStage;
    }

    public Instant getFulfillmentCommittedAt() {
        return fulfillmentCommittedAt;
    }

    public void setFulfillmentCommittedAt(Instant fulfillmentCommittedAt) {
        this.fulfillmentCommittedAt = fulfillmentCommittedAt;
    }

    public String getExceptionCode() {
        return exceptionCode;
    }

    public void setExceptionCode(String exceptionCode) {
        this.exceptionCode = exceptionCode;
    }

    public String getExceptionReason() {
        return exceptionReason;
    }

    public void setExceptionReason(String exceptionReason) {
        this.exceptionReason = exceptionReason;
    }
}
