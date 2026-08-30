package cn.zimu.fulfillment.file;

import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.domain.DataScope;
import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.idempotency.IdempotencyService;
import cn.zimu.fulfillment.common.idempotency.IdempotentResult;
import cn.zimu.fulfillment.common.web.CommandContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** 显式建立并读取来源模板的单一 AutomaticRelease 授权。 */
@Service
class SourceTemplateProfileService {

    private final JdbcTemplate jdbc;
    private final IdempotencyService idempotency;
    private final AuditLogService audit;

    SourceTemplateProfileService(
            JdbcTemplate jdbc, IdempotencyService idempotency, AuditLogService audit) {
        this.jdbc = jdbc;
        this.idempotency = idempotency;
        this.audit = audit;
    }

    IdempotentResult<Map<String, Object>> trust(
            long batchId, String idempotencyKey, CommandContext context) {
        return idempotency.execute(
                "source_template_profile.trust",
                idempotencyKey,
                Map.of("batch_id", batchId),
                200,
                () -> doTrust(batchId, context));
    }

    private Map<String, Object> doTrust(long batchId, CommandContext context) {
        BatchTemplate source = jdbc.query(
                        """
                        SELECT source.effective_source_channel, ib.template_family,
                               ib.template_version, ib.template_fingerprint, ib.confirmed_at
                        FROM app.import_batches ib
                        JOIN app.v_import_batch_effective_source source ON source.import_batch_id=ib.id
                        WHERE ib.id=? AND ib.batch_type='SOURCE_ORDER'
                        FOR SHARE OF ib
                        """,
                        (resultSet, rowNumber) -> new BatchTemplate(
                                SourceChannel.valueOf(resultSet.getString("effective_source_channel")),
                                resultSet.getString("template_family"),
                                resultSet.getString("template_version"),
                                resultSet.getString("template_fingerprint"),
                                resultSet.getObject("confirmed_at", OffsetDateTime.class)),
                        batchId)
                .stream()
                .findFirst()
                .orElseThrow(() -> BusinessException.notFound("来源订单批次不存在: " + batchId));
        if (source.confirmedAt() == null) {
            throw BusinessException.conflict(
                    "IMPORT_BATCH_CONFIRMATION_REQUIRED", "只有已由操作员确认的来源批次可以授权模板信任");
        }
        TrustedTemplate existing = find(source.channel(), source.family(), source.version(), source.fingerprint())
                .orElse(null);
        if (existing != null) {
            if (!"TRUSTED".equals(existing.status())) {
                throw BusinessException.conflict(
                        "TEMPLATE_PROFILE_REVOKED", "该模板版本已撤销，不能恢复旧授权");
            }
            return response(existing);
        }
        String profileNo = "TPL-" + UUID.randomUUID().toString().replace("-", "").toUpperCase();
        List<TrustedTemplate> inserted = jdbc.query(
                        """
                        INSERT INTO app.source_template_profiles
                            (profile_no, source_channel, template_family, template_version,
                             template_fingerprint, status, trusted_from_batch_id, trusted_by)
                        VALUES (?, ?, ?, ?, ?, 'TRUSTED', ?, ?)
                        ON CONFLICT (source_channel, template_family, template_version, template_fingerprint)
                        DO NOTHING
                        RETURNING id, profile_no, source_channel, template_family, template_version,
                                  template_fingerprint, status, trusted_at
                        """,
                        (resultSet, rowNumber) -> template(resultSet),
                        profileNo,
                        source.channel().name(),
                        source.family(),
                        source.version(),
                        source.fingerprint(),
                        batchId,
                        context.operator());
        if (inserted.isEmpty()) {
            TrustedTemplate concurrent = find(
                            source.channel(), source.family(), source.version(), source.fingerprint())
                    .orElseThrow(() -> new IllegalStateException("受信模板并发写入后无法读取"));
            if (!"TRUSTED".equals(concurrent.status())) {
                throw BusinessException.conflict(
                        "TEMPLATE_PROFILE_REVOKED", "该模板版本已撤销，不能恢复旧授权");
            }
            return response(concurrent);
        }
        TrustedTemplate created = inserted.getFirst();
        Map<String, Object> result = response(created);
        audit.record(new AuditLogService.AuditCommand()
                .dataScope(DataScope.BUSINESS)
                .requestId(context.requestId())
                .traceId(context.traceId())
                .operator(context.operator())
                .actorType(AuditActorType.HUMAN)
                .service("source-template-profile")
                .operation("template.trust")
                .requestPayload(Map.of("batch_id", batchId))
                .responsePayload(result)
                .httpStatus(200)
                .businessCode("SOURCE_TEMPLATE_TRUSTED"));
        return result;
    }

    Optional<TrustedTemplate> trusted(ParsedSourceFile parsed) {
        return find(
                        parsed.sourceChannel(),
                        parsed.templateFamily(),
                        parsed.templateVersion(),
                        parsed.templateFingerprint())
                .filter(profile -> "TRUSTED".equals(profile.status()));
    }

    void requireBatchMatch(TrustedTemplate profile, long batchId) {
        Boolean matches = jdbc.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM app.import_batches ib
                    JOIN app.v_import_batch_effective_source source ON source.import_batch_id=ib.id
                    WHERE ib.id=? AND ib.batch_type='SOURCE_ORDER'
                      AND source.effective_source_channel=?
                      AND ib.template_family=? AND ib.template_version=?
                      AND ib.template_fingerprint=?
                )
                """,
                Boolean.class,
                batchId,
                profile.sourceChannel().name(),
                profile.templateFamily(),
                profile.templateVersion(),
                profile.templateFingerprint());
        if (!Boolean.TRUE.equals(matches)) {
            throw BusinessException.conflict(
                    "TEMPLATE_PROFILE_MISMATCH", "来源批次结构与受信模板版本不一致，已停止自动放行");
        }
    }

    private Optional<TrustedTemplate> find(
            SourceChannel channel, String family, String version, String fingerprint) {
        List<TrustedTemplate> matches = jdbc.query(
                """
                SELECT id, profile_no, source_channel, template_family, template_version,
                       template_fingerprint, status, trusted_at
                FROM app.source_template_profiles
                WHERE source_channel=? AND template_family=? AND template_version=?
                  AND template_fingerprint=?
                """,
                (resultSet, rowNumber) -> template(resultSet),
                channel.name(),
                family,
                version,
                fingerprint);
        return matches.stream().findFirst();
    }

    private TrustedTemplate template(ResultSet resultSet) throws SQLException {
        return new TrustedTemplate(
                resultSet.getLong("id"),
                resultSet.getString("profile_no"),
                SourceChannel.valueOf(resultSet.getString("source_channel")),
                resultSet.getString("template_family"),
                resultSet.getString("template_version"),
                resultSet.getString("template_fingerprint"),
                resultSet.getString("status"),
                resultSet.getObject("trusted_at", OffsetDateTime.class));
    }

    private Map<String, Object> response(TrustedTemplate profile) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", Long.toString(profile.id()));
        result.put("profile_no", profile.profileNo());
        result.put("source_channel", profile.sourceChannel().name());
        result.put("template_family", profile.templateFamily());
        result.put("template_version", profile.templateVersion());
        result.put("template_fingerprint", profile.templateFingerprint());
        result.put("status", profile.status());
        result.put("trusted_at", profile.trustedAt());
        return result;
    }

    record TrustedTemplate(
            long id,
            String profileNo,
            SourceChannel sourceChannel,
            String templateFamily,
            String templateVersion,
            String templateFingerprint,
            String status,
            OffsetDateTime trustedAt) {}

    private record BatchTemplate(
            SourceChannel channel,
            String family,
            String version,
            String fingerprint,
            OffsetDateTime confirmedAt) {}
}
