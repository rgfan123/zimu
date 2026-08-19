package cn.zimu.fulfillment.agent;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 注册表可变引用（meta-agent-platform-impl 02，决策 03）：持 volatile {@link AgentRegistry}
 * 引用，确认/回滚等变更经 {@link #reload()} 从 DB 换新实例——无需重启即可被运行路径感知；
 * {@link AgentRegistry} 本身保持不可变。
 *
 * <p>{@link #reload()} 对前后两实例复用 {@link AgentRegistryChangeAuditor} 做 diff 并逐条落
 * AGENT 审计（含 ACTIVATED/RETIRED 版本生命周期事件）；无变化不产生任何审计。审计失败隔离
 * 由审计器内部容忍（与既有 audit 语义一致）。

 * <p>测试构造：{@link #AgentRegistryHolder(AgentRegistry)} 直接以既有实例初始化（不触发加载），
 * 此时 {@link #reload()} 不可用（无 DB 数据源），抛 {@link IllegalStateException} 防止误用。
 */
@Component
public class AgentRegistryHolder {

    private final AgentDefinitionRepository repository;
    private final AgentRegistryChangeAuditor auditor;
    private volatile AgentRegistry registry;

    /** Spring 装配：启动时全量从 DB 加载 active 定义构造初始注册表。 */
    @Autowired
    public AgentRegistryHolder(
            AgentDefinitionRepository repository, AgentRegistryChangeAuditor auditor) {
        this.repository = repository;
        this.auditor = auditor;
        this.registry = repository.loadRegistry();
    }

    /** 测试构造：以给定实例初始化（无 DB 数据源，reload 不可用）。 */
    public AgentRegistryHolder(AgentRegistry initial) {
        this.repository = null;
        this.auditor = null;
        this.registry = initial;
    }

    /** 当前生效注册表（volatile 读，运行路径每次取最新）。 */
    public AgentRegistry current() {
        return registry;
    }

    /** 从 DB 重载并换实例；变更经审计器落 AGENT 审计。 */
    public AgentRegistry reload() {
        if (repository == null) {
            throw new IllegalStateException("AgentRegistryHolder 无 DB 数据源（测试构造），不可 reload");
        }
        AgentRegistry next = repository.loadRegistry();
        auditor.recordChanges(registry, next);
        registry = next;
        return next;
    }
}
