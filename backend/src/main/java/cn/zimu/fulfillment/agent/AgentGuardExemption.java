package cn.zimu.fulfillment.agent;

/**
 * 平台默认 Agent 守卫的豁免枚举（05 决策，agent-decision-layer 08 票）。
 *
 * <p>枚举名与 {@code AgentDefinition.guard_exemptions} 存储值一致（默认空 = 守卫生效）。
 * 豁免是声明式白名单：声明后平台默认链跳过对应守卫（如数据查询类 Agent 由领域守卫先行
 * 短路时，可声明豁免避免双重判定）。
 */
public enum AgentGuardExemption {
    /** 平台默认链 [PII 拒绝]：输入含客户/收货人/手机号/地址等 PII 模式 → REJECTED 转人工。 */
    PII;
}
