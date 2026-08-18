package cn.zimu.fulfillment.agent;

/**
 * 一次 Agent 运行（invoke / resume）的调用侧上下文。
 *
 * <p>{@code threadId} 为会话延续占位：一期运行是无状态的，threadId 只透传进审计
 * （requestPayload.thread_id），不参与模型调用或会话恢复；{@code operator} 用于 AGENT
 * 审计的 operator 字段，为 null/空白时由门面兜底为 {@code "agent"}。
 * {@code businessEntityType}/{@code businessEntityId}（08 票）携带业务实体关联
 * （如 PROCUREMENT_TICKET / id），随 agent_run 行落库供双向追溯，空白视为不关联。
 */
public record AgentRunContext(
        String threadId,
        String operator,
        String businessEntityType,
        String businessEntityId) {

    public AgentRunContext {
        threadId = threadId == null ? "" : threadId;
        operator = operator == null ? "" : operator.strip();
        businessEntityType = blankToNull(businessEntityType);
        businessEntityId = blankToNull(businessEntityId);
    }

    public static AgentRunContext of(String threadId) {
        return new AgentRunContext(threadId, null, null, null);
    }

    public static AgentRunContext empty() {
        return new AgentRunContext("", null, null, null);
    }

    /** 携带业务实体关联的副本（如 PROCUREMENT_TICKET / 42）。 */
    public AgentRunContext withBusinessEntity(String type, String id) {
        return new AgentRunContext(threadId, operator, type, id);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
