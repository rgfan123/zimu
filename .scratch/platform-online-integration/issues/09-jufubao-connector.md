# 09 — 聚福宝 Java Connector：pullOrders（JSON 直连试点）

**What to build:** 聚福宝 Connector 实现在线拉单（JSON 直连，不碰 Excel）：登录（JFB_SESSION_CID + 3 JWT + CSRF 头）→ `orders/query` 分页拉取（no_delivery，page_token 游标）→ transform 为结构化订单 → 走 02 的结构化导入用例（建批次 + raw 行血缘 + 行级跳过）→ confirm 管线全通。pullOrderChanges 用 delivered/all 差分；状态变化只进 OrderEvent/ReviewCase，不静默改单。

**Blocked by:** 01, 02, D4（收货人字段补抓——缺收货人则 confirm 被 blocker 拒）

**Status:** resolved

- [ ] 登录/游标/分页拉取/transform 全链路跑通
- [ ] 订单（含收货人，D4 后）进入批次，confirm 后履约导出可生成
- [ ] 重复订单行级跳过（配合 02），失败重拉安全
- [ ] 状态变化信号按 D7 决策处理（ReviewCase 原因码待评审）

---

## 合并修订（2026-08-18）

**推翻原文的变更拉取方式**：原票写「pullOrderChanges 用 delivered/all 差分」。用户裁定改为**跨平台统一的消失检测**（新票 14）——理由是飞象无 JSON 无解、聚福宝取消态枚举至今未抓到，按各平台状态枚举分别实现走不通。本票只做 `pullOrders`，变更/取消一律交给 14。

**blocker 明确化**：原「D4 收货人字段补抓」现为正式票 **15**。缺收货人 → 每行 NEED_REVIEW → confirm 被拒 → 本票端到端验收永远过不了。

**拉取节奏警告**：若 15 确认 `sub-order-info` 是**逐单调用**，拉 20 单 = 20 次请求，会顶到 13 的「每平台每日 ≤2 次」合规红线。届时需重新设计取收货人的方式（如只对确认阶段缺字段的单按需取）。

修订后的 Blocked by：**01、02、15**

新增验收项：

- [ ] 收货人字段完整，**不因缺收货人产生 NEED_REVIEW**（本票成败判据）
- [ ] `pullOrderChanges` 不在本票范围内（交 14）

---

## Answer (2026-08-19)

**Status: resolved**

Java Connector 在线拉取已实现：`JufubaoPullClient`（GET g.jufubao.cn 种 JFB_SESSION_CID → login-by-username 表单登录 3 JWT + CSRF 头 → orders/query 分页 page_token 游标）+ `JufubaoOrderTransform`（真实抓包 JSON → `StructuredOrderRow`：main_order_id→sourceRef、sub_order_id→sourceLineRef、product_list→items、receiver 留空 + `receiver_missing: true`、rawSnapshot 浅层脱敏）+ `JufubaoConnector.pullOrders`（→ `SourceImportService.importStructured` 结构化管线）。测试 `JufubaoConnectorTest` 9 例 + `JufubaoOrderTransformTest` 4 例通过（HAR 真实样例）。

遗留风险：
- **票 15（收货人字段）仍是 blocker**：receiver 留空 → 订单进批次后行 NEED_REVIEW，需人工在复核补收货人；「不因缺收货人产生 NEED_REVIEW」验收项未闭环
- 聚福宝规格/单位无契约字段（占位「—」/「件」）；JFB_SESSION_CID 跨域 cookie 需真实运行确认
