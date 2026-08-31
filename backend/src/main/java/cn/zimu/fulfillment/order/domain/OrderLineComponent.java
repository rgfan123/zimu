package cn.zimu.fulfillment.order.domain;

import cn.zimu.fulfillment.common.jpa.CreatedAtEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 当单礼包组件快照（不可变业务事实）。 */
@Entity
@Table(name = "order_line_components")
public class OrderLineComponent extends CreatedAtEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_line_id", nullable = false)
    private Long orderLineId;

    @Column(name = "component_no", nullable = false)
    private Integer componentNo;

    @Column(name = "sku_id", nullable = false)
    private Long skuId;

    @Column(name = "quantity_per_bundle", nullable = false)
    private Integer quantityPerBundle;

    @Column(name = "total_quantity", nullable = false)
    private Integer totalQuantity;

    @Column(name = "product_name_snapshot", nullable = false)
    private String productNameSnapshot;

    @Column(name = "specification_snapshot", nullable = false)
    private String specificationSnapshot;

    @Column(name = "unit_snapshot", nullable = false)
    private String unitSnapshot;

    public Long getId() {
        return id;
    }

    public Long getOrderLineId() {
        return orderLineId;
    }

    public void setOrderLineId(Long orderLineId) {
        this.orderLineId = orderLineId;
    }

    public Integer getComponentNo() {
        return componentNo;
    }

    public void setComponentNo(Integer componentNo) {
        this.componentNo = componentNo;
    }

    public Long getSkuId() {
        return skuId;
    }

    public void setSkuId(Long skuId) {
        this.skuId = skuId;
    }

    public Integer getQuantityPerBundle() {
        return quantityPerBundle;
    }

    public void setQuantityPerBundle(Integer quantityPerBundle) {
        this.quantityPerBundle = quantityPerBundle;
    }

    public Integer getTotalQuantity() {
        return totalQuantity;
    }

    public void setTotalQuantity(Integer totalQuantity) {
        this.totalQuantity = totalQuantity;
    }

    public String getProductNameSnapshot() {
        return productNameSnapshot;
    }

    public void setProductNameSnapshot(String productNameSnapshot) {
        this.productNameSnapshot = productNameSnapshot;
    }

    public String getSpecificationSnapshot() {
        return specificationSnapshot;
    }

    public void setSpecificationSnapshot(String specificationSnapshot) {
        this.specificationSnapshot = specificationSnapshot;
    }

    public String getUnitSnapshot() {
        return unitSnapshot;
    }

    public void setUnitSnapshot(String unitSnapshot) {
        this.unitSnapshot = unitSnapshot;
    }
}
