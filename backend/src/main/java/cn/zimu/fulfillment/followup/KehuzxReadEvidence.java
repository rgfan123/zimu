package cn.zimu.fulfillment.followup;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

/** Immutable evidence captured from one approved Kehuzx read tool call. */
public record KehuzxReadEvidence(
        String agentRunId,
        String toolName,
        String argumentsDigest,
        String responseDigest,
        JsonNode responsePayload,
        String contractVersion,
        String upstreamCommit,
        Instant queriedAt) {}
