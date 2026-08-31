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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 履约方运单回传草稿：消息逐行提取的收货人姓名、物流公司、快递单号与可选系统发货任务号。
 *
 * <p>一行一个草稿；task_candidates / carrier_candidates 只保存确定性主数据或候选范围形成的候选，
 * 模型输出本身不直接成为已确认的 task_id / carrier_code。状态由人工确认命令从 OPEN 推进到
 * CONFIRMED / REJECTED。
 */
@Entity
@Table(name = "provider_tracking_drafts")
public class ProviderTrackingDraft extends AuditableEntity {

    public enum Status {
        OPEN,
        CONFIRMED,
        REJECTED
    }

    public enum ShipmentJudgment {
        FULL,
        PARTIAL,
        SHORTAGE,
        EXCEPTION
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "draft_no", nullable = false)
    private String draftNo;

    @Column(name = "submission_id", nullable = false)
    private Long submissionId;

    @Column(name = "line_no", nullable = false)
    private Integer lineNo;

    @Column(name = "raw_receiver_name")
    private String rawReceiverName;

    @Column(name = "masked_receiver_name")
    private String maskedReceiverName;

    @Column(name = "tracking_no")
    private String trackingNo;

    @Column(name = "carrier_code")
    private String carrierCode;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "carrier_candidates", nullable = false)
    private List<Map<String, Object>> carrierCandidates = new ArrayList<>();

    @Column(name = "task_id")
    private Long taskId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "task_candidates", nullable = false)
    private List<Map<String, Object>> taskCandidates = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "shipment_judgment", nullable = false)
    private ShipmentJudgment shipmentJudgment = ShipmentJudgment.FULL;

    @Column(name = "actual_quantity")
    private Integer actualQuantity;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "validation_issues", nullable = false)
    private List<String> validationIssues = new ArrayList<>();

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

    public Integer getLineNo() {
        return lineNo;
    }

    public void setLineNo(Integer lineNo) {
        this.lineNo = lineNo;
    }

    public String getRawReceiverName() {
        return rawReceiverName;
    }

    public void setRawReceiverName(String rawReceiverName) {
        this.rawReceiverName = rawReceiverName;
    }

    public String getMaskedReceiverName() {
        return maskedReceiverName;
    }

    public void setMaskedReceiverName(String maskedReceiverName) {
        this.maskedReceiverName = maskedReceiverName;
    }

    public String getTrackingNo() {
        return trackingNo;
    }

    public void setTrackingNo(String trackingNo) {
        this.trackingNo = trackingNo;
    }

    public String getCarrierCode() {
        return carrierCode;
    }

    public void setCarrierCode(String carrierCode) {
        this.carrierCode = carrierCode;
    }

    public List<Map<String, Object>> getCarrierCandidates() {
        return carrierCandidates;
    }

    public void setCarrierCandidates(List<Map<String, Object>> carrierCandidates) {
        this.carrierCandidates = carrierCandidates;
    }

    public Long getTaskId() {
        return taskId;
    }

    public void setTaskId(Long taskId) {
        this.taskId = taskId;
    }

    public List<Map<String, Object>> getTaskCandidates() {
        return taskCandidates;
    }

    public void setTaskCandidates(List<Map<String, Object>> taskCandidates) {
        this.taskCandidates = taskCandidates;
    }

    public ShipmentJudgment getShipmentJudgment() {
        return shipmentJudgment;
    }

    public void setShipmentJudgment(ShipmentJudgment shipmentJudgment) {
        this.shipmentJudgment = shipmentJudgment;
    }

    public Integer getActualQuantity() {
        return actualQuantity;
    }

    public void setActualQuantity(Integer actualQuantity) {
        this.actualQuantity = actualQuantity;
    }

    public List<String> getValidationIssues() {
        return validationIssues;
    }

    public void setValidationIssues(List<String> validationIssues) {
        this.validationIssues = validationIssues;
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
