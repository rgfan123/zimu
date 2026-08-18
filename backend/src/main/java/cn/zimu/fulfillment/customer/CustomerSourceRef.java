package cn.zimu.fulfillment.customer;

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

/** 来源渠道客户身份映射。 */
@Entity
@Table(name = "customer_source_refs")
public class CustomerSourceRef extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "customer_id", nullable = false)
    private Long customerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_channel", nullable = false)
    private SourceChannel sourceChannel;

    @Column(name = "source_customer_ref", nullable = false)
    private String sourceCustomerRef;

    public Long getId() {
        return id;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public void setCustomerId(Long customerId) {
        this.customerId = customerId;
    }

    public SourceChannel getSourceChannel() {
        return sourceChannel;
    }

    public void setSourceChannel(SourceChannel sourceChannel) {
        this.sourceChannel = sourceChannel;
    }

    public String getSourceCustomerRef() {
        return sourceCustomerRef;
    }

    public void setSourceCustomerRef(String sourceCustomerRef) {
        this.sourceCustomerRef = sourceCustomerRef;
    }
}
