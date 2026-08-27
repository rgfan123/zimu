package cn.zimu.fulfillment.followup;

import java.time.Instant;

/** Stable failure evidence from an approved remote read; never contains raw exception text. */
public record KehuzxReadFailure(
        String agentRunId,
        String toolName,
        String failureCode,
        String contractVersion,
        String upstreamCommit,
        Instant queriedAt) {}
