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

/**
 * 静态礼包 BOM 主数据（static-bundle-bom 02 票定稿）。
 * 礼包 = 商品族属性（本表）+ 组件清单（bundle_items）；礼包本身不创建 SKU、不单独计库存；
 * 订单命中时下单快照 BOM 到 order_line_components，主数据后续修改不影响历史订单。
 */
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

    /** 礼包级履约方：由 bundle_items 触发器推导维护；NULL=组件未齐。 */
    @Column(name = "fulfillment_provider_id")
    private Long fulfillmentProviderId;

    /** 上架状态：DRAFT=组件未齐不可被订单命中；ACTIVE=可识别命中；INACTIVE=下架。 */
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

    public void setCategoryId(Long categoryId) {
        this.categoryId = categoryId;
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

    public void setTaxRate(BigDecimal taxRate) {
        this.taxRate = taxRate;
    }

    public BigDecimal getSettlementCost() {
        return settlementCost;
    }

    public void setSettlementCost(BigDecimal settlementCost) {
        this.settlementCost = settlementCost;
    }

    public Long getFulfillmentProviderId() {
        return fulfillmentProviderId;
    }

    public void setFulfillmentProviderId(Long fulfillmentProviderId) {
        this.fulfillmentProviderId = fulfillmentProviderId;
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
