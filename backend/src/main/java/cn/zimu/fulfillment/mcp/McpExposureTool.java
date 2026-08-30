package cn.zimu.fulfillment.mcp;

/**
 * 已注册工具在核对视图里的投影：工具名、用途摘要、读写属性。
 *
 * <p>三项全部取自工具自己声明的元数据（{@link McpTool#name()} / {@link McpTool#description()} /
 * {@link McpTool#readOnly()}），不另写一份会与注册表漂移的描述文案。
 *
 * <p>刻意不投影 {@code inputSchema}：核对视图回答的是「开放了什么」，不是「怎么调用」——
 * 参数形态由 MCP 协议面的 {@code tools/list} 提供，管理台不重复承担第二份工具契约。
 */
public record McpExposureTool(String name, String description, boolean readOnly) {}
