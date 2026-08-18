package cn.zimu.fulfillment.sku;

import cn.zimu.fulfillment.common.jpa.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.LinkedHashMap;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 内部 SKU 到履约方商品编码的映射。 */
@Entity
@Table(name = "provider_skus")
public class ProviderSku extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "fulfillment_provider_id", nullable = false)
    private Long fulfillmentProviderId;

    @Column(name = "sku_id", nullable = false)
    private Long skuId;

    @Column(name = "provider_sku_code", nullable = false)
    private String providerSkuCode;

    @Column(name = "merchant_sku_code")
    private String merchantSkuCode;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "external_codes", nullable = false)
    private Map<String, Object> externalCodes = new LinkedHashMap<>();

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Version
    @Column(name = "lock_version", nullable = false)
    private Long lockVersion = 0L;

    public Long getId() {
        return id;
    }

    public Long getFulfillmentProviderId() {
        return fulfillmentProviderId;
    }

    public void setFulfillmentProviderId(Long fulfillmentProviderId) {
        this.fulfillmentProviderId = fulfillmentProviderId;
    }

    public Long getSkuId() {
        return skuId;
    }

    public void setSkuId(Long skuId) {
        this.skuId = skuId;
    }

    public String getProviderSkuCode() {
        return providerSkuCode;
    }

    public void setProviderSkuCode(String providerSkuCode) {
        this.providerSkuCode = providerSkuCode;
    }

    public String getMerchantSkuCode() {
        return merchantSkuCode;
    }

    public void setMerchantSkuCode(String merchantSkuCode) {
        this.merchantSkuCode = merchantSkuCode;
    }

    public Map<String, Object> getExternalCodes() {
        return externalCodes;
    }

    public void setExternalCodes(Map<String, Object> externalCodes) {
        this.externalCodes = externalCodes;
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
