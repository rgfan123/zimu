package cn.zimu.fulfillment.followup;

import cn.zimu.fulfillment.agent.AgentOutcome;
import cn.zimu.fulfillment.agent.AgentRunContext;
import cn.zimu.fulfillment.agent.AgentRunResult;
import cn.zimu.fulfillment.agent.AgentRuntimeFacade;
import cn.zimu.fulfillment.message.AsyncTaskStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Executes one pinned follow-up Agent run; deterministic persistence lives in the applier. */
@Service
public class BusinessFollowUpOrganizationService {

    private static final Pattern CUSTOMER_IDENTIFIER = Pattern.compile(
            "(?i)(?<![A-Z0-9])KH-[0-9]{6}-[0-9]{3}(?![A-Z0-9._-])");

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AgentRuntimeFacade agents;
    private final KehuzxRemoteReadTools kehuzxTools;
    private final BusinessFollowUpDraftApplicationService application;
    private final AsyncTaskStore tasks;

    public BusinessFollowUpOrganizationService(
            JdbcTemplate jdbc,
            ObjectMapper mapper,
            AgentRuntimeFacade agents,
            KehuzxRemoteReadTools kehuzxTools,
            BusinessFollowUpDraftApplicationService application,
            AsyncTaskStore tasks) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.agents = agents;
        this.kehuzxTools = kehuzxTools;
        this.application = application;
        this.tasks = tasks;
    }

    public void organize(AsyncTaskStore.AsyncTask task, String owner, Duration lease) {
        Work work = load(task.payloadRef());
        String runId = AgentRuntimeFacade.newRunId();
        List<String> customerIdentifiers = customerIdentifiersForModel(work.employeeDraft());
        kehuzxTools.authorizeRun(runId, customerIdentifiers);
        try {
            String input = mapper.writeValueAsString(Map.of(
                    "source", "ZIMU",
                    "followup_id", String.valueOf(work.followupId()),
                    "followup_no", work.followupNo(),
                    "message_submission_id", String.valueOf(work.submissionId()),
                    "source_revision", work.sourceRevision(),
                    "customer_identifiers", customerIdentifiers));
            AgentRunResult result = invokeWithLeaseHeartbeat(task, owner, lease, work, runId, input);
            if (result.outcome() == AgentOutcome.FAILED || result.outcome() == AgentOutcome.REJECTED) {
                throw new FollowUpOrganizationException(
                        result.error() == null ? "AGENT_RUN_FAILED" : result.error());
            }
            application.apply(task, owner, work, result);
        } catch (JsonProcessingException ex) {
            throw new FollowUpOrganizationException("FOLLOWUP_INPUT_INVALID", ex);
        } finally {
            kehuzxTools.completeRun(runId);
        }
    }

    private AgentRunResult invokeWithLeaseHeartbeat(
            AsyncTaskStore.AsyncTask task,
            String owner,
            Duration lease,
            Work work,
            String runId,
            String input) {
        Duration effectiveLease = lease == null || lease.isNegative() || lease.isZero()
                ? Duration.ofSeconds(120)
                : lease;
        AtomicBoolean leaseHeld = new AtomicBoolean(
                tasks.renewLease(task.id(), owner, effectiveLease));
        if (!leaseHeld.get()) {
            throw new FollowUpOrganizationException("FOLLOWUP_TASK_LEASE_LOST");
        }
        long heartbeatSeconds = Math.max(1, effectiveLease.toSeconds() / 3);
        try (var heartbeat = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("followup-lease-heartbeat-", 0).factory())) {
            ScheduledFuture<?> renewal = heartbeat.scheduleAtFixedRate(() -> {
                try {
                    if (!tasks.renewLease(task.id(), owner, effectiveLease)) {
                        leaseHeld.set(false);
                    }
                } catch (RuntimeException ignored) {
                    leaseHeld.set(false);
                }
            }, heartbeatSeconds, heartbeatSeconds, TimeUnit.SECONDS);
            try {
                AgentRunResult result = agents.invokePinnedWithRunId(
                        work.agentSlug(),
                        work.agentVersion(),
                        runId,
                        input,
                        AgentRunContext.empty().withBusinessEntity(
                                "BUSINESS_FOLLOWUP", String.valueOf(work.followupId())));
                if (!leaseHeld.get()) {
                    throw new FollowUpOrganizationException("FOLLOWUP_TASK_LEASE_LOST");
                }
                return result;
            } finally {
                renewal.cancel(true);
            }
        }
    }

    static List<String> customerIdentifiersForModel(String value) {
        LinkedHashSet<String> identifiers = new LinkedHashSet<>();
        if (value != null) {
            CUSTOMER_IDENTIFIER.matcher(value).results()
                    .map(result -> result.group().toUpperCase(java.util.Locale.ROOT))
                    .limit(20)
                    .forEach(identifiers::add);
        }
        return List.copyOf(identifiers);
    }

    @Transactional(readOnly = true)
    Work load(String payloadRef) {
        PayloadRef ref = PayloadRef.parse(payloadRef);
        List<Work> rows = jdbc.query(
                """
                SELECT bf.id, bf.followup_no, bf.message_submission_id, bf.employee_draft,
                       bf.source_revision, bf.agent_slug, bf.agent_version
                FROM app.business_followups bf
                WHERE bf.id = ?
                """,
                (rs, row) -> new Work(
                        rs.getLong("id"),
                        rs.getString("followup_no"),
                        rs.getLong("message_submission_id"),
                        rs.getString("employee_draft"),
                        rs.getInt("source_revision"),
                        rs.getString("agent_slug"),
                        rs.getInt("agent_version")),
                ref.followupId());
        Work work = rows.stream().findFirst()
                .orElseThrow(() -> new FollowUpOrganizationException("FOLLOWUP_NOT_FOUND"));
        if (work.sourceRevision() != ref.sourceRevision()) {
            throw new FollowUpOrganizationException("FOLLOWUP_SOURCE_SUPERSEDED");
        }
        if (work.agentSlug() == null || work.agentVersion() < 1) {
            throw new FollowUpOrganizationException("FOLLOWUP_AGENT_NOT_PINNED");
        }
        return work;
    }

    public record Work(
            long followupId,
            String followupNo,
            long submissionId,
            String employeeDraft,
            int sourceRevision,
            String agentSlug,
            int agentVersion) {}

    record PayloadRef(long followupId, int sourceRevision) {
        static PayloadRef parse(String value) {
            if (value == null || !value.matches("business-followup:[1-9][0-9]*:revision:[1-9][0-9]*")) {
                throw new FollowUpOrganizationException("FOLLOWUP_TASK_REF_INVALID");
            }
            String[] parts = value.split(":");
            try {
                return new PayloadRef(Long.parseLong(parts[1]), Integer.parseInt(parts[3]));
            } catch (NumberFormatException ex) {
                throw new FollowUpOrganizationException("FOLLOWUP_TASK_REF_INVALID", ex);
            }
        }
    }

    public static class FollowUpOrganizationException extends RuntimeException {
        private final String code;

        FollowUpOrganizationException(String code) {
            super(code);
            this.code = code;
        }

        FollowUpOrganizationException(String code, Throwable cause) {
            super(code, cause);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
