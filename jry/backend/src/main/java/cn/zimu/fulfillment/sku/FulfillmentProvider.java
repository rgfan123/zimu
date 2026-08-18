package cn.zimu.fulfillment.sku;

import cn.zimu.fulfillment.common.jpa.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.LinkedHashMap;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 履约方（京东云仓或第三方）。 */
@Entity
@Table(name = "fulfillment_providers")
public class FulfillmentProvider extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "provider_code", nullable = false)
    private String providerCode;

    @Column(name = "provider_name", nullable = false)
    private String providerName;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider_type", nullable = false)
    private ProviderType providerType;

    @Column(name = "inventory_managed_by_us", nullable = false)
    private boolean inventoryManagedByUs;

    @Column(name = "tracking_sla_minutes", nullable = false)
    private Integer trackingSlaMinutes;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config", nullable = false)
    private Map<String, Object> config = new LinkedHashMap<>();

    @Version
    @Column(name = "lock_version", nullable = false)
    private Long lockVersion = 0L;

    public Long getId() {
        return id;
    }

    public String getProviderCode() {
        return providerCode;
    }

    public void setProviderCode(String providerCode) {
        this.providerCode = providerCode;
    }

    public String getProviderName() {
        return providerName;
    }

    public void setProviderName(String providerName) {
        this.providerName = providerName;
    }

    public ProviderType getProviderType() {
        return providerType;
    }

    public void setProviderType(ProviderType providerType) {
        this.providerType = providerType;
    }

    public boolean isInventoryManagedByUs() {
        return inventoryManagedByUs;
    }

    public void setInventoryManagedByUs(boolean inventoryManagedByUs) {
        this.inventoryManagedByUs = inventoryManagedByUs;
    }

    public Integer getTrackingSlaMinutes() {
        return trackingSlaMinutes;
    }

    public void setTrackingSlaMinutes(Integer trackingSlaMinutes) {
        this.trackingSlaMinutes = trackingSlaMinutes;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public Map<String, Object> getConfig() {
        return config;
    }

    public void setConfig(Map<String, Object> config) {
        this.config = config;
    }

    public Long getLockVersion() {
        return lockVersion;
    }
}
