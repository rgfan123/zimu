package cn.zimu.fulfillment.connector.wecom;

import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.domain.DataScope;
import cn.zimu.fulfillment.common.error.BusinessException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 企微机器人管理台账：登记会出现在企业微信通讯录里的 aibot 实例（bot_id / 名称 / 密钥 /
 * 启用 / 备注）。管理界面先行，运行时热切换未启用——生产当前唯一在跑的机器人凭据仍来自
 * {@link WecomProperties}（app.wecom.* 部署配置，单机器人假设），{@link WecomConnectionManager}
 * 仍只持有一个 {@link WecomLongConnectionClient}；本表登记的实例目前不影响任何在跑长连接，
 * 接线随多机器人能力推进。
 *
 * <p>只回标识符与非敏感状态，与 readiness / 会话目录同一道 X-Operator 门。secret 只回
 * {@link WecomBot#secretConfigured()} 存在性标记，绝不回显明文；写入负载经
 * {@link AuditLogService} 落审计时，按全库统一规则自动脱敏（键名命中 secret 即替换为
 * {@code ***}，见 {@link cn.zimu.fulfillment.common.audit.SecretRedactor}）——与京东 pin 的
 * 脱敏投影先例（{@link cn.zimu.fulfillment.sku.FulfillmentProviderJdConfig#auditSafe}）同一
 * 结论：审计记录永远看不到明文密钥。
 */
@RestController
@RequestMapping("/api/v1/wecom/bots")
public class WecomBotController {

    private static final int MAX_BOT_ID_LENGTH = 128;
    private static final int MAX_NAME_LENGTH = 128;
    private static final int MAX_NOTE_LENGTH = 500;

    private static final RowMapper<WecomBot> ROW_MAPPER = (rs, rowNum) -> new WecomBot(
            rs.getString("bot_id"),
            rs.getString("name"),
            rs.getBoolean("secret_configured"),
            rs.getBoolean("enabled"),
            rs.getString("note"),
            rs.getString("updated_by"),
            rs.getObject("updated_at", OffsetDateTime.class));

    /** secretConfigured：是否已登记密钥，永不回显明文。 */
    public record WecomBot(
            String botId, String name, boolean secretConfigured, boolean enabled,
            String note, String updatedBy, OffsetDateTime updatedAt) {}

    public record BotList(List<WecomBot> bots) {}

    /** upsert 写入体：name 必填；secret 为 null/空串 = 保持现值；enabled 为 null = 新建默认启用/更新保持现值。 */
    public record WecomBotWrite(String name, String secret, Boolean enabled, String note) {}

    private final JdbcTemplate jdbc;
    private final WecomBotService bots;
    private final AuditLogService audit;

    public WecomBotController(JdbcTemplate jdbc, WecomBotService bots, AuditLogService audit) {
        this.jdbc = jdbc;
        this.bots = bots;
        this.audit = audit;
    }

    @GetMapping
    public BotList list(@RequestHeader(value = "X-Operator", required = false) String operator) {
        requireOperator(operator);
        return new BotList(jdbc.query(
                """
                SELECT bot_id, name, (secret IS NOT NULL) AS secret_configured, enabled, note,
                       updated_by, updated_at
                FROM app.wecom_bots
                ORDER BY bot_id
                """,
                ROW_MAPPER));
    }

    /**
     * upsert：botId 取自路径，不存在则新建。secret 留空（null 或空串）保持现值——与京东 pin
     * 的编辑交互先例一致，绝不会因为「懒得重填」而被误清空。
     */
    @PutMapping("/{botId}")
    public WecomBot upsert(
            @PathVariable String botId,
            @RequestBody(required = false) WecomBotWrite body,
            @RequestHeader(value = "X-Operator", required = false) String operator) {
        requireOperator(operator);
        String normalizedBotId = requireBotId(botId);
        String name = requireName(body == null ? null : body.name());
        String note = validateNote(body == null ? null : body.note());
        String secret = body == null ? null : body.secret();
        Boolean enabled = body == null ? null : body.enabled();

        bots.upsert(normalizedBotId, name, secret, enabled, note, operator);
        WecomBot saved = queryOne(normalizedBotId);

        audit.record(new AuditLogService.AuditCommand()
                .dataScope(DataScope.BUSINESS)
                .operator(operator)
                .actorType(AuditActorType.HUMAN)
                .service("WecomBotController")
                .operation("wecom_bot.upsert")
                .requestPayload(auditPayload(normalizedBotId, name, secret, enabled, note))
                .responsePayload(saved)
                .httpStatus(200)
                .businessCode("SUCCESS"));
        return saved;
    }

    private WecomBot queryOne(String botId) {
        List<WecomBot> rows = jdbc.query(
                """
                SELECT bot_id, name, (secret IS NOT NULL) AS secret_configured, enabled, note,
                       updated_by, updated_at
                FROM app.wecom_bots WHERE bot_id = ?
                """,
                ROW_MAPPER, botId);
        if (rows.isEmpty()) {
            throw BusinessException.notFound("机器人不存在");
        }
        return rows.getFirst();
    }

    /**
     * 审计请求负载：secret 未变更（null/空串）时整键不出现，避免落一个看似「已提交又被脱敏」
     * 的误导性 {@code ***}；真正提交了新密钥时原样放入，落库前由 AuditLogService 按
     * SecretRedactor 规则统一脱敏，审计记录里永远只有 {@code ***}。
     */
    private static Map<String, Object> auditPayload(
            String botId, String name, String secret, Boolean enabled, String note) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("bot_id", botId);
        payload.put("name", name);
        if (secret != null && !secret.isBlank()) {
            payload.put("secret", secret);
        }
        payload.put("enabled", enabled);
        payload.put("note", note);
        return payload;
    }

    private static void requireOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            throw new BusinessException(401, "ADMIN_AUTH_REQUIRED", "管理后台操作需要认证");
        }
    }

    private static String requireBotId(String botId) {
        if (botId == null || botId.isBlank() || botId.length() > MAX_BOT_ID_LENGTH) {
            throw new BusinessException(400, "INVALID_BOT_ID", "bot_id 非法");
        }
        return botId.trim();
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new BusinessException(400, "BOT_NAME_REQUIRED", "机器人名称必填");
        }
        String trimmed = name.trim();
        if (trimmed.length() > MAX_NAME_LENGTH) {
            throw new BusinessException(400, "INVALID_BOT_NAME", "机器人名称最长 " + MAX_NAME_LENGTH + " 字");
        }
        return trimmed;
    }

    private static String validateNote(String note) {
        if (note != null && note.length() > MAX_NOTE_LENGTH) {
            throw new BusinessException(400, "INVALID_BOT_NOTE", "备注最长 " + MAX_NOTE_LENGTH + " 字");
        }
        return note;
    }
}
