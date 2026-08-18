package cn.zimu.fulfillment.common.audit;

import cn.zimu.fulfillment.common.domain.DataScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 共享审计写入服务。所有外部调用票（Connector / JD Client / Excel 闭环）复用本服务，
 * 不重复实现审计写入。应在调用方的业务事务内调用以保证原子性。
 */
@Service
public class AuditLogService {

    private final AuditLogRepository repository;
    private final ObjectMapper objectMapper;
    private final EntityManager entityManager;

    public AuditLogService(
            AuditLogRepository repository, ObjectMapper objectMapper, EntityManager entityManager) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.entityManager = entityManager;
    }

    /** 审计命令；requestPayload / responsePayload 为任意对象，写入前统一转 Map 并脱敏。 */
    public static final class AuditCommand {

        private DataScope dataScope = DataScope.BUSINESS;
        private Long orderId;
        private String requestId;
        private String traceId;
        private String operator;
        private AuditActorType actorType;
        private String service;
        private String operation;
        private Object requestPayload;
        private Object responsePayload;
        private Integer httpStatus;
        private String businessCode;
        private Integer latencyMs;

        public AuditCommand dataScope(DataScope dataScope) {
            this.dataScope = dataScope;
            return this;
        }

        public AuditCommand orderId(Long orderId) {
            this.orderId = orderId;
            return this;
        }

        public AuditCommand requestId(String requestId) {
            this.requestId = requestId;
            return this;
        }

        public AuditCommand traceId(String traceId) {
            this.traceId = traceId;
            return this;
        }

        public AuditCommand operator(String operator) {
            this.operator = operator;
            return this;
        }

        public AuditCommand actorType(AuditActorType actorType) {
            this.actorType = actorType;
            return this;
        }

        public AuditCommand service(String service) {
            this.service = service;
            return this;
        }

        public AuditCommand operation(String operation) {
            this.operation = operation;
            return this;
        }

        public AuditCommand requestPayload(Object requestPayload) {
            this.requestPayload = requestPayload;
            return this;
        }

        public AuditCommand responsePayload(Object responsePayload) {
            this.responsePayload = responsePayload;
            return this;
        }

        public AuditCommand httpStatus(Integer httpStatus) {
            this.httpStatus = httpStatus;
            return this;
        }

        public AuditCommand businessCode(String businessCode) {
            this.businessCode = businessCode;
            return this;
        }

        public AuditCommand latencyMs(Integer latencyMs) {
            this.latencyMs = latencyMs;
            return this;
        }

        DataScope dataScope() {
            return dataScope;
        }

        Long orderId() {
            return orderId;
        }

        String requestId() {
            return requestId;
        }

        String traceId() {
            return traceId;
        }

        String operator() {
            return operator;
        }

        AuditActorType actorType() {
            return actorType;
        }

        String service() {
            return service;
        }

        String operation() {
            return operation;
        }

        Object requestPayload() {
            return requestPayload;
        }

        Object responsePayload() {
            return responsePayload;
        }

        Integer httpStatus() {
            return httpStatus;
        }

        String businessCode() {
            return businessCode;
        }

        Integer latencyMs() {
            return latencyMs;
        }
    }

    @Transactional
    public AuditLog record(AuditCommand command) {
        AuditLog log = new AuditLog();
        log.setDataScope(command.dataScope());
        log.setOrderId(command.orderId());
        log.setRequestId(command.requestId());
        log.setTraceId(command.traceId());
        log.setOperator(command.operator());
        log.setActorType(command.actorType());
        log.setService(command.service());
        log.setOperation(command.operation());
        log.setRequestPayload(toMap(command.requestPayload()));
        log.setResponsePayload(toMap(command.responsePayload()));
        log.setHttpStatus(command.httpStatus());
        log.setBusinessCode(command.businessCode());
        log.setLatencyMs(command.latencyMs());
        AuditLog saved = repository.save(log);
        // append-only 行不可变：persist 后立即脱离上下文，避免后续 flush 因 dirty-check 误发 UPDATE 触发 append-only 触发器
        entityManager.detach(saved);
        return saved;
    }

    private Map<String, Object> toMap(Object payload) {
        if (payload == null) {
            return null;
        }
        Object converted = payload instanceof Map<?, ?> ? payload : objectMapper.convertValue(payload, Map.class);
        return SecretRedactor.redact(converted);
    }
}
