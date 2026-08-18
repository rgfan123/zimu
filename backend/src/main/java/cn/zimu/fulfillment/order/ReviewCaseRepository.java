package cn.zimu.fulfillment.order;

import cn.zimu.fulfillment.order.domain.ReviewCase;
import cn.zimu.fulfillment.order.domain.ReviewCaseStatus;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReviewCaseRepository extends JpaRepository<ReviewCase, Long>, JpaSpecificationExecutor<ReviewCase> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ReviewCase r where r.id = :id")
    Optional<ReviewCase> findByIdForUpdate(@Param("id") Long id);

    List<ReviewCase> findByOrderIdOrderByCreatedAtAsc(Long orderId);

    boolean existsByOrderIdAndStatus(Long orderId, ReviewCaseStatus status);

    boolean existsByOrderIdAndStatusAndIdNot(Long orderId, ReviewCaseStatus status, Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            """
            select r from ReviewCase r
            where r.reasonCode = :reason and r.shipmentId = :shipmentId and r.status = :status
            """)
    Optional<ReviewCase> findShipmentCaseForUpdate(
            @Param("reason") String reason,
            @Param("shipmentId") Long shipmentId,
            @Param("status") ReviewCaseStatus status);

    @Query(
            """
            select case when count(r) > 0 then true else false end
            from ReviewCase r
            where r.reasonCode = :reason and r.orderId = :orderId
              and r.orderLineId is null and r.status = :status
            """)
    boolean existsOpenOrderCase(
            @Param("reason") String reason, @Param("orderId") Long orderId, @Param("status") ReviewCaseStatus status);

    @Query(
            """
            select case when count(r) > 0 then true else false end
            from ReviewCase r
            where r.reasonCode = :reason and r.orderId = :orderId
              and r.orderLineId = :lineId and r.status = :status
            """)
    boolean existsOpenLineCase(
            @Param("reason") String reason,
            @Param("orderId") Long orderId,
            @Param("lineId") Long lineId,
            @Param("status") ReviewCaseStatus status);

    @Query(
            """
            select case when count(r) > 0 then true else false end
            from ReviewCase r
            where r.reasonCode = :reason and r.messageSubmissionId = :submissionId
              and r.status = :status
            """)
    boolean existsOpenSubmissionCase(
            @Param("reason") String reason,
            @Param("submissionId") Long submissionId,
            @Param("status") ReviewCaseStatus status);

    @Query(
            """
            select r from ReviewCase r
            where r.status = :status and (
              r.messageSubmissionId = :submissionId
              or r.orderDraftId in (
                select d.id from OrderDraft d where d.submissionId = :submissionId
              )
              or r.providerTrackingDraftId in (
                select d.id from ProviderTrackingDraft d where d.submissionId = :submissionId
              )
            )
            """)
    List<ReviewCase> findOpenBySubmissionId(
            @Param("submissionId") Long submissionId, @Param("status") ReviewCaseStatus status);

    @Query(
            """
            select case when count(r) > 0 then true else false end
            from ReviewCase r
            where r.reasonCode = :reason and r.orderDraftId = :orderDraftId
              and r.status = :status
            """)
    boolean existsOpenOrderDraftCase(
            @Param("reason") String reason,
            @Param("orderDraftId") Long orderDraftId,
            @Param("status") ReviewCaseStatus status);

    @Query(
            """
            select r from ReviewCase r
            where r.orderDraftId = :orderDraftId and r.status = :status
            """)
    List<ReviewCase> findOpenByOrderDraftId(
            @Param("orderDraftId") Long orderDraftId, @Param("status") ReviewCaseStatus status);

    @Query(
            """
            select case when count(r) > 0 then true else false end
            from ReviewCase r
            where r.reasonCode = :reason and r.providerTrackingDraftId = :trackingDraftId
              and r.status = :status
            """)
    boolean existsOpenTrackingDraftCase(
            @Param("reason") String reason,
            @Param("trackingDraftId") Long trackingDraftId,
            @Param("status") ReviewCaseStatus status);

    @Query(
            """
            select r from ReviewCase r
            where r.providerTrackingDraftId = :trackingDraftId and r.status = :status
            """)
    List<ReviewCase> findOpenByTrackingDraftId(
            @Param("trackingDraftId") Long trackingDraftId, @Param("status") ReviewCaseStatus status);
}
