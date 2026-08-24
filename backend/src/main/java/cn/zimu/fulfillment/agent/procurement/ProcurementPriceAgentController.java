package cn.zimu.fulfillment.agent.procurement;

import cn.zimu.fulfillment.agent.AgentRunContext;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 采购比价 Agent 的 REST 接缝（agent-decision-layer 05，01 票）：把采购比价能力暴露给采购界面。
 *
 * <p>只读能力：运行一次采购比价（结构化输入 {@code procurement_ticket_id} 或 {@code sku_id}
 * + 可选 {@code quantity}），返回 {@link ProcurementPriceRunResult}（含可比候选
 * {@code candidates} 与被剔除候选 {@code excluded_candidates} + 理由）。输入非法抛
 * {@code INVALID_PARAMETERS}（由全局异常处理器转 4xx）；模型未配置/未注册/未启用以结果内的
 * 稳定 {@code error} 码返回（fail-closed，不抛异常）。运行审计由
 * {@link ProcurementPriceAgent} 落 AGENT 审计，operator 兜底为 {@code agent}——
 * 浏览器不得提供 X-Operator（受信网关覆盖身份，见 api-contract）。
 */
@RestController
@RequestMapping("/api/v1/procurement-price-agent")
public class ProcurementPriceAgentController {

    private final ProcurementPriceAgent agent;

    public ProcurementPriceAgentController(ProcurementPriceAgent agent) {
        this.agent = agent;
    }

    @PostMapping("/compare")
    public ProcurementPriceRunResult compare(@RequestBody String jsonInput) {
        return agent.compare(jsonInput, AgentRunContext.empty());
    }
}
