package cn.zimu.fulfillment.sku;

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

/** 来源平台商品到内部 SKU 的显式映射。 */
@Entity
@Table(name = "source_channel_skus")
public class SourceChannelSku extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_channel", nullable = false)
    private SourceChannel sourceChannel;

    @Column(name = "source_sku_ref", nullable = false)
    private String sourceSkuRef;

    @Column(name = "source_product_name")
    private String sourceProductName;

    @Column(name = "source_specification")
    private String sourceSpecification;

    @Column(name = "quantity_multiplier")
    private Integer quantityMultiplier;

    @Column(name = "sku_id", nullable = false)
    private Long skuId;

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

    public String getSourceSkuRef() {
        return sourceSkuRef;
    }

    public void setSourceSkuRef(String sourceSkuRef) {
        this.sourceSkuRef = sourceSkuRef;
    }

    public String getSourceProductName() {
        return sourceProductName;
    }

    public void setSourceProductName(String sourceProductName) {
        this.sourceProductName = sourceProductName;
    }

    public String getSourceSpecification() {
        return sourceSpecification;
    }

    public void setSourceSpecification(String sourceSpecification) {
        this.sourceSpecification = sourceSpecification;
    }

    public Integer getQuantityMultiplier() {
        return quantityMultiplier;
    }

    public void setQuantityMultiplier(Integer quantityMultiplier) {
        this.quantityMultiplier = quantityMultiplier;
    }

    public Long getSkuId() {
        return skuId;
    }

    public void setSkuId(Long skuId) {
        this.skuId = skuId;
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
