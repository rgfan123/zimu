package cn.zimu.fulfillment.message;

import cn.zimu.fulfillment.common.jpa.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 外部渠道在特定企业、应用和能力作用域内提供的主体标识及资料快照。
 *
 * <p>唯一作用域为 (corp_id, access_type, channel_identity)；显示名、备注、描述、头像均为可变
 * 资料快照，不作为唯一身份。`external_userid` 只作为作用域内渠道身份，不命名或展示为“微信号”。
 * 首次人工确认客户后保存到唯一 Customer 的显式绑定；未绑定、零命中或多命中进入复核事项。
 */
@Entity
@Table(name = "channel_identities")
public class ChannelIdentity extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "corp_id", nullable = false)
    private String corpId;

    @Column(name = "access_type", nullable = false)
    private String accessType;

    @Column(name = "channel_identity", nullable = false)
    private String channelIdentity;

    @Column(name = "customer_id")
    private Long customerId;

    @Column(name = "display_name")
    private String displayName;

    @Column(name = "remark")
    private String remark;

    @Column(name = "description")
    private String description;

    @Column(name = "avatar_url")
    private String avatarUrl;

    public Long getId() {
        return id;
    }

    public String getCorpId() {
        return corpId;
    }

    public void setCorpId(String corpId) {
        this.corpId = corpId;
    }

    public String getAccessType() {
        return accessType;
    }

    public void setAccessType(String accessType) {
        this.accessType = accessType;
    }

    public String getChannelIdentity() {
        return channelIdentity;
    }

    public void setChannelIdentity(String channelIdentity) {
        this.channelIdentity = channelIdentity;
    }

    public Long getCustomerId() {
        return customerId;
    }

    public void setCustomerId(Long customerId) {
        this.customerId = customerId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public void setAvatarUrl(String avatarUrl) {
        this.avatarUrl = avatarUrl;
    }
}
