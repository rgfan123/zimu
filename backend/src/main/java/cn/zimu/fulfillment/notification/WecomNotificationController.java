package cn.zimu.fulfillment.notification;

import cn.zimu.fulfillment.common.dto.PageResponse;
import cn.zimu.fulfillment.common.error.BusinessException;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Authenticated operations endpoint for notification delivery traceability (Issue #90). */
@RestController
@RequestMapping("/api/v1/admin/wecom-notifications")
@Validated
public class WecomNotificationController {

    private final WecomNotificationQueryService queries;

    public WecomNotificationController(WecomNotificationQueryService queries) {
        this.queries = queries;
    }

    @GetMapping("/deliveries")
    public PageResponse<WecomNotificationDeliveryDto> deliveries(
            @RequestHeader(value = "X-Operator", required = false) String operator,
            @RequestParam(name = "source_type", required = false) String sourceType,
            @RequestParam(name = "source_id", required = false) Long sourceId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int size) {
        if (operator == null || operator.isBlank()) {
            throw new BusinessException(401, "ADMIN_AUTH_REQUIRED", "通知投递记录查询需要认证");
        }
        return queries.deliveries(sourceType, sourceId, status, page, size);
    }
}
