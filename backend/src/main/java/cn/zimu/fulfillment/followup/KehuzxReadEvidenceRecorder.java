package cn.zimu.fulfillment.followup;

/** Persistence seam kept separate from the remote client for deterministic contract tests. */
@FunctionalInterface
public interface KehuzxReadEvidenceRecorder {
    void record(KehuzxReadEvidence evidence);

    default void recordFailure(KehuzxReadFailure failure) {}
}
