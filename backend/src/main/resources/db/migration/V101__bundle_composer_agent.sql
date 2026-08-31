-- 组包师 Agent（用户 2026-08-31 需求）：按预期售价/毛利目标/运费/仓储费等费用条件
-- 与库存偏好，整理礼包方案交人确认。
--
-- 定位红线：**一期纯提案制，allow_write=false**——方案只是建议，创建礼包由人在后台
-- 执行（二期接 bundles-write 后仍需人工确认触发）。所有金额算术必须来自
-- estimate_bundle_economics 工具（BigDecimal 精确核算，口径公开），模型不心算——
-- 心算错一个毛利率没人能复现，也没法回归。

INSERT INTO app.agent_definitions (
    agent_slug, name, description, system_prompt, prompt_version, model_ref,
    enabled, version, status, activated_by, activated_at, allow_write,
    guard_exemptions, output_schema, tool_whitelist)
VALUES (
    'bundle-composer-agent',
    '组包师 Agent',
    '按预期售价、毛利目标、运费/仓储费等费用条件与库存偏好，从成本档案与库存事实出发整理礼包方案（组件清单+经济账+短板提示+备选），交人确认；不创建任何礼包，不执行任何写操作。',
    $$你是组包师 Agent（只读提案制，绝不触发任何写操作）。你的职责：根据用户给出的商业条件（预期售价、毛利目标、运费、仓储费、其他费用）与偏好（品类倾向、件数、库存要求），组合出可执行的礼包方案，把账算清楚交给人确认。

工作序列：
1. 条件不全先问清：预期售价与毛利目标至少要有其一；运费/仓储费/其他费用缺省按 0 并在方案里注明「按 0 计」；
2. 选组件：用 find_bundle_candidates / search_skus 找启用 SKU（含供货价、履约方、库存观测）；用 search_product_archive 佐证成本与状态（停产/在产）；偏好里提到库存时用 get_inventory_overview / get_inventory_detail 取观测事实；
3. 算账：把候选组件清单交给 estimate_bundle_economics 算成本/毛利/毛利率。这一步是强制的——**你自己不得做任何加减乘除**，方案里出现的每个金额都必须原样来自该工具的返回；换组件、改数量后必须重新调用重算；
4. 出方案：主方案一套 + 至多两套备选（如毛利不达标时的降本替代、库存更充足的替代）。

硬性纪律：
- 毛利不达标就直说不达标，给出差距数字与替代路径，不得为凑数篡改任何输入；
- estimate_bundle_economics 返回 computable=false（组件缺供货价）时，如实列出缺价 SKU 并建议先补档案价，不得跳过缺价组件硬算；
- 库存判断只用工具返回的观测事实；无观测就说「无库存观测」，不得说成 0 或猜测充足；
- 你只能提案，不能创建。输出里不得出现「我已创建/已生成礼包」这类表述；确认后的创建动作由人到后台「主数据 → 静态礼包」执行；
- 停用/停产组件出现在候选中时必须在 warnings 里点名，不得静默采用。

输出严格遵守 output_schema：
- summary：两三句话说清方案定位与经济结论（毛利率是否达标）；
- proposal：组件明细与经济账（全部数字来自 estimate_bundle_economics 返回）；
- alternatives：备选方案摘要（可空）；
- inventory_notes：库存短板/无观测事实提示（可空）；
- requires_confirmation：恒为 true；confirmation_hint 写明确认后去哪里创建。$$,
    'bundle-composer-v1',
    'app.agent',
    true, 1, 'active', 'system', CURRENT_TIMESTAMP, false,
    '[]'::jsonb,
    $${
      "type": "object",
      "additionalProperties": false,
      "required": ["summary", "proposal", "alternatives", "inventory_notes", "requires_confirmation", "confirmation_hint"],
      "properties": {
        "summary": {"type": "string"},
        "proposal": {
          "type": "object",
          "additionalProperties": false,
          "required": ["bundle_name", "components", "economics"],
          "properties": {
            "bundle_name": {"type": "string"},
            "components": {
              "type": "array",
              "items": {
                "type": "object",
                "additionalProperties": false,
                "required": ["sku_id", "sku_code", "product_name", "quantity", "unit_purchase_price", "line_cost"],
                "properties": {
                  "sku_id": {"type": "string"},
                  "sku_code": {"type": "string"},
                  "product_name": {"type": "string"},
                  "quantity": {"type": "integer", "minimum": 1},
                  "unit_purchase_price": {"type": ["string", "null"]},
                  "line_cost": {"type": ["string", "null"]}
                }
              }
            },
            "economics": {
              "type": "object",
              "additionalProperties": false,
              "required": ["expected_price", "component_cost", "freight_fee", "storage_fee", "other_fee", "total_cost", "gross_margin", "gross_margin_rate"],
              "properties": {
                "expected_price": {"type": "string"},
                "component_cost": {"type": ["string", "null"]},
                "freight_fee": {"type": "string"},
                "storage_fee": {"type": "string"},
                "other_fee": {"type": "string"},
                "total_cost": {"type": ["string", "null"]},
                "gross_margin": {"type": ["string", "null"]},
                "gross_margin_rate": {"type": ["string", "null"]}
              }
            }
          }
        },
        "alternatives": {
          "type": "array",
          "items": {
            "type": "object",
            "additionalProperties": false,
            "required": ["name", "note"],
            "properties": {
              "name": {"type": "string"},
              "note": {"type": "string"}
            }
          }
        },
        "inventory_notes": {"type": "array", "items": {"type": "string"}},
        "requires_confirmation": {"type": "boolean", "const": true},
        "confirmation_hint": {"type": "string"}
      }
    }$$,
    '["search_skus", "get_sku", "search_product_archive", "get_inventory_overview", "get_inventory_detail", "list_bundles", "get_bundle", "find_bundle_candidates", "estimate_bundle_economics"]'::jsonb);
