package cn.zimu.fulfillment.agent;

/**
 * 一次 Agent 运行的输入：systemPrompt 与 userInput 均为调用方提供的普通文本；
 * tools 为本次运行的 MCP 工具绑定（03 票），可为 null（无工具）。
 */
public record AgentTaskRequest(String systemPrompt, String userInput, AgentToolBinding tools) {

    public AgentTaskRequest(String systemPrompt, String userInput) {
        this(systemPrompt, userInput, null);
    }
}
