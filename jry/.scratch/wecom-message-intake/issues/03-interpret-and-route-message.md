# 03 — 异步解释消息并完成基础意图分流

**What to build:** 消息被接收后由可恢复的后台任务异步解释，运营人员能够看到每次解释版本及最终分流；非业务消息不占用人工队列，无法判断、改单和取消消息形成一个可见且可审计的人工待办。

**Blocked by:** 02 — 接收并查看企业微信文字消息.

**Status:** resolved

**Claimed by:** root → zed-agent (2026-08-14 接手收口：全部 checkbox 已交付；异步因果一致性 P1 的 InterpretationTaskCausalityTest 稳定性问题已修复：类内 DB 污染清理 + 冻结时钟用例改为相对 statement_timestamp 的确定性判定)

## Answer

消息提交 → 异步解释 → 意图分流的基础主链已在当前整合树收口。接收事务只保存渠道证据、MessageSubmission 和 PostgreSQL 异步任务；Worker 通过 `SKIP LOCKED` 租约领取，最多三次模型调用。V19 引入可恢复的 `FINALIZING` 状态：模型外调在事务外，结果应用和任务成功在同一事务内并由 lease owner + 任务代际双重 fencing；终态失败与唯一 `NEED_REVIEW` 待办原子收口，中断后不会第四次调模型。租约被新 Worker 重领或已有更新一代任务时，旧返回/旧失败均不得写入解释、草稿、ReviewCase 或提交终态。

分流保持 fail-closed：`NON_BUSINESS` 只留档；`NEED_REVIEW` 建 `ORDER_OPS/OPEN` 事项；`ORDER_CHANGE` / `ORDER_CANCEL` 只接受有界字符串 `order_no`，`quoted=true` 不再被当成确定订单引用；客户订单与供应商运单由各自草稿工厂处理。同一提交/原因重新解释时，先 flush 旧开放事项为 `DISMISSED`，再建新一代，不触发 PostgreSQL 部分唯一索引。公开待办只保留 intent/provider/model/prompt version/稳定 error code/合法订单号白名单，不暴露原始模型输出、运行异常文本或日志堆栈消息。对外错误统一收敛为 `MODEL_NOT_CONFIGURED` / `MODEL_CALL_FAILED` / `MODEL_OUTPUT_INVALID`。

当前证据：`mvn -DskipTests test-compile` 成功；随后 `MessageInterpretationApiTest` 19、`AsyncTaskStoreTest` 4、`InterpretationTaskCausalityTest` 6、`MessageInterpretationSafetyApiTest` 2、`MessagePublicProjectionMigrationTest` 1、`MessageStructuredOutputBoundaryTest` 1，共 33/33 通过，0 failure/error/skip，exit 0，真实 PostgreSQL 16、Flyway V1→V20。新增覆盖第三次 RUNNING 失租不第四次调用模型、返回失败与抛异常统一三次重试、错误意图强制 NEED_REVIEW、终败追加安全解释版本、历史公共投影清洗，以及结构化输出的 256 KiB/depth/field/array 上界；字节上界使用 capped stream 在 `MAX_BYTES+1` 立即中止，不先分配完整序列化副本。相关 diff-check 为绿。本票仍保持 `claimed`；最终 Standards/Spec 双轴结论尚未全部归档，真实模型服务仍是外部验收门禁，本地未调用。

- [x] 接收事务只创建持久化任务，不等待模型；Spring Worker 使用租约领取任务，进程重启或租约超时后可以恢复。
- [x] 临时错误最多自动重试三次，重试或并发领取不会重复创建解释版本或 ReviewCase，最终失败进入唯一的 `NEED_REVIEW` 待办。
- [x] `MessageInterpreter` 是可替换的模型接缝，保存供应商、模型名称、提示词版本、结构化输出和错误；重新解释追加版本而不覆盖历史。
- [x] 公共查询能够展示任务状态、解释历史和当前意图，不泄露模型密钥或未经白名单处理的内部载荷。
- [x] `NON_BUSINESS` 只保存证据与分类结果，不创建 ReviewCase。
- [x] `NEED_REVIEW` 创建一个 `ORDER_OPS / OPEN` 事项；`ORDER_CHANGE` 与 `ORDER_CANCEL` 只有携带合法订单号，或通道明确提供可验证的稳定父消息 ID 时才形成相应人工事项，否则归入 `NEED_REVIEW`；当前企微引用类型/内容只作证据。
- [x] 改单和取消分流不自动修改或取消任何订单。
- [x] Worker 公共验收覆盖成功、三次失败、并发幂等、租约恢复、重新解释和四种基础分流。

## Comments

- 2026-08-14 独立 Standards 终审发现原实现不能支撑 `resolved`：租约过期后旧 Worker 仍可应用解释结果；较旧任务的延迟终败可覆盖较新草稿/终态；任务 `FAILED` 与唯一 `NEED_REVIEW` 事项分属两个事务，存在不可恢复窗口。
- 已用真实 PostgreSQL 记录首条 RED：`InterpretationTaskCausalityTest` 期望租约重领后仅应用 1 个版本，当前实际为 2（exit 1）。修复必须完成 red→green、新旧因果/原子终败回归及独立双轴复审后才能重新 `resolved`。
- 2026-08-14 增量 TDD 已记录并修复：同原因 ReviewCase 唯一索引冲突、`quoted=true` 误信为订单引用、原始模型输出/异常原文进入公共面、任务已终态后可空 `lease_until` 的门禁映射。当前聚焦整合回归为 33/33，Flyway 从 V1 升级到 V20，仍待最终双轴结论归档。
