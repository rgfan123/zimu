package cn.zimu.fulfillment.message;

import cn.zimu.fulfillment.common.dto.PageResponse;
import cn.zimu.fulfillment.common.error.BusinessException;
import cn.zimu.fulfillment.common.web.WriteCommands;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/channel-messages")
@Validated
public class ChannelMessageController {

    private final ChannelMessageQueryService queryService;

    public ChannelMessageController(ChannelMessageQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    public PageResponse<ChannelMessageSummaryDto> list(
            @RequestHeader(value = "X-Operator", required = false) String operator,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int size) {
        requireOperator(operator);
        return queryService.list(page, size);
    }

    @GetMapping("/{channel_message_id}")
    public ChannelMessageDetailDto detail(
            @RequestHeader(value = "X-Operator", required = false) String operator,
            @PathVariable("channel_message_id") String messageId) {
        requireOperator(operator);
        return queryService.detail(WriteCommands.parseIdentifier(messageId));
    }

    private static void requireOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            throw new BusinessException(401, "ADMIN_AUTH_REQUIRED", "管理后台查询需要认证");
        }
    }
}
