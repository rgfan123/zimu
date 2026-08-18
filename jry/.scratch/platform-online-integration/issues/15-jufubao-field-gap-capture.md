# 15 — 聚福宝收货人与字典补抓（HITL，09 的 blocker）

> 本票把原 D4「用户 10min 待办」升格为有验收的 blocker 票——红队评审 §4.1 的结论：缺收货人 → 每行 NEED_REVIEW（parser 必填校验）→ confirm 被 blocker 拒 → **09 的端到端验收永远过不了**。

**这是 HITL 票**：Agent 代不了，需真人登录 `g.jufubao.cn` 点开订单详情与发货表单并抓包；Agent 负责拿到 HAR 后解析并写契约文档。

**需要用户做的事：**
1. DevTools 开 Preserve log，登录聚福宝供应商后台
2. 打开一个待发货订单的**详情页**（触发 `sub-order-info`）
3. 点开该订单的**发货表单**（触发 `multi-send-form` 与 `/order-public/v1/logistics-company/options`）——**只打开表单，不要真提交发货**
4. 导出 HAR 到 `data-local/`（gitignored），告知路径

> ⚠️ HAR 含明文密码与有效会话，勿外传、勿提交。

**要确认的四个缺口：**

| # | 缺口 | 影响 |
|---|---|---|
| 1 | **收货人字段的准确 JSON path**（姓名/电话/地址，在 `sub-order-info` 或 `multi-send-form`） | 硬性成功条件。拿不到 09 验收不可能通过 |
| 2 | **物流公司字典**：`logistics-company/options` 完整快照（`company_id` ↔ 名称） | 11 的 `multi-send` 报文必填项 |
| 3 | **订单状态枚举全集**（现仅确认 `NO_DELIVERY`；需 `delivered`/`all` 两 tab 下全部取值，特别是取消态） | 14 消失检测的原因码粒度取决于它 |
| 4 | idaas **refresh 端点**（access ~12.8h / refresh 15 天） | 抓不到不算失败——每次完整登录成本很低，可退回每次登录 |

**额外必须留意：** `sub-order-info` 是**逐单调用还是能批量取**？若逐单，拉 20 单就是 20 次请求，会顶到 13 的「每日 ≤2 次拉取」合规节奏，直接影响 09 的实现方式。

**Blocked by:** None — can start immediately（三张 HITL 票里这张最急）

**Status:** needs-user

- [ ] 收货人姓名/电话/地址字段路径已确认并写入 `jufubao-supplier-export-api.md` §5.2
- [ ] 物流公司字典快照入库
- [ ] 订单状态枚举全集已补全（或明确记为「未观察到」）
- [ ] `sub-order-info` 调用粒度已确认（逐单 / 批量）
- [ ] refresh 端点结论已记录（含「未观察到」这一结论）
