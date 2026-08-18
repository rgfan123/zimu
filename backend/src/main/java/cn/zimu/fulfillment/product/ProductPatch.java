package cn.zimu.fulfillment.product;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 商品更新输入；可清空字段区分「未传」与「显式 null 清空」。
 * 出现任意新字段（含显式 null）即视为一次有效修改。
 */
public final class ProductPatch {

    @NotNull(message = "期望版本不能为空")
    private Long expectedVersion;

    @Size(max = 128, message = "商品名称超长")
    private String productName;

    @Pattern(regexp = cn.zimu.fulfillment.common.dto.Patterns.IDENTIFIER, message = "品类标识符无效")
    private String categoryId;

    private Boolean active;

    @Size(max = 1000, message = "原料描述超长")
    private String ingredients;
    private boolean ingredientsPresent;

    @Size(max = 10, message = "商品标签最多 10 个")
    private List<@Size(max = 32, message = "单个标签超长") String> tags;
    private boolean tagsPresent;

    private String listedFrom;
    private boolean listedFromPresent;

    private String listedUntil;
    private boolean listedUntilPresent;

    @Min(value = 1, message = "发货时效必须为正整数")
    private Integer leadTimeHours;
    private boolean leadTimeHoursPresent;

    private Object purchasePrice;
    private boolean purchasePricePresent;

    private Object retailPrice;
    private boolean retailPricePresent;

    private Object otherCost;
    private boolean otherCostPresent;

    @Size(max = 512, message = "主图引用超长")
    private String mainImageRef;
    private boolean mainImageRefPresent;

    public Long expectedVersion() {
        return expectedVersion;
    }

    public void setExpectedVersion(Long expectedVersion) {
        this.expectedVersion = expectedVersion;
    }

    public String productName() {
        return productName;
    }

    public void setProductName(String productName) {
        this.productName = productName;
    }

    public String categoryId() {
        return categoryId;
    }

    public void setCategoryId(String categoryId) {
        this.categoryId = categoryId;
    }

    public Boolean active() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }

    public String ingredients() {
        return ingredients;
    }

    @JsonSetter("ingredients")
    public void setIngredients(String ingredients) {
        this.ingredients = ingredients;
        this.ingredientsPresent = true;
    }

    public List<String> tags() {
        return tags;
    }

    @JsonSetter("tags")
    public void setTags(List<String> tags) {
        this.tags = tags;
        this.tagsPresent = true;
    }

    public String listedFrom() {
        return listedFrom;
    }

    @JsonSetter("listed_from")
    public void setListedFrom(String listedFrom) {
        this.listedFrom = listedFrom;
        this.listedFromPresent = true;
    }

    public String listedUntil() {
        return listedUntil;
    }

    @JsonSetter("listed_until")
    public void setListedUntil(String listedUntil) {
        this.listedUntil = listedUntil;
        this.listedUntilPresent = true;
    }

    public Integer leadTimeHours() {
        return leadTimeHours;
    }

    @JsonSetter("lead_time_hours")
    public void setLeadTimeHours(Integer leadTimeHours) {
        this.leadTimeHours = leadTimeHours;
        this.leadTimeHoursPresent = true;
    }

    public Object purchasePrice() {
        return purchasePrice;
    }

    @JsonSetter("purchase_price")
    public void setPurchasePrice(Object purchasePrice) {
        this.purchasePrice = purchasePrice;
        this.purchasePricePresent = true;
    }

    public Object retailPrice() {
        return retailPrice;
    }

    @JsonSetter("retail_price")
    public void setRetailPrice(Object retailPrice) {
        this.retailPrice = retailPrice;
        this.retailPricePresent = true;
    }

    public Object otherCost() {
        return otherCost;
    }

    @JsonSetter("other_cost")
    public void setOtherCost(Object otherCost) {
        this.otherCost = otherCost;
        this.otherCostPresent = true;
    }

    public String mainImageRef() {
        return mainImageRef;
    }

    @JsonSetter("main_image_ref")
    public void setMainImageRef(String mainImageRef) {
        this.mainImageRef = mainImageRef;
        this.mainImageRefPresent = true;
    }

    @JsonIgnore
    public boolean ingredientsPresent() {
        return ingredientsPresent;
    }

    @JsonIgnore
    public boolean tagsPresent() {
        return tagsPresent;
    }

    @JsonIgnore
    public boolean listedFromPresent() {
        return listedFromPresent;
    }

    @JsonIgnore
    public boolean listedUntilPresent() {
        return listedUntilPresent;
    }

    @JsonIgnore
    public boolean leadTimeHoursPresent() {
        return leadTimeHoursPresent;
    }

    @JsonIgnore
    public boolean purchasePricePresent() {
        return purchasePricePresent;
    }

    @JsonIgnore
    public boolean retailPricePresent() {
        return retailPricePresent;
    }

    @JsonIgnore
    public boolean otherCostPresent() {
        return otherCostPresent;
    }

    @JsonIgnore
    public boolean mainImageRefPresent() {
        return mainImageRefPresent;
    }

    /** 是否出现任意新字段（含显式 null 清空），用于判定本次是否为有效修改。 */
    @JsonIgnore
    public boolean anyArchiveFieldPresent() {
        return ingredientsPresent || tagsPresent || listedFromPresent || listedUntilPresent
                || leadTimeHoursPresent || purchasePricePresent || retailPricePresent || otherCostPresent
                || mainImageRefPresent;
    }
}
