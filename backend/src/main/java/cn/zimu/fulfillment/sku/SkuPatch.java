package cn.zimu.fulfillment.sku;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSetter;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** SKU 更新输入；可清空字段区分“未传”与“显式 null 清空”。 */
public final class SkuPatch {

    @NotNull(message = "期望版本不能为空")
    private Long expectedVersion;

    @Size(max = 200, message = "规格超长")
    private String specification;

    @Size(max = 32, message = "单位超长")
    private String unit;

    private Object netContentValue;
    private boolean netContentValuePresent;

    @Size(max = 16, message = "净含量单位超长")
    private String netContentUnit;
    private boolean netContentUnitPresent;

    private Object packageCount;
    private boolean packageCountPresent;

    @Size(max = 32, message = "包装单位超长")
    private String packageUnit;
    private boolean packageUnitPresent;

    @Size(max = 64, message = "条码超长")
    private String barcode;
    private boolean barcodePresent;

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

    public String unit() {
        return unit;
    }

    public void setUnit(String unit) {
        this.unit = unit;
    }

    public Object netContentValue() {
        return netContentValue;
    }

    @JsonSetter("net_content_value")
    public void setNetContentValue(Object netContentValue) {
        this.netContentValue = netContentValue;
        this.netContentValuePresent = true;
    }

    public String netContentUnit() {
        return netContentUnit;
    }

    @JsonSetter("net_content_unit")
    public void setNetContentUnit(String netContentUnit) {
        this.netContentUnit = netContentUnit;
        this.netContentUnitPresent = true;
    }

    public Object packageCount() {
        return packageCount;
    }

    @JsonSetter("package_count")
    public void setPackageCount(Object packageCount) {
        this.packageCount = packageCount;
        this.packageCountPresent = true;
    }

    public String packageUnit() {
        return packageUnit;
    }

    @JsonSetter("package_unit")
    public void setPackageUnit(String packageUnit) {
        this.packageUnit = packageUnit;
        this.packageUnitPresent = true;
    }

    public String barcode() {
        return barcode;
    }

    @JsonSetter("barcode")
    public void setBarcode(String barcode) {
        this.barcode = barcode;
        this.barcodePresent = true;
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

    @JsonIgnore
    public boolean barcodePresent() {
        return barcodePresent;
    }

    @JsonIgnore
    public boolean netContentValuePresent() {
        return netContentValuePresent;
    }

    @JsonIgnore
    public boolean netContentUnitPresent() {
        return netContentUnitPresent;
    }

    @JsonIgnore
    public boolean packageCountPresent() {
        return packageCountPresent;
    }

    @JsonIgnore
    public boolean packageUnitPresent() {
        return packageUnitPresent;
    }

    @JsonIgnore
    public boolean packagingIdentityPresent() {
        return netContentValuePresent || netContentUnitPresent || packageCountPresent || packageUnitPresent;
    }

    @JsonIgnore
    public boolean clearsPackagingIdentity() {
        return netContentValuePresent && netContentValue == null
                && netContentUnitPresent && netContentUnit == null
                && packageCountPresent && packageCount == null
                && packageUnitPresent && packageUnit == null;
    }
}
