package cn.zimu.fulfillment.mcp;

import java.util.List;

/**
 * MCP 开放面的只读核对视图（票 05）：管理员改完 {@code MCP_MODULES} 后在界面上确认结果，
 * 不必进容器翻环境变量。
 *
 * <p>两类模块必须分得清楚，这正是本视图存在的理由：
 * <ul>
 *   <li>{@code openModules}——**已开放**：模块的工具真的进了注册表，外部 MCP 面与内部 Agent
 *       平台此刻都能调用；逐工具给出名称、用途摘要与读写属性。</li>
 *   <li>{@code unopenedModules}——**已知但未开放**：系统里有声明这个模块的工具，但当前配置
 *       没列出它，因此一个工具都没注册。这里只给模块名，不给工具明细：未开放模块的工具
 *       根本没进注册表，凭空列出「开了会有什么」就得另建一份清单，那正是票 01 反转空值语义
 *       要消灭的漂移来源。</li>
 * </ul>
 *
 * <p>两个清单都可能为空，且空是合法状态而非错误：{@code MCP_MODULES} 未配置时
 * {@code openModules} 为空（fail-safe 语义，ADR 0015），全部模块开放时
 * {@code unopenedModules} 为空。
 */
public record McpExposure(List<McpExposureModule> openModules, List<String> unopenedModules) {

    public McpExposure {
        openModules = openModules == null ? List.of() : List.copyOf(openModules);
        unopenedModules = unopenedModules == null ? List.of() : List.copyOf(unopenedModules);
    }
}
