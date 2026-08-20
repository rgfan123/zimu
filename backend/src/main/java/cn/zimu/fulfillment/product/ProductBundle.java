package cn.zimu.fulfillment.product;

import cn.zimu.fulfillment.common.jpa.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;

/** 静态礼包商品族；可履约组件保存在 bundle_items。 */
@Entity
@Table(name = "product_bundles")
public class ProductBundle extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "bundle_code", nullable = false)
    private String bundleCode;

    @Column(name = "bundle_name", nullable = false)
    private String bundleName;

    @Column(name = "category_id")
    private Long categoryId;

    @Column(name = "barcode")
    private String barcode;

    @Column(name = "description")
    private String description;

    @Column(name = "tax_rate", precision = 5, scale = 2)
    private BigDecimal taxRate;

    @Column(name = "settlement_cost", precision = 14, scale = 2)
    private BigDecimal settlementCost;

    @Column(name = "fulfillment_provider_id")
    private Long fulfillmentProviderId;

    @Column(name = "status", nullable = false)
    private String status = "DRAFT";

    @Version
    @Column(name = "lock_version", nullable = false)
    private Long lockVersion = 0L;

    public Long getId() {
        return id;
    }

    public String getBundleCode() {
        return bundleCode;
    }

    public void setBundleCode(String bundleCode) {
        this.bundleCode = bundleCode;
    }

    public String getBundleName() {
        return bundleName;
    }

    public void setBundleName(String bundleName) {
        this.bundleName = bundleName;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public String getBarcode() {
        return barcode;
    }

    public void setBarcode(String barcode) {
        this.barcode = barcode;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public BigDecimal getTaxRate() {
        return taxRate;
    }

    public BigDecimal getSettlementCost() {
        return settlementCost;
    }

    public Long getFulfillmentProviderId() {
        return fulfillmentProviderId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getLockVersion() {
        return lockVersion;
    }
}
