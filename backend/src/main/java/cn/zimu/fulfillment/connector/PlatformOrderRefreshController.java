package cn.zimu.fulfillment.connector;

import cn.zimu.fulfillment.common.web.CommandContext;
import cn.zimu.fulfillment.common.web.WriteCommands;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 三平台（彩食鲜/聚福宝/飞象）订单数据一键刷新（人工触发）。
 *
 * <p>仅对已启用的 REAL + API Connector 执行在线拉取，Java Connector 优先，安全可用时
 * 才回退 Phase 0 脚本；产物仍进入 ImportBatch + 人工确认闭环。聚福宝 onlinePull 被
 * receiver ticket 15 阻断，本入口在领取频次与触网前稳定跳过。
 *
 * <p>幂等取舍（A1，契约 §3.2/§10.3）：refresh 会真实调用外部平台拉取，请求不可重放，
 * 因此 Idempotency-Key 仅做格式校验（≥8 字符）防重复点击，不做注册表级幂等；真正的
 * 重复防护由导入批次内容哈希幂等承担（见 PlatformOrderRefreshService 类注释）。
 */
@RestController
@RequestMapping("/api/v1/platform-orders")
class PlatformOrderRefreshController {

    /** 刷新请求体；全部字段可选。channels 缺省时刷新三平台。 */
    public record RefreshRequest(List<String> channels, String date_begin, String date_end) {}

    private final PlatformOrderRefreshService service;

    PlatformOrderRefreshController(PlatformOrderRefreshService service) {
        this.service = service;
    }

    @PostMapping("/refresh")
    Map<String, Object> refresh(
            @RequestBody(required = false) RefreshRequest body,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Operator") String operator) {
        WriteCommands.requireIdempotencyKey(idempotencyKey);
        CommandContext context = WriteCommands.writeContext(operator);
        return service.refresh(body, context);
    }
}
