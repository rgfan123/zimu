package cn.zimu.fulfillment.agent;

/**
 * 一次 Agent 运行的输入：systemPrompt 与 userInput 均为调用方提供的普通文本；
 * tools 为本次运行的 MCP 工具绑定（03 票），可为 null（无工具）。
 *
 * <p>04 票扩展：{@code definition} 携带本次运行的 Agent 定义（output_schema / prompt_version /
 * 系统提示词等），运行时 Adapter 据此做「定义 → ChatRequest → 结果」；接口签名
 * {@link AgentRuntime#run} 不变，2/3 参构造器保持既有调用面（definition=null，B/C 路径
 * 收敛前使用）。
 */
public record AgentTaskRequest(
        String systemPrompt, String userInput, AgentToolBinding tools, AgentDefinition definition) {

    public AgentTaskRequest(String systemPrompt, String userInput) {
        this(systemPrompt, userInput, null, null);
    }

    public AgentTaskRequest(String systemPrompt, String userInput, AgentToolBinding tools) {
        this(systemPrompt, userInput, tools, null);
    }
}
