package cn.zimu.fulfillment.fulfillment;

import cn.zimu.fulfillment.common.web.CommandContext;
import cn.zimu.fulfillment.common.web.WriteCommands;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 人工录入运单：手上只有单号、没有回填文件时的入口。 */
@RestController
@RequestMapping("/api/v1")
@Validated
public class ManualTrackingEntryController {

    private final ManualTrackingEntryService service;

    public ManualTrackingEntryController(ManualTrackingEntryService service) {
        this.service = service;
    }

    /**
     * 录入请求。
     *
     * @param carrier 快递公司；名称或承运商代码都行。<b>留空</b>表示「按运单号前缀推断」，
     *                推不出来服务端会报错要求明确指定——不会默认一个
     */
    public record ManualTrackingEntry(
            @Size(max = 64, message = "快递公司超长") String carrier,
            @NotBlank(message = "运单号不能为空")
                    @Size(max = 64, message = "运单号超长")
                    String trackingNumber) {}

    /** 录入界面的快递公司下拉；只返回启用的承运商。 */
    @GetMapping("/carriers")
    public List<Map<String, String>> carriers() {
        return service.availableCarriers();
    }

    /**
     * 给一张发货批次录入运单。
     *
     * <p>幂等键必填：这一步会推发货卡、并可能触发来源回传真写客户平台，误点两次的代价
     * 由服务端的重放收敛承担，不能指望前端不重复提交。
     */
    @PostMapping("/shipments/{id}/manual-tracking")
    public ResponseEntity<ManualTrackingOutcome> enter(
            @PathVariable String id,
            @Valid @RequestBody ManualTrackingEntry body,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Operator") String operator) {
        WriteCommands.requireIdempotencyKey(idempotencyKey);
        CommandContext context = WriteCommands.writeContext(operator);
        return ResponseEntity.ok(service.enter(
                WriteCommands.parseIdentifier(id), body.carrier(), body.trackingNumber(), context));
    }
}
