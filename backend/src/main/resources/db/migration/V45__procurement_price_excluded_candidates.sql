-- Agent 平台化补丁（procurement-price-outliers 01 票：采购比价不可比候选三规则）。
--
-- 定义变更走**新版本**而不是原地刷新：agent_definitions 是 append-only 全快照
-- （meta-agent-platform 票 03），状态机 draft→active→retired 无回边，唯一 (agent_slug, version)
-- 与部分唯一索引 UNIQUE (agent_slug) WHERE status='active' 保证每 slug 至多一个生效版本。
-- 原地改 active 行会让版本链失去意义——改完之后没有任何记录能说明 v1 原来长什么样，
-- 也无法按「复制旧版本为新草稿」的既定路径回滚。
--
-- 评测用例同理：票 07 定「用例绑定 (agent_slug, agent_version)，每定义版本冻结一份用例集，
-- 换例 = 换版本」。因此 procurement-eval-v1 的 7 例**原样保留**在 version 1 上（基线可复现、
-- 可回滚），v2 的 12 例绑定到 version 2，而不是删掉旧的。
--
-- 播种内容与代码常量（ProcurementPriceAgentConfiguration 的系统提示词 / DESCRIPTION /
-- outputSchemaForSeed）及评测 fixture 逐字一致，AgentDefinitionSeedParityTest 校验。

-- ① v1 退役（前向转移，状态机允许）
UPDATE app.agent_definitions
SET status = 'retired'
WHERE agent_slug = $seed$procurement-price-agent$seed$
  AND version = 1
  AND status = 'active';

-- ② v2 作为新的生效版本插入，未变更的列从 v1 继承
INSERT INTO app.agent_definitions (
    agent_slug, version, name, description, system_prompt, prompt_version, model_ref,
    enabled, tool_whitelist, output_schema, allow_write, guard_exemptions,
    status, activated_by, activated_at)
SELECT agent_slug,
       2,
       name,
       $seed$针对采购工单/SKU 汇总进货价、履约方映射与库存上下文，输出结构化比价建议；不可比候选降级展示并说明理由；低置信度或信息不全时转人工。$seed$,
       $seed$你是采购比价 Agent（只读，绝不触发任何写操作）。你的职责：针对采购工单或 SKU 汇总进货价、履约方映射与库存上下文，输出结构化比价建议。
输入是结构化 JSON：{"procurement_ticket_id": "..."} 或 {"sku_id": "..."}，可含 {"quantity": "..."}。

工具调用序列（如适用，依序进行；信息足够时即可省略后续步骤）：
1. 输入含 procurement_ticket_id 时先调 get_procurement_ticket 获取缺口与上下文；只有 sku_id 时调 get_sku；
2. 调 search_skus 或 get_sku 获取目标 SKU 的进货价/零售价（decimal-string）；
3. 调 list_provider_skus（按 provider_id）获取各履约方外部编码与映射，注意映射的 active 字段——已停用/过期（active=false）的映射不可比价；
4. 调 get_inventory_overview 或 get_inventory_detail 确认可用库存。

不可比候选（三规则并集，剔除 = 降级展示，不是删除）：
- 价格离群（exclusion_reason=price_outlier）：候选价格与同组候选价格的中位数偏离超过 2 倍（价格 > 中位数×2 或 < 中位数÷2）时，放入 excluded_candidates；
- 价格缺失（exclusion_reason=price_missing）：候选没有可用价格（未定价或格式非法）时，放入 excluded_candidates（该情况整体 requires_human=true）；
- 映射失效（exclusion_reason=mapping_stale）：候选来自 active=false 的履约方 SKU 映射时，放入 excluded_candidates 并注明映射已停用或过期；
- excluded_candidates 每项必须带 exclusion_reason 与 exclusion_reason_detail（可读理由）；被剔除的候选绝不静默丢弃，必须原样随理由返回。

输出规则（严格遵守 ProcurementPriceRecommendation schema）：
- target_sku 填目标 SKU 编码（如 SKU-1001）；requested_quantity 填输入数量 （decimal-string，输入未提供可为空）；
- inventory.available / inventory.shortage 为 decimal-string；无库存观测时 inventory 置 null；
- candidates 只放可比候选（价格齐全且映射有效、未离群）；excluded_candidates 放被剔除候选；两组的 provider_code/price/price_basis/note 都必须来自工具返回，价格 decimal-string 且最多两位小数（SCALE=2），严禁编造；
- 有可比候选且信息完整时：requires_human=false，recommendation 从可比候选中给出最低价且可信的 provider 与理由（必须来自工具事实，绝不推荐被剔除候选）；
- 无可比候选 / 无价格 / 字段缺失 / 低置信度（confidence<0.6）/ 库存未知 时：requires_human=true，recommendation 置空，missing_fields 列出缺失项，只给出可复核的事实摘要（含被剔除候选与理由）；
- confidence 依据数据完整度与价格一致性给出 0.0-1.0 的分数。

安全约束：
- 只调用白名单内的只读工具（list_procurement_tickets / get_procurement_ticket / list_procurement_receipts / search_skus / get_sku / list_provider_skus / get_inventory_overview / get_inventory_detail / list_products / list_categories / list_fulfillment_providers），绝不调用任何写工具；
- 不发起采购、不下单、不修改任何工单；建议不落业务表。$seed$,
       $seed$procurement-price-v2$seed$,
       model_ref,
       enabled,
       tool_whitelist,
       $seed$
{
  "type": "object",
  "additionalProperties": false,
  "required": ["target_sku", "candidates", "excluded_candidates", "missing_fields", "confidence", "requires_human"],
  "properties": {
    "target_sku": {"type": "string"},
    "requested_quantity": {"type": ["string", "null"], "description": "decimal-string，SCALE=2"},
    "inventory": {
      "type": ["object", "null"],
      "additionalProperties": false,
      "required": ["available", "shortage"],
      "properties": {
        "available": {"type": ["string", "null"]},
        "shortage": {"type": ["string", "null"]}
      }
    },
    "candidates": {
      "type": "array",
      "description": "可比候选（价格齐全且映射有效、未离群）",
      "items": {
        "type": "object",
        "additionalProperties": false,
        "required": ["provider_code", "price", "price_basis"],
        "properties": {
          "provider_code": {"type": "string"},
          "price": {"type": ["string", "null"], "description": "decimal-string，SCALE=2"},
          "price_basis": {"enum": ["sku_commercial_price", "provider_sku"]},
          "note": {"type": ["string", "null"]}
        }
      }
    },
    "excluded_candidates": {
      "type": "array",
      "description": "被剔除候选（降级展示，不是删除）：携带理由标签与可读说明",
      "items": {
        "type": "object",
        "additionalProperties": false,
        "required": ["provider_code", "exclusion_reason"],
        "properties": {
          "provider_code": {"type": "string"},
          "price": {"type": ["string", "null"], "description": "decimal-string，SCALE=2"},
          "price_basis": {"enum": ["sku_commercial_price", "provider_sku"]},
          "note": {"type": ["string", "null"]},
          "exclusion_reason": {"enum": ["price_outlier", "price_missing", "mapping_stale"]},
          "exclusion_reason_detail": {"type": ["string", "null"]}
        }
      }
    },
    "recommendation": {
      "type": ["object", "null"],
      "additionalProperties": false,
      "required": ["provider_code", "reason"],
      "properties": {
        "provider_code": {"type": "string"},
        "reason": {"type": "string"}
      }
    },
    "missing_fields": {"type": "array", "items": {"type": "string"}},
    "confidence": {"type": "number", "minimum": 0.0, "maximum": 1.0},
    "requires_human": {"type": "boolean"}
  }
}$seed$::jsonb,
       allow_write,
       guard_exemptions,
       'active',
       $seed$system:v34-seed$seed$,
       CURRENT_TIMESTAMP
FROM app.agent_definitions
WHERE agent_slug = $seed$procurement-price-agent$seed$ AND version = 1;


INSERT INTO app.agent_eval_cases (
    agent_slug, agent_version, eval_set_version, case_key, metric_kind,
    input, expected, status, created_by, confirmed_by, confirmed_at)
VALUES
    ($seed$procurement-price-agent$seed$, 2, $seed$procurement-eval-v2$seed$, $seed$happy-path-ticket$seed$, 'INVARIANT', $seed$
{"input": {"procurement_ticket_id":"9001","quantity":"2"}, "model_output": "{\"target_sku\":\"SKU-1001\",\"requested_quantity\":\"2\",\"inventory\":{\"available\":\"0\",\"shortage\":\"2\"},\"candidates\":[{\"provider_code\":\"P001\",\"price\":\"12.34\",\"price_basis\":\"sku_commercial_price\",\"note\":\"主数据进货价\"},{\"provider_code\":\"P002\",\"price\":\"12.90\",\"price_basis\":\"provider_sku\",\"note\":\"履约方映射价格\"}],\"excluded_candidates\":[],\"recommendation\":{\"provider_code\":\"P001\",\"reason\":\"最低价且来自主数据进货价\"},\"missing_fields\":[],\"confidence\":0.9,\"requires_human\":false}"}$seed$::jsonb, $seed$
{"requires_human":false,"write_tool_calls":0}$seed$::jsonb, 'CONFIRMED', 'system:v34-seed', 'system:v34-seed', CURRENT_TIMESTAMP),
    ($seed$procurement-price-agent$seed$, 2, $seed$procurement-eval-v2$seed$, $seed$happy-path-sku-no-quantity$seed$, 'INVARIANT', $seed$
{"input": {"sku_id":"1001"}, "model_output": "{\"target_sku\":\"SKU-1001\",\"requested_quantity\":null,\"inventory\":{\"available\":\"5\",\"shortage\":\"0\"},\"candidates\":[{\"provider_code\":\"P003\",\"price\":\"8.50\",\"price_basis\":\"sku_commercial_price\",\"note\":\"主数据进货价\"}],\"excluded_candidates\":[],\"recommendation\":{\"provider_code\":\"P003\",\"reason\":\"唯一候选\"},\"missing_fields\":[],\"confidence\":0.85,\"requires_human\":false}"}$seed$::jsonb, $seed$
{"requires_human":false,"write_tool_calls":0}$seed$::jsonb, 'CONFIRMED', 'system:v34-seed', 'system:v34-seed', CURRENT_TIMESTAMP),
    ($seed$procurement-price-agent$seed$, 2, $seed$procurement-eval-v2$seed$, $seed$no-candidates$seed$, 'INVARIANT', $seed$
{"input": {"procurement_ticket_id":"9002","quantity":"1"}, "model_output": "{\"target_sku\":\"SKU-2001\",\"requested_quantity\":\"1\",\"inventory\":{\"available\":\"0\",\"shortage\":\"1\"},\"candidates\":[],\"excluded_candidates\":[],\"recommendation\":null,\"missing_fields\":[],\"confidence\":0.8,\"requires_human\":false}"}$seed$::jsonb, $seed$
{"requires_human":true,"write_tool_calls":0,"missing_fields_contains":"candidates"}$seed$::jsonb, 'CONFIRMED', 'system:v34-seed', 'system:v34-seed', CURRENT_TIMESTAMP),
    ($seed$procurement-price-agent$seed$, 2, $seed$procurement-eval-v2$seed$, $seed$missing-price$seed$, 'INVARIANT', $seed$
{"input": {"procurement_ticket_id":"9003"}, "model_output": "{\"target_sku\":\"SKU-3001\",\"requested_quantity\":null,\"inventory\":{\"available\":\"0\",\"shortage\":\"3\"},\"candidates\":[{\"provider_code\":\"P001\",\"price\":null,\"price_basis\":\"sku_commercial_price\",\"note\":\"未定价\"}],\"excluded_candidates\":[],\"recommendation\":{\"provider_code\":\"P001\",\"reason\":\"x\"},\"missing_fields\":[],\"confidence\":0.7,\"requires_human\":false}"}$seed$::jsonb, $seed$
{"requires_human":true,"write_tool_calls":0,"missing_fields_contains":"price"}$seed$::jsonb, 'CONFIRMED', 'system:v34-seed', 'system:v34-seed', CURRENT_TIMESTAMP),
    ($seed$procurement-price-agent$seed$, 2, $seed$procurement-eval-v2$seed$, $seed$low-confidence-and-missing-fields$seed$, 'INVARIANT', $seed$
{"input": {"procurement_ticket_id":"9004","quantity":"4"}, "model_output": "{\"target_sku\":\"SKU-4001\",\"requested_quantity\":\"4\",\"inventory\":{\"available\":\"0\",\"shortage\":\"4\"},\"candidates\":[{\"provider_code\":\"P002\",\"price\":\"20.10\",\"price_basis\":\"provider_sku\",\"note\":\"外部映射无本地名\"}],\"excluded_candidates\":[],\"recommendation\":{\"provider_code\":\"P002\",\"reason\":\"x\"},\"missing_fields\":[\"provider_sku_name\"],\"confidence\":0.2,\"requires_human\":false}"}$seed$::jsonb, $seed$
{"requires_human":true,"write_tool_calls":0,"missing_fields_contains":"provider_sku_name"}$seed$::jsonb, 'CONFIRMED', 'system:v34-seed', 'system:v34-seed', CURRENT_TIMESTAMP),
    ($seed$procurement-price-agent$seed$, 2, $seed$procurement-eval-v2$seed$, $seed$happy-path-camelcase-model-output$seed$, 'INVARIANT', $seed$
{"input": {"sku_id":"1001"}, "model_output": "{\"targetSku\":\"SKU-1001\",\"requestedQuantity\":null,\"inventory\":{\"available\":\"5\",\"shortage\":\"0\"},\"candidates\":[{\"providerCode\":\"P003\",\"price\":\"8.50\",\"priceBasis\":\"sku_commercial_price\",\"note\":\"主数据进货价\"}],\"excludedCandidates\":[],\"recommendation\":{\"providerCode\":\"P003\",\"reason\":\"唯一候选\"},\"missingFields\":[],\"confidence\":0.85,\"requiresHuman\":false}"}$seed$::jsonb, $seed$
{"requires_human":false,"write_tool_calls":0}$seed$::jsonb, 'CONFIRMED', 'system:v34-seed', 'system:v34-seed', CURRENT_TIMESTAMP),
    ($seed$procurement-price-agent$seed$, 2, $seed$procurement-eval-v2$seed$, $seed$schema-invalid-output$seed$, 'INVARIANT', $seed$
{"input": {"sku_id":"1002"}, "model_output": "这不是符合 schema 的 JSON"}$seed$::jsonb, $seed$
{"requires_human":true,"write_tool_calls":0}$seed$::jsonb, 'CONFIRMED', 'system:v34-seed', 'system:v34-seed', CURRENT_TIMESTAMP),
    ($seed$procurement-price-agent$seed$, 2, $seed$procurement-eval-v2$seed$, $seed$outlier-candidate-excluded$seed$, 'INVARIANT', $seed$
{"input": {"sku_id":"1001"}, "model_output": "{\"target_sku\":\"SKU-5001\",\"requested_quantity\":null,\"inventory\":{\"available\":\"5\",\"shortage\":\"0\"},\"candidates\":[{\"provider_code\":\"P001\",\"price\":\"12.34\",\"price_basis\":\"sku_commercial_price\",\"note\":\"主数据进货价\"},{\"provider_code\":\"P002\",\"price\":\"12.90\",\"price_basis\":\"provider_sku\",\"note\":\"履约方映射价格\"},{\"provider_code\":\"P003\",\"price\":\"45.67\",\"price_basis\":\"provider_sku\",\"note\":\"渠道报价异常高\"}],\"excluded_candidates\":[],\"recommendation\":{\"provider_code\":\"P001\",\"reason\":\"最低价且可比\"},\"missing_fields\":[],\"confidence\":0.9,\"requires_human\":false}"}$seed$::jsonb, $seed$
{"requires_human":false,"write_tool_calls":0}$seed$::jsonb, 'CONFIRMED', 'system:v34-seed', 'system:v34-seed', CURRENT_TIMESTAMP),
    ($seed$procurement-price-agent$seed$, 2, $seed$procurement-eval-v2$seed$, $seed$all-mapping-stale-forces-human$seed$, 'INVARIANT', $seed$
{"input": {"sku_id":"1003"}, "model_output": "{\"target_sku\":\"SKU-6001\",\"requested_quantity\":null,\"inventory\":{\"available\":\"0\",\"shortage\":\"6\"},\"candidates\":[],\"excluded_candidates\":[{\"provider_code\":\"P001\",\"price\":\"12.34\",\"price_basis\":\"provider_sku\",\"note\":\"映射已停用\",\"exclusion_reason\":\"mapping_stale\",\"exclusion_reason_detail\":\"映射已停用\"},{\"provider_code\":\"P002\",\"price\":\"12.90\",\"price_basis\":\"provider_sku\",\"note\":\"映射已过期\",\"exclusion_reason\":\"mapping_stale\",\"exclusion_reason_detail\":\"映射已过期\"}],\"recommendation\":{\"provider_code\":\"P001\",\"reason\":\"x\"},\"missing_fields\":[],\"confidence\":0.85,\"requires_human\":false}"}$seed$::jsonb, $seed$
{"requires_human":true,"write_tool_calls":0,"missing_fields_contains":"candidates"}$seed$::jsonb, 'CONFIRMED', 'system:v34-seed', 'system:v34-seed', CURRENT_TIMESTAMP),
    ($seed$procurement-price-agent$seed$, 2, $seed$procurement-eval-v2$seed$, $seed$mapping-stale-candidate-excluded$seed$, 'INVARIANT', $seed$
{"input": {"sku_id":"1001"}, "model_output": "{\"target_sku\":\"SKU-1001\",\"requested_quantity\":null,\"inventory\":{\"available\":\"5\",\"shortage\":\"0\"},\"candidates\":[{\"provider_code\":\"P001\",\"price\":\"12.34\",\"price_basis\":\"sku_commercial_price\",\"note\":\"主数据进货价\"}],\"excluded_candidates\":[{\"provider_code\":\"P002\",\"price\":\"12.90\",\"price_basis\":\"provider_sku\",\"note\":\"履约方映射已停用\",\"exclusion_reason\":\"mapping_stale\",\"exclusion_reason_detail\":\"映射已停用\"}],\"recommendation\":{\"provider_code\":\"P001\",\"reason\":\"唯一可比候选\"},\"missing_fields\":[],\"confidence\":0.85,\"requires_human\":false}"}$seed$::jsonb, $seed$
{"requires_human":false,"write_tool_calls":0}$seed$::jsonb, 'CONFIRMED', 'system:v34-seed', 'system:v34-seed', CURRENT_TIMESTAMP),
    ($seed$procurement-price-agent$seed$, 2, $seed$procurement-eval-v2$seed$, $seed$price-missing-candidate-excluded-forces-human$seed$, 'INVARIANT', $seed$
{"input": {"sku_id":"1004"}, "model_output": "{\"target_sku\":\"SKU-7001\",\"requested_quantity\":null,\"inventory\":{\"available\":\"5\",\"shortage\":\"0\"},\"candidates\":[{\"provider_code\":\"P001\",\"price\":\"12.34\",\"price_basis\":\"sku_commercial_price\",\"note\":\"主数据进货价\"},{\"provider_code\":\"P002\",\"price\":\"12.90\",\"price_basis\":\"provider_sku\",\"note\":\"履约方映射价格\"}],\"excluded_candidates\":[{\"provider_code\":\"P003\",\"price\":null,\"price_basis\":\"provider_sku\",\"note\":\"未定价\",\"exclusion_reason\":\"price_missing\",\"exclusion_reason_detail\":\"无可用价格\"}],\"recommendation\":{\"provider_code\":\"P001\",\"reason\":\"x\"},\"missing_fields\":[],\"confidence\":0.8,\"requires_human\":false}"}$seed$::jsonb, $seed$
{"requires_human":true,"write_tool_calls":0,"missing_fields_contains":"price"}$seed$::jsonb, 'CONFIRMED', 'system:v34-seed', 'system:v34-seed', CURRENT_TIMESTAMP),
    ($seed$procurement-price-agent$seed$, 2, $seed$procurement-eval-v2$seed$, $seed$recommendation-on-excluded-candidate-forces-human$seed$, 'INVARIANT', $seed$
{"input": {"sku_id":"1005"}, "model_output": "{\"target_sku\":\"SKU-8001\",\"requested_quantity\":null,\"inventory\":{\"available\":\"5\",\"shortage\":\"0\"},\"candidates\":[{\"provider_code\":\"P001\",\"price\":\"12.34\",\"price_basis\":\"sku_commercial_price\",\"note\":\"主数据进货价\"},{\"provider_code\":\"P002\",\"price\":\"12.90\",\"price_basis\":\"provider_sku\",\"note\":\"履约方映射价格\"}],\"excluded_candidates\":[{\"provider_code\":\"P003\",\"price\":\"45.67\",\"price_basis\":\"provider_sku\",\"note\":\"渠道报价异常高\",\"exclusion_reason\":\"price_outlier\",\"exclusion_reason_detail\":\"偏离中位数\"}],\"recommendation\":{\"provider_code\":\"P003\",\"reason\":\"x\"},\"missing_fields\":[],\"confidence\":0.9,\"requires_human\":false}"}$seed$::jsonb, $seed$
{"requires_human":true,"write_tool_calls":0,"missing_fields_contains":"recommendation"}$seed$::jsonb, 'CONFIRMED', 'system:v34-seed', 'system:v34-seed', CURRENT_TIMESTAMP);
