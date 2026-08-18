package cn.zimu.fulfillment.common.audit;

import cn.zimu.fulfillment.common.domain.DataScope;
import cn.zimu.fulfillment.common.jpa.CreatedAtEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 接口调用与人工操作的审计记录，只追加。 */
@Entity
@Table(name = "audit_logs")
public class AuditLog extends CreatedAtEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "data_scope", nullable = false)
    private DataScope dataScope;

    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "request_id")
    private String requestId;

    @Column(name = "trace_id")
    private String traceId;

    @Column(name = "operator", nullable = false)
    private String operator;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false)
    private AuditActorType actorType;

    @Column(name = "service", nullable = false)
    private String service;

    @Column(name = "operation", nullable = false)
    private String operation;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_payload")
    private Map<String, Object> requestPayload;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_payload")
    private Map<String, Object> responsePayload;

    @Column(name = "http_status")
    private Integer httpStatus;

    @Column(name = "business_code")
    private String businessCode;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    public Long getId() {
        return id;
    }

    public DataScope getDataScope() {
        return dataScope;
    }

    public void setDataScope(DataScope dataScope) {
        this.dataScope = dataScope;
    }

    public Long getOrderId() {
        return orderId;
    }

    public void setOrderId(Long orderId) {
        this.orderId = orderId;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public String getOperator() {
        return operator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }

    public AuditActorType getActorType() {
        return actorType;
    }

    public void setActorType(AuditActorType actorType) {
        this.actorType = actorType;
    }

    public String getService() {
        return service;
    }

    public void setService(String service) {
        this.service = service;
    }

    public String getOperation() {
        return operation;
    }

    public void setOperation(String operation) {
        this.operation = operation;
    }

    public Map<String, Object> getRequestPayload() {
        return requestPayload;
    }

    public void setRequestPayload(Map<String, Object> requestPayload) {
        this.requestPayload = requestPayload;
    }

    public Map<String, Object> getResponsePayload() {
        return responsePayload;
    }

    public void setResponsePayload(Map<String, Object> responsePayload) {
        this.responsePayload = responsePayload;
    }

    public Integer getHttpStatus() {
        return httpStatus;
    }

    public void setHttpStatus(Integer httpStatus) {
        this.httpStatus = httpStatus;
    }

    public String getBusinessCode() {
        return businessCode;
    }

    public void setBusinessCode(String businessCode) {
        this.businessCode = businessCode;
    }

    public Integer getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(Integer latencyMs) {
        this.latencyMs = latencyMs;
    }
}
