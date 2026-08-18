package cn.zimu.fulfillment.sku;

import cn.zimu.fulfillment.common.jpa.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;

/** 公司内部唯一可履约 SKU；编码与 provider 由数据库触发器维护。 */
@Entity
@Table(name = "skus")
public class Sku extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sku_sequence_no", insertable = false, updatable = false)
    private Long skuSequenceNo;

    @Column(name = "sku_code", insertable = false, updatable = false)
    private String skuCode;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "fulfillment_provider_id", nullable = false)
    private Long fulfillmentProviderId;

    @Column(name = "specification", nullable = false)
    private String specification;

    @Column(name = "unit", nullable = false)
    private String unit;

    @Column(name = "barcode")
    private String barcode;

    @Column(name = "purchase_price", precision = 14, scale = 2)
    private BigDecimal purchasePrice;

    @Column(name = "retail_price", precision = 14, scale = 2)
    private BigDecimal retailPrice;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Version
    @Column(name = "lock_version", nullable = false)
    private Long lockVersion = 0L;

    public Long getId() {
        return id;
    }

    public Long getSkuSequenceNo() {
        return skuSequenceNo;
    }

    public String getSkuCode() {
        return skuCode;
    }

    public Long getProductId() {
        return productId;
    }

    public void setProductId(Long productId) {
        this.productId = productId;
    }

    public Long getFulfillmentProviderId() {
        return fulfillmentProviderId;
    }

    public void setFulfillmentProviderId(Long fulfillmentProviderId) {
        this.fulfillmentProviderId = fulfillmentProviderId;
    }

    public String getSpecification() {
        return specification;
    }

    public void setSpecification(String specification) {
        this.specification = specification;
    }

    public String getUnit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    public String getBarcode() {
        return barcode;
    }

    public void setBarcode(String barcode) {
        this.barcode = barcode;
    }

    public BigDecimal getPurchasePrice() {
        return purchasePrice;
    }

    public void setPurchasePrice(BigDecimal purchasePrice) {
        this.purchasePrice = purchasePrice;
    }

    public BigDecimal getRetailPrice() {
        return retailPrice;
    }

    public void setRetailPrice(BigDecimal retailPrice) {
        this.retailPrice = retailPrice;
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
