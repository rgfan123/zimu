package cn.zimu.fulfillment.common.audit;

import cn.zimu.fulfillment.common.domain.DataScope;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long>, JpaSpecificationExecutor<AuditLog> {

    Optional<AuditLog> findByIdAndDataScope(Long id, DataScope dataScope);
}
