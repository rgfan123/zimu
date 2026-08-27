package cn.zimu.fulfillment.followup;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Stores remote read payloads privately; logs contain only stable identifiers and failure codes. */
@Repository
public class JdbcKehuzxReadEvidenceRecorder implements KehuzxReadEvidenceRecorder {

    private final JdbcTemplate jdbc;

    public JdbcKehuzxReadEvidenceRecorder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void record(KehuzxReadEvidence evidence) {
        jdbc.update(
                """
                INSERT INTO app.kehuzx_read_evidence
                    (agent_run_id, tool_name, arguments_digest, response_digest,
                     response_payload, contract_version, upstream_commit, queried_at)
                VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?)
                """,
                evidence.agentRunId(),
                evidence.toolName(),
                evidence.argumentsDigest(),
                evidence.responseDigest(),
                evidence.responsePayload().toString(),
                evidence.contractVersion(),
                evidence.upstreamCommit(),
                java.sql.Timestamp.from(evidence.queriedAt()));
    }

    @Override
    public void recordFailure(KehuzxReadFailure failure) {
        jdbc.update(
                """
                INSERT INTO app.kehuzx_read_failures
                    (agent_run_id, tool_name, failure_code, contract_version,
                     upstream_commit, queried_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                failure.agentRunId(),
                failure.toolName(),
                failure.failureCode(),
                failure.contractVersion(),
                failure.upstreamCommit(),
                java.sql.Timestamp.from(failure.queriedAt()));
    }
}
