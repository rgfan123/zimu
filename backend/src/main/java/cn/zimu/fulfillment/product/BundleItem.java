package cn.zimu.fulfillment.product;

import cn.zimu.fulfillment.common.jpa.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/** 静态礼包当前 BOM 的一个组件。 */
@Entity
@Table(name = "bundle_items")
public class BundleItem extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "bundle_id", nullable = false)
    private Long bundleId;

    @Column(name = "sort_no", nullable = false)
    private int sortNo;

    @Column(name = "sku_id", nullable = false)
    private Long skuId;

    @Column(name = "quantity_per_bundle", precision = 18, scale = 3, nullable = false)
    private BigDecimal quantityPerBundle;

    @Column(name = "emg_code_snapshot")
    private String emgCodeSnapshot;

    @Column(name = "source_text_snapshot")
    private String sourceTextSnapshot;

    public Long getId() {
        return id;
    }

    public Long getBundleId() {
        return bundleId;
    }

    public void setBundleId(Long bundleId) {
        this.bundleId = bundleId;
    }

    public int getSortNo() {
        return sortNo;
    }

    public void setSortNo(int sortNo) {
        this.sortNo = sortNo;
    }

    public Long getSkuId() {
        return skuId;
    }

    public void setSkuId(Long skuId) {
        this.skuId = skuId;
    }

    public BigDecimal getQuantityPerBundle() {
        return quantityPerBundle;
    }

    public void setQuantityPerBundle(BigDecimal quantityPerBundle) {
        this.quantityPerBundle = quantityPerBundle;
    }

    public String getEmgCodeSnapshot() {
        return emgCodeSnapshot;
    }

    public void setEmgCodeSnapshot(String emgCodeSnapshot) {
        this.emgCodeSnapshot = emgCodeSnapshot;
    }

    public String getSourceTextSnapshot() {
        return sourceTextSnapshot;
    }

    public void setSourceTextSnapshot(String sourceTextSnapshot) {
        this.sourceTextSnapshot = sourceTextSnapshot;
    }
}
