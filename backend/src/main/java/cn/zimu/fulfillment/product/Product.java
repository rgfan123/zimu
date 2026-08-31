package cn.zimu.fulfillment.product;

import cn.zimu.fulfillment.common.jpa.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDate;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 商品族（不含包装规格、单位与履约方归属）。 */
@Entity
@Table(name = "products")
public class Product extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "product_code", nullable = false)
    private String productCode;

    @Column(name = "product_name", nullable = false)
    private String productName;

    @Column(name = "brand_name")
    private String brandName;

    @Column(name = "category_id")
    private Long categoryId;

    @Column(name = "description")
    private String description;

    @Column(name = "ingredients")
    private String ingredients;

    /** 商品标签：去重后的字符串数组；空列表落 null。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tags")
    private List<String> tags;

    @Column(name = "listed_from")
    private LocalDate listedFrom;

    @Column(name = "listed_until")
    private LocalDate listedUntil;

    @Column(name = "lead_time_hours")
    private Integer leadTimeHours;

    @Column(name = "main_image_ref")
    private String mainImageRef;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Version
    @Column(name = "lock_version", nullable = false)
    private Long lockVersion = 0L;

    public Long getId() {
        return id;
    }

    public String getProductCode() {
        return productCode;
    }

    public void setProductCode(String productCode) {
        this.productCode = productCode;
    }

    public String getProductName() {
        return productName;
    }

    public void setProductName(String productName) {
        this.productName = productName;
    }

    public String getBrandName() {
        return brandName;
    }

    public void setBrandName(String brandName) {
        this.brandName = brandName;
    }

    public Long getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(Long categoryId) {
        this.categoryId = categoryId;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getIngredients() {
        return ingredients;
    }

    public void setIngredients(String ingredients) {
        this.ingredients = ingredients;
    }

    public List<String> getTags() {
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags;
    }

    public LocalDate getListedFrom() {
        return listedFrom;
    }

    public void setListedFrom(LocalDate listedFrom) {
        this.listedFrom = listedFrom;
    }

    public LocalDate getListedUntil() {
        return listedUntil;
    }

    public void setListedUntil(LocalDate listedUntil) {
        this.listedUntil = listedUntil;
    }

    public Integer getLeadTimeHours() {
        return leadTimeHours;
    }

    public void setLeadTimeHours(Integer leadTimeHours) {
        this.leadTimeHours = leadTimeHours;
    }

    public String getMainImageRef() {
        return mainImageRef;
    }

    public void setMainImageRef(String mainImageRef) {
        this.mainImageRef = mainImageRef;
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
