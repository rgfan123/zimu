package cn.zimu.fulfillment.message;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MessageSubmissionRepository extends JpaRepository<MessageSubmission, Long> {

    Optional<MessageSubmission> findBySourceMessageId(Long sourceMessageId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from MessageSubmission s where s.id = :id")
    Optional<MessageSubmission> findByIdForUpdate(@Param("id") Long id);
}
