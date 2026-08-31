package cn.zimu.fulfillment.file;

import cn.zimu.fulfillment.common.audit.AuditActorType;
import cn.zimu.fulfillment.common.audit.AuditLogService;
import cn.zimu.fulfillment.common.domain.DataScope;
import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.idempotency.IdempotencyService;
import cn.zimu.fulfillment.common.idempotency.IdempotentResult;
import cn.zimu.fulfillment.common.web.CommandContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 显式建立并读取来源模板的单一 AutomaticRelease 授权。 */
@Service
class SourceTemplateProfileService {

    private final JdbcTemplate jdbc;
    private final IdempotencyService idempotency;
    private final AuditLogService audit;
    private final ObjectMapper objectMapper;

    SourceTemplateProfileService(
            JdbcTemplate jdbc,
            IdempotencyService idempotency,
            AuditLogService audit,
            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.idempotency = idempotency;
        this.audit = audit;
        this.objectMapper = objectMapper;
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
        List<Long> locked = jdbc.query(
                """
                SELECT id FROM app.import_batches
                WHERE id=? AND batch_type='SOURCE_ORDER'
                FOR SHARE
                """,
                (resultSet, rowNumber) -> resultSet.getLong(1),
                batchId);
        if (locked.isEmpty()) {
            throw BusinessException.notFound("来源订单批次不存在: " + batchId);
        }
        BatchTemplate source = batchTemplate(batchId);
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
                                  template_fingerprint, status, trusted_from_batch_id, trusted_at
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

    private BatchTemplate batchTemplate(long batchId) {
        return jdbc.query(
                        """
                        SELECT source.effective_source_channel, source.effective_template_family,
                               ib.template_version, source.effective_template_fingerprint, ib.confirmed_at
                        FROM app.import_batches ib
                        JOIN app.v_import_batch_effective_source source ON source.import_batch_id=ib.id
                        WHERE ib.id=? AND ib.batch_type='SOURCE_ORDER'
                        """,
                        (resultSet, rowNumber) -> new BatchTemplate(
                                SourceChannel.valueOf(resultSet.getString("effective_source_channel")),
                                resultSet.getString("effective_template_family"),
                                resultSet.getString("template_version"),
                                resultSet.getString("effective_template_fingerprint"),
                                resultSet.getObject("confirmed_at", OffsetDateTime.class)),
                        batchId)
                .stream()
                .findFirst()
                .orElseThrow(() -> BusinessException.notFound("来源订单批次不存在: " + batchId));
    }

    /** 上传完成后只按数据库中的有效归因身份查找 standing authorization。 */
    Optional<TrustedTemplate> trustedForBatch(long batchId) {
        return jdbc.query(
                        """
                        SELECT profile.id, profile.profile_no, profile.source_channel,
                               profile.template_family, profile.template_version,
                               profile.template_fingerprint, profile.status,
                               profile.trusted_from_batch_id, profile.trusted_at
                        FROM app.import_batches ib
                        JOIN app.v_import_batch_effective_source source ON source.import_batch_id=ib.id
                        JOIN app.source_template_profiles profile
                          ON profile.source_channel=source.effective_source_channel
                         AND profile.template_family=source.effective_template_family
                         AND profile.template_version=ib.template_version
                         AND profile.template_fingerprint=source.effective_template_fingerprint
                         AND profile.status='TRUSTED'
                        JOIN app.import_batches authority_batch
                          ON authority_batch.id=profile.trusted_from_batch_id
                         AND authority_batch.confirmed_at IS NOT NULL
                        JOIN app.v_import_batch_effective_source authority_source
                          ON authority_source.import_batch_id=authority_batch.id
                         AND authority_source.effective_source_channel=profile.source_channel
                         AND authority_source.effective_template_family=profile.template_family
                         AND authority_batch.template_version=profile.template_version
                         AND authority_source.effective_template_fingerprint=profile.template_fingerprint
                        WHERE ib.id=? AND ib.batch_type='SOURCE_ORDER'
                        """,
                        (resultSet, rowNumber) -> template(resultSet),
                        batchId)
                .stream()
                .findFirst();
    }

    /**
     * AutomaticRelease 最终授权检查。先锁批次再用新语句读取有效归因，随后锁 profile 再读取状态，
     * 避免等待并发 correction/revocation 后继续使用语句开始时的旧快照。
     */
    TrustedTemplate requireTrustedBatchMatchForRelease(long profileId, long batchId) {
        List<Long> batchLocks = jdbc.query(
                "SELECT id FROM app.import_batches WHERE id=? AND batch_type='SOURCE_ORDER' FOR UPDATE",
                (resultSet, rowNumber) -> resultSet.getLong(1),
                batchId);
        if (batchLocks.isEmpty()) {
            throw BusinessException.notFound("来源订单批次不存在: " + batchId);
        }
        BatchTemplate batch = batchTemplate(batchId);
        List<Long> profileLocks = jdbc.query(
                "SELECT id FROM app.source_template_profiles WHERE id=? FOR SHARE",
                (resultSet, rowNumber) -> resultSet.getLong(1),
                profileId);
        if (profileLocks.isEmpty()) {
            throw BusinessException.conflict(
                    "TEMPLATE_PROFILE_MISMATCH", "受信模板授权不存在，已停止自动放行");
        }
        TrustedTemplate profile = findById(profileId)
                .orElseThrow(() -> new IllegalStateException("已锁定的受信模板无法读取"));
        if (!"TRUSTED".equals(profile.status())) {
            throw BusinessException.conflict(
                    "TEMPLATE_PROFILE_REVOKED", "受信模板授权已撤销，已停止自动放行");
        }
        if (batch.channel() != profile.sourceChannel()
                || !batch.family().equals(profile.templateFamily())
                || !batch.version().equals(profile.templateVersion())
                || !batch.fingerprint().equals(profile.templateFingerprint())) {
            throw BusinessException.conflict(
                    "TEMPLATE_PROFILE_MISMATCH", "来源批次结构与受信模板版本不一致，已停止自动放行");
        }
        List<Long> authorityLocks = jdbc.query(
                "SELECT id FROM app.import_batches WHERE id=? AND batch_type='SOURCE_ORDER' FOR SHARE",
                (resultSet, rowNumber) -> resultSet.getLong(1),
                profile.trustedFromBatchId());
        if (authorityLocks.isEmpty()) {
            throw BusinessException.conflict(
                    "TEMPLATE_PROFILE_MISMATCH", "受信模板的授权依据批次不存在，已停止自动放行");
        }
        BatchTemplate authority = batchTemplate(profile.trustedFromBatchId());
        if (authority.confirmedAt() == null
                || authority.channel() != profile.sourceChannel()
                || !authority.family().equals(profile.templateFamily())
                || !authority.version().equals(profile.templateVersion())
                || !authority.fingerprint().equals(profile.templateFingerprint())) {
            throw BusinessException.conflict(
                    "TEMPLATE_PROFILE_MISMATCH", "受信模板的授权依据已被纠正或失效，已停止自动放行");
        }
        return profile;
    }

    private Optional<TrustedTemplate> findById(long profileId) {
        return jdbc.query(
                        """
                        SELECT id, profile_no, source_channel, template_family, template_version,
                               template_fingerprint, status, trusted_from_batch_id, trusted_at
                        FROM app.source_template_profiles WHERE id=?
                        """,
                        (resultSet, rowNumber) -> template(resultSet),
                        profileId)
                .stream()
                .findFirst();
    }

    private Optional<TrustedTemplate> find(
            SourceChannel channel, String family, String version, String fingerprint) {
        List<TrustedTemplate> matches = jdbc.query(
                """
                SELECT id, profile_no, source_channel, template_family, template_version,
                       template_fingerprint, status, trusted_from_batch_id, trusted_at
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
                resultSet.getLong("trusted_from_batch_id"),
                resultSet.getObject("trusted_at", OffsetDateTime.class));
    }

    /** 与批次确认同事务记录“该批次已经消费哪个 standing authorization”。 */
    void recordConsumedAuthorization(long batchId, TrustedTemplate profile) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("profile_id", profile.id());
        state.put("profile_no", profile.profileNo());
        state.put("stage", "CONFIRMED_PENDING_OUTBOUND");
        jdbc.update(
                """
                UPDATE app.import_batches
                SET error_detail=jsonb_set(
                    COALESCE(error_detail, '{}'::jsonb),
                    '{automatic_release}',
                    ?::jsonb,
                    true)
                WHERE id=?
                """,
                json(state),
                batchId);
    }

    Optional<ConsumedRelease> consumedRelease(long batchId) {
        return jdbc.query(
                        """
                        SELECT confirmed_at,
                               error_detail#>>'{automatic_release,profile_id}' profile_id,
                               error_detail#>>'{automatic_release,profile_no}' profile_no,
                               error_detail#>>'{automatic_release,stage}' stage
                        FROM app.import_batches
                        WHERE id=? AND batch_type='SOURCE_ORDER'
                        """,
                        (resultSet, rowNumber) -> {
                            String profileId = resultSet.getString("profile_id");
                            if (resultSet.getObject("confirmed_at") == null || profileId == null) {
                                return null;
                            }
                            try {
                                return new ConsumedRelease(
                                        Long.parseLong(profileId),
                                        resultSet.getString("profile_no"),
                                        resultSet.getString("stage"));
                            } catch (NumberFormatException exception) {
                                throw BusinessException.conflict(
                                        "AUTOMATIC_RELEASE_STATE_INVALID",
                                        "来源批次的自动放行授权快照损坏，禁止继续出站");
                            }
                        },
                        batchId)
                .stream()
                .filter(Objects::nonNull)
                .findFirst();
    }

    /**
     * 兼容早期自动确认：当批次快照缺失时，只接受同一系统操作人、稳定 request/trace id、
     * 成功确认码和明确 template_profile_id 四项追加审计证据；人工确认不得被推断成自动授权。
     */
    @Transactional
    Optional<ConsumedRelease> recoverLegacyConsumedAuthorization(long batchId, String systemOperator) {
        Boolean alreadyConfirmed = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM app.import_batches "
                        + "WHERE id=? AND batch_type='SOURCE_ORDER' AND confirmed_at IS NOT NULL)",
                Boolean.class,
                batchId);
        if (!Boolean.TRUE.equals(alreadyConfirmed)) {
            // 未确认批次继续走正常 standing authorization 路径；这里不能抢批次行锁，
            // 否则会改变并发归因纠正的既有冲突语义。
            return Optional.empty();
        }
        List<ConfirmedBatch> batches = jdbc.query(
                """
                SELECT confirmed_at, confirmed_by
                FROM app.import_batches
                WHERE id=? AND batch_type='SOURCE_ORDER'
                FOR UPDATE
                """,
                (resultSet, rowNumber) -> new ConfirmedBatch(
                        resultSet.getObject("confirmed_at", OffsetDateTime.class),
                        resultSet.getString("confirmed_by")),
                batchId);
        if (batches.isEmpty()
                || batches.getFirst().confirmedAt() == null
                || !Objects.equals(systemOperator, batches.getFirst().confirmedBy())) {
            return Optional.empty();
        }
        Optional<ConsumedRelease> existing = consumedRelease(batchId);
        if (existing.isPresent()) {
            return existing;
        }

        List<LegacyAuthorizationEvidence> evidence = jdbc.query(
                """
                SELECT DISTINCT ON (profile.id)
                       audit.id audit_id, profile.id profile_id, profile.profile_no
                FROM app.audit_logs audit
                JOIN app.source_template_profiles profile
                  ON profile.id::text=audit.request_payload->>'template_profile_id'
                WHERE audit.service='source-file-import'
                  AND audit.operation='source-orders.confirm'
                  AND audit.business_code='IMPORT_BATCH_CONFIRMED'
                  AND audit.http_status=200
                  -- 仅兼容 marker 上线前已知的 actor 误标版本；当前 SYSTEM no-op
                  -- 审计不得在下一次重试时被提升成历史授权。
                  AND audit.actor_type='HUMAN'
                  AND audit.operator=?
                  AND audit.request_id=?
                  AND audit.trace_id='automatic-release-template-' || profile.id::text || '-batch-' || ?::text
                  AND audit.request_payload->>'batch_id'=?::text
                  AND audit.data_scope='BUSINESS'
                ORDER BY profile.id, audit.id DESC
                """,
                (resultSet, rowNumber) -> new LegacyAuthorizationEvidence(
                        resultSet.getLong("audit_id"),
                        resultSet.getLong("profile_id"),
                        resultSet.getString("profile_no")),
                systemOperator,
                "automatic-release-batch-" + batchId,
                batchId,
                batchId);
        if (evidence.isEmpty()) {
            return Optional.empty();
        }
        if (evidence.size() != 1) {
            throw BusinessException.conflict(
                    "AUTOMATIC_RELEASE_STATE_INVALID",
                    "来源批次存在冲突的历史自动放行审计，禁止继续出站");
        }
        LegacyAuthorizationEvidence recovered = evidence.getFirst();
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("profile_id", recovered.profileId());
        state.put("profile_no", recovered.profileNo());
        state.put("stage", "CONFIRMED_PENDING_OUTBOUND");
        state.put("recovered_from_audit_log_id", recovered.auditId());
        int updated = jdbc.update(
                """
                UPDATE app.import_batches
                SET error_detail=jsonb_set(
                    COALESCE(error_detail, '{}'::jsonb),
                    '{automatic_release}',
                    ?::jsonb,
                    true)
                WHERE id=? AND confirmed_at IS NOT NULL AND confirmed_by=?
                  AND NOT jsonb_exists(COALESCE(error_detail, '{}'::jsonb), 'automatic_release')
                """,
                json(state),
                batchId,
                systemOperator);
        if (updated != 1) {
            throw BusinessException.conflict(
                    "AUTOMATIC_RELEASE_STATE_INVALID",
                    "来源批次历史自动放行授权恢复冲突，禁止继续出站");
        }
        return Optional.of(new ConsumedRelease(
                recovered.profileId(), recovered.profileNo(), "CONFIRMED_PENDING_OUTBOUND"));
    }

    void recordAutomaticReleaseStage(
            long batchId,
            long profileId,
            String stage,
            String errorCode,
            List<String> failedShipmentIds) {
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("stage", stage);
        patch.put("error_code", errorCode);
        patch.put("failed_shipment_ids", failedShipmentIds == null ? List.of() : List.copyOf(failedShipmentIds));
        int updated = jdbc.update(
                """
                UPDATE app.import_batches
                SET error_detail=jsonb_set(
                    error_detail,
                    '{automatic_release}',
                    (error_detail->'automatic_release') || ?::jsonb,
                    true)
                WHERE id=?
                  AND error_detail#>>'{automatic_release,profile_id}'=?
                """,
                json(patch),
                batchId,
                Long.toString(profileId));
        if (updated != 1) {
            throw BusinessException.conflict(
                    "AUTOMATIC_RELEASE_STATE_INVALID",
                    "来源批次缺少已消费的自动放行授权，禁止更新出站阶段");
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
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
            long trustedFromBatchId,
            OffsetDateTime trustedAt) {}

    record ConsumedRelease(long profileId, String profileNo, String stage) {}

    private record ConfirmedBatch(OffsetDateTime confirmedAt, String confirmedBy) {}

    private record LegacyAuthorizationEvidence(long auditId, long profileId, String profileNo) {}

    private record BatchTemplate(
            SourceChannel channel,
            String family,
            String version,
            String fingerprint,
            OffsetDateTime confirmedAt) {}
}
