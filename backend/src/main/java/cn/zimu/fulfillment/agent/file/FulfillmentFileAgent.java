package cn.zimu.fulfillment.agent.file;

import cn.zimu.fulfillment.agent.AgentDefinition;
import cn.zimu.fulfillment.agent.AgentFailureCode;
import cn.zimu.fulfillment.agent.AgentInputFormat;
import cn.zimu.fulfillment.agent.AgentRunContext;
import cn.zimu.fulfillment.agent.AgentRunResult;
import cn.zimu.fulfillment.agent.AgentRuntimeFacade;
import cn.zimu.fulfillment.batch.ImportBatchProgress;
import cn.zimu.fulfillment.batch.ImportBatchProgressService;
import cn.zimu.fulfillment.common.error.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * 履约单据 Agent（Excel 闭环：收表 → 发货 → 回填 → 回传）。
 *
 * <p>**它不发货、不回填、不回传**。这三件事全部由既有确定性服务执行，本 Agent 的
 * 工具白名单里一个写工具都没有（V56 定义 allow_write=false）。它做的是把四段链路
 * 的当前事实翻译成运营能直接行动的解读——这正是「Agent 只给依据不做决定」在履约域
 * 的落点。
 *
 * <p>四段数字先由 {@link ImportBatchProgressService} 用 SQL 算出，再作为事实交给模型，
 * 且返回时用 {@link FulfillmentFilePolicy} 覆盖模型转述的业务号与当前段。理由很实际：
 * 模型抄错批次号，运营就会去后台搜一个不存在的单子，而这种错在自然语言里毫无破绽。
 *
 * <p>注册表解析 / enabled 判定 / run_id / 工具绑定 / 审计 / 观测全部由
 * {@link AgentRuntimeFacade} 承接，与采购比价 Agent 同构。
 */
@Component
public class FulfillmentFileAgent {

    /** 注册表 slug（与 V56 种子一致）。 */
    public static final String AGENT_SLUG = "fulfillment-file-agent";

    private final AgentRuntimeFacade facade;
    private final ImportBatchProgressService progressService;
    private final ObjectMapper mapper;

    public FulfillmentFileAgent(
            AgentRuntimeFacade facade,
            ImportBatchProgressService progressService,
            ObjectMapper mapper) {
        this.facade = facade;
        this.progressService = progressService;
        this.mapper = mapper;
    }

    /**
     * 解读一个导入批次的履约进度。
     *
     * @return 始终携带确定性进度；模型失败时 assessment 为 null 且 error 为稳定码——
     *         **模型挂了不该让运营连事实都看不到**
     * @throws BusinessException 输入不合法（INVALID_PARAMETERS，不进入模型）或批次不存在
     */
    public FulfillmentFileRunResult assess(String jsonInput, AgentRunContext context) {
        AgentDefinition definition = facade.definitionOf(AGENT_SLUG);
        if (definition == null || definition.inputFormat() != AgentInputFormat.STRUCTURED_JSON) {
            throw new IllegalStateException(
                    "fulfillment-file-agent 定义 input_format 必须为 STRUCTURED_JSON（配置漂移）");
        }
        FulfillmentFileInput input = FulfillmentFileInput.parse(jsonInput);
        // 事实先取：批次不存在直接 404，不浪费一次模型调用
        ImportBatchProgress progress = progressService.of(input.importBatchId());

        AgentRunContext ctx = (context == null ? AgentRunContext.empty() : context)
                .withBusinessEntity("IMPORT_BATCH", String.valueOf(input.importBatchId()));
        AgentRunResult result = facade.invoke(AGENT_SLUG, input.toUserInput(), ctx);
        if (result.error() != null) {
            return new FulfillmentFileRunResult(
                    progress, null, result.provider(), result.model(), result.promptVersion(), result.error());
        }
        try {
            FulfillmentFileAssessment raw =
                    mapper.treeToValue(result.output(), FulfillmentFileAssessment.class);
            return new FulfillmentFileRunResult(
                    progress,
                    FulfillmentFilePolicy.enforce(raw, progress),
                    result.provider(),
                    result.model(),
                    result.promptVersion(),
                    null);
        } catch (Exception ex) {
            return new FulfillmentFileRunResult(
                    progress,
                    null,
                    result.provider(),
                    result.model(),
                    result.promptVersion(),
                    AgentFailureCode.AGENT_OUTPUT_INVALID.name());
        }
    }
}
