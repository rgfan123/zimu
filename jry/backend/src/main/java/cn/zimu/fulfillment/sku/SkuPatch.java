package cn.zimu.fulfillment.sku;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** SKU 更新输入；价格字段区分“未传”与“显式 null 清空”。 */
public final class SkuPatch {

    @NotNull(message = "期望版本不能为空")
    private Long expectedVersion;

    @Size(max = 200, message = "规格超长")
    private String specification;

    @Size(max = 64, message = "条码超长")
    private String barcode;

    private Boolean active;
    private Object purchasePrice;
    private Object retailPrice;
    private boolean purchasePricePresent;
    private boolean retailPricePresent;

    public Long expectedVersion() {
        return expectedVersion;
    }

    public void setExpectedVersion(Long expectedVersion) {
        this.expectedVersion = expectedVersion;
    }

    public String specification() {
        return specification;
    }

    public void setSpecification(String specification) {
        this.specification = specification;
    }

    public String barcode() {
        return barcode;
    }

    public void setBarcode(String barcode) {
        this.barcode = barcode;
    }

    public Boolean active() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
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

    @JsonIgnore
    public boolean purchasePricePresent() {
        return purchasePricePresent;
    }

    @JsonIgnore
    public boolean retailPricePresent() {
        return retailPricePresent;
    }
}
