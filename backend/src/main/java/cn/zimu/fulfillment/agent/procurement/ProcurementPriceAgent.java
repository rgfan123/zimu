package cn.zimu.fulfillment.agent.procurement;

import cn.zimu.fulfillment.agent.AgentFailureCode;
import cn.zimu.fulfillment.agent.AgentRunContext;
import cn.zimu.fulfillment.agent.AgentRunResult;
import cn.zimu.fulfillment.agent.AgentRuntimeFacade;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * 采购比价 Agent（agent-decision-layer 05；meta-agent-platform-impl 05 收敛为门面薄包装）：
 * 按注册表定义执行一次采购比价。
 *
 * <p>注册表解析 / enabled 判定 / run_id / 工具绑定 / 模型运行 / 审计 / 观测全部由
 * {@link AgentRuntimeFacade} 承接（04 决策：权限/守卫/审计/观测留 Control Plane）；
 * 本类只保留领域层：
 * <ul>
 *   <li>结构化输入解析与校验（{@link ProcurementPriceInput}，INVALID_PARAMETERS 不进入模型）；</li>
 *   <li>输出 record 反序列化 + 策略落地（{@link ProcurementPricePolicy#enforce} 确定性归一化，
 *       无候选/无价格/字段缺失/低置信度 → requires_human=true）；</li>
 *   <li>业务 run-result 组装（{@link ProcurementPriceRunResult}）。</li>
 * </ul>
 *
 * <p>建议不落任何业务表：运行结果仅返回给调用方并随门面 AGENT 审计留痕（可复核事实摘要的
 * 完整工具调用序列可观测性在 {@code app.agent_tool_calls}，08 票承接）。
 */
@Component
public class ProcurementPriceAgent {

    /** 注册表 slug（与 V33 种子 procurement-price-agent 一致）。 */
    public static final String AGENT_SLUG = "procurement-price-agent";

    private final AgentRuntimeFacade facade;
    private final ObjectMapper mapper;

    public ProcurementPriceAgent(AgentRuntimeFacade facade, ObjectMapper mapper) {
        this.facade = facade;
        this.mapper = mapper;
    }

    /**
     * 执行一次采购比价：输入为结构化 JSON（procurement_ticket_id 或 sku_id + 可选 quantity）。
     *
     * @return 模型运行结果；注册表拒绝（未注册/未启用）与底层失败均以
     *         {@link ProcurementPriceRunResult} 携带稳定失败码返回，不抛异常；
     *         输入不合法抛 {@code INVALID_PARAMETERS} 业务错误。
     */
    public ProcurementPriceRunResult compare(String jsonInput, AgentRunContext context) {
        ProcurementPriceInput input = ProcurementPriceInput.parse(jsonInput);
        AgentRunResult result = facade.invoke(AGENT_SLUG, input.toUserInput(), context);
        if (result.error() != null) {
            // REJECTED / FAILED：稳定失败码（门面已留审计与观测）
            return new ProcurementPriceRunResult(
                    null, result.provider(), result.model(), result.promptVersion(), result.error());
        }
        try {
            ProcurementPriceRecommendation raw =
                    mapper.treeToValue(result.output(), ProcurementPriceRecommendation.class);
            return new ProcurementPriceRunResult(
                    ProcurementPricePolicy.enforce(raw),
                    result.provider(),
                    result.model(),
                    result.promptVersion(),
                    null);
        } catch (Exception ex) {
            // 传输层 JsonNode 无法还原为业务 record：按输出无效收口（不把解析细节带进结果）
            return new ProcurementPriceRunResult(
                    null,
                    result.provider(),
                    result.model(),
                    result.promptVersion(),
                    AgentFailureCode.AGENT_OUTPUT_INVALID.name());
        }
    }
}
