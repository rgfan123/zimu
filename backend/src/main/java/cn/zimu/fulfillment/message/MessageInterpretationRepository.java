package cn.zimu.fulfillment.message;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MessageInterpretationRepository extends JpaRepository<MessageInterpretation, Long> {

    List<MessageInterpretation> findBySubmissionIdOrderByVersionDesc(Long submissionId);

    Optional<MessageInterpretation> findTopBySubmissionIdOrderByVersionDesc(Long submissionId);

    @Query("select coalesce(max(i.version), 0) from MessageInterpretation i where i.submissionId = :submissionId")
    int currentVersion(@Param("submissionId") Long submissionId);
}
