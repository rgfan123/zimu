# 04 — MCP 暴露"刷新平台订单→展示→确认→触发发货"闭环工具

**Type:** implementation

**What to build:** 授权 Agent 通过 MCP 完成「刷新订单」业务闭环：从三个来源平台（彩食鲜 / 聚福宝 / 飞象）拉取订单（复用现有拉取编排），拉取结果以导入批次形态展示给用户确认，确认后触发发货（履约导出生成，复用既有批次确认应用用例）；回填与回传沿用既有链路，本票不新增。

三个 MCP 工具：

1. **`refresh_platform_orders`**（写）——触发三平台订单拉取编排（复用 `PlatformOrderRefreshService.refresh`：脚本通道 + 内容哈希幂等建 NEW 导入批次 + 审计；单渠道失败不阻断），返回各渠道结果（批次 id / 识别订单数 / 重复跳过数 / 失败原因）。`actorType=SYSTEM` 语义沿用现有拉取编排。
2. **`list_import_batches` / `get_import_batch`**（读）——展示批次摘要与批次内订单，供用户在企微对话中确认。复用 `SourceImportController` 背后的查询应用层（批次、订单行），不直写业务表。
3. **`confirm_import_batch`**（写）——确认导入批次（复用现有批次确认应用用例与幂等 scope），返回 `outbound_routing` / 履约导出 id；**确认主体为企微对话中发起确认的用户**（人工闸门保留，不自动确认），确认后既有链路继续：履约导出（发货）→ 第三方回传物流单号（T3）→ 来源回填（SourceReturnExport）→ 回传 Sync（三平台 Connector）。

**Blocked by:** 一期（脚本通道）**无阻塞**，可直接开工。
在线化替换部分 **blocked by** `platform-online-integration` 07 / 08 / 09（三平台在线拉取 Connector，均 ready-for-agent 未实现）——只影响把 `PlatformOrderRefreshService` 内部实现换成在线拉取，不影响本票一期交付；07/08/09 完成后替换内部实现，MCP 工具面不变。

**Status:** open
**GitHub:** https://github.com/rgfan123/zimu/issues/24

## 边界与既有约束（platform-online-integration 合并裁决，不得违反）

- **人工确认闸门保留**：`confirm_import_batch` 只在用户明确发起时被调用；不做自动确认。自动确认开关（按渠道）保持默认关闭。
- **确认仍是人工主体**：refresh 拉取与建批次为 SYSTEM 主体（沿用），confirm 审计必须记录发起确认的 Agent 身份与对话上下文，不得伪造 operator。
- 聚福宝当前脚本通道缺收货人字段（票 15 blocker）——refresh 返回中聚福宝可能无批次或仅报告拉取数量，工具输出须如实呈现，不假装成功。
- MCP 写工具沿用现有 `McpWriteTools` 的 `executeWrite` 模式：Agent 身份 + 幂等键 + 期望版本 + AuditLog。
- Connector 禁止直接写业务表；所有动作走应用层用例（`PlatformOrderRefreshService` / `SourceImportService`）。

## 验收项

- [ ] tools/list 出现 `refresh_platform_orders` / `list_import_batches` / `get_import_batch` / `confirm_import_batch` 四个工具。
- [ ] `refresh_platform_orders` 触发后：彩食鲜/飞象各产生一个 NEW 导入批次（内容哈希幂等，重复刷新不重复建批次）；聚福宝如实报告拉取数量或缺口；单渠道失败不阻断其他渠道；SYSTEM 审计。
- [ ] `get_import_batch` 返回批次内订单摘要（订单号/会员/商品/数量/收货人），可供企微对话展示。
- [ ] `confirm_import_batch` 确认后：生成履约导出（`generated_fulfillment_export_ids` 非空）；同幂等键重放返回首次结果；版本过期/批次不满足确认条件时返回稳定业务错误码且不留半截事实。
- [ ] 确认审计记录 Agent 身份；不出现无身份或伪造 operator 的写审计。
- [ ] 回填/回传不新增 MCP 工具（复用既有链路），本票不改变 MCP 终局工具边界之外的语义。

## 待确认

- [ ] `refresh_platform_orders` 参数：channels 过滤（默认三平台）+ 日期范围（沿用 `app.platform-pull.default-days`）+ idempotency_key。
- [ ] `confirm_import_batch` 参数：现状批次确认接口无版本参数（`confirm(batchId, idempotencyKey, context)`），幂等键已保证重放收敛——**不加乐观锁**，与 REST 完全一致；返回透传 `outbound_routing`（京东路径确认后自动触发 SDK 建单，已有 jd-real-sdk-switch 05，MCP 不重复触发）。
- [ ] 企微对话中"展示订单列表"的形式：文字列表（一期）vs 模板卡片（T2/U2 落地后可选），本票先文字摘要。
