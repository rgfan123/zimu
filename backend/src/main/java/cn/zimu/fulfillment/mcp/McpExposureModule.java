package cn.zimu.fulfillment.mcp;

import java.util.List;

/** 一个**已开放**模块及其当前已注册的工具（工具按名称排序，便于逐条核对）。 */
public record McpExposureModule(String module, List<McpExposureTool> tools) {

    public McpExposureModule {
        tools = tools == null ? List.of() : List.copyOf(tools);
    }
}
