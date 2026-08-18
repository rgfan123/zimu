# 09 — AI/Agent UI 组件库调研

> 调研日期：2026-08（基于当时 npm registry / 官方文档的一手数据）
> 目标：为二期「Agent 管理页」（Agent 定义管理、草稿确认、运行记录看板、对话式创建 Agent）选定可复用 AI/Agent UI 资产，优先复用现成组件库，不重复造轮子。

## 0. 结论速览（TL;DR）

| 类别 | 推荐 | 一句话理由 |
|---|---|---|
| ① 对话交互（对话式创建 Agent） | **`@ant-design/x` 锁定 1.6.x** + 复用已有 `orderAssistantApi` 会话/草稿/确认范式 | 蚂蚁官方 AI 组件库，MIT，1.6.1 与项目 antd 5.21 完全兼容（2.x 才要求 antd 6）；Bubble/Sender/Conversations/useXAgent 一套覆盖 |
| ② Agent 列表与草稿确认 | **antd 原生 Table/Form/Drawer/Descriptions**（可选加 `@ant-design/pro-components` 的 ProTable） | 与 antd 零摩擦、MIT、ProTable 支持 antd 5.11+；草稿确认复用 workbench 已有 ReviewPanel 范式 |
| ③ 运行记录看板 | **antd Table + Drawer 详情 + Timeline + echarts**（项目已有 echarts） | Langfuse/Dify 日志形态作参考；自研轻量看板足够，不引入重平台 |
| ④ 编排画布（可省） | **`@xyflow/react`（React Flow）** | MIT、活跃维护；Flowise/Dify/Langflow 同款底层；二期可先以表单化配置替代 |

**不推荐引入**：Vercel AI SDK 的 UI 组件/AI Elements（Next.js 生态绑定 + Java 后端需手写协议）、shadcn/ui chat（Tailwind 体系冲突）、`@chatscope/chat-ui-kit-react`（维护趋缓 + 设计语言冲突）。

---

## 1. 本项目前端技术栈（仓库内核实，非假设）

来源：`frontend/package.json`、`frontend/src/` 目录结构。

- **React 18.3 + Vite 5 + TypeScript 5.6**，路由 react-router-dom 6.26
- **UI 组件库：antd ^5.21.0 + @ant-design/icons**（企业级设计语言，整套在用）
- 其他：dayjs（日期）、**echarts 5.5（图表，看板可用）**、jsdom（测试）
- 管理页先例：`pages/system/` 已有 4 个管理页（AuditLogs、Connectors、FulfillmentProviders、SystemConfig）；`pages/workbench/` 已有**草稿评审面板** `OrderDraftReviewPanel.tsx` / `TrackingDraftReviewPanel.tsx`——「草稿确认」交互范式仓库内已有可复刻的先例
- **已有的 AI 对话资产**：`pages/demo/AiOrderAssistantPanel.tsx`（279 行）实现了一个完整的「对话式创建」闭环：
  - 会话创建 `orderAssistantApi.createSession()` → 逐条 `sendMessage()` → 结构化草稿预览（Descriptions + Table）→ `READY_TO_CONFIRM` 待确认状态 → `confirm()` → `onRunCreated(DemoRun)` 写运行记录
  - 该文件底部还有 `service_ready` 提示「管理员配置兼容 OpenAI 协议的模型后即可启用」——**后端已具备兼容 OpenAI 协议的流式/对话能力**（Spring Boot）
  - 前端目前是**手搓聊天 UI**（手写气泡 div、`Input.TextArea` + 手动 useState 管理消息），正是本次要替换/升级的部分

> 结论：本项目不是从零起步。「对话式创建 Agent」与 `AiOrderAssistantPanel` 的「会话→对话→草稿→确认→运行」是同构范式，后端 API 形态可直接迁移复用；前端只需把「手搓气泡」换成成熟聊天组件。

---

## 2. ① 对话/聊天 UI 候选评估

### 2.1 `@ant-design/x`（Ant Design X）—— 推荐主选

- npm：`@ant-design/x`，**MIT**，latest **2.9.0**（2026-07-28 更新，维护活跃）；官网 <https://ant-design-x.antgroup.com/components/introduce-cn>，GitHub <https://github.com/ant-design/x>
- **版本兼容性是关键**：
  - **1.6.1**（2025-09-12 发布，1.x 线最后版本）peerDependencies：`antd ^5.20.3` → **与项目 antd 5.21 完全兼容，零升级成本**
  - 2.x（2.0.0 于 2025-11-22 发布）peerDependencies：`antd ^6.x` → 需要先升 antd 6
  - 1→2 迁移说明：<https://ant-design-x.antgroup.com/docs/react/migration-v2-cn>
- 覆盖「对话式创建 Agent」所需组件（官方组件名）：
  - **Bubble**（聊天气泡，支持 loading/打字态/内容扩展）、**Sender**（发送输入区，支持 loading、attachments、自动高度）、**Conversations**（会话列表，新建/删除/选中）、**Prompts**（提示词模板，可做「帮我创建 X 类 Agent」引导）、**Suggestion**（输入建议）、**Welcome**（空态欢迎页）、**Attachments**（附件）、**ThoughtChain**（Agent 思考链展示）、**XProvider**（主题）
  - Hooks：**useXAgent / useXChat**（对话状态 + 请求管理，对接任意后端 fetch，不绑定 Node 协议）
- 取舍：与 antd 设计语言完全一致，主题走 `ConfigProvider`，无需引入第二套设计体系。**唯一注意点：package.json 锁 `@ant-design/x@~1.6.1`，防止误升 2.x 拉到 antd 6**。二期若整体升级 antd 6（参考官方 v5→v6 迁移：<https://ant.design/docs/react/migration-v6-cn>），再同步升 x 到 2.x。
- 落地方式：替换 `AiOrderAssistantPanel.tsx` 中手写气泡 div 与 TextArea 为 `Bubble.List + Sender`，会话/草稿/确认逻辑与 `orderAssistantApi` 不变。

### 2.2 Vercel AI SDK（`ai` / `ai/react` useChat）—— 备选，评估后不推荐做主 UI

- npm：`ai` latest **7.0.66**，**Apache-2.0**（registry 核实）；<https://ai-sdk.dev>
- `useChat` 等 hooks 是优秀的对话状态管理（消息数组 + 流式渲染 + 中断重试），<https://github.com/vercel/ai> 文档 `use-chat`：<https://ai-sdk.dev/docs/reference/ai-sdk-ui/use-chat>
- **不利因素**：
  - 官方默认消息协议面向 Node/Edge 后端（AI SDK 服务端 `streamText` 流式协议）；**本项目是 Spring Boot（Java）后端，无官方 Java SDK**，需手工实现其 UI 消息协议或自写 fetch——协议对接成本高于收益
  - 官方预构建 UI 组件 **AI Elements**（2025-10 发布，<https://vercel.com/changelog/introducing-ai-elements>、<https://vercel.com/academy/ai-sdk/ai-elements>）与 Next.js/Server Components 生态深度绑定，与本项目 Vite SPA 不符
  - 官方模板 vercel/ai-chatbot（<https://github.com/vercel/ai-chatbot>，MIT）：Next.js + shadcn/ui + Tailwind + Auth.js + Neon，技术栈差异大，**仅作交互范式参考**（多会话侧栏、流式消息、生成式 UI）
- 结论：若未来需要「流式打字机」体验且后端可自行实现流式协议（项目后端已兼容 OpenAI 协议，可用 SSE 透传），可参考其消息协议设计；UI 层仍建议用 `@ant-design/x`。

### 2.3 shadcn/ui chat 组件 —— 不引入，仅参考

- shadcn/ui 官方于 **2026-06** 发布 Chat 类组件（<https://ui.shadcn.com/docs/changelog/2026-06-chat-components>，InfoQ 报道 <https://www.infoq.com/news/2026/08/shadcn-conversational-primitives/>）
- 体系为 **Tailwind CSS + Radix**，与项目 antd 体系是两套设计语言，并存成本高（样式冲突、主题不一致、体积增加）
- 结论：不引入；其「流式对话原语」的交互范式可参考。

### 2.4 `@chatscope/chat-ui-kit-react` —— 不采用

- npm：latest **2.1.1**，MIT（registry 核实），最后修改 **2025-05-15**，维护趋缓
- 独立的完整聊天皮肤（自带样式体系），与 antd 视觉冲突；纯展示组件、无状态管理、无会话概念
- 结论：不采用（<https://www.npmjs.com/package/@chatscope/chat-ui-kit-react>）。

### 2.5 小结（对话交互）

| 候选 | 许可 | antd 兼容 | 维护 | 结论 |
|---|---|---|---|---|
| **@ant-design/x 1.6.x** | MIT | ✅ antd ^5.20.3 | 活跃（2.x 更活跃但需 antd 6） | **推荐** |
| Vercel AI SDK useChat | Apache-2.0 | 无关（自管消息） | 活跃 | 备选/参考协议 |
| shadcn/ui chat | MIT | ❌ Tailwind 体系 | 活跃（2026-06 新增） | 仅参考 |
| @chatscope/chat-ui-kit-react | MIT | ❌ 独立皮肤 | 趋缓 | 不采用 |
| antd 手搓（现状） | — | ✅ | — | 保留逻辑，换组件 |

---

## 3. ② Agent 管理/编排产品形态调研（可复用资产）

### 3.1 OpenWebUI —— 聊天门户形态

- 许可：**BSD-3-Clause**（2024 年从原许可改为 BSD-3，官方讨论：<https://github.com/open-webui/open-webui/discussions/8467>）；GitHub：<https://github.com/open-webui/open-webui>
- 界面形态：左侧会话列表 + 中央聊天 + 顶部模型选择器 + 工作区（知识库/模型/用户管理）
- 可复用资产（范式而非代码）：会话列表-聊天双栏布局、模型选择器、RAG 附件区

### 3.2 AnythingLLM —— 工作区形态

- 许可：**MIT**；GitHub：<https://github.com/Mintplex-Labs/anything-llm>
- 界面形态：左侧导航（工作区 + 文档库 + Agent 对话）+ 中央聊天 + 系统设置
- 可复用资产：「工作区」概念（一个 Agent = 一个隔离配置/对话空间，与二期 Agent 定义管理同构）

### 3.3 Dify —— 应用管理 + 编排 + 运行日志，参考价值最高

- 许可：开源版 **Apache-2.0**；GitHub：<https://github.com/langgenius/dify>
- 界面形态（对二期四类页面全覆盖）：
  - **应用列表**：卡片/列表展示应用（Agent/工作流/聊天助手），含草稿与发布状态
  - **编排画布**：基于 React Flow 的拖拽节点编辑器
  - **运行日志/标注**：运行记录列表 + 详情抽屉（traces/步骤）
- 对比参考：<https://futureagi.com/blog/dify-vs-flowise-vs-langflow-2026/>

### 3.4 Langflow / Flowise —— 画布形态

- Langflow：**MIT**（<https://github.com/langflow-ai/langflow>）；Flowise：**Apache-2.0**（<https://github.com/FlowiseAI/Flowise>）
- 界面形态：左侧组件面板 + 中央节点画布（React Flow）+ 右侧节点配置 + 运行测试面板
- 对比：<https://selfhosting.sh/compare/flowise-vs-langflow/>

### 3.5 LangGraph Studio / LangSmith —— 图可视化 + 可观测性形态

- LangGraph Studio：LangChain 生态桌面应用（闭源，属 LangSmith），节点图可视化 + **时间旅行调试** + 状态侧栏；概念文档：<https://github.com/langchain-ai/langgraph/blob/f8c1a323cc50a6da14836ebc4c53c79db0f6508c/docs/docs/concepts/langgraph_studio.md>
- LangSmith Studio vs Agent Chat UI：<https://support.langchain.com/articles/1353449293-langsmith-studio-vs-agent-chat-ui>
- 参考价值：运行记录看板的「步骤时间线 / 状态树」形态

### 3.6 Langfuse —— LLM 可观测性形态（看板参考）

- 许可：**MIT**（registry 核实，npm `langfuse` 3.38.20）；GitHub：<https://github.com/langfuse/langfuse>
- 界面形态：traces/spans 树形详情 + 成本/延迟/评分指标卡 + 运行表格；OpenTelemetry GenAI 兼容
- 结论：形态参考价值高，但**不建议引入整套**（自托管 + 数据模型重），二期自研轻量看板即可

### 3.7 可复用资产小结

| 产品 | 许可 | 对二期的可复用资产 |
|---|---|---|
| OpenWebUI | BSD-3 | 会话列表-聊天布局、模型选择器 |
| AnythingLLM | MIT | 工作区（Agent 隔离空间）概念 |
| Dify | Apache-2.0 | 应用列表/草稿-发布、编排画布、运行日志（最全） |
| Langflow | MIT | 画布 + 组件市场形态 |
| Flowise | Apache-2.0 | 聊天流画布 + API 端点 |
| LangGraph Studio | 闭源 | 图可视化 + 时间旅行调试（参考） |
| Langfuse | MIT | traces 树、运行指标看板（参考） |

---

## 4. ③④ 管理页/看板/画布的组件选型

### 4.1 Agent 列表与草稿确认（管理表格类）

- **antd 原生**：`Table`（列表+分页）、`Form`（草稿编辑）、`Drawer`/`Modal`（草稿详情）、`Descriptions`（只读预览）、`Tag`（状态：草稿/待确认/已发布）、`Popconfirm`（删除确认）、`Segmented`/`Tabs`（草稿 vs 已发布切换）——项目已全量具备
- **`@ant-design/pro-components`（ProComponents，可选增强）**：MIT，latest **2.8.10**（2026-07-29 更新，活跃），peerDependencies `antd ^4.24.15 || ^5.11.2` → **兼容项目 antd 5.21**（registry 核实）。`ProTable`（搜索表单+表格+列设置+批量操作一体）适合「Agent 定义管理」「运行记录」这类高频管理表格；`ProForm` 适合「Agent 定义编辑表单」
- 草稿确认交互：直接复刻 `pages/workbench/OrderDraftReviewPanel.tsx` 的「预览 + 逐项确认 + 补充缺失字段」范式（仓库内已有先例），无需新库
- 参考形态：Dify 应用列表的「草稿/发布」状态切换

### 4.2 运行记录看板

- **antd `Table`**（运行记录列表：时间/Agent/触发方式/状态/耗时/Token）+ **`Drawer` 详情**（步骤时间线 `Timeline` 或 `Descriptions`，仿 Langfuse traces 树）+ **`Statistic`/`Badge`**（汇总指标）+ **echarts**（项目已装：耗时趋势、成功率、Token 消耗图）
- 无需引入 Langfuse/OpenTelemetry 全套；后端已有 `DemoRun` 概念（`AiOrderAssistantPanel` 的 `onRunCreated(run)`），运行记录数据结构已有雏形
- 状态色复用 `components/StatusTag.tsx`（已有语义化状态组件）

### 4.3 编排画布（可省，二期范围外）

- **`@xyflow/react`（React Flow 官方）**：MIT，latest **12.11.3**（2026-08-12 更新，活跃），react ≥17（registry 核实）；官网 <https://reactflow.dev>；**Flowise / Dify / Langflow 底层画布均为它**——若二期需要可视化编排，直接用同款即可，生态成熟（自定义节点、MiniMap、自动布局）
- 备选：若二期先不做画布，用「表单化配置」（antd Form + JSON Schema 渲染）替代可视化编排，成本更低
- 参考：<https://reactflow.dev/index>

---

## 5. 许可与维护状态汇总（一手来源）

| 资产 | 许可 | 最新版本/时间 | 与项目兼容性 |
|---|---|---|---|
| `@ant-design/x` 1.6.1 | MIT | 2025-09-12（1.x 线） | ✅ antd ^5.20.3 |
| `@ant-design/x` 2.9.0 | MIT | 2026-07-28 | ⚠️ 需 antd ^6 |
| `ai`（Vercel AI SDK） | Apache-2.0 | 7.0.66 | 仅协议参考 |
| vercel/ai-chatbot | MIT | 活跃 | ❌ Next.js 栈 |
| shadcn/ui chat | MIT | 2026-06 官方新增 | ❌ Tailwind 体系 |
| `@chatscope/chat-ui-kit-react` | MIT | 2.1.1 / 2025-05 | ❌ 独立皮肤 |
| `@ant-design/pro-components` | MIT | 2.8.10 / 2026-07 | ✅ antd ^5.11.2 |
| `@xyflow/react`（React Flow） | MIT | 12.11.3 / 2026-08 | ✅ react ≥17 |
| OpenWebUI | BSD-3 | 活跃 | 形态参考 |
| AnythingLLM | MIT | 活跃 | 形态参考 |
| Dify（开源版） | Apache-2.0 | 活跃 | 形态参考 |
| Langflow | MIT | 活跃 | 形态参考 |
| Flowise | Apache-2.0 | 活跃 | 形态参考 |
| LangGraph Studio | 闭源 | — | 形态参考 |
| Langfuse | MIT | 3.38.20 | 形态参考 |

> 注：以上版本/许可均于调研当日从 npm registry（registry.npmjs.org）与官方 GitHub/文档核实。

---

## 6. 落地取舍建议

1. **锁定版本防漂移**：`@ant-design/x` 用 `"~1.6.1"`（或精确 `1.6.1`）写入 `frontend/package.json`，避免 npm 拉到 2.x 导致 antd 6 peer 冲突；同时加一条备注说明升级路径（antd 6 迁移时同步升 x 2.x）
2. **优先替换手搓 UI，不动后端范式**：`AiOrderAssistantPanel` 的「会话→消息→草稿→确认→run」API 形态即「对话式创建 Agent」原型，后端只做字段替换（Agent 配置草稿替代订单草稿）
3. **渐进引入**：二期第一批只引入 `@ant-design/x`（对话）+ antd 原生组件（列表/看板）；`pro-components`、`@xyflow/react` 视二期范围按需加
4. **不引入 Tailwind/Next.js 系**：shadcn chat、AI Elements、ai-chatbot 仅作交互参考，避免双设计体系
