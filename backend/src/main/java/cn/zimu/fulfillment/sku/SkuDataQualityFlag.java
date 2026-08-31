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

/** SKU 的显式数据质量证据；它不是持久化的 readiness 状态。 */
@Entity
@Table(name = "sku_data_quality_flags")
public class SkuDataQualityFlag extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sku_id", nullable = false)
    private Long skuId;

    @Column(name = "flag_code", nullable = false)
    private String flagCode;

    @Column(name = "blocking_reason")
    private String blockingReason;

    @Column(name = "message", nullable = false)
    private String message;

    @Column(name = "action", nullable = false)
    private String action;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence", nullable = false)
    private Map<String, Object> evidence = new LinkedHashMap<>();

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Version
    @Column(name = "lock_version", nullable = false)
    private Long lockVersion = 0L;

    public Long getId() {
        return id;
    }

    public Long getSkuId() {
        return skuId;
    }

    public String getFlagCode() {
        return flagCode;
    }

    public String getBlockingReason() {
        return blockingReason;
    }

    public String getMessage() {
        return message;
    }

    public String getAction() {
        return action;
    }

    public Map<String, Object> getEvidence() {
        return evidence;
    }

    public boolean isActive() {
        return active;
    }

    public Long getLockVersion() {
        return lockVersion;
    }
}
