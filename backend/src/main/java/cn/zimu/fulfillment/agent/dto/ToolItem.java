package cn.zimu.fulfillment.agent.dto;

/**
 * 工具白名单投影（12 票消费方要求 4）：白名单名称 + 读写属性。
 *
 * <p>{@code read_only} 取 {@code McpToolRegistry} 的 08 决策读写元数据（默认禁写，
 * 写工具必须显式 readOnly=false）；白名单引用未注册工具属配置漂移，
 * {@code registered=false} 且 {@code read_only=null}，前端按「未知工具」处理而非误标。
 */
public record ToolItem(String name, Boolean readOnly, boolean registered) {}
