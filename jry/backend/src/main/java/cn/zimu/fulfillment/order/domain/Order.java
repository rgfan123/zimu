package cn.zimu.fulfillment.order.domain;

import cn.zimu.fulfillment.common.domain.DataScope;
import cn.zimu.fulfillment.common.domain.SourceChannel;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 长期 CanonicalOrder 头。 */
@Entity
@Table(name = "orders")
public class Order extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_no", nullable = false)
    private String orderNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "data_scope", nullable = false)
    private DataScope dataScope;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_channel", nullable = false)
    private SourceChannel sourceChannel;

    @Column(name = "source_ref", nullable = false)
    private String sourceRef;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_ref_kind", nullable = false)
    private SourceRefKind sourceRefKind;

    @Column(name = "source_version")
    private String sourceVersion;

    @Column(name = "source_import_batch_id")
    private Long sourceImportBatchId;

    @Column(name = "customer_id")
    private Long customerId;

    @Column(name = "correction_of_order_id")
    private Long correctionOfOrderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_status", nullable = false)
    private OrderStatus orderStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "settlement_method", nullable = false)
    private SettlementMethod settlementMethod;

    @Column(name = "settlement_time", nullable = false)
    private Instant settlementTime;

    @Column(name = "receiver_name", nullable = false)
    private String receiverName;

    @Column(name = "receiver_phone", nullable = false)
    private String receiverPhone;

    @Column(name = "receiver_address", nullable = false)
    private String receiverAddress;

    @Column(name = "remark")
    private String remark;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence_refs", nullable = false)
    private List<String> evidenceRefs = new ArrayList<>();

    @Version
    @Column(name = "lock_version", nullable = false)
    private Long lockVersion = 0L;

    public Long getId() {
        return id;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public void setOrderNo(String orderNo) {
        this.orderNo = orderNo;
    }

    public DataScope getDataScope() {
        return dataScope;
    }

    public void setDataScope(DataScope dataScope) {
        this.dataScope = dataScope;
    }

    public SourceChannel getSourceChannel() {
        return sourceChannel;
    }

    public void setSourceChannel(SourceChannel sourceChannel) {
        this.sourceChannel = sourceChannel;
    }

    public String getSourceRef() {
        return sourceRef;
    }

    public void setSourceRef(String sourceRef) {
        this.sourceRef = sourceRef;
    }

    public SourceRefKind getSourceRefKind() {
        return sourceRefKind;
    }

    public void setSourceRefKind(SourceRefKind sourceRefKind) {
        this.sourceRefKind = sourceRefKind;
    }

    public String getSourceVersion() {
        return sourceVersion;
    }

    public void setSourceVersion(String sourceVersion) {
        this.sourceVersion = sourceVersion;
    }

    public Long getSourceImportBatchId() {
        return sourceImportBatchId;
    }

    public void setSourceImportBatchId(Long sourceImportBatchId) {
        this.sourceImportBatchId = sourceImportBatchId;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public void setCustomerId(Long customerId) {
        this.customerId = customerId;
    }

    public Long getCorrectionOfOrderId() {
        return correctionOfOrderId;
    }

    public void setCorrectionOfOrderId(Long correctionOfOrderId) {
        this.correctionOfOrderId = correctionOfOrderId;
    }

    public OrderStatus getOrderStatus() {
        return orderStatus;
    }

    public void setOrderStatus(OrderStatus orderStatus) {
        this.orderStatus = orderStatus;
    }

    public SettlementMethod getSettlementMethod() {
        return settlementMethod;
    }

    public void setSettlementMethod(SettlementMethod settlementMethod) {
        this.settlementMethod = settlementMethod;
    }

    public Instant getSettlementTime() {
        return settlementTime;
    }

    public void setSettlementTime(Instant settlementTime) {
        this.settlementTime = settlementTime;
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

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public List<String> getEvidenceRefs() {
        return evidenceRefs;
    }

    public void setEvidenceRefs(List<String> evidenceRefs) {
        this.evidenceRefs = evidenceRefs;
    }

    public Long getLockVersion() {
        return lockVersion;
    }
}
