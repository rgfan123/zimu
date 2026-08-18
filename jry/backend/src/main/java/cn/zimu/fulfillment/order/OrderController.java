package cn.zimu.fulfillment.order;

import cn.zimu.fulfillment.common.domain.SourceChannel;
import cn.zimu.fulfillment.common.dto.PageResponse;
import cn.zimu.fulfillment.common.web.WriteCommands;
import cn.zimu.fulfillment.fulfillment.FulfillmentReadService;
import cn.zimu.fulfillment.order.domain.OrderStatus;
import cn.zimu.fulfillment.order.domain.ProcessingHealth;
import cn.zimu.fulfillment.order.domain.ProcessingStage;
import cn.zimu.fulfillment.order.dto.OrderDetailDto;
import cn.zimu.fulfillment.order.dto.CorrectionOrderCommand;
import cn.zimu.fulfillment.order.dto.OrderEventDto;
import cn.zimu.fulfillment.order.dto.OrderSummaryDto;
import cn.zimu.fulfillment.order.dto.OrderVersionDto;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.Valid;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.ResponseEntity;

/** 订单查询 API：列表、详情、Timeline 与版本。 */
@RestController
@RequestMapping("/api/v1/orders")
@Validated
public class OrderController {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private final OrderQueryService queryService;
    private final FulfillmentReadService fulfillmentReadService;
    private final OrderCreateService orderCreateService;

    public OrderController(
            OrderQueryService queryService,
            FulfillmentReadService fulfillmentReadService,
            OrderCreateService orderCreateService) {
        this.queryService = queryService;
        this.fulfillmentReadService = fulfillmentReadService;
        this.orderCreateService = orderCreateService;
    }

    @GetMapping
    public PageResponse<OrderSummaryDto> list(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int size,
            @RequestParam(name = "date_from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
            @RequestParam(name = "date_to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
            @RequestParam(name = "source_channel", required = false) SourceChannel sourceChannel,
            @RequestParam(name = "order_status", required = false) OrderStatus orderStatus,
            @RequestParam(name = "processing_stage", required = false) ProcessingStage processingStage,
            @RequestParam(name = "processing_health", required = false) ProcessingHealth processingHealth,
            @RequestParam(name = "provider_id", required = false) String providerId,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) List<String> sort) {
        return queryService.list(new OrderListQuery(
                page,
                size,
                toStartOfShanghaiDay(dateFrom),
                toStartOfNextShanghaiDay(dateTo),
                sourceChannel,
                orderStatus,
                processingStage,
                processingHealth,
                providerId == null ? null : WriteCommands.parseIdentifier(providerId),
                query,
                sort));
    }

    private static Instant toStartOfShanghaiDay(LocalDate date) {
        return date == null ? null : date.atStartOfDay(SHANGHAI).toInstant();
    }

    private static Instant toStartOfNextShanghaiDay(LocalDate date) {
        return date == null ? null : date.plusDays(1).atStartOfDay(SHANGHAI).toInstant();
    }

    @GetMapping("/{order_id}")
    public OrderDetailDto detail(@PathVariable("order_id") String orderId) {
        return queryService.getDetail(WriteCommands.parseIdentifier(orderId));
    }

    @GetMapping("/{order_id}/timeline")
    public List<OrderEventDto> timeline(@PathVariable("order_id") String orderId) {
        return queryService.timeline(WriteCommands.parseIdentifier(orderId));
    }

    @GetMapping("/{order_id}/versions")
    public List<OrderVersionDto> versions(@PathVariable("order_id") String orderId) {
        return queryService.versions(WriteCommands.parseIdentifier(orderId));
    }

    @GetMapping("/{order_id}/shipments")
    public List<Map<String, Object>> shipments(@PathVariable("order_id") String orderId) {
        return fulfillmentReadService.orderShipments(WriteCommands.parseIdentifier(orderId));
    }

    @PostMapping("/{order_id}/corrections")
    public ResponseEntity<?> correction(
            @PathVariable("order_id") String orderId,
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestHeader(value = "X-Operator", required = false) String operator,
            @Valid @RequestBody CorrectionOrderCommand command) {
        return WriteCommands.respond(orderCreateService.createCorrection(
                WriteCommands.parseIdentifier(orderId), command,
                WriteCommands.requireIdempotencyKey(key), WriteCommands.writeContext(operator)));
    }
}
