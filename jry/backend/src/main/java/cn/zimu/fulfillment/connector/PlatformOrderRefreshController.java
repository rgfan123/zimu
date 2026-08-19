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
 * <p>复用 Phase 0 拉取脚本（scripts/*_fetch_orders.py）作为在线拉取通道：后端进程内
 * 执行脚本 → 产物文件自动进入现有导入闭环（ImportBatch + 人工确认），与人工导表
 * 上传同一条管线，不绕过批次语义。聚福宝 JSON 直连缺收货人字段（票 15 blocker），
 * 只拉取并报告数量，不自动导入。
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
            @RequestHeader(value = "X-Operator", required = false) String operator) {
        CommandContext context = WriteCommands.writeContext(operator);
        return service.refresh(body, context);
    }
}
