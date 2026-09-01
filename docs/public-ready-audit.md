# 企业 ERP Public-ready 文案对账

审计日期：2026-08-12

## 概述

- 扫描范围：`frontend/src` 中实际路由可达的用户文案；会由页面、Toast 或抽屉透传的后端错误；路由、Compose、静态演示与 Mock/Demo 渲染路径。
- 排除范围：测试、构建产物、代码注释、内部日志，以及未被任何路由或组件引用的 `PlaceholderPage`。
- 产品基准：内部企业订单履约 ERP；受众为运营、履约、采购和管理人员；语气应正式、简洁、可执行；不得暴露 PII、密钥、内部 URL、原始 SDK/异常信息，Mock/Demo 必须明确且隔离。
- 结论：共 19 项。分类按发现行计数（组合分类会分别计入）：A 8 项、B 16 项、C 5 项、D 3 项；优先级为 🔴 9 项、🟠 8 项、🟡 1 项、⚪ 1 项。
- 错误面核对：逐一追踪了 36 个 `errorMessage(...)` 渲染点、直接渲染的 `result.message` / `run.error.message`，以及 152 个后端业务错误/连接测试消息线索；共同缺口集中在 4xx 原文与字段名透传。

分类：A = 不适合直接上线；B = 用户友好向；C = 与产品画像不符；D = 硬编码项。

## 对账表

| # | 分类 | 位置 | 原文 | 问题 | 建议 | 优先级 |
|---|---|---|---|---|---|---|
| 1 | B | `frontend/src/api/client.ts:35-42` | ```${f.field}: ${f.message}```；`return body.message` | 4xx 与字段错误先于状态转换直接返回，36 个调用点都会显示后端字段名或“请求参数校验失败”。用户通常只知道请求失败，不知道应改哪一项；也可能收到内部错误原文。 | 先按状态/业务码转换，再把字段名映射为页面标签；统一输出“哪项内容有问题 + 去哪里/如何修正”，未知 4xx 只显示稳定提示和追踪编号。 | 🔴 |
| 2 | A+B | `backend/src/main/java/cn/zimu/fulfillment/file/SourceFileParser.java:51-60,393-395` | `无法读取上传文件: ` + `exception.getMessage()` | 文件导入失败时把解析库异常原文送入 422，随后由 #1 显示；可能暴露 ZIP/工作簿内部结构，且没有可执行修复方式。 | 客户端只返回“文件无法识别，请确认文件未损坏且格式为 Excel/CSV 后重试”；异常原文仅进服务端日志并关联追踪编号。 | 🔴 |
| 3 | A+B+C | `frontend/src/pages/fulfillment/JdWarehousePage.tsx:123-167` | `京东仓配 SDK`、`真实 SDK 配置不完整`、`${business_code}`、`JSON.stringify(sdkResult.data)` | 默认 Mock 会显示 `MOCK_SUCCESS`、`mock client completed` 和 request/response JSON；真实模式也会直出 SDK 业务码、消息和数据。既暴露内部实现，也让 Mock 结果像真实作业结果，错误时没有业务下一步。 | 页面改为“京东仓配连接检查”，明确“模拟数据/真实查询”；仅展示白名单业务字段。失败时说明“权限/连接检查未通过 + 联系管理员核对授权或稍后重试”，原始码和响应只留审计。 | 🔴 |
| 4 | A+B | `frontend/src/pages/workbench/ManualReviewPage.tsx:42-45,165-179` | `${subject_type} #${subject_id}`；`label: key`；`JSON.stringify(value)` | 核心人工复核抽屉直接显示 `ORDER_LINE`、`scenario`、`requested_quantity` 等内部字段及 JSON；操作员看不到字段业务含义，也没有针对事项的处理指引。 | 为对象类型、团队、原因和 detail 建白名单视图模型；按事项类型呈现“原因、证据、应核对内容、处理入口”，未知字段不渲染到用户界面。 | 🔴 |
| 5 | A+B | `frontend/src/pages/orders/OrderDetailPage.tsx:216-222` | ```${case_no}（${reason}）：${JSON.stringify(c.detail)}``` | 订单详情重复直出复核 detail JSON；当前真实数据会显示 `{"message":"来源回传失败","scenario":"SYNC_FAILED"}`，暴露枚举且没有下一步。 | 复用人工复核的领域化摘要，并提供“前往人工复核”动作；内部 detail 仅供审计/调试。 | 🔴 |
| 6 | A+B | `frontend/src/components/OrderTimeline.tsx:56-70,127-148` | `JSON.stringify(raw)`、`event.operator || 'system'`、`按 sequence_no 升序` | 时间线把未知 payload 键和值原样显示；当前数据中的 `line_count`、`seed-runner` 会直接出现，Demo 还会显示 `scenario_code/mock_step`。`sequence_no` 是内部字段，不是用户语义。 | 事件类型按白名单定义标题、字段与动作；未知 payload 隐藏并记录日志。把 `system`/seed 操作员映射为业务角色，把页脚改为“按发生时间排序”。 | 🔴 |
| 7 | A+B | `frontend/src/pages/system/AuditLogsPage.tsx:166-186` | `request_id`、`trace_id`、`请求快照`/`响应快照` + 原始 JSON | 页面整块展示审计 JSON；订单创建会把含收货人、电话、地址的 input/detail 写入审计，而当前脱敏器只处理 password/token/secret 等键，因此会违反“不暴露 PII”红线。界面还直接暴露追踪字段。 | 在服务端落库和返回前按角色做 PII 脱敏；页面只展示白名单业务摘要。追踪编号折叠到“技术详情”，并限制审计详情权限。 | 🔴 |
| 8 | A+C+D | `frontend/src/api/endpoints.ts:48-54,265-303`（原审计时点） | `X-Operator: ops-admin`；`X-Operator: demo-admin` | 所有浏览器写操作固定冒充同一运营账号，文件下载还写成 Demo 管理员；这些值会出现在审计与时间线，破坏“所有人工操作可审计”的核心承诺。 | 从已认证会话由服务端确定真实操作人；身份未建立时禁用正式写操作。Demo 身份只能用于 `/demo` 数据域，不能用于 BUSINESS。 | ✅ **已解决（2026-08-31 前）**：`ops-admin`/`demo-admin` 硬编码已从 `frontend/src/api/endpoints.ts`/`client.ts` 移除（复核零命中），`client.ts` 现明确声明浏览器不得提供 `X-Operator`、操作人身份由受信网关覆盖注入 |
| 9 | A+D | `docker-compose.yml:128-129` | `demo@zimu.local` / `zimu-demo-2026` | 管理驾驶舱经 `/metabase` 对外可达，Compose 与 README 公开可预测的默认管理员账号和密码；若部署者未覆盖即可直接登录。 | 取消默认值并在缺失环境变量时让初始化失败；首次启动生成/注入独立凭据，轮换已有账号，README 只说明配置方式。 | 🔴 |
| 10 | B | `backend/src/main/java/cn/zimu/fulfillment/common/error/GlobalExceptionHandler.java:36-72,80-94` | `请求参数校验失败`、`请求体不是合法 JSON 或字段类型不匹配`、`接口不存在: …`、`唯一性约束冲突` | 这些错误经 #1 可到达 UI，使用 JSON、接口、字段类型、约束等开发语言；多数只有“发生了什么”，没有用户可执行动作。 | 按业务码返回稳定用户消息，例如“提交内容无法识别，请检查必填项和格式后重试”；资源/方法/SQL 细节仅写日志。 | 🟠 |
| 11 | B | `backend/src/main/java/cn/zimu/fulfillment/file/TrackingFileService.java:79-85,225-256` | `当前缺少京东官方回传 golden 样表`、`结果必须为 SHIPPED/PARTIAL/FAILED`、`FAILED 必须填异常原因` | 上传 Toast 会显示 golden、英文枚举与固定格式，用户不知道应下载哪个模板或修改哪一行。 | 返回行号/列名和中文允许值，并给动作：“请重新下载本批次模板，按‘已发货/部分发货/失败’填写后重新上传”；京东 gate 说明联系管理员补充官方模板。 | 🟠 |
| 12 | B+C | `backend/src/main/java/cn/zimu/fulfillment/connector/ExcelPlatformConnector.java:19-27` | `Connector 已停用`、`文件 Adapter 可用`、`在线 API 尚未配置端点或凭据引用` | `ConnectorsPage.tsx:155-156` 会原样展示这些结果；Connector/Adapter/API Client 是开发实现名，失败消息没有管理员下一步。 | 统一为渠道业务状态：“文件接入可用”“在线接入未完成授权，请在渠道接入中补充服务地址和凭据后重试”。 | 🟠 |
| 13 | B | `frontend/src/pages/demo/AiOrderAssistantPanel.tsx:120-127` | `管理员配置兼容 OpenAI 协议的模型后即可启用` | 这是诚实能力说明，不属于占位，但对业务演示用户暴露协议与配置实现；用户也没有可执行入口。 | 改为“智能提取暂不可用；可继续使用固定演示场景。管理员完成智能服务接入后可启用”，具体协议放运维文档。 | 🟠 |
| 14 | B+C | `frontend/src/pages/analytics/AnalyticsPage.tsx:309-315,572-580,845-847` | `BOM`、`Internal SKU`、`Order Line`、`Canonical SKU`、`契约 §4.7` | 经营分析面向运营和管理者，却混用领域内部英文与代码契约编号，指标口径难以一次读懂。 | 分别改为“礼包组件清单”“内部商品编码”“订单明细行”“换算后的实际商品件数”；口径用业务例子说明，不引用内部契约。 | 🟠 |
| 15 | B | `frontend/src/pages/fulfillment/SalesOutboundPage.tsx:97-103,170-174,270-275,397-400` | `原批次 ID`、`XLSX`、`整批校验，再原子写入`、`模板版本` | 文件作业页混入 ID、文件扩展名和事务术语；“原子写入”无法指导履约人员完成回传，版本字段也直接显示内部值。 | 改为“原导入批次号”；说明“请选择从本批次下载并填写完成的履约文件，全部内容检查通过后才会接收”；模板版本仅在技术详情展示。 | 🟠 |
| 16 | B | `frontend/src/pages/procurement/ProcurementTicketsPage.tsx:211-213` | `结果回填 · 采购回执（…，append-only）` | `append-only` 是存储实现术语，采购人员无法由此理解业务规则。 | 改为“采购回执（历史记录只追加、不覆盖）”。 | 🟠 |
| 17 | B+C | `frontend/src/pages/dashboard/DashboardPage.tsx:143,212-214` | `BUSINESS 数据域`、`Asia/Shanghai` | 工作台把内部隔离枚举和时区标识直接放进业务提示，与简洁的 ERP 语气不一致。 | 改为“仅统计正式业务订单”“业务日按北京时间计算”。 | 🟡 |
| 18 | B | `frontend/src/pages/orders/OrderDetailPage.tsx:112-123` | `404`；`订单不存在或不在当前数据域（BUSINESS）` | 页面直接显示 HTTP 状态和内部数据域；虽然提供了返回动作，但用户仍无法区分订单不存在与无权查看。 | 去掉 404/BUSINESS，改为“未找到该订单，或你没有查看权限；请核对订单号后返回列表，仍有疑问请联系管理员”。 | 🟠 |
| 19 | D | `frontend/src/pages/demo/AiOrderAssistantPanel.tsx:26-27,212-214` | `2026 年 8 月 15 日`、`2026 年 8 月 31 日` | 示例明确标为演示，不构成 A 类；但固定日期会过期，之后可能生成已过交付/结账时间的草稿。 | 按当前业务日生成未来日期，或从 Demo 配置注入参考日；继续保留“演示订单示例”标识。 | ⚪ |

## 最先处理的三个上线阻塞

1. 封住错误透传链：先修 `errorMessage` 的状态/业务码映射，再移除文件解析异常原文；否则所有页面会持续泄露技术消息。
2. 取消动态对象直出：京东 SDK、人工复核、订单详情、时间线和审计必须采用白名单视图；审计 PII 需先在服务端脱敏。
3. ~~恢复身份与管理入口可信度：正式写操作不能固定为 `ops-admin/demo-admin`~~（#8 已解决，见对账表；`X-Operator` 硬编码已移除）。Metabase 仍不能保留公开默认管理员密码（#9，未复核，留待确认）。

## 改法示例

- A：`{"scenario":"SYNC_FAILED"}` → “来源渠道回传失败；请核对回填文件后在人工复核中重新处理”。原始 JSON 仅保留在受限审计记录。
- B：`requested_quantity: 必须大于 0` → “第 3 行的申请数量必须大于 0，请修改后重新上传”。
- C：`京东仓配 SDK · MOCK_SUCCESS` → “京东仓配连接检查 · 模拟数据（不代表真实权限）”。
- D：默认管理员密码 → 必填部署变量；`X-Operator` → 服务端从认证会话注入真实操作人。

## 红项抽查证据

- #1：36 个页面/组件调用点共用 `frontend/src/api/client.ts:35-42`；字段错误在 401/404/500 分支前返回，普通 4xx 直接返回 `body.message`。
- #2：`SourceFileParser.safeMessage` 明确返回 `exception.getMessage()`，上传入口在 `SkuMappingsPage` / `SalesOutboundPage` 通过 `errorMessage` 渲染。
- #3：页面直接渲染 `business_code/message/data`；默认 `MockJdWarehouseClient` 返回 `MOCK_SUCCESS`、英文消息与 request/response 对象。
- #4：公共 HTTP seam 的 `/api/v1/review-cases` 当前返回 `subject_type=ORDER_LINE`，detail 含 `scenario=SYNC_FAILED`；抽屉逐键原样渲染。
- #5：公共 HTTP seam 的 `/api/v1/orders/120` 当前复核 detail 含 `scenario=SYNC_FAILED`；页面对整段 detail 执行 `JSON.stringify`。
- #6：公共 HTTP seam 的 `/api/v1/orders/120/timeline` 当前 payload 含未映射的 `line_count`，operator 为 `seed-runner`；组件还固定显示 `sequence_no`。
- #7：`OrderCreateService:450-460` 把含收货信息的 input/detail 写入审计；`SecretRedactor:11-38` 未覆盖 name/phone/address；审计详情页整块输出 JSON。
- #8（原审计时点证据，现已解决）：`writeHeaders`、履约文件下载和通用下载分别硬编码 `ops-admin` / `demo-admin`，且这些操作人会进入审计与事件；2026-08-31 前已改为服务端网关覆盖身份，`endpoints.ts`/`client.ts` 复核零命中。
- #9：相同默认账号由 Compose 注入、Metabase provisioner 使用，并在 README 公开；不是仅存在于测试代码的样例。

## 验证

- `npm test`（`frontend`）：7/7 通过。
- `npm run build`（`frontend`）：通过；仅有既有 chunk size 警告。
- 只读公共 seam：`GET /api/v1/review-cases`、`GET /api/v1/orders/120`、`GET /api/v1/orders/120/timeline`、`GET /api/v1/audit-logs/1` 成功，用于确认动态原文可达。
- `rg` 复核：占位/调试线索中只有 `PlaceholderPage` 命中“建设中”，该组件没有引用或路由，不计为发现；Demo/Mock 页面已明确隔离，只有其原始技术 payload 计入发现。

本报告只做审计，未修改任何用户文案或生产代码。
