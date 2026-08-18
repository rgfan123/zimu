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
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 阻断自动履约的人工复核事项。 */
@Entity
@Table(name = "review_cases")
public class ReviewCase extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "case_no", nullable = false)
    private String caseNo;

    @Column(name = "case_type", nullable = false)
    private String caseType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private ReviewCaseStatus status;

    @Column(name = "responsible_team", nullable = false)
    private String responsibleTeam;

    @Column(name = "reason_code", nullable = false)
    private String reasonCode;

    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "order_line_id")
    private Long orderLineId;

    @Column(name = "fulfillment_id")
    private Long fulfillmentId;

    @Column(name = "shipment_id")
    private Long shipmentId;

    @Column(name = "import_batch_id")
    private Long importBatchId;

    @Column(name = "raw_import_row_id")
    private Long rawImportRowId;

    @Column(name = "message_submission_id")
    private Long messageSubmissionId;

    @Column(name = "order_draft_id")
    private Long orderDraftId;

    @Column(name = "provider_tracking_draft_id")
    private Long providerTrackingDraftId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detail", nullable = false)
    private Map<String, Object> detail = new LinkedHashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "resolution")
    private Map<String, Object> resolution;

    @Version
    @Column(name = "resolution_version", nullable = false)
    private Long resolutionVersion = 0L;

    @Column(name = "resolved_by")
    private String resolvedBy;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    public Long getId() {
        return id;
    }

    public String getCaseNo() {
        return caseNo;
    }

    public void setCaseNo(String caseNo) {
        this.caseNo = caseNo;
    }

    public String getCaseType() {
        return caseType;
    }

    public void setCaseType(String caseType) {
        this.caseType = caseType;
    }

    public ReviewCaseStatus getStatus() {
        return status;
    }

    public void setStatus(ReviewCaseStatus status) {
        this.status = status;
    }

    public String getResponsibleTeam() {
        return responsibleTeam;
    }

    public void setResponsibleTeam(String responsibleTeam) {
        this.responsibleTeam = responsibleTeam;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public void setReasonCode(String reasonCode) {
        this.reasonCode = reasonCode;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public Long getOrderLineId() {
        return orderLineId;
    }

    public void setOrderLineId(Long orderLineId) {
        this.orderLineId = orderLineId;
    }

    public Long getFulfillmentId() {
        return fulfillmentId;
    }

    public void setFulfillmentId(Long fulfillmentId) {
        this.fulfillmentId = fulfillmentId;
    }

    public Long getShipmentId() {
        return shipmentId;
    }

    public void setShipmentId(Long shipmentId) {
        this.shipmentId = shipmentId;
    }

    public Long getImportBatchId() {
        return importBatchId;
    }

    public void setImportBatchId(Long importBatchId) {
        this.importBatchId = importBatchId;
    }

    public Long getRawImportRowId() {
        return rawImportRowId;
    }

    public void setRawImportRowId(Long rawImportRowId) {
        this.rawImportRowId = rawImportRowId;
    }

    public Long getMessageSubmissionId() {
        return messageSubmissionId;
    }

    public void setMessageSubmissionId(Long messageSubmissionId) {
        this.messageSubmissionId = messageSubmissionId;
    }

    public Long getOrderDraftId() {
        return orderDraftId;
    }

    public void setOrderDraftId(Long orderDraftId) {
        this.orderDraftId = orderDraftId;
    }

    public Long getProviderTrackingDraftId() {
        return providerTrackingDraftId;
    }

    public void setProviderTrackingDraftId(Long providerTrackingDraftId) {
        this.providerTrackingDraftId = providerTrackingDraftId;
    }

    public Map<String, Object> getDetail() {
        return detail;
    }

    public void setDetail(Map<String, Object> detail) {
        this.detail = detail;
    }

    public Map<String, Object> getResolution() {
        return resolution;
    }

    public void setResolution(Map<String, Object> resolution) {
        this.resolution = resolution;
    }

    public Long getResolutionVersion() {
        return resolutionVersion;
    }

    public String getResolvedBy() {
        return resolvedBy;
    }

    public void setResolvedBy(String resolvedBy) {
        this.resolvedBy = resolvedBy;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public void setResolvedAt(Instant resolvedAt) {
        this.resolvedAt = resolvedAt;
    }
}
