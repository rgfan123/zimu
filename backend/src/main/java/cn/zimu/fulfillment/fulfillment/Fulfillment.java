package cn.zimu.fulfillment.fulfillment;

import cn.zimu.fulfillment.common.jpa.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;

/** 一条订单行的履约单元（1:1）；后续履约票在本实体上扩展状态机。 */
@Entity
@Table(name = "fulfillments")
public class Fulfillment extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "fulfillment_no", nullable = false)
    private String fulfillmentNo;

    @Column(name = "order_line_id", nullable = false)
    private Long orderLineId;

    @Column(name = "fulfillment_provider_id", nullable = false)
    private Long fulfillmentProviderId;

    @Column(name = "requested_quantity", nullable = false)
    private BigDecimal requestedQuantity;

    @Column(name = "cumulative_shipped_quantity", nullable = false)
    private BigDecimal cumulativeShippedQuantity = BigDecimal.ZERO;

    @Column(name = "cancelled_quantity", nullable = false)
    private BigDecimal cancelledQuantity = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "shipping_progress", nullable = false)
    private ShippingProgress shippingProgress = ShippingProgress.NOT_SHIPPED;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false)
    private FulfillmentOutcome outcome = FulfillmentOutcome.IN_PROGRESS;

    @Column(name = "exception_code")
    private String exceptionCode;

    @Column(name = "exception_reason")
    private String exceptionReason;

    @Version
    @Column(name = "lock_version", nullable = false)
    private Long lockVersion = 0L;

    public Long getId() {
        return id;
    }

    public String getFulfillmentNo() {
        return fulfillmentNo;
    }

    public void setFulfillmentNo(String fulfillmentNo) {
        this.fulfillmentNo = fulfillmentNo;
    }

    public Long getOrderLineId() {
        return orderLineId;
    }

    public void setOrderLineId(Long orderLineId) {
        this.orderLineId = orderLineId;
    }

    public Long getFulfillmentProviderId() {
        return fulfillmentProviderId;
    }

    public void setFulfillmentProviderId(Long fulfillmentProviderId) {
        this.fulfillmentProviderId = fulfillmentProviderId;
    }

    public BigDecimal getRequestedQuantity() {
        return requestedQuantity;
    }

    public void setRequestedQuantity(BigDecimal requestedQuantity) {
        this.requestedQuantity = requestedQuantity;
    }

    public BigDecimal getCumulativeShippedQuantity() {
        return cumulativeShippedQuantity;
    }

    public void setCumulativeShippedQuantity(BigDecimal cumulativeShippedQuantity) {
        this.cumulativeShippedQuantity = cumulativeShippedQuantity;
    }

    public BigDecimal getCancelledQuantity() {
        return cancelledQuantity;
    }

    public void setCancelledQuantity(BigDecimal cancelledQuantity) {
        this.cancelledQuantity = cancelledQuantity;
    }

    public ShippingProgress getShippingProgress() {
        return shippingProgress;
    }

    public void setShippingProgress(ShippingProgress shippingProgress) {
        this.shippingProgress = shippingProgress;
    }

    public FulfillmentOutcome getOutcome() {
        return outcome;
    }

    public void setOutcome(FulfillmentOutcome outcome) {
        this.outcome = outcome;
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

    public Long getLockVersion() {
        return lockVersion;
    }
}
