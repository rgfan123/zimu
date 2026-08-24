-- 履约单据 Agent（Excel 闭环：收表 → 发货 → 回填 → 回传）。
--
-- 定位红线：**只读解读，不执行任何业务动作**。发货、回填、回传全部由既有确定性服务执行，
-- 本 Agent 的工具白名单里没有任何写工具，allow_write=false。
-- 四段进度由 get_import_batch_progress 用 SQL 算出后交给模型，模型不数数——
-- 让模型去数「发了几单」，错了没人能复现，也没法回归。

INSERT INTO app.agent_definitions (
    agent_slug, name, description, system_prompt, prompt_version, model_ref,
    enabled, version, status, activated_by, activated_at, allow_write,
    guard_exemptions, output_schema, tool_whitelist)
VALUES (
    'fulfillment-file-agent',
    '履约单据 Agent',
    '读取一个导入批次在收表/发货/回填/回传四段链路上的进度与阻塞事实，输出人话解读与建议后续动作；不执行任何发货、回填或回传动作。',
    $$你是履约单据 Agent（只读，绝不触发任何写操作）。你的职责：把一个导入批次在「收表 → 发货 → 回填 → 回传」四段链路上的当前事实，翻译成运营人员能直接行动的解读与建议。

输入是结构化 JSON：{"import_batch_id": "..."}。

工具调用序列：
1. 先调 get_import_batch_progress 拿到四段进度与阻塞分组。这一步是必须的，且通常一次就够——四段事实已经一次取全，不要为了同一批次反复调用；
2. 仅当阻塞里出现你无法解释的 reason_code 时，调 get_review_case 查看一条样本；
3. 仅当阻塞与库存有关时，调 get_inventory_overview 佐证。

硬性纪律：
- 四段的数字**以 get_import_batch_progress 返回的为准**，你不得自行推算、累加或修正。工具没给的数字就是没有；
- 某段 supported=false 表示该段对这批不适用（例如复核未过就不会有发货单）。此时必须说「该段暂不适用」，**不得**说成「0 单待处理」——这两者对运营是完全不同的行动信号；
- 你只能建议，不能执行。建议里不得出现「我已经…」这类表述；
- 任何需要选客户、选 SKU、填数量、确认金额的动作，一律建议「去后台处理」并给出可搜索的业务号，不要把它描述成一步可完成的操作；
- 事实不足以判断下一步时，requires_human=true 并在 missing_fields 写清缺什么。

输出严格遵守 output_schema：
- summary：两三句话说清这批到哪一步、卡在什么；
- stage_notes：逐段一句话，未接入的段位如实写明；
- suggested_actions：每条含 action（做什么）、reason（依据哪条事实）、target_no（可去后台搜的业务号，没有则省略）；
- requires_human：只要有任何一段需要人工判断即为 true。$$,
    'fulfillment-file-v1',
    'app.agent',
    true, 1, 'active', 'system', CURRENT_TIMESTAMP, false,
    '[]'::jsonb,
    $${
      "type": "object",
      "additionalProperties": false,
      "required": ["batch_no", "summary", "stage_notes", "suggested_actions", "requires_human"],
      "properties": {
        "batch_no": {"type": "string"},
        "current_stage": {"type": ["string", "null"]},
        "summary": {"type": "string"},
        "stage_notes": {
          "type": "array",
          "items": {
            "type": "object",
            "additionalProperties": false,
            "required": ["stage", "note"],
            "properties": {
              "stage": {"type": "string"},
              "note": {"type": "string"}
            }
          }
        },
        "suggested_actions": {
          "type": "array",
          "items": {
            "type": "object",
            "additionalProperties": false,
            "required": ["action", "reason"],
            "properties": {
              "action": {"type": "string"},
              "reason": {"type": "string"},
              "target_no": {"type": ["string", "null"]}
            }
          }
        },
        "requires_human": {"type": "boolean"},
        "missing_fields": {"type": "array", "items": {"type": "string"}}
      }
    }$$::jsonb,
    '["get_import_batch_progress","get_review_case","list_review_cases","get_inventory_overview","search_skus"]'::jsonb);

-- INVARIANT 评测用例：确定性门禁，跑 stub 评分器，不进真实模型。
-- 三条覆盖三个最容易做错的判定：必须先调进度工具、未接入段不得说成 0、缺事实时转人工。
INSERT INTO app.agent_eval_cases (
    agent_slug, agent_version, metric_kind, input, expected, status, created_by, confirmed_by, confirmed_at)
VALUES
    ('fulfillment-file-agent', 1, 'INVARIANT',
     '{"import_batch_id":"7001"}'::jsonb,
     '{"requires_human":false,"tool_sequence":["get_import_batch_progress"]}'::jsonb,
     'CONFIRMED', 'system', 'system', CURRENT_TIMESTAMP),
    ('fulfillment-file-agent', 1, 'INVARIANT',
     '{"import_batch_id":"7002"}'::jsonb,
     '{"requires_human":true,"missing_fields":["blockers"]}'::jsonb,
     'CONFIRMED', 'system', 'system', CURRENT_TIMESTAMP),
    ('fulfillment-file-agent', 1, 'INVARIANT',
     '{}'::jsonb,
     '{"expected_error":"INVALID_PARAMETERS"}'::jsonb,
     'CONFIRMED', 'system', 'system', CURRENT_TIMESTAMP);
