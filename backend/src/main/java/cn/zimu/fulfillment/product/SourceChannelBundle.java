package cn.zimu.fulfillment.product;

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

/** 渠道礼包编号到静态礼包主数据的显式业务映射。 */
@Entity
@Table(name = "source_channel_bundles")
public class SourceChannelBundle extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_channel", nullable = false)
    private SourceChannel sourceChannel;

    @Column(name = "source_bundle_ref", nullable = false)
    private String sourceBundleRef;

    @Column(name = "source_bundle_name")
    private String sourceBundleName;

    @Column(name = "source_barcode")
    private String sourceBarcode;

    @Column(name = "quantity_multiplier", nullable = false)
    private Integer quantityMultiplier = 1;

    @Column(name = "bundle_id", nullable = false)
    private Long bundleId;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Version
    @Column(name = "lock_version", nullable = false)
    private Long lockVersion = 0L;

    public Long getId() {
        return id;
    }

    public SourceChannel getSourceChannel() {
        return sourceChannel;
    }

    public void setSourceChannel(SourceChannel sourceChannel) {
        this.sourceChannel = sourceChannel;
    }

    public String getSourceBundleRef() {
        return sourceBundleRef;
    }

    public void setSourceBundleRef(String sourceBundleRef) {
        this.sourceBundleRef = sourceBundleRef;
    }

    public String getSourceBundleName() {
        return sourceBundleName;
    }

    public void setSourceBundleName(String sourceBundleName) {
        this.sourceBundleName = sourceBundleName;
    }

    public String getSourceBarcode() {
        return sourceBarcode;
    }

    public void setSourceBarcode(String sourceBarcode) {
        this.sourceBarcode = sourceBarcode;
    }

    public Integer getQuantityMultiplier() {
        return quantityMultiplier;
    }

    public void setQuantityMultiplier(Integer quantityMultiplier) {
        this.quantityMultiplier = quantityMultiplier;
    }

    public Long getBundleId() {
        return bundleId;
    }

    public void setBundleId(Long bundleId) {
        this.bundleId = bundleId;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public Long getLockVersion() {
        return lockVersion;
    }
}
