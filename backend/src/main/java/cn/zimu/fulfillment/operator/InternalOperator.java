package cn.zimu.fulfillment.operator;

import cn.zimu.fulfillment.common.jpa.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * 内部运营人员（Issue #89）：姓名、企微 userid、所属责任团队。
 *
 * <p>只做「运营人员 ↔ 企微 userid ↔ 责任团队」映射与责任归属；不做登录/角色/权限，
 * 不把 {@code channel_identities} 当内部员工表。停用（active=false）而非物理删除：
 * 解析 seam 只返回 active 人员。
 */
@Entity
@Table(name = "internal_operators")
public class InternalOperator extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "responsible_team", nullable = false)
    private String responsibleTeam;

    @Column(name = "wecom_userid")
    private String wecomUserid;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Version
    @Column(name = "lock_version", nullable = false)
    private Long lockVersion = 0L;

    public Long getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getResponsibleTeam() {
        return responsibleTeam;
    }

    public void setResponsibleTeam(String responsibleTeam) {
        this.responsibleTeam = responsibleTeam;
    }

    public String getWecomUserid() {
        return wecomUserid;
    }

    public void setWecomUserid(String wecomUserid) {
        this.wecomUserid = wecomUserid;
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
