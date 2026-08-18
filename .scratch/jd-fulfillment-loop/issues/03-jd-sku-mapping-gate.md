# 03 — 建立京东 SKU 映射门禁

**Type:** implementation

**What to build:** SKU 运营人员可以在京东建单前核对当前 Shipment 的全部商品映射；缺失、停用或与京东商品事实冲突的映射会形成可处理的阻断事项，修正后可以重新验证。

**Blocked by:** 01 — 统一 Shipment 级京东出库单边界

**Status:** resolved

**Claimed by:** codex-root

- [x] 每个内部 SKU 必须存在有效的京东 goods 标识和适用的显式单位换算，才可通过门禁。
- [x] 系统使用只读京东商品查询核对映射存在性与可用状态；名称等非唯一展示字段只提示差异，不被用来猜测或自动改写映射。
- [x] 缺失、停用、无效或关键事实冲突会创建或复用一个阻断 ReviewCase，并准确列出受影响的 ShipmentItem。
- [x] 运营人员能从阻断结果进入现有 SKU 映射维护流程，修正后手动重跑核对。
- [x] 重复核对幂等，不重复创建开放 ReviewCase；核对与人工处理过程写入事件和审计。
- [x] Mock 模式覆盖全部通过、缺少映射、失效商品与非关键名称差异，不触发写接口。

## Comments

- 2026-08-13：实现与聚焦测试已收口，六项 AC 均有当前工作树的绿测证据。票据按整合流程仍保持 `claimed`，等待修复后的 Spec/Standards 双轴独立终审；未将本地 Mock/PG 成功表述为真实京东权限验证。
- 2026-08-14：按 Standards 复审补齐了外调前幂等 claim 与并发锁序硬化。同 key 同 payload 重放现在不再调用 `queryGoodsInfo`或重复写 REAL 审计；门禁持久化改为先锁 Shipment 及其明细，不再持有 CanonicalOrder 行锁等待 Shipment。OrderEvent/OrderVersion 通过同一个订单级 `pg_advisory_xact_lock` 分配序号；Tracking 同样先锁 Shipment，再更新 ShipmentItem/OrderLine。本轮自审未发现新的 P0–P2，票据仍等待独立复核。

## Answer

已完成 Shipment 级京东 SKU 映射门禁：

- `POST /api/v1/shipments/{shipmentId}/jd-sku-mapping-check` 以当前 Shipment 的全部 ShipmentItem 为边界；普通行核对行 SKU，礼包行按 `instructed_quantity × quantity_per_bundle` 核对当前批次的组件 SKU。
- 通过共享 typed `JdGoodsReadOnlyVerifier` 统一新旧 checker 的响应解析与 `enableFlag` 语义；`queryGoodsInfo` 明确携带 String `queryType="2"`。调用带事务 guard，不得在数据库事务或 Shipment 行锁内触发。
- 核对采用 prepare 无锁快照 → 幂等 claim/replay → remote 无事务查询 → persist 事务持久化。同 key 同 payload 在外调前重放已存响应；不同 payload 或执行中 claim 返回 409。持久化先锁 Shipment，再重锁并重验 ShipmentItem、OrderLine、BundleComponent、SKU 和 ProviderSku 快照/版本；查询期间发生变化时返回 `JD_SKU_MAPPING_CHANGED_DURING_CHECK` 而不写旧结果。本地业务事实与幂等 `SUCCEEDED` 在同一事务内提交；OrderEvent/OrderVersion 使用同一订单级事务 advisory lock 分配序号，不锁 CanonicalOrder 行。
- 门禁 fail closed：内部 SKU/映射缺失或停用、goodsNo 缺失、显式换算缺失/非法/不整数、JD 查询失败/商品缺失/停用、goodsNo 或 erpGoodsNo 冲突均阻断；展示名称只产生 warning，不猜测也不回写。
- 阻断创建或复用唯一开放 `JD_SKU_MAPPING_BLOCKED` ReviewCase。每次仍阻断的重跑都用白名单 detail 更新 `check_run_no`、受影响 ShipmentItems/issues 和维护定位；修复后重跑解除同一 case，终态 API 的 `allowed_actions` 为空。
- ProviderSku 公共 POST/PATCH 现支持 `merchant_sku_code`，运营人员可直接修复 `erpGoodsNo` 冲突；响应与 ReviewCase 均提供现有 SKU 映射页/API 以及重跑 API 定位。
- 同一幂等键重放不重复写 ReviewCase、OrderEvent 或审计；不同 key 仍阻断时复用并刷新同一开放 case。Mock 门禁只使用京东只读商品 seam，聚焦 PG 测试确认没有 `orderSoCreate` 审计事实。

当前工作树验证证据（2026-08-13）：

- `mvn -DskipTests test-compile`：exit 0，`BUILD SUCCESS`，276 个 main + 44 个 test source 编译通过。
- `JdGoodsReadOnlyVerifierTest`：1/1；`JdSkuMappingCheckServiceTest`：7/7；`ShipmentJdSkuMappingGateApiTest`：9/9，共 17/17，0 failure / 0 error。最终 PG 聚焦命令 exit 0、`BUILD SUCCESS`。
- PG 公共 seam 覆盖：全部通过/幂等重放、礼包部分批次数量、换算修复后解除阻断、缺失映射→停用映射时 detail 刷新、失效商品、名称 warning、`merchant_sku_code` 创建/修复 erpGoodsNo 冲突、terminal actions 为空，以及同订单双 Shipment 并发事件序号唯一。

并发/幂等硬化后的增量验证证据（2026-08-14，未运行全量）：

- `mvn -DskipTests test-compile`：exit 0，`BUILD SUCCESS`。
- `ReadOnlyExternalIdempotencyApiTest`：2/2，0 failure / 0 error；真实 PostgreSQL 验证 replay 与 different-payload/in-progress 冲突均在外调前截断，REAL-mode `queryGoodsInfo` 审计只写一次。
- `ShipmentJdSkuMappingGateApiTest` 的 3 个硬化用例：3/3，0 failure / 0 error；覆盖同 key 重放 0 次额外 JD 查询、不同 Shipment 复用 key 在外调前 409，以及真实 PostgreSQL 下 gate×Tracking 并发无死锁且事实守恒。

独立终审已完成：Spec 轴与 Standards 轴均为 P0/P1/P2/P3 = 0。审查确认最新外调前幂等 claim、Shipment-first 锁序、共享订单 advisory append lock、快照变化 fail-closed 以及重放不重复 JD 查询/审计均成立。因此本地 Mock + 真实 PostgreSQL 范围可标记为 `resolved`；这不代表真实京东权限或生产环境验收。
