package cn.zimu.fulfillment.mcp;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 只读端点：当前 MCP 开放面（已开放模块及其已注册工具 + 已知但未开放的模块）。
 *
 * <p>纯只读、无参数、无副作用——它回答的是部署事实。**本端点不提供任何修改开放面的能力**：
 * 开放面由 {@code MCP_MODULES} 在部署期决定并在启动期一次性生效（注册期排除，ADR 0015），
 * 界面上能改就等于绕过部署评审，且注册表在运行期不可变，改了也不会生效。
 *
 * <p>与 {@code GET /api/v1/business-modules}（业务模块接通清单）是两件事：那个答「这块业务
 * 能力今天接通了吗」，本端点答「哪些 MCP 工具对外开放着」，两者不共用配置、不互相推导。
 */
@RestController
@RequestMapping("/api/v1/mcp-exposure")
public class McpExposureController {

    private final McpExposureReadService service;

    public McpExposureController(McpExposureReadService service) {
        this.service = service;
    }

    @GetMapping
    public McpExposure exposure() {
        return service.exposure();
    }
}
