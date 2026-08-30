package cn.zimu.fulfillment.file;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.web.CommandContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** AutomaticRelease 的模板授权与有效来源身份必须在最终确认事务内保持原子。 */
@Testcontainers
@SpringBootTest(properties = {
    "app.source-order-intake-worker.enabled=false",
    "app.message-worker.enabled=false"
})
class SourceTemplateProfileAuthorizationIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired SourceTemplateProfileService profiles;
    @Autowired SourceBatchAutomaticReleaseService automaticRelease;

    @Test
    void concurrentRevocationWinsBeforeAutomaticReleaseCanConfirm() throws Exception {
        String suffix = suffix();
        String fingerprint = "DAZHE-v1-revoke-" + suffix;
        long seedBatchId = insertBatch(
                "DAZHE", "DAZHE_SOURCE_ORDER", "v1", fingerprint, true, suffix + "a");
        long profileId = profiles.trust(
                        seedBatchId,
                        "trust-revoke-" + suffix,
                        new CommandContext("trust-revoke-" + suffix, "trust-revoke-" + suffix, "source-ops"))
                .result()
                .entrySet()
                .stream()
                .filter(entry -> "id".equals(entry.getKey()))
                .map(entry -> Long.parseLong(entry.getValue().toString()))
                .findFirst()
                .orElseThrow();
        long pendingBatchId = insertBatch(
                "DAZHE", "DAZHE_SOURCE_ORDER", "v1", fingerprint, false, suffix + "b");

        CompletableFuture<BusinessException> release;
        try (Connection revocation = dataSource.getConnection()) {
            revocation.setAutoCommit(false);
            try (PreparedStatement update = revocation.prepareStatement(
                    "UPDATE app.source_template_profiles "
                            + "SET status='REVOKED', revoked_by='security-ops', revoked_at=? WHERE id=?")) {
                update.setObject(1, OffsetDateTime.now());
                update.setLong(2, profileId);
                assertThat(update.executeUpdate()).isEqualTo(1);
            }
            release = CompletableFuture.supplyAsync(() -> releaseFailure(pendingBatchId));
            Thread.sleep(250);
            assertThat(release.isDone())
                    .as("最终授权复验必须等待正在提交的 profile 撤销")
                    .isFalse();
            revocation.commit();
        }

        BusinessException blocked = release.get(5, TimeUnit.SECONDS);
        assertThat(blocked.getBusinessCode()).isEqualTo("TEMPLATE_PROFILE_REVOKED");
        assertUnconfirmedWithoutOrders(pendingBatchId);
        assertThatThrownBy(() -> jdbc.update(
                        "UPDATE app.source_template_profiles SET revoked_by=NULL WHERE id=?",
                        profileId))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                        "UPDATE app.source_template_profiles SET revoked_by='rewritten' WHERE id=?",
                        profileId))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void concurrentAttributionCorrectionCannotReuseTheOldProfileIdentity() throws Exception {
        String suffix = suffix();
        String fingerprint = "WANGQI-v1-correction-" + suffix;
        long seedBatchId = insertBatch(
                "WANGQI", "WANGQI_SOURCE_ORDER", "v1", fingerprint, true, suffix + "c");
        profiles.trust(
                seedBatchId,
                "trust-correction-" + suffix,
                new CommandContext(
                        "trust-correction-" + suffix, "trust-correction-" + suffix, "source-ops"));
        long pendingBatchId = insertBatch(
                "WANGQI", "WANGQI_SOURCE_ORDER", "v1", fingerprint, false, suffix + "d");

        CompletableFuture<BusinessException> release;
        try (Connection correction = dataSource.getConnection()) {
            correction.setAutoCommit(false);
            try (PreparedStatement insert = correction.prepareStatement(
                    """
                    INSERT INTO app.source_attribution_corrections
                        (import_batch_id, correction_no, attributed_source_channel,
                         attributed_template_family, attributed_template_fingerprint,
                         reason, evidence, corrected_by)
                    VALUES (?, 1, 'DAZHE', 'DAZHE_SOURCE_ORDER', ?,
                            '并发授权回归测试', '{}'::jsonb, 'source-ops')
                    """)) {
                insert.setLong(1, pendingBatchId);
                insert.setString(2, "DAZHE-v1-correction-" + suffix);
                assertThat(insert.executeUpdate()).isEqualTo(1);
            }
            release = CompletableFuture.supplyAsync(() -> releaseFailure(pendingBatchId));
            Thread.sleep(250);
            assertThat(release.isDone())
                    .as("最终授权复验必须等待正在提交的来源归因纠正")
                    .isFalse();
            correction.commit();
        }

        BusinessException blocked = release.get(5, TimeUnit.SECONDS);
        assertThat(blocked.getBusinessCode()).isEqualTo("TEMPLATE_PROFILE_MISMATCH");
        assertUnconfirmedWithoutOrders(pendingBatchId);
    }

    private BusinessException releaseFailure(long batchId) {
        try {
            automaticRelease.releaseIfTrusted(batchId);
            throw new AssertionError("AutomaticRelease 应被并发授权变化阻断");
        } catch (BusinessException exception) {
            return exception;
        }
    }

    private long insertBatch(
            String sourceChannel,
            String family,
            String version,
            String fingerprint,
            boolean confirmed,
            String hashSalt) {
        return jdbc.queryForObject(
                """
                INSERT INTO app.import_batches
                    (batch_no, batch_type, import_mode, revision_no, source_channel,
                     template_family, template_version, template_fingerprint,
                     original_file_name, content_sha256, file_ref, status, uploaded_by,
                     processed_at, confirmed_at, confirmed_by)
                VALUES (?, 'SOURCE_ORDER', 'NEW', 1, ?, ?, ?, ?, ?, ?, ?, 'COMPLETED',
                        'source-ops', CURRENT_TIMESTAMP,
                        CASE WHEN ? THEN CURRENT_TIMESTAMP END,
                        CASE WHEN ? THEN 'source-ops' END)
                RETURNING id
                """,
                Long.class,
                "IMP-TEMPLATE-AUTH-" + UUID.randomUUID().toString().replace("-", "").toUpperCase(),
                sourceChannel,
                family,
                version,
                fingerprint,
                "template-auth-" + hashSalt + ".xlsx",
                hashSalt.repeat(5).substring(0, 64),
                "test://template-auth/" + hashSalt,
                confirmed,
                confirmed);
    }

    private void assertUnconfirmedWithoutOrders(long batchId) {
        assertThat(jdbc.queryForObject(
                        "SELECT confirmed_at IS NULL FROM app.import_batches WHERE id=?",
                        Boolean.class,
                        batchId))
                .isTrue();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM app.orders WHERE source_import_batch_id=?",
                        Integer.class,
                        batchId))
                .isZero();
    }

    private String suffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
