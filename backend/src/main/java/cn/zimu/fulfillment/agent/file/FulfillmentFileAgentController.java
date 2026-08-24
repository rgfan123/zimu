package cn.zimu.fulfillment.agent.file;

import cn.zimu.fulfillment.agent.AgentRunContext;
import cn.zimu.fulfillment.batch.ImportBatchProgress;
import cn.zimu.fulfillment.batch.ImportBatchProgressService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 履约单据 Agent 端点（/api 面，Basic Auth 人工面）。
 *
 * <p>两个端点刻意分开：
 * <ul>
 *   <li>{@code GET .../progress} 只出确定性事实，**不调模型**——看进度不该花钱，
 *       也不该因为模型不可用而看不到；</li>
 *   <li>{@code POST .../assessment} 才跑 Agent 出解读。用 POST 是因为它有成本与副作用
 *       （落 agent_runs、消耗 token），GET 语义上应当可缓存可重放，这里不是。</li>
 * </ul>
 * 两者都不改变任何业务状态。
 */
@RestController
@RequestMapping("/api/v1/import-batches/{batchId}")
public class FulfillmentFileAgentController {

    private final FulfillmentFileAgent agent;
    private final ImportBatchProgressService progress;

    public FulfillmentFileAgentController(
            FulfillmentFileAgent agent, ImportBatchProgressService progress) {
        this.agent = agent;
        this.progress = progress;
    }

    /** 四段链路的确定性进度（不调模型）。 */
    @GetMapping("/progress")
    public ImportBatchProgress progress(@PathVariable("batchId") long batchId) {
        return progress.of(batchId);
    }

    /** Agent 解读与建议；始终附带确定性进度，模型失败时事实照常返回。 */
    @PostMapping("/assessment")
    public FulfillmentFileRunResult assess(
            @PathVariable("batchId") long batchId,
            @RequestHeader(name = "X-Operator", required = false) String operator) {
        // 操作人进审计主体：Agent 是代跑的，追责要落到发起的人身上
        AgentRunContext context = new AgentRunContext(
                "import-batch-" + batchId, operator, null, null);
        return agent.assess("{\"import_batch_id\":\"" + batchId + "\"}", context);
    }
}
