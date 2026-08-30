package cn.zimu.fulfillment.file;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 历史误归因批次通过公开接口追加纠正，原始批次、订单与审计事实保持不变。 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "app.gateway.basic-auth.username=source-attribution-test",
            "app.gateway.basic-auth.password=source-attribution-password",
            "app.message-worker.enabled=false",
            "app.file-store.root=${java.io.tmpdir}/zimu-source-attribution-test"
        })
class SourceAttributionCorrectionApiTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired SourceBatchAutomaticReleaseService automaticRelease;

    @Test
    void correctionIsAppendOnlyIdempotentAndLeavesRecordedFactsUnchanged() {
        long batchId = jdbc.queryForObject(
                """
                INSERT INTO app.import_batches
                    (batch_no,batch_type,import_mode,revision_no,source_channel,
                     template_family,template_version,template_fingerprint,
                     original_file_name,content_sha256,file_ref,status,uploaded_by,processed_at)
                VALUES ('IMP-HISTORICAL-SOURCE-001','SOURCE_ORDER','NEW',1,'WANGQI',
                        'WANGQI_SOURCE_ORDER','v1','WANGQI-v1-historical',
                        '历史来源.xlsx',repeat('a',64),'historical-source-file','COMPLETED',
                        'source-attribution-test',CURRENT_TIMESTAMP)
                RETURNING id
                """,
                Long.class);
        long orderId = jdbc.queryForObject(
                """
                INSERT INTO app.orders
                    (order_no,data_scope,source_channel,source_ref,source_ref_kind,source_version,
                     source_import_batch_id,order_status,settlement_method,settlement_time,
                     receiver_name,receiver_phone,receiver_address)
                VALUES ('ORD-HISTORICAL-SOURCE-001','BUSINESS','WANGQI','HISTORICAL-SOURCE-001',
                        'PROVIDED','v1',?,'RECEIVED','OTHER',CURRENT_TIMESTAMP,
                        '测试收货人','13800000000','测试地址1号')
                RETURNING id
                """,
                Long.class,
                batchId);
        Map<String, Object> body = Map.of(
                "source_channel_display_name", "大者",
                "reason", "用户确认历史十五列表格实际来自大者",
                "evidence", Map.of("source", "人工确认"));
        HttpHeaders headers = writeHeaders("source-attribution-001");
        ResponseEntity<Map> corrected = http.exchange(
                "/api/v1/import-batches/" + batchId + "/source-attribution-corrections",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class);
        ResponseEntity<Map> replayed = http.exchange(
                "/api/v1/import-batches/" + batchId + "/source-attribution-corrections",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                Map.class);

        assertThat(corrected.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replayed.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replayed.getBody()).isEqualTo(corrected.getBody());
        assertThat(corrected.getBody())
                .containsEntry("recorded_source_channel_display_name", "大者")
                .containsEntry("effective_source_channel_display_name", "大者")
                .containsEntry("invalidated_source_return_count", 0)
                .containsEntry("successor_source_return_export_id", null);

        assertThat(jdbc.queryForObject(
                "SELECT source_channel FROM app.import_batches WHERE id=?", String.class, batchId))
                .isEqualTo("WANGQI");
        assertThat(jdbc.queryForObject(
                "SELECT source_channel FROM app.orders WHERE id=?", String.class, orderId))
                .isEqualTo("WANGQI");
        assertThat(jdbc.queryForObject(
                "SELECT effective_source_channel FROM app.v_import_batch_effective_source WHERE import_batch_id=?",
                String.class,
                batchId)).isEqualTo("DAZHE");
        assertThat(http.getForObject("/api/v1/orders/" + orderId, Map.class))
                .containsEntry("source_channel", "DAZHE");
        assertThat(http.getForObject(
                        "/api/v1/orders?source_channel=DAZHE&query=HISTORICAL-SOURCE-001&page=0&size=20",
                        Map.class))
                .containsEntry("total_elements", 1);
        assertThat(http.getForObject(
                        "/api/v1/analytics/channels?source_channel=DAZHE",
                        Map[].class))
                .anySatisfy(row -> assertThat(row).containsEntry("source_channel", "DAZHE"));
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.source_attribution_corrections WHERE import_batch_id=?",
                Integer.class,
                batchId)).isOne();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.order_events WHERE order_id=? AND event_type_code='SOURCE_ATTRIBUTION_CORRECTED'",
                Integer.class,
                orderId)).isOne();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.order_versions WHERE order_id=? AND change_reason='来源归因纠正'",
                Integer.class,
                orderId)).isOne();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.audit_logs WHERE request_id='req-source-attribution-001'",
                Integer.class)).isOne();
    }

    @Test
    void realWanqiBatchCannotBeRelabeledAsDazhe() {
        long batchId = jdbc.queryForObject(
                """
                INSERT INTO app.import_batches
                    (batch_no,batch_type,source_channel,template_family,template_version,template_fingerprint,
                     original_file_name,content_sha256,file_ref,status,uploaded_by,processed_at,settlement_missing)
                VALUES ('IMP-WANQI-SCOPE-001','SOURCE_ORDER','WANQI','WANQI_SOURCE_ORDER','v1-52-columns',
                        'WANQI-v1-52-columns-test','万齐.xlsx',repeat('c',64),'wanqi-source-file','COMPLETED',
                        'source-attribution-test',CURRENT_TIMESTAMP,true)
                RETURNING id
                """,
                Long.class);
        ResponseEntity<Map> response = http.exchange(
                "/api/v1/import-batches/" + batchId + "/source-attribution-corrections",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "source_channel_display_name", "大者",
                        "reason", "错误尝试",
                        "evidence", Map.of()), writeHeaders("source-attribution-scope-001")),
                Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(response.getBody()).containsEntry("business_code", "SOURCE_ATTRIBUTION_SCOPE_UNSUPPORTED");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app.source_attribution_corrections WHERE import_batch_id=?",
                Integer.class,
                batchId)).isZero();
    }

    @Test
    void correctedBatchTrustsOnlyItsEffectiveTemplateIdentity() {
        String suffix = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        long batchId = jdbc.queryForObject(
                """
                INSERT INTO app.import_batches
                    (batch_no,batch_type,import_mode,revision_no,source_channel,
                     template_family,template_version,template_fingerprint,
                     original_file_name,content_sha256,file_ref,status,uploaded_by,processed_at,
                     confirmed_at,confirmed_by)
                VALUES (?, 'SOURCE_ORDER','NEW',1,'WANGQI',
                        'WANGQI_SOURCE_ORDER','v1',?,
                        '历史来源.xlsx',?,?,'COMPLETED','source-attribution-test',CURRENT_TIMESTAMP,
                        CURRENT_TIMESTAMP,'source-attribution-test')
                RETURNING id
                """,
                Long.class,
                "IMP-CORRECTED-TRUST-" + suffix,
                "WANGQI-v1-trust-" + suffix,
                suffix.repeat(6).substring(0, 64),
                "test://corrected-trust/" + suffix);
        ResponseEntity<Map> corrected = http.exchange(
                "/api/v1/import-batches/" + batchId + "/source-attribution-corrections",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "source_channel_display_name", "大者",
                        "reason", "确认历史十五列表格实际来自大者",
                        "evidence", Map.of("source", "人工确认")),
                        writeHeaders("source-attribution-trust-correct-" + suffix)),
                Map.class);
        assertThat(corrected.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<Map> trusted = http.exchange(
                "/api/v1/import-batches/" + batchId + "/trust-template",
                HttpMethod.POST,
                new HttpEntity<>(null, writeHeaders("source-attribution-trust-profile-" + suffix)),
                Map.class);

        assertThat(trusted.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(trusted.getBody())
                .containsEntry("source_channel", "DAZHE")
                .containsEntry("template_family", "DAZHE_SOURCE_ORDER")
                .containsEntry("template_version", "v1")
                .containsEntry("template_fingerprint", "DAZHE-v1-trust-" + suffix);
        assertThat(jdbc.queryForObject(
                        """
                        SELECT count(*) FROM app.source_template_profiles
                        WHERE source_channel='WANGQI' OR template_family='WANGQI_SOURCE_ORDER'
                           OR template_fingerprint=?
                        """,
                        Integer.class,
                        "WANGQI-v1-trust-" + suffix))
                .isZero();

        long revisionId = jdbc.queryForObject(
                """
                INSERT INTO app.import_batches
                    (batch_no,batch_type,import_mode,parent_import_batch_id,revision_no,source_channel,
                     template_family,template_version,template_fingerprint,
                     original_file_name,content_sha256,file_ref,status,error_detail,uploaded_by,processed_at)
                VALUES (?, 'SOURCE_ORDER','REVISION',?,2,'DAZHE',
                        'DAZHE_SOURCE_ORDER','v1',?,
                        '历史来源修订.xlsx',?,?,'COMPLETED',
                        '{"candidate_status":"PENDING","candidate_snapshot_version":2,
                          "source_order_candidates":[]}'::jsonb,
                        'source-attribution-test',CURRENT_TIMESTAMP)
                RETURNING id
                """,
                Long.class,
                "IMP-CORRECTED-REVISION-" + suffix,
                batchId,
                "DAZHE-v1-trust-" + suffix,
                (suffix + "e").repeat(5).substring(0, 64),
                "test://corrected-revision/" + suffix);

        assertThat(automaticRelease.releaseIfTrusted(revisionId)).isTrue();
        assertThat(jdbc.queryForObject(
                        "SELECT confirmed_at IS NOT NULL FROM app.import_batches WHERE id=?",
                        Boolean.class,
                        revisionId))
                .isTrue();
    }

    private HttpHeaders writeHeaders(String key) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", key);
        headers.set("X-Request-Id", "req-" + key);
        headers.set("X-Operator", "source-attribution-test");
        headers.setBasicAuth("source-attribution-test", "source-attribution-password");
        return headers;
    }
}
