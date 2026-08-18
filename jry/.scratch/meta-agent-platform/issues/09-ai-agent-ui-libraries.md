# 09 — AI/Agent UI 组件库调研

**Type:** research
**Status:** resolved
**Blocked by:** —

## Question

用户提出：agent/AI 相关 UI 网上有现成组件库，二期前端管理页（Agent 定义管理、草稿确认、运行记录看板、对话式创建 Agent）应优先复用。调研并给出推荐清单（带证据/链接）：

1. 对话/聊天 UI：Vercel AI SDK 的 UI 组件、shadcn/ui chat 类组件、其他聊天 UI 模板——哪些适合「对话式创建 Agent」交互。
2. Agent 管理/编排产品形态：OpenWebUI、AnythingLLM、Langflow / Flowise、Dify、LangGraph Studio 等开源项目的管理界面形态与可复用资产（组件、布局、交互范式）。
3. 本项目前端技术栈（frontend/ 实际栈以仓库为准，如 React + Vite + 组件库）下的可落地选择与取舍：组件库 vs 模板 vs 自研，含许可与维护状态。
4. 结论格式：按「对话交互 / Agent 列表与草稿确认 / 运行记录看板 / 编排画布」四类给出候选 + 推荐 + 理由。

输出：结论写为 `## Answer` 追加到本票文件并置 `Status: resolved`；详细调研文档写 `.scratch/meta-agent-platform/research/09-ai-agent-ui-libraries.md`。不要运行任何 git 命令。

## Answer

前端技术栈已核实：React 18 + Vite 5 + TS + **antd 5.21** + echarts；仓库内已有手搓 AI 对话面板 `pages/demo/AiOrderAssistantPanel.tsx`（会话→消息→草稿→确认→run，与「对话式创建 Agent」同构，后端 API 可复用）。按四类结论（许可/版本均经 npm registry 核实）：

1. **对话交互（对话式创建 Agent）→ 推荐 `@ant-design/x` 锁定 1.6.x**（MIT；1.6.1 peerDeps `antd ^5.20.3`，与项目 antd 5.21 零摩擦；2.x 需 antd 6 暂不升）。Bubble/Sender/Conversations/Prompts/useXAgent/useXChat 一套覆盖对话式创建；替换 AiOrderAssistantPanel 的手写气泡即可，后端逻辑不变。备选：Vercel AI SDK useChat（Apache-2.0，但 Java 后端需手写流协议，仅参考）；不引入 shadcn/ui chat（Tailwind 体系冲突）与 @chatscope/chat-ui-kit-react（2025-05 后停更、独立皮肤）。
2. **Agent 列表与草稿确认 → antd 原生 Table/Form/Drawer/Descriptions/Tag**（可选加 `@ant-design/pro-components` ProTable，MIT、支持 antd 5.11+）；草稿确认复刻 `pages/workbench/OrderDraftReviewPanel.tsx` 已有范式；参考 Dify 应用列表的「草稿/发布」状态切换。
3. **运行记录看板 → antd Table + Drawer 详情 + Timeline + echarts（项目已装）**；仿 Langfuse/Dify 运行日志形态（列表 + traces 树详情 + 指标卡），自研轻量即可，不引入 Langfuse 全套。
4. **编排画布（可省）→ 如做则用 `@xyflow/react`（React Flow，MIT，Flowise/Dify/Langflow 同款底层）**；二期可先用表单化配置替代。

落地要点：package.json 锁 `@ant-design/x@~1.6.1` 防误升 antd 6；不引入 Tailwind/Next.js 系；形态参考 OpenWebUI（BSD-3）/AnythingLLM（MIT）/Dify（Apache-2.0）/Langflow（MIT）/Flowise（Apache-2.0）/LangGraph Studio（闭源）。详见 `.scratch/meta-agent-platform/research/09-ai-agent-ui-libraries.md`。
