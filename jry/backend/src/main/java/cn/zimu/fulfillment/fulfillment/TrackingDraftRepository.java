package cn.zimu.fulfillment.fulfillment;

import cn.zimu.fulfillment.order.domain.ReviewCaseStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TrackingDraftRepository
        extends JpaRepository<ProviderTrackingDraft, Long>, JpaSpecificationExecutor<ProviderTrackingDraft> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from ProviderTrackingDraft d where d.id = :id")
    Optional<ProviderTrackingDraft> findByIdForUpdate(@Param("id") Long id);

    List<ProviderTrackingDraft> findBySubmissionIdOrderByLineNoAsc(Long submissionId);

    @Query("select d.submissionId from ProviderTrackingDraft d where d.id = :id")
    Optional<Long> findSubmissionIdById(@Param("id") Long id);

    long countBySubmissionIdAndStatus(Long submissionId, ProviderTrackingDraft.Status status);

    @Query(
            """
            select count(d) from ProviderTrackingDraft d
            where d.submissionId = :submissionId and d.status = :draftStatus
              and exists (
                select r.id from ReviewCase r
                where r.providerTrackingDraftId = d.id and r.status = :caseStatus
              )
            """)
    long countActionableBySubmissionIdAndStatus(
            @Param("submissionId") Long submissionId,
            @Param("draftStatus") ProviderTrackingDraft.Status draftStatus,
            @Param("caseStatus") ReviewCaseStatus caseStatus);

    boolean existsByTrackingNoAndCarrierCode(String trackingNo, String carrierCode);
}
