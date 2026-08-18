package cn.zimu.fulfillment.connector.wecom;

import cn.zimu.fulfillment.common.error.BusinessException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 受权管理诊断：企业微信长连接的配置门禁与实时连接状态（非密投影，X-Operator 校验保留）。
 */
@RestController
@RequestMapping("/api/v1/wecom")
public class WecomReadinessController {

    private final WecomReadinessService readinessService;

    public WecomReadinessController(WecomReadinessService readinessService) {
        this.readinessService = readinessService;
    }

    @GetMapping("/readiness")
    public WecomConnectionReadiness readiness(
            @RequestHeader(value = "X-Operator", required = false) String operator) {
        if (operator == null || operator.isBlank()) {
            throw new BusinessException(401, "ADMIN_AUTH_REQUIRED", "管理后台查询需要认证");
        }
        return readinessService.inspect();
    }
}
