package cn.zimu.fulfillment.order;

import cn.zimu.fulfillment.common.jpa.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 订单草稿行：模型原值 + SKU 候选；商品名称/规格/单位以确认后的 SKU 主数据为准。 */
@Entity
@Table(name = "order_draft_lines")
public class OrderDraftLine extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_draft_id", nullable = false)
    private Long orderDraftId;

    @Column(name = "line_no", nullable = false)
    private Integer lineNo;

    @Column(name = "sku_id")
    private Long skuId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "sku_candidates", nullable = false)
    private List<Map<String, Object>> skuCandidates = new ArrayList<>();

    @Column(name = "product_name_raw")
    private String productNameRaw;

    @Column(name = "spec_raw")
    private String specRaw;

    @Column(name = "unit_raw")
    private String unitRaw;

    @Column(name = "quantity")
    private BigDecimal quantity;

    @Column(name = "fulfilled_quantity", nullable = false)
    private BigDecimal fulfilledQuantity = BigDecimal.ZERO;

    public Long getId() {
        return id;
    }

    public Long getOrderDraftId() {
        return orderDraftId;
    }

    public void setOrderDraftId(Long orderDraftId) {
        this.orderDraftId = orderDraftId;
    }

    public Integer getLineNo() {
        return lineNo;
    }

    public void setLineNo(Integer lineNo) {
        this.lineNo = lineNo;
    }

    public Long getSkuId() {
        return skuId;
    }

    public void setSkuId(Long skuId) {
        this.skuId = skuId;
    }

    public List<Map<String, Object>> getSkuCandidates() {
        return skuCandidates;
    }

    public void setSkuCandidates(List<Map<String, Object>> skuCandidates) {
        this.skuCandidates = skuCandidates;
    }

    public String getProductNameRaw() {
        return productNameRaw;
    }

    public void setProductNameRaw(String productNameRaw) {
        this.productNameRaw = productNameRaw;
    }

    public String getSpecRaw() {
        return specRaw;
    }

    public void setSpecRaw(String specRaw) {
        this.specRaw = specRaw;
    }

    public String getUnitRaw() {
        return unitRaw;
    }

    public void setUnitRaw(String unitRaw) {
        this.unitRaw = unitRaw;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public void setQuantity(BigDecimal quantity) {
        this.quantity = quantity;
    }

    public BigDecimal getFulfilledQuantity() {
        return fulfilledQuantity;
    }

    public void setFulfilledQuantity(BigDecimal fulfilledQuantity) {
        this.fulfilledQuantity = fulfilledQuantity;
    }
}
