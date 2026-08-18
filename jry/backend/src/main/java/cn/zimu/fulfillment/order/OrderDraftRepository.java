package cn.zimu.fulfillment.order;

import cn.zimu.fulfillment.order.domain.ReviewCaseStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderDraftRepository
        extends JpaRepository<OrderDraft, Long>, JpaSpecificationExecutor<OrderDraft> {

    Optional<OrderDraft> findByDraftNo(String draftNo);

    List<OrderDraft> findBySubmissionIdOrderByIdAsc(Long submissionId);

    @Query("select d.submissionId from OrderDraft d where d.id = :id")
    Optional<Long> findSubmissionIdById(@Param("id") Long id);

    long countBySubmissionIdAndStatus(Long submissionId, OrderDraft.Status status);

    @Query(
            """
            select count(d) from OrderDraft d
            where d.submissionId = :submissionId and d.status = :draftStatus
              and exists (
                select r.id from ReviewCase r
                where r.orderDraftId = d.id and r.status = :caseStatus
              )
            """)
    long countActionableBySubmissionIdAndStatus(
            @Param("submissionId") Long submissionId,
            @Param("draftStatus") OrderDraft.Status draftStatus,
            @Param("caseStatus") ReviewCaseStatus caseStatus);

    long countBySubmissionId(Long submissionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from OrderDraft d where d.id = :id")
    Optional<OrderDraft> findByIdForUpdate(@Param("id") Long id);

    Page<OrderDraft> findByStatusOrderByCreatedAtDesc(OrderDraft.Status status, Pageable pageable);

    Page<OrderDraft> findBySubmissionIdOrderByCreatedAtDesc(Long submissionId, Pageable pageable);

    Page<OrderDraft> findByStatusAndSubmissionIdOrderByCreatedAtDesc(
            OrderDraft.Status status, Long submissionId, Pageable pageable);
}
