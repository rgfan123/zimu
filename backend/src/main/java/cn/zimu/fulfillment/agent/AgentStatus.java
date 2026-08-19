package cn.zimu.fulfillment.agent;

/**
 * Agent 定义版本生命周期状态（meta-agent-platform 03）：draft / active / retired 三态，
 * 无 retired→active 回边（回滚 = 复制旧版本为新草稿）。{@code enabled}（运维启停）与
 * {@code status}（版本生命周期）正交。
 *
 * <p>DB 存储小写字符串（agent_definitions.status），经 {@link #fromDb} / {@link #toDb}
 * 与枚举互转，避免魔法字符串散落。
 */
public enum AgentStatus {

    DRAFT,
    ACTIVE,
    RETIRED;

    /** 从 DB 小写字符串解析；未知值抛 {@link IllegalArgumentException}（fail-fast）。 */
    public static AgentStatus fromDb(String value) {
        return switch (value) {
            case "draft" -> DRAFT;
            case "active" -> ACTIVE;
            case "retired" -> RETIRED;
            default -> throw new IllegalArgumentException("未知 Agent 状态: " + value);
        };
    }

    /** 落库表示（小写字符串）。 */
    public String toDb() {
        return switch (this) {
            case DRAFT -> "draft";
            case ACTIVE -> "active";
            case RETIRED -> "retired";
        };
    }
}
