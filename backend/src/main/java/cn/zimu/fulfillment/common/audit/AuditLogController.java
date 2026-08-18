package cn.zimu.fulfillment.common.audit;

import cn.zimu.fulfillment.common.dto.PageResponse;
import cn.zimu.fulfillment.common.domain.DataScope;
import cn.zimu.fulfillment.common.error.BusinessException;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/audit-logs")
@Validated
public class AuditLogController {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final AuditLogRepository repository;

    public AuditLogController(AuditLogRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public PageResponse<AuditLogDto> list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int size,
            @RequestParam(name = "request_id", required = false) String requestId,
            @RequestParam(name = "trace_id", required = false) String traceId,
            @RequestParam(required = false) String operator,
            @RequestParam(required = false) String service,
            @RequestParam(required = false) String operation,
            @RequestParam(name = "business_code", required = false) String businessCode,
            @RequestParam(name = "date_from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(name = "date_to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo) {
        Page<AuditLog> result = repository.findAll(
                searchSpecification(
                        requestId,
                        traceId,
                        operator,
                        service,
                        operation,
                        businessCode,
                        toStartOfShanghaiDay(dateFrom),
                        toStartOfNextShanghaiDay(dateTo)),
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
        return PageResponse.of(
                result.getContent().stream().map(log -> AuditLogDto.from(log, false)).toList(),
                result);
    }

    @GetMapping("/{audit_id}")
    public AuditLogDto get(@PathVariable("audit_id") String auditId) {
        long id = Long.parseLong(auditId);
        AuditLog log = repository
                .findByIdAndDataScope(id, DataScope.BUSINESS)
                .orElseThrow(() -> BusinessException.notFound("审计记录不存在: " + auditId));
        return AuditLogDto.from(log, true);
    }

    private static Instant toStartOfShanghaiDay(LocalDate date) {
        return date == null ? null : date.atStartOfDay(SHANGHAI).toInstant();
    }

    private static Instant toStartOfNextShanghaiDay(LocalDate date) {
        return date == null ? null : date.plusDays(1).atStartOfDay(SHANGHAI).toInstant();
    }

    private static Specification<AuditLog> searchSpecification(
            String requestId,
            String traceId,
            String operator,
            String service,
            String operation,
            String businessCode,
            Instant dateFrom,
            Instant dateTo) {
        List<Specification<AuditLog>> filters = new ArrayList<>();
        filters.add((root, query, criteria) -> criteria.equal(root.get("dataScope"), DataScope.BUSINESS));
        addEqualFilter(filters, "requestId", requestId);
        addEqualFilter(filters, "traceId", traceId);
        addEqualFilter(filters, "operator", operator);
        addEqualFilter(filters, "service", service);
        addEqualFilter(filters, "operation", operation);
        addEqualFilter(filters, "businessCode", businessCode);
        if (dateFrom != null) {
            filters.add((root, query, criteria) -> criteria.greaterThanOrEqualTo(root.get("createdAt"), dateFrom));
        }
        if (dateTo != null) {
            filters.add((root, query, criteria) -> criteria.lessThan(root.get("createdAt"), dateTo));
        }
        return Specification.allOf(filters);
    }

    private static void addEqualFilter(
            List<Specification<AuditLog>> filters, String attribute, String value) {
        if (value != null && !value.isBlank()) {
            filters.add((root, query, criteria) -> criteria.equal(root.get(attribute), value));
        }
    }
}
