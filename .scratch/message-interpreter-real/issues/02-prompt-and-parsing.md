# 02 — 提示词 v1 与输出解析

**Type:** implementation

**What to build:** 系统提示词 v1（意图 6 类 + CUSTOMER_ORDER / SUPPLIER_TRACKING 字段 schema）与响应 JSON 解析：`InterpretationResult(intent, structuredOutput, provider, model, promptVersion, error)`。

**Blocked by:** 01 — DeepSeekMessageInterpreter 客户端骨架

**Status:** resolved

**Source:** .scratch/message-interpreter-real/spec.md（Solution 提示词/输出边界节）+ .scratch/wecom-message-intake/spec.md（意图枚举与草稿规则节 L115-125）

- [ ] 提示词 v1 常量（与 `prompt-version` 配置值对应，如 `wecom-interpret-v1`）：输出 JSON 对象，字段 = intent（六枚举之一）+ payload（按意图：CUSTOMER_ORDER → customer_name/receiver_name/receiver_phone/address/items[{product_name,quantity,unit,specification}]/settlement_method；SUPPLIER_TRACKING → lines[{receiver_name,tracking_no,quantity,shipment_judgment}]；其他意图 → 简短 reason）。模型不得输出内部 ID/编码；数量三位小数规则；无法确定不填。
- [ ] 解析：LLM 返回文本 → JSON 解析（容忍代码块包裹/前后噪音）→ intent 归一（非六枚举 → NEED_REVIEW + MODEL_OUTPUT_INVALID）→ 构造 InterpretationResult（provider/model/promptVersion 来自配置，error=null）。
- [ ] 意图细化参考既有 intent 判定规则（spec L115-125：CUSTOMER_ORDER/SUPPLIER_TRACKING 创建待办、NON_BUSINESS 不创建等由路由层负责——本票只保证 intent 归一正确）。
- [ ] 主验收（延续 01 的 stub server）：成功响应 → 各意图解析正确（6 类各一用例）；非法 intent / 非 JSON / 缺字段 → NEED_REVIEW + MODEL_OUTPUT_INVALID；structuredOutput 只含模型原始 JSON 键。
- [ ] 提示词文本内嵌引用 spec 规则（不把业务规则写死进解析器，模型输出为准 + boundary 兜底）。

**Do not:** commit；改动路由/草稿/复核逻辑；引入新依赖。

**完成后更新票：** 勾选 checkbox、追加 ## Comments、Status 置 resolved。

## Comments

- 提示词 v1（PROMPT_V1，prompt-version=wecom-interpret-v1）：意图六枚举 + CUSTOMER_ORDER（receiver{name,phone,address}/items[{product,spec,unit,quantity,source_sku_ref}]/customer/settlement_method）+ SUPPLIER_TRACKING（lines[{name,tracking_no,task_no,carrier,shipment,actual_quantity}] + names/tracking_nos 配对失败路径）；禁止输出内部 ID、不猜测。
- 解析（DeepSeekMessageInterpreter.parse）：strip 代码块包裹 → JSON → intent 归一（大小写不敏感，非法 → NEED_REVIEW+MODEL_OUTPUT_INVALID）→ structuredOutput 透传模型原始 JSON（键契约与 WecomOrderDraftFactory/WecomTrackingDraftFactory 消费方一致，已在实现前核对）。
- 验收：DeepSeekMessageInterpreterTest 15/15（新增 6 意图、非法 intent、小写归一、非 JSON、代码块容错、lines 透传）；回归 26/26。
