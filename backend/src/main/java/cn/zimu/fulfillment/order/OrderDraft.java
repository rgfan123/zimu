package cn.zimu.fulfillment.order;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 从渠道消息提取出的、仍待补充或确认的结构化下单建议（企微一期）。
 *
 * <p>Customer/SKU 候选只来自确定性映射；模型原值保留在各原始列与复核事项 detail 中，
 * 不把任何内部 ID 视为已确认事实。确认/拒绝后 {@link #revision} 随 @Version 递增。
 */
@Entity
@Table(name = "order_drafts")
public class OrderDraft extends AuditableEntity {

    public enum Status {
        OPEN,
        CONFIRMED,
        REJECTED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "draft_no", nullable = false)
    private String draftNo;

    @Column(name = "submission_id", nullable = false)
    private Long submissionId;

    @Column(name = "source_order_no", nullable = false)
    private String sourceOrderNo;

    @Column(name = "customer_id")
    private Long customerId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "customer_candidates", nullable = false)
    private List<Map<String, Object>> customerCandidates = new ArrayList<>();

    @Column(name = "customer_name_raw")
    private String customerNameRaw;

    @Column(name = "receiver_name")
    private String receiverName;

    @Column(name = "receiver_phone")
    private String receiverPhone;

    @Column(name = "receiver_address")
    private String receiverAddress;

    @Column(name = "settlement_method")
    private String settlementMethod;

    @Column(name = "settlement_time")
    private Instant settlementTime;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "missing_fields", nullable = false)
    private List<String> missingFields = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private Status status = Status.OPEN;

    @Version
    @Column(name = "revision", nullable = false)
    private Long revision = 0L;

    @Column(name = "confirmed_by")
    private String confirmedBy;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    public Long getId() {
        return id;
    }

    public String getDraftNo() {
        return draftNo;
    }

    public void setDraftNo(String draftNo) {
        this.draftNo = draftNo;
    }

    public Long getSubmissionId() {
        return submissionId;
    }

    public void setSubmissionId(Long submissionId) {
        this.submissionId = submissionId;
    }

    public String getSourceOrderNo() {
        return sourceOrderNo;
    }

    public void setSourceOrderNo(String sourceOrderNo) {
        this.sourceOrderNo = sourceOrderNo;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public void setCustomerId(Long customerId) {
        this.customerId = customerId;
    }

    public List<Map<String, Object>> getCustomerCandidates() {
        return customerCandidates;
    }

    public void setCustomerCandidates(List<Map<String, Object>> customerCandidates) {
        this.customerCandidates = customerCandidates;
    }

    public String getCustomerNameRaw() {
        return customerNameRaw;
    }

    public void setCustomerNameRaw(String customerNameRaw) {
        this.customerNameRaw = customerNameRaw;
    }

    public String getReceiverName() {
        return receiverName;
    }

    public void setReceiverName(String receiverName) {
        this.receiverName = receiverName;
    }

    public String getReceiverPhone() {
        return receiverPhone;
    }

    public void setReceiverPhone(String receiverPhone) {
        this.receiverPhone = receiverPhone;
    }

    public String getReceiverAddress() {
        return receiverAddress;
    }

    public void setReceiverAddress(String receiverAddress) {
        this.receiverAddress = receiverAddress;
    }

    public String getSettlementMethod() {
        return settlementMethod;
    }

    public void setSettlementMethod(String settlementMethod) {
        this.settlementMethod = settlementMethod;
    }

    public Instant getSettlementTime() {
        return settlementTime;
    }

    public void setSettlementTime(Instant settlementTime) {
        this.settlementTime = settlementTime;
    }

    public List<String> getMissingFields() {
        return missingFields;
    }

    public void setMissingFields(List<String> missingFields) {
        this.missingFields = missingFields;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public Long getRevision() {
        return revision;
    }

    public String getConfirmedBy() {
        return confirmedBy;
    }

    public void setConfirmedBy(String confirmedBy) {
        this.confirmedBy = confirmedBy;
    }

    public Instant getConfirmedAt() {
        return confirmedAt;
    }

    public void setConfirmedAt(Instant confirmedAt) {
        this.confirmedAt = confirmedAt;
    }
}
