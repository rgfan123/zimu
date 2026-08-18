# 01 — 为 SKU 增加进货价与零售价

**What to build:** 商品运营人员能够在 SKU 主数据中查看和维护进货价、零售价；未知价格明确显示为未定价，不与 0 元混淆。

**Blocked by:** None — can start immediately

**Status:** resolved

**Claimed by:** codex-root

- [x] SKU 持久化模型包含可为空、非负、两位小数的进货价和零售价，API 以 decimal string 表达。
- [x] SKU 新建、编辑、查询和乐观锁覆盖两个价格字段，非法金额返回可诊断错误。
- [x] SKU 管理页可查看和编辑两种价格；空值显示“未定价”，不会暗中填 0。
- [x] 迁移、schema 镜像、OpenAPI、真实 PostgreSQL API 测试和前端公开 helper 测试一致。

## Answer

已完成本地实现与 Standards/Spec 双轴终审；两轴均无 P0–P2 发现，本票本地验收项全部有证据，状态设为 `resolved`。

- V13 为 `app.skus` 增加可空 `NUMERIC(14,2)` 进货价/零售价及非负约束，`docs/schema.sql` 已同步；未定价持久化和返回为 `null`，0 元返回为 `"0.00"`。
- SKU POST/PATCH 只接受最多 12 位整数、2 位小数的非负 decimal string；数值 JSON token、负数、超小数位和溢出会返回 `INVALID_COMMERCIAL_PRICE` 及字段诊断。PATCH 区分未传字段与显式 `null` 清空，仍经过 `expected_version` 和幂等键。
- SKU 管理页已增加进货价/零售价列与新建、编辑字段；空值显示“未定价”，明示 0 显示 `¥0.00`。
- TDD 证据：后端红测 2/2 分别因价格字段缺失和非法值被返回 201 而失败；实现后 `MasterDataApiTest,SkuCommercialPriceApiTest` 真实 PostgreSQL + HTTP 4/4 通过，包含 CRUD、分页读取、null/0 区分、清空幂等重放、版本冲突和非法金额。
- 前端公开 helper 在最终快照复核中 4/4 通过，`npm run typecheck` 通过；既有前端全量 86/86、OpenAPI YAML/引用校验和 `git diff --check` 证据也均通过。
- 最终 Standards 复核覆盖 V13/schema/JPA 金额约束、decimal-string 边界、PATCH presence 语义、幂等/乐观锁、OpenAPI 与前端类型；Spec 复核逐条核对本票四项 AC，均未发现 P0–P2。
- 将权威目录实际导入后再验证“27 个有价、34 个空价”属于 Catalog02 的 compose/runtime 验收步骤，不是本票价格字段实现的遗留缺陷。
- 未运行浏览器/Playwright，遵守当前明示禁止。未 stage、commit 或 push。
