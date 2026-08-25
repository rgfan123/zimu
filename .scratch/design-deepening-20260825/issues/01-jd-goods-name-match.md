# 01 — 京东商品名比对内核归一（P0）

**What to build:** 收拢 `sku` 包内两份逐字节相同的名称比对内核，消除核对与门禁
对「无参照名」一静默一警示的 advisory 口径矛盾；无参照名从静默放行改为显性提示。

**Blocked by:** 无
**Status:** 内核 + 核对侧提示已随本效力落地（分支 `claude/design-deepening-20260825`）；
剩余一个产品问题待拍板。

## 背景

- `JdSkuMappingCheckService.nameMatches`（advisory 核对）：参照 = `skus.specification`
  + `external_codes.provider_sku_name`；**无参照名 → true（放行）**。
- `ShipmentJdSkuMappingGateService.nameMatches`（提交门禁）：参照 =
  `order_lines.product_name_snapshot` + `external_codes.provider_sku_name`；
  **无参照名 → false → 计入 NAME_MISMATCH 警示**。评审核实（2026-08-25）：
  名称比对在门禁侧只进 warnings、从不阻断提交——阻断只由 issues（映射缺失/
  商品失效等）决定，既有回归测试
  `reportsNameMismatchAsWarningWithoutBlockingOrRewritingTheMapping` 钉死。
- normalize/token 两份逐字节复制；比对规则（去空白 + equals/双向 contains）相同。
- 后果：specification 命中而商品名快照不命中、或两参照全空时，核对页沉默、
  门禁出警示——两条 advisory 口径互相矛盾，运营无从判断哪边可信；且双份内核
  随时可能各自漂移。

## 已落地

- 新增 `sku/JdGoodsNameMatch`：normalize + 相互包含判定 + 三值裁决
  `MATCHED/MISMATCHED/NO_REFERENCE`，两个调用方全部改为消费内核；
- 门禁语义零变化（NO_REFERENCE 仍视为不命中，继续只出 NAME_MISMATCH 警示、
  不阻断提交）；
- 核对侧 NO_REFERENCE 不再静默放行，产出 `NAME_REFERENCE_MISSING` 差异项，
  提示补 `external_codes.provider_sku_name`（这是门禁也读的字段，补齐后两侧一致）。

## 剩余（需产品拍板）

参照字段集是否统一：门禁要不要也认 `specification`？风险：规格字符串短
（如「500g*10 袋」）+ contains 规则可能误判一致，让 NAME_MISMATCH 警示失去
提醒作用。建议维持现状（门禁只认商品名快照 + provider_sku_name），由
NAME_REFERENCE_MISSING 提示驱动运营补 provider_sku_name。另一个待议题：
名称不符要不要从警示升级为阻断（今天两侧都只是 advisory）——这是产品决定，
不随本票改。
