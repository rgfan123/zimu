package cn.zimu.fulfillment.connector.wecom;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 企微机器人实例登记（管理界面先行）：写入侧 upsert 语义单独收口，读取由
 * {@link WecomBotController} 直接查询——与 {@link WecomChatDirectoryController}
 * / {@link WecomChatReplyPolicyService} 的 Controller+Service 分工同构。
 *
 * <p>运行时长连接凭据仍取自 {@link WecomProperties}（app.wecom.* 部署配置，单机器人假设），
 * {@link WecomConnectionManager} 仍只持有一个 {@link WecomLongConnectionClient}；本表登记的
 * 实例尚未接入热切换，不影响任何在跑连接。
 *
 * <p>secret 明文列，与 fulfillment_providers.config 里京东 pin 同一存法（不自研加密方案）：
 * 读侧只投影是否已配置，永不回显明文。
 */
@Service
public class WecomBotService {

    private final JdbcTemplate jdbc;

    public WecomBotService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * upsert：{@code secret} 为 null/空串时保持现值（新建行则维持未配置状态，不强行要求
     * 首次登记就必须带密钥）；{@code enabled} 为 null 时新建默认启用、更新保持现值；
     * {@code note} 为 null/空串即清除备注。
     */
    public void upsert(String botId, String name, String secret, Boolean enabled, String note, String operator) {
        jdbc.update(
                """
                INSERT INTO app.wecom_bots AS b (bot_id, name, secret, enabled, note, updated_by, updated_at)
                VALUES (?::text, ?::text, NULLIF(?::text, ''), COALESCE(?::boolean, true), NULLIF(?::text, ''), ?::text, now())
                ON CONFLICT (bot_id) DO UPDATE
                    SET name       = ?::text,
                        secret     = CASE WHEN NULLIF(?::text, '') IS NULL THEN b.secret ELSE ?::text END,
                        enabled    = COALESCE(?::boolean, b.enabled),
                        note       = NULLIF(?::text, ''),
                        updated_by = ?::text,
                        updated_at = now()
                """,
                botId, name, secret, enabled, note, operator,
                name, secret, secret, enabled, note, operator);
    }
}
