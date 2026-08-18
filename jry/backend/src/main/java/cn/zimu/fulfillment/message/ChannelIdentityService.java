package cn.zimu.fulfillment.message;

import cn.zimu.fulfillment.common.error.BusinessException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 渠道身份绑定用例：只保存企微主体、接入类型与渠道标识唯一作用域内的显式 Customer 绑定。
 *
 * <p>普通微信群转发场景没有真实渠道身份，绝不调用本服务；已绑定身份的后续消息可据此
 * 带出客户候选，但一期仍须人工确认订单。冲突绑定、跨作用域 ID 由公共 API 明确拒绝。
 * 确认事务通过 {@link #bindFromSubmission} 钩子读取源消息的身份分类，只有消息入口显式
 * 声明 {@code sender_identity_type='CUSTOMER'} 时才建立绑定。
 */
@Service
public class ChannelIdentityService {

    /** 消息入口声明发送者为真实客户渠道身份的标记值。 */
    public static final String SENDER_IDENTITY_CUSTOMER = "CUSTOMER";

    private final ChannelIdentityRepository identities;
    private final JdbcTemplate jdbc;

    public ChannelIdentityService(ChannelIdentityRepository identities, JdbcTemplate jdbc) {
        this.identities = identities;
        this.jdbc = jdbc;
    }

    public Optional<ChannelIdentity> findBound(String corpId, String accessType, String channelIdentity) {
        return identities.findByCorpIdAndAccessTypeAndChannelIdentity(corpId, accessType, channelIdentity)
                .filter(binding -> binding.getCustomerId() != null);
    }

    /**
     * 确认事务内建立或更新唯一绑定；绑定已指向其他客户时明确拒绝。
     *
     * <p>snapshot 为结构化输出中的渠道资料白名单（display_name/remark/description/avatar_url），
     * 作为可变快照保存。
     */
    @Transactional
    public ChannelIdentity bind(
            String corpId,
            String accessType,
            String channelIdentity,
            long customerId,
            Map<String, Object> snapshot) {
        if (isBlank(corpId) || isBlank(accessType) || isBlank(channelIdentity)) {
            throw BusinessException.unprocessable(
                    "CHANNEL_IDENTITY_INVALID", "渠道身份作用域不完整，不能建立绑定");
        }
        return identities
                .findByCorpIdAndAccessTypeAndChannelIdentity(corpId, accessType, channelIdentity)
                .map(existing -> {
                    if (existing.getCustomerId() != null && !Objects.equals(existing.getCustomerId(), customerId)) {
                        throw BusinessException.conflict(
                                "CHANNEL_IDENTITY_CONFLICT", "该渠道身份已绑定到其他客户，请先处理主数据冲突");
                    }
                    existing.setCustomerId(customerId);
                    applySnapshot(existing, snapshot);
                    return identities.save(existing);
                })
                .orElseGet(() -> {
                    ChannelIdentity binding = new ChannelIdentity();
                    binding.setCorpId(corpId);
                    binding.setAccessType(accessType);
                    binding.setChannelIdentity(channelIdentity);
                    binding.setCustomerId(customerId);
                    applySnapshot(binding, snapshot);
                    return identities.save(binding);
                });
    }

    /**
     * 确认事务渠道绑定钩子：仅当源消息入口显式提供真实客户渠道身份时，把该身份
     * （作用域 corp_id + sender_access_type + sender_user_id）绑定到唯一 Customer；
     * 普通微信群转发员工等仅传输身份一律返回空，绝不绑定。
     */
    @Transactional
    public Optional<ChannelIdentity> bindFromSubmission(long submissionId, long customerId) {
        Map<String, Object> scope = jdbc.query(
                """
                SELECT cm.corp_id, cm.sender_identity_type, cm.sender_access_type, cm.sender_user_id
                FROM app.message_submissions ms
                JOIN app.channel_messages cm ON cm.id = ms.source_message_id
                WHERE ms.id = ?
                """,
                rs -> {
                    if (!rs.next()) {
                        return null;
                    }
                    Map<String, Object> values = new java.util.LinkedHashMap<>();
                    values.put("corp_id", stringOrNull(rs.getString("corp_id")));
                    values.put("sender_identity_type", stringOrNull(rs.getString("sender_identity_type")));
                    values.put("sender_access_type", stringOrNull(rs.getString("sender_access_type")));
                    values.put("sender_user_id", stringOrNull(rs.getString("sender_user_id")));
                    return values;
                },
                submissionId);
        if (scope == null || !SENDER_IDENTITY_CUSTOMER.equals(scope.get("sender_identity_type"))) {
            return Optional.empty();
        }
        String accessType = (String) scope.get("sender_access_type");
        String channelIdentity = (String) scope.get("sender_user_id");
        if (isBlank(accessType) || isBlank(channelIdentity)) {
            throw BusinessException.unprocessable(
                    "CHANNEL_IDENTITY_INVALID", "消息入口声明了客户渠道身份但作用域不完整，不能建立绑定");
        }
        return Optional.of(
                bind((String) scope.get("corp_id"), accessType, channelIdentity, customerId, Map.of()));
    }

    private static void applySnapshot(ChannelIdentity binding, Map<String, Object> snapshot) {
        if (snapshot == null) {
            return;
        }
        binding.setDisplayName(stringOrNull(snapshot.get("display_name")));
        binding.setRemark(stringOrNull(snapshot.get("remark")));
        binding.setDescription(stringOrNull(snapshot.get("description")));
        binding.setAvatarUrl(stringOrNull(snapshot.get("avatar_url")));
    }

    private static String stringOrNull(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
